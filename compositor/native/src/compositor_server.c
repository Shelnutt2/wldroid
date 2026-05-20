/*
 * compositor_server.c — Main Wayland compositor server
 *
 * Sets up:
 *   - Android backend + output bound to an ANativeWindow
 *   - AHardwareBuffer allocator
 *   - GLES2 renderer (GPU, with pixman fallback)
 *   - wlroots scene graph, output layout, XDG shell, seat
 *
 * The event loop runs on whatever thread calls compositor_server_run();
 * the JNI bridge spawns a dedicated pthread for this.
 */
/* pipe2() on Android bionic requires _GNU_SOURCE. */
#define _GNU_SOURCE
#include <errno.h>
#include <limits.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>
#include <fcntl.h>
#include <android/log.h>
#include "ahb_registry.h"
#include "ahb_registry_receiver.h"
#include <android/native_window.h>

#include <wayland-server-core.h>
#include <wlr/backend.h>
#include <wlr/render/allocator.h>
#include <wlr/render/wlr_renderer.h>
#include <wlr/types/wlr_compositor.h>
#include <wlr/types/wlr_output.h>
#include <wlr/types/wlr_output_layout.h>
#include <wlr/types/wlr_scene.h>
#include <wlr/types/wlr_seat.h>
#include <wlr/types/wlr_xdg_shell.h>
#include <wlr/types/wlr_subcompositor.h>
#include <wlr/types/wlr_data_device.h>
#include <wlr/types/wlr_viewporter.h>
#include <wlr/types/wlr_xdg_decoration_v1.h>
#include <wlr/types/wlr_fractional_scale_v1.h>
#include <wlr/types/wlr_single_pixel_buffer_v1.h>
#include <wlr/types/wlr_xdg_output_v1.h>
#include <wlr/types/wlr_primary_selection_v1.h>
#include <wlr/types/wlr_cursor_shape_v1.h>
#include <wlr/util/log.h>

/* wlr/config.h defines WLR_HAS_GLES2_RENDERER — must be included before the
 * GLES2 guard below so that EGL/GLES headers are pulled in when enabled. */
#include <wlr/config.h>

/* GLES2 GPU renderer — requires EGL + wlroots GLES2 support. */
#if WLR_HAS_GLES2_RENDERER
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>
#include <wlr/render/egl.h>
#include <wlr/render/gles2.h>
#include "render/egl.h"  /* wlr_egl_destroy (internal wlroots header) */
#endif

/* linux-dmabuf-v1 — advertised when GLES2 renderer is active */
#include <wlr/types/wlr_linux_dmabuf_v1.h>
#include <sys/sysmacros.h>    /* makedev() */
#include <drm_fourcc.h>       /* DRM_FORMAT_*, DRM_FORMAT_MOD_INVALID */

#define LOGI_COMP(...) __android_log_print(ANDROID_LOG_INFO, "Compositor", __VA_ARGS__)

#include "android_backend.h"
#include "android_output.h"
#include "ahb_allocator.h"
#include "compositor_server.h"
#include "input_handler.h"
#include "text_input_handler.h"

#if WLR_HAS_XWAYLAND
#include <wlr/xwayland.h>
#include "xwayland_surface.h"
#endif

#define LOG_TAG "CompositorServer"

/* ------------------------------------------------------------------ */
/* Client tracking                                                     */
/* ------------------------------------------------------------------ */

struct client_destroy_wrapper {
    struct wl_listener listener;
    struct compositor_server *server;
};

static void handle_client_destroyed(struct wl_listener *listener, void *data) {
    (void)data;
    struct client_destroy_wrapper *wrapper =
        wl_container_of(listener, wrapper, listener);
    int count = __atomic_sub_fetch(&wrapper->server->client_count, 1, __ATOMIC_SEQ_CST);
    wlr_log(WLR_INFO, "Client disconnected (count: %d)", count);
    wl_list_remove(&wrapper->listener.link);
    free(wrapper);
}

static void handle_client_created(struct wl_listener *listener, void *data) {
    struct compositor_server *server =
        wl_container_of(listener, server, client_created);
    struct wl_client *client = data;

    struct client_destroy_wrapper *wrapper = calloc(1, sizeof(*wrapper));
    if (!wrapper) {
        wlr_log(WLR_ERROR, "Failed to allocate client destroy wrapper");
        return;  // Don't increment — we can't track this client
    }

    int count = __atomic_add_fetch(&server->client_count, 1, __ATOMIC_SEQ_CST);
    wlr_log(WLR_INFO, "Client connected (count: %d)", count);

    wrapper->server = server;
    wrapper->listener.notify = handle_client_destroyed;
    wl_client_add_destroy_listener(client, &wrapper->listener);
}

/* ------------------------------------------------------------------ */
/* Event handlers                                                      */
/* ------------------------------------------------------------------ */

