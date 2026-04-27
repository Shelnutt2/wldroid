/*
 * test_common.h — Shared test macros for drm-shim tests
 *
 * Provides lightweight assertion and test-runner macros with file/line
 * reporting.  No Android-specific dependencies — tests can run on any Linux host.
 */
#ifndef TEST_COMMON_H
#define TEST_COMMON_H

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static int g_tests_run    = 0;
static int g_tests_passed = 0;
static int g_tests_failed = 0;

#define TEST_FAIL(fmt, ...)                                                \
    do {                                                                    \
        fprintf(stderr, "  FAIL [%s:%d] " fmt "\n",                        \
                __FILE__, __LINE__, ##__VA_ARGS__);                        \
        g_tests_failed++;                                                  \
        return;                                                            \
    } while (0)

#define ASSERT(cond)                                                       \
    do {                                                                    \
        if (!(cond)) {                                                     \
            TEST_FAIL("assertion failed: %s", #cond);                      \
        }                                                                  \
    } while (0)

#define ASSERT_EQ(a, b)                                                    \
    do {                                                                    \
        if ((a) != (b)) {                                                  \
            TEST_FAIL("%s != %s (%lld != %lld)",                           \
                      #a, #b, (long long)(a), (long long)(b));             \
        }                                                                  \
    } while (0)

#define ASSERT_NE(a, b)                                                    \
    do {                                                                    \
        if ((a) == (b)) {                                                  \
            TEST_FAIL("%s == %s (%lld)", #a, #b, (long long)(a));          \
        }                                                                  \
    } while (0)

#define ASSERT_NULL(ptr)                                                   \
    do {                                                                    \
        if ((ptr) != NULL) {                                               \
            TEST_FAIL("%s is not NULL", #ptr);                             \
        }                                                                  \
    } while (0)

#define ASSERT_NOT_NULL(ptr)                                               \
    do {                                                                    \
        if ((ptr) == NULL) {                                               \
            TEST_FAIL("%s is NULL", #ptr);                                 \
        }                                                                  \
    } while (0)

#define RUN_TEST(fn)                                                       \
    do {                                                                    \
        int _before = g_tests_failed;                                      \
        g_tests_run++;                                                     \
        printf("  RUN  %s\n", #fn);                                        \
        fn();                                                              \
        if (g_tests_failed == _before) {                                   \
            g_tests_passed++;                                              \
            printf("  PASS %s\n", #fn);                                    \
        }                                                                  \
    } while (0)

#define TEST_SUMMARY()                                                     \
    do {                                                                    \
        printf("\n%d/%d tests passed",                                     \
               g_tests_passed, g_tests_run);                               \
        if (g_tests_failed > 0) {                                          \
            printf(" (%d FAILED)\n", g_tests_failed);                      \
        } else {                                                           \
            printf("\n");                                                   \
        }                                                                  \
    } while (0)

#define TEST_EXIT_CODE() (g_tests_failed > 0 ? 1 : 0)

#endif /* TEST_COMMON_H */
