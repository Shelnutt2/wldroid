/*
 * android_backend.c — wlroots backend implementation for Android
 *
 * Modelled after the headless backend: maintains a list of outputs and
 * emits new_output on start().  Sets WLR_BUFFER_CAP_DATA_PTR and
 * WLR_BUFFER_CAP_DMABUF so the pixman renderer can use the CPU
 * data-pointer path and the GLES2 renderer can import AHBs via DMA-BUF.
 */
#include <stdlib.h>
#include <android/log.h>
#include <wlr/backend.h>
#include <wlr/backend/interface.h>
#include <wlr/util/log.h>

#include "android_backend.h"
#include "android_output.h"
#include "ahb_allocator.h"

#define LOG_TAG "AndroidBackend"

/* Forward declarations of impl functions */
static bool backend_start(struct wlr_backend *wlr_backend);
static void backend_destroy(struct wlr_backend *wlr_backend);

static const struct wlr_backend_impl backend_impl = {
    .start = backend_start,
    .destroy = backend_destroy,
    .get_drm_fd = NULL,   /* no DRM fd — returns -1 implicitly */
};

/* ------------------------------------------------------------------ */

static bool backend_start(struct wlr_backend *wlr_backend) {
    struct android_backend *backend =
        wl_container_of(wlr_backend, backend, backend);

    backend->started = true;

    /* Emit new_output for every output that was already attached. */
    struct android_output *output;
    wl_list_for_each(output, &backend->outputs, link) {
        wl_signal_emit_mutable(&backend->backend.events.new_output,
                               &output->wlr_output);
    }

    return true;
}

static void backend_destroy(struct wlr_backend *wlr_backend) {
    struct android_backend *backend =
        wl_container_of(wlr_backend, backend, backend);

    /* Destroy all outputs. Iterate safely — android_output_destroy removes
     * itself from the list. */
    struct android_output *output, *tmp;
    wl_list_for_each_safe(output, tmp, &backend->outputs, link) {
        wlr_output_destroy(&output->wlr_output);
    }

    wlr_backend_finish(wlr_backend);
    free(backend);
}

/* ------------------------------------------------------------------ */

struct wlr_backend *android_backend_create(struct wl_display *display) {
    struct android_backend *backend = calloc(1, sizeof(*backend));
    if (!backend) {
        wlr_log(WLR_ERROR, "Failed to allocate android_backend");
        return NULL;
    }

    wlr_backend_init(&backend->backend, &backend_impl);
    /* In wlroots 0.19+, buffer_caps is a field on struct wlr_backend
     * (moved from wlr_backend_impl.get_buffer_caps). */
    backend->backend.buffer_caps = WLR_BUFFER_CAP_DATA_PTR;
    if (ahb_dmabuf_export_available()) {
        backend->backend.buffer_caps |= WLR_BUFFER_CAP_DMABUF;
    }

    backend->wl_display = display;
    backend->started = false;
    wl_list_init(&backend->outputs);

    wlr_log(WLR_INFO, "Android backend created");
    return &backend->backend;
}

bool android_backend_is_android(struct wlr_backend *backend) {
    return backend && backend->impl == &backend_impl;
}
