/*
 * gbm.h — Generic Buffer Management (GBM) public header
 *
 * AHardwareBuffer-backed shim providing the standard GBM API on Android.
 * This header mirrors the Mesa GBM interface so that virglrenderer (and
 * any other consumer that does `#include <gbm.h>`) can build unmodified.
 */
#ifndef __GBM_H__
#define __GBM_H__

#ifdef __cplusplus
extern "C" {
#endif

#include <stddef.h>
#include <stdint.h>

/* ------------------------------------------------------------------ */
/* Format macros — aliased to DRM_FORMAT_* values                      */
/* We use drm_fourcc.h (and its fourcc_code macro) as the single       */
/* source of truth.  No private fourcc helper is needed.               */
/* ------------------------------------------------------------------ */
#include <drm_fourcc.h>

/*
 * Some older drm_fourcc.h versions lack 16-bit float formats.
 * Define them manually using the standard fourcc_code macro.
 */
#ifndef DRM_FORMAT_XBGR16161616F
#define DRM_FORMAT_XBGR16161616F fourcc_code('X', 'B', '4', 'H')
#endif
#ifndef DRM_FORMAT_ABGR16161616F
#define DRM_FORMAT_ABGR16161616F fourcc_code('A', 'B', '4', 'H')
#endif

/* RGBA / packed formats */
#define GBM_FORMAT_R8              DRM_FORMAT_R8
#define GBM_FORMAT_GR88            DRM_FORMAT_GR88
#define GBM_FORMAT_RGB565          DRM_FORMAT_RGB565
#define GBM_FORMAT_RGB888          DRM_FORMAT_RGB888
#define GBM_FORMAT_BGR888          DRM_FORMAT_BGR888
#define GBM_FORMAT_ARGB8888        DRM_FORMAT_ARGB8888
#define GBM_FORMAT_XRGB8888        DRM_FORMAT_XRGB8888
#define GBM_FORMAT_ABGR8888        DRM_FORMAT_ABGR8888
#define GBM_FORMAT_XBGR8888        DRM_FORMAT_XBGR8888

/* 10-bit / HDR formats */
#define GBM_FORMAT_ARGB2101010     DRM_FORMAT_ARGB2101010
#define GBM_FORMAT_XRGB2101010     DRM_FORMAT_XRGB2101010
#define GBM_FORMAT_ABGR2101010     DRM_FORMAT_ABGR2101010
#define GBM_FORMAT_XBGR2101010     DRM_FORMAT_XBGR2101010

/* 16-bit float formats */
#define GBM_FORMAT_XBGR16161616F   DRM_FORMAT_XBGR16161616F
#define GBM_FORMAT_ABGR16161616F   DRM_FORMAT_ABGR16161616F

/* Planar / YUV formats */
#define GBM_FORMAT_NV12            DRM_FORMAT_NV12
#define GBM_FORMAT_NV21            DRM_FORMAT_NV21
#define GBM_FORMAT_YVU420          DRM_FORMAT_YVU420
#define GBM_FORMAT_P010            DRM_FORMAT_P010

/* Maximum number of planes for multi-planar formats */
#define GBM_MAX_PLANES 4

/* ------------------------------------------------------------------ */
/* Buffer-object usage flags                                           */
/* ------------------------------------------------------------------ */
#define GBM_BO_USE_SCANOUT       (1 << 0)
#define GBM_BO_USE_CURSOR        (1 << 1)
#define GBM_BO_USE_RENDERING     (1 << 2)
#define GBM_BO_USE_WRITE         (1 << 3)
#define GBM_BO_USE_LINEAR        (1 << 4)

/* ------------------------------------------------------------------ */
/* Transfer (map) flags                                                */
/* ------------------------------------------------------------------ */
#define GBM_BO_TRANSFER_READ       (1 << 0)
#define GBM_BO_TRANSFER_WRITE      (1 << 1)
#define GBM_BO_TRANSFER_READ_WRITE (GBM_BO_TRANSFER_READ | GBM_BO_TRANSFER_WRITE)

/* ------------------------------------------------------------------ */
/* Buffer-object handle union                                          */
/* ------------------------------------------------------------------ */
union gbm_bo_handle {
    void     *ptr;
    int32_t   s32;
    uint32_t  u32;
    int64_t   s64;
    uint64_t  u64;
};

/* ------------------------------------------------------------------ */
/* Opaque structs — defined in gbm_ahb.c                               */
/* ------------------------------------------------------------------ */
struct gbm_device;
struct gbm_bo;
struct gbm_surface;

/* ------------------------------------------------------------------ */
/* Import types                                                        */
/* ------------------------------------------------------------------ */
#define GBM_BO_IMPORT_WL_BUFFER    0x5501
#define GBM_BO_IMPORT_EGL_IMAGE    0x5502
#define GBM_BO_IMPORT_FD           0x5503
#define GBM_BO_IMPORT_FD_MODIFIER  0x5504

struct gbm_import_fd_data {
    int      fd;
    uint32_t width;
    uint32_t height;
    uint32_t stride;
    uint32_t format;
};

struct gbm_import_fd_modifier_data {
    uint32_t  width;
    uint32_t  height;
    uint32_t  format;
    uint32_t  num_fds;
    int       fds[4];
    int       strides[4];
    int       offsets[4];
    uint64_t  modifier;
};

/* ------------------------------------------------------------------ */
/* Device lifecycle                                                    */
/* ------------------------------------------------------------------ */
struct gbm_device *gbm_create_device(int fd);
void               gbm_device_destroy(struct gbm_device *gbm);
int                gbm_device_get_fd(struct gbm_device *gbm);
const char        *gbm_device_get_backend_name(struct gbm_device *gbm);
int                gbm_device_is_format_supported(struct gbm_device *gbm,
                                                   uint32_t format,
                                                   uint32_t usage);

/* ------------------------------------------------------------------ */
/* Buffer-object lifecycle                                             */
/* ------------------------------------------------------------------ */
struct gbm_bo *gbm_bo_create(struct gbm_device *gbm,
                              uint32_t width, uint32_t height,
                              uint32_t format, uint32_t flags);
struct gbm_bo *gbm_bo_create_with_modifiers(struct gbm_device *gbm,
                                             uint32_t width, uint32_t height,
                                             uint32_t format,
                                             const uint64_t *modifiers,
                                             const unsigned int count);
struct gbm_bo *gbm_bo_create_with_modifiers2(struct gbm_device *gbm,
                                              uint32_t width, uint32_t height,
                                              uint32_t format,
                                              const uint64_t *modifiers,
                                              const unsigned int count,
                                              uint32_t flags);
struct gbm_bo *gbm_bo_import(struct gbm_device *gbm, uint32_t type,
                              void *buffer, uint32_t flags);
void           gbm_bo_destroy(struct gbm_bo *bo);

/* ------------------------------------------------------------------ */
/* Buffer-object query                                                 */
/* ------------------------------------------------------------------ */
uint32_t              gbm_bo_get_width(struct gbm_bo *bo);
uint32_t              gbm_bo_get_height(struct gbm_bo *bo);
uint32_t              gbm_bo_get_format(struct gbm_bo *bo);
uint32_t              gbm_bo_get_stride(struct gbm_bo *bo);
uint32_t              gbm_bo_get_stride_for_plane(struct gbm_bo *bo, int plane);
int                   gbm_bo_get_plane_count(struct gbm_bo *bo);
uint32_t              gbm_bo_get_offset(struct gbm_bo *bo, int plane);
uint64_t              gbm_bo_get_modifier(struct gbm_bo *bo);
union gbm_bo_handle   gbm_bo_get_handle(struct gbm_bo *bo);
union gbm_bo_handle   gbm_bo_get_handle_for_plane(struct gbm_bo *bo, int plane);
struct gbm_device    *gbm_bo_get_device(struct gbm_bo *bo);

/* ------------------------------------------------------------------ */
/* DMA-BUF export                                                      */
/* ------------------------------------------------------------------ */
int gbm_bo_get_fd(struct gbm_bo *bo);
int gbm_bo_get_fd_for_plane(struct gbm_bo *bo, int plane);

/* ------------------------------------------------------------------ */
/* Map / unmap                                                         */
/* ------------------------------------------------------------------ */
void *gbm_bo_map(struct gbm_bo *bo, uint32_t x, uint32_t y,
                  uint32_t width, uint32_t height,
                  uint32_t flags, uint32_t *stride, void **map_data);
void  gbm_bo_unmap(struct gbm_bo *bo, void *map_data);

/* ------------------------------------------------------------------ */
/* Write (legacy)                                                      */
/* ------------------------------------------------------------------ */
int gbm_bo_write(struct gbm_bo *bo, const void *buf, size_t count);

/* ------------------------------------------------------------------ */
/* User data                                                           */
/* ------------------------------------------------------------------ */
void  gbm_bo_set_user_data(struct gbm_bo *bo, void *data,
                            void (*destroy_user_data)(struct gbm_bo *, void *));
void *gbm_bo_get_user_data(struct gbm_bo *bo);

/* ------------------------------------------------------------------ */
/* Format / modifier plane count                                       */
/* ------------------------------------------------------------------ */
int gbm_device_get_format_modifier_plane_count(struct gbm_device *gbm,
                                                uint32_t format,
                                                uint64_t modifier);

/* ------------------------------------------------------------------ */
/* AHardwareBuffer accessor (Android-only)                             */
/* ------------------------------------------------------------------ */
#ifdef __ANDROID__
#include <android/hardware_buffer.h>
AHardwareBuffer *gbm_bo_get_ahb(struct gbm_bo *bo);
#endif

#ifdef __cplusplus
}
#endif

#endif /* __GBM_H__ */
