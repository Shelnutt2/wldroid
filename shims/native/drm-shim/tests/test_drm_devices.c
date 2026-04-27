/*
 * test_drm_devices.c — Tests for device enumeration in drm-shim
 *
 * Verifies drmGetDevices2, drmGetDevices, drmFreeDevices by linking
 * directly against drm_shim.c (no LD_PRELOAD needed).
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <xf86drm.h>
#include "test_common.h"
#include "drm_shim.h"

/* ------------------------------------------------------------------ */
/* drmGetDevices2 / drmGetDevices / drmFreeDevices                    */
/* ------------------------------------------------------------------ */

static void test_get_devices2_count(void) {
    int ret = drmGetDevices2(0, NULL, 0);
    ASSERT_EQ(ret, 1);
}

static void test_get_devices2_fill(void) {
    drmDevicePtr devices[1] = {NULL};
    int ret = drmGetDevices2(0, devices, 1);
    ASSERT_EQ(ret, 1);
    ASSERT_NOT_NULL(devices[0]);
    ASSERT(devices[0]->available_nodes & (1 << DRM_NODE_RENDER));
    drmFreeDevices(devices, ret);
}

static void test_render_node_path(void) {
    drmDevicePtr devices[1] = {NULL};
    int ret = drmGetDevices2(0, devices, 1);
    ASSERT_EQ(ret, 1);
    ASSERT_NOT_NULL(devices[0]->nodes[DRM_NODE_RENDER]);
    ASSERT_EQ(strcmp(devices[0]->nodes[DRM_NODE_RENDER],
                     DRM_SHIM_RENDER_NODE), 0);
    drmFreeDevices(devices, ret);
}

static void test_card_node_path(void) {
    drmDevicePtr devices[1] = {NULL};
    int ret = drmGetDevices2(0, devices, 1);
    ASSERT_EQ(ret, 1);
    ASSERT_NOT_NULL(devices[0]->nodes[DRM_NODE_PRIMARY]);
    ASSERT_EQ(strcmp(devices[0]->nodes[DRM_NODE_PRIMARY],
                     DRM_SHIM_CARD_NODE), 0);
    drmFreeDevices(devices, ret);
}

static void test_bus_type_platform(void) {
    drmDevicePtr devices[1] = {NULL};
    int ret = drmGetDevices2(0, devices, 1);
    ASSERT_EQ(ret, 1);
    ASSERT_EQ(devices[0]->bustype, DRM_BUS_PLATFORM);
    drmFreeDevices(devices, ret);
}

static void test_platform_fullname(void) {
    drmDevicePtr devices[1] = {NULL};
    int ret = drmGetDevices2(0, devices, 1);
    ASSERT_EQ(ret, 1);
    ASSERT_NOT_NULL(devices[0]->businfo.platform);
    ASSERT_NOT_NULL(devices[0]->businfo.platform->fullname);
    ASSERT_EQ(strcmp(devices[0]->businfo.platform->fullname,
                     DRM_SHIM_PLATFORM_NAME), 0);
    drmFreeDevices(devices, ret);
}

static void test_free_devices_null_safety(void) {
    /* Must not crash when given NULL / zero count. */
    drmFreeDevices(NULL, 0);
}

static void test_get_devices_legacy(void) {
    drmDevicePtr devices[1] = {NULL};
    int ret = drmGetDevices(devices, 1);
    ASSERT_EQ(ret, 1);
    drmFreeDevices(devices, ret);
}

/* ------------------------------------------------------------------ */

int main(void) {
    printf("drm-shim: device enumeration tests\n");
    RUN_TEST(test_get_devices2_count);
    RUN_TEST(test_get_devices2_fill);
    RUN_TEST(test_render_node_path);
    RUN_TEST(test_card_node_path);
    RUN_TEST(test_bus_type_platform);
    RUN_TEST(test_platform_fullname);
    RUN_TEST(test_free_devices_null_safety);
    RUN_TEST(test_get_devices_legacy);
    TEST_SUMMARY();
    return TEST_EXIT_CODE();
}
