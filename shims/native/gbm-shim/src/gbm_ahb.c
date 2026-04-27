/*
 * gbm_ahb.c — AHardwareBuffer-backed GBM implementation
 *
 * Provides the standard GBM API for use on Android where native GBM is
 * unavailable.  Each gbm_bo wraps an AHardwareBuffer; DMA-BUF fd export
 * uses the same dlsym(AHardwareBuffer_getNativeHandle) pattern proven in
 * compositor/src/ahb_allocator.c.
 */
#include <stdlib.h>
#include <string.h>
#include <errno.h>
#include <dlfcn.h>
#include <fcntl.h>
#include <unistd.h>
#include <pthread.h>
#include <android/hardware_buffer.h>
#include <android/log.h>
#include <drm_fourcc.h>

#include "gbm.h"
#include "gbm_ahb_formats.h"

/* ------------------------------------------------------------------ */
/* Logging                                                             */
/* ------------------------------------------------------------------ */
#define LOG_TAG "gbm_shim"

#define GBM_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

#ifndef NDEBUG
#define GBM_LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#else
#define GBM_LOGD(...) ((void)0)
#endif

/* ------------------------------------------------------------------ */
/* native_handle_t (not in public NDK — matches AOSP cutils layout)    */
/* See also: compositor/src/ahb_allocator.c:23-30                      */
/* ------------------------------------------------------------------ */
typedef struct {
    int version;    /* sizeof(native_handle_t) */
    int numFds;
    int numInts;
    int data[];     /* numFds fds, then numInts ints (C11 flexible array) */
} gbm_native_handle_t;

typedef const gbm_native_handle_t *(*PFN_AHB_getNativeHandle)(
    const AHardwareBuffer *);

/* ------------------------------------------------------------------ */
/* dlsym for AHardwareBuffer_getNativeHandle (thread-safe via once)     */
/* ------------------------------------------------------------------ */
static pthread_once_t g_nh_once = PTHREAD_ONCE_INIT;
static PFN_AHB_getNativeHandle g_get_native_handle = NULL;

static void load_native_handle_fn(void) {
    g_get_native_handle = (PFN_AHB_getNativeHandle)
        dlsym(RTLD_DEFAULT, "AHardwareBuffer_getNativeHandle");
    if (!g_get_native_handle) {
        GBM_LOGE("AHardwareBuffer_getNativeHandle not available via dlsym");
    }
}

/* ------------------------------------------------------------------ */
/* Opaque struct definitions                                           */
/* ------------------------------------------------------------------ */

struct gbm_device {
    int fd;         /* stored fd — -1 is valid on Android */
};

struct gbm_bo {
    struct gbm_device    *device;
    AHardwareBuffer      *ahb;
    AHardwareBuffer_Desc  desc;
    uint32_t              gbm_format;
    uint32_t              stride;       /* bytes per row */
    void                 *user_data;
    void                (*user_data_destroy)(struct gbm_bo *, void *);
    void                 *map_data;     /* non-NULL while mapped */
    int                   mapped;       /* flag for unmap safety */
};

/* ================================================================== */
/* Device lifecycle                                                    */
/* ================================================================== */

struct gbm_device *gbm_create_device(int fd) {
    struct gbm_device *dev = calloc(1, sizeof(*dev));
    if (!dev) {
        GBM_LOGE("gbm_create_device: allocation failed");
        return NULL;
    }
    dev->fd = fd;
    GBM_LOGD("gbm_create_device: fd=%d", fd);
    return dev;
}

void gbm_device_destroy(struct gbm_device *gbm) {
    if (!gbm) return;
    GBM_LOGD("gbm_device_destroy: freeing device fd=%d", gbm->fd);
    free(gbm);
}

int gbm_device_get_fd(struct gbm_device *gbm) {
    if (!gbm) return -1;
    return gbm->fd;
}

const char *gbm_device_get_backend_name(struct gbm_device *gbm) {
    return gbm ? "android" : NULL;
}

int gbm_device_is_format_supported(struct gbm_device *gbm,
                                    uint32_t format, uint32_t usage) {
    (void)gbm;
    (void)usage;
    return gbm_to_ahb_format(format) != 0 ? 1 : 0;
}

