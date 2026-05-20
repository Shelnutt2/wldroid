/*
 * text_input_handler.c — Text input / IME bridge between Android and Wayland
 *
 * Implements zwp_text_input_v3 for clients that support it (GTK4, Qt6),
 * and falls back to synthetic wlr_keyboard events for clients that don't
 * (Electron/VS Code).
 *
 * Architecture:
 *   Android IME → JNI → text_input_handle_commit_text()
 *     ├── text-input-v3 path: zwp_text_input_v3_send_commit_string + done
 *     └── fallback path: synthetic wlr_keyboard key press/release events
 *
 *   Wayland client → zwp_text_input_v3.enable + commit → pipe byte → Kotlin shows IME
 */
#define _GNU_SOURCE
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <fcntl.h>
#include <errno.h>
#include <pthread.h>
#include <android/log.h>

#include <wayland-server-core.h>
#include <wlr/types/wlr_seat.h>
#include <wlr/types/wlr_keyboard.h>
#include <wlr/types/wlr_compositor.h>
#include <linux/input-event-codes.h>
#include <wlr/interfaces/wlr_keyboard.h>
#include <wlr/util/log.h>
#include <xkbcommon/xkbcommon.h>

#include "text-input-unstable-v3-server-protocol.h"
#include "compositor_server.h"
#include "text_input_handler.h"

#define LOG_TAG "TextInput"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)

/* ------------------------------------------------------------------ */
/* ASCII → evdev keycode lookup table (for synthetic key fallback)     */
/* ------------------------------------------------------------------ */

#define ASCII_MAX 128

static struct {
    xkb_keycode_t keycode;  /* evdev keycode (xkb keycode - 8) */
    bool needs_shift;
} ascii_to_key[ASCII_MAX];

static bool ascii_table_built = false;

/**
 * Scan the xkb keymap to build a codepoint → (evdev keycode, shift?) table.
 * Called once when the keyboard is available.
 */
static void build_ascii_table(struct wlr_keyboard *kb) {
    if (!kb || !kb->keymap) return;

    struct xkb_state *state = xkb_state_new(kb->keymap);
    if (!state) return;

    xkb_keycode_t min_kc = xkb_keymap_min_keycode(kb->keymap);
    xkb_keycode_t max_kc = xkb_keymap_max_keycode(kb->keymap);

    memset(ascii_to_key, 0, sizeof(ascii_to_key));

    /* Pass 1: scan all keycodes without modifiers (lowercase, digits, etc.) */
    for (xkb_keycode_t kc = min_kc; kc <= max_kc; kc++) {
        uint32_t cp = xkb_state_key_get_utf32(state, kc);
        if (cp > 0 && cp < ASCII_MAX && ascii_to_key[cp].keycode == 0) {
            ascii_to_key[cp].keycode = kc - 8;  /* xkb → evdev */
            ascii_to_key[cp].needs_shift = false;
        }
    }

    /* Pass 2: scan with Shift held (uppercase, symbols like !@# etc.) */
    xkb_mod_index_t shift_idx =
        xkb_keymap_mod_get_index(kb->keymap, XKB_MOD_NAME_SHIFT);
    if (shift_idx != XKB_MOD_INVALID) {
        struct xkb_state *shift_state = xkb_state_new(kb->keymap);
        if (shift_state) {
            /* Find the Shift keycode by trying each key */
            for (xkb_keycode_t kc = min_kc; kc <= max_kc; kc++) {
                xkb_state_update_key(shift_state, kc, XKB_KEY_DOWN);
                if (xkb_state_mod_index_is_active(shift_state, shift_idx,
                                                   XKB_STATE_MODS_DEPRESSED)) {
                    /* Shift is now held — scan all other keys */
                    for (xkb_keycode_t inner = min_kc; inner <= max_kc; inner++) {
                        if (inner == kc) continue; /* skip Shift itself */
                        uint32_t cp = xkb_state_key_get_utf32(shift_state, inner);
                        if (cp > 0 && cp < ASCII_MAX &&
                            ascii_to_key[cp].keycode == 0) {
                            ascii_to_key[cp].keycode = inner - 8;
                            ascii_to_key[cp].needs_shift = true;
                        }
                    }
                    xkb_state_update_key(shift_state, kc, XKB_KEY_UP);
                    break; /* found Shift, done */
                }
                xkb_state_update_key(shift_state, kc, XKB_KEY_UP);
            }
            xkb_state_unref(shift_state);
        }
    }

    xkb_state_unref(state);
    ascii_table_built = true;
    LOGD("ASCII keycode table built (%u–%u scanned)", min_kc, max_kc);
}

/* ------------------------------------------------------------------ */
/* Synthetic key emission (fallback for clients without text-input-v3) */
/* ------------------------------------------------------------------ */

/* KEY_LEFTSHIFT fallback if linux/input-event-codes.h is unavailable. */
#ifndef KEY_LEFTSHIFT
#define KEY_LEFTSHIFT 42
#endif
#ifndef KEY_BACKSPACE
#define KEY_BACKSPACE 14
#endif
#ifndef KEY_DELETE
#define KEY_DELETE 111
#endif

