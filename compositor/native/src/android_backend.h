/*
 * android_backend.h — wlroots backend for Android
 *
 * Follows the headless backend pattern: embeds struct wlr_backend as the
 * first member so wl_container_of upcasts work correctly.
 */
#ifndef ANDROID_BACKEND_H
#define ANDROID_BACKEND_H

#include <stdbool.h>
#include <wayland-server-core.h>
#include <wlr/backend.h>

struct android_backend {
    struct wlr_backend backend;   /* MUST be first member */
    struct wl_display *wl_display;
    struct wl_list outputs;       /* android_output.link */
    bool started;
};

/**
 * Create an Android backend.  Does not start it — call wlr_backend_start()
 * after attaching outputs and a renderer.
 */
struct wlr_backend *android_backend_create(struct wl_display *display);

/**
 * Returns true if @backend was created by android_backend_create().
 */
bool android_backend_is_android(struct wlr_backend *backend);

#endif /* ANDROID_BACKEND_H */
