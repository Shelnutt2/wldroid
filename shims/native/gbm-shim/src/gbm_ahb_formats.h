/*
 * gbm_ahb_formats.h — GBM ↔ AHardwareBuffer format mapping declarations
 */
#ifndef GBM_AHB_FORMATS_H
#define GBM_AHB_FORMATS_H

#include <stdint.h>

/*
 * Convert a GBM/DRM fourcc format to the corresponding AHardwareBuffer format.
 * Returns 0 if the format is not supported.
 */
uint32_t gbm_to_ahb_format(uint32_t gbm_format);

/*
 * Convert an AHardwareBuffer format to the corresponding GBM/DRM fourcc.
 * Returns 0 if the format is not known.
 */
uint32_t ahb_to_gbm_format(uint32_t ahb_format);

/*
 * Return bytes-per-pixel for the given GBM/DRM fourcc format.
 * Returns 0 if the format is not known.
 */
uint32_t gbm_format_bpp(uint32_t gbm_format);

#endif /* GBM_AHB_FORMATS_H */