static void ensure_keyboard_focus(struct compositor_server *server) {
    if (!server || !server->seat || !server->keyboard) return;
    if (server->seat->keyboard_state.focused_surface) return;

    struct compositor_toplevel *ct;
    wl_list_for_each(ct, &server->toplevels, link) {
        if (!ct->rendering_only && ct->toplevel && ct->toplevel->base &&
            ct->toplevel->base->surface) {
            wlr_seat_keyboard_notify_enter(server->seat, ct->toplevel->base->surface,
                server->keyboard->keycodes, server->keyboard->num_keycodes,
                &server->keyboard->modifiers);
            text_input_handle_keyboard_enter(server, ct->toplevel->base->surface);
            return;
        }
    }

    LOGW("No non-rendering surface available for synthetic keyboard focus");
}

static void notify_synthetic_key(struct compositor_server *server,
                                 struct wlr_keyboard_key_event *ev) {
    wlr_keyboard_notify_key(server->keyboard, ev);
    if (server->seat) {
        wlr_seat_keyboard_notify_key(server->seat, ev->time_msec,
                                     ev->keycode, ev->state);
    }
}

static void emit_synthetic_keys(struct compositor_server *server,
                                 const char *text) {
    if (!server->keyboard) return;
    ensure_keyboard_focus(server);
    if (!ascii_table_built) build_ascii_table(server->keyboard);

    uint32_t timestamp = 0;

    const char *p = text;
    while (*p) {
        /* Decode one UTF-8 character to a Unicode codepoint */
        unsigned char c = (unsigned char)*p;
        uint32_t codepoint = 0;
        int bytes = 0;

        if (c < 0x80) {
            codepoint = c;
            bytes = 1;
        } else if (c < 0xC0) {
            p++; continue; /* stray continuation byte, skip */
        } else if (c < 0xE0) {
            codepoint = c & 0x1F;
            bytes = 2;
        } else if (c < 0xF0) {
            codepoint = c & 0x0F;
            bytes = 3;
        } else {
            codepoint = c & 0x07;
            bytes = 4;
        }

        for (int i = 1; i < bytes; i++) {
            if ((p[i] & 0xC0) != 0x80) { codepoint = 0; break; }
            codepoint = (codepoint << 6) | (p[i] & 0x3F);
        }
        p += bytes;

        if (codepoint == 0 || codepoint >= ASCII_MAX) {
            LOGD("Skipping non-ASCII codepoint U+%04X", codepoint);
            continue;
        }

        xkb_keycode_t evdev_kc = ascii_to_key[codepoint].keycode;
        if (evdev_kc == 0) {
            LOGD("No keycode mapping for U+%04X", codepoint);
            continue;
        }

        struct wlr_keyboard_key_event ev = {
            .time_msec = timestamp++,
            .update_state = true,
        };

        /* Press Shift if needed */
        if (ascii_to_key[codepoint].needs_shift) {
            ev.keycode = KEY_LEFTSHIFT;
            ev.state = WL_KEYBOARD_KEY_STATE_PRESSED;
            notify_synthetic_key(server, &ev);
        }

        /* Key press */
        ev.keycode = evdev_kc;
        ev.state = WL_KEYBOARD_KEY_STATE_PRESSED;
        ev.time_msec = timestamp++;
        notify_synthetic_key(server, &ev);

        /* Key release */
        ev.state = WL_KEYBOARD_KEY_STATE_RELEASED;
        ev.time_msec = timestamp++;
        notify_synthetic_key(server, &ev);

        /* Release Shift */
        if (ascii_to_key[codepoint].needs_shift) {
            ev.keycode = KEY_LEFTSHIFT;
            ev.state = WL_KEYBOARD_KEY_STATE_RELEASED;
            ev.time_msec = timestamp++;
            notify_synthetic_key(server, &ev);
        }
    }
}

/* ------------------------------------------------------------------ */
/* text-input-v3 protocol implementation                               */
/* ------------------------------------------------------------------ */

/** Per-client text input resource state. */
struct text_input {
    struct wl_resource *resource;
    struct wl_list link;  /* in text_inputs list */
    bool pending_enabled;
    bool current_enabled;
    uint32_t serial;
    bool entered;
    struct wlr_surface *entered_surface;

    char *surrounding_text;
    int32_t surrounding_cursor;
    int32_t surrounding_anchor;

    char *pending_surrounding_text;
    int32_t pending_surrounding_cursor;
    int32_t pending_surrounding_anchor;
    bool pending_surrounding_text_set;

    bool pending_cursor_rectangle_set;
    int32_t pending_cursor_x;
    int32_t pending_cursor_y;
    int32_t pending_cursor_width;
    int32_t pending_cursor_height;

    bool cursor_rectangle_set;
    int32_t cursor_x;
    int32_t cursor_y;
    int32_t cursor_width;
    int32_t cursor_height;
};

static struct wl_list text_inputs;           /* all text_input resources */
static struct text_input *active_text_input = NULL;
static struct compositor_server *g_text_input_server = NULL;
static bool active_text_input_committed = false;
static struct wlr_surface *focused_text_input_surface = NULL;
static struct wl_listener focused_text_input_surface_destroy_listener;

#define PENDING_TEXT_MAX_COUNT 256
#define PENDING_TEXT_MAX_BYTES (256 * 1024)
#define DELETE_SURROUNDING_MAX_COUNT 256

enum pending_text_op_type {
    PENDING_TEXT_COMMIT,
    PENDING_TEXT_DELETE,
};

