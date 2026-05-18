/*
 * android_output.c — wlroots output implementation for Android
 *
 * Commit path:
 *   1. If the buffer came from our AHB allocator **and** ASurfaceControl is
 *      available, present it zero-copy via ASurfaceTransaction_setBuffer.
 *   2. Otherwise, fall back to CPU copy: wlr_buffer_begin_data_ptr_access →
 *      ANativeWindow_lock → memcpy row-by-row → ANativeWindow_unlockAndPost.
 *
 * Frame pacing uses a wl_event_loop timer that fires every ~16 ms (60 Hz).
 */
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <android/log.h>
#include <android/native_window.h>
#include <android/surface_control.h>
#include <android/hardware_buffer.h>
#include <wayland-server-core.h>
#include <wlr/backend.h>
#include <wlr/interfaces/wlr_output.h>
#include <wlr/types/wlr_output.h>
#include <wlr/util/log.h>

#include "android_backend.h"
#include "android_output.h"
#include "ahb_allocator.h"
#include <drm_fourcc.h>

#define LOG_TAG "AndroidOutput"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

/* ------------------------------------------------------------------ */
/* Forward declarations                                                */
/* ------------------------------------------------------------------ */
static bool output_test(struct wlr_output *wlr_output,
                        const struct wlr_output_state *state);
static bool output_commit(struct wlr_output *wlr_output,
                          const struct wlr_output_state *state);
static void output_destroy(struct wlr_output *wlr_output);

static const struct wlr_output_impl output_impl = {
    .set_cursor = NULL,
    .move_cursor = NULL,
    .destroy = output_destroy,
    .test = output_test,
    .commit = output_commit,
    .get_gamma_size = NULL,
    .get_cursor_formats = NULL,
    .get_cursor_sizes = NULL,
    .get_primary_formats = NULL,
};

/* ------------------------------------------------------------------ */
/* Helpers                                                             */
/* ------------------------------------------------------------------ */

/**
 * Bytes per pixel for the ANativeWindow_Buffer format returned by
 * ANativeWindow_lock.  We only support 32-bpp and 16-bpp for now.
 */
static int format_bpp(int32_t format) {
    switch (format) {
    case 1: /* WINDOW_FORMAT_RGBA_8888 */
    case 2: /* WINDOW_FORMAT_RGBX_8888 */
        return 4;
    case 4: /* WINDOW_FORMAT_RGB_565 */
        return 2;
    default:
        return 4;
    }
}

/**
 * Present a buffer from our AHB allocator via ASurfaceTransaction (zero-copy).
 * Returns true on success, false if the path is unavailable.
 */
static bool commit_ahb_surface_control(struct android_output *output,
                                       struct ahb_buffer *abuf) {
    ASurfaceControl *sc = (ASurfaceControl *)output->surface_control;
    if (!sc) {
        return false;
    }

    ASurfaceTransaction *txn = ASurfaceTransaction_create();
    if (!txn) {
        return false;
    }

    ASurfaceTransaction_setBuffer(txn, sc, abuf->ahb, -1 /* no fence */);
    ASurfaceTransaction_setVisibility(txn, sc,
                                      1 /* ASURFACE_TRANSACTION_VISIBILITY_SHOW */);
    ASurfaceTransaction_apply(txn);
    ASurfaceTransaction_delete(txn);
    return true;
}

/**
 * Present a buffer via CPU copy (ANativeWindow_lock / memcpy / unlockAndPost).
 */
static bool commit_cpu_copy(struct android_output *output,
                            struct wlr_buffer *buffer) {
    void *src_data = NULL;
    uint32_t src_fmt = 0;
    size_t src_stride = 0;

    if (!wlr_buffer_begin_data_ptr_access(buffer,
            WLR_BUFFER_DATA_PTR_ACCESS_READ, &src_data, &src_fmt, &src_stride)) {
        wlr_log(WLR_ERROR, "begin_data_ptr_access failed");
        return false;
    }

    ANativeWindow_Buffer win_buf;
    if (ANativeWindow_lock(output->native_window, &win_buf, NULL) != 0) {
        wlr_log(WLR_ERROR, "ANativeWindow_lock failed");
        wlr_buffer_end_data_ptr_access(buffer);
        return false;
    }

    int bpp = format_bpp(win_buf.format);
    if (win_buf.format != 1 /* WINDOW_FORMAT_RGBA_8888 */) {
        wlr_log(WLR_DEBUG, "CPU blit: unexpected window format %d (bpp=%d)",
                win_buf.format, bpp);
    }
    int copy_width = buffer->width < win_buf.width
                         ? buffer->width : win_buf.width;
    int copy_height = buffer->height < win_buf.height
                          ? buffer->height : win_buf.height;
    size_t row_bytes = (size_t)copy_width * bpp;

    const uint8_t *src = (const uint8_t *)src_data;
    uint8_t *dst = (uint8_t *)win_buf.bits;
    size_t dst_stride = (size_t)win_buf.stride * bpp;

    for (int y = 0; y < copy_height; y++) {
        memcpy(dst + y * dst_stride, src + y * src_stride, row_bytes);
    }

    ANativeWindow_unlockAndPost(output->native_window);
    wlr_buffer_end_data_ptr_access(buffer);
    return true;
}

