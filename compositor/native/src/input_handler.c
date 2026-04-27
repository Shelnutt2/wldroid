/*
 * input_handler.c — Android input event forwarding to wlroots seat
 *
 * Architecture:
 *   JNI thread → SPSC ring buffer + pipe byte → compositor event loop
 *                                                → wlroots seat/keyboard APIs
 *
 * The ring buffer is lock-free (single producer, single consumer) using
 * __atomic builtins for the head/tail indices.  A single pipe byte wakes
 * the wl_event_loop fd callback which drains all queued events.
 */
#define _GNU_SOURCE
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <fcntl.h>
#include <math.h>
#include <android/log.h>

#include <wayland-server-core.h>
#include <wlr/interfaces/wlr_keyboard.h>
#include <wlr/types/wlr_keyboard.h>
#include <wlr/types/wlr_seat.h>
#include <wlr/types/wlr_scene.h>
#include <wlr/util/log.h>
#include <xkbcommon/xkbcommon.h>

#include "compositor_server.h"
#include "input_handler.h"
#include "keycode_map.h"

#define LOG_TAG "InputHandler"
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

#define SCROLL_PIXELS_PER_NOTCH 15.0

/* ------------------------------------------------------------------ */
/* Ring buffer (SPSC, lock-free)                                       */
/* ------------------------------------------------------------------ */

#define INPUT_RING_SIZE 256 /* must be power of 2 */
#define INPUT_RING_MASK (INPUT_RING_SIZE - 1)

enum input_event_type {
    INPUT_KEY,
    INPUT_TOUCH_DOWN,
    INPUT_TOUCH_MOTION,
    INPUT_TOUCH_UP,
    INPUT_POINTER_MOTION,
    INPUT_POINTER_BUTTON,
    INPUT_POINTER_SCROLL,
};

struct input_event {
    enum input_event_type type;
    uint32_t timestamp_msec;
    union {
        struct { uint32_t linux_keycode; int pressed; } key;
        struct { int32_t id; double x, y; } touch;
        struct { double x, y; } pointer_motion;
        struct { uint32_t button; int pressed; } pointer_button;
        struct { double dx, dy; } pointer_scroll;
    };
};

/*
 * NOTE: These static globals restrict the input handler to a single
 * compositor instance per process.  This is acceptable because Android
 * runs each activity in a single process and we only ever create one
 * compositor at a time.  If multiple instances are ever needed, embed
 * the ring buffer in struct compositor_server instead.
 */
static struct input_event ring[INPUT_RING_SIZE];
static unsigned int ring_head; /* producer (JNI thread) */
static unsigned int ring_tail; /* consumer (compositor thread) */

/**
 * Enqueue an event.  Returns a pointer to the slot to fill, or NULL if full.
 * Caller must fill the slot, then call ring_commit().
 */
static struct input_event *ring_reserve(void) {
    unsigned int head = __atomic_load_n(&ring_head, __ATOMIC_RELAXED);
    unsigned int tail = __atomic_load_n(&ring_tail, __ATOMIC_ACQUIRE);
    if (((head + 1) & INPUT_RING_MASK) == (tail & INPUT_RING_MASK)) {
        return NULL; /* full */
    }
    return &ring[head & INPUT_RING_MASK];
}

static void ring_commit(void) {
    unsigned int head = __atomic_load_n(&ring_head, __ATOMIC_RELAXED);
    __atomic_store_n(&ring_head, (head + 1) & INPUT_RING_MASK,
                     __ATOMIC_RELEASE);
}

static struct input_event *ring_peek(void) {
    unsigned int tail = __atomic_load_n(&ring_tail, __ATOMIC_RELAXED);
    unsigned int head = __atomic_load_n(&ring_head, __ATOMIC_ACQUIRE);
    if (tail == head) {
        return NULL; /* empty */
    }
    return &ring[tail & INPUT_RING_MASK];
}

static void ring_consume(void) {
    unsigned int tail = __atomic_load_n(&ring_tail, __ATOMIC_RELAXED);
    __atomic_store_n(&ring_tail, (tail + 1) & INPUT_RING_MASK,
                     __ATOMIC_RELEASE);
}

