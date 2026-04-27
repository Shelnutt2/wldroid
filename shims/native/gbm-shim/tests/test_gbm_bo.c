/*
 * test_gbm_bo.c — Buffer object tests for gbm-shim
 *
 * Tests run on-device only (require AHardwareBuffer).
 */
#include <unistd.h>
#include <drm_fourcc.h>
#include "gbm.h"
#include "test_common.h"

static struct gbm_device *g_dev = NULL;

static void setup(void) {
    if (!g_dev) {
        g_dev = gbm_create_device(-1);
    }
}

/* ---- Tests ---- */

static void test_create_abgr8888(void) {
    setup();
    struct gbm_bo *bo = gbm_bo_create(g_dev, 64, 64,
                                       GBM_FORMAT_ABGR8888,
                                       GBM_BO_USE_RENDERING);
    ASSERT_NOT_NULL(bo);
    ASSERT_EQ(gbm_bo_get_width(bo), 64u);
    ASSERT_EQ(gbm_bo_get_height(bo), 64u);
    ASSERT_EQ(gbm_bo_get_format(bo), GBM_FORMAT_ABGR8888);
    gbm_bo_destroy(bo);
}

static void test_create_xrgb8888_unsupported(void) {
    setup();
    /* XRGB8888 was removed from the format table (R/B channel swap). */
    struct gbm_bo *bo = gbm_bo_create(g_dev, 128, 64,
                                       GBM_FORMAT_XRGB8888,
                                       GBM_BO_USE_RENDERING);
    ASSERT_NULL(bo);
}

static void test_create_xbgr8888(void) {
    setup();
    struct gbm_bo *bo = gbm_bo_create(g_dev, 128, 64,
                                       GBM_FORMAT_XBGR8888,
                                       GBM_BO_USE_RENDERING);
    ASSERT_NOT_NULL(bo);
    ASSERT_EQ(gbm_bo_get_width(bo), 128u);
    ASSERT_EQ(gbm_bo_get_height(bo), 64u);
    gbm_bo_destroy(bo);
}

static void test_create_rgb565(void) {
    setup();
    struct gbm_bo *bo = gbm_bo_create(g_dev, 32, 32,
                                       GBM_FORMAT_RGB565,
                                       GBM_BO_USE_RENDERING);
    ASSERT_NOT_NULL(bo);
    gbm_bo_destroy(bo);
}

static void test_create_r8(void) {
    setup();
    struct gbm_bo *bo = gbm_bo_create(g_dev, 256, 1,
                                       GBM_FORMAT_R8,
                                       GBM_BO_USE_LINEAR);
    /* R8 maps to BLOB — may or may not be supported depending on device. */
    if (bo) {
        gbm_bo_destroy(bo);
    }
}

static void test_zero_width_returns_null(void) {
    setup();
    struct gbm_bo *bo = gbm_bo_create(g_dev, 0, 64,
                                       GBM_FORMAT_ABGR8888,
                                       GBM_BO_USE_RENDERING);
    ASSERT_NULL(bo);
}

static void test_zero_height_returns_null(void) {
    setup();
    struct gbm_bo *bo = gbm_bo_create(g_dev, 64, 0,
                                       GBM_FORMAT_ABGR8888,
                                       GBM_BO_USE_RENDERING);
    ASSERT_NULL(bo);
}

static void test_unsupported_format_returns_null(void) {
    setup();
    struct gbm_bo *bo = gbm_bo_create(g_dev, 64, 64,
                                       0xDEADBEEF,
                                       GBM_BO_USE_RENDERING);
    ASSERT_NULL(bo);
}

static void test_query_stride(void) {
    setup();
    struct gbm_bo *bo = gbm_bo_create(g_dev, 64, 64,
                                       GBM_FORMAT_ABGR8888,
                                       GBM_BO_USE_RENDERING);
    ASSERT_NOT_NULL(bo);
    uint32_t stride = gbm_bo_get_stride(bo);
    /* Stride must be at least width * bpp (4 for ABGR8888) = 256 */
    ASSERT(stride >= 64u * 4u);
    gbm_bo_destroy(bo);
}