static void on_new_output(struct wl_listener *listener, void *data) {
    struct compositor_server *server =
        wl_container_of(listener, server, new_output);
    struct wlr_output *wlr_output = data;

    /* Enable the output BEFORE initializing the renderer.
     * wlr_output_init_render() sets up the swapchain, but the initial
     * enable commit triggers output_ensure_buffer() which tries to
     * create the swapchain too early. When renderer is NULL,
     * output_ensure_buffer returns true immediately, allowing the
     * enable commit to succeed cleanly. */
    struct wlr_output_state state;
    wlr_output_state_init(&state);
    wlr_output_state_set_enabled(&state, true);
    bool commit_ok = wlr_output_commit_state(wlr_output, &state);
    wlr_output_state_finish(&state);
    LOGI_COMP("Enable commit (before init_render): ok=%d, enabled=%d, width=%d, height=%d",
              commit_ok, wlr_output->enabled, wlr_output->width, wlr_output->height);

    /* Now init the renderer + allocator for this output. */
    wlr_output_init_render(wlr_output, server->allocator, server->renderer);
    LOGI_COMP("Renderer initialized for output");

    /* Add to the output layout (at automatic position). */
    struct wlr_output_layout_output *lo =
        wlr_output_layout_add_auto(server->output_layout, wlr_output);

    /* Create the scene output. */
    server->scene_output = wlr_scene_output_create(server->scene, wlr_output);
    wlr_scene_output_layout_add_output(server->scene_output_layout, lo,
                                       server->scene_output);

    server->output = wlr_output;

    /* Register frame + request_state listeners. */
    wl_signal_add(&wlr_output->events.frame, &server->output_frame);
    wl_signal_add(&wlr_output->events.request_state,
                  &server->output_request_state);
    LOGI_COMP("Frame listener registered on output %s", wlr_output->name);

    wlr_output_create_global(wlr_output, server->wl_display);

    LOGI_COMP("Output configured: %s", wlr_output->name);
}

static void on_output_frame(struct wl_listener *listener, void *data) {
    (void)data;
    struct compositor_server *server =
        wl_container_of(listener, server, output_frame);

    /* Defense in depth: skip rendering while paused. */
    if (server->paused) {
        return;
    }

    struct wlr_scene_output *scene_output = server->scene_output;
    if (!scene_output) {
        return;
    }

    bool committed = wlr_scene_output_commit(scene_output, NULL);
    static int frame_count = 0;
    if (++frame_count <= 5 || frame_count % 600 == 0) {
        LOGI_COMP("Frame %d: committed=%d, output %dx%d", frame_count,
                  committed, server->output ? server->output->width : 0,
                  server->output ? server->output->height : 0);
    }

    struct timespec now;
    clock_gettime(CLOCK_MONOTONIC, &now);
    wlr_scene_output_send_frame_done(scene_output, &now);
}

static void on_output_request_state(struct wl_listener *listener, void *data) {
    (void)listener;
    struct wlr_output_event_request_state *event = data;
    wlr_output_commit_state(event->output, event->state);
}

/* ------------------------------------------------------------------ */
/* Multi-window focus management                                       */
/* ------------------------------------------------------------------ */

static void focus_toplevel(struct compositor_toplevel *ct) {
    struct compositor_server *server = ct->server;

    /* Deactivate all others — but when focusing a rendering-only surface,
     * keep the Ozone (non-rendering-only) toplevel activated so Chromium
     * continues to accept keyboard input. */
    struct compositor_toplevel *other;
    wl_list_for_each(other, &server->toplevels, link) {
        if (other != ct) {
            if (ct->rendering_only && !other->rendering_only) {
                /* Keep Ozone activated — it needs to think it has focus */
                continue;
            }
            wlr_xdg_toplevel_set_activated(other->toplevel, false);
        }
    }

    /* Raise to top in scene graph. */
    wlr_scene_node_raise_to_top(&ct->scene_tree->node);

    /* Activate the toplevel visually. */
    wlr_xdg_toplevel_set_activated(ct->toplevel, true);

    /* Give keyboard focus — but SKIP for rendering-only surfaces.
     * The EGL wrapper's surface has no Chromium keyboard handler.
     * By skipping, keyboard focus stays on the previously focused
     * surface (Ozone's) which was set when Ozone's toplevel mapped first. */
    if (!ct->rendering_only) {
        struct wlr_surface *surface = ct->toplevel->base->surface;
        struct wlr_seat *seat = server->seat;
        struct wlr_keyboard *kb = wlr_seat_get_keyboard(seat);
        if (kb) {
            wlr_seat_keyboard_notify_enter(seat, surface,
                kb->keycodes, kb->num_keycodes, &kb->modifiers);
        } else {
            wlr_seat_keyboard_notify_enter(seat, surface, NULL, 0, NULL);
        }
        text_input_handle_keyboard_enter(server, surface);
    }

    /* Move to front of list (MRU order). */
    wl_list_remove(&ct->link);
    wl_list_insert(&server->toplevels, &ct->link);
}

