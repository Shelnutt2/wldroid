/*
 * ahb_allocator.c — AHardwareBuffer-backed wlroots allocator
 *
 * Each buffer wraps an AHardwareBuffer allocated with CPU + GPU + composer
 * usage flags.  The data-ptr access path locks the AHB for CPU access so
 * that pixman (or any renderer using WLR_BUFFER_CAP_DATA_PTR) can read/
 * write pixel data.
 */
#include <stdlib.h>
#include <string.h>
#include <dlfcn.h>
#include <pthread.h>
#include <fcntl.h>
#include <android/hardware_buffer.h>
#include <drm_fourcc.h>
#include <wlr/render/allocator.h>
#include <wlr/render/drm_format_set.h>
#include <wlr/interfaces/wlr_buffer.h>
#include <wlr/util/log.h>

#include "ahb_allocator.h"
#include "drm_format_table.h"

/* Minimal native_handle_t — matches AOSP's <cutils/native_handle.h>.
 * Not in the public NDK, but universally present on Android. */
typedef struct {
    int version;    /* sizeof(native_handle_t) */
    int numFds;
    int numInts;
    int data[];     /* numFds fds, then numInts ints — C11 flexible array */
} ahb_native_handle_t;

typedef const ahb_native_handle_t* (*PFN_AHB_getNativeHandle)(
    const AHardwareBuffer*);

static PFN_AHB_getNativeHandle get_native_handle = NULL;
static pthread_once_t native_handle_once = PTHREAD_ONCE_INIT;

static void init_native_handle_fn(void) {
    get_native_handle = (PFN_AHB_getNativeHandle)
        dlsym(RTLD_DEFAULT, "AHardwareBuffer_getNativeHandle");
    if (!get_native_handle) {
        wlr_log(WLR_DEBUG, "AHardwareBuffer_getNativeHandle not available");
    }
}

/* Return bytes-per-pixel for the given AHB format. */
static int bpp_for_ahb_format(uint32_t ahb_format) {
    switch (ahb_format) {
    case AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM:
    case AHARDWAREBUFFER_FORMAT_R8G8B8X8_UNORM:
        return 4;
    case AHARDWAREBUFFER_FORMAT_R5G6B5_UNORM:
        return 2;
    default:
        return 4;
    }
}

/* ------------------------------------------------------------------ */
/* Buffer impl                                                         */
/* ------------------------------------------------------------------ */

static void ahb_buffer_destroy(struct wlr_buffer *wlr_buffer);
static bool ahb_buffer_get_dmabuf(struct wlr_buffer *wlr_buffer,
                                   struct wlr_dmabuf_attributes *attribs);
static bool ahb_buffer_begin_data_ptr_access(struct wlr_buffer *wlr_buffer,
                                              uint32_t flags, void **data,
                                              uint32_t *format, size_t *stride);
static void ahb_buffer_end_data_ptr_access(struct wlr_buffer *wlr_buffer);

/* Exported (by address) so android_output.c can identify our buffers. */
const struct wlr_buffer_impl ahb_buffer_impl = {
    .destroy = ahb_buffer_destroy,
    .get_dmabuf = ahb_buffer_get_dmabuf,
    .get_shm = NULL,
    .begin_data_ptr_access = ahb_buffer_begin_data_ptr_access,
    .end_data_ptr_access = ahb_buffer_end_data_ptr_access,
};

static void ahb_buffer_destroy(struct wlr_buffer *wlr_buffer) {
    struct ahb_buffer *buf = wl_container_of(wlr_buffer, buf, base);
    if (buf->ahb) {
        AHardwareBuffer_release(buf->ahb);
    }
    free(buf);
}

static bool ahb_buffer_get_dmabuf(struct wlr_buffer *wlr_buffer,
                                   struct wlr_dmabuf_attributes *attribs) {
    struct ahb_buffer *buf = wl_container_of(wlr_buffer, buf, base);

    /* Thread-safe one-time init of AHardwareBuffer_getNativeHandle — a
     * system API in libnativewindow.so, present on Android 8+ but not in
     * the public NDK headers. */
    pthread_once(&native_handle_once, init_native_handle_fn);
    if (!get_native_handle) return false;

    const ahb_native_handle_t *handle = get_native_handle(buf->ahb);
    if (!handle || handle->numFds < 1) return false;

    memset(attribs, 0, sizeof(*attribs));

    /* handle->data[0] is the DMA-BUF fd — AOSP convention across all
     * SoC vendors. Dup with CLOEXEC because wlroots owns the fd. */
    attribs->fd[0] = fcntl(handle->data[0], F_DUPFD_CLOEXEC, 0);
    if (attribs->fd[0] < 0) return false;

    attribs->n_planes = 1;
    attribs->offset[0] = 0;
    /* AHardwareBuffer_Desc.stride is in PIXELS; DRM needs BYTES. */
    attribs->stride[0] = buf->desc.stride * bpp_for_ahb_format(buf->desc.format);
    attribs->width = buf->desc.width;
    attribs->height = buf->desc.height;
    attribs->format = ahb_format_to_drm(buf->desc.format);
    attribs->modifier = DRM_FORMAT_MOD_INVALID;

    return true;
}

