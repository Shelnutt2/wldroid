/*
 * test_toplevel_tracking.c — Unit tests for wl_list-based toplevel tracking
 *
 * Exercises the add/remove/focus-reorder patterns used by the multi-window
 * compositor without needing full wlroots.  Only requires <wayland-util.h>
 * for wl_list.
 *
 * Build:  cc $(pkg-config --cflags wayland-server) \
 *            -o test_toplevel_tracking test_toplevel_tracking.c \
 *            $(pkg-config --libs wayland-server)
 */
#include "test_harness.h"
#include <wayland-util.h>  /* wl_list */

/* Minimal mock of a compositor_toplevel for list tests. */
struct mock_toplevel {
    struct wl_list link;
    int id;
};

TEST(test_add_remove) {
    struct wl_list toplevels;
    wl_list_init(&toplevels);

    struct mock_toplevel a = { .id = 1 };
    struct mock_toplevel b = { .id = 2 };
    wl_list_insert(&toplevels, &a.link);
    wl_list_insert(&toplevels, &b.link);
    ASSERT_EQ(wl_list_length(&toplevels), 2);

    wl_list_remove(&a.link);
    ASSERT_EQ(wl_list_length(&toplevels), 1);

    /* b should be the only remaining entry. */
    struct mock_toplevel *first = wl_container_of(toplevels.next, first, link);
    ASSERT_EQ(first->id, 2);
}

TEST(test_focus_reorder) {
    struct wl_list toplevels;
    wl_list_init(&toplevels);

    struct mock_toplevel a = { .id = 1 };
    struct mock_toplevel b = { .id = 2 };
    struct mock_toplevel c = { .id = 3 };
    wl_list_insert(&toplevels, &a.link);
    wl_list_insert(&toplevels, &b.link);
    wl_list_insert(&toplevels, &c.link);
    /* Order is c, b, a (insert at head). */

    /* "Focus" a by moving to front (MRU reorder). */
    wl_list_remove(&a.link);
    wl_list_insert(&toplevels, &a.link);
    /* Now order should be a, c, b. */
    struct mock_toplevel *first = wl_container_of(toplevels.next, first, link);
    ASSERT_EQ(first->id, 1);
}

TEST(test_empty_list) {
    struct wl_list toplevels;
    wl_list_init(&toplevels);
    ASSERT_TRUE(wl_list_empty(&toplevels));
    ASSERT_EQ(wl_list_length(&toplevels), 0);
}

TEST(test_iteration_order) {
    struct wl_list toplevels;
    wl_list_init(&toplevels);

    struct mock_toplevel a = { .id = 1 };
    struct mock_toplevel b = { .id = 2 };
    struct mock_toplevel c = { .id = 3 };

    /* Insert in order: a, b, c — wl_list_insert prepends, so list is c, b, a */
    wl_list_insert(&toplevels, &a.link);
    wl_list_insert(&toplevels, &b.link);
    wl_list_insert(&toplevels, &c.link);

    int expected[] = {3, 2, 1};
    int i = 0;
    struct mock_toplevel *t;
    wl_list_for_each(t, &toplevels, link) {
        ASSERT_EQ(t->id, expected[i]);
        i++;
    }
    ASSERT_EQ(i, 3);
}

TEST(test_remove_middle) {
    struct wl_list toplevels;
    wl_list_init(&toplevels);

    struct mock_toplevel a = { .id = 1 };
    struct mock_toplevel b = { .id = 2 };
    struct mock_toplevel c = { .id = 3 };
    wl_list_insert(&toplevels, &a.link);
    wl_list_insert(&toplevels, &b.link);
    wl_list_insert(&toplevels, &c.link);

    /* Remove b (middle element). */
    wl_list_remove(&b.link);
    ASSERT_EQ(wl_list_length(&toplevels), 2);

    /* First should be c, second should be a. */
    struct mock_toplevel *first = wl_container_of(toplevels.next, first, link);
    ASSERT_EQ(first->id, 3);
    struct mock_toplevel *second = wl_container_of(first->link.next, second, link);
    ASSERT_EQ(second->id, 1);
}

int main(void) {
    printf("Toplevel Tracking Tests:\n");
    RUN_TEST(test_add_remove);
    RUN_TEST(test_focus_reorder);
    RUN_TEST(test_empty_list);
    RUN_TEST(test_iteration_order);
    RUN_TEST(test_remove_middle);
    TEST_SUMMARY();
}
