/*
 * compositor_server.h — Main Wayland compositor server
 *
 * Ties together the Android backend, AHB allocator, wlroots scene graph,
 * XDG shell, and seat into a complete compositor that can be driven from
 * JNI on a background thread.
 */
#ifndef COMPOSITOR_SERVER_H
#define COMPOSITOR_SERVER_H

#include <stdbool.h>

#include <android/native_window.h>
#include <wayland-server-core.h>
#include <wlr/config.h>
#include <wlr/types/wlr_scene.h>
#include <wlr/types/wlr_output_layout.h>
#include <wlr/types/wlr_keyboard.h>
#include <wlr/types/wlr_xdg_shell.h>

#if WLR_HAS_XWAYLAND
struct wlr_xwayland;
#endif

struct compositor_server {
    struct wl_display *wl_display;
    struct wlr_backend *backend;
    struct wlr_renderer *renderer;
    struct wlr_allocator *allocator;

    struct wlr_scene *scene;
    struct wlr_scene_output_layout *scene_output_layout;
    struct wlr_output_layout *output_layout;
    struct wlr_scene_output *scene_output;

    struct wlr_xdg_shell *xdg_shell;
    struct wlr_compositor *compositor;
    struct wlr_seat *seat;

    /* Output */
    struct wlr_output *output;
    ANativeWindow *native_window;

    /* Multi-window toplevel tracking (MRU order — head = focused) */
    struct wl_list toplevels;        /* compositor_toplevel.link */

    /* Resize signaling (UI thread → compositor thread via pipe) */
    struct wl_event_source *resize_event_source;
    int resize_pipe[2];              /* [0]=read, [1]=write */
    int pending_width;
    int pending_height;

    /* Input (created/destroyed by input_handler) */
    struct wlr_keyboard *keyboard;
    int input_pipe[2];               /* [0]=read, [1]=write */
    struct wl_event_source *input_event_source;

    /* Text input / IME (created/destroyed by text_input_handler) */
    struct wl_global *text_input_manager;
    int ime_request_pipe[2];         /* [0]=read (Kotlin side), [1]=write (compositor) */

    /* Pause/resume signaling (UI thread → compositor thread via pipe) */
    bool paused;
    ANativeWindow *pending_resume_window;
    int pause_resume_pipe[2];            /* [0]=read, [1]=write */
    struct wl_event_source *pause_resume_event_source;

    /* Listeners */
    struct wl_listener new_output;
    struct wl_listener new_xdg_toplevel;
    struct wl_listener new_xdg_popup;
    struct wl_listener output_frame;
    struct wl_listener output_request_state;

    const char *socket_path; /* Wayland socket name (e.g. "wayland-0") */

    /* Client tracking (for lifecycle management) */
    int client_count;
    struct wl_listener client_created;

#if WLR_HAS_XWAYLAND
    /* XWayland integration (X11 app support) */
    struct wlr_xwayland *xwayland;
    struct wl_listener new_xwayland_surface;
#endif
};

/**
 * Per-toplevel tracking state.  Stored in compositor_server.toplevels.
 */
struct compositor_toplevel {
    struct wl_list link;                /* compositor_server.toplevels */
    struct compositor_server *server;
    struct wlr_xdg_toplevel *toplevel;
    struct wlr_scene_tree *scene_tree;

    bool rendering_only;  /* true for EGL rendering surface (touch redirected to Ozone) */

    struct wl_listener map;
    struct wl_listener unmap;
    struct wl_listener commit;
    struct wl_listener destroy;
    struct wl_listener request_fullscreen;
};

/**
 * Create and initialise the compositor.  Does NOT start the event loop.
 * @window is retained (with ANativeWindow_acquire semantics handled
 * internally) for the lifetime of the server.
 * @xwayland_enabled controls whether XWayland (X11 app support) is
 * initialised.  When false, XWayland is skipped even if compiled in.
 */
struct compositor_server *compositor_server_create(ANativeWindow *window,
                                                   bool xwayland_enabled);

/**
 * Run the Wayland event loop.  Blocks until compositor_server_stop() is
 * called from another thread.
 */
void compositor_server_run(struct compositor_server *server);

/**
 * Ask the event loop to exit.  Thread-safe (wl_display_terminate is safe
 * to call from any thread).
 */
void compositor_server_stop(struct compositor_server *server);

/**
 * Tear down all compositor resources.  Must be called after the event
 * loop has returned.
 */
void compositor_server_destroy(struct compositor_server *server);

/**
 * Return the Wayland socket name so that clients can connect (set as
 * WAYLAND_DISPLAY).
 */
const char *compositor_server_get_socket(struct compositor_server *server);

/**
 * Request the compositor to resize its output.  Thread-safe — can be called
 * from any thread (typically the Android UI thread via JNI).  The actual
 * resize is applied on the compositor event-loop thread.
 */
void compositor_server_resize_output(struct compositor_server *server,
                                     int width, int height);

/**
 * Return the number of currently connected Wayland clients.
 */
int compositor_server_get_client_count(struct compositor_server *server);

/**
 * Return the XWayland DISPLAY name (e.g. ":0") if XWayland is running,
 * or NULL if XWayland is disabled, failed to start, or not compiled in.
 */
const char *compositor_server_get_xwayland_display(struct compositor_server *server);


/**
 * Pause the compositor — detach the native window and stop rendering.
 * Thread-safe (signals via pipe; actual work runs on the compositor thread).
 */
void compositor_server_pause(struct compositor_server *server);

/**
 * Resume the compositor — attach a new native window and restart rendering.
 * Thread-safe (signals via pipe; actual work runs on the compositor thread).
 * Acquires its own reference to @window.
 */
void compositor_server_resume(struct compositor_server *server,
                              ANativeWindow *window);

#endif /* COMPOSITOR_SERVER_H */