static struct compositor_toplevel *get_focused_toplevel(
        struct compositor_server *server) {
    if (wl_list_empty(&server->toplevels)) return NULL;
    struct compositor_toplevel *ct;
    ct = wl_container_of(server->toplevels.next, ct, link);
    return ct;
}

/* ------------------------------------------------------------------ */
/* Toplevel lifecycle listeners                                        */
/* ------------------------------------------------------------------ */

static void on_toplevel_map(struct wl_listener *listener, void *data) {
    LOGI_COMP("Toplevel MAPPED");
    (void)data;
    struct compositor_toplevel *ct = wl_container_of(listener, ct, map);

    /* Detect the EGL rendering surface by app_id.  Mark it so the input
     * handler can redirect touch/pointer events to Ozone's surface. */
    const char *app_id = ct->toplevel->app_id;
    if (app_id && strcmp(app_id, "com.coder.vscode") == 0) {
        ct->rendering_only = true;
        wlr_log(WLR_INFO, "Toplevel \"%s\" marked rendering_only", app_id);
    }

    focus_toplevel(ct);
}

static void on_toplevel_unmap(struct wl_listener *listener, void *data) {
    LOGI_COMP("Toplevel UNMAPPED");
    (void)data;
    struct compositor_toplevel *ct = wl_container_of(listener, ct, unmap);

    struct wlr_surface *surface = ct->toplevel && ct->toplevel->base
        ? ct->toplevel->base->surface : NULL;
    if (surface && ct->server->seat &&
        ct->server->seat->keyboard_state.focused_surface == surface) {
        text_input_handle_keyboard_leave(ct->server, surface);
    }

    /* If this was focused, focus the next toplevel in the list. */
    if (ct == get_focused_toplevel(ct->server)) {
        struct compositor_toplevel *next = NULL;
        if (ct->link.next != &ct->server->toplevels) {
            next = wl_container_of(ct->link.next, next, link);
        }
        if (next) focus_toplevel(next);
    }
}

static void on_toplevel_destroy(struct wl_listener *listener, void *data) {
    (void)data;
    struct compositor_toplevel *ct = wl_container_of(listener, ct, destroy);
    struct wlr_surface *surface = ct->toplevel && ct->toplevel->base
        ? ct->toplevel->base->surface : NULL;
    if (surface) {
        text_input_handle_keyboard_leave(ct->server, surface);
    }
    wl_list_remove(&ct->link);
    wl_list_remove(&ct->map.link);
    wl_list_remove(&ct->unmap.link);
    wl_list_remove(&ct->commit.link);
    wl_list_remove(&ct->destroy.link);
    wl_list_remove(&ct->request_fullscreen.link);
    free(ct);
}

static void on_request_fullscreen(struct wl_listener *listener, void *data) {
    (void)data;
    struct compositor_toplevel *ct =
        wl_container_of(listener, ct, request_fullscreen);
    if (!ct->toplevel->base->initialized) return;
    if (ct->server->output) {
        wlr_xdg_toplevel_set_size(ct->toplevel,
            ct->server->output->width, ct->server->output->height);
    }
    wlr_scene_node_set_position(&ct->scene_tree->node, 0, 0);
    wlr_xdg_toplevel_set_fullscreen(ct->toplevel, true);
    wlr_xdg_surface_schedule_configure(ct->toplevel->base);
}

static void on_toplevel_commit(struct wl_listener *listener, void *data) {
    (void)data;
    struct compositor_toplevel *ct = wl_container_of(listener, ct, commit);
    struct wlr_xdg_surface *xdg_surface = ct->toplevel->base;

    if (xdg_surface->initial_commit) {
        int w = ct->server->output ? ct->server->output->width : 0;
        int h = ct->server->output ? ct->server->output->height : 0;
        LOGI_COMP("Toplevel initial_commit: output %dx%d", w, h);
        if (w > 0 && h > 0) {
            wlr_xdg_toplevel_set_size(ct->toplevel, w, h);
            wlr_xdg_toplevel_set_maximized(ct->toplevel, true);
        }
    }
}

/* ------------------------------------------------------------------ */
/* Popup support                                                       */
/* ------------------------------------------------------------------ */

struct compositor_popup {
    struct wlr_xdg_popup *xdg_popup;
    struct wl_listener commit;
    struct wl_listener destroy;
};

static void on_popup_commit(struct wl_listener *listener, void *data) {
    (void)data;
    struct compositor_popup *popup = wl_container_of(listener, popup, commit);
    if (popup->xdg_popup->base->initial_commit) {
        wlr_xdg_surface_schedule_configure(popup->xdg_popup->base);
    }
}

static void on_popup_destroy(struct wl_listener *listener, void *data) {
    (void)data;
    struct compositor_popup *popup = wl_container_of(listener, popup, destroy);
    wl_list_remove(&popup->commit.link);
    wl_list_remove(&popup->destroy.link);
    free(popup);
}