/* ------------------------------------------------------------------ */
/* Pipe wake helper                                                    */
/* ------------------------------------------------------------------ */

static void wake_compositor(struct compositor_server *server) {
    char c = 'i';
    (void)write(server->input_pipe[1], &c, 1);
}

/* ------------------------------------------------------------------ */
/* Scene hit-test helper                                               */
/* ------------------------------------------------------------------ */

static struct wlr_surface *surface_at(struct compositor_server *server,
                                       double lx, double ly,
                                       double *sx, double *sy) {
    struct wlr_scene_node *node =
        wlr_scene_node_at(&server->scene->tree.node, lx, ly, sx, sy);
    if (!node || node->type != WLR_SCENE_NODE_BUFFER) {
        return NULL;
    }
    struct wlr_scene_buffer *sbuf = wlr_scene_buffer_from_node(node);
    struct wlr_scene_surface *ssurf = wlr_scene_surface_try_from_buffer(sbuf);
    return ssurf ? ssurf->surface : NULL;
}

/* ------------------------------------------------------------------ */
/* Pointer focus tracking                                              */
/* ------------------------------------------------------------------ */

static struct wlr_surface *focused_surface = NULL;
static struct wl_listener focused_surface_destroy_listener;

static void handle_focused_surface_destroy(struct wl_listener *listener,
                                           void *data) {
    (void)listener;
    (void)data;
    focused_surface = NULL;
    wl_list_remove(&focused_surface_destroy_listener.link);
    wl_list_init(&focused_surface_destroy_listener.link);
}

/* ------------------------------------------------------------------ */
/* Event dispatch (compositor thread)                                  */
/* ------------------------------------------------------------------ */

/* Forward declaration */
static struct wlr_surface *find_touch_redirect_surface(struct compositor_server *server);
static void dispatch_key(struct compositor_server *server,
                         const struct input_event *ev) {
    if (!server->keyboard) return;

    /* Ensure keyboard focus is on the Ozone (non-rendering-only) surface.
     * The rendering-only surface skips keyboard enter in focus_toplevel,
     * so we must ensure the seat sends keys to the right client. */
    struct wlr_surface *focused = server->seat->keyboard_state.focused_surface;
    if (!focused) {
        /* No surface has keyboard focus — find and enter Ozone's surface */
        struct wlr_surface *target = find_touch_redirect_surface(server);
        if (!target) {
            /* No redirect target — try the first toplevel */
            if (!wl_list_empty(&server->toplevels)) {
                struct compositor_toplevel *ct;
                ct = wl_container_of(server->toplevels.next, ct, link);
                target = ct->toplevel->base->surface;
            }
        }
        if (target) {
            wlr_log(WLR_INFO, "dispatch_key: setting keyboard focus on Ozone surface");
            wlr_seat_keyboard_notify_enter(server->seat, target,
                server->keyboard->keycodes, server->keyboard->num_keycodes,
                &server->keyboard->modifiers);

        }
    }

    wlr_log(WLR_DEBUG, "dispatch_key: keycode=%d pressed=%d focused=%p",
            ev->key.linux_keycode, ev->key.pressed,
            (void*)server->seat->keyboard_state.focused_surface);

    uint32_t wl_state = ev->key.pressed ? WL_KEYBOARD_KEY_STATE_PRESSED
                                        : WL_KEYBOARD_KEY_STATE_RELEASED;

    /* Update keyboard internal state (keycodes, xkb, modifiers). */
    struct wlr_keyboard_key_event key_event = {
        .time_msec = ev->timestamp_msec,
        .keycode = ev->key.linux_keycode,
        .update_state = true,
        .state = wl_state,
    };
    wlr_keyboard_notify_key(server->keyboard, &key_event);

    /* Forward the key event to the focused client. wlr_keyboard_notify_key
     * only updates internal keyboard state — it does not send wl_keyboard.key
     * to clients. The seat's notify_key dispatches through the grab interface
     * and ultimately calls wl_keyboard_send_key on the focused surface. */
    wlr_seat_keyboard_notify_key(server->seat, ev->timestamp_msec,
                                  ev->key.linux_keycode, wl_state);

}

