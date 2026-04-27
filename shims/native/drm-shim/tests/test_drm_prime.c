/*
 * test_drm_prime.c — Tests for PRIME / auth / ioctl stubs in drm-shim
 *
 * Verifies drmPrimeHandleToFD, drmPrimeFDToHandle, drmGetMagic,
 * drmAuthMagic, drmIoctl, and drmClose by linking directly against
 * drm_shim.c.
 */
#include <stdio.h>
#include <stdlib.h>
#include <stdint.h>
#include <unistd.h>
#include <errno.h>
#include <xf86drm.h>
#include "test_common.h"

/* ------------------------------------------------------------------ */
/* drmPrimeHandleToFD / drmPrimeFDToHandle                            */
/* ------------------------------------------------------------------ */

static void test_prime_handle_to_fd(void) {
    int prime_fd = -1;
    int ret = drmPrimeHandleToFD(3, 1, 0, &prime_fd);
    ASSERT_EQ(ret, 0);
    ASSERT(prime_fd >= 0);
    close(prime_fd);
}

static void test_prime_fd_is_valid(void) {
    int prime_fd = -1;
    int ret = drmPrimeHandleToFD(3, 1, 0, &prime_fd);
    ASSERT_EQ(ret, 0);
    ASSERT(prime_fd >= 0);
    /* The shim returns a memfd, which should be writable. */
    ssize_t n = write(prime_fd, "x", 1);
    ASSERT_EQ(n, 1);
    close(prime_fd);
}

static void test_prime_fd_to_handle(void) {
    uint32_t handle = 0;
    int ret = drmPrimeFDToHandle(3, 0, &handle);
    ASSERT_EQ(ret, 0);
    ASSERT(handle > 0);
}

static void test_prime_handles_unique(void) {
    uint32_t h1 = 0, h2 = 0;
    int r1 = drmPrimeFDToHandle(3, 0, &h1);
    int r2 = drmPrimeFDToHandle(3, 0, &h2);
    ASSERT_EQ(r1, 0);
    ASSERT_EQ(r2, 0);
    ASSERT_NE(h1, h2);
}

static void test_prime_null_fd_ptr(void) {
    int ret = drmPrimeHandleToFD(3, 1, 0, NULL);
    ASSERT_EQ(ret, -EINVAL);
}

static void test_prime_null_handle_ptr(void) {
    int ret = drmPrimeFDToHandle(3, 0, NULL);
    ASSERT_EQ(ret, -EINVAL);
}

/* ------------------------------------------------------------------ */
/* drmGetMagic / drmAuthMagic                                         */
/* ------------------------------------------------------------------ */

static void test_auth_magic(void) {
    drm_magic_t magic = 0;
    int ret = drmGetMagic(3, &magic);
    ASSERT_EQ(ret, 0);
    ASSERT_NE(magic, (drm_magic_t)0);
    ret = drmAuthMagic(3, magic);
    ASSERT_EQ(ret, 0);
}

/* ------------------------------------------------------------------ */
/* drmIoctl                                                           */
/* ------------------------------------------------------------------ */

static void test_ioctl_succeeds(void) {
    int ret = drmIoctl(3, 0, NULL);
    ASSERT_EQ(ret, 0);
}

/* ------------------------------------------------------------------ */

int main(void) {
    printf("drm-shim: PRIME / auth / ioctl tests\n");
    RUN_TEST(test_prime_handle_to_fd);
    RUN_TEST(test_prime_fd_is_valid);
    RUN_TEST(test_prime_fd_to_handle);
    RUN_TEST(test_prime_handles_unique);
    RUN_TEST(test_prime_null_fd_ptr);
    RUN_TEST(test_prime_null_handle_ptr);
    RUN_TEST(test_auth_magic);
    RUN_TEST(test_ioctl_succeeds);
    TEST_SUMMARY();
    return TEST_EXIT_CODE();
}