/* ------------------------------------------------------------------ */
/* Frame timer                                                         */
/* ------------------------------------------------------------------ */

static int frame_timer_handler(void *data) {
    struct android_output *output = data;
    static int timer_count = 0;
    if (++timer_count <= 5 || timer_count % 60 == 0) {
        LOGD("frame_timer_handler #%d: enabled=%d, frame_pending=%d",
             timer_count, output->wlr_output.enabled,
             output->wlr_output.frame_pending);
    }
    wlr_output_send_frame(&output->wlr_output);
    /* Always re-arm the timer regardless of whether the frame signal was
     * handled. The timer must keep firing to drive the rendering loop.
     * Previously the timer was only re-armed inside output_commit(), but
     * if on_output_frame never runs (e.g. the initial output commit failed
     * and wlr_output_send_frame skipped the signal), the timer would stop
     * forever after one fire. */
    wl_event_source_timer_update(output->frame_timer, output->frame_delay_ms);
    return 0;
}

/* ------------------------------------------------------------------ */
/* wlr_output_impl                                                     */
/* ------------------------------------------------------------------ */

static bool output_test(struct wlr_output *wlr_output,
                        const struct wlr_output_state *state) {
    (void)wlr_output;
    (void)state;
    /* Accept all state changes. The Android backend handles BUFFER, MODE,
     * and ENABLED in output_commit; other fields (RENDER_FORMAT, DAMAGE,
     * GAMMA_LUT, etc.) are managed by wlroots internally and don't need
     * backend validation. Rejecting unknown bits caused swapchain test
     * failures — wlr_scene_output_build_state sets bits beyond the three
     * we originally accepted. */
    return true;
}

static bool output_commit(struct wlr_output *wlr_output,
                          const struct wlr_output_state *state) {
    struct android_output *output =
        wl_container_of(wlr_output, output, wlr_output);

    if (!output_test(wlr_output, state)) {
        return false;
    }

    /* When the native window is detached (paused), skip buffer presentation
     * but still accept the commit so wlroots state tracking stays consistent. */
    if (!output->native_window) {
        return true;
    }

    static int commit_count = 0;
    if (++commit_count <= 10) {
        LOGI("output_commit #%d: committed=0x%x (enabled=%d, mode=%d, buffer=%d)",
             commit_count,
             state->committed,
             !!(state->committed & WLR_OUTPUT_STATE_ENABLED),
             !!(state->committed & WLR_OUTPUT_STATE_MODE),
             !!(state->committed & WLR_OUTPUT_STATE_BUFFER));
    }

    /* If the output mode changed, update the ANativeWindow buffer geometry. */
    if (state->committed & WLR_OUTPUT_STATE_MODE) {
        ANativeWindow_setBuffersGeometry(output->native_window,
            state->custom_mode.width, state->custom_mode.height,
            AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM);
    }

    if (state->committed & WLR_OUTPUT_STATE_BUFFER) {
        struct wlr_buffer *buffer = state->buffer;
        bool presented = false;

        /* Try zero-copy AHB path first. */
        struct ahb_buffer *abuf = ahb_buffer_try_from_wlr(buffer);
        if (abuf) {
            presented = commit_ahb_surface_control(output, abuf);
        }

        /* Fallback: CPU copy via ANativeWindow_lock. */
        if (!presented) {
            presented = commit_cpu_copy(output, buffer);
        }

        if (presented) {
            static int present_count = 0;
            if (++present_count <= 5 || present_count % 60 == 0) {
                LOGI("Buffer presented #%d: %dx%d (cpu_copy)", present_count,
                     buffer->width, buffer->height);
            }
        }

        struct wlr_output_event_present event = {
            .output = wlr_output,
            .commit_seq = wlr_output->commit_seq + 1,
            .presented = presented,
        };
        wlr_output_send_present(wlr_output, &event);
    }

    /* Re-arm the frame timer so the next frame is requested. */
    wl_event_source_timer_update(output->frame_timer, output->frame_delay_ms);

    return true;
}

static void output_destroy(struct wlr_output *wlr_output) {
    struct android_output *output =
        wl_container_of(wlr_output, output, wlr_output);

    wl_list_remove(&output->link);

    if (output->frame_timer) {
        wl_event_source_remove(output->frame_timer);
    }

    if (output->surface_control) {
        ASurfaceControl_release((ASurfaceControl *)output->surface_control);
    }

    if (output->native_window) {
        ANativeWindow_release(output->native_window);
    }

    free(output);
}