/* Helper: if a rendering-only toplevel exists, return the first
 * non-rendering-only (Ozone) toplevel's surface for touch redirect.
 * Returns NULL if no redirect is needed or no target exists. */
static struct wlr_surface *find_touch_redirect_surface(
        struct compositor_server *server) {
    bool has_rendering_only = false;
    struct compositor_toplevel *redirect_target = NULL;
    struct compositor_toplevel *ct;
    wl_list_for_each(ct, &server->toplevels, link) {
        if (ct->rendering_only)
            has_rendering_only = true;
        else if (!redirect_target)
            redirect_target = ct;
    }
    if (has_rendering_only && redirect_target)
        return redirect_target->toplevel->base->surface;
    return NULL;
}

static void dispatch_touch_down(struct compositor_server *server,
                                const struct input_event *ev) {
    double sx, sy;
    struct wlr_surface *surface =
        surface_at(server, ev->touch.x, ev->touch.y, &sx, &sy);
    if (!surface) {
        wlr_log(WLR_INFO, "dispatch_touch_down: no surface at (%.0f, %.0f)", ev->touch.x, ev->touch.y);
        return;
    }
    wlr_log(WLR_INFO, "dispatch_touch_down: surface found at (%.0f, %.0f) -> sx=%.1f sy=%.1f", ev->touch.x, ev->touch.y, sx, sy);

    /* Redirect touch from rendering-only surface to Ozone's surface.
     * Both surfaces are fullscreen at (0,0) so sx/sy stay the same. */
    struct wlr_surface *redirect = find_touch_redirect_surface(server);
    if (redirect) {
        wlr_log(WLR_INFO, "dispatch_touch_down: redirected from rendering-only to Ozone surface");
        surface = redirect;
    }

    wlr_seat_touch_notify_down(server->seat, surface, ev->timestamp_msec,
                               ev->touch.id, sx, sy);
    wlr_seat_touch_notify_frame(server->seat);

    /* Set keyboard focus on touch — but NOT when redirecting to Ozone.
     * Sending wl_keyboard.enter to Ozone's surface from the redirect
     * causes VS Code to abort (protocol conflict with its internal state). */
    if (!redirect) {
        struct wlr_keyboard *kb = server->keyboard;
        if (kb) {
            wlr_seat_keyboard_notify_enter(server->seat, surface,
                                           kb->keycodes, kb->num_keycodes,
                                           &kb->modifiers);
        }
    }

}

static void dispatch_touch_motion(struct compositor_server *server,
                                  const struct input_event *ev) {
    double sx, sy;
    struct wlr_surface *surface =
        surface_at(server, ev->touch.x, ev->touch.y, &sx, &sy);
    if (!surface) {
        wlr_log(WLR_INFO, "dispatch_touch_motion: no surface at (%.0f, %.0f)", ev->touch.x, ev->touch.y);
        return;
    }
    wlr_log(WLR_INFO, "dispatch_touch_motion: surface found at (%.0f, %.0f) -> sx=%.1f sy=%.1f", ev->touch.x, ev->touch.y, sx, sy);

    /* wlr_seat_touch_notify_motion uses the touch point's original surface
     * (set by touch_down) so we don't need to override surface here.
     * sx/sy are the coordinates that matter. */
    wlr_seat_touch_notify_motion(server->seat, ev->timestamp_msec,
                                 ev->touch.id, sx, sy);
    wlr_seat_touch_notify_frame(server->seat);
}

static void dispatch_touch_up(struct compositor_server *server,
                              const struct input_event *ev) {
    wlr_seat_touch_notify_up(server->seat, ev->timestamp_msec,
                             ev->touch.id);
    wlr_seat_touch_notify_frame(server->seat);
}

