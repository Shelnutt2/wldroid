/*
 * test_client.c — Built-in Wayland test client
 *
 * Draws a 4-quadrant colour pattern (red/green/blue/yellow) via
 * xdg-shell + wl_shm.  Runs entirely in-process on a background
 * pthread, connected to the compositor through a socketpair.
 */
#include "test_client.h"
#include "xdg-shell-client-protocol.h"

#include <errno.h>
#include <linux/memfd.h>
#include <pthread.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/socket.h>
#include <sys/syscall.h>
#include <unistd.h>

#include <android/log.h>
#include <wayland-client.h>

#define LOG_TAG "TestClient"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

/* Default surface size — the compositor may resize via configure events. */
#define DEFAULT_WIDTH  1080
#define DEFAULT_HEIGHT 2400

/* ARGB8888 colour values (little-endian uint32_t). */
#define COLOR_RED    0xFFFF0000
#define COLOR_GREEN  0xFF00FF00
#define COLOR_BLUE   0xFF0000FF
#define COLOR_YELLOW 0xFFFFFF00

/* ------------------------------------------------------------------ */
/* Per-client state                                                    */
/* ------------------------------------------------------------------ */

struct test_client_state {
    /* Wayland globals */
    struct wl_display    *display;
    struct wl_registry   *registry;
    struct wl_compositor *compositor;
    struct wl_shm        *shm;
    struct xdg_wm_base   *wm_base;

    /* Surface chain */
    struct wl_surface    *surface;
    struct xdg_surface   *xdg_surface;
    struct xdg_toplevel  *xdg_toplevel;

    /* Buffer state */
    int width;
    int height;
    int configured; /* set to 1 after the first xdg_surface.configure */
};

/* ------------------------------------------------------------------ */
/* SHM buffer helpers                                                  */
/* ------------------------------------------------------------------ */

static void fill_pattern(uint32_t *pixels, int w, int h) {
    int half_w = w / 2;
    int half_h = h / 2;
    for (int y = 0; y < h; y++) {
        for (int x = 0; x < w; x++) {
            if (y < half_h)
                pixels[y * w + x] = (x < half_w) ? COLOR_RED : COLOR_GREEN;
            else
                pixels[y * w + x] = (x < half_w) ? COLOR_BLUE : COLOR_YELLOW;
        }
    }
}

/**
 * Create an SHM buffer, fill it with the test pattern, attach it to
 * the surface, and commit.
 */
