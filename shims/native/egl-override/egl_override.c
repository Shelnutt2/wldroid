/*
 * libEGL.so.1 wrapper — mode-aware EGL interception for proot GPU rendering.
 *
 * ANGLE loads Mesa's EGL via dlopen("libEGL.so.1"). This wrapper sits at
 * /opt/wldroid/lib/libEGL.so.1 (first on LD_LIBRARY_PATH), so ANGLE finds
 * us first. We load the real Mesa EGL and pass through all functions, with
 * mode-dependent behavior controlled by WLDROID_GPU_MODE:
 *
 * SOFTWARE MODE (default, WLDROID_GPU_MODE unset or SOFTWARE):
 *   1. eglGetPlatformDisplay(GBM, ...) → redirect to SURFACELESS_MESA.
 *   2. eglCreateWindowSurface → pbuffer + wl_shm + xdg_toplevel.
 *   3. eglSwapBuffers → glReadPixels → row-flip → wl_shm → wl_surface.commit.
 *   Pixel transport: GPU render → glReadPixels → row-flip → wl_shm → commit.
 *
 * GPU MODE (WLDROID_GPU_MODE = VIRGL_GLES|VIRGL_ZINK|TURNIP_DIRECT|VENUS):
 *   All EGL calls pass through to real Mesa unchanged (GBM platform).
 *   Chromium/ANGLE uses GbmSurfacelessWayland with FBOs + zwp_linux_dmabuf_v1.
 *   An xdg_toplevel is still created for compositor touch redirect (app_id
 *   "com.coder.vscode"), but no SHM buffers or pbuffers are used.
 *
 * Build: aarch64-linux-gnu-gcc -shared -fPIC -O2 -Wl,-soname,libEGL.so.1 -o OUT SRC -ldl
 * No wayland/EGL/GL headers needed — all loaded via dlopen/dlsym.
 */
#define _GNU_SOURCE
#include <dlfcn.h>
#include <unistd.h>
#include <string.h>
#include <stdint.h>
#include <stdarg.h>
#include <sys/mman.h>
#include <stdlib.h>
#include <fcntl.h>

/* ========================================================================
 * EGL types (no EGL headers needed)
 * ======================================================================== */
typedef void *EGLDisplay;
typedef unsigned int EGLenum;
typedef unsigned int EGLBoolean;
typedef intptr_t EGLAttrib;
typedef int EGLint;
typedef void *EGLConfig;
typedef void (*EGLFuncPtr)(void);
typedef void *EGLSurface;
typedef void *EGLNativeWindowType;

#define EGL_DEFAULT_DISPLAY       ((void *)0)
#define EGL_NO_DISPLAY            ((EGLDisplay)0)
#define EGL_PLATFORM_GBM_KHR     0x31D7
#define EGL_PLATFORM_SURFACELESS_MESA 0x31DD
#define EGL_TRUE                  1
#define EGL_FALSE                 0
#define EGL_NO_SURFACE            ((void *)0)
#define EGL_WIDTH                 0x3057
#define EGL_HEIGHT                0x3058
#define EGL_SURFACE_TYPE           0x3033
#define EGL_WINDOW_BIT             0x0004
#define EGL_PBUFFER_BIT            0x0001
#define EGL_NONE                  0x3038

/* GL constants */
#define GL_RGBA                   0x1908
#define GL_UNSIGNED_BYTE          0x1401

/* wl_shm format — matches GL_RGBA byte order on little-endian */
#define WL_SHM_FORMAT_ABGR8888   0x34324241

/* ========================================================================
 * Wayland protocol constants
 * ======================================================================== */
#define WL_DISPLAY_GET_REGISTRY         1
#define WL_REGISTRY_BIND                0
#define WL_COMPOSITOR_CREATE_SURFACE    0
#define WL_SURFACE_DESTROY              0
#define WL_SURFACE_ATTACH               1
#define WL_SURFACE_DAMAGE_BUFFER        9
#define WL_SURFACE_COMMIT               6

/* xdg_shell protocol opcodes */
#define XDG_WM_BASE_DESTROY             0
#define XDG_WM_BASE_GET_XDG_SURFACE    2
#define XDG_WM_BASE_PONG               3
#define XDG_SURFACE_DESTROY             0
#define XDG_SURFACE_GET_TOPLEVEL        1
#define XDG_SURFACE_ACK_CONFIGURE       4

/* xdg_toplevel opcodes */
#define XDG_TOPLEVEL_DESTROY            0
#define XDG_TOPLEVEL_SET_TITLE          2
#define XDG_TOPLEVEL_SET_APP_ID         3

/* wl_shm / wl_shm_pool / wl_buffer opcodes */
#define WL_SHM_CREATE_POOL             0
#define WL_SHM_POOL_CREATE_BUFFER      0
#define WL_SHM_POOL_DESTROY            2
#define WL_BUFFER_DESTROY              0

/* ========================================================================
 * Logging (no stdio — avoid GLIBC_2.38 fprintf)
 * ======================================================================== */
#define LOG(msg) do { \
    ssize_t __attribute__((unused)) _r = write(STDERR_FILENO, msg, sizeof(msg) - 1); \
} while(0)

/* Variable-length log helper */
static void log_str(const char *s) {
    if (s) {
        ssize_t __attribute__((unused)) _r = write(STDERR_FILENO, s, strlen(s));
    }
}

/* Integer to string helper */
static void log_int(int n) {
    char buf[16];
    int i = 0;
    if (n < 0) { buf[i++] = '-'; n = -n; }
    if (n == 0) { buf[i++] = '0'; }
    else {
        char tmp[16]; int j = 0;
        while (n > 0) { tmp[j++] = '0' + (n % 10); n /= 10; }
        while (j > 0) buf[i++] = tmp[--j];
    }
    buf[i] = '\0';
    log_str(buf);
}

/* ========================================================================
 * Minimal xdg_shell protocol interface stubs
 *
 * Since we have no wayland-protocols headers, we define the wl_interface
 * and wl_message structs matching libwayland's ABI exactly, and provide
 * the three xdg_shell interfaces (xdg_wm_base, xdg_surface, xdg_toplevel)
 * with correct method/event signatures so the marshaller works.
 * ======================================================================== */

struct wl_message_stub {
    const char *name;
    const char *signature;
    const void **types;
};

struct wl_interface_stub {
    const char *name;
    int version;
    int method_count;
    const struct wl_message_stub *methods;
    int event_count;
    const struct wl_message_stub *events;
};

static const void *null_types[] = { NULL, NULL, NULL, NULL, NULL };

/* --- xdg_wm_base: 4 methods, 1 event --- */
static const struct wl_message_stub xdg_wm_base_methods[] = {
    { "destroy",           "",   null_types },  /* opcode 0 */
    { "create_positioner", "n",  null_types },  /* opcode 1 */
    { "get_xdg_surface",  "no", null_types },   /* opcode 2 */
    { "pong",             "u",  null_types },   /* opcode 3 */
};
static const struct wl_message_stub xdg_wm_base_events[] = {
    { "ping", "u", null_types },                /* event 0 */
};
static const struct wl_interface_stub xdg_wm_base_iface = {
    "xdg_wm_base", 2,
    4, xdg_wm_base_methods,
    1, xdg_wm_base_events
};

/* --- xdg_surface: 5 methods, 1 event --- */
static const struct wl_message_stub xdg_surface_methods[] = {
    { "destroy",              "",     null_types },  /* opcode 0 */
    { "get_toplevel",         "n",    null_types },  /* opcode 1 */
    { "get_popup",            "noo",  null_types },  /* opcode 2 */
    { "set_window_geometry",  "iiii", null_types },  /* opcode 3 */
    { "ack_configure",        "u",    null_types },  /* opcode 4 */
};
static const struct wl_message_stub xdg_surface_events[] = {
    { "configure", "u", null_types },                /* event 0 */
};
static const struct wl_interface_stub xdg_surface_iface = {
    "xdg_surface", 2,
    5, xdg_surface_methods,
    1, xdg_surface_events
};

/* --- xdg_toplevel: 14 methods, 2 events --- */
static const struct wl_message_stub xdg_toplevel_methods[] = {
    { "destroy",          "",     null_types },  /* opcode 0 */
    { "set_parent",       "?o",   null_types },  /* opcode 1 */
    { "set_title",        "s",    null_types },  /* opcode 2 */
    { "set_app_id",       "s",    null_types },  /* opcode 3 */
    { "show_window_menu", "ouii", null_types },  /* opcode 4 */
    { "move",             "ou",   null_types },  /* opcode 5 */
    { "resize",           "ouu",  null_types },  /* opcode 6 */
    { "set_max_size",     "ii",   null_types },  /* opcode 7 */
    { "set_min_size",     "ii",   null_types },  /* opcode 8 */
    { "set_maximized",    "",     null_types },  /* opcode 9 */
    { "unset_maximized",  "",     null_types },  /* opcode 10 */
    { "set_fullscreen",   "?o",   null_types },  /* opcode 11 */
    { "unset_fullscreen", "",     null_types },  /* opcode 12 */
    { "set_minimized",    "",     null_types },  /* opcode 13 */
};
static const struct wl_message_stub xdg_toplevel_events[] = {
    { "configure", "iia", null_types },          /* event 0 */
    { "close",     "",    null_types },          /* event 1 */
};
static const struct wl_interface_stub xdg_toplevel_iface = {
    "xdg_toplevel", 2,
    14, xdg_toplevel_methods,
    2, xdg_toplevel_events
};

/* ========================================================================
 * Real Mesa EGL function pointers
 * ======================================================================== */
static void *real_egl = NULL;

typedef EGLFuncPtr (*pfn_eglGetProcAddress)(const char *);
typedef EGLDisplay (*pfn_eglGetPlatformDisplay)(EGLenum, void*, const EGLAttrib*);
typedef EGLDisplay (*pfn_eglGetPlatformDisplayEXT)(EGLenum, void*, const EGLint*);
typedef EGLDisplay (*pfn_eglGetDisplay)(void *);
typedef EGLBoolean (*pfn_eglChooseConfig)(EGLDisplay, const EGLint *, EGLConfig *, EGLint, EGLint *);
typedef EGLBoolean (*pfn_eglGetConfigAttrib)(EGLDisplay, EGLConfig, EGLint, EGLint *);
typedef EGLSurface (*pfn_eglCreateWindowSurface)(EGLDisplay, EGLConfig, EGLNativeWindowType, const EGLint *);
typedef EGLSurface (*pfn_eglCreatePlatformWindowSurface)(EGLDisplay, EGLConfig, void *, const EGLAttrib *);
typedef EGLSurface (*pfn_eglCreatePlatformWindowSurfaceEXT)(EGLDisplay, EGLConfig, void *, const EGLint *);
typedef EGLSurface (*pfn_eglCreatePbufferSurface)(EGLDisplay, EGLConfig, const EGLint *);
typedef EGLBoolean (*pfn_eglDestroySurface)(EGLDisplay, EGLSurface);
typedef EGLBoolean (*pfn_eglQuerySurface)(EGLDisplay, EGLSurface, EGLint, EGLint *);

