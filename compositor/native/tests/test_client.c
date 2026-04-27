/*
 * test_client.c — Minimal Wayland test client for compositor validation
 *
 * Validates the SHM buffer path, XDG shell, and frame callbacks by:
 *   1. Connecting to the compositor via WAYLAND_DISPLAY
 *   2. Binding wl_compositor (v4+), wl_shm, and xdg_wm_base (v1+)
 *   3. Creating an xdg_toplevel surface with a POSIX-shm backed buffer
 *   4. Rendering 60 frames with an animated color fill
 *   5. Exiting cleanly with diagnostics
 *
 * Build (standalone):
 *   wayland-scanner private-code .../xdg-shell.xml xdg-shell-protocol.c
 *   wayland-scanner client-header .../xdg-shell.xml xdg-shell-client-protocol.h
 *   cc -D_GNU_SOURCE -o test_client test_client.c xdg-shell-protocol.c \
 *      $(pkg-config --cflags --libs wayland-client)
 */
#ifndef _GNU_SOURCE
#define _GNU_SOURCE
#endif
#include <errno.h>
#include <fcntl.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <time.h>
#include <unistd.h>

#include <wayland-client.h>
#include "xdg-shell-client-protocol.h"

/* ── Constants ────────────────────────────────────────────────────────── */

#define WIDTH       640
#define HEIGHT      480
#define STRIDE      (WIDTH * 4)
#define BUF_SIZE    (STRIDE * HEIGHT)
#define MAX_FRAMES  60

/* Coder blue in XRGB8888: X=0x00 R=0x4A G=0x67 B=0xFF → 0x004A67FF */
#define CODER_BLUE  0x004A67FF

#define LOG(fmt, ...) fprintf(stdout, "[test_client] " fmt "\n", ##__VA_ARGS__)
#define ERR(fmt, ...) fprintf(stderr, "[test_client] ERROR: " fmt "\n", ##__VA_ARGS__)

/* ── Globals ──────────────────────────────────────────────────────────── */

static struct wl_display    *display;
static struct wl_registry   *registry;
static struct wl_compositor *compositor;
static struct wl_shm        *shm;
static struct xdg_wm_base   *wm_base;

static struct wl_surface     *surface;
static struct xdg_surface    *xdg_surf;
static struct xdg_toplevel   *toplevel;
static struct wl_buffer      *buffer;

static uint32_t *shm_data;
static int       frame_count;
static bool      running = true;
static bool      surface_configured = false;

/* ── Helpers ──────────────────────────────────────────────────────────── */

static uint64_t now_ms(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (uint64_t)ts.tv_sec * 1000 + (uint64_t)ts.tv_nsec / 1000000;
}

/* Create an anonymous shared-memory fd using memfd_create, with a tmpfile
 * fallback for older kernels that lack the syscall. */
static int create_shm_fd(size_t size) {
    int fd = memfd_create("wayland-shm", MFD_CLOEXEC);
    if (fd < 0) {
        /* Fallback: open a temp file, unlink immediately. */
        char path[] = "/tmp/wayland-shm-XXXXXX";
        fd = mkstemp(path);
        if (fd < 0) return -1;
        unlink(path);
    }
    if (ftruncate(fd, (off_t)size) < 0) {
        close(fd);
        return -1;
    }
    return fd;
}

/* Fill the buffer with a colour that shifts slightly each frame. */
static void fill_buffer(int frame) {
    /* Animate by cycling the green channel around Coder blue. */
    uint8_t g = (uint8_t)(0x67 + (frame * 3) % 64);
    uint32_t pixel = (uint32_t)((0x00 << 24) | (0x4A << 16) | (g << 8) | 0xFF);
    for (int i = 0; i < WIDTH * HEIGHT; i++) {
        shm_data[i] = pixel;
    }
}

/* ── Frame callback ───────────────────────────────────────────────────── */

static void frame_done(void *data, struct wl_callback *cb, uint32_t time);

static const struct wl_callback_listener frame_listener = {
    .done = frame_done,
};

static void request_frame(void) {
    struct wl_callback *cb = wl_surface_frame(surface);
    wl_callback_add_listener(cb, &frame_listener, NULL);

    fill_buffer(frame_count);
    wl_surface_attach(surface, buffer, 0, 0);
    wl_surface_damage_buffer(surface, 0, 0, WIDTH, HEIGHT);
    wl_surface_commit(surface);
}

static void frame_done(void *data, struct wl_callback *cb, uint32_t time) {
    (void)data; (void)time;
    wl_callback_destroy(cb);
    frame_count++;

    if (frame_count >= MAX_FRAMES) {
        running = false;
        return;
    }
    request_frame();
}

/* ── XDG shell listeners ─────────────────────────────────────────────── */

static void xdg_surface_configure(void *data, struct xdg_surface *surf,
                                   uint32_t serial) {
    (void)data;
    xdg_surface_ack_configure(surf, serial);
    surface_configured = true;

    /* Submit the first frame after the initial configure. */
    if (frame_count == 0) {
        request_frame();
    }
}

static const struct xdg_surface_listener xdg_surface_listener = {
    .configure = xdg_surface_configure,
};

static void xdg_toplevel_configure(void *data, struct xdg_toplevel *tl,
                                    int32_t w, int32_t h,
                                    struct wl_array *states) {
    (void)data; (void)tl; (void)w; (void)h; (void)states;
}

static void xdg_toplevel_close(void *data, struct xdg_toplevel *tl) {
    (void)data; (void)tl;
    running = false;
}

