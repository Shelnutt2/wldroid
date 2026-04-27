/*
 * hardware_buffer.h — Vendored minimal stub for cross-compilation
 *
 * Provides just enough of the Android NDK <android/hardware_buffer.h>
 * interface for gbm_ahb.c and gbm_ahb_formats.c to compile with
 * aarch64-linux-gnu-gcc (which lacks NDK headers).
 *
 * Only constants and declarations actually referenced by the GBM shim
 * sources are included.  Values match the NDK API-29 definitions.
 */
#ifndef ANDROID_HARDWARE_BUFFER_H_STUB
#define ANDROID_HARDWARE_BUFFER_H_STUB

#include <stdint.h>

/* ------------------------------------------------------------------ */
/* AHardwareBuffer format constants                                    */
/* Values from NDK <android/hardware_buffer.h>                         */
/* ------------------------------------------------------------------ */
#define AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM      1
#define AHARDWAREBUFFER_FORMAT_R8G8B8X8_UNORM      2
#define AHARDWAREBUFFER_FORMAT_R8G8B8_UNORM        3
#define AHARDWAREBUFFER_FORMAT_R5G6B5_UNORM        4
#define AHARDWAREBUFFER_FORMAT_R16G16B16A16_FLOAT   0x16
#define AHARDWAREBUFFER_FORMAT_R10G10B10A2_UNORM   0x2b
#define AHARDWAREBUFFER_FORMAT_BLOB                0x21

/* ------------------------------------------------------------------ */
/* AHardwareBuffer usage constants                                     */
/* ------------------------------------------------------------------ */
#define AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN       ((uint64_t)3 << 0)
#define AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN      ((uint64_t)3 << 4)
#define AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE    ((uint64_t)1 << 8)
#define AHARDWAREBUFFER_USAGE_GPU_FRAMEBUFFER      ((uint64_t)1 << 9)
#define AHARDWAREBUFFER_USAGE_COMPOSER_OVERLAY      ((uint64_t)1 << 11)

/* ------------------------------------------------------------------ */
/* Opaque AHardwareBuffer handle                                       */
/* ------------------------------------------------------------------ */
typedef struct AHardwareBuffer AHardwareBuffer;

/* ------------------------------------------------------------------ */
/* AHardwareBuffer_Desc                                                */
/* ------------------------------------------------------------------ */
typedef struct AHardwareBuffer_Desc {
    uint32_t width;
    uint32_t height;
    uint32_t layers;
    uint32_t format;
    uint64_t usage;
    uint32_t stride;
    uint32_t rfu0;
    uint32_t rfu1;
} AHardwareBuffer_Desc;

/* ------------------------------------------------------------------ */
/* ARect — used by AHardwareBuffer_lock                                */
/* ------------------------------------------------------------------ */
typedef struct ARect {
    int32_t left;
    int32_t top;
    int32_t right;
    int32_t bottom;
} ARect;

/* ------------------------------------------------------------------ */
/* Function declarations                                               */
/* At runtime these resolve from the Android system's libandroid.so.   */
/* For the cross-compiled .so they become undefined weak symbols       */
/* satisfied by dlopen/dlsym or the proot loader environment.          */
/* ------------------------------------------------------------------ */
int  AHardwareBuffer_allocate(const AHardwareBuffer_Desc *desc,
                              AHardwareBuffer **outBuffer);
void AHardwareBuffer_describe(const AHardwareBuffer *buffer,
                              AHardwareBuffer_Desc *outDesc);
void AHardwareBuffer_release(AHardwareBuffer *buffer);
int  AHardwareBuffer_lock(AHardwareBuffer *buffer, uint64_t usage,
                          int32_t fence, const ARect *rect, void **outVirtualAddress);
int  AHardwareBuffer_unlock(AHardwareBuffer *buffer, int32_t *fence);

#endif /* ANDROID_HARDWARE_BUFFER_H_STUB */