static void on_new_xdg_popup(struct wl_listener *listener, void *data) {
    (void)listener;
    struct wlr_xdg_popup *xdg_popup = data;

    struct wlr_xdg_surface *parent =
        wlr_xdg_surface_try_from_wlr_surface(xdg_popup->parent);
    if (!parent) {
        wlr_log(WLR_ERROR, "Popup has no parent surface");
        return;
    }
    struct wlr_scene_tree *parent_tree = parent->data;
    if (!parent_tree) {
        wlr_log(WLR_ERROR, "Popup parent has no scene tree");
        return;
    }

    struct wlr_scene_tree *popup_tree =
        wlr_scene_xdg_surface_create(parent_tree, xdg_popup->base);
    xdg_popup->base->data = popup_tree;

    struct compositor_popup *popup = calloc(1, sizeof(*popup));
    if (!popup) {
        wlr_log(WLR_ERROR, "Failed to allocate compositor_popup");
        return;
    }
    popup->xdg_popup = xdg_popup;

    popup->commit.notify = on_popup_commit;
    wl_signal_add(&xdg_popup->base->surface->events.commit, &popup->commit);

    popup->destroy.notify = on_popup_destroy;
    wl_signal_add(&xdg_popup->events.destroy, &popup->destroy);
}

/* ------------------------------------------------------------------ */
/* Toplevel creation                                                    */
/* ------------------------------------------------------------------ */

static void on_new_xdg_toplevel(struct wl_listener *listener, void *data) {
    struct compositor_server *server =
        wl_container_of(listener, server, new_xdg_toplevel);
    struct wlr_xdg_toplevel *toplevel = data;

    struct compositor_toplevel *ct = calloc(1, sizeof(*ct));
    if (!ct) {
        wlr_log(WLR_ERROR, "Failed to allocate compositor_toplevel");
        return;
    }
    ct->server = server;
    ct->toplevel = toplevel;
    ct->scene_tree = wlr_scene_xdg_surface_create(
        &server->scene->tree, toplevel->base);
    ct->scene_tree->node.data = ct;
    toplevel->base->data = ct->scene_tree;

    /* Wire up listeners. */
    ct->map.notify = on_toplevel_map;
    wl_signal_add(&toplevel->base->surface->events.map, &ct->map);
    ct->unmap.notify = on_toplevel_unmap;
    wl_signal_add(&toplevel->base->surface->events.unmap, &ct->unmap);
    ct->commit.notify = on_toplevel_commit;
    wl_signal_add(&toplevel->base->surface->events.commit, &ct->commit);
    ct->destroy.notify = on_toplevel_destroy;
    wl_signal_add(&toplevel->events.destroy, &ct->destroy);
    ct->request_fullscreen.notify = on_request_fullscreen;
    wl_signal_add(&toplevel->events.request_fullscreen,
                  &ct->request_fullscreen);

    wl_list_insert(&server->toplevels, &ct->link);

    wlr_log(WLR_INFO, "New XDG toplevel: %s (total: %d)",
             toplevel->title ? toplevel->title : "(untitled)",
             wl_list_length(&server->toplevels));
}

/* ------------------------------------------------------------------ */
/* Resize event (pipe-based, UI thread → compositor thread)            */
/* ------------------------------------------------------------------ */

static int on_resize_event(int fd, uint32_t mask, void *data) {
    (void)mask;
    struct compositor_server *server = data;

    /* Drain all pending notification bytes (multiple resizes may coalesce). */
    char buf[32];
    ssize_t n;
    do {
        n = read(fd, buf, sizeof(buf));
    } while (n > 0);
    if (n < 0 && errno != EAGAIN) {
        wlr_log(WLR_ERROR, "resize pipe read error: %s", strerror(errno));
        return 0;
    }

    /* Ensure width/height written with a release fence on the UI thread are visible. */
    __atomic_thread_fence(__ATOMIC_ACQUIRE);

    int width = server->pending_width;
    int height = server->pending_height;

    if (!server->output || width <= 0 || height <= 0) return 0;

    int old_width = server->output->width;
    int old_height = server->output->height;

    /* Update the wlr_output mode — sends wl_output.mode to clients. */
    struct wlr_output_state state;
    wlr_output_state_init(&state);
    wlr_output_state_set_custom_mode(&state, width, height,
                                     ANDROID_DEFAULT_REFRESH);
    wlr_output_commit_state(server->output, &state);
    wlr_output_state_finish(&state);

    /* Resize tracked toplevels that were sized to the full output. */
    struct compositor_toplevel *ct;
    wl_list_for_each(ct, &server->toplevels, link) {
        if (ct->toplevel->current.fullscreen ||
            ((int)ct->toplevel->current.width == old_width &&
             (int)ct->toplevel->current.height == old_height)) {
            wlr_xdg_toplevel_set_size(ct->toplevel, width, height);
            wlr_scene_node_set_position(&ct->scene_tree->node, 0, 0);
        }
    }

    wlr_log(WLR_INFO, "Output resized to %dx%d", width, height);
    return 0;
}
/* ------------------------------------------------------------------ */
/* Pause/resume event (pipe-based, UI thread → compositor thread)      */
/* ------------------------------------------------------------------ */

