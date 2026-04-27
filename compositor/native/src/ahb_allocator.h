/*
 * ahb_allocator.h — wlroots allocator backed by Android AHardwareBuffer
 *
 * Buffers expose WLR_BUFFER_CAP_DATA_PTR (CPU access for pixman) and
 * WLR_BUFFER_CAP_DMABUF (GPU access for GLES2 via EGLImage import).
 */
#ifndef AHB_ALLOCATOR_H
#define AHB_ALLOCATOR_H

#include <android/hardware_buffer.h>
#include <wlr/types/wlr_buffer.h>
#include <wlr/render/allocator.h>

struct ahb_allocator {
    struct wlr_allocator base;  /* MUST be first member */
};

/* WARNING: The first two fields (base, ahb) are duplicated in the wlroots
 * android-compat.patch (render/gles2/renderer.c). If you change the field
 * order here, update the patch copy as well. */
struct ahb_buffer {
    struct wlr_buffer base;     /* MUST be first member */
    AHardwareBuffer *ahb;
    AHardwareBuffer_Desc desc;
    void *locked_data;          /* non-NULL while data-ptr access is active */
};

/**
 * Create an AHardwareBuffer-backed allocator.
 */
struct wlr_allocator *ahb_allocator_create(void);

/**
 * If @buffer was created by our AHB allocator, return the containing
 * ahb_buffer.  Otherwise return NULL.  This is used by the output commit
 * path to decide whether the zero-copy ASurfaceTransaction path is
 * available.
 */
struct ahb_buffer *ahb_buffer_try_from_wlr(struct wlr_buffer *buffer);

/**
 * Returns true if DMA-BUF export is available on this device.
 * Checks for AHardwareBuffer_getNativeHandle via dlsym.
 */
bool ahb_dmabuf_export_available(void);

#endif /* AHB_ALLOCATOR_H */
