/*
 * ahb_mock.c — Mock AHardwareBuffer + Android logging for host unit tests
 *
 * Provides fake implementations of the Android NDK functions used by
 * gbm_ahb.c so that tests can run on a Linux host without the NDK.
 * Each AHardwareBuffer is backed by a plain malloc'd buffer.
 */
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include <android/hardware_buffer.h>
#include <android/log.h>

/* ------------------------------------------------------------------ */
/* AHardwareBuffer mock                                                */
/* ------------------------------------------------------------------ */

struct AHardwareBuffer {
    AHardwareBuffer_Desc desc;
    void *pixels;  /* CPU-accessible backing store (allocated on lock) */
};

int AHardwareBuffer_allocate(const AHardwareBuffer_Desc *desc,
                             AHardwareBuffer **outBuffer) {
    if (!desc || !outBuffer) return -1;

    struct AHardwareBuffer *buf = calloc(1, sizeof(*buf));
    if (!buf) return -1;

    buf->desc = *desc;
    /* Set a plausible stride (pixels) — equal to width for simplicity. */
    buf->desc.stride = desc->width;
    buf->pixels = NULL;

    *outBuffer = buf;
    return 0;
}

void AHardwareBuffer_describe(const AHardwareBuffer *buffer,
                              AHardwareBuffer_Desc *outDesc) {
    if (!buffer || !outDesc) return;
    *outDesc = buffer->desc;
}

void AHardwareBuffer_release(AHardwareBuffer *buffer) {
    if (!buffer) return;
    free(buffer->pixels);
    free(buffer);
}

int AHardwareBuffer_lock(AHardwareBuffer *buffer, uint64_t usage,
                         int32_t fence, const ARect *rect,
                         void **outVirtualAddress) {
    (void)usage;
    (void)fence;
    (void)rect;

    if (!buffer || !outVirtualAddress) return -1;

    if (!buffer->pixels) {
        /* Determine bytes per pixel from the AHB format */
        uint32_t bpp = 4; /* default */
        switch (buffer->desc.format) {
        case AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM:
        case AHARDWAREBUFFER_FORMAT_R8G8B8X8_UNORM:
        case AHARDWAREBUFFER_FORMAT_R10G10B10A2_UNORM:
            bpp = 4; break;
        case AHARDWAREBUFFER_FORMAT_R8G8B8_UNORM:
            bpp = 3; break;
        case AHARDWAREBUFFER_FORMAT_R5G6B5_UNORM:
            bpp = 2; break;
        case AHARDWAREBUFFER_FORMAT_R16G16B16A16_FLOAT:
            bpp = 8; break;
        case AHARDWAREBUFFER_FORMAT_BLOB:
            bpp = 1; break;
        }
        size_t size = (size_t)buffer->desc.stride * buffer->desc.height * bpp;
        buffer->pixels = calloc(1, size ? size : 1);
        if (!buffer->pixels) return -1;
    }

    *outVirtualAddress = buffer->pixels;
    return 0;
}

int AHardwareBuffer_unlock(AHardwareBuffer *buffer, int32_t *fence) {
    (void)buffer;
    if (fence) *fence = -1;
    return 0;
}

/* ------------------------------------------------------------------ */
/* AHardwareBuffer_getNativeHandle mock (resolved via dlsym)           */
/* ------------------------------------------------------------------ */

/*
 * Minimal native_handle_t layout matching AOSP cutils convention.
 * gbm_ahb.c uses dlsym(RTLD_DEFAULT, "AHardwareBuffer_getNativeHandle")
 * to locate this function at runtime.
 */
typedef struct {
    int version;
    int numFds;
    int numInts;
    int data[2];  /* data[0] = fd, data[1] = padding */
} mock_native_handle_t;

static mock_native_handle_t g_mock_handle;

const mock_native_handle_t *AHardwareBuffer_getNativeHandle(
        const AHardwareBuffer *buffer) {
    (void)buffer;
    /*
     * Return a handle with one fd.  We use fd 0 (stdin) as a harmless
     * file descriptor that can be dup'd.  The caller (gbm_bo_get_fd)
     * will F_DUPFD_CLOEXEC it to get a new fd.
     */
    g_mock_handle.version = (int)sizeof(mock_native_handle_t);
    g_mock_handle.numFds  = 1;
    g_mock_handle.numInts = 0;
    g_mock_handle.data[0] = 0;  /* fd=0 (stdin) — safe to dup */
    return &g_mock_handle;
}

/* ------------------------------------------------------------------ */
/* Android logging mock                                                */
/* ------------------------------------------------------------------ */

int __android_log_print(int prio, const char *tag, const char *fmt, ...) {
    (void)prio;
    va_list ap;
    va_start(ap, fmt);
    fprintf(stderr, "[%s] ", tag ? tag : "?");
    int ret = vfprintf(stderr, fmt, ap);
    fprintf(stderr, "\n");
    va_end(ap);
    return ret;
}