static const struct xdg_toplevel_listener toplevel_listener = {
    .configure = xdg_toplevel_configure,
    .close     = xdg_toplevel_close,
};

static void wm_base_ping(void *data, struct xdg_wm_base *base,
                          uint32_t serial) {
    (void)data;
    xdg_wm_base_pong(base, serial);
}

static const struct xdg_wm_base_listener wm_base_listener = {
    .ping = wm_base_ping,
};

/* ── Registry listener ────────────────────────────────────────────────── */

static void registry_global(void *data, struct wl_registry *reg,
                             uint32_t name, const char *interface,
                             uint32_t version) {
    (void)data;
    if (strcmp(interface, wl_compositor_interface.name) == 0) {
        if (version < 4) {
            ERR("wl_compositor version %u < 4", version);
            return;
        }
        compositor = wl_registry_bind(reg, name,
                                      &wl_compositor_interface, 4);
    } else if (strcmp(interface, wl_shm_interface.name) == 0) {
        shm = wl_registry_bind(reg, name, &wl_shm_interface, 1);
    } else if (strcmp(interface, xdg_wm_base_interface.name) == 0) {
        if (version < 1) {
            ERR("xdg_wm_base version %u < 1", version);
            return;
        }
        wm_base = wl_registry_bind(reg, name,
                                    &xdg_wm_base_interface, 1);
        xdg_wm_base_add_listener(wm_base, &wm_base_listener, NULL);
    }
}

static void registry_global_remove(void *data, struct wl_registry *reg,
                                    uint32_t name) {
    (void)data; (void)reg; (void)name;
}

static const struct wl_registry_listener registry_listener = {
    .global        = registry_global,
    .global_remove = registry_global_remove,
};

/* ── Buffer creation ──────────────────────────────────────────────────── */

static int create_buffer(void) {
    int fd = create_shm_fd(BUF_SIZE);
    if (fd < 0) {
        ERR("failed to create shm fd: %s", strerror(errno));
        return -1;
    }

    shm_data = mmap(NULL, BUF_SIZE, PROT_READ | PROT_WRITE,
                     MAP_SHARED, fd, 0);
    if (shm_data == MAP_FAILED) {
        ERR("mmap failed: %s", strerror(errno));
        close(fd);
        return -1;
    }

    struct wl_shm_pool *pool = wl_shm_create_pool(shm, fd, BUF_SIZE);
    buffer = wl_shm_pool_create_buffer(pool, 0, WIDTH, HEIGHT, STRIDE,
                                        WL_SHM_FORMAT_XRGB8888);
    wl_shm_pool_destroy(pool);
    close(fd);  /* fd kept alive by the mmap + wl_shm_pool internally */
    return 0;
}

/* ── Main ─────────────────────────────────────────────────────────────── */

int main(void) {
    LOG("connecting to Wayland display...");

    display = wl_display_connect(NULL);
    if (!display) {
        ERR("cannot connect (is WAYLAND_DISPLAY set?)");
        return 1;
    }
    LOG("connected to display");

    registry = wl_display_get_registry(display);
    wl_registry_add_listener(registry, &registry_listener, NULL);
    wl_display_roundtrip(display);

    /* Validate required globals. */
    if (!compositor) { ERR("wl_compositor not found (need v4+)"); return 1; }
    if (!shm)        { ERR("wl_shm not found");                  return 1; }
    if (!wm_base)    { ERR("xdg_wm_base not found (need v1+)");  return 1; }
    LOG("globals bound: wl_compositor, wl_shm, xdg_wm_base");

    /* Create surface hierarchy. */
    surface  = wl_compositor_create_surface(compositor);
    xdg_surf = xdg_wm_base_get_xdg_surface(wm_base, surface);
    xdg_surface_add_listener(xdg_surf, &xdg_surface_listener, NULL);

    toplevel = xdg_surface_get_toplevel(xdg_surf);
    xdg_toplevel_add_listener(toplevel, &toplevel_listener, NULL);
    xdg_toplevel_set_title(toplevel, "Coder Test Client");
    wl_surface_commit(surface);  /* trigger initial configure */

    /* Create SHM buffer. */
    if (create_buffer() < 0) return 1;
    LOG("shm buffer created: %dx%d, format=XRGB8888, stride=%d",
        WIDTH, HEIGHT, STRIDE);

    /* Event loop — render MAX_FRAMES then exit. */
    uint64_t t_start = now_ms();

    while (running && wl_display_dispatch(display) != -1) {
        /* The frame callback drives rendering. */
    }

    uint64_t t_end = now_ms();
    double elapsed = (double)(t_end - t_start) / 1000.0;

    LOG("rendered %d/%d frames in %.3f s (%.1f fps)",
        frame_count, MAX_FRAMES, elapsed,
        elapsed > 0.0 ? (double)frame_count / elapsed : 0.0);

    /* Cleanup. */
    if (buffer)   wl_buffer_destroy(buffer);
    if (toplevel) xdg_toplevel_destroy(toplevel);
    if (xdg_surf) xdg_surface_destroy(xdg_surf);
    if (surface)  wl_surface_destroy(surface);
    if (wm_base)  xdg_wm_base_destroy(wm_base);
    if (shm)      wl_shm_destroy(shm);
    if (compositor) wl_compositor_destroy(compositor);
    wl_registry_destroy(registry);
    wl_display_disconnect(display);

    if (shm_data && shm_data != MAP_FAILED) {
        munmap(shm_data, BUF_SIZE);
    }

    LOG("exit OK");
    return (frame_count >= MAX_FRAMES) ? 0 : 1;
}