struct pending_text_op {
    enum pending_text_op_type type;
    char *text;
    uint32_t before_length;
    uint32_t after_length;
    bool code_points;
    size_t bytes;
    struct pending_text_op *next;
};

static pthread_mutex_t pending_text_mutex = PTHREAD_MUTEX_INITIALIZER;
static struct pending_text_op *pending_text_head = NULL;
static struct pending_text_op *pending_text_tail = NULL;
static size_t pending_text_count = 0;
static size_t pending_text_bytes = 0;

static int on_text_input_event(int fd, uint32_t mask, void *data);
static void clear_pending_text_ops(void);
static void clear_text_input_focus(struct compositor_server *server,
                                   struct wlr_surface *surface,
                                   bool send_leave);
static void set_active_text_input(struct text_input *ti) {
    bool committed = ti != NULL && ti->current_enabled;
    active_text_input = ti;
    __atomic_store_n(&active_text_input_committed, committed, __ATOMIC_RELEASE);
}

/* ---- Pipe helpers: signal Kotlin to show/hide IME ---- */

static void request_ime_command(struct compositor_server *server, char cmd) {
    if (!server || server->ime_request_pipe[1] < 0) return;

    ssize_t rc = write(server->ime_request_pipe[1], &cmd, 1);
    if (rc == 1 || (rc < 0 && errno == EAGAIN)) return;

    wlr_log(WLR_ERROR, "IME request pipe write failed: %s", strerror(errno));
}

static void request_ime_show(struct compositor_server *server) {
    request_ime_command(server, 'S');
}

static void request_ime_hide(struct compositor_server *server) {
    request_ime_command(server, 'H');
}

/* ---- text-input-v3 keyboard focus routing ---- */

static bool listener_linked(struct wl_listener *listener) {
    return listener->link.next != NULL && !wl_list_empty(&listener->link);
}

static void remove_focused_surface_listener(void) {
    if (listener_linked(&focused_text_input_surface_destroy_listener)) {
        wl_list_remove(&focused_text_input_surface_destroy_listener.link);
        wl_list_init(&focused_text_input_surface_destroy_listener.link);
    }
}

static bool text_input_matches_surface_client(struct text_input *ti,
                                              struct wlr_surface *surface) {
    if (!ti || !ti->resource || !surface || !surface->resource) return false;
    return wl_resource_get_client(ti->resource) ==
           wl_resource_get_client(surface->resource);
}

static void clear_committed_surrounding_text_state(struct text_input *ti) {
    if (!ti) return;

    free(ti->surrounding_text);
    ti->surrounding_text = NULL;
    ti->surrounding_cursor = 0;
    ti->surrounding_anchor = 0;
}

static void clear_surrounding_text_state(struct text_input *ti) {
    if (!ti) return;

    clear_committed_surrounding_text_state(ti);

    free(ti->pending_surrounding_text);
    ti->pending_surrounding_text = NULL;
    ti->pending_surrounding_cursor = 0;
    ti->pending_surrounding_anchor = 0;
    ti->pending_surrounding_text_set = false;
}

static void clear_pending_text_input_state(struct text_input *ti) {
    if (!ti) return;

    ti->pending_enabled = false;
    ti->pending_cursor_rectangle_set = false;
    ti->pending_cursor_x = 0;
    ti->pending_cursor_y = 0;
    ti->pending_cursor_width = 0;
    ti->pending_cursor_height = 0;
}

static void clear_committed_text_input_state(struct text_input *ti) {
    if (!ti) return;

    ti->current_enabled = false;
    ti->cursor_rectangle_set = false;
    ti->cursor_x = 0;
    ti->cursor_y = 0;
    ti->cursor_width = 0;
    ti->cursor_height = 0;
}

static void reset_text_input_request_state(struct text_input *ti) {
    clear_pending_text_input_state(ti);
    clear_committed_text_input_state(ti);
    clear_surrounding_text_state(ti);
}

static void maybe_send_leave(struct text_input *ti, bool send_leave) {
    if (!ti || !ti->entered) return;

    bool was_enabled = ti->current_enabled;
    if (send_leave && ti->entered_surface && ti->entered_surface->resource) {
        zwp_text_input_v3_send_leave(ti->resource,
                                     ti->entered_surface->resource);
    }
    ti->entered = false;
    ti->entered_surface = NULL;
    if (active_text_input == ti) {
        set_active_text_input(NULL);
        if (was_enabled && g_text_input_server) {
            request_ime_hide(g_text_input_server);
        }
    }
    reset_text_input_request_state(ti);
}

static void clear_text_input_focus(struct compositor_server *server,
                                   struct wlr_surface *surface,
                                   bool send_leave) {
    (void)server;
    struct text_input *ti;
    wl_list_for_each(ti, &text_inputs, link) {
        if (!ti->entered) continue;
        if (!surface || ti->entered_surface == surface) {
            maybe_send_leave(ti, send_leave);
        }
    }
    if (!surface || focused_text_input_surface == surface) {
        focused_text_input_surface = NULL;
        remove_focused_surface_listener();
    }
}

static void handle_focused_text_input_surface_destroy(struct wl_listener *listener,
                                                       void *data) {
    (void)listener;
    struct wlr_surface *surface = data;
    clear_text_input_focus(g_text_input_server, surface, false);
}

