/*
 * test_gbm_formats.c — Format mapping tests for gbm-shim
 *
 * These tests validate the GBM ↔ AHB format conversion table.
 * They can run on any platform with the format headers available.
 */
#include <android/hardware_buffer.h>
#include "gbm.h"
#include "gbm_ahb_formats.h"
#include "test_common.h"

/* ---- Tests ---- */

static void test_abgr8888_maps_correctly(void) {
    uint32_t ahb = gbm_to_ahb_format(GBM_FORMAT_ABGR8888);
    ASSERT_EQ(ahb, (uint32_t)AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM);
}

static void test_xbgr8888_maps_correctly(void) {
    uint32_t ahb = gbm_to_ahb_format(GBM_FORMAT_XBGR8888);
    ASSERT_EQ(ahb, (uint32_t)AHARDWAREBUFFER_FORMAT_R8G8B8X8_UNORM);
}

static void test_rgb565_maps_correctly(void) {
    uint32_t ahb = gbm_to_ahb_format(GBM_FORMAT_RGB565);
    ASSERT_EQ(ahb, (uint32_t)AHARDWAREBUFFER_FORMAT_R5G6B5_UNORM);
}

static void test_abgr16161616f_maps_correctly(void) {
    uint32_t ahb = gbm_to_ahb_format(GBM_FORMAT_ABGR16161616F);
    ASSERT_EQ(ahb, (uint32_t)AHARDWAREBUFFER_FORMAT_R16G16B16A16_FLOAT);
}

static void test_xbgr16161616f_maps_correctly(void) {
    uint32_t ahb = gbm_to_ahb_format(GBM_FORMAT_XBGR16161616F);
    ASSERT_EQ(ahb, (uint32_t)AHARDWAREBUFFER_FORMAT_R16G16B16A16_FLOAT);
}

static void test_unsupported_returns_zero(void) {
    ASSERT_EQ(gbm_to_ahb_format(0xDEADBEEF), 0u);
    ASSERT_EQ(ahb_to_gbm_format(0xDEADBEEF), 0u);
    ASSERT_EQ(gbm_format_bpp(0xDEADBEEF), 0u);
}

static void test_argb8888_unsupported(void) {
    /* ARGB8888 was intentionally removed — R/B channel swap. */
    ASSERT_EQ(gbm_to_ahb_format(GBM_FORMAT_ARGB8888), 0u);
    ASSERT_EQ(gbm_format_bpp(GBM_FORMAT_ARGB8888), 0u);
}

static void test_xrgb8888_unsupported(void) {
    /* XRGB8888 was intentionally removed — R/B channel swap. */
    ASSERT_EQ(gbm_to_ahb_format(GBM_FORMAT_XRGB8888), 0u);
    ASSERT_EQ(gbm_format_bpp(GBM_FORMAT_XRGB8888), 0u);
}

static void test_rgb888_unsupported(void) {
    /* RGB888 was intentionally removed — R/B channel swap. */
    ASSERT_EQ(gbm_to_ahb_format(GBM_FORMAT_RGB888), 0u);
    ASSERT_EQ(gbm_format_bpp(GBM_FORMAT_RGB888), 0u);
}

static void test_gr88_unsupported(void) {
    /* GR88 has no matching 2-byte-per-pixel AHB format. */
    ASSERT_EQ(gbm_to_ahb_format(GBM_FORMAT_GR88), 0u);
    ASSERT_EQ(gbm_format_bpp(GBM_FORMAT_GR88), 0u);
}

static void test_bpp_correctness(void) {
    ASSERT_EQ(gbm_format_bpp(GBM_FORMAT_ABGR8888), 4u);
    ASSERT_EQ(gbm_format_bpp(GBM_FORMAT_XBGR8888), 4u);
    ASSERT_EQ(gbm_format_bpp(GBM_FORMAT_RGB565), 2u);
    ASSERT_EQ(gbm_format_bpp(GBM_FORMAT_R8), 1u);
    ASSERT_EQ(gbm_format_bpp(GBM_FORMAT_BGR888), 3u);
    ASSERT_EQ(gbm_format_bpp(GBM_FORMAT_ABGR16161616F), 8u);
    ASSERT_EQ(gbm_format_bpp(GBM_FORMAT_XBGR16161616F), 8u);
    ASSERT_EQ(gbm_format_bpp(GBM_FORMAT_ABGR2101010), 4u);
}

static void test_all_supported_formats_map_nonzero(void) {
    /* Every supported GBM format should map to a non-zero AHB format. */
    static const uint32_t formats[] = {
        GBM_FORMAT_ABGR8888, GBM_FORMAT_XBGR8888,
        GBM_FORMAT_RGB565,   GBM_FORMAT_BGR888,
        GBM_FORMAT_ABGR16161616F, GBM_FORMAT_XBGR16161616F,
        GBM_FORMAT_R8,       GBM_FORMAT_ABGR2101010,
    };
    for (size_t i = 0; i < sizeof(formats) / sizeof(formats[0]); i++) {
        ASSERT_NE(gbm_to_ahb_format(formats[i]), 0u);
        ASSERT_NE(gbm_format_bpp(formats[i]), 0u);
    }
}

static void test_gbm_ahb_roundtrip(void) {
    /* ABGR8888 → R8G8B8A8_UNORM → should map back to ABGR8888
     * (the first entry in the table for that AHB format). */
    uint32_t ahb = gbm_to_ahb_format(GBM_FORMAT_ABGR8888);
    uint32_t gbm = ahb_to_gbm_format(ahb);
    ASSERT_EQ(gbm, GBM_FORMAT_ABGR8888);
}

/* ---- Main ---- */

int main(void) {
    printf("=== test_gbm_formats ===\n");

    RUN_TEST(test_abgr8888_maps_correctly);
    RUN_TEST(test_xbgr8888_maps_correctly);
    RUN_TEST(test_rgb565_maps_correctly);
    RUN_TEST(test_abgr16161616f_maps_correctly);
    RUN_TEST(test_xbgr16161616f_maps_correctly);
    RUN_TEST(test_unsupported_returns_zero);
    RUN_TEST(test_argb8888_unsupported);
    RUN_TEST(test_xrgb8888_unsupported);
    RUN_TEST(test_rgb888_unsupported);
    RUN_TEST(test_gr88_unsupported);
    RUN_TEST(test_bpp_correctness);
    RUN_TEST(test_all_supported_formats_map_nonzero);
    RUN_TEST(test_gbm_ahb_roundtrip);

    TEST_SUMMARY();
    return TEST_EXIT_CODE();
}