/* ------------------------------------------------------------------ */
/* Public API                                                          */
/* ------------------------------------------------------------------ */

void android_output_detach_window(struct android_output *output) {
    /* Disarm frame timer so no more frames fire while detached. */
    wl_event_source_timer_update(output->frame_timer, 0);

    /* Disable the wlr_output. */
    struct wlr_output_state state;
    wlr_output_state_init(&state);
    wlr_output_state_set_enabled(&state, false);
    wlr_output_commit_state(&output->wlr_output, &state);
    wlr_output_state_finish(&state);

    /* Release ASurfaceControl if present. */
    if (output->surface_control) {
        ASurfaceControl_release((ASurfaceControl *)output->surface_control);
        output->surface_control = NULL;
    }

    /* Release ANativeWindow. */
    if (output->native_window) {
        ANativeWindow_release(output->native_window);
        output->native_window = NULL;
    }

    LOGI("Output detached (window released, frame timer disarmed)");
}

void android_output_attach_window(struct android_output *output,
                                  ANativeWindow *window) {
    /* Acquire new window reference. */
    ANativeWindow_acquire(window);
    output->native_window = window;

    /* Recreate ASurfaceControl for zero-copy presentation. */
    output->surface_control =
        ASurfaceControl_createFromWindow(window, "coder-compositor");

    /* Update buffer geometry to current output dimensions. */
    ANativeWindow_setBuffersGeometry(output->native_window,
        output->wlr_output.width, output->wlr_output.height,
        AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM);

    /* Re-enable the wlr_output. */
    struct wlr_output_state state;
    wlr_output_state_init(&state);
    wlr_output_state_set_enabled(&state, true);
    wlr_output_commit_state(&output->wlr_output, &state);
    wlr_output_state_finish(&state);

    /* Re-arm frame timer. */
    wl_event_source_timer_update(output->frame_timer, output->frame_delay_ms);

    LOGI("Output attached: %dx%d, frame_delay=%dms",
         output->wlr_output.width, output->wlr_output.height,
         output->frame_delay_ms);
}

struct wlr_output *android_output_create(struct android_backend *backend,
                                         ANativeWindow *window) {
    struct android_output *output = calloc(1, sizeof(*output));
    if (!output) {
        wlr_log(WLR_ERROR, "Failed to allocate android_output");
        return NULL;
    }

    int width = ANativeWindow_getWidth(window);
    int height = ANativeWindow_getHeight(window);

    /* Build initial output state with the window's native resolution. */
    struct wlr_output_state initial_state;
    wlr_output_state_init(&initial_state);
    wlr_output_state_set_custom_mode(&initial_state, width, height,
                                     ANDROID_DEFAULT_REFRESH);

    struct wl_event_loop *loop =
        wl_display_get_event_loop(backend->wl_display);

    wlr_output_init(&output->wlr_output, &backend->backend, &output_impl,
                     loop, &initial_state);
    wlr_output_state_finish(&initial_state);

    /* Override the default render format. wlroots defaults to DRM_FORMAT_XRGB8888
     * but Android's AHardwareBuffer only supports ABGR8888 (R8G8B8A8_UNORM) byte
     * order. Without this, every swapchain allocation fails and no frames render. */
    output->wlr_output.render_format = DRM_FORMAT_ABGR8888;
    LOGI("Render format set to DRM_FORMAT_ABGR8888 (0x%08x)", DRM_FORMAT_ABGR8888);

    wlr_output_set_name(&output->wlr_output, "ANDROID-1");

    output->backend = backend;
    output->native_window = window;
    ANativeWindow_acquire(window);  /* Take our own reference */
    output->frame_delay_ms = 1000 / 60; /* ~16 ms for 60 Hz */

    /* Try to create an ASurfaceControl for zero-copy buffer presentation. */
    output->surface_control =
        ASurfaceControl_createFromWindow(window, "coder-compositor");

    /* Frame timer — fires once per frame interval. */
    output->frame_timer =
        wl_event_loop_add_timer(loop, frame_timer_handler, output);
    /* Arm the timer to kick off the first frame.
     * wl_event_loop_add_timer() creates disarmed timers — without this,
     * the frame loop never starts because output_commit() (which re-arms
     * the timer) is only reached via the frame callback path. */
    wl_event_source_timer_update(output->frame_timer, output->frame_delay_ms);
    LOGI("Frame timer armed: delay=%dms, output=%p",
         output->frame_delay_ms, (void *)output);

    /* Add to backend output list. */
    wl_list_insert(&backend->outputs, &output->link);

    /* If backend is already started, emit the signal immediately. */
    if (backend->started) {
        wl_signal_emit_mutable(&backend->backend.events.new_output,
                               &output->wlr_output);
    }

    LOGI("Android output created: %dx%d @ %d mHz",
         width, height, ANDROID_DEFAULT_REFRESH);
    return &output->wlr_output;
}