void text_input_handle_keyboard_leave(struct compositor_server *server,
                                      struct wlr_surface *surface) {
    clear_text_input_focus(server, surface, true);
}

void text_input_handle_keyboard_enter(struct compositor_server *server,
                                      struct wlr_surface *surface) {
    if (!server || !surface || !surface->resource) return;

    if (focused_text_input_surface == surface) return;
    clear_text_input_focus(server, NULL, true);

    focused_text_input_surface = surface;
    remove_focused_surface_listener();
    focused_text_input_surface_destroy_listener.notify =
        handle_focused_text_input_surface_destroy;
    wl_signal_add(&surface->events.destroy,
                  &focused_text_input_surface_destroy_listener);

    struct text_input *ti;
    wl_list_for_each(ti, &text_inputs, link) {
        if (!text_input_matches_surface_client(ti, surface)) continue;
        reset_text_input_request_state(ti);
        ti->entered = true;
        ti->entered_surface = surface;
        zwp_text_input_v3_send_enter(ti->resource, surface->resource);
    }
}

/* ---- zwp_text_input_v3 request handlers ---- */

static void ti_destroy(struct wl_client *client,
                        struct wl_resource *resource) {
    (void)client;
    wl_resource_destroy(resource);
}

static void ti_enable(struct wl_client *client,
                       struct wl_resource *resource) {
    (void)client;
    struct text_input *ti = wl_resource_get_user_data(resource);
    if (!ti) return;
    if (!ti->entered) {
        LOGD("text-input-v3: ignoring enable while not focused");
        return;
    }

    clear_committed_surrounding_text_state(ti);
    ti->pending_enabled = true;
    LOGD("text-input-v3: enable pending");
}

static void ti_disable(struct wl_client *client,
                        struct wl_resource *resource) {
    (void)client;
    struct text_input *ti = wl_resource_get_user_data(resource);
    if (!ti) return;
    if (!ti->entered) {
        LOGD("text-input-v3: ignoring disable while not focused");
        return;
    }

    clear_committed_surrounding_text_state(ti);
    ti->pending_enabled = false;
    LOGD("text-input-v3: disable pending");
}

static void ti_set_surrounding_text(struct wl_client *client,
                                     struct wl_resource *resource,
                                     const char *text,
                                     int32_t cursor,
                                     int32_t anchor) {
    (void)client;
    struct text_input *ti = wl_resource_get_user_data(resource);
    if (!ti) return;
    if (!ti->entered) {
        LOGD("text-input-v3: ignoring surrounding text while not focused");
        return;
    }

    char *copy = text ? strdup(text) : strdup("");
    if (!copy) return;

    free(ti->pending_surrounding_text);
    ti->pending_surrounding_text = copy;
    size_t len = strlen(ti->pending_surrounding_text);
    ti->pending_surrounding_cursor = cursor < 0 ? 0 : (cursor > (int32_t)len ? (int32_t)len : cursor);
    ti->pending_surrounding_anchor = anchor < 0 ? 0 : (anchor > (int32_t)len ? (int32_t)len : anchor);
    ti->pending_surrounding_text_set = true;
}

static void ti_set_text_change_cause(struct wl_client *client,
                                      struct wl_resource *resource,
                                      uint32_t cause) {
    (void)client; (void)resource; (void)cause;
}

static void ti_set_content_type(struct wl_client *client,
                                 struct wl_resource *resource,
                                 uint32_t hint,
                                 uint32_t purpose) {
    (void)client; (void)resource; (void)hint; (void)purpose;
}

static void ti_set_cursor_rectangle(struct wl_client *client,
                                     struct wl_resource *resource,
                                     int32_t x, int32_t y,
                                     int32_t width, int32_t height) {
    (void)client;
    struct text_input *ti = wl_resource_get_user_data(resource);
    if (!ti) return;
    if (!ti->entered) {
        LOGD("text-input-v3: ignoring cursor rectangle while not focused");
        return;
    }

    ti->pending_cursor_rectangle_set = true;
    ti->pending_cursor_x = x;
    ti->pending_cursor_y = y;
    ti->pending_cursor_width = width;
    ti->pending_cursor_height = height;
}

static void ti_commit(struct wl_client *client,
                       struct wl_resource *resource) {
    (void)client;
    struct text_input *ti = wl_resource_get_user_data(resource);
    if (!ti) return;
    if (!ti->entered) {
        clear_pending_text_input_state(ti);
        clear_surrounding_text_state(ti);
        LOGD("text-input-v3: ignoring commit while not focused");
        return;
    }

    bool was_enabled = ti->current_enabled;
    bool was_active = active_text_input == ti;
    ti->serial++;
    ti->current_enabled = ti->pending_enabled;

    if (ti->pending_surrounding_text_set) {
        free(ti->surrounding_text);
        ti->surrounding_text = ti->pending_surrounding_text;
        ti->surrounding_cursor = ti->pending_surrounding_cursor;
        ti->surrounding_anchor = ti->pending_surrounding_anchor;
        ti->pending_surrounding_text = NULL;
        ti->pending_surrounding_cursor = 0;
        ti->pending_surrounding_anchor = 0;
        ti->pending_surrounding_text_set = false;
    }

    if (ti->current_enabled) {
        set_active_text_input(ti);
        if (!was_enabled && g_text_input_server) {
            request_ime_show(g_text_input_server);
        }
    } else {
        if (was_active) {
            set_active_text_input(NULL);
            if (was_enabled && g_text_input_server) {
                request_ime_hide(g_text_input_server);
            }
        }
        clear_surrounding_text_state(ti);
    }

    if (was_enabled != ti->current_enabled) {
        LOGD("text-input-v3: %s committed serial=%u",
             ti->current_enabled ? "enabled" : "disabled", ti->serial);
    }

    if (ti->pending_cursor_rectangle_set) {
        ti->cursor_rectangle_set = true;
        ti->cursor_x = ti->pending_cursor_x;
        ti->cursor_y = ti->pending_cursor_y;
        ti->cursor_width = ti->pending_cursor_width;
        ti->cursor_height = ti->pending_cursor_height;
        ti->pending_cursor_rectangle_set = false;
        LOGD("text-input-v3: cursor rectangle %d,%d %dx%d",
             ti->cursor_x, ti->cursor_y, ti->cursor_width, ti->cursor_height);
    }
}

