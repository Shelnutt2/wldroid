/*
 * test_drm_format.c — Unit tests for DRM fourcc ↔ AHB format conversion
 *
 * Compiles on the host (Linux x86_64) with just libdrm headers.
 * Build:  cc -I/usr/include/libdrm -o test_drm_format test_drm_format.c
 */
#include "test_harness.h"

/*
 * Mock the Android AHB format constants before including the format table.
 * The stub header at stubs/android/hardware_buffer.h is an empty placeholder
 * so drm_format_table.h can compile on the host (pass -Istubs to the compiler).
 */
#define AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM 1
#define AHARDWAREBUFFER_FORMAT_R8G8B8X8_UNORM 2
#define AHARDWAREBUFFER_FORMAT_R5G6B5_UNORM   4

#include "../src/drm_format_table.h"

TEST(test_drm_to_ahb_abgr8888) {
    ASSERT_EQ(drm_format_to_ahb(DRM_FORMAT_ABGR8888),
              AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM);
}

TEST(test_drm_to_ahb_xbgr8888) {
    ASSERT_EQ(drm_format_to_ahb(DRM_FORMAT_XBGR8888),
              AHARDWAREBUFFER_FORMAT_R8G8B8X8_UNORM);
}

TEST(test_drm_to_ahb_rgb565) {
    ASSERT_EQ(drm_format_to_ahb(DRM_FORMAT_RGB565),
              AHARDWAREBUFFER_FORMAT_R5G6B5_UNORM);
}

TEST(test_drm_to_ahb_unknown) {
    ASSERT_EQ(drm_format_to_ahb(0xDEADBEEF), 0);
}

TEST(test_ahb_to_drm_roundtrip) {
    ASSERT_EQ(ahb_format_to_drm(drm_format_to_ahb(DRM_FORMAT_ABGR8888)),
              DRM_FORMAT_ABGR8888);
}

TEST(test_ahb_to_drm_unknown) {
    ASSERT_EQ(ahb_format_to_drm(0xFF), 0);
}

int main(void) {
    printf("DRM Format Table Tests:\n");
    RUN_TEST(test_drm_to_ahb_abgr8888);
    RUN_TEST(test_drm_to_ahb_xbgr8888);
    RUN_TEST(test_drm_to_ahb_rgb565);
    RUN_TEST(test_drm_to_ahb_unknown);
    RUN_TEST(test_ahb_to_drm_roundtrip);
    RUN_TEST(test_ahb_to_drm_unknown);
    TEST_SUMMARY();
}