static void dispatch_pointer_motion(struct compositor_server *server,
                                    const struct input_event *ev) {
    double sx, sy;
    struct wlr_surface *surface =
        surface_at(server, ev->pointer_motion.x, ev->pointer_motion.y,
                   &sx, &sy);

    if (surface != focused_surface) {
        if (focused_surface) {
            wlr_seat_pointer_notify_clear_focus(server->seat);
            wl_list_remove(&focused_surface_destroy_listener.link);
            wl_list_init(&focused_surface_destroy_listener.link);
        }
        focused_surface = surface;
        if (surface) {
            wlr_seat_pointer_notify_enter(server->seat, surface, sx, sy);
            focused_surface_destroy_listener.notify =
                handle_focused_surface_destroy;
            wl_signal_add(&surface->events.destroy,
                          &focused_surface_destroy_listener);
        }
    }

    if (surface) {
        wlr_seat_pointer_notify_motion(server->seat, ev->timestamp_msec,
                                       sx, sy);
    }
    wlr_seat_pointer_notify_frame(server->seat);
}

static void dispatch_pointer_button(struct compositor_server *server,
                                    const struct input_event *ev) {
    wlr_seat_pointer_notify_button(
        server->seat, ev->timestamp_msec,
        ev->pointer_button.button,
        ev->pointer_button.pressed ? WL_POINTER_BUTTON_STATE_PRESSED
                                   : WL_POINTER_BUTTON_STATE_RELEASED);
    wlr_seat_pointer_notify_frame(server->seat);
}

static void dispatch_pointer_scroll(struct compositor_server *server,
                                    const struct input_event *ev) {
    /* Vertical axis */
    if (ev->pointer_scroll.dy != 0.0) {
        wlr_seat_pointer_notify_axis(
            server->seat, ev->timestamp_msec,
            WL_POINTER_AXIS_VERTICAL_SCROLL,
            ev->pointer_scroll.dy * SCROLL_PIXELS_PER_NOTCH,
            (int32_t)lround(ev->pointer_scroll.dy),
            WL_POINTER_AXIS_SOURCE_WHEEL,
            WL_POINTER_AXIS_RELATIVE_DIRECTION_IDENTICAL);
    }
    /* Horizontal axis */
    if (ev->pointer_scroll.dx != 0.0) {
        wlr_seat_pointer_notify_axis(
            server->seat, ev->timestamp_msec,
            WL_POINTER_AXIS_HORIZONTAL_SCROLL,
            ev->pointer_scroll.dx * SCROLL_PIXELS_PER_NOTCH,
            (int32_t)lround(ev->pointer_scroll.dx),
            WL_POINTER_AXIS_SOURCE_WHEEL,
            WL_POINTER_AXIS_RELATIVE_DIRECTION_IDENTICAL);
    }
    wlr_seat_pointer_notify_frame(server->seat);
}

/* ------------------------------------------------------------------ */
/* wl_event_loop fd callback                                           */
/* ------------------------------------------------------------------ */

static int on_input_event(int fd, uint32_t mask, void *data) {
    (void)mask;
    struct compositor_server *server = data;

    /* Drain the pipe. */
    char buf[64];
    while (read(fd, buf, sizeof(buf)) > 0) { /* spin */ }

    /* Dequeue and dispatch all pending events. */
    struct input_event *ev;
    while ((ev = ring_peek()) != NULL) {
        switch (ev->type) {
        case INPUT_KEY:             dispatch_key(server, ev); break;
        case INPUT_TOUCH_DOWN:
            wlr_log(WLR_INFO, "on_input_event: TOUCH_DOWN id=%d x=%.0f y=%.0f", ev->touch.id, ev->touch.x, ev->touch.y);
            dispatch_touch_down(server, ev);
            break;
        case INPUT_TOUCH_MOTION:
            wlr_log(WLR_INFO, "on_input_event: TOUCH_MOTION id=%d x=%.0f y=%.0f", ev->touch.id, ev->touch.x, ev->touch.y);
            dispatch_touch_motion(server, ev);
            break;
        case INPUT_TOUCH_UP:
            wlr_log(WLR_INFO, "on_input_event: TOUCH_UP id=%d x=%.0f y=%.0f", ev->touch.id, ev->touch.x, ev->touch.y);
            dispatch_touch_up(server, ev);
            break;
        case INPUT_POINTER_MOTION:  dispatch_pointer_motion(server, ev); break;
        case INPUT_POINTER_BUTTON:  dispatch_pointer_button(server, ev); break;
        case INPUT_POINTER_SCROLL:  dispatch_pointer_scroll(server, ev); break;
        }
        ring_consume();
    }

    return 0;
}