/** Called when the text_input wl_resource is destroyed. */
static void ti_resource_destroy(struct wl_resource *resource) {
    struct text_input *ti = wl_resource_get_user_data(resource);
    if (!ti) return;

    if (active_text_input == ti) {
        set_active_text_input(NULL);
        if (ti->current_enabled && g_text_input_server) {
            request_ime_hide(g_text_input_server);
        }
    }
    if (ti->entered) {
        ti->entered = false;
        ti->entered_surface = NULL;
    }
    wl_list_remove(&ti->link);
    clear_surrounding_text_state(ti);
    free(ti);
}

static const struct zwp_text_input_v3_interface text_input_impl = {
    .destroy = ti_destroy,
    .enable = ti_enable,
    .disable = ti_disable,
    .set_surrounding_text = ti_set_surrounding_text,
    .set_text_change_cause = ti_set_text_change_cause,
    .set_content_type = ti_set_content_type,
    .set_cursor_rectangle = ti_set_cursor_rectangle,
    .commit = ti_commit,
};

/* ---- zwp_text_input_manager_v3 ---- */

static void manager_destroy(struct wl_client *client,
                             struct wl_resource *resource) {
    (void)client;
    wl_resource_destroy(resource);
}

static void manager_get_text_input(struct wl_client *client,
                                    struct wl_resource *resource,
                                    uint32_t id,
                                    struct wl_resource *seat) {
    (void)seat;
    struct text_input *ti = calloc(1, sizeof(*ti));
    if (!ti) {
        wl_client_post_no_memory(client);
        return;
    }

    ti->resource = wl_resource_create(client,
        &zwp_text_input_v3_interface,
        wl_resource_get_version(resource), id);
    if (!ti->resource) {
        free(ti);
        wl_client_post_no_memory(client);
        return;
    }

    wl_resource_set_implementation(ti->resource, &text_input_impl,
                                   ti, ti_resource_destroy);
    wl_list_insert(&text_inputs, &ti->link);
    if (focused_text_input_surface &&
        text_input_matches_surface_client(ti, focused_text_input_surface)) {
        ti->entered = true;
        ti->entered_surface = focused_text_input_surface;
        zwp_text_input_v3_send_enter(ti->resource,
                                     focused_text_input_surface->resource);
    }
    LOGD("text-input-v3: new text_input created");
}

static const struct zwp_text_input_manager_v3_interface manager_impl = {
    .destroy = manager_destroy,
    .get_text_input = manager_get_text_input,
};

static void bind_manager(struct wl_client *client, void *data,
                          uint32_t version, uint32_t id) {
    (void)data;
    struct wl_resource *resource = wl_resource_create(client,
        &zwp_text_input_manager_v3_interface, version, id);
    if (!resource) {
        wl_client_post_no_memory(client);
        return;
    }
    wl_resource_set_implementation(resource, &manager_impl, data, NULL);
}

/* ------------------------------------------------------------------ */
/* Public API                                                          */
/* ------------------------------------------------------------------ */

