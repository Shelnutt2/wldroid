/*
 * drm_shim.h — Internal constants and logging for the DRM shim library.
 *
 * This header does NOT redefine libdrm types; consumers must include
 * <xf86drm.h> from the system libdrm-dev package.
 */

#ifndef DRM_SHIM_H
#define DRM_SHIM_H

#include <unistd.h>  /* write, STDERR_FILENO, ssize_t, size_t */
#include <stdarg.h>  /* va_list, va_start, va_end */
#include <stdio.h>   /* vsnprintf */

/* Driver identity — must be "virtio_gpu" so Mesa loads virtio_gpu_dri.so
 * and selects the virgl DRM winsys path. */
#define DRM_SHIM_DRIVER_NAME    "virtio_gpu"
#define DRM_SHIM_DRIVER_DESC    "Coder virtio-gpu bridge (drm-shim)"
#define DRM_SHIM_DRIVER_DATE    "20260422"
#define DRM_SHIM_VERSION_MAJOR  0
#define DRM_SHIM_VERSION_MINOR  1
#define DRM_SHIM_VERSION_PATCH  0

#define DRM_SHIM_RENDER_NODE    "/dev/dri/renderD128"
#define DRM_SHIM_CARD_NODE      "/dev/dri/card0"
#define DRM_SHIM_BUS_ID         "platform:virtio-gpu:00"
#define DRM_SHIM_PLATFORM_NAME  "virtio-gpu"

/* Vtest socket defaults */
#define VTEST_DEFAULT_SOCK      "/tmp/.virgl_test"

/* Resource / BO tracking */
#define MAX_BO_TABLE_INITIAL    1024
#define MMAP_OFFSET_BASE        0x10000000ULL
#define MMAP_OFFSET_STRIDE      0x100000ULL   /* 1 MB stride per resource */

/* Logging — write()-based to avoid glibc 2.38 __isoc23_fprintf symbol.
 * Only accepts a plain string (no printf format args). */
static inline void drm_shim_log_msg(const char *msg)
{
    if (msg) {
        static const char prefix[] = "[drm-shim] ";
        ssize_t __attribute__((unused)) r;
        r = write(STDERR_FILENO, prefix, sizeof(prefix) - 1);
        size_t len = 0;
        while (msg[len]) len++;
        r = write(STDERR_FILENO, msg, len);
        r = write(STDERR_FILENO, "\n", 1);
    }
}
#define DRM_SHIM_LOG(msg) drm_shim_log_msg(msg)

/* Formatted logging — uses vsnprintf + write() to avoid glibc symbol issues. */
static inline void drm_shim_log_fmt(const char *fmt, ...) __attribute__((format(printf, 1, 2)));
static inline void drm_shim_log_fmt(const char *fmt, ...)
{
    char buf[256];
    static const char prefix[] = "[drm-shim] ";
    ssize_t __attribute__((unused)) r;
    r = write(STDERR_FILENO, prefix, sizeof(prefix) - 1);
    va_list ap;
    va_start(ap, fmt);
    int n = vsnprintf(buf, sizeof(buf), fmt, ap);
    va_end(ap);
    if (n > 0)
        r = write(STDERR_FILENO, buf, (size_t)n < sizeof(buf) ? (size_t)n : sizeof(buf) - 1);
    r = write(STDERR_FILENO, "\n", 1);
}
#define DRM_SHIM_LOGF(fmt, ...) drm_shim_log_fmt(fmt, ##__VA_ARGS__)

#ifdef NDEBUG
#define DRM_SHIM_DEBUG(msg)
#else
#define DRM_SHIM_DEBUG(msg) drm_shim_log_msg(msg)
#endif

/* Saved Wayland state for EGL wrapper (see egl_override.c).
 * Populated by wl_display_connect / wl_proxy_add_listener intercepts. */
extern void *drm_shim_saved_wl_display;
extern void *drm_shim_saved_wl_surface;

#endif /* DRM_SHIM_H */