static int on_pause_resume_event(int fd, uint32_t mask, void *data) {
    (void)mask;
    struct compositor_server *server = data;

    char cmd;
    ssize_t n = read(fd, &cmd, 1);
    if (n <= 0) {
        if (n < 0 && errno != EAGAIN) {
            wlr_log(WLR_ERROR, "pause_resume pipe read error: %s",
                    strerror(errno));
        }
        return 0;
    }

    if (cmd == 'P') {
        /* Pause: detach the native window. */
        if (server->output) {
            struct android_output *output =
                wl_container_of(server->output, output, wlr_output);
            android_output_detach_window(output);
        }
        server->paused = true;
        LOGI_COMP("Compositor paused — output detached");
    } else if (cmd == 'R') {
        /* Resume: attach the pending window. */
        ANativeWindow *window = server->pending_resume_window;
        server->pending_resume_window = NULL;
        if (server->output && window) {
            struct android_output *output =
                wl_container_of(server->output, output, wlr_output);
            android_output_attach_window(output, window);
            ANativeWindow_release(window);  /* attach acquires its own ref */
        }
        server->paused = false;
        LOGI_COMP("Compositor resumed — output attached");
    }

    return 0;
}


/* ------------------------------------------------------------------ */
/* Public API                                                          */
/* ------------------------------------------------------------------ */

/* Route wlr_log to Android logcat so all wlroots errors are visible. */
static void android_wlr_log_callback(enum wlr_log_importance importance,
                                      const char *fmt, va_list args) {
    int prio;
    switch (importance) {
    case WLR_ERROR: prio = ANDROID_LOG_ERROR; break;
    case WLR_INFO:  prio = ANDROID_LOG_INFO; break;
    default:        prio = ANDROID_LOG_DEBUG; break;
    }
    __android_log_vprint(prio, "wlroots", fmt, args);
}

#if WLR_HAS_GLES2_RENDERER
/**
 * Try to create a GLES2 renderer using Android's EGL.
 * Returns NULL if any step fails (caller should fall back to pixman).
 */
static struct wlr_renderer *try_create_gles2_renderer(void) {
    EGLDisplay egl_display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (egl_display == EGL_NO_DISPLAY) {
        wlr_log(WLR_DEBUG, "eglGetDisplay failed — no EGL available");
        return NULL;
    }

    EGLint major, minor;
    if (!eglInitialize(egl_display, &major, &minor)) {
        wlr_log(WLR_DEBUG, "eglInitialize failed");
        /* Don't eglTerminate — on Android, EGL_DEFAULT_DISPLAY is a
         * singleton managed by the system runtime. Terminating it could
         * affect other components sharing the display. */
        return NULL;
    }
    wlr_log(WLR_DEBUG, "EGL %d.%d initialized", major, minor);

    /* GLES2 context with no config — requires EGL_KHR_no_config_context.
     * If the extension isn't present, eglCreateContext fails and we fall
     * through to pixman. This is the correct behavior for devices that
     * don't support configless contexts. */
    EGLint ctx_attribs[] = {
        EGL_CONTEXT_CLIENT_VERSION, 2,
        EGL_NONE,
    };
    EGLContext egl_context = eglCreateContext(
        egl_display, EGL_NO_CONFIG_KHR, EGL_NO_CONTEXT, ctx_attribs);
    if (egl_context == EGL_NO_CONTEXT) {
        wlr_log(WLR_DEBUG, "eglCreateContext failed (0x%x) — "
                 "device may lack EGL_KHR_no_config_context",
                 eglGetError());
        /* Don't eglTerminate — display may be shared with Android system */
        return NULL;
    }

    struct wlr_egl *egl = wlr_egl_create_with_context(egl_display, egl_context);
    if (!egl) {
        wlr_log(WLR_DEBUG, "wlr_egl_create_with_context failed");
        eglDestroyContext(egl_display, egl_context);
        return NULL;
    }

    struct wlr_renderer *renderer = wlr_gles2_renderer_create(egl);
    if (!renderer) {
        wlr_log(WLR_DEBUG, "wlr_gles2_renderer_create failed");
        wlr_egl_destroy(egl);
        return NULL;
    }

    wlr_log(WLR_DEBUG, "GLES2 renderer created successfully");
    return renderer;
}
#endif /* WLR_HAS_GLES2_RENDERER */

