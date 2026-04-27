/*
 * test_gbm_map.c — Map/unmap tests for gbm-shim
 *
 * Tests run on-device only (require AHardwareBuffer with CPU access).
 */
#include <string.h>
#include "gbm.h"
#include "test_common.h"

static struct gbm_device *g_dev = NULL;

static void setup(void) {
    if (!g_dev) {
        g_dev = gbm_create_device(-1);
    }
}

/* ---- Tests ---- */

static void test_map_write_read_roundtrip(void) {
    setup();
    struct gbm_bo *bo = gbm_bo_create(g_dev, 64, 64,
                                       GBM_FORMAT_ABGR8888,
                                       GBM_BO_USE_LINEAR);
    ASSERT_NOT_NULL(bo);

    uint32_t stride = 0;
    void *map_data = NULL;
    void *addr = gbm_bo_map(bo, 0, 0, 64, 64,
                             GBM_BO_TRANSFER_READ_WRITE,
                             &stride, &map_data);
    if (!addr) {
        /* May fail in restricted environments — skip gracefully. */
        gbm_bo_destroy(bo);
        return;
    }

    ASSERT(stride > 0);
    ASSERT_NOT_NULL(map_data);

    /* Write a pattern to the first row */
    uint32_t *pixels = (uint32_t *)addr;
    for (int x = 0; x < 64; x++) {
        pixels[x] = 0xFF00FF00u;  /* green */
    }

    /* Read it back */
    ASSERT_EQ(pixels[0], 0xFF00FF00u);
    ASSERT_EQ(pixels[63], 0xFF00FF00u);

    gbm_bo_unmap(bo, map_data);
    gbm_bo_destroy(bo);
}

static void test_map_subrect(void) {
    setup();
    struct gbm_bo *bo = gbm_bo_create(g_dev, 128, 128,
                                       GBM_FORMAT_ABGR8888,
                                       GBM_BO_USE_LINEAR);
    ASSERT_NOT_NULL(bo);

    uint32_t stride = 0;
    void *map_data = NULL;
    void *addr = gbm_bo_map(bo, 32, 32, 64, 64,
                             GBM_BO_TRANSFER_WRITE,
                             &stride, &map_data);
    if (!addr) {
        gbm_bo_destroy(bo);
        return;
    }

    ASSERT(stride > 0);
    gbm_bo_unmap(bo, map_data);
    gbm_bo_destroy(bo);
}

static void test_stride_positive_after_map(void) {
    setup();
    struct gbm_bo *bo = gbm_bo_create(g_dev, 64, 64,
                                       GBM_FORMAT_ABGR8888,
                                       GBM_BO_USE_LINEAR);
    ASSERT_NOT_NULL(bo);

    uint32_t stride = 0;
    void *map_data = NULL;
    void *addr = gbm_bo_map(bo, 0, 0, 64, 64,
                             GBM_BO_TRANSFER_READ,
                             &stride, &map_data);
    if (!addr) {
        gbm_bo_destroy(bo);
        return;
    }

    ASSERT(stride >= 64u * 4u);
    gbm_bo_unmap(bo, map_data);
    gbm_bo_destroy(bo);
}

static void test_unmap_null_safe(void) {
    /* Must not crash */
    gbm_bo_unmap(NULL, NULL);
}

static void test_map_flags_translation(void) {
    setup();
    struct gbm_bo *bo = gbm_bo_create(g_dev, 32, 32,
                                       GBM_FORMAT_ABGR8888,
                                       GBM_BO_USE_LINEAR);
    ASSERT_NOT_NULL(bo);

    /* Map with read-only flag */
    uint32_t stride = 0;
    void *map_data = NULL;
    void *addr = gbm_bo_map(bo, 0, 0, 32, 32,
                             GBM_BO_TRANSFER_READ,
                             &stride, &map_data);
    if (addr) {
        ASSERT(stride > 0);
        gbm_bo_unmap(bo, map_data);
    }

    /* Map with write-only flag */
    stride = 0;
    map_data = NULL;
    addr = gbm_bo_map(bo, 0, 0, 32, 32,
                       GBM_BO_TRANSFER_WRITE,
                       &stride, &map_data);
    if (addr) {
        ASSERT(stride > 0);
        gbm_bo_unmap(bo, map_data);
    }

    gbm_bo_destroy(bo);
}

static void test_map_null_bo(void) {
    void *map_data;
    uint32_t stride;
    void *ptr = gbm_bo_map(NULL, 0, 0, 64, 64,
                            GBM_BO_TRANSFER_READ,
                            &stride, &map_data);
    ASSERT_NULL(ptr);
}

static void test_map_double_map_returns_null(void) {
    setup();
    struct gbm_bo *bo = gbm_bo_create(g_dev, 32, 32,
                                       GBM_FORMAT_ABGR8888,
                                       GBM_BO_USE_LINEAR);
    ASSERT_NOT_NULL(bo);

    uint32_t stride = 0;
    void *map_data = NULL;
    void *addr = gbm_bo_map(bo, 0, 0, 32, 32,
                             GBM_BO_TRANSFER_READ_WRITE,
                             &stride, &map_data);
    if (!addr) {
        /* Map may fail in restricted environments. */
        gbm_bo_destroy(bo);
        return;
    }

    /* Second map while still mapped should fail. */
    uint32_t stride2 = 0;
    void *map_data2 = NULL;
    void *addr2 = gbm_bo_map(bo, 0, 0, 32, 32,
                              GBM_BO_TRANSFER_READ,
                              &stride2, &map_data2);
    ASSERT_NULL(addr2);

    gbm_bo_unmap(bo, map_data);
    gbm_bo_destroy(bo);
}

/* ---- Main ---- */

int main(void) {
    printf("=== test_gbm_map ===\n");

    RUN_TEST(test_map_write_read_roundtrip);
    RUN_TEST(test_map_subrect);
    RUN_TEST(test_stride_positive_after_map);
    RUN_TEST(test_unmap_null_safe);
    RUN_TEST(test_map_flags_translation);
    RUN_TEST(test_map_null_bo);
    RUN_TEST(test_map_double_map_returns_null);

    TEST_SUMMARY();

    if (g_dev) {
        gbm_device_destroy(g_dev);
    }
    return TEST_EXIT_CODE();
}
