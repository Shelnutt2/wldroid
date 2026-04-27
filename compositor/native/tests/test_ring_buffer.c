/*
 * test_ring_buffer.c — Verify the SPSC ring buffer algorithm
 *
 * The ring buffer in input_handler.c uses file-static functions with
 * heavy wlroots/Android dependencies, making direct inclusion impractical.
 * This test verifies the same algorithm independently — the logic is copied
 * verbatim from input_handler.c (ring_reserve / ring_commit / ring_peek /
 * ring_consume).
 *
 * Build (host-only):
 *   cc test_ring_buffer.c -o test_ring_buffer && ./test_ring_buffer
 */
#include "test_harness.h"
#include <stdint.h>

/* ── Ring buffer (copied from input_handler.c) ────────────────────── */

#define RING_SIZE 256          /* must be power of 2 */
#define RING_MASK (RING_SIZE - 1)

struct test_event {
    int type;
    uint32_t value;
};

static struct test_event ring[RING_SIZE];
static unsigned int ring_head;  /* producer index */
static unsigned int ring_tail;  /* consumer index */

static struct test_event *ring_reserve(void) {
    unsigned int head = __atomic_load_n(&ring_head, __ATOMIC_RELAXED);
    unsigned int tail = __atomic_load_n(&ring_tail, __ATOMIC_ACQUIRE);
    if (((head + 1) & RING_MASK) == (tail & RING_MASK))
        return NULL; /* full */
    return &ring[head & RING_MASK];
}

static void ring_commit(void) {
    unsigned int head = __atomic_load_n(&ring_head, __ATOMIC_RELAXED);
    __atomic_store_n(&ring_head, (head + 1) & RING_MASK, __ATOMIC_RELEASE);
}

static struct test_event *ring_peek(void) {
    unsigned int tail = __atomic_load_n(&ring_tail, __ATOMIC_RELAXED);
    unsigned int head = __atomic_load_n(&ring_head, __ATOMIC_ACQUIRE);
    if (tail == head)
        return NULL; /* empty */
    return &ring[tail & RING_MASK];
}

static void ring_consume(void) {
    unsigned int tail = __atomic_load_n(&ring_tail, __ATOMIC_RELAXED);
    __atomic_store_n(&ring_tail, (tail + 1) & RING_MASK, __ATOMIC_RELEASE);
}

static void ring_reset(void) {
    ring_head = 0;
    ring_tail = 0;
    memset(ring, 0, sizeof(ring));
}

/* ── Tests ────────────────────────────────────────────────────────── */

TEST(empty_ring_peek_returns_null) {
    ring_reset();
    ASSERT_NULL(ring_peek());
}

TEST(single_enqueue_dequeue) {
    ring_reset();
    struct test_event *ev = ring_reserve();
    ASSERT_NOT_NULL(ev);
    ev->type = 42;
    ev->value = 100;
    ring_commit();

    struct test_event *peeked = ring_peek();
    ASSERT_NOT_NULL(peeked);
    ASSERT_EQ(peeked->type, 42);
    ASSERT_EQ(peeked->value, 100);
    ring_consume();

    ASSERT_NULL(ring_peek());
}

TEST(full_ring_returns_null) {
    ring_reset();
    /* Fill to capacity (RING_SIZE - 1 usable slots) */
    for (int i = 0; i < RING_SIZE - 1; i++) {
        struct test_event *ev = ring_reserve();
        ASSERT_NOT_NULL(ev);
        ev->type = i;
        ring_commit();
    }
    /* Next reserve should fail — ring is full */
    ASSERT_NULL(ring_reserve());
}

TEST(fifo_order) {
    ring_reset();
    for (int i = 0; i < 10; i++) {
        struct test_event *ev = ring_reserve();
        ASSERT_NOT_NULL(ev);
        ev->value = (uint32_t)i;
        ring_commit();
    }
    for (int i = 0; i < 10; i++) {
        struct test_event *peeked = ring_peek();
        ASSERT_NOT_NULL(peeked);
        ASSERT_EQ(peeked->value, (uint32_t)i);
        ring_consume();
    }
    ASSERT_NULL(ring_peek());
}

TEST(wrap_around) {
    ring_reset();
    /* Fill and drain multiple times to force index wrap-around */
    for (int round = 0; round < 3; round++) {
        for (int i = 0; i < 200; i++) {
            struct test_event *ev = ring_reserve();
            ASSERT_NOT_NULL(ev);
            ev->value = (uint32_t)(round * 1000 + i);
            ring_commit();
        }
        for (int i = 0; i < 200; i++) {
            struct test_event *peeked = ring_peek();
            ASSERT_NOT_NULL(peeked);
            ASSERT_EQ(peeked->value, (uint32_t)(round * 1000 + i));
            ring_consume();
        }
        ASSERT_NULL(ring_peek());
    }
}

TEST(consume_then_refill) {
    ring_reset();
    /* Fill completely */
    for (int i = 0; i < RING_SIZE - 1; i++) {
        struct test_event *ev = ring_reserve();
        ASSERT_NOT_NULL(ev);
        ev->type = i;
        ring_commit();
    }
    ASSERT_NULL(ring_reserve());

    /* Consume all */
    for (int i = 0; i < RING_SIZE - 1; i++) {
        ASSERT_NOT_NULL(ring_peek());
        ring_consume();
    }
    ASSERT_NULL(ring_peek());

    /* Should be able to fill again */
    struct test_event *ev = ring_reserve();
    ASSERT_NOT_NULL(ev);
    ev->type = 999;
    ring_commit();

    struct test_event *peeked = ring_peek();
    ASSERT_NOT_NULL(peeked);
    ASSERT_EQ(peeked->type, 999);
}

int main(void) {
    printf("Ring buffer tests\n");

    RUN_TEST(empty_ring_peek_returns_null);
    RUN_TEST(single_enqueue_dequeue);
    RUN_TEST(full_ring_returns_null);
    RUN_TEST(fifo_order);
    RUN_TEST(wrap_around);
    RUN_TEST(consume_then_refill);

    TEST_SUMMARY();
}
