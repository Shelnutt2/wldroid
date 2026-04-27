/*
 * test_gbm_device.c — Device lifecycle tests for gbm-shim
 *
 * Tests run on-device only (require AHardwareBuffer).
 */
#include "gbm.h"
#include "test_common.h"

/* ---- Tests ---- */

static void test_create_with_neg1_fd(void) {
    struct gbm_device *dev = gbm_create_device(-1);
    ASSERT_NOT_NULL(dev);
    ASSERT_EQ(gbm_device_get_fd(dev), -1);
    gbm_device_destroy(dev);
}

static void test_create_with_zero_fd(void) {
    struct gbm_device *dev = gbm_create_device(0);
    ASSERT_NOT_NULL(dev);
    ASSERT_EQ(gbm_device_get_fd(dev), 0);
    gbm_device_destroy(dev);
}

static void test_create_with_42_fd(void) {
    struct gbm_device *dev = gbm_create_device(42);
    ASSERT_NOT_NULL(dev);
    ASSERT_EQ(gbm_device_get_fd(dev), 42);
    gbm_device_destroy(dev);
}

static void test_get_fd_roundtrip(void) {
    struct gbm_device *dev = gbm_create_device(7);
    ASSERT_NOT_NULL(dev);
    int fd = gbm_device_get_fd(dev);
    ASSERT_EQ(fd, 7);
    gbm_device_destroy(dev);
}

static void test_backend_name_is_android(void) {
    struct gbm_device *dev = gbm_create_device(-1);
    ASSERT_NOT_NULL(dev);
    const char *name = gbm_device_get_backend_name(dev);
    ASSERT_NOT_NULL(name);
    ASSERT_EQ(strcmp(name, "android"), 0);
    gbm_device_destroy(dev);
}

static void test_destroy_null_safe(void) {
    /* Must not crash */
    gbm_device_destroy(NULL);
}

static void test_format_supported_known(void) {
    struct gbm_device *dev = gbm_create_device(-1);
    ASSERT_NOT_NULL(dev);
    ASSERT(gbm_device_is_format_supported(dev, GBM_FORMAT_ABGR8888, 0));
    ASSERT(gbm_device_is_format_supported(dev, GBM_FORMAT_XBGR8888, 0));
    ASSERT(gbm_device_is_format_supported(dev, GBM_FORMAT_RGB565, 0));
    ASSERT(gbm_device_is_format_supported(dev, GBM_FORMAT_ABGR16161616F, 0));
    ASSERT(gbm_device_is_format_supported(dev, GBM_FORMAT_XBGR16161616F, 0));
    gbm_device_destroy(dev);
}

static void test_format_supported_excluded(void) {
    struct gbm_device *dev = gbm_create_device(-1);
    ASSERT_NOT_NULL(dev);
    /*
     * ARGB8888/XRGB8888 were re-added to the format table (channel swap is
     * harmless in the zero-copy GPU path).  RGB888 and GR88 remain excluded.
     */
    ASSERT(gbm_device_is_format_supported(dev, GBM_FORMAT_ARGB8888, 0));
    ASSERT(gbm_device_is_format_supported(dev, GBM_FORMAT_XRGB8888, 0));
    ASSERT(!gbm_device_is_format_supported(dev, GBM_FORMAT_RGB888, 0));
    ASSERT(!gbm_device_is_format_supported(dev, GBM_FORMAT_GR88, 0));
    gbm_device_destroy(dev);
}

static void test_format_supported_unknown(void) {
    struct gbm_device *dev = gbm_create_device(-1);
    ASSERT_NOT_NULL(dev);
    /* 0xDEADBEEF is not a real format */
    ASSERT(!gbm_device_is_format_supported(dev, 0xDEADBEEF, 0));
    gbm_device_destroy(dev);
}

static void test_format_supported_null_device(void) {
    /* Function ignores device pointer — should still work. */
    int supported = gbm_device_is_format_supported(NULL, GBM_FORMAT_ABGR8888, 0);
    ASSERT_EQ(supported, 1);
}

static void test_backend_name_null_device(void) {
    const char *name = gbm_device_get_backend_name(NULL);
    ASSERT_NULL(name);
}

static void test_get_fd_null_device(void) {
    ASSERT_EQ(gbm_device_get_fd(NULL), -1);
}

static void test_create_destroy_cycle_x100(void) {
    for (int i = 0; i < 100; i++) {
        struct gbm_device *dev = gbm_create_device(i);
        ASSERT_NOT_NULL(dev);
        gbm_device_destroy(dev);
    }
}

/* ---- Main ---- */

int main(void) {
    printf("=== test_gbm_device ===\n");

    RUN_TEST(test_create_with_neg1_fd);
    RUN_TEST(test_create_with_zero_fd);
    RUN_TEST(test_create_with_42_fd);
    RUN_TEST(test_get_fd_roundtrip);
    RUN_TEST(test_backend_name_is_android);
    RUN_TEST(test_destroy_null_safe);
    RUN_TEST(test_format_supported_known);
    RUN_TEST(test_format_supported_excluded);
    RUN_TEST(test_format_supported_unknown);
    RUN_TEST(test_format_supported_null_device);
    RUN_TEST(test_backend_name_null_device);
    RUN_TEST(test_get_fd_null_device);
    RUN_TEST(test_create_destroy_cycle_x100);

    TEST_SUMMARY();
    return TEST_EXIT_CODE();
}