struct compositor_server *compositor_server_create(ANativeWindow *window,
                                                   bool xwayland_enabled) {
    struct compositor_server *server = calloc(1, sizeof(*server));
    if (!server) {
        wlr_log(WLR_ERROR, "Failed to allocate compositor_server");
        return NULL;
    }

    /* Route all wlr_log messages to Android logcat instead of stderr. */
    wlr_log_init(WLR_DEBUG, android_wlr_log_callback);

    /* Disable direct scanout — it bypasses the scene compositor and passes
     * client buffers directly to the output. This causes wrong-sized buffers
     * (e.g. 250x250 weston-simple-shm) to be presented on the 1080x2400 output. */
    setenv("WLR_SCENE_DISABLE_DIRECT_SCANOUT", "1", 1);

    server->native_window = window;

    /* ---- Wayland display ---- */
    server->wl_display = wl_display_create();
    if (!server->wl_display) {
        wlr_log(WLR_ERROR, "wl_display_create failed");
        goto err_free;
    }

    /* ---- Backend ---- */
    server->backend = android_backend_create(server->wl_display);
    if (!server->backend) {
        goto err_display;
    }

    /* ---- Renderer (GLES2 with pixman fallback) ---- */
#if WLR_HAS_GLES2_RENDERER
    server->renderer = try_create_gles2_renderer();
#endif
    if (!server->renderer) {
        wlr_log(WLR_INFO, "Using pixman renderer (GLES2 not available)");
        server->renderer = wlr_renderer_autocreate(server->backend);
    }
    if (!server->renderer) {
        wlr_log(WLR_ERROR, "Failed to create any renderer");
        goto err_backend;
    }

    wlr_renderer_init_wl_display(server->renderer, server->wl_display);

    /* Advertise linux-dmabuf-v1 with manually constructed feedback.
     * wlr_linux_dmabuf_v1_create_with_renderer() fails on Android because
     * wlr_renderer_get_drm_fd() returns -1 (no DRM device). We build the
     * feedback struct manually with our supported AHardwareBuffer formats. */
    {
        struct wlr_linux_dmabuf_feedback_v1 feedback = {0};
        /* Use renderD128 device ID to match the stub /dev/dri/renderD128
         * that proot bind-mounts. major=226, minor=128 */
        feedback.main_device = makedev(226, 128);

        struct wlr_linux_dmabuf_feedback_v1_tranche *tranche =
            wlr_linux_dmabuf_feedback_add_tranche(&feedback);
        if (tranche) {
            tranche->target_device = feedback.main_device;

            /* Formats matching drm_format_table.h — the 3 AHB-backed formats */
            static const struct { uint32_t format; uint64_t modifier; } fmts[] = {
                { DRM_FORMAT_ABGR8888, DRM_FORMAT_MOD_INVALID },
                { DRM_FORMAT_XBGR8888, DRM_FORMAT_MOD_INVALID },
                { DRM_FORMAT_RGB565,   DRM_FORMAT_MOD_INVALID },
            };
            for (size_t i = 0; i < sizeof(fmts)/sizeof(fmts[0]); i++) {
                wlr_drm_format_set_add(&tranche->formats,
                    fmts[i].format, fmts[i].modifier);
            }
        }

        struct wlr_linux_dmabuf_v1 *dmabuf =
            wlr_linux_dmabuf_v1_create(server->wl_display, 5, &feedback);
        wlr_linux_dmabuf_feedback_v1_finish(&feedback);

        if (dmabuf) {
            wlr_log(WLR_INFO, "linux-dmabuf-v1 advertised (manual feedback, "
                     "3 formats, device 226:128)");
        } else {
            wlr_log(WLR_ERROR, "linux-dmabuf-v1 manual creation failed");
        }
    }

    /* ---- Allocator ---- */
    server->allocator = ahb_allocator_create();
    if (!server->allocator) {
        goto err_renderer;
    }

    /* ---- Output (wraps the ANativeWindow) ---- */
    struct android_backend *ab =
        wl_container_of(server->backend, ab, backend);
    struct wlr_output *wlr_output = android_output_create(ab, window);
    if (!wlr_output) {
        goto err_alloc;
    }

    /* ---- Scene graph + output layout ---- */
    server->scene = wlr_scene_create();
    server->output_layout = wlr_output_layout_create(server->wl_display);
    server->scene_output_layout =
        wlr_scene_attach_output_layout(server->scene, server->output_layout);

    /* ---- new_output listener — wired up before backend start ---- */
    server->new_output.notify = on_new_output;
    wl_signal_add(&server->backend->events.new_output, &server->new_output);

    /* ---- Frame + request_state — set in on_new_output ---- */
    server->output_frame.notify = on_output_frame;
    server->output_request_state.notify = on_output_request_state;

    /* ---- Multi-window toplevel tracking ---- */
    wl_list_init(&server->toplevels);

    /* ---- Client tracking (for lifecycle management) ---- */
    server->client_count = 0;
    server->client_created.notify = handle_client_created;
    wl_display_add_client_created_listener(server->wl_display,
                                           &server->client_created);

    /* ---- Resize pipe (UI thread → compositor thread) ---- */
    if (pipe2(server->resize_pipe, O_CLOEXEC | O_NONBLOCK) == 0) {
        struct wl_event_loop *ev_loop =
            wl_display_get_event_loop(server->wl_display);
        server->resize_event_source = wl_event_loop_add_fd(
            ev_loop, server->resize_pipe[0], WL_EVENT_READABLE,
            on_resize_event, server);
    } else {
        wlr_log(WLR_ERROR, "Failed to create resize pipe");
        server->resize_pipe[0] = -1;
        server->resize_pipe[1] = -1;
    }
    /* ---- Pause/resume pipe (UI thread → compositor thread) ---- */
    server->paused = false;
    server->pending_resume_window = NULL;
    if (pipe2(server->pause_resume_pipe, O_CLOEXEC | O_NONBLOCK) == 0) {
        struct wl_event_loop *pr_loop =
            wl_display_get_event_loop(server->wl_display);
        server->pause_resume_event_source = wl_event_loop_add_fd(
            pr_loop, server->pause_resume_pipe[0], WL_EVENT_READABLE,
            on_pause_resume_event, server);
    } else {
        wlr_log(WLR_ERROR, "Failed to create pause_resume pipe");
        server->pause_resume_pipe[0] = -1;
        server->pause_resume_pipe[1] = -1;
    }


    /* ---- Wayland protocols ---- */
    server->compositor =
        wlr_compositor_create(server->wl_display, 5, server->renderer);
    wlr_subcompositor_create(server->wl_display);
    wlr_data_device_manager_create(server->wl_display);
    wlr_viewporter_create(server->wl_display);
    wlr_xdg_decoration_manager_v1_create(server->wl_display);
    wlr_fractional_scale_manager_v1_create(server->wl_display, 1);
    wlr_single_pixel_buffer_manager_v1_create(server->wl_display);
    wlr_xdg_output_manager_v1_create(server->wl_display,
                                     server->output_layout);
    wlr_primary_selection_v1_device_manager_create(server->wl_display);
    wlr_cursor_shape_manager_v1_create(server->wl_display, 1);

    server->xdg_shell = wlr_xdg_shell_create(server->wl_display, 3);
    server->seat = wlr_seat_create(server->wl_display, "seat0");

    server->new_xdg_toplevel.notify = on_new_xdg_toplevel;
    wl_signal_add(&server->xdg_shell->events.new_toplevel,
                  &server->new_xdg_toplevel);

    server->new_xdg_popup.notify = on_new_xdg_popup;
    wl_signal_add(&server->xdg_shell->events.new_popup,
                  &server->new_xdg_popup);

    /* ---- Input handler (keyboard, touch, pointer) ---- */
    if (input_handler_init(server) != 0) {
        wlr_log(WLR_ERROR, "input_handler_init failed");
        /* Non-fatal: compositor works without input forwarding */
    }

    /* ---- Text input / IME handler ---- */
    if (text_input_handler_init(server) != 0) {
        wlr_log(WLR_ERROR, "text_input_handler_init failed");
        /* Non-fatal: compositor works without text input */
    }

    /* Start AHB registry receiver — listens for AHB handles from the virgl
     * server process over a Unix domain socket. */
    const char *ahb_socket = getenv("AHB_REGISTRY_SOCKET");
    if (ahb_socket) {
        if (ahb_registry_receiver_start(ahb_socket) != 0) {
            wlr_log(WLR_ERROR, "Failed to start AHB registry receiver");
            /* Non-fatal: GPU rendering will fail but compositor can still run */
        }
    } else {
        wlr_log(WLR_INFO, "AHB_REGISTRY_SOCKET not set, AHB registry receiver disabled");
    }

#if WLR_HAS_XWAYLAND
    /* ---- XWayland (X11 app support) ---- */
    if (xwayland_enabled) {
        server->xwayland = wlr_xwayland_create(server->wl_display,
                                                server->compositor, true);
        if (server->xwayland) {
            server->new_xwayland_surface.notify = on_new_xwayland_surface;
            wl_signal_add(&server->xwayland->events.new_surface,
                          &server->new_xwayland_surface);
            wlr_xwayland_set_seat(server->xwayland, server->seat);
            wlr_log(WLR_INFO, "XWayland ready (lazy mode), DISPLAY=%s",
                    server->xwayland->display_name);
        } else {
            wlr_log(WLR_ERROR, "Failed to start XWayland");
        }
    } else {
        wlr_log(WLR_INFO, "XWayland disabled by configuration");
    }
#endif

    /* ---- Socket ---- */
    const char *socket = wl_display_add_socket_auto(server->wl_display);
    if (!socket) {
        wlr_log(WLR_ERROR, "wl_display_add_socket_auto failed");
        goto err_alloc;
    }
    server->socket_path = socket;
    wlr_log(WLR_INFO, "Wayland socket: %s", socket);

    /*
     * The Wayland socket is private — it lives in cacheDir/wayland-runtime/
     * and is accessed by the proot child process running under the same UID.
     * No chmod is needed; the default socket permissions (0755 dir, 0700 socket)
     * are sufficient for same-UID access.
     */

    /* ---- Start backend (emits new_output) ---- */
    if (!wlr_backend_start(server->backend)) {
        wlr_log(WLR_ERROR, "wlr_backend_start failed");
        goto err_alloc;
    }

    wlr_log(WLR_INFO, "Compositor server created successfully");
    return server;

err_alloc:
    if (server->allocator) {
        wlr_allocator_destroy(server->allocator);
    }
err_renderer:
    if (server->renderer) {
        wlr_renderer_destroy(server->renderer);
    }
err_backend:
    wlr_backend_destroy(server->backend);
err_display:
    wl_display_destroy(server->wl_display);
err_free:
    free(server);
    return NULL;
}

