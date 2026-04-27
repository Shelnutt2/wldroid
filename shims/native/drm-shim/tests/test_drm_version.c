/*
 * test_drm_version.c — Tests for version / capability queries in drm-shim
 *
 * Verifies drmGetVersion, drmFreeVersion, drmGetCap, drmGetBusid,
 * drmFreeBusid, drmGetNodeTypeFromFd, drmGetRenderDeviceNameFromFd,
 * and drmGetDeviceNameFromFd2 by linking directly against drm_shim.c.
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>
#include <errno.h>
#include <xf86drm.h>
#include "test_common.h"
#include "drm_shim.h"

/* ------------------------------------------------------------------ */
/* drmGetVersion / drmFreeVersion                                     */
/* ------------------------------------------------------------------ */

static void test_version_not_null(void) {
    drmVersionPtr v = drmGetVersion(3);
    ASSERT_NOT_NULL(v);
    drmFreeVersion(v);
}

static void test_driver_name(void) {
    drmVersionPtr v = drmGetVersion(3);
    ASSERT_NOT_NULL(v);
    ASSERT_NOT_NULL(v->name);
    ASSERT_EQ(strcmp(v->name, DRM_SHIM_DRIVER_NAME), 0);
    drmFreeVersion(v);
}

static void test_version_numbers(void) {
    drmVersionPtr v = drmGetVersion(3);
    ASSERT_NOT_NULL(v);
    ASSERT_EQ(v->version_major, DRM_SHIM_VERSION_MAJOR);
    ASSERT_EQ(v->version_minor, DRM_SHIM_VERSION_MINOR);
    ASSERT_EQ(v->version_patchlevel, DRM_SHIM_VERSION_PATCH);
    drmFreeVersion(v);
}

static void test_description(void) {
    drmVersionPtr v = drmGetVersion(3);
    ASSERT_NOT_NULL(v);
    ASSERT_NOT_NULL(v->desc);
    ASSERT(v->desc_len > 0);
    drmFreeVersion(v);
}

static void test_date(void) {
    drmVersionPtr v = drmGetVersion(3);
    ASSERT_NOT_NULL(v);
    ASSERT_NOT_NULL(v->date);
    ASSERT_EQ(strcmp(v->date, DRM_SHIM_DRIVER_DATE), 0);
    drmFreeVersion(v);
}

static void test_name_len(void) {
    drmVersionPtr v = drmGetVersion(3);
    ASSERT_NOT_NULL(v);
    ASSERT_EQ(v->name_len, (int)strlen(DRM_SHIM_DRIVER_NAME));
    drmFreeVersion(v);
}

static void test_free_version_null_safety(void) {
    /* Must not crash when given NULL. */
    drmFreeVersion(NULL);
}

static void test_version_negative_fd(void) {
    /* The shim fakes all fds, so -1 should still return a version. */
    drmVersionPtr v = drmGetVersion(-1);
    ASSERT_NOT_NULL(v);
    drmFreeVersion(v);
}

/* ------------------------------------------------------------------ */
/* drmGetCap                                                          */
/* ------------------------------------------------------------------ */

static void test_cap_prime(void) {
    uint64_t val = 0;
    int ret = drmGetCap(3, DRM_CAP_PRIME, &val);
    ASSERT_EQ(ret, 0);
    ASSERT_EQ(val, (uint64_t)(DRM_PRIME_CAP_IMPORT | DRM_PRIME_CAP_EXPORT));
}

static void test_cap_syncobj(void) {
    uint64_t val = 0;
    int ret = drmGetCap(3, DRM_CAP_SYNCOBJ, &val);
    ASSERT_EQ(ret, 0);
    ASSERT_EQ(val, (uint64_t)1);
}

static void test_cap_invalid(void) {
    uint64_t val = 0;
    int ret = drmGetCap(3, 9999, &val);
    ASSERT_EQ(ret, -EINVAL);
}

/* ------------------------------------------------------------------ */
/* drmGetBusid / drmFreeBusid                                         */
/* ------------------------------------------------------------------ */

static void test_busid(void) {
    char *busid = drmGetBusid(3);
    ASSERT_NOT_NULL(busid);
    ASSERT_EQ(strcmp(busid, DRM_SHIM_BUS_ID), 0);
    drmFreeBusid(busid);
}

/* ------------------------------------------------------------------ */
/* Node type / device name helpers                                    */
/* ------------------------------------------------------------------ */

static void test_node_type(void) {
    int type = drmGetNodeTypeFromFd(3);
    ASSERT_EQ(type, DRM_NODE_RENDER);
}

static void test_render_device_name(void) {
    char *name = drmGetRenderDeviceNameFromFd(3);
    ASSERT_NOT_NULL(name);
    ASSERT_EQ(strcmp(name, DRM_SHIM_RENDER_NODE), 0);
    free(name);
}

static void test_device_name_from_fd2(void) {
    char *name = drmGetDeviceNameFromFd2(3);
    ASSERT_NOT_NULL(name);
    ASSERT_EQ(strcmp(name, DRM_SHIM_RENDER_NODE), 0);
    free(name);
}

/* ------------------------------------------------------------------ */

int main(void) {
    printf("drm-shim: version / capability tests\n");
    RUN_TEST(test_version_not_null);
    RUN_TEST(test_driver_name);
    RUN_TEST(test_version_numbers);
    RUN_TEST(test_description);
    RUN_TEST(test_date);
    RUN_TEST(test_name_len);
    RUN_TEST(test_free_version_null_safety);
    RUN_TEST(test_version_negative_fd);
    RUN_TEST(test_cap_prime);
    RUN_TEST(test_cap_syncobj);
    RUN_TEST(test_cap_invalid);
    RUN_TEST(test_busid);
    RUN_TEST(test_node_type);
    RUN_TEST(test_render_device_name);
    RUN_TEST(test_device_name_from_fd2);
    TEST_SUMMARY();
    return TEST_EXIT_CODE();
}