int text_input_handler_init(struct compositor_server *server) {
    wl_list_init(&text_inputs);
    wl_list_init(&focused_text_input_surface_destroy_listener.link);
    focused_text_input_surface = NULL;
    set_active_text_input(NULL);
    g_text_input_server = server;
    server->ime_request_pipe[0] = -1;
    server->ime_request_pipe[1] = -1;
    server->text_input_pipe[0] = -1;
    server->text_input_pipe[1] = -1;
    server->text_input_event_source = NULL;

    /* IME request pipe: compositor writes 'S'/'H', Kotlin reads. */
    if (pipe2(server->ime_request_pipe, O_CLOEXEC | O_NONBLOCK) != 0) {
        wlr_log(WLR_ERROR, "text_input: pipe2 failed");
        return -1;
    }

    /* Text commit pipe: JNI thread queues text, compositor event loop sends protocol events. */
    if (pipe2(server->text_input_pipe, O_CLOEXEC | O_NONBLOCK) != 0) {
        wlr_log(WLR_ERROR, "text_input: text pipe2 failed");
        close(server->ime_request_pipe[0]);
        close(server->ime_request_pipe[1]);
        server->ime_request_pipe[0] = -1;
        server->ime_request_pipe[1] = -1;
        return -1;
    }
    struct wl_event_loop *ev_loop = wl_display_get_event_loop(server->wl_display);
    server->text_input_event_source = wl_event_loop_add_fd(
        ev_loop, server->text_input_pipe[0], WL_EVENT_READABLE,
        on_text_input_event, server);
    if (!server->text_input_event_source) {
        wlr_log(WLR_ERROR, "text_input: wl_event_loop_add_fd failed");
        close(server->ime_request_pipe[0]);
        close(server->ime_request_pipe[1]);
        close(server->text_input_pipe[0]);
        close(server->text_input_pipe[1]);
        server->ime_request_pipe[0] = -1;
        server->ime_request_pipe[1] = -1;
        server->text_input_pipe[0] = -1;
        server->text_input_pipe[1] = -1;
        return -1;
    }

    /* Register zwp_text_input_manager_v3 global (version 1). */
    server->text_input_manager = wl_global_create(server->wl_display,
        &zwp_text_input_manager_v3_interface, 1, server, bind_manager);
    if (!server->text_input_manager) {
        wlr_log(WLR_ERROR, "text_input: wl_global_create failed");
        if (server->text_input_event_source) {
            wl_event_source_remove(server->text_input_event_source);
            server->text_input_event_source = NULL;
        }
        close(server->ime_request_pipe[0]);
        close(server->ime_request_pipe[1]);
        close(server->text_input_pipe[0]);
        close(server->text_input_pipe[1]);
        server->ime_request_pipe[0] = -1;
        server->ime_request_pipe[1] = -1;
        server->text_input_pipe[0] = -1;
        server->text_input_pipe[1] = -1;
        return -1;
    }

    /* Pre-build the ASCII keycode table if keyboard is already up. */
    if (server->keyboard) {
        build_ascii_table(server->keyboard);
    }

    wlr_log(WLR_INFO, "Text input handler initialized");
    return 0;
}

void text_input_handler_destroy(struct compositor_server *server) {
    clear_text_input_focus(server, NULL, false);

    if (server->text_input_manager) {
        wl_global_destroy(server->text_input_manager);
        server->text_input_manager = NULL;
    }

    if (server->ime_request_pipe[0] >= 0) {
        close(server->ime_request_pipe[0]);
        close(server->ime_request_pipe[1]);
        server->ime_request_pipe[0] = -1;
        server->ime_request_pipe[1] = -1;
    }
    if (server->text_input_event_source) {
        wl_event_source_remove(server->text_input_event_source);
        server->text_input_event_source = NULL;
    }
    if (server->text_input_pipe[0] >= 0) {
        close(server->text_input_pipe[0]);
        close(server->text_input_pipe[1]);
        server->text_input_pipe[0] = -1;
        server->text_input_pipe[1] = -1;
    }
    clear_pending_text_ops();

    set_active_text_input(NULL);
    g_text_input_server = NULL;
}

static void dispatch_commit_text(struct compositor_server *server,
                                 const char *text) {
    if (!text || !*text) return;

    if (active_text_input && active_text_input->current_enabled) {
        /* Send via text-input-v3 protocol on the compositor event loop. */
        zwp_text_input_v3_send_commit_string(active_text_input->resource, text);
        zwp_text_input_v3_send_done(active_text_input->resource,
                                     active_text_input->serial);
        LOGD("Committed text via text-input-v3 (%zu bytes)", strlen(text));
    } else {
        /* Fallback: inject synthetic keyboard events through the seat path. */
        emit_synthetic_keys(server, text);
        LOGD("Committed text via synthetic keys (%zu bytes)", strlen(text));
    }
}

static void emit_delete_keys(struct compositor_server *server,
                             uint32_t before_length,
                             uint32_t after_length) {
    if (!server || !server->keyboard) return;
    ensure_keyboard_focus(server);

    uint32_t timestamp = 0;
    struct wlr_keyboard_key_event ev = {
        .update_state = true,
    };

    for (uint32_t i = 0; i < before_length; i++) {
        ev.keycode = KEY_BACKSPACE;
        ev.state = WL_KEYBOARD_KEY_STATE_PRESSED;
        ev.time_msec = timestamp++;
        notify_synthetic_key(server, &ev);
        ev.state = WL_KEYBOARD_KEY_STATE_RELEASED;
        ev.time_msec = timestamp++;
        notify_synthetic_key(server, &ev);
    }
    for (uint32_t i = 0; i < after_length; i++) {
        ev.keycode = KEY_DELETE;
        ev.state = WL_KEYBOARD_KEY_STATE_PRESSED;
        ev.time_msec = timestamp++;
        notify_synthetic_key(server, &ev);
        ev.state = WL_KEYBOARD_KEY_STATE_RELEASED;
        ev.time_msec = timestamp++;
        notify_synthetic_key(server, &ev);
    }
}


static uint32_t clamp_delete_count(uint32_t count) {
    return count > DELETE_SURROUNDING_MAX_COUNT ? DELETE_SURROUNDING_MAX_COUNT : count;
}