/* ================================================================== */
/* Buffer-object lifecycle                                             */
/* ================================================================== */

struct gbm_bo *gbm_bo_create(struct gbm_device *gbm,
                              uint32_t width, uint32_t height,
                              uint32_t format, uint32_t flags) {
    if (!gbm) return NULL;
    if (width == 0 || height == 0) {
        GBM_LOGE("gbm_bo_create: invalid dimensions %ux%u", width, height);
        return NULL;
    }

    uint32_t ahb_format = gbm_to_ahb_format(format);
    if (ahb_format == 0) {
        GBM_LOGE("gbm_bo_create: unsupported format 0x%08x", format);
        return NULL;
    }

    uint32_t bpp = gbm_format_bpp(format);
    if (bpp == 0) {
        GBM_LOGE("gbm_bo_create: unknown bpp for format 0x%08x", format);
        return NULL;
    }

    /* Map GBM usage flags to AHB usage bits */
    uint64_t ahb_usage = 0;
    if (flags & GBM_BO_USE_RENDERING) {
        ahb_usage |= AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE
                    | AHARDWAREBUFFER_USAGE_GPU_FRAMEBUFFER;
    }
    if (flags & GBM_BO_USE_LINEAR) {
        ahb_usage |= AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN
                    | AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN;
    }
    if (flags & GBM_BO_USE_SCANOUT) {
        ahb_usage |= AHARDWAREBUFFER_USAGE_COMPOSER_OVERLAY;
    }
    if (flags & GBM_BO_USE_WRITE) {
        ahb_usage |= AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN;
    }
    /* Ensure at least GPU usage so the allocation succeeds */
    if (ahb_usage == 0) {
        ahb_usage = AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE
                  | AHARDWAREBUFFER_USAGE_GPU_FRAMEBUFFER;
    }

    AHardwareBuffer_Desc desc = {
        .width  = width,
        .height = height,
        .layers = 1,
        .format = ahb_format,
        .usage  = ahb_usage,
    };

    AHardwareBuffer *ahb = NULL;
    int ret = AHardwareBuffer_allocate(&desc, &ahb);
    if (ret != 0) {
        GBM_LOGE("gbm_bo_create: AHardwareBuffer_allocate failed: %d", ret);
        return NULL;
    }

    /* Re-read descriptor to pick up the stride the driver selected. */
    AHardwareBuffer_describe(ahb, &desc);

    struct gbm_bo *bo = calloc(1, sizeof(*bo));
    if (!bo) {
        AHardwareBuffer_release(ahb);
        return NULL;
    }

    bo->device     = gbm;
    bo->ahb        = ahb;
    bo->desc       = desc;
    bo->gbm_format = format;
    /* AHardwareBuffer_Desc.stride is in PIXELS; GBM needs BYTES. */
    bo->stride     = desc.stride * bpp;

    GBM_LOGD("gbm_bo_create: %ux%u fmt=0x%x stride=%u (px=%u bpp=%u)",
             width, height, format, bo->stride, desc.stride, bpp);
    return bo;
}

struct gbm_bo *gbm_bo_create_with_modifiers(struct gbm_device *gbm,
                                             uint32_t width, uint32_t height,
                                             uint32_t format,
                                             const uint64_t *modifiers,
                                             const unsigned int count) {
    (void)modifiers;
    (void)count;
    /* Fall back to linear allocation — we don't support modifiers. */
    return gbm_bo_create(gbm, width, height, format,
                         GBM_BO_USE_RENDERING | GBM_BO_USE_LINEAR);
}

struct gbm_bo *gbm_bo_create_with_modifiers2(struct gbm_device *gbm,
                                              uint32_t width, uint32_t height,
                                              uint32_t format,
                                              const uint64_t *modifiers,
                                              const unsigned int count,
                                              uint32_t flags) {
    (void)modifiers;
    (void)count;
    /* Merge caller flags with LINEAR for modifier-less path. */
    return gbm_bo_create(gbm, width, height, format,
                         flags | GBM_BO_USE_LINEAR);
}