void compositor_server_run(struct compositor_server *server) {
    wlr_log(WLR_INFO, "Entering Wayland event loop");
    wl_display_run(server->wl_display);
    wlr_log(WLR_INFO, "Wayland event loop exited");
}

void compositor_server_stop(struct compositor_server *server) {
    if (server && server->wl_display) {
        wl_display_terminate(server->wl_display);
    }
}

void compositor_server_destroy(struct compositor_server *server) {
    if (!server) {
        return;
    }

    /* Tear down in reverse creation order. */
#if WLR_HAS_XWAYLAND
    if (server->xwayland) {
        wl_list_remove(&server->new_xwayland_surface.link);
        wlr_xwayland_destroy(server->xwayland);
        server->xwayland = NULL;
    }
#endif

    wl_list_remove(&server->client_created.link);
    wl_display_destroy_clients(server->wl_display);

    /* Clean up resize pipe. */
    if (server->resize_event_source) {
        wl_event_source_remove(server->resize_event_source);
    }
    if (server->resize_pipe[0] >= 0) close(server->resize_pipe[0]);
    if (server->resize_pipe[1] >= 0) close(server->resize_pipe[1]);
    /* Clean up pause/resume pipe. */
    if (server->pause_resume_event_source) {
        wl_event_source_remove(server->pause_resume_event_source);
    }
    if (server->pause_resume_pipe[0] >= 0) close(server->pause_resume_pipe[0]);
    if (server->pause_resume_pipe[1] >= 0) close(server->pause_resume_pipe[1]);
    if (server->pending_resume_window) {
        ANativeWindow_release(server->pending_resume_window);
        server->pending_resume_window = NULL;
    }


    /* Destroy AHB registry. */
    ahb_registry_receiver_stop();
    ahb_registry_destroy();

    /* Clean up text input handler (before input handler — depends on keyboard). */
    text_input_handler_destroy(server);

    /* Clean up input handler. */
    input_handler_destroy(server);

    /* Remove listeners before destroying the objects they reference. */
    wl_list_remove(&server->new_output.link);
    wl_list_remove(&server->new_xdg_toplevel.link);
    wl_list_remove(&server->new_xdg_popup.link);
    if (server->output) {
        wl_list_remove(&server->output_frame.link);
        wl_list_remove(&server->output_request_state.link);
    }

    /*
     * Toplevels are freed by wl_display_destroy_clients() triggering
     * on_toplevel_destroy for each connected client's surfaces.
     * Any still lingering are leaked intentionally — the server is
     * about to be freed anyway.
     */

    wlr_scene_node_destroy(&server->scene->tree.node);
    wlr_allocator_destroy(server->allocator);
    wlr_renderer_destroy(server->renderer);
    wlr_backend_destroy(server->backend);
    wl_display_destroy(server->wl_display);
    free(server);
}