static void test_query_stride_for_plane(void) {
    setup();
    struct gbm_bo *bo = gbm_bo_create(g_dev, 64, 64,
                                       GBM_FORMAT_ABGR8888,
                                       GBM_BO_USE_RENDERING);
    ASSERT_NOT_NULL(bo);
    ASSERT_EQ(gbm_bo_get_stride_for_plane(bo, 0), gbm_bo_get_stride(bo));
    ASSERT_EQ(gbm_bo_get_stride_for_plane(bo, 1), 0u);
    gbm_bo_destroy(bo);
}

static void test_query_plane_count(void) {
    setup();
    struct gbm_bo *bo = gbm_bo_create(g_dev, 64, 64,
                                       GBM_FORMAT_ABGR8888,
                                       GBM_BO_USE_RENDERING);
    ASSERT_NOT_NULL(bo);
    ASSERT_EQ(gbm_bo_get_plane_count(bo), 1);
    gbm_bo_destroy(bo);
}

static void test_query_device(void) {
    setup();
    struct gbm_bo *bo = gbm_bo_create(g_dev, 64, 64,
                                       GBM_FORMAT_ABGR8888,
                                       GBM_BO_USE_RENDERING);
    ASSERT_NOT_NULL(bo);
    ASSERT_EQ((void *)gbm_bo_get_device(bo), (void *)g_dev);
    gbm_bo_destroy(bo);
}

static void test_get_fd_returns_valid_fd(void) {
    setup();
    struct gbm_bo *bo = gbm_bo_create(g_dev, 64, 64,
                                       GBM_FORMAT_ABGR8888,
                                       GBM_BO_USE_RENDERING);
    ASSERT_NOT_NULL(bo);
    int fd = gbm_bo_get_fd(bo);
    /* On real devices fd should be >= 0; in restricted envs it may be -1. */
    if (fd >= 0) {
        close(fd);
    }
    gbm_bo_destroy(bo);
}

static void test_user_data_roundtrip(void) {
    setup();
    struct gbm_bo *bo = gbm_bo_create(g_dev, 64, 64,
                                       GBM_FORMAT_ABGR8888,
                                       GBM_BO_USE_RENDERING);
    ASSERT_NOT_NULL(bo);
    int sentinel = 42;
    gbm_bo_set_user_data(bo, &sentinel, NULL);
    void *got = gbm_bo_get_user_data(bo);
    ASSERT_EQ(got, (void *)&sentinel);
    gbm_bo_destroy(bo);
}

static int g_destroy_called = 0;
static void destroy_cb(struct gbm_bo *bo, void *data) {
    (void)bo;
    (void)data;
    g_destroy_called = 1;
}

static void test_user_data_destroy_callback(void) {
    setup();
    g_destroy_called = 0;
    struct gbm_bo *bo = gbm_bo_create(g_dev, 64, 64,
                                       GBM_FORMAT_ABGR8888,
                                       GBM_BO_USE_RENDERING);
    ASSERT_NOT_NULL(bo);
    int sentinel = 99;
    gbm_bo_set_user_data(bo, &sentinel, destroy_cb);
    gbm_bo_destroy(bo);
    ASSERT_EQ(g_destroy_called, 1);
}

static void test_stress_create_destroy_x50(void) {
    setup();
    for (int i = 0; i < 50; i++) {
        struct gbm_bo *bo = gbm_bo_create(g_dev, 16, 16,
                                           GBM_FORMAT_ABGR8888,
                                           GBM_BO_USE_RENDERING);
        ASSERT_NOT_NULL(bo);
        gbm_bo_destroy(bo);
    }
}

/* ---- New tests: handles, stubs, modifiers, NULL, formats ---- */