struct gbm_bo *gbm_bo_import(struct gbm_device *gbm, uint32_t type,
                              void *buffer, uint32_t flags) {
    (void)gbm;
    (void)type;
    (void)buffer;
    (void)flags;
    errno = ENOSYS;
    GBM_LOGD("gbm_bo_import: stub — not supported");
    return NULL;
}

void gbm_bo_destroy(struct gbm_bo *bo) {
    if (!bo) return;

    if (bo->user_data && bo->user_data_destroy) {
        bo->user_data_destroy(bo, bo->user_data);
    }

    /* Safety net: unlock if caller forgot to unmap before destroying. */
    if (bo->mapped && bo->ahb) {
        GBM_LOGD("gbm_bo_destroy: auto-unlocking mapped buffer");
        AHardwareBuffer_unlock(bo->ahb, NULL);
        bo->mapped = 0;
    }

    if (bo->ahb) {
        AHardwareBuffer_release(bo->ahb);
    }

    GBM_LOGD("gbm_bo_destroy: %ux%u fmt=0x%x",
             bo->desc.width, bo->desc.height, bo->gbm_format);
    free(bo);
}

/* ================================================================== */
/* Buffer-object query                                                 */
/* ================================================================== */

uint32_t gbm_bo_get_width(struct gbm_bo *bo) {
    return bo ? bo->desc.width : 0;
}

uint32_t gbm_bo_get_height(struct gbm_bo *bo) {
    return bo ? bo->desc.height : 0;
}

uint32_t gbm_bo_get_format(struct gbm_bo *bo) {
    return bo ? bo->gbm_format : 0;
}

uint32_t gbm_bo_get_stride(struct gbm_bo *bo) {
    return bo ? bo->stride : 0;
}

uint32_t gbm_bo_get_stride_for_plane(struct gbm_bo *bo, int plane) {
    if (!bo || plane != 0) return 0;
    return bo->stride;
}

int gbm_bo_get_plane_count(struct gbm_bo *bo) {
    return bo ? 1 : 0;
}

uint32_t gbm_bo_get_offset(struct gbm_bo *bo, int plane) {
    if (!bo || plane != 0) return 0;
    return 0;
}

uint64_t gbm_bo_get_modifier(struct gbm_bo *bo) {
    /*
     * Return DRM_FORMAT_MOD_INVALID ("unspecified / driver-defined") because
     * AHardwareBuffer does not expose its tiling layout.  This is consistent
     * with ahb_allocator.c in the compositor.
     */
    (void)bo;
    return DRM_FORMAT_MOD_INVALID;
}

union gbm_bo_handle gbm_bo_get_handle(struct gbm_bo *bo) {
    union gbm_bo_handle handle;
    memset(&handle, 0, sizeof(handle));
    if (bo) {
        handle.ptr = (void *)bo->ahb;
    }
    return handle;
}

union gbm_bo_handle gbm_bo_get_handle_for_plane(struct gbm_bo *bo, int plane) {
    if (plane != 0) {
        union gbm_bo_handle handle;
        memset(&handle, 0, sizeof(handle));
        return handle;
    }
    return gbm_bo_get_handle(bo);
}

struct gbm_device *gbm_bo_get_device(struct gbm_bo *bo) {
    return bo ? bo->device : NULL;
}

/* ================================================================== */
/* DMA-BUF export                                                      */
/* Direct port from compositor/src/ahb_allocator.c:77-116              */
/* ================================================================== */

int gbm_bo_get_fd(struct gbm_bo *bo) {
    if (!bo || !bo->ahb) return -1;

    pthread_once(&g_nh_once, load_native_handle_fn);
    if (!g_get_native_handle) {
        GBM_LOGE("gbm_bo_get_fd: getNativeHandle unavailable");
        return -1;
    }

    const gbm_native_handle_t *handle = g_get_native_handle(bo->ahb);
    if (!handle || handle->numFds < 1) {
        GBM_LOGE("gbm_bo_get_fd: native handle has no fds");
        return -1;
    }

    /* handle->data[0] is the DMA-BUF fd — AOSP convention.
     * Dup with CLOEXEC because the caller owns the returned fd. */
    int fd = fcntl(handle->data[0], F_DUPFD_CLOEXEC, 0);
    if (fd < 0) {
        GBM_LOGE("gbm_bo_get_fd: fcntl(F_DUPFD_CLOEXEC) failed: %s",
                 strerror(errno));
        return fd;
    }

    return fd;
}