/* ------------------------------------------------------------------ */
/* Keyboard implementation (minimal — real HW events come from us)     */
/* ------------------------------------------------------------------ */

static const struct wlr_keyboard_impl keyboard_impl = { 0 };

/* ------------------------------------------------------------------ */
/* Public API                                                          */
/* ------------------------------------------------------------------ */

int input_handler_init(struct compositor_server *server) {
    /* ---- Pipe ---- */
    if (pipe2(server->input_pipe, O_CLOEXEC | O_NONBLOCK) != 0) {
        wlr_log(WLR_ERROR, "input_handler: pipe2 failed");
        return -1;
    }

    struct wl_event_loop *ev_loop =
        wl_display_get_event_loop(server->wl_display);
    server->input_event_source = wl_event_loop_add_fd(
        ev_loop, server->input_pipe[0], WL_EVENT_READABLE,
        on_input_event, server);
    if (!server->input_event_source) {
        wlr_log(WLR_ERROR, "input_handler: wl_event_loop_add_fd failed");
        close(server->input_pipe[0]);
        close(server->input_pipe[1]);
        return -1;
    }

    /* ---- Keyboard ---- */
    struct wlr_keyboard *kb = calloc(1, sizeof(*kb));
    if (!kb) {
        wlr_log(WLR_ERROR, "input_handler: calloc keyboard failed");
        wl_event_source_remove(server->input_event_source);
        close(server->input_pipe[0]);
        close(server->input_pipe[1]);
        return -1;
    }
    wlr_keyboard_init(kb, &keyboard_impl, "android-keyboard");

    /* Default US keymap via xkbcommon. */
    struct xkb_context *ctx = xkb_context_new(XKB_CONTEXT_NO_FLAGS);
    if (ctx) {
        struct xkb_keymap *keymap =
            xkb_keymap_new_from_names(ctx, NULL, XKB_KEYMAP_COMPILE_NO_FLAGS);
        if (keymap) {
            wlr_keyboard_set_keymap(kb, keymap);
            xkb_keymap_unref(keymap);
            wlr_log(WLR_INFO, "XKB keymap loaded successfully");
        } else {
            wlr_log(WLR_ERROR, "Failed to create XKB keymap — XKB_CONFIG_ROOT=%s",
                     getenv("XKB_CONFIG_ROOT") ? getenv("XKB_CONFIG_ROOT") : "(unset)");
        }
        xkb_context_unref(ctx);
    }

    server->keyboard = kb;
    wlr_seat_set_keyboard(server->seat, kb);

    /* Advertise all input capabilities. */
    wlr_seat_set_capabilities(server->seat,
                              WL_SEAT_CAPABILITY_KEYBOARD |
                              WL_SEAT_CAPABILITY_POINTER |
                              WL_SEAT_CAPABILITY_TOUCH);

    /* Reset ring buffer state. */
    __atomic_store_n(&ring_head, 0, __ATOMIC_RELAXED);
    __atomic_store_n(&ring_tail, 0, __ATOMIC_RELAXED);
    focused_surface = NULL;
    wl_list_init(&focused_surface_destroy_listener.link);

    wlr_log(WLR_INFO, "Input handler initialised (keyboard + pointer + touch)");
    return 0;
}

void input_handler_destroy(struct compositor_server *server) {
    if (server->input_event_source) {
        wl_event_source_remove(server->input_event_source);
        server->input_event_source = NULL;
    }
    if (server->input_pipe[0] >= 0) {
        close(server->input_pipe[0]);
        server->input_pipe[0] = -1;
    }
    if (server->input_pipe[1] >= 0) {
        close(server->input_pipe[1]);
        server->input_pipe[1] = -1;
    }
    if (server->keyboard) {
        wlr_keyboard_finish(server->keyboard);
        free(server->keyboard);
        server->keyboard = NULL;
    }
    wl_list_remove(&focused_surface_destroy_listener.link);
    wl_list_init(&focused_surface_destroy_listener.link);
    focused_surface = NULL;
}

/* ---- Thread-safe send functions ---- */