static size_t utf8_codepoint_len(const char *text, size_t pos, size_t len,
                                 uint32_t *codepoint) {
    unsigned char c = (unsigned char)text[pos];
    if (c < 0x80) {
        *codepoint = c;
        return 1;
    }
    if ((c & 0xE0) == 0xC0 && pos + 1 < len) {
        *codepoint = ((uint32_t)(c & 0x1F) << 6) |
                     ((uint32_t)(text[pos + 1] & 0x3F));
        return 2;
    }
    if ((c & 0xF0) == 0xE0 && pos + 2 < len) {
        *codepoint = ((uint32_t)(c & 0x0F) << 12) |
                     ((uint32_t)(text[pos + 1] & 0x3F) << 6) |
                     ((uint32_t)(text[pos + 2] & 0x3F));
        return 3;
    }
    if ((c & 0xF8) == 0xF0 && pos + 3 < len) {
        *codepoint = ((uint32_t)(c & 0x07) << 18) |
                     ((uint32_t)(text[pos + 1] & 0x3F) << 12) |
                     ((uint32_t)(text[pos + 2] & 0x3F) << 6) |
                     ((uint32_t)(text[pos + 3] & 0x3F));
        return 4;
    }
    *codepoint = c;
    return 1;
}

static uint32_t delete_units_for_codepoint(uint32_t codepoint,
                                           bool code_points) {
    if (code_points) return 1;
    return codepoint > 0xFFFF ? 2 : 1;
}

static uint32_t utf8_delete_bytes_before(const char *text, size_t cursor,
                                         uint32_t units_to_delete,
                                         bool code_points) {
    if (!text || units_to_delete == 0) return 0;
    size_t len = strlen(text);
    if (cursor > len) cursor = len;

    uint32_t total_units = 0;
    size_t pos = 0;
    while (pos < cursor) {
        uint32_t cp = 0;
        size_t cp_len = utf8_codepoint_len(text, pos, cursor, &cp);
        total_units += delete_units_for_codepoint(cp, code_points);
        pos += cp_len;
    }

    uint32_t target_units = total_units > units_to_delete
        ? total_units - units_to_delete
        : 0;
    uint32_t units = 0;
    size_t start = 0;
    pos = 0;
    while (pos < cursor) {
        uint32_t cp = 0;
        size_t cp_len = utf8_codepoint_len(text, pos, cursor, &cp);
        uint32_t cp_units = delete_units_for_codepoint(cp, code_points);
        if (units + cp_units <= target_units) {
            pos += cp_len;
            units += cp_units;
            start = pos;
        } else {
            break;
        }
    }
    return (uint32_t)(cursor - start);
}

static uint32_t utf8_delete_bytes_after(const char *text, size_t cursor,
                                        uint32_t units_to_delete,
                                        bool code_points) {
    if (!text || units_to_delete == 0) return 0;
    size_t len = strlen(text);
    if (cursor > len) cursor = len;

    uint32_t units = 0;
    size_t pos = cursor;
    while (pos < len && units < units_to_delete) {
        uint32_t cp = 0;
        size_t cp_len = utf8_codepoint_len(text, pos, len, &cp);
        units += delete_units_for_codepoint(cp, code_points);
        pos += cp_len;
    }
    return (uint32_t)(pos - cursor);
}

static bool text_input_delete_byte_counts(struct text_input *ti,
                                          uint32_t before_length,
                                          uint32_t after_length,
                                          bool code_points,
                                          uint32_t *before_bytes,
                                          uint32_t *after_bytes) {
    *before_bytes = 0;
    *after_bytes = 0;
    if (!ti || !ti->surrounding_text) return false;

    size_t len = strlen(ti->surrounding_text);
    size_t cursor = ti->surrounding_cursor < 0 ? 0 : (size_t)ti->surrounding_cursor;
    if (cursor > len) cursor = len;

    *before_bytes = utf8_delete_bytes_before(
        ti->surrounding_text, cursor, before_length, code_points);
    *after_bytes = utf8_delete_bytes_after(
        ti->surrounding_text, cursor, after_length, code_points);
    return true;
}

static void dispatch_delete_surrounding_text(struct compositor_server *server,
                                             uint32_t before_length,
                                             uint32_t after_length,
                                             bool code_points) {
    before_length = clamp_delete_count(before_length);
    after_length = clamp_delete_count(after_length);
    if (before_length == 0 && after_length == 0) return;

    if (active_text_input && active_text_input->current_enabled) {
        uint32_t before_bytes = 0;
        uint32_t after_bytes = 0;
        bool has_surrounding_text = text_input_delete_byte_counts(
            active_text_input, before_length, after_length, code_points,
            &before_bytes, &after_bytes);
        if (has_surrounding_text) {
            if (before_bytes == 0 && after_bytes == 0) return;
            zwp_text_input_v3_send_delete_surrounding_text(
                active_text_input->resource, before_bytes, after_bytes);
            zwp_text_input_v3_send_done(active_text_input->resource,
                                         active_text_input->serial);
            LOGD("Deleted surrounding text via text-input-v3 (%u/%u bytes before, %u/%u bytes after)",
                 before_bytes, before_length, after_bytes, after_length);
            return;
        }

        LOGD("No committed surrounding text available; falling back to synthetic delete keys");
    }

    emit_delete_keys(server, before_length, after_length);
    LOGD("Deleted surrounding text via synthetic keys (%u before, %u after)",
         before_length, after_length);
}

