/*
 * drm_format_table.h — DRM fourcc ↔ AHardwareBuffer format mapping
 *
 * DRM uses memory-layout naming while AHB uses component-order naming, so
 * DRM_FORMAT_ABGR8888 (bytes in memory: R, G, B, A) maps to AHB's
 * AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM.
 */
#ifndef DRM_FORMAT_TABLE_H
#define DRM_FORMAT_TABLE_H

#include <stdint.h>
#include <drm_fourcc.h>
#include <android/hardware_buffer.h>

struct drm_ahb_format_pair {
    uint32_t drm;
    uint32_t ahb;
};

static const struct drm_ahb_format_pair drm_ahb_format_table[] = {
    { DRM_FORMAT_ABGR8888, AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM },  /* 0x34324241 ↔ 1 */
    { DRM_FORMAT_XBGR8888, AHARDWAREBUFFER_FORMAT_R8G8B8X8_UNORM },  /* 0x34324258 ↔ 2 */
    { DRM_FORMAT_RGB565,   AHARDWAREBUFFER_FORMAT_R5G6B5_UNORM },     /* 0x36314752 ↔ 4 */
};

static const size_t drm_ahb_format_table_len =
    sizeof(drm_ahb_format_table) / sizeof(drm_ahb_format_table[0]);

static inline uint32_t drm_format_to_ahb(uint32_t drm_format) {
    for (size_t i = 0; i < drm_ahb_format_table_len; i++) {
        if (drm_ahb_format_table[i].drm == drm_format) {
            return drm_ahb_format_table[i].ahb;
        }
    }
    return 0; /* unknown */
}

static inline uint32_t ahb_format_to_drm(uint32_t ahb_format) {
    for (size_t i = 0; i < drm_ahb_format_table_len; i++) {
        if (drm_ahb_format_table[i].ahb == ahb_format) {
            return drm_ahb_format_table[i].drm;
        }
    }
    return 0; /* unknown */
}

#endif /* DRM_FORMAT_TABLE_H */