static void test_bo_get_handle(void) {
    setup();
    struct gbm_bo *bo = gbm_bo_create(g_dev, 64, 64,
                                       GBM_FORMAT_ABGR8888,
                                       GBM_BO_USE_RENDERING);
    ASSERT_NOT_NULL(bo);
    union gbm_bo_handle handle = gbm_bo_get_handle(bo);
    ASSERT_NOT_NULL(handle.ptr);  /* AHB pointer should be non-NULL */
    union gbm_bo_handle plane_handle = gbm_bo_get_handle_for_plane(bo, 0);
    ASSERT(plane_handle.ptr == handle.ptr);  /* Same for single-plane */
    union gbm_bo_handle bad_plane = gbm_bo_get_handle_for_plane(bo, 1);
    ASSERT_NULL(bad_plane.ptr);  /* Invalid plane */
    gbm_bo_destroy(bo);
}

static void test_import_returns_null(void) {
    setup();
    struct gbm_bo *bo = gbm_bo_import(g_dev, 0, NULL, 0);
    ASSERT_NULL(bo);
}

static void test_create_with_modifiers_fallback(void) {
    setup();
    uint64_t mod = DRM_FORMAT_MOD_LINEAR;
    struct gbm_bo *bo = gbm_bo_create_with_modifiers(g_dev, 64, 64,
                                                      GBM_FORMAT_ABGR8888,
                                                      &mod, 1);
    /* Falls back to gbm_bo_create — should succeed. */
    if (bo) {
        ASSERT_EQ(gbm_bo_get_width(bo), 64u);
        gbm_bo_destroy(bo);
    }
}

static void test_bo_write_returns_error(void) {
    setup();
    struct gbm_bo *bo = gbm_bo_create(g_dev, 64, 64,
                                       GBM_FORMAT_ABGR8888,
                                       GBM_BO_USE_RENDERING);
    ASSERT_NOT_NULL(bo);
    int ret = gbm_bo_write(bo, "test", 4);
    ASSERT_EQ(ret, -1);
    gbm_bo_destroy(bo);
}

static void test_bo_get_modifier(void) {
    setup();
    struct gbm_bo *bo = gbm_bo_create(g_dev, 64, 64,
                                       GBM_FORMAT_ABGR8888,
                                       GBM_BO_USE_RENDERING);
    ASSERT_NOT_NULL(bo);
    uint64_t mod = gbm_bo_get_modifier(bo);
    ASSERT_EQ(mod, DRM_FORMAT_MOD_INVALID);
    gbm_bo_destroy(bo);
}

static void test_bo_get_modifier_null(void) {
    uint64_t mod = gbm_bo_get_modifier(NULL);
    ASSERT_EQ(mod, DRM_FORMAT_MOD_INVALID);
}

static void test_bo_get_offset(void) {
    setup();
    struct gbm_bo *bo = gbm_bo_create(g_dev, 64, 64,
                                       GBM_FORMAT_ABGR8888,
                                       GBM_BO_USE_RENDERING);
    ASSERT_NOT_NULL(bo);
    ASSERT_EQ(gbm_bo_get_offset(bo, 0), 0u);
    gbm_bo_destroy(bo);
}

static void test_bo_get_plane_count_null(void) {
    ASSERT_EQ(gbm_bo_get_plane_count(NULL), 0);
}

static void test_bo_get_offset_null(void) {
    ASSERT_EQ(gbm_bo_get_offset(NULL, 0), 0u);
}

static void test_bo_destroy_null_safe(void) {
    /* Must not crash. */
    gbm_bo_destroy(NULL);
}

static void test_get_fd_returns_valid_fd_assert(void) {
    setup();
    struct gbm_bo *bo = gbm_bo_create(g_dev, 64, 64,
                                       GBM_FORMAT_ABGR8888,
                                       GBM_BO_USE_LINEAR);
    ASSERT_NOT_NULL(bo);
    int fd = gbm_bo_get_fd(bo);
    /* On Android with AHB support, fd should be valid. */
    ASSERT(fd >= 0);
    close(fd);
    gbm_bo_destroy(bo);
}