static bool ahb_buffer_begin_data_ptr_access(struct wlr_buffer *wlr_buffer,
                                              uint32_t flags, void **data,
                                              uint32_t *format, size_t *stride) {
    struct ahb_buffer *buf = wl_container_of(wlr_buffer, buf, base);

    uint64_t usage = 0;
    if (flags & WLR_BUFFER_DATA_PTR_ACCESS_READ) {
        usage |= AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN;
    }
    if (flags & WLR_BUFFER_DATA_PTR_ACCESS_WRITE) {
        usage |= AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN;
    }

    void *addr = NULL;
    int ret = AHardwareBuffer_lock(buf->ahb, usage, -1 /* no fence */,
                                   NULL /* full rect */, &addr);
    if (ret != 0) {
        wlr_log(WLR_ERROR, "AHardwareBuffer_lock failed: %d", ret);
        return false;
    }

    buf->locked_data = addr;
    *data = addr;
    *format = ahb_format_to_drm(buf->desc.format);

    /* desc.stride is in pixels; convert to bytes. */
    *stride = (size_t)buf->desc.stride * bpp_for_ahb_format(buf->desc.format);

    return true;
}

static void ahb_buffer_end_data_ptr_access(struct wlr_buffer *wlr_buffer) {
    struct ahb_buffer *buf = wl_container_of(wlr_buffer, buf, base);
    AHardwareBuffer_unlock(buf->ahb, NULL);
    buf->locked_data = NULL;
}

/* ------------------------------------------------------------------ */
/* Allocator impl                                                      */
/* ------------------------------------------------------------------ */

static struct wlr_buffer *ahb_alloc_create_buffer(
    struct wlr_allocator *wlr_alloc, int width, int height,
    const struct wlr_drm_format *format);
static void ahb_alloc_destroy(struct wlr_allocator *wlr_alloc);

static const struct wlr_allocator_interface ahb_alloc_impl = {
    .create_buffer = ahb_alloc_create_buffer,
    .destroy = ahb_alloc_destroy,
};

static struct wlr_buffer *ahb_alloc_create_buffer(
    struct wlr_allocator *wlr_alloc, int width, int height,
    const struct wlr_drm_format *format) {
    (void)wlr_alloc;

    uint32_t ahb_format = drm_format_to_ahb(format->format);
    if (ahb_format == 0) {
        wlr_log(WLR_ERROR, "Unsupported DRM format 0x%08x for AHB", format->format);
        return NULL;
    }

    struct ahb_buffer *buf = calloc(1, sizeof(*buf));
    if (!buf) {
        return NULL;
    }

    AHardwareBuffer_Desc desc = {
        .width = (uint32_t)width,
        .height = (uint32_t)height,
        .layers = 1,
        .format = ahb_format,
        .usage = AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN
               | AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN
               | AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE
               | AHARDWAREBUFFER_USAGE_GPU_FRAMEBUFFER
               | AHARDWAREBUFFER_USAGE_COMPOSER_OVERLAY,
    };

    int ret = AHardwareBuffer_allocate(&desc, &buf->ahb);
    if (ret != 0) {
        wlr_log(WLR_ERROR, "AHardwareBuffer_allocate failed: %d", ret);
        free(buf);
        return NULL;
    }

    /* Re-read the descriptor to pick up the stride the driver selected. */
    AHardwareBuffer_describe(buf->ahb, &buf->desc);

    wlr_buffer_init(&buf->base, &ahb_buffer_impl, width, height);

    wlr_log(WLR_DEBUG, "AHB buffer allocated: %dx%d fmt=0x%x stride=%u",
             width, height, ahb_format, buf->desc.stride);
    return &buf->base;
}

static void ahb_alloc_destroy(struct wlr_allocator *wlr_alloc) {
    struct ahb_allocator *alloc = wl_container_of(wlr_alloc, alloc, base);
    free(alloc);
}

/* ------------------------------------------------------------------ */
/* Public API                                                          */
/* ------------------------------------------------------------------ */

struct wlr_allocator *ahb_allocator_create(void) {
    struct ahb_allocator *alloc = calloc(1, sizeof(*alloc));
    if (!alloc) {
        wlr_log(WLR_ERROR, "Failed to allocate ahb_allocator");
        return NULL;
    }

    wlr_allocator_init(&alloc->base, &ahb_alloc_impl,
        WLR_BUFFER_CAP_DATA_PTR | WLR_BUFFER_CAP_DMABUF);

    wlr_log(WLR_INFO, "AHB allocator created");
    return &alloc->base;
}

bool ahb_dmabuf_export_available(void) {
    pthread_once(&native_handle_once, init_native_handle_fn);
    return get_native_handle != NULL;
}

struct ahb_buffer *ahb_buffer_try_from_wlr(struct wlr_buffer *buffer) {
    if (!buffer || buffer->impl != &ahb_buffer_impl) {
        return NULL;
    }
    struct ahb_buffer *abuf;
    return wl_container_of(buffer, abuf, base);
}