static bool enqueue_text_op(struct pending_text_op *op) {
    if (!op) return false;
    pthread_mutex_lock(&pending_text_mutex);
    if (pending_text_count >= PENDING_TEXT_MAX_COUNT ||
        pending_text_bytes + op->bytes > PENDING_TEXT_MAX_BYTES) {
        pthread_mutex_unlock(&pending_text_mutex);
        LOGW("Dropping IME operation: pending queue full");
        free(op->text);
        free(op);
        return false;
    }
    if (pending_text_tail) {
        pending_text_tail->next = op;
    } else {
        pending_text_head = op;
    }
    pending_text_tail = op;
    pending_text_count++;
    pending_text_bytes += op->bytes;
    pthread_mutex_unlock(&pending_text_mutex);
    return true;
}

static bool enqueue_text_commit(const char *text) {
    struct pending_text_op *op = calloc(1, sizeof(*op));
    if (!op) return false;
    op->type = PENDING_TEXT_COMMIT;
    op->text = strdup(text);
    if (!op->text) {
        free(op);
        return false;
    }
    op->bytes = strlen(op->text);
    return enqueue_text_op(op);
}

static bool enqueue_text_delete(uint32_t before_length, uint32_t after_length, bool code_points) {
    struct pending_text_op *op = calloc(1, sizeof(*op));
    if (!op) return false;
    op->type = PENDING_TEXT_DELETE;
    op->before_length = clamp_delete_count(before_length);
    op->after_length = clamp_delete_count(after_length);
    op->code_points = code_points;
    op->bytes = sizeof(*op) + op->before_length + op->after_length;
    return enqueue_text_op(op);
}

static struct pending_text_op *pop_text_op(void) {
    pthread_mutex_lock(&pending_text_mutex);
    struct pending_text_op *op = pending_text_head;
    if (op) {
        pending_text_head = op->next;
        if (!pending_text_head) {
            pending_text_tail = NULL;
        }
        pending_text_count--;
        pending_text_bytes -= op->bytes;
    }
    pthread_mutex_unlock(&pending_text_mutex);
    return op;
}

static void clear_pending_text_ops(void) {
    struct pending_text_op *op;
    while ((op = pop_text_op()) != NULL) {
        free(op->text);
        free(op);
    }
}

static int on_text_input_event(int fd, uint32_t mask, void *data) {
    (void)mask;
    struct compositor_server *server = data;

    char buf[64];
    ssize_t n;
    do {
        n = read(fd, buf, sizeof(buf));
    } while (n > 0);
    if (n < 0 && errno != EAGAIN) {
        wlr_log(WLR_ERROR, "text input pipe read error: %s", strerror(errno));
        return 0;
    }

    struct pending_text_op *op;
    while ((op = pop_text_op()) != NULL) {
        if (op->type == PENDING_TEXT_COMMIT) {
            dispatch_commit_text(server, op->text);
        } else if (op->type == PENDING_TEXT_DELETE) {
            dispatch_delete_surrounding_text(server, op->before_length, op->after_length, op->code_points);
        }
        free(op->text);
        free(op);
    }
    return 0;
}

static bool wake_text_input_loop(struct compositor_server *server) {
    if (!server || server->text_input_pipe[1] < 0 || !server->text_input_event_source) {
        return false;
    }
    char c = 't';
    ssize_t rc = write(server->text_input_pipe[1], &c, 1);
    if (rc == 1) return true;
    if (rc < 0 && errno == EAGAIN) return true;
    wlr_log(WLR_ERROR, "text input pipe wake failed: %s", strerror(errno));
    return false;
}

void text_input_handle_commit_text(struct compositor_server *server,
                                    const char *text) {
    if (!server || !text || !*text) return;
    if (server->text_input_pipe[1] < 0 || !server->text_input_event_source) {
        wlr_log(WLR_ERROR, "text input commit dropped: event pipe unavailable");
        return;
    }
    if (!enqueue_text_commit(text)) return;
    if (!wake_text_input_loop(server)) {
        clear_pending_text_ops();
    }
}

void text_input_handle_delete_surrounding_text(struct compositor_server *server,
                                                uint32_t before_length,
                                                uint32_t after_length) {
    if (!server || (before_length == 0 && after_length == 0)) return;
    if (server->text_input_pipe[1] < 0 || !server->text_input_event_source) {
        wlr_log(WLR_ERROR, "text input delete dropped: event pipe unavailable");
        return;
    }
    if (!enqueue_text_delete(before_length, after_length, false)) return;
    if (!wake_text_input_loop(server)) {
        clear_pending_text_ops();
    }
}

void text_input_handle_delete_surrounding_text_in_code_points(
    struct compositor_server *server,
    uint32_t before_length,
    uint32_t after_length) {
    if (!server || (before_length == 0 && after_length == 0)) return;
    if (server->text_input_pipe[1] < 0 || !server->text_input_event_source) {
        wlr_log(WLR_ERROR, "text input delete dropped: event pipe unavailable");
        return;
    }
    if (!enqueue_text_delete(before_length, after_length, true)) return;
    if (!wake_text_input_loop(server)) {
        clear_pending_text_ops();
    }
}

bool text_input_has_active_text_input(void) {
    return __atomic_load_n(&active_text_input_committed, __ATOMIC_ACQUIRE);
}

void text_input_handle_ime_shown(struct compositor_server *server) {
    (void)server;
    LOGD("IME shown");
}

void text_input_handle_ime_hidden(struct compositor_server *server) {
    (void)server;
    LOGD("IME hidden");
}
