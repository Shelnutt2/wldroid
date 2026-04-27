/*
 * gbm_ahb_formats.c — GBM ↔ AHardwareBuffer format mapping table
 *
 * DRM uses memory-layout naming while AHB uses component-order naming, so
 * DRM_FORMAT_ABGR8888 (bytes in memory: R, G, B, A) maps to AHB's
 * AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM.
 *
 * See also: compositor/src/drm_format_table.h for the same convention.
 */
#include <android/hardware_buffer.h>
#include "gbm.h"
#include "gbm_ahb_formats.h"

struct gbm_ahb_format_entry {
    uint32_t gbm;
    uint32_t ahb;
    uint32_t bpp;
};

static const struct gbm_ahb_format_entry format_table[] = {
    { GBM_FORMAT_ABGR8888,      AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM,   4 },
    { GBM_FORMAT_XBGR8888,      AHARDWAREBUFFER_FORMAT_R8G8B8X8_UNORM,   4 },
    /*
     * ARGB8888 / XRGB8888: the DRM byte order ([B,G,R,A] and [B,G,R,X])
     * differs from AHB's R8G8B8A8/R8G8B8X8 ([R,G,B,A] / [R,G,B,X]).
     * Normally this would swap red and blue, but in our AHB zero-copy GPU
     * rendering path the GPU writes and the compositor reads the same
     * physical buffer — the format label is only used for AHB allocation
     * sizing, not CPU-side reinterpretation.  Without these entries
     * virglrenderer's GBM allocation for VIRGL_FORMAT_B8G8R8A8_UNORM
     * (→ GBM_FORMAT_ARGB8888) fails and AHB export never happens.
     *
     * RGB888 is still excluded: no matching 3-byte AHB format with the
     * right channel order (BGR888 maps to R8G8B8_UNORM above).
     */
    { GBM_FORMAT_ARGB8888,      AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM,   4 },
    { GBM_FORMAT_XRGB8888,      AHARDWAREBUFFER_FORMAT_R8G8B8X8_UNORM,   4 },
    /*
     * GR88 (2 bytes/pixel) is excluded: there is no 2-byte-per-pixel AHB
     * format.  The previous mapping to R8G8B8A8_UNORM with bpp=2 was wrong
     * (4 bytes/pixel AHB format with 2-byte stride calculation).
     */
    { GBM_FORMAT_RGB565,         AHARDWAREBUFFER_FORMAT_R5G6B5_UNORM,     2 },
    { GBM_FORMAT_BGR888,         AHARDWAREBUFFER_FORMAT_R8G8B8_UNORM,     3 },
    { GBM_FORMAT_ABGR16161616F,  AHARDWAREBUFFER_FORMAT_R16G16B16A16_FLOAT, 8 },
    { GBM_FORMAT_XBGR16161616F,  AHARDWAREBUFFER_FORMAT_R16G16B16A16_FLOAT, 8 },
    { GBM_FORMAT_R8,             AHARDWAREBUFFER_FORMAT_BLOB,             1 },
    { GBM_FORMAT_ABGR2101010,    AHARDWAREBUFFER_FORMAT_R10G10B10A2_UNORM, 4 },
    /*
     * 10-bit ARGB/XRGB/XBGR 2101010: AHB has R10G10B10A2_UNORM (API 33+)
     * but we target API 29 so these are not universally available.  Kept
     * out of the table until minSdk is raised.
     *
     * YUV/planar formats (NV12, YVU420, P010): AHB supports these but
     * they require special multi-plane handling which this single-plane
     * shim does not implement.
     */
};

static const size_t format_table_len =
    sizeof(format_table) / sizeof(format_table[0]);

uint32_t gbm_to_ahb_format(uint32_t gbm_format) {
    for (size_t i = 0; i < format_table_len; i++) {
        if (format_table[i].gbm == gbm_format) {
            return format_table[i].ahb;
        }
    }
    return 0;
}

uint32_t ahb_to_gbm_format(uint32_t ahb_format) {
    for (size_t i = 0; i < format_table_len; i++) {
        if (format_table[i].ahb == ahb_format) {
            return format_table[i].gbm;
        }
    }
    return 0;
}

uint32_t gbm_format_bpp(uint32_t gbm_format) {
    for (size_t i = 0; i < format_table_len; i++) {
        if (format_table[i].gbm == gbm_format) {
            return format_table[i].bpp;
        }
    }
    return 0;
}