const char *compositor_server_get_socket(struct compositor_server *server) {
    return server ? server->socket_path : NULL;
}

void compositor_server_resize_output(struct compositor_server *server,
                                     int width, int height) {
    if (!server || server->resize_pipe[1] < 0) return;
    server->pending_width = width;
    server->pending_height = height;
    __atomic_thread_fence(__ATOMIC_RELEASE);
    char c = 'r';
    write(server->resize_pipe[1], &c, 1);
}

void compositor_server_pause(struct compositor_server *server) {
    if (!server || server->pause_resume_pipe[1] < 0) return;
    char c = 'P';
    write(server->pause_resume_pipe[1], &c, 1);
}

void compositor_server_resume(struct compositor_server *server,
                              ANativeWindow *window) {
    if (!server || server->pause_resume_pipe[1] < 0 || !window) return;
    /* Acquire a reference for the compositor thread to consume. */
    ANativeWindow_acquire(window);
    server->pending_resume_window = window;
    __atomic_thread_fence(__ATOMIC_RELEASE);
    char c = 'R';
    write(server->pause_resume_pipe[1], &c, 1);
}

int compositor_server_get_client_count(struct compositor_server *server) {
    return server ? __atomic_load_n(&server->client_count, __ATOMIC_SEQ_CST) : 0;
}

const char *compositor_server_get_xwayland_display(struct compositor_server *server) {
#if WLR_HAS_XWAYLAND
    return (server && server->xwayland) ? server->xwayland->display_name : NULL;
#else
    (void)server;
    return NULL;
#endif
}