static pfn_eglGetProcAddress                 real_getProcAddress = NULL;
static pfn_eglGetPlatformDisplay             real_getPlatformDisplay = NULL;
static pfn_eglGetPlatformDisplayEXT          real_getPlatformDisplayEXT = NULL;
static pfn_eglGetDisplay                     real_eglGetDisplay = NULL;
static pfn_eglChooseConfig                   real_eglChooseConfig = NULL;
static pfn_eglGetConfigAttrib                real_eglGetConfigAttrib = NULL;
static pfn_eglCreateWindowSurface            real_eglCreateWindowSurface = NULL;
static pfn_eglCreatePlatformWindowSurface    real_eglCreatePlatformWindowSurface = NULL;
static pfn_eglCreatePlatformWindowSurfaceEXT real_eglCreatePlatformWindowSurfaceEXT = NULL;
static pfn_eglCreatePbufferSurface           real_eglCreatePbufferSurface = NULL;
static pfn_eglDestroySurface                 real_eglDestroySurface = NULL;
static pfn_eglQuerySurface                   real_eglQuerySurface = NULL;

/* --- Diagnostic intercepts --- */
typedef EGLBoolean (*pfn_eglSwapBuffers)(EGLDisplay, EGLSurface);
typedef EGLBoolean (*pfn_eglMakeCurrent)(EGLDisplay, EGLSurface, EGLSurface, void *);
typedef EGLBoolean (*pfn_eglInitialize)(EGLDisplay, EGLint *, EGLint *);
typedef void *(*pfn_eglCreateContext)(EGLDisplay, EGLConfig, void *, const EGLint *);
typedef EGLint (*pfn_eglGetError)(void);

static pfn_eglSwapBuffers    real_eglSwapBuffers = NULL;
static pfn_eglMakeCurrent    real_eglMakeCurrent = NULL;
static pfn_eglInitialize     real_eglInitialize = NULL;
static pfn_eglCreateContext  real_eglCreateContext = NULL;
static pfn_eglGetError       real_eglGetError = NULL;

/* Diagnostic counters */
static int suppress_bad_surface = 0;
static int make_current_count = 0;

/* GPU mode: 0 = software (readback mode), 1 = GPU (GBM passthrough mode) */
static int gpu_mode = 0;
static int egl_init_failed_once = 0;  /* Track GBM eglInitialize failure for surfaceless fallback detection */
/* --- eglQueryString intercept (strip unsupported extensions in GPU mode) --- */
typedef const char *(*pfn_eglQueryString)(EGLDisplay, EGLint);
static pfn_eglQueryString real_eglQueryString = NULL;
static char filtered_extensions[8192];
static int extensions_filtered = 0;



/* GBM device recreation: Mesa's libgbm function pointers and state */
typedef void *(*pfn_gbm_create_device)(int fd);
typedef void (*pfn_gbm_device_destroy)(void *gbm);

static pfn_gbm_create_device  real_gbm_create_device = NULL;
static pfn_gbm_device_destroy real_gbm_device_destroy = NULL;
static void *mesa_gbm_device = NULL;  /* Our Mesa-created GBM device */


/* ========================================================================
 * GL function pointers (loaded lazily via eglGetProcAddress)
 * ======================================================================== */
typedef void (*pfn_glFinish)(void);
typedef void (*pfn_glReadPixels)(int x, int y, int w, int h, unsigned fmt, unsigned type, void *data);

static pfn_glFinish     real_glFinish = NULL;
static pfn_glReadPixels real_glReadPixels = NULL;
static int gl_funcs_loaded = 0;

static void ensure_gl_funcs(void) {
    if (gl_funcs_loaded) return;
    if (!real_getProcAddress) return;
    real_glFinish = (pfn_glFinish)real_getProcAddress("glFinish");
    real_glReadPixels = (pfn_glReadPixels)real_getProcAddress("glReadPixels");
    gl_funcs_loaded = 1;
    LOG("[egl-wrapper] Loaded GL functions (glFinish, glReadPixels)\n");
}

/* ========================================================================
 * Wayland function pointers (loaded via dlopen at runtime)
 * ======================================================================== */
typedef void *(*pfn_wl_display_connect)(const char *);
typedef void  (*pfn_wl_display_disconnect)(void *);
typedef int   (*pfn_wl_display_roundtrip)(void *);
typedef int   (*pfn_wl_display_dispatch)(void *);
typedef int   (*pfn_wl_display_flush)(void *);
typedef void *(*pfn_wl_proxy_marshal_flags)(void *, uint32_t, const void *, uint32_t, uint32_t, ...);
typedef int   (*pfn_wl_proxy_add_listener)(void *, void *, void *);
typedef uint32_t (*pfn_wl_proxy_get_version)(void *);

static pfn_wl_display_connect      wl_connect = NULL;
static pfn_wl_display_disconnect   wl_disconnect = NULL;
static pfn_wl_display_roundtrip    wl_roundtrip = NULL;
static pfn_wl_display_dispatch     wl_dispatch = NULL;
static pfn_wl_display_flush        wl_flush = NULL;
static pfn_wl_proxy_marshal_flags  wl_marshal_flags = NULL;
static pfn_wl_proxy_add_listener   wl_add_listener = NULL;
static pfn_wl_proxy_get_version    wl_get_version = NULL;

/* Wayland interface globals (resolved via dlsym) */
static const void *wl_registry_iface = NULL;   /* &wl_registry_interface */
static const void *wl_compositor_iface = NULL;  /* &wl_compositor_interface */
static const void *wl_surface_iface = NULL;     /* &wl_surface_interface */
static const void *wl_shm_iface = NULL;         /* &wl_shm_interface */
static const void *wl_shm_pool_iface = NULL;    /* &wl_shm_pool_interface */
static const void *wl_buffer_iface = NULL;      /* &wl_buffer_interface */

/* ========================================================================
 * Wrapper Wayland state — our own connection to the compositor
 * ======================================================================== */
static void *wrapper_wl_display = NULL;
static void *wrapper_wl_compositor = NULL;
static void *wrapper_xdg_wm_base = NULL;
static void *wrapper_wl_shm = NULL;
static int wayland_ready = 0;

/* Default size for pbuffer / wl_shm — ANGLE/Ozone will resize as needed */
#define DEFAULT_WIDTH  1080
#define DEFAULT_HEIGHT 2400

/* ========================================================================
 * SHM window tracking — replaces old wl_egl_window tracking
 * ======================================================================== */
#define MAX_SHM_WINDOWS 8

struct shm_window {
    void *wl_surface;
    void *xdg_surface;
    void *xdg_toplevel;

    /* SHM double buffer */
    void *wl_buffer[2];
    uint8_t *shm_data;        /* mmap'd region (2 frames) */
    int shm_fd;
    int current_buf;           /* 0 or 1 */
    int buf_busy[2];           /* cleared by wl_buffer.release */

    /* Readback temp buffer (for row-flip) */
    uint8_t *readback_buf;
    size_t readback_buf_size;

    /* Associated pbuffer */
    void *pbuffer_surface;     /* EGLSurface */

    /* EGL config used to create the pbuffer (needed for resize) */
    void *egl_config;
    void *egl_display;

    /* Size */
    int width, height;
    int pending_width, pending_height;
    int needs_resize;

    size_t shm_total_size;
};

static struct shm_window shm_windows[MAX_SHM_WINDOWS];
static int shm_window_count = 0;

/* Find shm_window by pbuffer surface */
static struct shm_window *find_shm_window(EGLSurface surface) {
    for (int i = 0; i < shm_window_count; i++) {
        if (shm_windows[i].pbuffer_surface == surface)
            return &shm_windows[i];
    }
    return NULL;
}

/* ========================================================================
 * wl_buffer release listener
 * ======================================================================== */
static void buffer_handle_release(void *data, void *buffer) {
    struct shm_window *win = (struct shm_window *)data;
    for (int i = 0; i < 2; i++) {
        if (win->wl_buffer[i] == buffer) {
            win->buf_busy[i] = 0;
            break;
        }
    }
}

static struct {
    void (*release)(void *, void *);
} wl_buffer_listener = { buffer_handle_release };

/* ========================================================================
 * SHM buffer management
 * ======================================================================== */

/* memfd_create wrapper — uses syscall if libc wrapper unavailable */
static int create_memfd(const char *name) {
    #ifndef __NR_memfd_create
    #ifdef __aarch64__
    #define __NR_memfd_create 279
    #else
    #define __NR_memfd_create 319
    #endif
    #endif
    /* Try libc memfd_create first, fall back to syscall */
    int fd = syscall(__NR_memfd_create, name, 1 /* MFD_CLOEXEC */);
    return fd;
}