int gbm_bo_get_fd_for_plane(struct gbm_bo *bo, int plane) {
    if (plane != 0) return -1;
    return gbm_bo_get_fd(bo);
}

/* ================================================================== */
/* Map / Unmap                                                         */
/* Port from compositor/src/ahb_allocator.c:119-153                    */
/* ================================================================== */

void *gbm_bo_map(struct gbm_bo *bo, uint32_t x, uint32_t y,
                  uint32_t width, uint32_t height,
                  uint32_t flags, uint32_t *stride, void **map_data) {
    if (!bo || !bo->ahb || !stride || !map_data) {
        errno = EINVAL;
        return NULL;
    }

    if (bo->mapped) {
        GBM_LOGE("gbm_bo_map: already mapped");
        errno = EBUSY;
        return NULL;
    }

    uint64_t usage = 0;
    if (flags & GBM_BO_TRANSFER_READ) {
        usage |= AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN;
    }
    if (flags & GBM_BO_TRANSFER_WRITE) {
        usage |= AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN;
    }
    if (usage == 0) {
        usage = AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN;
    }

    ARect rect = {
        .left   = (int32_t)x,
        .top    = (int32_t)y,
        .right  = (int32_t)(x + width),
        .bottom = (int32_t)(y + height),
    };

    void *addr = NULL;
    int ret = AHardwareBuffer_lock(bo->ahb, usage, -1 /* no fence */,
                                   &rect, &addr);
    if (ret != 0) {
        GBM_LOGE("gbm_bo_map: AHardwareBuffer_lock failed: %d", ret);
        errno = EACCES;
        return NULL;
    }

    *stride   = bo->stride;
    *map_data = addr;  /* opaque token for gbm_bo_unmap */

    bo->map_data = addr;
    bo->mapped   = 1;

    GBM_LOGD("gbm_bo_map: %ux%u+%u+%u stride=%u", width, height, x, y, bo->stride);
    return addr;
}

void gbm_bo_unmap(struct gbm_bo *bo, void *map_data) {
    if (!bo || !bo->ahb) return;
    if (!bo->mapped) return;
    (void)map_data;

    AHardwareBuffer_unlock(bo->ahb, NULL);
    bo->map_data = NULL;
    bo->mapped   = 0;
    GBM_LOGD("gbm_bo_unmap: done");
}

/* ================================================================== */
/* Write (legacy) — stub                                               */
/* ================================================================== */

int gbm_bo_write(struct gbm_bo *bo, const void *buf, size_t count) {
    (void)bo;
    (void)buf;
    (void)count;
    errno = ENOSYS;
    return -1;
}

/* ================================================================== */
/* User data                                                           */
/* ================================================================== */

void gbm_bo_set_user_data(struct gbm_bo *bo, void *data,
                           void (*destroy_user_data)(struct gbm_bo *, void *)) {
    if (!bo) return;
    bo->user_data = data;
    bo->user_data_destroy = destroy_user_data;
}

void *gbm_bo_get_user_data(struct gbm_bo *bo) {
    return bo ? bo->user_data : NULL;
}

/* ================================================================== */
/* Format / modifier plane count                                       */
/* ================================================================== */

int gbm_device_get_format_modifier_plane_count(struct gbm_device *gbm,
                                                uint32_t format,
                                                uint64_t modifier) {
    (void)gbm;
    (void)modifier;
    /* We only support single-plane formats via AHB. */
    if (gbm_to_ahb_format(format) != 0) {
        return 1;
    }
    return 0;
}

/* ================================================================== */
/* AHardwareBuffer accessor (Android-specific)                         */
/* ================================================================== */

#ifdef __ANDROID__
#include <android/hardware_buffer.h>

AHardwareBuffer *gbm_bo_get_ahb(struct gbm_bo *bo) {
    return bo ? bo->ahb : NULL;
}
#endif