void input_handler_send_key(struct compositor_server *server,
                            int android_keycode, int pressed,
                            uint32_t timestamp_msec) {
    uint16_t linux_key = android_keycode_to_linux(android_keycode);
    wlr_log(WLR_INFO, "input_handler_send_key: android=%d linux=%d pressed=%d", android_keycode, linux_key, pressed);
    if (linux_key == 0) {
        wlr_log(WLR_INFO, "input_handler_send_key: UNMAPPED android keycode %d", android_keycode);
        return;
    }

    struct input_event *ev = ring_reserve();
    if (!ev) {
        LOGW("Input ring full — dropping key event");
        return;
    }
    ev->type = INPUT_KEY;
    ev->timestamp_msec = timestamp_msec;
    ev->key.linux_keycode = linux_key;
    ev->key.pressed = pressed;
    ring_commit();
    wake_compositor(server);
}

void input_handler_send_touch_down(struct compositor_server *server,
                                   int32_t touch_id, double x, double y,
                                   uint32_t timestamp_msec) {
    struct input_event *ev = ring_reserve();
    if (!ev) {
        LOGW("Input ring full — dropping touch down");
        return;
    }
    ev->type = INPUT_TOUCH_DOWN;
    ev->timestamp_msec = timestamp_msec;
    ev->touch.id = touch_id;
    ev->touch.x = x;
    ev->touch.y = y;
    ring_commit();
    wake_compositor(server);
}

void input_handler_send_touch_motion(struct compositor_server *server,
                                     int32_t touch_id, double x, double y,
                                     uint32_t timestamp_msec) {
    struct input_event *ev = ring_reserve();
    if (!ev) {
        LOGW("Input ring full — dropping touch motion");
        return;
    }
    ev->type = INPUT_TOUCH_MOTION;
    ev->timestamp_msec = timestamp_msec;
    ev->touch.id = touch_id;
    ev->touch.x = x;
    ev->touch.y = y;
    ring_commit();
    wake_compositor(server);
}

void input_handler_send_touch_up(struct compositor_server *server,
                                 int32_t touch_id,
                                 uint32_t timestamp_msec) {
    struct input_event *ev = ring_reserve();
    if (!ev) {
        LOGW("Input ring full — dropping touch up");
        return;
    }
    ev->type = INPUT_TOUCH_UP;
    ev->timestamp_msec = timestamp_msec;
    ev->touch.id = touch_id;
    ev->touch.x = 0;
    ev->touch.y = 0;
    ring_commit();
    wake_compositor(server);
}

void input_handler_send_pointer_motion(struct compositor_server *server,
                                       double x, double y,
                                       uint32_t timestamp_msec) {
    struct input_event *ev = ring_reserve();
    if (!ev) {
        LOGW("Input ring full — dropping pointer motion");
        return;
    }
    ev->type = INPUT_POINTER_MOTION;
    ev->timestamp_msec = timestamp_msec;
    ev->pointer_motion.x = x;
    ev->pointer_motion.y = y;
    ring_commit();
    wake_compositor(server);
}

void input_handler_send_pointer_button(struct compositor_server *server,
                                       uint32_t button, int pressed,
                                       uint32_t timestamp_msec) {
    struct input_event *ev = ring_reserve();
    if (!ev) {
        LOGW("Input ring full — dropping pointer button");
        return;
    }
    ev->type = INPUT_POINTER_BUTTON;
    ev->timestamp_msec = timestamp_msec;
    ev->pointer_button.button = button;
    ev->pointer_button.pressed = pressed;
    ring_commit();
    wake_compositor(server);
}

void input_handler_send_pointer_scroll(struct compositor_server *server,
                                       double dx, double dy,
                                       uint32_t timestamp_msec) {
    struct input_event *ev = ring_reserve();
    if (!ev) {
        LOGW("Input ring full — dropping pointer scroll");
        return;
    }
    ev->type = INPUT_POINTER_SCROLL;
    ev->timestamp_msec = timestamp_msec;
    ev->pointer_scroll.dx = dx;
    ev->pointer_scroll.dy = dy;
    ring_commit();
    wake_compositor(server);
}