static int create_shm_buffers(struct shm_window *win, int w, int h) {
    int stride = w * 4;  /* RGBA = 4 bytes/pixel */
    size_t buf_size = (size_t)stride * h;
    size_t total_size = buf_size * 2;  /* double buffer */

    /* Create shared memory fd */
    int fd = create_memfd("egl_shm");
    if (fd < 0) {
        LOG("[egl-wrapper] ERROR: memfd_create failed\n");
        return 0;
    }
    if (ftruncate(fd, (off_t)total_size) < 0) {
        LOG("[egl-wrapper] ERROR: ftruncate failed\n");
        close(fd);
        return 0;
    }

    /* mmap the shared memory */
    uint8_t *data = (uint8_t *)mmap(NULL, total_size, PROT_READ | PROT_WRITE,
                                     MAP_SHARED, fd, 0);
    if (data == MAP_FAILED) {
        LOG("[egl-wrapper] ERROR: mmap failed for shm\n");
        close(fd);
        return 0;
    }

    /* Create wl_shm_pool */
    if (!wrapper_wl_shm || !wl_shm_pool_iface) {
        LOG("[egl-wrapper] ERROR: wl_shm not available\n");
        munmap(data, total_size);
        close(fd);
        return 0;
    }

    uint32_t shm_ver = wl_get_version(wrapper_wl_shm);
    void *pool = wl_marshal_flags(
        wrapper_wl_shm, WL_SHM_CREATE_POOL,
        wl_shm_pool_iface, shm_ver, 0,
        NULL, fd, (int32_t)total_size);
    if (!pool) {
        LOG("[egl-wrapper] ERROR: wl_shm_create_pool failed\n");
        munmap(data, total_size);
        close(fd);
        return 0;
    }

    /* Create two wl_buffers from the pool */
    uint32_t pool_ver = wl_get_version(pool);
    for (int i = 0; i < 2; i++) {
        int32_t offset = (int32_t)(i * buf_size);
        win->wl_buffer[i] = wl_marshal_flags(
            pool, WL_SHM_POOL_CREATE_BUFFER,
            wl_buffer_iface, pool_ver, 0,
            NULL, offset, w, h, stride, (uint32_t)WL_SHM_FORMAT_ABGR8888);
        if (!win->wl_buffer[i]) {
            LOG("[egl-wrapper] ERROR: wl_shm_pool_create_buffer failed\n");
            /* cleanup partial */
            if (i > 0 && win->wl_buffer[0]) {
                wl_marshal_flags(win->wl_buffer[0], WL_BUFFER_DESTROY, NULL,
                                 wl_get_version(win->wl_buffer[0]), 0);
            }
            wl_marshal_flags(pool, WL_SHM_POOL_DESTROY, NULL, pool_ver, 0);
            munmap(data, total_size);
            close(fd);
            return 0;
        }
        /* Attach release listener */
        wl_add_listener(win->wl_buffer[i], (void *)&wl_buffer_listener, win);
        win->buf_busy[i] = 0;
    }

    /* Destroy pool — no longer needed after buffer creation */
    wl_marshal_flags(pool, WL_SHM_POOL_DESTROY, NULL, pool_ver, 0);

    /* Allocate readback buffer for row-flip */
    uint8_t *readback = (uint8_t *)mmap(NULL, buf_size, PROT_READ | PROT_WRITE,
                                         MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (readback == MAP_FAILED) {
        LOG("[egl-wrapper] ERROR: mmap failed for readback buffer\n");
        for (int i = 0; i < 2; i++) {
            wl_marshal_flags(win->wl_buffer[i], WL_BUFFER_DESTROY, NULL,
                             wl_get_version(win->wl_buffer[i]), 0);
        }
        munmap(data, total_size);
        close(fd);
        return 0;
    }

    win->shm_data = data;
    win->shm_fd = fd;
    win->shm_total_size = total_size;
    win->readback_buf = readback;
    win->readback_buf_size = buf_size;
    win->current_buf = 0;

    LOG("[egl-wrapper] Created SHM double buffers (");
    log_int(w);
    LOG("x");
    log_int(h);
    LOG(")\n");

    return 1;
}

static void destroy_shm_buffers(struct shm_window *win) {
    for (int i = 0; i < 2; i++) {
        if (win->wl_buffer[i]) {
            wl_marshal_flags(win->wl_buffer[i], WL_BUFFER_DESTROY, NULL,
                             wl_get_version(win->wl_buffer[i]), 0);
            win->wl_buffer[i] = NULL;
        }
    }
    if (win->shm_data && win->shm_data != MAP_FAILED) {
        munmap(win->shm_data, win->shm_total_size);
        win->shm_data = NULL;
    }
    if (win->readback_buf && win->readback_buf != MAP_FAILED) {
        munmap(win->readback_buf, win->readback_buf_size);
        win->readback_buf = NULL;
    }
    if (win->shm_fd >= 0) {
        close(win->shm_fd);
        win->shm_fd = -1;
    }
}

/* ========================================================================
 * xdg_shell event handlers
 * ======================================================================== */

/* xdg_wm_base ping → pong (keep-alive) */
static void xdg_wm_base_handle_ping(void *data, void *xdg_wm_base_proxy,
                                      uint32_t serial) {
    (void)data;
    if (wl_marshal_flags && wl_get_version) {
        wl_marshal_flags(xdg_wm_base_proxy, XDG_WM_BASE_PONG, NULL,
                         wl_get_version(xdg_wm_base_proxy), 0, serial);
        LOG("[egl-wrapper] xdg_wm_base: pong\n");
    }
}
static struct {
    void (*ping)(void *, void *, uint32_t);
} xdg_wm_base_listener = { xdg_wm_base_handle_ping };

/* xdg_surface configure → ack_configure + commit */
static void xdg_surface_handle_configure(void *data, void *xdg_surface_proxy,
                                          uint32_t serial) {
    /* ack_configure */
    if (wl_marshal_flags && wl_get_version) {
        wl_marshal_flags(xdg_surface_proxy, XDG_SURFACE_ACK_CONFIGURE, NULL,
                         wl_get_version(xdg_surface_proxy), 0, serial);
    }
    /* commit the wl_surface — find shm_window by xdg_surface */
    struct shm_window *win = (struct shm_window *)data;
    if (win && win->wl_surface && wl_marshal_flags && wl_get_version) {
        wl_marshal_flags(win->wl_surface, WL_SURFACE_COMMIT, NULL,
                         wl_get_version(win->wl_surface), 0);
    }
    LOG("[egl-wrapper] xdg_surface: ack_configure + commit\n");
}
static struct {
    void (*configure)(void *, void *, uint32_t);
} xdg_surface_listener = { xdg_surface_handle_configure };

/* xdg_toplevel configure → set pending resize */
static void xdg_toplevel_handle_configure(void *data, void *toplevel,
                                           int32_t width, int32_t height,
                                           void *states) {
    (void)toplevel; (void)states;
    struct shm_window *win = (struct shm_window *)data;
    if (win && width > 0 && height > 0) {
        if (width != win->width || height != win->height) {
            win->pending_width = width;
            win->pending_height = height;
            win->needs_resize = 1;
            LOG("[egl-wrapper] xdg_toplevel configure: pending resize ");
            log_int(width);
            LOG("x");
            log_int(height);
            LOG("\n");
        }
    }
}
static void xdg_toplevel_handle_close(void *data, void *toplevel) {
    (void)data; (void)toplevel;
    LOG("[egl-wrapper] xdg_toplevel close\n");
}
static struct {
    void (*configure)(void *, void *, int32_t, int32_t, void *);
    void (*close)(void *, void *);
} xdg_toplevel_listener = { xdg_toplevel_handle_configure, xdg_toplevel_handle_close };

/* ========================================================================
 * Registry listener — discover wl_compositor + xdg_wm_base + wl_shm
 * ======================================================================== */

/* wl_interface has const char *name as first field */
static const char *iface_name(const void *iface) {
    if (!iface) return "";
    return *(const char **)iface;
}

static void registry_global(void *data, void *registry, uint32_t name,
                            const char *interface, uint32_t version) {
    (void)data;
    if (strcmp(interface, "wl_compositor") == 0 && wl_marshal_flags && wl_compositor_iface) {
        uint32_t bind_ver = version < 4 ? version : 4;
        log_str("[egl-wrapper] Found wl_compositor v");
        char vbuf[4] = { '0' + (bind_ver % 10), '\n', 0 };
        log_str(vbuf);

        wrapper_wl_compositor = wl_marshal_flags(
            registry, WL_REGISTRY_BIND,
            wl_compositor_iface, bind_ver, 0,
            name, iface_name(wl_compositor_iface), bind_ver, NULL);

        if (wrapper_wl_compositor) {
            LOG("[egl-wrapper] Bound wl_compositor\n");
        } else {
            LOG("[egl-wrapper] ERROR: Failed to bind wl_compositor\n");
        }
    }
    else if (strcmp(interface, "xdg_wm_base") == 0 && wl_marshal_flags) {
        uint32_t bind_ver = version < 2 ? version : 2;
        log_str("[egl-wrapper] Found xdg_wm_base v");
        char vbuf[4] = { '0' + (bind_ver % 10), '\n', 0 };
        log_str(vbuf);

        wrapper_xdg_wm_base = wl_marshal_flags(
            registry, WL_REGISTRY_BIND,
            (const void *)&xdg_wm_base_iface, bind_ver, 0,
            name, "xdg_wm_base", bind_ver, NULL);

        if (wrapper_xdg_wm_base) {
            /* Add ping listener for keep-alive */
            wl_add_listener(wrapper_xdg_wm_base, (void *)&xdg_wm_base_listener, NULL);
            LOG("[egl-wrapper] Bound xdg_wm_base\n");
        } else {
            LOG("[egl-wrapper] ERROR: Failed to bind xdg_wm_base\n");
        }
    }
    else if (strcmp(interface, "wl_shm") == 0 && wl_marshal_flags && wl_shm_iface) {
        uint32_t bind_ver = version < 1 ? version : 1;
        log_str("[egl-wrapper] Found wl_shm v");
        char vbuf[4] = { '0' + (bind_ver % 10), '\n', 0 };
        log_str(vbuf);

        wrapper_wl_shm = wl_marshal_flags(
            registry, WL_REGISTRY_BIND,
            wl_shm_iface, bind_ver, 0,
            name, iface_name(wl_shm_iface), bind_ver, NULL);

        if (wrapper_wl_shm) {
            LOG("[egl-wrapper] Bound wl_shm\n");
        } else {
            LOG("[egl-wrapper] ERROR: Failed to bind wl_shm\n");
        }
    }
}

static void registry_global_remove(void *data, void *registry, uint32_t name) {
    (void)data; (void)registry; (void)name;
}

/* Registry listener struct — must match wayland ABI */
static struct {
    void (*global)(void *, void *, uint32_t, const char *, uint32_t);
    void (*global_remove)(void *, void *, uint32_t);
} registry_listener = {
    .global = registry_global,
    .global_remove = registry_global_remove,
};

/* ========================================================================
 * Initialize Wayland connection
 * ======================================================================== */
static int init_wayland_connection(void) {
    if (wayland_ready) return 1;
    if (!wl_connect || !wl_roundtrip || !wl_marshal_flags ||
        !wl_add_listener || !wl_get_version || !wl_registry_iface) {
        LOG("[egl-wrapper] Wayland functions not loaded, cannot init connection\n");
        return 0;
    }

    wrapper_wl_display = wl_connect(NULL);
    if (!wrapper_wl_display) {
        LOG("[egl-wrapper] ERROR: wl_display_connect(NULL) failed\n");
        return 0;
    }
    LOG("[egl-wrapper] Connected to Wayland compositor (own connection)\n");

    uint32_t disp_ver = wl_get_version(wrapper_wl_display);
    void *registry = wl_marshal_flags(
        wrapper_wl_display, WL_DISPLAY_GET_REGISTRY,
        wl_registry_iface, disp_ver, 0, NULL);
    if (!registry) {
        LOG("[egl-wrapper] ERROR: Failed to get wl_registry\n");
        return 0;
    }
    LOG("[egl-wrapper] Got wl_registry\n");

    /* wl_registry_add_listener */
    wl_add_listener(registry, (void *)&registry_listener, NULL);

    /* Roundtrip to receive global advertisements */
    wl_roundtrip(wrapper_wl_display);

    if (!wrapper_wl_compositor) {
        LOG("[egl-wrapper] ERROR: wl_compositor not found after roundtrip\n");
        return 0;
    }

    if (!wrapper_xdg_wm_base) {
        LOG("[egl-wrapper] ERROR: xdg_wm_base not found after roundtrip\n");
        return 0;
    }

    if (!gpu_mode && !wrapper_wl_shm) {
        LOG("[egl-wrapper] ERROR: wl_shm not found after roundtrip\n");
        return 0;
    }

    wayland_ready = 1;
    if (gpu_mode) {
        LOG("[egl-wrapper] Wayland connection initialized (compositor + xdg_wm_base)\n");
    } else {
        LOG("[egl-wrapper] Wayland connection initialized (compositor + xdg_wm_base + wl_shm)\n");
    }
    return 1;
}

/* ========================================================================
 * Create xdg_toplevel for touch redirect only (GPU mode — no SHM/pbuffer)
 * ======================================================================== */
static int touch_redirect_created = 0;
static void *touch_redirect_surface = NULL;
static void *touch_redirect_xdg_surface = NULL;
static void *touch_redirect_toplevel = NULL;

/* Minimal xdg_surface configure handler for touch redirect (just ack) */
static void touch_xdg_surface_handle_configure(void *data, void *xdg_surface_proxy,
                                                 uint32_t serial) {
    (void)data;
    if (wl_marshal_flags && wl_get_version) {
        wl_marshal_flags(xdg_surface_proxy, XDG_SURFACE_ACK_CONFIGURE, NULL,
                         wl_get_version(xdg_surface_proxy), 0, serial);
    }
    /* Commit the surface */
    if (touch_redirect_surface && wl_marshal_flags && wl_get_version) {
        wl_marshal_flags(touch_redirect_surface, WL_SURFACE_COMMIT, NULL,
                         wl_get_version(touch_redirect_surface), 0);
    }
    LOG("[egl-wrapper] touch redirect: ack_configure + commit\n");
}
static struct {
    void (*configure)(void *, void *, uint32_t);
} touch_xdg_surface_listener = { touch_xdg_surface_handle_configure };

/* Minimal xdg_toplevel handlers (no resize needed for touch redirect) */
static void touch_xdg_toplevel_handle_configure(void *data, void *toplevel,
                                                  int32_t width, int32_t height,
                                                  void *states) {
    (void)data; (void)toplevel; (void)width; (void)height; (void)states;
    /* No-op: touch redirect surface has no buffer, no resize logic needed */
}
static void touch_xdg_toplevel_handle_close(void *data, void *toplevel) {
    (void)data; (void)toplevel;
    LOG("[egl-wrapper] touch redirect: close\n");
}
static struct {
    void (*configure)(void *, void *, int32_t, int32_t, void *);
    void (*close)(void *, void *);
} touch_xdg_toplevel_listener = { touch_xdg_toplevel_handle_configure, touch_xdg_toplevel_handle_close };

static void create_touch_redirect_toplevel(void) {
    if (touch_redirect_created) return;
    if (!wayland_ready || !wrapper_wl_compositor || !wrapper_xdg_wm_base) {
        LOG("[egl-wrapper] Cannot create touch redirect: wayland not ready\n");
        return;
    }

    /* 1. Create wl_surface */
    uint32_t comp_ver = wl_get_version(wrapper_wl_compositor);
    touch_redirect_surface = wl_marshal_flags(
        wrapper_wl_compositor, WL_COMPOSITOR_CREATE_SURFACE,
        wl_surface_iface, comp_ver, 0, NULL);
    if (!touch_redirect_surface) {
        LOG("[egl-wrapper] ERROR: touch redirect wl_surface creation failed\n");
        return;
    }
    LOG("[egl-wrapper] touch redirect: created wl_surface\n");

    /* 2. Create xdg_surface */
    uint32_t xdg_ver = wl_get_version(wrapper_xdg_wm_base);
    touch_redirect_xdg_surface = wl_marshal_flags(
        wrapper_xdg_wm_base, XDG_WM_BASE_GET_XDG_SURFACE,
        (const void *)&xdg_surface_iface, xdg_ver, 0,
        NULL, touch_redirect_surface);
    if (!touch_redirect_xdg_surface) {
        LOG("[egl-wrapper] ERROR: touch redirect xdg_surface creation failed\n");
        return;
    }
    wl_add_listener(touch_redirect_xdg_surface, (void *)&touch_xdg_surface_listener, NULL);
    LOG("[egl-wrapper] touch redirect: created xdg_surface\n");

    /* 3. Create xdg_toplevel */
    touch_redirect_toplevel = wl_marshal_flags(
        touch_redirect_xdg_surface, XDG_SURFACE_GET_TOPLEVEL,
        (const void *)&xdg_toplevel_iface, wl_get_version(touch_redirect_xdg_surface), 0,
        NULL);
    if (!touch_redirect_toplevel) {
        LOG("[egl-wrapper] ERROR: touch redirect xdg_toplevel creation failed\n");
        return;
    }
    LOG("[egl-wrapper] touch redirect: created xdg_toplevel\n");

    /* 4. Set title and app_id for compositor identification */
    wl_marshal_flags(touch_redirect_toplevel, XDG_TOPLEVEL_SET_TITLE, NULL, wl_get_version(touch_redirect_toplevel), 0, "VS Code");
    wl_marshal_flags(touch_redirect_toplevel, XDG_TOPLEVEL_SET_APP_ID, NULL, wl_get_version(touch_redirect_toplevel), 0, "com.coder.vscode");

    /* 5. Add toplevel listener */
    wl_add_listener(touch_redirect_toplevel, (void *)&touch_xdg_toplevel_listener, NULL);

    /* 6. Initial commit (required by xdg_shell before configure) */
    wl_marshal_flags(touch_redirect_surface, WL_SURFACE_COMMIT, NULL,
                     wl_get_version(touch_redirect_surface), 0);

    /* 7. Roundtrip to get configure event */
    wl_roundtrip(wrapper_wl_display);

    touch_redirect_created = 1;
    LOG("[egl-wrapper] touch redirect: toplevel setup complete\n");
}

/* ========================================================================
 * Create wl_surface + xdg_toplevel + SHM buffers + pbuffer
 * ======================================================================== */
static struct shm_window *create_shm_window(EGLDisplay dpy, EGLConfig config) {
    if (!wayland_ready || !wrapper_wl_compositor || !wrapper_xdg_wm_base) {
        LOG("[egl-wrapper] Cannot create shm_window: wayland not ready\n");
        return NULL;
    }
    if (shm_window_count >= MAX_SHM_WINDOWS) {
        LOG("[egl-wrapper] ERROR: max shm_windows reached\n");
        return NULL;
    }

    struct shm_window *win = &shm_windows[shm_window_count];
    memset(win, 0, sizeof(*win));
    win->shm_fd = -1;
    win->egl_config = config;
    win->egl_display = dpy;

    /* 1. Create wl_surface */
    uint32_t comp_ver = wl_get_version(wrapper_wl_compositor);
    void *surface = wl_marshal_flags(
        wrapper_wl_compositor, WL_COMPOSITOR_CREATE_SURFACE,
        wl_surface_iface, comp_ver, 0, NULL);
    if (!surface) {
        LOG("[egl-wrapper] ERROR: wl_compositor_create_surface failed\n");
        return NULL;
    }
    win->wl_surface = surface;
    LOG("[egl-wrapper] Created wl_surface\n");

    /* 2. Create xdg_surface(wl_surface) */
    uint32_t xdg_ver = wl_get_version(wrapper_xdg_wm_base);
    void *xdg_surf = wl_marshal_flags(
        wrapper_xdg_wm_base, XDG_WM_BASE_GET_XDG_SURFACE,
        (const void *)&xdg_surface_iface, xdg_ver, 0,
        NULL, surface);
    if (!xdg_surf) {
        LOG("[egl-wrapper] ERROR: xdg_wm_base.get_xdg_surface failed\n");
        return NULL;
    }
    win->xdg_surface = xdg_surf;
    /* Add configure listener — pass shm_window as data */
    wl_add_listener(xdg_surf, (void *)&xdg_surface_listener, win);
    LOG("[egl-wrapper] Created xdg_surface\n");

    /* 3. Create xdg_toplevel */
    void *toplevel = wl_marshal_flags(
        xdg_surf, XDG_SURFACE_GET_TOPLEVEL,
        (const void *)&xdg_toplevel_iface, wl_get_version(xdg_surf), 0,
        NULL);
    if (!toplevel) {
        LOG("[egl-wrapper] ERROR: xdg_surface.get_toplevel failed\n");
        return NULL;
    }
    win->xdg_toplevel = toplevel;
    LOG("[egl-wrapper] Created xdg_toplevel\n");

    /* 4. Set title and app_id for compositor identification */
    wl_marshal_flags(toplevel, XDG_TOPLEVEL_SET_TITLE, NULL, wl_get_version(toplevel), 0, "VS Code");
    wl_marshal_flags(toplevel, XDG_TOPLEVEL_SET_APP_ID, NULL, wl_get_version(toplevel), 0, "com.coder.vscode");

    /* 5. Add toplevel listener — pass shm_window as data for resize */
    wl_add_listener(toplevel, (void *)&xdg_toplevel_listener, win);

    /* 6. Initial commit (required by xdg_shell before configure) */
    wl_marshal_flags(surface, WL_SURFACE_COMMIT, NULL,
                     wl_get_version(surface), 0);

    /* 7. Roundtrip to get configure event (ack'd by our listener) */
    wl_roundtrip(wrapper_wl_display);
    LOG("[egl-wrapper] xdg_toplevel setup complete\n");

    /* 8. Create SHM double buffers */
    win->width = DEFAULT_WIDTH;
    win->height = DEFAULT_HEIGHT;
    if (!create_shm_buffers(win, DEFAULT_WIDTH, DEFAULT_HEIGHT)) {
        LOG("[egl-wrapper] ERROR: Failed to create SHM buffers\n");
        return NULL;
    }

    /* 9. Create pbuffer surface for offscreen rendering */
    EGLint pbuf_attribs[] = {
        EGL_WIDTH, DEFAULT_WIDTH,
        EGL_HEIGHT, DEFAULT_HEIGHT,
        EGL_NONE
    };
    if (!real_eglCreatePbufferSurface) {
        LOG("[egl-wrapper] ERROR: eglCreatePbufferSurface not available\n");
        destroy_shm_buffers(win);
        return NULL;
    }
    EGLSurface pbuf = real_eglCreatePbufferSurface(dpy, config, pbuf_attribs);
    if (pbuf == EGL_NO_SURFACE) {
        LOG("[egl-wrapper] ERROR: eglCreatePbufferSurface failed\n");
        destroy_shm_buffers(win);
        return NULL;
    }
    win->pbuffer_surface = pbuf;
    LOG("[egl-wrapper] Created pbuffer surface\n");

    shm_window_count++;
    return win;
}

/* ========================================================================
 * Handle resize (called from eglSwapBuffers when needs_resize is set)
 * ======================================================================== */
static int handle_shm_resize(struct shm_window *win) {
    int new_w = win->pending_width;
    int new_h = win->pending_height;

    LOG("[egl-wrapper] Resizing SHM window to ");
    log_int(new_w);
    LOG("x");
    log_int(new_h);
    LOG("\n");

    /* Destroy old SHM buffers */
    destroy_shm_buffers(win);

    /* Destroy old pbuffer */
    if (win->pbuffer_surface && real_eglDestroySurface) {
        real_eglDestroySurface(win->egl_display, win->pbuffer_surface);
        win->pbuffer_surface = NULL;
    }

    /* Create new SHM buffers at new size */
    if (!create_shm_buffers(win, new_w, new_h)) {
        LOG("[egl-wrapper] ERROR: Failed to recreate SHM buffers after resize\n");
        return 0;
    }

    /* Create new pbuffer at new size */
    EGLint pbuf_attribs[] = {
        EGL_WIDTH, new_w,
        EGL_HEIGHT, new_h,
        EGL_NONE
    };
    EGLSurface pbuf = real_eglCreatePbufferSurface(
        win->egl_display, win->egl_config, pbuf_attribs);
    if (pbuf == EGL_NO_SURFACE) {
        LOG("[egl-wrapper] ERROR: eglCreatePbufferSurface failed after resize\n");
        return 0;
    }
    win->pbuffer_surface = pbuf;

    win->width = new_w;
    win->height = new_h;
    win->needs_resize = 0;

    LOG("[egl-wrapper] Resize complete\n");
    return 1;
}

/* ========================================================================
 * Constructor — load Mesa EGL + Wayland libraries
 * ======================================================================== */
__attribute__((constructor))
static void egl_wrapper_init(void) {
    LOG("[egl-wrapper] Loading real Mesa EGL from /usr/lib/aarch64-linux-gnu/libEGL.so.1\n");
    real_egl = dlopen("/usr/lib/aarch64-linux-gnu/libEGL.so.1", RTLD_NOW | RTLD_LOCAL);
    if (!real_egl) {
        real_egl = dlopen("/usr/lib/aarch64-linux-gnu/libEGL_mesa.so.0", RTLD_NOW | RTLD_LOCAL);
    }
    if (!real_egl) {
        LOG("[egl-wrapper] ERROR: Failed to load real Mesa EGL!\n");
        return;
    }
    LOG("[egl-wrapper] Real Mesa EGL loaded\n");

    real_getProcAddress = (pfn_eglGetProcAddress)dlsym(real_egl, "eglGetProcAddress");
    if (!real_getProcAddress) {
        LOG("[egl-wrapper] ERROR: No eglGetProcAddress in real Mesa\n");
        return;
    }

    /* Resolve all EGL functions */
    real_getPlatformDisplay = (pfn_eglGetPlatformDisplay)real_getProcAddress("eglGetPlatformDisplay");
    real_getPlatformDisplayEXT = (pfn_eglGetPlatformDisplayEXT)real_getProcAddress("eglGetPlatformDisplayEXT");
    real_eglGetDisplay = (pfn_eglGetDisplay)real_getProcAddress("eglGetDisplay");
    real_eglChooseConfig = (pfn_eglChooseConfig)real_getProcAddress("eglChooseConfig");
    real_eglGetConfigAttrib = (pfn_eglGetConfigAttrib)real_getProcAddress("eglGetConfigAttrib");
    real_eglCreateWindowSurface = (pfn_eglCreateWindowSurface)real_getProcAddress("eglCreateWindowSurface");
    real_eglCreatePlatformWindowSurface = (pfn_eglCreatePlatformWindowSurface)real_getProcAddress("eglCreatePlatformWindowSurface");
    real_eglCreatePlatformWindowSurfaceEXT = (pfn_eglCreatePlatformWindowSurfaceEXT)real_getProcAddress("eglCreatePlatformWindowSurfaceEXT");
    real_eglCreatePbufferSurface = (pfn_eglCreatePbufferSurface)real_getProcAddress("eglCreatePbufferSurface");
    real_eglDestroySurface = (pfn_eglDestroySurface)real_getProcAddress("eglDestroySurface");
    real_eglQuerySurface = (pfn_eglQuerySurface)real_getProcAddress("eglQuerySurface");

    /* Diagnostic intercept functions */
    real_eglSwapBuffers = (pfn_eglSwapBuffers)real_getProcAddress("eglSwapBuffers");
    real_eglMakeCurrent = (pfn_eglMakeCurrent)real_getProcAddress("eglMakeCurrent");
    real_eglInitialize = (pfn_eglInitialize)real_getProcAddress("eglInitialize");
    real_eglCreateContext = (pfn_eglCreateContext)real_getProcAddress("eglCreateContext");

    real_eglGetError = (pfn_eglGetError)real_getProcAddress("eglGetError");
    real_eglQueryString = (pfn_eglQueryString)real_getProcAddress("eglQueryString");

    /* --- Load wayland-client functions --- */
    void *wl_client = dlopen("libwayland-client.so.0", RTLD_NOW | RTLD_LOCAL);
    if (wl_client) {
        wl_connect = (pfn_wl_display_connect)dlsym(wl_client, "wl_display_connect");
        wl_disconnect = (pfn_wl_display_disconnect)dlsym(wl_client, "wl_display_disconnect");
        wl_roundtrip = (pfn_wl_display_roundtrip)dlsym(wl_client, "wl_display_roundtrip");
        wl_dispatch = (pfn_wl_display_dispatch)dlsym(wl_client, "wl_display_dispatch");
        wl_flush = (pfn_wl_display_flush)dlsym(wl_client, "wl_display_flush");
        wl_marshal_flags = (pfn_wl_proxy_marshal_flags)dlsym(wl_client, "wl_proxy_marshal_flags");
        wl_add_listener = (pfn_wl_proxy_add_listener)dlsym(wl_client, "wl_proxy_add_listener");
        wl_get_version = (pfn_wl_proxy_get_version)dlsym(wl_client, "wl_proxy_get_version");

        wl_registry_iface = dlsym(wl_client, "wl_registry_interface");
        wl_compositor_iface = dlsym(wl_client, "wl_compositor_interface");
        wl_surface_iface = dlsym(wl_client, "wl_surface_interface");
        wl_shm_iface = dlsym(wl_client, "wl_shm_interface");
        wl_shm_pool_iface = dlsym(wl_client, "wl_shm_pool_interface");
        wl_buffer_iface = dlsym(wl_client, "wl_buffer_interface");

        LOG("[egl-wrapper] Loaded libwayland-client\n");
    } else {
        LOG("[egl-wrapper] WARNING: Could not load libwayland-client.so.0\n");
    }

    /* GL functions loaded lazily via ensure_gl_funcs() in eglSwapBuffers */

    /* Detect GPU mode from WLDroid's namespaced environment variable. */
    const char *mode_env = getenv("WLDROID_GPU_MODE");
    if (mode_env && (strcmp(mode_env, "VIRGL_GLES") == 0 ||
                     strcmp(mode_env, "VIRGL_ZINK") == 0 ||
                     strcmp(mode_env, "TURNIP_DIRECT") == 0 ||
                     strcmp(mode_env, "VENUS") == 0)) {
        gpu_mode = 1;
        LOG("[egl-wrapper] GPU mode: GBM passthrough\n");

        /* Load Mesa's libgbm for GBM device recreation.
         * Chromium statically links minigbm, whose gbm_device has a different
         * layout than Mesa's. Mesa's EGL rejects minigbm devices with:
         *   "DRI2: gbm device using incorrect/incompatible backend"
         * We dlopen Mesa's libgbm to create a Mesa-compatible gbm_device. */
        void *gbm_handle = dlopen("/usr/lib/aarch64-linux-gnu/libgbm.so.1", RTLD_NOW | RTLD_LOCAL);
        if (!gbm_handle) {
            gbm_handle = dlopen("libgbm.so.1", RTLD_NOW | RTLD_LOCAL);
        }
        if (gbm_handle) {
            real_gbm_create_device = (pfn_gbm_create_device)dlsym(gbm_handle, "gbm_create_device");
            real_gbm_device_destroy = (pfn_gbm_device_destroy)dlsym(gbm_handle, "gbm_device_destroy");
            LOG("[egl-wrapper] Loaded Mesa libgbm.so.1\n");
        } else {
            LOG("[egl-wrapper] WARNING: Could not load Mesa libgbm.so.1\n");
        }
    } else {
        gpu_mode = 0;
        LOG("[egl-wrapper] Software mode: surfaceless + readback\n");
    }

    LOG("[egl-wrapper] Initialized\n");
}

/* ========================================================================
 * EGL Interceptors
 * ======================================================================== */

EGLBoolean eglChooseConfig(EGLDisplay dpy, const EGLint *attrib_list,
                           EGLConfig *configs, EGLint config_size, EGLint *num_config) {
    if (!real_eglChooseConfig) return EGL_FALSE;

    if (gpu_mode) {
        /* GPU mode: pass through unchanged — Mesa returns GBM-compatible configs */
        EGLBoolean ret = real_eglChooseConfig(dpy, attrib_list, configs, config_size, num_config);
        if (num_config) {
            LOG("[egl-wrapper] eglChooseConfig (GPU passthrough): ");
            log_str(ret ? "OK" : "FAILED");
            LOG(", ");
            log_int(*num_config);
            LOG(" configs\n");
        }
        return ret;
    }

    /* Software mode: Copy attribute list, replacing EGL_WINDOW_BIT with EGL_PBUFFER_BIT
     * in EGL_SURFACE_TYPE.  ANGLE asks for window surfaces but we only
     * have pbuffers on the surfaceless platform. */
#define MAX_EGL_ATTRIBS 64
    EGLint modified[MAX_EGL_ATTRIBS];
    int modified_count = 0;
    int did_modify = 0;

    if (attrib_list) {
        for (int i = 0; attrib_list[i] != EGL_NONE && modified_count < MAX_EGL_ATTRIBS - 2; i += 2) {
            modified[modified_count]     = attrib_list[i];
            modified[modified_count + 1] = attrib_list[i + 1];
            if (attrib_list[i] == EGL_SURFACE_TYPE) {
                /* Replace WINDOW_BIT with PBUFFER_BIT */
                EGLint val = attrib_list[i + 1];
                if (val & EGL_WINDOW_BIT) {
                    val = (val & ~EGL_WINDOW_BIT) | EGL_PBUFFER_BIT;
                    modified[modified_count + 1] = val;
                    did_modify = 1;
                }
            }
            modified_count += 2;
        }
        modified[modified_count] = EGL_NONE;
    }

    const EGLint *use_attribs = (attrib_list && did_modify) ? modified : attrib_list;
    EGLBoolean ret = real_eglChooseConfig(dpy, use_attribs, configs, config_size, num_config);

    /* Log result */
    if (num_config) {
        LOG("[egl-wrapper] eglChooseConfig: ");
        log_str(ret ? "OK" : "FAILED");
        LOG(", ");
        log_int(*num_config);
        LOG(" configs");
        if (did_modify) LOG(" (WINDOW->PBUFFER)");
        LOG("\n");
    }

    return ret;
}

EGLBoolean eglGetConfigAttrib(EGLDisplay dpy, EGLConfig config, EGLint attribute, EGLint *value) {
    if (!real_eglGetConfigAttrib) return EGL_FALSE;
    EGLBoolean ret = real_eglGetConfigAttrib(dpy, config, attribute, value);

    if (gpu_mode) {
        /* GPU mode: pass through unchanged */
        return ret;
    }

    /* Software mode: Add EGL_WINDOW_BIT to EGL_SURFACE_TYPE so ANGLE thinks
     * this config supports window surfaces (we intercept
     * eglCreateWindowSurface -> pbuffer anyway) */
    if (ret && attribute == EGL_SURFACE_TYPE && value) {
        *value |= EGL_WINDOW_BIT;
    }

    return ret;
}

/* --- Window surface creation: create pbuffer + wl_shm + xdg_toplevel --- */
/* --- eglQuerySurface: fake EGL_WINDOW_BIT for our managed pbuffer surfaces --- */

EGLBoolean eglQuerySurface(EGLDisplay dpy, EGLSurface surface, EGLint attribute, EGLint *value) {
    /* Safety net: resolve lazily if constructor didn't run (e.g., dlopen ordering) */
    if (!real_eglQuerySurface) {
        real_eglQuerySurface = (pfn_eglQuerySurface)real_getProcAddress("eglQuerySurface");
    }
    if (!real_eglQuerySurface) return EGL_FALSE;

    if (gpu_mode) {
        /* GPU mode: pass through unchanged */
        return real_eglQuerySurface(dpy, surface, attribute, value);
    }

    /* Software mode: fake window surface attributes for our managed pbuffers */
    struct shm_window *win = find_shm_window(surface);
    if (win) {
        switch (attribute) {
        case EGL_SURFACE_TYPE:  /* 0x3033 */
            if (value) *value = EGL_WINDOW_BIT | EGL_PBUFFER_BIT;
            return EGL_TRUE;
        case EGL_WIDTH:  /* 0x3057 */
            if (value) *value = win->width;
            return EGL_TRUE;
        case EGL_HEIGHT:  /* 0x3058 */
            if (value) *value = win->height;
            return EGL_TRUE;
        default:
            break;
        }
    }

    return real_eglQuerySurface(dpy, surface, attribute, value);
}



EGLSurface eglCreateWindowSurface(EGLDisplay dpy, EGLConfig config,
                                   EGLNativeWindowType win, const EGLint *attrib_list) {
    if (gpu_mode) {
        /* GPU mode: pass through to real Mesa, but create xdg_toplevel for touch redirect */
        init_wayland_connection();
        create_touch_redirect_toplevel();
        LOG("[egl-wrapper] eglCreateWindowSurface: GBM passthrough\n");
        if (!real_eglCreateWindowSurface)
            real_eglCreateWindowSurface = (pfn_eglCreateWindowSurface)real_getProcAddress("eglCreateWindowSurface");
        if (!real_eglCreateWindowSurface) return EGL_NO_SURFACE;
        return real_eglCreateWindowSurface(dpy, config, win, attrib_list);
    }

    /* Software mode: existing pbuffer + wl_shm path */
    (void)win; (void)attrib_list;

    if (!init_wayland_connection()) {
        LOG("[egl-wrapper] eglCreateWindowSurface: wayland init failed\n");
        return EGL_NO_SURFACE;
    }

    LOG("[egl-wrapper] eglCreateWindowSurface: creating pbuffer + wl_shm window\n");
    struct shm_window *sw = create_shm_window(dpy, config);
    if (sw) {
        return sw->pbuffer_surface;
    }
    LOG("[egl-wrapper] ERROR: Failed to create shm_window\n");
    return EGL_NO_SURFACE;
}

EGLSurface eglCreatePlatformWindowSurface(EGLDisplay dpy, EGLConfig config,
                                           void *native_window, const EGLAttrib *attrib_list) {
    if (gpu_mode) {
        /* GPU mode: pass through to real Mesa, but create xdg_toplevel for touch redirect */
        init_wayland_connection();
        create_touch_redirect_toplevel();
        LOG("[egl-wrapper] eglCreatePlatformWindowSurface: GBM passthrough\n");
        if (!real_eglCreatePlatformWindowSurface)
            real_eglCreatePlatformWindowSurface = (pfn_eglCreatePlatformWindowSurface)real_getProcAddress("eglCreatePlatformWindowSurface");
        if (!real_eglCreatePlatformWindowSurface) return EGL_NO_SURFACE;
        return real_eglCreatePlatformWindowSurface(dpy, config, native_window, attrib_list);
    }

    /* Software mode: existing pbuffer + wl_shm path */
    (void)native_window; (void)attrib_list;

    if (!init_wayland_connection()) {
        LOG("[egl-wrapper] eglCreatePlatformWindowSurface: wayland init failed\n");
        return EGL_NO_SURFACE;
    }

    LOG("[egl-wrapper] eglCreatePlatformWindowSurface: creating pbuffer + wl_shm window\n");
    struct shm_window *sw = create_shm_window(dpy, config);
    if (sw) {
        return sw->pbuffer_surface;
    }
    LOG("[egl-wrapper] ERROR: Failed to create shm_window\n");
    return EGL_NO_SURFACE;
}

EGLSurface eglCreatePlatformWindowSurfaceEXT(EGLDisplay dpy, EGLConfig config,
                                              void *native_window, const EGLint *attrib_list) {
    if (gpu_mode) {
        /* GPU mode: pass through to real Mesa, but create xdg_toplevel for touch redirect */
        init_wayland_connection();
        create_touch_redirect_toplevel();
        LOG("[egl-wrapper] eglCreatePlatformWindowSurfaceEXT: GBM passthrough\n");
        if (!real_eglCreatePlatformWindowSurfaceEXT)
            real_eglCreatePlatformWindowSurfaceEXT = (pfn_eglCreatePlatformWindowSurfaceEXT)real_getProcAddress("eglCreatePlatformWindowSurfaceEXT");
        if (!real_eglCreatePlatformWindowSurfaceEXT) return EGL_NO_SURFACE;
        return real_eglCreatePlatformWindowSurfaceEXT(dpy, config, native_window, attrib_list);
    }

    /* Software mode: existing pbuffer + wl_shm path */
    (void)native_window; (void)attrib_list;

    if (!init_wayland_connection()) {
        LOG("[egl-wrapper] eglCreatePlatformWindowSurfaceEXT: wayland init failed\n");
        return EGL_NO_SURFACE;
    }

    LOG("[egl-wrapper] eglCreatePlatformWindowSurfaceEXT: creating pbuffer + wl_shm window\n");
    struct shm_window *sw = create_shm_window(dpy, config);
    if (sw) {
        return sw->pbuffer_surface;
    }
    LOG("[egl-wrapper] ERROR: Failed to create shm_window\n");
    return EGL_NO_SURFACE;
}

/* --- Diagnostic intercepts: pipeline tracing --- */

EGLBoolean eglInitialize(EGLDisplay dpy, EGLint *major, EGLint *minor) {
    /* Safety net: resolve lazily if constructor didn't run (e.g., dlopen ordering) */
    if (!real_eglInitialize) {
        real_eglInitialize = (pfn_eglInitialize)real_getProcAddress("eglInitialize");
    }
    if (!real_eglInitialize) return EGL_FALSE;

    EGLBoolean ret = real_eglInitialize(dpy, major, minor);
    if (gpu_mode) {
        if (!ret) {
            egl_init_failed_once = 1;
            LOG("[egl-wrapper] WARNING: GBM eglInitialize failed, will fall back to software if retry succeeds\n");
        } else if (egl_init_failed_once) {
            LOG("[egl-wrapper] GBM failed but retry succeeded (surfaceless fallback) — switching to software mode\n");
            gpu_mode = 0;
            egl_init_failed_once = 0;
        }
    }
    LOG("[egl-wrapper] eglInitialize: ");
    if (ret && major && minor) {
        log_int(*major);
        LOG(".");
        log_int(*minor);
        LOG(" OK\n");
    } else {
        log_str(ret ? "OK" : "FAILED");
        LOG("\n");
    }
    return ret;
}

void *eglCreateContext(EGLDisplay dpy, EGLConfig config, void *share_context, const EGLint *attrib_list) {
    /* Safety net: resolve lazily if constructor didn't run (e.g., dlopen ordering) */
    if (!real_eglCreateContext) {
        real_eglCreateContext = (pfn_eglCreateContext)real_getProcAddress("eglCreateContext");
    }
    if (!real_eglCreateContext) return NULL;

    void *ctx = real_eglCreateContext(dpy, config, share_context, attrib_list);
    LOG("[egl-wrapper] eglCreateContext: ");
    log_str(ctx ? "OK" : "FAILED (NULL)");
    LOG("\n");
    return ctx;
}

EGLBoolean eglMakeCurrent(EGLDisplay dpy, EGLSurface draw, EGLSurface read, void *ctx) {
    /* Safety net: resolve lazily if constructor didn't run (e.g., dlopen ordering) */
    if (!real_eglMakeCurrent) {
        real_eglMakeCurrent = (pfn_eglMakeCurrent)real_getProcAddress("eglMakeCurrent");
    }
    if (!real_eglMakeCurrent) return EGL_FALSE;

    EGLBoolean ret = real_eglMakeCurrent(dpy, draw, read, ctx);
    make_current_count++;
    if (make_current_count <= 10) {
        LOG("[egl-wrapper] eglMakeCurrent #");
        log_int(make_current_count);
        LOG(": ");
        log_str(ret ? "OK" : "FAILED");
        struct shm_window *mc_win = find_shm_window(draw);
        if (mc_win) {
            LOG(" [managed surface ");
            log_int(mc_win->width);
            LOG("x");
            log_int(mc_win->height);
            LOG("]");
        }
        LOG("\n");
    }

    /* Load GL functions once context is current */
    if (ret && !gl_funcs_loaded) {
        ensure_gl_funcs();
        /* Probe GL strings for diagnostics */
        typedef const unsigned char* (*pfn_glGetString)(unsigned int);
        pfn_glGetString my_glGetString = (pfn_glGetString)real_getProcAddress("glGetString");
        if (my_glGetString) {
            const char *ver = (const char*)my_glGetString(0x1F02); /* GL_VERSION */
            const char *ren = (const char*)my_glGetString(0x1F01); /* GL_RENDERER */
            const char *ven = (const char*)my_glGetString(0x1F00); /* GL_VENDOR */
            const char *sl  = (const char*)my_glGetString(0x8B8C); /* GL_SHADING_LANGUAGE_VERSION */
            LOG("[egl-wrapper] GL_VERSION: "); log_str(ver ? ver : "(null)"); LOG("\n");
            LOG("[egl-wrapper] GL_RENDERER: "); log_str(ren ? ren : "(null)"); LOG("\n");
            LOG("[egl-wrapper] GL_VENDOR: "); log_str(ven ? ven : "(null)"); LOG("\n");
            LOG("[egl-wrapper] GL_SHADING_LANGUAGE_VERSION: "); log_str(sl ? sl : "(null)"); LOG("\n");
        }
    }

    return ret;
}

/* --- eglSwapBuffers: readback → row-flip → wl_shm → commit --- */

EGLBoolean eglSwapBuffers(EGLDisplay dpy, EGLSurface surface) {
    static int swap_count = 0;
    swap_count++;
    if (swap_count <= 5 || (swap_count % 100 == 0)) {
        LOG("[egl-wrapper] eglSwapBuffers #");
        log_int(swap_count);
        if (gpu_mode) LOG(" (gpu_mode)");
        LOG("\n");
    }
    if (gpu_mode) {
        /* GPU mode: pure passthrough */
        if (!real_eglSwapBuffers) {
            real_eglSwapBuffers = (pfn_eglSwapBuffers)real_getProcAddress("eglSwapBuffers");
        }
        if (!real_eglSwapBuffers) return EGL_FALSE;
        return real_eglSwapBuffers(dpy, surface);
    }

    /* Software mode: readback → row-flip → wl_shm → commit */
    /* Find associated shm_window */
    struct shm_window *win = find_shm_window(surface);

    if (!win) {
        /* Not one of our managed surfaces — pass through */
        if (!real_eglSwapBuffers) {
            real_eglSwapBuffers = (pfn_eglSwapBuffers)real_getProcAddress("eglSwapBuffers");
        }
        if (!real_eglSwapBuffers) return EGL_FALSE;
        return real_eglSwapBuffers(dpy, surface);
    }

    swap_count++;

    /* Handle pending resize */
    if (win->needs_resize) {
        EGLSurface old_pbuf = win->pbuffer_surface;
        if (!handle_shm_resize(win)) {
            LOG("[egl-wrapper] ERROR: resize failed in eglSwapBuffers\n");
            return EGL_FALSE;
        }
        /* If pbuffer changed, caller (ANGLE) needs to eglMakeCurrent with new surface.
         * We update the surface pointer so find_shm_window still works. The next
         * eglMakeCurrent from ANGLE will pick up the new surface. For now, if the
         * surface changed, we need to rebind. */
        if (win->pbuffer_surface != old_pbuf && real_eglMakeCurrent) {
            /* Re-bind with new pbuffer — use same draw+read */
            real_eglMakeCurrent(dpy, win->pbuffer_surface, win->pbuffer_surface, NULL);
            /* Note: passing NULL context keeps current context; however this is not
             * standard. ANGLE will do its own MakeCurrent. We skip rebind here and
             * return — the frame will be rendered on next swap after ANGLE rebinds. */
        }
    }

    /* Ensure GL functions are loaded */
    ensure_gl_funcs();
    if (!real_glFinish || !real_glReadPixels) {
        LOG("[egl-wrapper] ERROR: GL functions not available for readback\n");
        return EGL_FALSE;
    }

    int w = win->width;
    int h = win->height;
    int stride = w * 4;
    size_t buf_size = (size_t)stride * h;

    /* Pick buffer — prefer non-busy */
    int idx = win->current_buf;
    if (win->buf_busy[idx]) {
        idx = 1 - idx;
        if (win->buf_busy[idx]) {
            /* Both busy — dispatch events to get release */
            if (wl_dispatch) {
                wl_dispatch(wrapper_wl_display);
            }
            /* If still busy, use current anyway (will overwrite in-flight buffer) */
            idx = win->current_buf;
            win->buf_busy[idx] = 0;
        }
    }

    /* 1. Finish GPU rendering */
    real_glFinish();

    /* 2. Read pixels into temp buffer */
    real_glReadPixels(0, 0, w, h, GL_RGBA, GL_UNSIGNED_BYTE, win->readback_buf);

    /* 3. Row-flip copy into SHM buffer (GL origin is bottom-left, SHM is top-left) */
    uint8_t *dst = win->shm_data + (idx * buf_size);
    for (int row = 0; row < h; row++) {
        memcpy(dst + row * stride,
               win->readback_buf + (h - 1 - row) * stride,
               (size_t)stride);
    }

    /* 4. Attach buffer to surface */
    uint32_t surf_ver = wl_get_version(win->wl_surface);
    wl_marshal_flags(win->wl_surface, WL_SURFACE_ATTACH, NULL,
                     surf_ver, 0, win->wl_buffer[idx], (int32_t)0, (int32_t)0);

    /* 5. Damage the entire buffer */
    wl_marshal_flags(win->wl_surface, WL_SURFACE_DAMAGE_BUFFER, NULL,
                     surf_ver, 0, (int32_t)0, (int32_t)0, (int32_t)w, (int32_t)h);

    /* 6. Commit */
    wl_marshal_flags(win->wl_surface, WL_SURFACE_COMMIT, NULL, surf_ver, 0);

    /* 7. Flush to compositor */
    if (wl_flush) {
        wl_flush(wrapper_wl_display);
    }

    /* 8. Mark buffer as busy, swap to next */
    win->buf_busy[idx] = 1;
    win->current_buf = 1 - idx;

    if (swap_count <= 3 || (swap_count % 100) == 0) {
        LOG("[egl-wrapper] eglSwapBuffers: readback → wl_shm (#");
        log_int(swap_count);
        LOG(")\n");
    }

    /* Call real eglSwapBuffers on the pbuffer — Mesa will return EGL_BAD_SURFACE
     * but this consumes the call in ANGLE's dispatch chain. Then clear the error
     * so ANGLE's subsequent eglGetError check sees EGL_SUCCESS. */
    if (real_eglSwapBuffers) {
        suppress_bad_surface = 1;
        real_eglSwapBuffers(dpy, surface);  /* Expected to "fail" on pbuffer */
    }
    if (real_eglGetError) {
        real_eglGetError();  /* Consume EGL_BAD_SURFACE error */
    }
    suppress_bad_surface = 0;

    return EGL_TRUE;
}

/* --- eglGetError: suppress EGL_BAD_SURFACE from pbuffer swap --- */

EGLint eglGetError(void) {
    if (!real_eglGetError) return 0x3000;  /* EGL_SUCCESS */
    EGLint err = real_eglGetError();

    if (gpu_mode) {
        /* GPU mode: pass through unchanged */
        return err;
    }

    /* Software mode: Suppress EGL_BAD_SURFACE (0x300D) only when our
     * eglSwapBuffers wrapper just called real_eglSwapBuffers on a pbuffer
     * (which is expected to fail). The scoped flag prevents masking
     * legitimate EGL_BAD_SURFACE errors from other call sites. */
    if (err == 0x300D && suppress_bad_surface) {
        suppress_bad_surface = 0;
        return 0x3000;  /* EGL_SUCCESS */
    }
    return err;
}

/* --- eglDestroySurface: clean up shm_window resources in software mode --- */

EGLBoolean eglDestroySurface(EGLDisplay dpy, EGLSurface surface) {
    /* Safety net: resolve lazily if constructor didn't run (e.g., dlopen ordering) */
    if (!real_eglDestroySurface) {
        real_eglDestroySurface = (pfn_eglDestroySurface)real_getProcAddress("eglDestroySurface");
    }
    if (!real_eglDestroySurface) return EGL_FALSE;

    if (gpu_mode) {
        /* GPU mode: pass through unchanged */
        return real_eglDestroySurface(dpy, surface);
    }

    /* Software mode: check if this is one of our managed shm_windows */
    struct shm_window *win = find_shm_window(surface);
    if (win) {
        LOG("[egl-wrapper] eglDestroySurface: cleaning up managed shm_window\n");

        /* 1. Destroy SHM buffers, readback buffer, unmap shm, close fd */
        destroy_shm_buffers(win);

        /* 2. Destroy xdg_toplevel */
        if (win->xdg_toplevel && wl_marshal_flags && wl_get_version) {
            wl_marshal_flags(win->xdg_toplevel, XDG_TOPLEVEL_DESTROY, NULL,
                             wl_get_version(win->xdg_toplevel), 0);
            win->xdg_toplevel = NULL;
        }

        /* 3. Destroy xdg_surface */
        if (win->xdg_surface && wl_marshal_flags && wl_get_version) {
            wl_marshal_flags(win->xdg_surface, XDG_SURFACE_DESTROY, NULL,
                             wl_get_version(win->xdg_surface), 0);
            win->xdg_surface = NULL;
        }

        /* 4. Destroy wl_surface */
        if (win->wl_surface && wl_marshal_flags && wl_get_version) {
            wl_marshal_flags(win->wl_surface, WL_SURFACE_DESTROY, NULL,
                             wl_get_version(win->wl_surface), 0);
            win->wl_surface = NULL;
        }

        /* 5. Remove from shm_windows[] — swap with last entry */
        int idx = (int)(win - shm_windows);
        if (idx < shm_window_count - 1) {
            shm_windows[idx] = shm_windows[shm_window_count - 1];
        }
        shm_window_count--;
        memset(&shm_windows[shm_window_count], 0, sizeof(struct shm_window));

        LOG("[egl-wrapper] eglDestroySurface: shm_window cleaned up\n");
    }

    /* Pass through to real Mesa to destroy the underlying pbuffer/surface */
    return real_eglDestroySurface(dpy, surface);
}

/* --- Display creation: GBM → Surfaceless redirect --- */

EGLDisplay eglGetPlatformDisplay(EGLenum platform, void *native_display, const EGLAttrib *attrib_list) {
    if (!real_getPlatformDisplay) return EGL_NO_DISPLAY;
    if (platform == EGL_PLATFORM_GBM_KHR) {
        init_wayland_connection();
        if (gpu_mode) {
            if (!mesa_gbm_device && real_gbm_create_device) {
                /* Open the DRM render node directly — drm-shim intercepts this */
                int drm_fd = open("/dev/dri/renderD128", O_RDWR);
                if (drm_fd >= 0) {
                    mesa_gbm_device = real_gbm_create_device(drm_fd);
                    if (mesa_gbm_device) {
                        LOG("[egl-wrapper] Created Mesa GBM device from /dev/dri/renderD128 (fd=");
                        log_int(drm_fd);
                        LOG(")\n");
                    } else {
                        LOG("[egl-wrapper] ERROR: Mesa gbm_create_device failed\n");
                        close(drm_fd);
                    }
                } else {
                    LOG("[egl-wrapper] ERROR: Failed to open /dev/dri/renderD128\n");
                }
            }

            if (mesa_gbm_device) {
                init_wayland_connection();
                return real_getPlatformDisplay(platform, mesa_gbm_device, attrib_list);
            }
            /* Fallback: passthrough */
            init_wayland_connection();
            return real_getPlatformDisplay(platform, native_display, attrib_list);
        }
        LOG("[egl-wrapper] eglGetPlatformDisplay: GBM -> SURFACELESS\n");
        return real_getPlatformDisplay(EGL_PLATFORM_SURFACELESS_MESA, EGL_DEFAULT_DISPLAY, attrib_list);
    }
    return real_getPlatformDisplay(platform, native_display, attrib_list);
}

EGLDisplay eglGetPlatformDisplayEXT(EGLenum platform, void *native_display, const EGLint *attrib_list) {
    if (!real_getPlatformDisplayEXT) return EGL_NO_DISPLAY;
    if (platform == EGL_PLATFORM_GBM_KHR) {
        init_wayland_connection();
        if (gpu_mode) {
            if (!mesa_gbm_device && real_gbm_create_device) {
                /* Open the DRM render node directly — drm-shim intercepts this */
                int drm_fd = open("/dev/dri/renderD128", O_RDWR);
                if (drm_fd >= 0) {
                    mesa_gbm_device = real_gbm_create_device(drm_fd);
                    if (mesa_gbm_device) {
                        LOG("[egl-wrapper] Created Mesa GBM device from /dev/dri/renderD128 (fd=");
                        log_int(drm_fd);
                        LOG(")\n");
                    } else {
                        LOG("[egl-wrapper] ERROR: Mesa gbm_create_device failed\n");
                        close(drm_fd);
                    }
                } else {
                    LOG("[egl-wrapper] ERROR: Failed to open /dev/dri/renderD128\n");
                }
            }

            if (mesa_gbm_device) {
                init_wayland_connection();
                return real_getPlatformDisplayEXT(platform, mesa_gbm_device, attrib_list);
            }
            /* Fallback: passthrough */
            init_wayland_connection();
            return real_getPlatformDisplayEXT(platform, native_display, attrib_list);
        }
        LOG("[egl-wrapper] eglGetPlatformDisplayEXT: GBM -> SURFACELESS\n");
        return real_getPlatformDisplayEXT(EGL_PLATFORM_SURFACELESS_MESA, EGL_DEFAULT_DISPLAY, attrib_list);
    }
    return real_getPlatformDisplayEXT(platform, native_display, attrib_list);
}

EGLDisplay eglGetDisplay(void *display_id) {
    if (gpu_mode) {
        LOG("[egl-wrapper] eglGetDisplay: passthrough\n");
        if (real_eglGetDisplay) {
            return real_eglGetDisplay(display_id);
        }
        return EGL_NO_DISPLAY;
    }

    LOG("[egl-wrapper] eglGetDisplay called -> redirecting to SURFACELESS\n");

    if (real_getPlatformDisplay) {
        init_wayland_connection();
        return real_getPlatformDisplay(EGL_PLATFORM_SURFACELESS_MESA, EGL_DEFAULT_DISPLAY, NULL);
    }

    if (real_eglGetDisplay) {
        return real_eglGetDisplay(display_id);
    }
    return EGL_NO_DISPLAY;
}

/* ========================================================================
 * eglGetProcAddress — redirect to our interceptors
 * ======================================================================== */
/* ========================================================================
 * eglQueryString — strip unsupported extensions in GPU mode
 *
 * Mesa's virgl driver advertises EGL_ANDROID_native_fence_sync, but virgl
 * over vtest doesn't actually support Android native fences.  Chromium sees
 * the extension, calls eglDupNativeFenceFDANDROID, and crashes with
 * "Trace/breakpoint trap".  Strip the extension so Chromium never tries.
 * ======================================================================== */
#define EGL_EXTENSIONS 0x3055

const char *eglQueryString(EGLDisplay dpy, EGLint name) {
    if (!real_eglQueryString)
        real_eglQueryString = (pfn_eglQueryString)real_getProcAddress("eglQueryString");
    if (!real_eglQueryString) return NULL;

    const char *result = real_eglQueryString(dpy, name);

    /* In GPU mode, strip unsupported Android extensions from the list */
    if (name == EGL_EXTENSIONS && result && gpu_mode) {
        if (strstr(result, "EGL_ANDROID_native_fence_sync")) {
            if (!extensions_filtered) {
                size_t len = strlen(result);
                if (len < sizeof(filtered_extensions) - 1) {
                    strcpy(filtered_extensions, result);
                    char *pos = strstr(filtered_extensions, "EGL_ANDROID_native_fence_sync");
                    if (pos) {
                        char *end = pos;
                        while (*end && *end != ' ') end++;
                        if (*end == ' ') end++;  /* skip trailing space */
                        memmove(pos, end, strlen(end) + 1);
                    }
                    extensions_filtered = 1;
                    LOG("[egl-wrapper] Stripped EGL_ANDROID_native_fence_sync from extensions\n");
                }
            }
            return filtered_extensions;
        }
    }

    return result;
}

EGLFuncPtr eglGetProcAddress(const char *procname) {
    if (!real_getProcAddress) return NULL;
    if (procname) {
        if (strcmp(procname, "eglGetPlatformDisplay") == 0)
            return (EGLFuncPtr)eglGetPlatformDisplay;
        if (strcmp(procname, "eglGetPlatformDisplayEXT") == 0)
            return (EGLFuncPtr)eglGetPlatformDisplayEXT;
        if (strcmp(procname, "eglGetDisplay") == 0)
            return (EGLFuncPtr)eglGetDisplay;
        if (strcmp(procname, "eglChooseConfig") == 0)
            return (EGLFuncPtr)eglChooseConfig;
        if (strcmp(procname, "eglGetConfigAttrib") == 0)
            return (EGLFuncPtr)eglGetConfigAttrib;
        if (strcmp(procname, "eglQuerySurface") == 0)
            return (EGLFuncPtr)eglQuerySurface;
        if (strcmp(procname, "eglCreateWindowSurface") == 0)
            return (EGLFuncPtr)eglCreateWindowSurface;
        if (strcmp(procname, "eglCreatePlatformWindowSurface") == 0)
            return (EGLFuncPtr)eglCreatePlatformWindowSurface;
        if (strcmp(procname, "eglCreatePlatformWindowSurfaceEXT") == 0)
            return (EGLFuncPtr)eglCreatePlatformWindowSurfaceEXT;
        if (strcmp(procname, "eglDestroySurface") == 0)
            return (EGLFuncPtr)eglDestroySurface;
        if (strcmp(procname, "eglInitialize") == 0)
            return (EGLFuncPtr)eglInitialize;
        if (strcmp(procname, "eglCreateContext") == 0)
            return (EGLFuncPtr)eglCreateContext;
        if (strcmp(procname, "eglMakeCurrent") == 0)
            return (EGLFuncPtr)eglMakeCurrent;
        if (strcmp(procname, "eglSwapBuffers") == 0)
            return (EGLFuncPtr)eglSwapBuffers;
        if (strcmp(procname, "eglGetError") == 0)
            return (EGLFuncPtr)eglGetError;
        if (strcmp(procname, "eglQueryString") == 0)
            return (EGLFuncPtr)eglQueryString;
        /* Block eglDupNativeFenceFDANDROID — virgl/vtest doesn't support it */
        if (strcmp(procname, "eglDupNativeFenceFDANDROID") == 0) {
            LOG("[egl-wrapper] Blocked eglDupNativeFenceFDANDROID\n");
            return NULL;
        }
    }
    return real_getProcAddress(procname);
}