static void draw_frame(struct test_client_state *state) {
    int w = state->width;
    int h = state->height;
    int stride = w * 4;
    int size = stride * h;

    /*
     * memfd_create syscall is available since Linux 3.17 (all Android API 26+
     * devices).  The NDK only exposes the wrapper from API 30, so we call the
     * syscall directly to support our minSdk of 26.
     */
    int fd = (int)syscall(__NR_memfd_create, "test-client-buf", 0);
    if (fd < 0) {
        LOGE("memfd_create failed: %s", strerror(errno));
        return;
    }
    if (ftruncate(fd, size) != 0) {
        LOGE("ftruncate failed: %s", strerror(errno));
        close(fd);
        return;
    }

    uint32_t *data = mmap(NULL, size, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
    if (data == MAP_FAILED) {
        LOGE("mmap failed: %s", strerror(errno));
        close(fd);
        return;
    }

    fill_pattern(data, w, h);

    struct wl_shm_pool *pool = wl_shm_create_pool(state->shm, fd, size);
    struct wl_buffer *buffer = wl_shm_pool_create_buffer(
        pool, 0, w, h, stride, WL_SHM_FORMAT_ARGB8888);
    wl_shm_pool_destroy(pool);

    munmap(data, size);
    close(fd);

    wl_surface_attach(state->surface, buffer, 0, 0);
    wl_surface_damage_buffer(state->surface, 0, 0, w, h);
    wl_surface_commit(state->surface);

    /* Buffer will be released by the compositor after rendering. */
    /* For simplicity we let libwayland handle cleanup on disconnect. */
}

/* ------------------------------------------------------------------ */
/* xdg_wm_base listener (ping/pong)                                    */
/* ------------------------------------------------------------------ */

static void xdg_wm_base_handle_ping(void *data, struct xdg_wm_base *wm_base,
                                     uint32_t serial) {
    (void)data;
    xdg_wm_base_pong(wm_base, serial);
}

static const struct xdg_wm_base_listener wm_base_listener = {
    .ping = xdg_wm_base_handle_ping,
};

/* ------------------------------------------------------------------ */
/* xdg_surface listener                                                */
/* ------------------------------------------------------------------ */

static void xdg_surface_handle_configure(void *data,
                                         struct xdg_surface *xdg_surface,
                                         uint32_t serial) {
    struct test_client_state *state = data;
    xdg_surface_ack_configure(xdg_surface, serial);

    state->configured = 1;
    draw_frame(state);
    LOGI("Configured and drew %dx%d test pattern", state->width, state->height);
}

static const struct xdg_surface_listener xdg_surface_listener = {
    .configure = xdg_surface_handle_configure,
};

/* ------------------------------------------------------------------ */
/* xdg_toplevel listener                                               */
/* ------------------------------------------------------------------ */

static void xdg_toplevel_handle_configure(void *data,
                                          struct xdg_toplevel *toplevel,
                                          int32_t width, int32_t height,
                                          struct wl_array *states) {
    (void)toplevel;
    (void)states;
    struct test_client_state *state = data;

    if (width > 0 && height > 0) {
        state->width = width;
        state->height = height;
    }
}

static void xdg_toplevel_handle_close(void *data,
                                      struct xdg_toplevel *toplevel) {
    (void)data;
    (void)toplevel;
    /* Nothing to do — compositor teardown will disconnect us. */
}

static void xdg_toplevel_handle_configure_bounds(void *data,
                                                 struct xdg_toplevel *toplevel,
                                                 int32_t width, int32_t height) {
    (void)data;
    (void)toplevel;
    (void)width;
    (void)height;
}

static void xdg_toplevel_handle_wm_capabilities(void *data,
                                                 struct xdg_toplevel *toplevel,
                                                 struct wl_array *capabilities) {
    (void)data;
    (void)toplevel;
    (void)capabilities;
}

static const struct xdg_toplevel_listener toplevel_listener = {
    .configure = xdg_toplevel_handle_configure,
    .close = xdg_toplevel_handle_close,
    .configure_bounds = xdg_toplevel_handle_configure_bounds,
    .wm_capabilities = xdg_toplevel_handle_wm_capabilities,
};

/* ------------------------------------------------------------------ */
/* Registry listener                                                   */
/* ------------------------------------------------------------------ */

static void registry_handle_global(void *data, struct wl_registry *registry,
                                   uint32_t name, const char *interface,
                                   uint32_t version) {
    struct test_client_state *state = data;
    (void)version;

    if (strcmp(interface, wl_compositor_interface.name) == 0) {
        state->compositor = wl_registry_bind(
            registry, name, &wl_compositor_interface, 4);
    } else if (strcmp(interface, wl_shm_interface.name) == 0) {
        state->shm = wl_registry_bind(
            registry, name, &wl_shm_interface, 1);
    } else if (strcmp(interface, xdg_wm_base_interface.name) == 0) {
        state->wm_base = wl_registry_bind(
            registry, name, &xdg_wm_base_interface, 1);
        xdg_wm_base_add_listener(state->wm_base, &wm_base_listener, state);
    }
}

static void registry_handle_global_remove(void *data,
                                          struct wl_registry *registry,
                                          uint32_t name) {
    (void)data;
    (void)registry;
    (void)name;
}

static const struct wl_registry_listener registry_listener = {
    .global = registry_handle_global,
    .global_remove = registry_handle_global_remove,
};

/* ------------------------------------------------------------------ */
/* Client thread                                                       */
/* ------------------------------------------------------------------ */

static void *test_client_thread(void *arg) {
    int client_fd = (int)(intptr_t)arg;

    LOGI("Test client thread started (fd=%d)", client_fd);

    struct test_client_state state = {0};
    state.width = DEFAULT_WIDTH;
    state.height = DEFAULT_HEIGHT;

    state.display = wl_display_connect_to_fd(client_fd);
    if (!state.display) {
        LOGE("wl_display_connect_to_fd failed");
        close(client_fd);
        return NULL;
    }

    /* Bind globals. */
    state.registry = wl_display_get_registry(state.display);
    wl_registry_add_listener(state.registry, &registry_listener, &state);
    wl_display_roundtrip(state.display);

    if (!state.compositor || !state.shm || !state.wm_base) {
        LOGE("Missing required globals (compositor=%p shm=%p wm_base=%p)",
             (void *)state.compositor, (void *)state.shm,
             (void *)state.wm_base);
        wl_display_disconnect(state.display);
        return NULL;
    }

    /* Create xdg-shell surface. */
    state.surface = wl_compositor_create_surface(state.compositor);
    state.xdg_surface = xdg_wm_base_get_xdg_surface(state.wm_base,
                                                     state.surface);
    xdg_surface_add_listener(state.xdg_surface, &xdg_surface_listener, &state);

    state.xdg_toplevel = xdg_surface_get_toplevel(state.xdg_surface);
    xdg_toplevel_add_listener(state.xdg_toplevel, &toplevel_listener, &state);
    xdg_toplevel_set_title(state.xdg_toplevel, "Test Pattern");

    /* Initial commit to trigger the configure sequence. */
    wl_surface_commit(state.surface);

    /* Run event loop until the compositor disconnects us. */
    LOGI("Entering event loop");
    while (wl_display_dispatch(state.display) != -1) {
        /* keep running */
    }

    LOGI("Test client event loop exited");
    wl_display_disconnect(state.display);
    return NULL;
}

/* ------------------------------------------------------------------ */
/* Public API                                                          */
/* ------------------------------------------------------------------ */

int test_client_start(struct compositor_server *server) {
    if (!server || !server->wl_display) {
        LOGE("test_client_start: invalid server");
        return -1;
    }

    /*
     * Create a socketpair:
     *   fds[0] → server side (handed to wl_client_create)
     *   fds[1] → client side (passed to the client thread)
     */
    int fds[2];
    if (socketpair(AF_UNIX, SOCK_STREAM, 0, fds) != 0) {
        LOGE("socketpair failed: %s", strerror(errno));
        return -1;
    }

    /*
     * Register the server end with libwayland-server.  After this the
     * compositor's event loop will handle protocol messages from fds[0].
     */
    struct wl_client *client = wl_client_create(server->wl_display, fds[0]);
    if (!client) {
        LOGE("wl_client_create failed");
        close(fds[0]);
        close(fds[1]);
        return -1;
    }

    /* Spawn the client thread with fds[1]. */
    pthread_t thread;
    pthread_attr_t attr;
    pthread_attr_init(&attr);
    pthread_attr_setdetachstate(&attr, PTHREAD_CREATE_DETACHED);

    int rc = pthread_create(&thread, &attr, test_client_thread,
                            (void *)(intptr_t)fds[1]);
    pthread_attr_destroy(&attr);

    if (rc != 0) {
        LOGE("pthread_create failed: %d", rc);
        /* The server-side fd is owned by the wl_client now; destroying
         * the client will close it.  We only need to close our end. */
        close(fds[1]);
        return -1;
    }

    LOGI("Test client started");
    return 0;
}
