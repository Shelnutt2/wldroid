/*
 * test_harness.h — Minimal C test framework (no external dependencies)
 *
 * Provides assert macros and a simple test runner with pass/fail summary.
 */
#ifndef TEST_HARNESS_H
#define TEST_HARNESS_H

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static int tests_run = 0, tests_passed = 0, tests_failed = 0;

#define TEST(name) static void name(void)
#define RUN_TEST(name) do { \
    printf("  %-50s", #name); \
    tests_run++; \
    name(); \
    tests_passed++; \
    printf("PASS\n"); \
} while(0)

#define ASSERT_EQ(a, b) do { \
    if ((a) != (b)) { \
        printf("FAIL\n    %s:%d: %s != %s\n", __FILE__, __LINE__, #a, #b); \
        tests_failed++; tests_passed--; return; \
    } \
} while(0)

#define ASSERT_NEQ(a, b) do { \
    if ((a) == (b)) { \
        printf("FAIL\n    %s:%d: %s == %s\n", __FILE__, __LINE__, #a, #b); \
        tests_failed++; tests_passed--; return; \
    } \
} while(0)

#define ASSERT_TRUE(x) ASSERT_NEQ((x), 0)
#define ASSERT_NULL(x) ASSERT_EQ((void*)(x), NULL)
#define ASSERT_NOT_NULL(x) ASSERT_NEQ((void*)(x), NULL)

#define TEST_SUMMARY() do { \
    printf("\n%d/%d tests passed", tests_passed, tests_run); \
    if (tests_failed) printf(", %d FAILED", tests_failed); \
    printf("\n"); \
    return tests_failed ? 1 : 0; \
} while(0)

#endif /* TEST_HARNESS_H */