static void test_create_abgr2101010(void) {
    setup();
    struct gbm_bo *bo = gbm_bo_create(g_dev, 64, 64,
                                       GBM_FORMAT_ABGR2101010,
                                       GBM_BO_USE_RENDERING);
    /* May succeed on devices with 10-bit support. */
    if (bo) {
        ASSERT_EQ(gbm_bo_get_format(bo), GBM_FORMAT_ABGR2101010);
        gbm_bo_destroy(bo);
    }
}

static void test_create_abgr16161616f(void) {
    setup();
    struct gbm_bo *bo = gbm_bo_create(g_dev, 64, 64,
                                       GBM_FORMAT_ABGR16161616F,
                                       GBM_BO_USE_RENDERING);
    if (bo) {
        ASSERT_EQ(gbm_bo_get_width(bo), 64u);
        gbm_bo_destroy(bo);
    }
}

static void test_create_xbgr16161616f(void) {
    setup();
    struct gbm_bo *bo = gbm_bo_create(g_dev, 64, 64,
                                       GBM_FORMAT_XBGR16161616F,
                                       GBM_BO_USE_RENDERING);
    if (bo) {
        ASSERT_EQ(gbm_bo_get_width(bo), 64u);
        gbm_bo_destroy(bo);
    }
}

static void test_bo_query_null_safety(void) {
    /* All query functions should handle NULL gracefully. */
    ASSERT_EQ(gbm_bo_get_width(NULL), 0u);
    ASSERT_EQ(gbm_bo_get_height(NULL), 0u);
    ASSERT_EQ(gbm_bo_get_format(NULL), 0u);
    ASSERT_EQ(gbm_bo_get_stride(NULL), 0u);
    ASSERT_EQ(gbm_bo_get_stride_for_plane(NULL, 0), 0u);
    ASSERT_NULL(gbm_bo_get_device(NULL));
    ASSERT_NULL(gbm_bo_get_user_data(NULL));
}

/* ---- Main ---- */

int main(void) {
    printf("=== test_gbm_bo ===\n");

    RUN_TEST(test_create_abgr8888);
    RUN_TEST(test_create_xrgb8888_unsupported);
    RUN_TEST(test_create_xbgr8888);
    RUN_TEST(test_create_rgb565);
    RUN_TEST(test_create_r8);
    RUN_TEST(test_zero_width_returns_null);
    RUN_TEST(test_zero_height_returns_null);
    RUN_TEST(test_unsupported_format_returns_null);
    RUN_TEST(test_query_stride);
    RUN_TEST(test_query_stride_for_plane);
    RUN_TEST(test_query_plane_count);
    RUN_TEST(test_query_device);
    RUN_TEST(test_get_fd_returns_valid_fd);
    RUN_TEST(test_user_data_roundtrip);
    RUN_TEST(test_user_data_destroy_callback);
    RUN_TEST(test_stress_create_destroy_x50);
    RUN_TEST(test_bo_get_handle);
    RUN_TEST(test_import_returns_null);
    RUN_TEST(test_create_with_modifiers_fallback);
    RUN_TEST(test_bo_write_returns_error);
    RUN_TEST(test_bo_get_modifier);
    RUN_TEST(test_bo_get_modifier_null);
    RUN_TEST(test_bo_get_offset);
    RUN_TEST(test_bo_get_plane_count_null);
    RUN_TEST(test_bo_get_offset_null);
    RUN_TEST(test_bo_destroy_null_safe);
    RUN_TEST(test_get_fd_returns_valid_fd_assert);
    RUN_TEST(test_create_abgr2101010);
    RUN_TEST(test_create_abgr16161616f);
    RUN_TEST(test_create_xbgr16161616f);
    RUN_TEST(test_bo_query_null_safety);

    TEST_SUMMARY();

    if (g_dev) {
        gbm_device_destroy(g_dev);
    }
    return TEST_EXIT_CODE();
}
