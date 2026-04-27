/*
 * android_output.h — wlroots output backed by ANativeWindow
 *
 * Uses ASurfaceTransaction when available (API 29+) for zero-copy buffer
 * presentation via AHardwareBuffer, with an ANativeWindow_lock/memcpy
 * fallback for CPU-rendered buffers.
 */
#ifndef ANDROID_OUTPUT_H
#define ANDROID_OUTPUT_H

#include <android/native_window.h>
#include <wayland-server-core.h>
#include <wlr/types/wlr_output.h>

/* Forward declaration — full definition in android_backend.h */
struct android_backend;

#define ANDROID_DEFAULT_REFRESH (60 * 1000) /* 60 Hz in mHz */

struct android_output {
    struct wlr_output wlr_output;    /* MUST be first member */
    struct android_backend *backend;
    struct wl_list link;             /* android_backend.outputs */

    ANativeWindow *native_window;
    /* ASurfaceControl handle — NULL when SurfaceControl API is unavailable. */
    void *surface_control;

    struct wl_event_source *frame_timer;
    int frame_delay_ms;              /* 1000 / 60 ≈ 16 ms */
};

/**
 * Create an output that presents to @window.
 * The output is added to @backend->outputs but is not signalled until
 * wlr_backend_start().
 */
struct wlr_output *android_output_create(struct android_backend *backend,
                                         ANativeWindow *window);

#endif /* ANDROID_OUTPUT_H */
