/*
 * drm_shim.c — LD_PRELOAD shim that bridges VIRTGPU ioctls to vtest.
 *
 * Inside proot there are no real DRM devices.  This library:
 *   1. Exposes a fake "virtio_gpu" platform device so Mesa selects the
 *      virgl DRM winsys path (virtio_gpu_dri.so).
 *   2. Intercepts drmIoctl() and dispatches VIRTGPU ioctls to the
 *      vtest Unix socket server (virgl_test_server).
 *   3. Intercepts mmap() to redirect resource mappings to shm fds
 *      received from the vtest server.
 *
 * Wire protocol: vtest v3 (multi-client mode, server-assigned resource IDs).
 * Falls back to v2 (shm fd passing via SCM_RIGHTS) if server is older.
 */

#define _GNU_SOURCE

#include <stdlib.h>
#include <string.h>
#include <stdint.h>
#include <unistd.h>
#include <errno.h>
#include <dlfcn.h>
#include <pthread.h>
#include <signal.h>
#include <stdarg.h>
#include <stdbool.h>
#include <fcntl.h>
#include <sys/mman.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <sys/eventfd.h>
#include <sys/stat.h>
#include <sys/sysmacros.h>
#include <xf86drm.h>
#include <virtgpu_drm.h>

#include "drm_shim.h"

/* Round up to alignment boundary */
#define ALIGN_UP(x, align) (((x) + (align) - 1) & ~((size_t)(align) - 1))

/* Vtest protocol command IDs (from vtest_protocol.h) */
#define VCMD_GET_CAPS                1
#define VCMD_RESOURCE_CREATE         2
#define VCMD_RESOURCE_UNREF          3
#define VCMD_TRANSFER_GET            4
#define VCMD_TRANSFER_PUT            5
#define VCMD_SUBMIT_CMD              6
#define VCMD_RESOURCE_BUSY_WAIT      7
#define VCMD_CREATE_RENDERER         8
#define VCMD_GET_CAPS2               9
#define VCMD_PING_PROTOCOL_VERSION  10
#define VCMD_PROTOCOL_VERSION       11
#define VCMD_RESOURCE_CREATE2       12
#define VCMD_TRANSFER_GET2          13
#define VCMD_TRANSFER_PUT2          14

#define VCMD_BUSY_WAIT_FLAG_WAIT     1

/* ------------------------------------------------------------------ */
/*  Helpers                                                           */
/* ------------------------------------------------------------------ */

static char *shim_strdup(const char *s)
{
    if (!s)
        return NULL;
    size_t len = strlen(s) + 1;
    char *dup = malloc(len);
    if (dup)
        memcpy(dup, s, len);
    return dup;
}

/* ------------------------------------------------------------------ */
/*  Vtest global state                                                */
/* ------------------------------------------------------------------ */

static int vtest_sock = -1;
static pthread_mutex_t vtest_mutex = PTHREAD_MUTEX_INITIALIZER;
/* Multi-fd tracking: multiple libraries (ANGLE, Mesa EGL) may independently
 * open /dev/dri/renderD128, each getting a different fd.  We track all of them
 * so that every fd's ioctls, mmaps, and stats are properly intercepted. */
#define MAX_DRM_FDS 32
static int drm_fds[MAX_DRM_FDS];
static int drm_fd_count = 0;
static pthread_mutex_t drm_fd_mutex = PTHREAD_MUTEX_INITIALIZER;

static void drm_fd_add(int fd)
{
    pthread_mutex_lock(&drm_fd_mutex);
    /* Check if already tracked */
    for (int i = 0; i < drm_fd_count; i++) {
        if (drm_fds[i] == fd) {
            pthread_mutex_unlock(&drm_fd_mutex);
            return;
        }
    }
    /* Add if space available */
    if (drm_fd_count < MAX_DRM_FDS) {
        drm_fds[drm_fd_count++] = fd;
    }
    pthread_mutex_unlock(&drm_fd_mutex);
}

static int drm_fd_is_tracked(int fd)
{
    if (fd < 0) return 0;
    /* Fast lockless scan.  The lockless scan could briefly see stale data
     * during concurrent drmClose(), but the worst case is a false positive
     * (tracking a removed fd) or false negative (missing a valid fd during
     * removal).  No crash or corruption possible. */
    int count = __atomic_load_n(&drm_fd_count, __ATOMIC_ACQUIRE);
    for (int i = 0; i < count; i++) {
        if (__atomic_load_n(&drm_fds[i], __ATOMIC_RELAXED) == fd)
            return 1;
    }
    return 0;
}

static void drm_fd_remove(int fd)
{
    pthread_mutex_lock(&drm_fd_mutex);
    for (int i = 0; i < drm_fd_count; i++) {
        if (drm_fds[i] == fd) {
            drm_fds[i] = drm_fds[--drm_fd_count];
            break;
        }
    }
    pthread_mutex_unlock(&drm_fd_mutex);
}
static int vtest_protocol_version = 0;

/* BO / resource tracking */
struct drm_shim_bo {
    uint32_t bo_handle;    /* local handle (what Mesa sees) — 0 = free slot */
    uint32_t res_handle;   /* vtest-side resource ID */
    int      shm_fd;       /* shared memory fd from RESOURCE_CREATE2 */
    uint32_t size;         /* resource data size in bytes */
    uint32_t stride;       /* stride from resource create args */
    uint32_t width;        /* resource width (for TRANSFER_GET box) */
    uint32_t height;       /* resource height (for TRANSFER_GET box) */
    uint64_t mmap_offset;  /* fake offset returned by MAP ioctl */
    void    *mmap_ptr;     /* mmap'd pointer (lazily mapped) */
    int      mapped;       /* whether mmap_offset has been assigned */
    bool     is_ahb;       /* true when backed by AHB dmabuf (skip TRANSFER_GET2) */
};

static struct drm_shim_bo *bo_table = NULL;
static uint32_t bo_table_capacity = 0;
static uint32_t bo_handle_next = 1;
static pthread_mutex_t bo_mutex = PTHREAD_MUTEX_INITIALIZER;

/* Persistent inode→res_handle cache (survives gem_close).
 * When a BO is created via RESOURCE_CREATE2 with an AHB shm_fd,
 * we record its inode + res_handle here.  If the BO is later closed
 * and the same AHB fd is re-imported, we can recover the res_handle
 * instead of leaving it as 0. */
#define INODE_CACHE_SIZE 64
static struct {
    ino_t    inode;
    dev_t    dev;
    uint32_t res_handle;
    uint32_t width, height, stride, size;
    bool     is_ahb;
    bool     valid;
} inode_cache[INODE_CACHE_SIZE];
static pthread_mutex_t inode_cache_mutex = PTHREAD_MUTEX_INITIALIZER;

static void inode_cache_store(int fd, uint32_t res_handle,
                              uint32_t width, uint32_t height,
                              uint32_t stride, uint32_t size, bool is_ahb)
{
    struct stat st;
    if (fd < 0 || res_handle == 0 || fstat(fd, &st) < 0)
        return;

    pthread_mutex_lock(&inode_cache_mutex);
    /* Look for existing entry or empty slot */
    int slot = -1;
    for (int i = 0; i < INODE_CACHE_SIZE; i++) {
        if (inode_cache[i].valid &&
            inode_cache[i].dev == st.st_dev &&
            inode_cache[i].inode == st.st_ino) {
            slot = i;  /* update in place */
            break;
        }
        if (!inode_cache[i].valid && slot < 0)
            slot = i;
    }
    if (slot < 0) slot = 0;  /* evict first entry */
    inode_cache[slot].inode      = st.st_ino;
    inode_cache[slot].dev        = st.st_dev;
    inode_cache[slot].res_handle = res_handle;
    inode_cache[slot].width      = width;
    inode_cache[slot].height     = height;
    inode_cache[slot].stride     = stride;
    inode_cache[slot].size       = size;
    inode_cache[slot].is_ahb     = is_ahb;
    inode_cache[slot].valid      = true;
    pthread_mutex_unlock(&inode_cache_mutex);
}

/* Try to recover res_handle and geometry from inode cache.
 * Returns true if found.  Must be called WITHOUT bo_mutex held
 * (takes inode_cache_mutex only). */
static bool inode_cache_lookup(int fd, struct drm_shim_bo *bo)
{
    struct stat st;
    if (fd < 0 || fstat(fd, &st) < 0)
        return false;

    bool found = false;
    pthread_mutex_lock(&inode_cache_mutex);
    for (int i = 0; i < INODE_CACHE_SIZE; i++) {
        if (inode_cache[i].valid &&
            inode_cache[i].dev == st.st_dev &&
            inode_cache[i].inode == st.st_ino) {
            bo->res_handle = inode_cache[i].res_handle;
            bo->width      = inode_cache[i].width;
            bo->height     = inode_cache[i].height;
            bo->stride     = inode_cache[i].stride;
            bo->size       = inode_cache[i].size;
            bo->is_ahb     = inode_cache[i].is_ahb;
            found = true;
            break;
        }
    }
    pthread_mutex_unlock(&inode_cache_mutex);
    return found;
}

/* mmap interception */
typedef void *(*real_mmap_fn)(void *, size_t, int, int, int, off_t);
static real_mmap_fn real_mmap = NULL;

/* ioctl interception */
typedef int (*real_ioctl_fn)(int fd, unsigned long request, ...);
static real_ioctl_fn real_ioctl = NULL;

/* open / openat interception */
typedef int (*real_open_fn)(const char *pathname, int flags, ...);
static real_open_fn real_open = NULL;

typedef int (*real_openat_fn)(int dirfd, const char *pathname, int flags, ...);
static real_openat_fn real_openat = NULL;

/* fstatat interception — fakes DRM device nodes as character devices */
typedef int (*real_fstatat_fn)(int dirfd, const char *pathname,
                               struct stat *buf, int flags);
static real_fstatat_fn real_fstatat = NULL;

/* dlopen interception — trace DRI/Mesa/GPU library loads */
typedef void *(*real_dlopen_fn)(const char *, int);
static real_dlopen_fn real_dlopen_ptr = NULL;
/* fcntl interception — Mesa's GBM EGL calls fcntl(F_DUPFD_CLOEXEC) on the
 * gbm device fd during eglInitialize; proot may not support it natively. */
typedef int (*real_fcntl_fn)(int fd, int cmd, ...);
static real_fcntl_fn real_fcntl = NULL;
static real_fcntl_fn real_fcntl64 = NULL;


/* dlsym interception — needed to catch Chromium's dlsym(handle, "wl_proxy_add_listener").
 * Chromium dlopen's libwayland-client.so.0 and uses dlsym(handle, ...) on that
 * specific handle, bypassing LD_PRELOAD symbol resolution.  We intercept dlsym
 * itself so we can redirect wayland function lookups to our wrappers. */
typedef void *(*pfn_dlsym)(void *, const char *);
static pfn_dlsym real_dlsym = NULL;

/* Bootstrap real dlsym using dlvsym (GNU extension, declared in <dlfcn.h> with
 * _GNU_SOURCE).  dlvsym resolves a specific symbol version from glibc, avoiding
 * recursion through our own dlsym interceptor.  GLIBC_2.17 is the stable
 * version for dlsym on aarch64.  Unlike __libc_dlsym (a glibc internal that
 * some distros don't export), dlvsym is part of the public GNU API. */
static void ensure_real_dlsym(void)
{
    if (!real_dlsym)
        real_dlsym = (pfn_dlsym)dlvsym(RTLD_NEXT, "dlsym", "GLIBC_2.17");
}

/* Safe internal dlsym — avoids recursion through our public dlsym interceptor.
 * All dlsym calls inside drm-shim MUST use this macro instead of dlsym(). */
#define SHIM_DLSYM(handle, sym) (ensure_real_dlsym(), real_dlsym((handle), (sym)))

/* ------------------------------------------------------------------ */
/*  Electron child-process detection                                  */
/* ------------------------------------------------------------------ */

/*
 * VS Code / Electron spawns child processes (extensionHost, fileWatcher,
 * shared-process, zygote, utility, renderer, crashpad-handler) that
 * inherit LD_PRELOAD.  Most don't use DRM, Wayland, or vtest — our
 * dlsym/ioctl/wayland intercepts can SIGSEGV in those processes, so we
 * skip heavyweight initialization and pass through wayland calls directly.
 *
 * EXCEPTION: --type=gpu-process is Chromium's GPU process, which IS the
 * process that calls drmGetDevices2() to enumerate DRM devices.  It must
 * NOT be skipped, or the shim's dlsym() interceptor won't redirect
 * drmGetDevices2 and Chromium gets "Permission denied" on /dev/dri/.
 *
 * Detection: old-style Node.js fork children set ELECTRON_RUN_AS_NODE=1.
 * Newer Electron UtilityProcess children use Chromium's --type= arg
 * (--type=utility, --type=renderer, --type=zygote, etc.).
 * The main browser process does NOT have --type=.
 */
static int is_electron_child(void)
{
    static int cached = -1;
    if (cached != -1)
        return cached;

    /* Check ELECTRON_RUN_AS_NODE first (old-style Node children) */
    if (getenv("ELECTRON_RUN_AS_NODE")) {
        cached = 1;
        return 1;
    }

    /* Check /proc/self/cmdline for --type= (Chromium child processes).
     * cmdline args are NUL-separated; search each arg for the prefix. */
    cached = 0;
    int fd = open("/proc/self/cmdline", O_RDONLY);
    if (fd >= 0) {
        char buf[4096];
        ssize_t n = read(fd, buf, sizeof(buf) - 1);
        close(fd);
        if (n > 0) {
            buf[n] = '\0';
            for (ssize_t i = 0; i < n; i++) {
                if (buf[i] == '\0')
                    continue;
                if (strncmp(&buf[i], "--type=", 7) == 0) {
                    /* gpu-process needs DRM interception — don't skip it */
                    if (strncmp(&buf[i], "--type=gpu-process", 18) != 0) {
                        cached = 1;
                    }
                    break;
                }
                /* Skip to next NUL */
                while (i < n && buf[i] != '\0')
                    i++;
            }
        }
    }

    return cached;
}

/* ------------------------------------------------------------------ */
/*  Wayland state sharing — export Ozone's display+surface for EGL    */
/* ------------------------------------------------------------------ */

/*
 * These globals are exported (non-static) so the EGL wrapper can find them
 * via dlsym(RTLD_DEFAULT, "drm_shim_saved_wl_display").  Both are void*
 * so we don't need Wayland headers.
 */
void *drm_shim_saved_wl_display = NULL;  /* Ozone's wl_display* */
void *drm_shim_saved_wl_surface = NULL;  /* first wl_surface (main window) */

/* Track recent wl_surface proxies in case we need them later */
#define MAX_TRACKED_SURFACES 16
static void *tracked_surfaces[MAX_TRACKED_SURFACES];
static int   tracked_surface_count = 0;

/* --- wl_display_connect intercept --- */
typedef void *(*pfn_wl_display_connect)(const char *);
static pfn_wl_display_connect real_wl_display_connect = NULL;

void *wl_display_connect(const char *name)
{
    if (!real_wl_display_connect)
        real_wl_display_connect = (pfn_wl_display_connect)SHIM_DLSYM(RTLD_NEXT, "wl_display_connect");
    if (!real_wl_display_connect)
        return NULL;

    /* Electron child processes: pass through without tracking */
    if (is_electron_child())
        return real_wl_display_connect(name);

    void *display = real_wl_display_connect(name);
    if (display && !drm_shim_saved_wl_display) {
        drm_shim_saved_wl_display = display;
        DRM_SHIM_LOG("Saved Ozone wl_display");
    }
    return display;
}

/* --- wl_proxy_add_listener intercept: track surfaces, detect toplevel --- */
typedef int         (*pfn_wl_proxy_add_listener)(void *, void **, void *);
typedef const char *(*pfn_wl_proxy_get_class)(void *);

static pfn_wl_proxy_add_listener real_wl_proxy_add_listener = NULL;
static pfn_wl_proxy_get_class    pfn_get_class = NULL;

int wl_proxy_add_listener(void *proxy, void **implementation, void *data)
{
    if (!real_wl_proxy_add_listener)
        real_wl_proxy_add_listener = (pfn_wl_proxy_add_listener)SHIM_DLSYM(RTLD_NEXT, "wl_proxy_add_listener");
    if (!real_wl_proxy_add_listener)
        return -1;

    /* Electron child processes: pass through without surface tracking */
    if (is_electron_child())
        return real_wl_proxy_add_listener(proxy, implementation, data);

    /* Lazy-resolve wl_proxy_get_class (available since libwayland 1.11) */
    if (!pfn_get_class)
        pfn_get_class = (pfn_wl_proxy_get_class)SHIM_DLSYM(RTLD_DEFAULT, "wl_proxy_get_class");

    if (pfn_get_class && proxy) {
        const char *cls = pfn_get_class(proxy);
        if (cls) {
            /* Log every intercepted listener registration for debugging */
            {
                static const char prefix[] = "[drm-shim] wl_proxy_add_listener: class=";
                ssize_t __attribute__((unused)) r;
                r = write(STDERR_FILENO, prefix, sizeof(prefix) - 1);
                size_t len = 0;
                while (cls[len]) len++;
                r = write(STDERR_FILENO, cls, len);
                r = write(STDERR_FILENO, "\n", 1);
            }

            if (strcmp(cls, "wl_surface") == 0) {
                /* Track this surface proxy */
                if (tracked_surface_count < MAX_TRACKED_SURFACES)
                    tracked_surfaces[tracked_surface_count++] = proxy;
                DRM_SHIM_LOG("Tracked wl_surface proxy");

                /* First surface = main window surface.  Chromium creates its
                 * toplevel wl_surface before cursor/popup ones, so the first
                 * one we see is the right choice.  Export it immediately —
                 * we can't rely on xdg_toplevel appearing because Chromium
                 * uses its own static libwayland copy for that path. */
                if (!drm_shim_saved_wl_surface) {
                    drm_shim_saved_wl_surface = proxy;
                    DRM_SHIM_LOG("Saved toplevel wl_surface (first surface)");
                }
            }
            else if (strcmp(cls, "xdg_toplevel") == 0) {
                /* Nice-to-have confirmation — not required for selection */
                DRM_SHIM_LOG("xdg_toplevel seen (confirmation)");
            }
        }
    }

    return real_wl_proxy_add_listener(proxy, implementation, data);
}

/* ------------------------------------------------------------------ */
/*  BO table operations                                               */
/* ------------------------------------------------------------------ */

/* Must be called with bo_mutex held. Returns a new BO with handle assigned. */
static struct drm_shim_bo *bo_alloc(void)
{
    uint32_t h = bo_handle_next++;

    /* Grow table if needed */
    if (h >= bo_table_capacity) {
        uint32_t new_cap = bo_table_capacity ? bo_table_capacity * 2 : MAX_BO_TABLE_INITIAL;
        while (new_cap <= h)
            new_cap *= 2;
        struct drm_shim_bo *new_table = realloc(bo_table, new_cap * sizeof(*bo_table));
        if (!new_table)
            return NULL;
        memset(&new_table[bo_table_capacity], 0,
               (new_cap - bo_table_capacity) * sizeof(*new_table));
        bo_table = new_table;
        bo_table_capacity = new_cap;
    }

    struct drm_shim_bo *bo = &bo_table[h];
    memset(bo, 0, sizeof(*bo));
    bo->bo_handle = h;
    bo->shm_fd = -1;
    return bo;
}

/* Must be called with bo_mutex held. */
static struct drm_shim_bo *bo_lookup(uint32_t handle)
{
    if (handle == 0 || handle >= bo_table_capacity)
        return NULL;
    struct drm_shim_bo *bo = &bo_table[handle];
    if (bo->bo_handle == 0)
        return NULL;
    return bo;
}

/* Must be called with bo_mutex held. Linear scan for mmap reverse lookup. */
static struct drm_shim_bo *bo_lookup_by_offset(uint64_t offset)
{
    for (uint32_t i = 1; i < bo_table_capacity; i++) {
        if (bo_table[i].bo_handle != 0 &&
            bo_table[i].mapped &&
            bo_table[i].mmap_offset == offset)
            return &bo_table[i];
    }
    return NULL;
}

/* ------------------------------------------------------------------ */
/*  Vtest socket helpers                                              */
/* ------------------------------------------------------------------ */

static int vtest_block_write(int fd, const void *buf, size_t count)
{
    const uint8_t *p = buf;
    size_t left = count;
    while (left > 0) {
        ssize_t n = write(fd, p, left);
        if (n < 0) {
            if (errno == EINTR)
                continue;
            return -1;
        }
        p += n;
        left -= (size_t)n;
    }
    return 0;
}

static int vtest_block_read(int fd, void *buf, size_t count)
{
    uint8_t *p = buf;
    size_t left = count;
    while (left > 0) {
        ssize_t n = read(fd, p, left);
        if (n <= 0) {
            if (n < 0 && errno == EINTR)
                continue;
            return -1;
        }
        p += n;
        left -= (size_t)n;
    }
    return 0;
}

/* Receive a file descriptor via SCM_RIGHTS ancillary data */
static int vtest_recv_fd(int sock_fd)
{
    char byte_buf[1];
    struct iovec iov = { .iov_base = byte_buf, .iov_len = 1 };
    char cmsg_buf[CMSG_SPACE(sizeof(int))];
    struct msghdr msg = {
        .msg_iov = &iov,
        .msg_iovlen = 1,
        .msg_control = cmsg_buf,
        .msg_controllen = sizeof(cmsg_buf),
    };

    ssize_t ret;
    do {
        ret = recvmsg(sock_fd, &msg, 0);
    } while (ret < 0 && errno == EINTR);
    if (ret <= 0)
        return -1;

    struct cmsghdr *cmsg = CMSG_FIRSTHDR(&msg);
    if (!cmsg || cmsg->cmsg_level != SOL_SOCKET || cmsg->cmsg_type != SCM_RIGHTS)
        return -1;

    int fd;
    memcpy(&fd, CMSG_DATA(cmsg), sizeof(fd));
    return fd;
}

/* ------------------------------------------------------------------ */
/*  Vtest connection & handshake                                      */
/* ------------------------------------------------------------------ */

static int vtest_connect(void)
{
    const char *path = getenv("VTEST_SOCK");
    if (!path || !path[0])
        path = VTEST_DEFAULT_SOCK;

    int sock = socket(AF_UNIX, SOCK_STREAM, 0);
    if (sock < 0)
        return -1;

    struct sockaddr_un addr;
    memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;

    /* Copy path, ensure NUL termination within sun_path */
    size_t plen = strlen(path);
    if (plen >= sizeof(addr.sun_path)) {
        close(sock);
        return -1;
    }
    memcpy(addr.sun_path, path, plen);

    if (connect(sock, (struct sockaddr *)&addr, sizeof(addr)) < 0) {
        close(sock);
        return -1;
    }

    return sock;
}

static int vtest_handshake(int sock)
{
    /* VCMD_CREATE_RENDERER: header length is in BYTES (name length) */
    const char *name = "drm-shim";
    uint32_t name_len = (uint32_t)strlen(name);
    uint32_t hdr[2];

    hdr[0] = name_len;
    hdr[1] = VCMD_CREATE_RENDERER;
    if (vtest_block_write(sock, hdr, 8) < 0)
        return -1;
    if (vtest_block_write(sock, name, name_len) < 0)
        return -1;

    /* VCMD_PING_PROTOCOL_VERSION: 0-length, expects echo response */
    hdr[0] = 0;
    hdr[1] = VCMD_PING_PROTOCOL_VERSION;
    if (vtest_block_write(sock, hdr, 8) < 0)
        return -1;

    /* Read ping response */
    uint32_t resp_hdr[2];
    if (vtest_block_read(sock, resp_hdr, 8) < 0)
        return -1;
    /* resp_hdr[1] should be VCMD_PING_PROTOCOL_VERSION */

    /* VCMD_PROTOCOL_VERSION: request version 3 (multi-client support) */
    hdr[0] = 1;  /* 1 dword payload */
    hdr[1] = VCMD_PROTOCOL_VERSION;
    uint32_t ver_req = 3;
    if (vtest_block_write(sock, hdr, 8) < 0)
        return -1;
    if (vtest_block_write(sock, &ver_req, 4) < 0)
        return -1;

    /* Read version response: hdr + 1 dword (negotiated version) */
    if (vtest_block_read(sock, resp_hdr, 8) < 0)
        return -1;
    uint32_t ver_resp;
    if (vtest_block_read(sock, &ver_resp, 4) < 0)
        return -1;

    return (int)ver_resp;
}

/* ------------------------------------------------------------------ */
/*  VIRTGPU ioctl handlers                                            */
/* ------------------------------------------------------------------ */
/*  Lazy vtest connection (thread-safe double-check locking)          */
/* ------------------------------------------------------------------ */

static int vtest_ensure_connected(void)
{
    /* Fast path: already connected */
    if (vtest_sock >= 0)
        return 0;

    /* Slow path: connect now (under lock) */
    pthread_mutex_lock(&vtest_mutex);
    if (vtest_sock >= 0) {
        /* Another thread connected while we waited */
        pthread_mutex_unlock(&vtest_mutex);
        return 0;
    }

    {
        static const char msg[] = "[drm-shim] vtest_ensure_connected: connecting...\n";
        ssize_t __attribute__((unused)) r = write(STDERR_FILENO, msg, sizeof(msg) - 1);
    }

    int sock = vtest_connect();
    if (sock < 0) {
        {
            static const char msg[] = "[drm-shim] vtest_ensure_connected: FAILED to connect\n";
            ssize_t __attribute__((unused)) r = write(STDERR_FILENO, msg, sizeof(msg) - 1);
        }
        pthread_mutex_unlock(&vtest_mutex);
        return -1;
    }

    int ver = vtest_handshake(sock);
    if (ver < 0) {
        {
            static const char msg[] = "[drm-shim] vtest_ensure_connected: handshake FAILED\n";
            ssize_t __attribute__((unused)) r = write(STDERR_FILENO, msg, sizeof(msg) - 1);
        }
        close(sock);
        pthread_mutex_unlock(&vtest_mutex);
        return -1;
    }

    vtest_sock = sock;
    vtest_protocol_version = ver;
    pthread_mutex_unlock(&vtest_mutex);

    /* Log outside the lock */
    {
        static const char msg[] = "[drm-shim] vtest connection established (lazy)\n";
        ssize_t __attribute__((unused)) r = write(STDERR_FILENO, msg, sizeof(msg) - 1);
    }

    if (ver < 2) {
        static const char msg[] = "[drm-shim] WARNING: vtest protocol < 2 (shm not supported)\n";
        ssize_t __attribute__((unused)) r = write(STDERR_FILENO, msg, sizeof(msg) - 1);
    }

    return 0;
}


/* ------------------------------------------------------------------ */

static int shim_getparam(void *arg)
{
    struct drm_virtgpu_getparam *gp = arg;
    uint64_t *val = (uint64_t *)(uintptr_t)gp->value;

    {
        static const char msg[] = "[drm-shim] shim_getparam() called\n";
        ssize_t __attribute__((unused)) r = write(STDERR_FILENO, msg, sizeof(msg) - 1);
    }

    /* These params are hardcoded — no vtest connection needed */
    switch (gp->param) {
    case VIRTGPU_PARAM_3D_FEATURES:
        *val = 1;
        return 0;
    case VIRTGPU_PARAM_CAPSET_QUERY_FIX:
        *val = 1;
        return 0;
    default:
        /* Unknown params (RESOURCE_BLOB, HOST_VISIBLE, etc.): not supported */
        *val = 0;
        errno = EINVAL;
        return -1;
    }
}

static int shim_get_caps(void *arg)
{
    {
        static const char msg[] = "[drm-shim] shim_get_caps() called\n";
        ssize_t __attribute__((unused)) r = write(STDERR_FILENO, msg, sizeof(msg) - 1);
    }

    if (vtest_ensure_connected() < 0) {
        errno = EIO;
        return -1;
    }

    struct drm_virtgpu_get_caps *gc = arg;
    void *caps_buf = (void *)(uintptr_t)gc->addr;
    uint32_t cmd_id;

    if (gc->cap_set_id == 1)
        cmd_id = VCMD_GET_CAPS;
    else if (gc->cap_set_id == 2)
        cmd_id = VCMD_GET_CAPS2;
    else {
        errno = EINVAL;
        return -1;
    }

    pthread_mutex_lock(&vtest_mutex);

    /* Send 0-length caps request */
    uint32_t hdr[2] = { 0, cmd_id };
    if (vtest_block_write(vtest_sock, hdr, 8) < 0) {
        pthread_mutex_unlock(&vtest_mutex);
        errno = EIO;
        return -1;
    }

    /* Read response header: [data_bytes+1 | capset_id] */
    uint32_t resp_hdr[2];
    if (vtest_block_read(vtest_sock, resp_hdr, 8) < 0) {
        pthread_mutex_unlock(&vtest_mutex);
        errno = EIO;
        return -1;
    }

    /* Server sends hdr[0] = max_size + 1, data = max_size bytes */
    uint32_t data_bytes = resp_hdr[0] > 0 ? resp_hdr[0] - 1 : 0;

    if (data_bytes > 0) {
        /* Read caps data into temporary buffer */
        void *tmp = malloc(data_bytes);
        if (!tmp) {
            /* Must still consume the data to keep protocol in sync */
            char discard[256];
            uint32_t left = data_bytes;
            while (left > 0) {
                uint32_t chunk = left < sizeof(discard) ? left : sizeof(discard);
                vtest_block_read(vtest_sock, discard, chunk);
                left -= chunk;
            }
            pthread_mutex_unlock(&vtest_mutex);
            errno = ENOMEM;
            return -1;
        }

        if (vtest_block_read(vtest_sock, tmp, data_bytes) < 0) {
            free(tmp);
            pthread_mutex_unlock(&vtest_mutex);
            errno = EIO;
            return -1;
        }

        /* Copy to user buffer */
        uint32_t copy_size = gc->size < data_bytes ? gc->size : data_bytes;
        memcpy(caps_buf, tmp, copy_size);
        free(tmp);
    }
    pthread_mutex_unlock(&vtest_mutex);

    return 0;
}

static int shim_resource_create(void *arg)
{
    {
        static const char msg[] = "[drm-shim] shim_resource_create() called\n";
        ssize_t __attribute__((unused)) r = write(STDERR_FILENO, msg, sizeof(msg) - 1);
    }

    if (vtest_ensure_connected() < 0) {
        errno = EIO;
        return -1;
    }

    struct drm_virtgpu_resource_create *rc = arg;

    /* Allocate local BO first to get a handle */
    pthread_mutex_lock(&bo_mutex);
    struct drm_shim_bo *bo = bo_alloc();
    if (!bo) {
        pthread_mutex_unlock(&bo_mutex);
        errno = ENOMEM;
        return -1;
    }
    uint32_t handle = bo->bo_handle;
    pthread_mutex_unlock(&bo_mutex);

    /* Build VCMD_RESOURCE_CREATE2 payload (11 dwords). */
    uint32_t payload[11];
    if (vtest_protocol_version >= 3)
        payload[0] = 0;           /* v3: server assigns res_handle */
    else
        payload[0] = handle;      /* v2: client assigns res_handle */
    payload[1]  = rc->target;
    payload[2]  = rc->format;
    payload[3]  = rc->bind;
    payload[4]  = rc->width;
    payload[5]  = rc->height;
    payload[6]  = rc->depth;
    payload[7]  = rc->array_size;
    payload[8]  = rc->last_level;
    payload[9]  = rc->nr_samples;
    payload[10] = rc->size;        /* data_size for shm */

    /* Ensure data_size covers at least stride*height (fixes too-small memfd) */
    uint32_t stride_size = rc->stride * rc->height;
    if (rc->size > 0 && stride_size > rc->size) {
        payload[10] = stride_size;
        DRM_SHIM_LOGF("resource_create: bumped DATA_SIZE %u -> %u (stride=%u, height=%u)",
                      rc->size, stride_size, rc->stride, rc->height);
    }

    pthread_mutex_lock(&vtest_mutex);

    uint32_t hdr[2] = { 11, VCMD_RESOURCE_CREATE2 };
    if (vtest_block_write(vtest_sock, hdr, 8) < 0 ||
        vtest_block_write(vtest_sock, payload, sizeof(payload)) < 0) {
        pthread_mutex_unlock(&vtest_mutex);
        errno = EIO;
        return -1;
    }

    /* Protocol v3+: server sends response header + server-assigned res_id.
     * Protocol v2:  no response header — only the shm fd follows. */
    uint32_t server_res_id = handle;  /* default: client-assigned (v2) */
    if (vtest_protocol_version >= 3) {
        uint32_t resp_hdr[2];
        if (vtest_block_read(vtest_sock, resp_hdr, 8) < 0) {
            pthread_mutex_unlock(&vtest_mutex);
            errno = EIO;
            return -1;
        }
        /* resp_hdr[0] = 1 (1 dword of data), resp_hdr[1] = VCMD_RESOURCE_CREATE2 */
        uint32_t res_id;
        if (vtest_block_read(vtest_sock, &res_id, 4) < 0) {
            pthread_mutex_unlock(&vtest_mutex);
            errno = EIO;
            return -1;
        }
        server_res_id = res_id;
    }

    /* Receive shm fd if data_size > 0 */
    int shm_fd = -1;
    if (rc->size > 0) {
        shm_fd = vtest_recv_fd(vtest_sock);
        if (shm_fd < 0) {
            pthread_mutex_unlock(&vtest_mutex);
            DRM_SHIM_LOG("resource_create: failed to receive shm fd");
            errno = EIO;
            return -1;
        }
    }
    pthread_mutex_unlock(&vtest_mutex);

    /* Fill BO entry */
    pthread_mutex_lock(&bo_mutex);
    bo = bo_lookup(handle);
    if (bo) {
        bo->res_handle = server_res_id;  /* v3: server-assigned, v2: client-assigned */
        bo->shm_fd = shm_fd;
        bo->size = payload[10];   /* Use the possibly-bumped value */
        bo->stride = rc->stride;
        bo->width = rc->width;
        bo->height = rc->height;

        /* Detect AHB dmabuf vs memfd via /proc/self/fd readlink.
         * memfds show as "/memfd:..." while dmabufs show as "anon_inode:dmabuf" or "/dmabuf:..." */
        if (shm_fd >= 0) {
            char link_path[64];
            char link_target[256];
            snprintf(link_path, sizeof(link_path), "/proc/self/fd/%d", shm_fd);
            ssize_t len = readlink(link_path, link_target, sizeof(link_target) - 1);
            if (len > 0) {
                link_target[len] = '\0';
                bo->is_ahb = (strstr(link_target, "memfd:") == NULL);
                if (bo->is_ahb) {
                    DRM_SHIM_LOGF("resource_create: bo=%u AHB detected (fd=%d -> %s)", handle, shm_fd, link_target);
                }
            }
        }
    }
    pthread_mutex_unlock(&bo_mutex);

    /* Seed inode cache so re-imports after gem_close can recover res_handle */
    if (shm_fd >= 0 && server_res_id != 0) {
        inode_cache_store(shm_fd, server_res_id,
                          rc->width, rc->height, rc->stride, payload[10],
                          bo ? bo->is_ahb : false);
    }

    DRM_SHIM_LOGF("resource_create: bo=%u res=%u shm_fd=%d size=%u stride=%u %ux%u",
                  handle, server_res_id, shm_fd, payload[10], rc->stride, rc->width, rc->height);

    /* Fill return values for Mesa */
    rc->bo_handle = handle;
    rc->res_handle = server_res_id;

    return 0;
}

static int shim_map(void *arg)
{
    struct drm_virtgpu_map *m = arg;

    pthread_mutex_lock(&bo_mutex);
    struct drm_shim_bo *bo = bo_lookup(m->handle);
    if (!bo) {
        pthread_mutex_unlock(&bo_mutex);
        errno = ENOENT;
        return -1;
    }

    if (!bo->mapped) {
        bo->mmap_offset = MMAP_OFFSET_BASE + ((uint64_t)bo->bo_handle * MMAP_OFFSET_STRIDE);
        bo->mapped = 1;
    }
    m->offset = bo->mmap_offset;
    pthread_mutex_unlock(&bo_mutex);

    return 0;
}

static int shim_execbuffer(void *arg)
{
    if (vtest_ensure_connected() < 0) {
        errno = EIO;
        return -1;
    }

    struct drm_virtgpu_execbuffer *eb = arg;
    { static int exec_count = 0; if (++exec_count <= 5 || (exec_count % 100) == 0) DRM_SHIM_LOGF("execbuffer: count=%d size=%u", exec_count, eb->size); }
    void *cmd = (void *)(uintptr_t)eb->command;
    uint32_t size_dwords = (eb->size + 3) / 4;

    /* Zero padding bytes to avoid leaking uninitialized data */
    uint32_t padded_size = size_dwords * 4;
    if (padded_size > eb->size) {
        memset((char *)cmd + eb->size, 0, padded_size - eb->size);
    }

    pthread_mutex_lock(&vtest_mutex);

    uint32_t hdr[2] = { size_dwords, VCMD_SUBMIT_CMD };
    if (vtest_block_write(vtest_sock, hdr, 8) < 0 ||
        vtest_block_write(vtest_sock, cmd, size_dwords * 4) < 0) {
        pthread_mutex_unlock(&vtest_mutex);
        errno = EIO;
        return -1;
    }
    pthread_mutex_unlock(&vtest_mutex);

    /* Handle fence_fd_out if requested */
    if (eb->flags & VIRTGPU_EXECBUF_FENCE_FD_OUT) {
        eb->fence_fd = eventfd(0, EFD_CLOEXEC);
        if (eb->fence_fd >= 0) {
            uint64_t val = 1;
            ssize_t __attribute__((unused)) w = write(eb->fence_fd, &val, sizeof(val));
        }
    }

    return 0;
}

static int shim_transfer_to_host(void *arg)
{
    if (vtest_ensure_connected() < 0) {
        errno = EIO;
        return -1;
    }

    struct drm_virtgpu_3d_transfer_to_host *t = arg;

    pthread_mutex_lock(&bo_mutex);
    struct drm_shim_bo *bo = bo_lookup(t->bo_handle);
    uint32_t res_handle = bo ? bo->res_handle : 0;
    pthread_mutex_unlock(&bo_mutex);

    if (!res_handle) {
        errno = ENOENT;
        return -1;
    }

    /* VCMD_TRANSFER_PUT2 payload: 10 dwords
     * [res_handle, level, x, y, z, w, h, d, data_size(0), offset] */
    uint32_t payload[10];
    payload[0] = res_handle;
    payload[1] = t->level;
    payload[2] = t->box.x;
    payload[3] = t->box.y;
    payload[4] = t->box.z;
    payload[5] = t->box.w;
    payload[6] = t->box.h;
    payload[7] = t->box.d;
    payload[8] = 0;          /* data_size = 0 (shm-based, v2) */
    payload[9] = t->offset;

    pthread_mutex_lock(&vtest_mutex);

    uint32_t hdr[2] = { 10, VCMD_TRANSFER_PUT2 };
    if (vtest_block_write(vtest_sock, hdr, 8) < 0 ||
        vtest_block_write(vtest_sock, payload, sizeof(payload)) < 0) {
        pthread_mutex_unlock(&vtest_mutex);
        errno = EIO;
        return -1;
    }
    pthread_mutex_unlock(&vtest_mutex);

    return 0;
}

static int shim_transfer_from_host(void *arg)
{
    if (vtest_ensure_connected() < 0) {
        errno = EIO;
        return -1;
    }

    struct drm_virtgpu_3d_transfer_from_host *t = arg;

    pthread_mutex_lock(&bo_mutex);
    struct drm_shim_bo *bo = bo_lookup(t->bo_handle);
    uint32_t res_handle = bo ? bo->res_handle : 0;
    pthread_mutex_unlock(&bo_mutex);

    if (!res_handle) {
        errno = ENOENT;
        return -1;
    }

    /* VCMD_TRANSFER_GET2 payload: 10 dwords */
    uint32_t payload[10];
    payload[0] = res_handle;
    payload[1] = t->level;
    payload[2] = t->box.x;
    payload[3] = t->box.y;
    payload[4] = t->box.z;
    payload[5] = t->box.w;
    payload[6] = t->box.h;
    payload[7] = t->box.d;
    payload[8] = 0;          /* data_size = 0 (shm-based, v2) */
    payload[9] = t->offset;

    pthread_mutex_lock(&vtest_mutex);

    uint32_t hdr[2] = { 10, VCMD_TRANSFER_GET2 };
    if (vtest_block_write(vtest_sock, hdr, 8) < 0 ||
        vtest_block_write(vtest_sock, payload, sizeof(payload)) < 0) {
        pthread_mutex_unlock(&vtest_mutex);
        errno = EIO;
        return -1;
    }
    pthread_mutex_unlock(&vtest_mutex);

    return 0;
}

static int shim_wait(void *arg)
{
    if (vtest_ensure_connected() < 0) {
        errno = EIO;
        return -1;
    }

    struct drm_virtgpu_3d_wait *w = arg;

    pthread_mutex_lock(&bo_mutex);
    struct drm_shim_bo *bo = bo_lookup(w->handle);
    uint32_t res_handle = bo ? bo->res_handle : 0;
    pthread_mutex_unlock(&bo_mutex);

    if (!res_handle) {
        errno = ENOENT;
        return -1;
    }

    /* Map DRM WAIT flags to vtest busy_wait flags */
    uint32_t vtest_flags = 0;
    if (!(w->flags & VIRTGPU_WAIT_NOWAIT))
        vtest_flags = VCMD_BUSY_WAIT_FLAG_WAIT;

    uint32_t payload[2] = { res_handle, vtest_flags };

    pthread_mutex_lock(&vtest_mutex);

    uint32_t hdr[2] = { 2, VCMD_RESOURCE_BUSY_WAIT };
    if (vtest_block_write(vtest_sock, hdr, 8) < 0 ||
        vtest_block_write(vtest_sock, payload, 8) < 0) {
        pthread_mutex_unlock(&vtest_mutex);
        errno = EIO;
        return -1;
    }

    /* Read response: [1 | VCMD_RESOURCE_BUSY_WAIT] [busy_flag] */
    uint32_t resp_hdr[2], busy;
    if (vtest_block_read(vtest_sock, resp_hdr, 8) < 0 ||
        vtest_block_read(vtest_sock, &busy, 4) < 0) {
        pthread_mutex_unlock(&vtest_mutex);
        errno = EIO;
        return -1;
    }
    pthread_mutex_unlock(&vtest_mutex);

    if (busy && (w->flags & VIRTGPU_WAIT_NOWAIT)) {
        errno = EBUSY;
        return -1;
    }

    return 0;
}

static int shim_resource_info(void *arg)
{
    struct drm_virtgpu_resource_info *ri = arg;

    pthread_mutex_lock(&bo_mutex);
    struct drm_shim_bo *bo = bo_lookup(ri->bo_handle);
    if (!bo) {
        pthread_mutex_unlock(&bo_mutex);
        errno = ENOENT;
        return -1;
    }
    ri->res_handle = bo->res_handle;
    ri->size = bo->size;
    ri->stride = bo->stride;
    pthread_mutex_unlock(&bo_mutex);
    DRM_SHIM_LOGF("resource_info: bo=%u → res_handle=%u size=%u stride=%u", ri->bo_handle, ri->res_handle, ri->size, ri->stride);

    return 0;
}

static int shim_gem_close(void *arg)
{
    struct drm_gem_close *gc = arg;

    pthread_mutex_lock(&bo_mutex);
    struct drm_shim_bo *bo = bo_lookup(gc->handle);
    if (!bo) {
        pthread_mutex_unlock(&bo_mutex);
        return 0;  /* closing unknown handle is not an error */
    }
    uint32_t res_handle = bo->res_handle;
    bool was_ahb = bo->is_ahb;

    /* Cleanup local state */
    if (bo->mmap_ptr && bo->size > 0)
        munmap(bo->mmap_ptr, bo->size);
    if (bo->shm_fd >= 0)
        close(bo->shm_fd);

    /* Mark slot as free */
    memset(bo, 0, sizeof(*bo));
    pthread_mutex_unlock(&bo_mutex);

    /* Tell vtest server to unref — but NOT for AHB resources,
     * because the AHB may be re-imported later and the server-side
     * resource (GL texture backed by EGL image) must stay alive. */
    if (res_handle && !was_ahb) {
        uint32_t payload[1] = { res_handle };
        pthread_mutex_lock(&vtest_mutex);
        if (vtest_sock >= 0) {
            uint32_t hdr[2] = { 1, VCMD_RESOURCE_UNREF };
            vtest_block_write(vtest_sock, hdr, 8);
            vtest_block_write(vtest_sock, payload, 4);
        }
        pthread_mutex_unlock(&vtest_mutex);
    } else if (res_handle && was_ahb) {
        DRM_SHIM_LOGF("gem_close: bo res=%u is AHB, skipping RESOURCE_UNREF", res_handle);
    }

    return 0;
}

static int shim_get_cap_ioctl(void *arg)
{
    struct drm_get_cap *cap = arg;

    switch (cap->capability) {
    case DRM_CAP_PRIME:
        cap->value = DRM_PRIME_CAP_IMPORT | DRM_PRIME_CAP_EXPORT;
        return 0;
    case DRM_CAP_SYNCOBJ:
    case DRM_CAP_SYNCOBJ_TIMELINE:
        cap->value = 1;
        return 0;
#ifdef DRM_CAP_TIMESTAMP_MONOTONIC
    case DRM_CAP_TIMESTAMP_MONOTONIC:
        cap->value = 1;
        return 0;
#endif
    default:
        cap->value = 0;
        return 0;
    }
}

/* Block until a resource is no longer busy on the server side.
 * Must be called with vtest_mutex held. */
static void vtest_busy_wait_for_resource(uint32_t res_handle)
{
    if (vtest_sock < 0 || res_handle == 0)
        return;
    uint32_t payload[2] = { res_handle, VCMD_BUSY_WAIT_FLAG_WAIT };
    uint32_t hdr[2] = { 2, VCMD_RESOURCE_BUSY_WAIT };
    if (vtest_block_write(vtest_sock, hdr, 8) < 0 ||
        vtest_block_write(vtest_sock, payload, 8) < 0)
        return;
    uint32_t resp_hdr[2], busy;
    if (vtest_block_read(vtest_sock, resp_hdr, 8) < 0 ||
        vtest_block_read(vtest_sock, &busy, 4) < 0)
        return;
    DRM_SHIM_LOGF("busy_wait_for_resource: res=%u busy=%u", res_handle, busy);
}

/* Flush GPU pixel data to shm before exporting.
 * Uses data_size > 0 so the server does glReadPixels into a temp buffer
 * and sends pixel data over the socket (instead of writing to resource IOV).
 * Must be called with vtest_mutex held. */
static void vtest_transfer_get_for_export(uint32_t res_handle,
                                           uint32_t width, uint32_t height)
{
    if (vtest_sock < 0 || res_handle == 0)
        return;

    uint32_t payload[10];
    payload[0] = res_handle;
    payload[1] = 0;       /* level */
    payload[2] = 0;       /* x */
    payload[3] = 0;       /* y */
    payload[4] = 0;       /* z */
    payload[5] = width;   /* w */
    payload[6] = height;  /* h */
    payload[7] = 1;       /* d */
    payload[8] = 0;       /* data_size = 0: server writes into shm IOV */
    payload[9] = 0;       /* offset */
    uint32_t hdr[2] = { 10, VCMD_TRANSFER_GET2 };
    if (vtest_block_write(vtest_sock, hdr, 8) < 0 ||
        vtest_block_write(vtest_sock, payload, sizeof(payload)) < 0) {
        DRM_SHIM_LOG("transfer_get_for_export: write failed");
        return;
    }
}

static int shim_prime_handle_to_fd(void *arg)
{
    struct drm_prime_handle *ph = arg;

    pthread_mutex_lock(&bo_mutex);
    struct drm_shim_bo *bo = bo_lookup(ph->handle);
    if (!bo) {
        pthread_mutex_unlock(&bo_mutex);
        errno = ENOENT;
        return -1;
    }

    uint32_t res_handle = bo->res_handle;
    int shm_fd = bo->shm_fd;
    uint32_t w = bo->width;
    uint32_t h = bo->height;
    uint32_t st = bo->stride;
    bool is_ahb = bo->is_ahb;
    pthread_mutex_unlock(&bo_mutex);

    DRM_SHIM_LOGF("prime_h2fd(ioctl): bo=%u res=%u shm_fd=%d is_ahb=%d", ph->handle, res_handle, shm_fd, is_ahb);

    /* AHB-backed: data is in GPU memory, skip TRANSFER_GET2 but still sync */
    if (is_ahb && shm_fd >= 0) {
        DRM_SHIM_LOGF("prime_h2fd(ioctl): bo=%u AHB-backed, skip transfer", ph->handle);
        pthread_mutex_lock(&vtest_mutex);
        vtest_busy_wait_for_resource(res_handle);
        pthread_mutex_unlock(&vtest_mutex);
        ph->fd = dup(shm_fd);
        return (ph->fd >= 0) ? 0 : -1;
    }

    /* Tier 1: flush GPU data via socket-based TRANSFER_GET2 then dup shm_fd */
    if (shm_fd >= 0) {
        DRM_SHIM_LOGF("prime_h2fd(ioctl): bo=%u tier1-shm, flushing %ux%u stride=%u", ph->handle, w, h, st);
        pthread_mutex_lock(&vtest_mutex);
        vtest_transfer_get_for_export(res_handle, w, h);
        vtest_busy_wait_for_resource(res_handle);
        pthread_mutex_unlock(&vtest_mutex);

        /* Diagnostic: check if shm has non-zero data after flush */
        {
            void *probe = mmap(NULL, 4096, PROT_READ, MAP_SHARED, shm_fd, 0);
            if (probe != MAP_FAILED) {
                const uint8_t *p = (const uint8_t *)probe;
                int nonzero = 0;
                for (int i = 0; i < 256; i++) {
                    if (p[i]) nonzero++;
                }
                DRM_SHIM_LOGF("shm_probe: fd=%d first_256_bytes: %d non-zero, first16=[%02x %02x %02x %02x %02x %02x %02x %02x %02x %02x %02x %02x %02x %02x %02x %02x]",
                    shm_fd, nonzero,
                    p[0],p[1],p[2],p[3],p[4],p[5],p[6],p[7],
                    p[8],p[9],p[10],p[11],p[12],p[13],p[14],p[15]);
                munmap(probe, 4096);
            }
        }

        ph->fd = dup(shm_fd);
        return (ph->fd >= 0) ? 0 : -1;
    }

    /* Tier 2 (was tier4): empty memfd placeholder */
    DRM_SHIM_LOGF("prime_h2fd(ioctl): bo=%u tier2-memfd placeholder", ph->handle);
    ph->fd = memfd_create("drm-shim-prime", MFD_CLOEXEC);
    return (ph->fd >= 0) ? 0 : -1;
}

/* Check if any existing BO has the same underlying buffer (same inode).
 * Must be called with bo_mutex held. Returns the existing BO or NULL. */
static struct drm_shim_bo *bo_find_by_inode(int fd)
{
    struct stat import_stat;
    if (fstat(fd, &import_stat) < 0)
        return NULL;

    for (uint32_t i = 1; i < bo_handle_next; i++) {
        if (bo_table[i].bo_handle != 0 && bo_table[i].shm_fd >= 0) {
            struct stat existing_stat;
            if (fstat(bo_table[i].shm_fd, &existing_stat) == 0 &&
                existing_stat.st_dev == import_stat.st_dev &&
                existing_stat.st_ino == import_stat.st_ino) {
                return &bo_table[i];
            }
        }
    }
    return NULL;
}

static int shim_prime_fd_to_handle(void *arg)
{
    struct drm_prime_handle *ph = arg;

    /* Detect AHB dmabuf vs memfd via /proc/self/fd readlink.
     * memfds show as "/memfd:..." while dmabufs show as "anon_inode:dmabuf" or "/dmabuf:..." */
    off_t size = lseek(ph->fd, 0, SEEK_END);
    if (size < 0) size = 0;
    lseek(ph->fd, 0, SEEK_SET);
    bool is_ahb = false;
    {
        char link_path[64];
        char link_target[256];
        snprintf(link_path, sizeof(link_path), "/proc/self/fd/%d", ph->fd);
        ssize_t len = readlink(link_path, link_target, sizeof(link_target) - 1);
        if (len > 0) {
            link_target[len] = '\0';
            is_ahb = (strstr(link_target, "memfd:") == NULL);
            if (is_ahb) {
                DRM_SHIM_LOGF("shim_prime_fd_to_handle: AHB detected (fd=%d -> %s)", ph->fd, link_target);
            }
        }
    }

    pthread_mutex_lock(&bo_mutex);

    /* Dedup: check if we already have a BO for this underlying buffer */
    struct drm_shim_bo *existing = bo_find_by_inode(ph->fd);
    if (existing) {
        ph->handle = existing->bo_handle;
        DRM_SHIM_LOGF("shim_prime_fd_to_handle: dedup hit, reusing bo=%u (is_ahb=%d)",
                       existing->bo_handle, existing->is_ahb);
        pthread_mutex_unlock(&bo_mutex);
        return 0;
    }

    struct drm_shim_bo *bo = bo_alloc();
    if (!bo) {
        pthread_mutex_unlock(&bo_mutex);
        errno = ENOMEM;
        return -1;
    }
    bo->shm_fd = dup(ph->fd);
    bo->size = (uint32_t)size;
    bo->res_handle = 0;   /* imported, no vtest resource */
    bo->is_ahb = is_ahb;
    ph->handle = bo->bo_handle;
    pthread_mutex_unlock(&bo_mutex);

    /* Try to recover res_handle from inode cache (survives gem_close) */
    if (inode_cache_lookup(ph->fd, bo)) {
        DRM_SHIM_LOGF("shim_prime_fd_to_handle: inode_cache hit, restored res=%u for bo=%u",
                       bo->res_handle, bo->bo_handle);
    }

    DRM_SHIM_LOGF("shim_prime_fd_to_handle: new bo=%u size=%u res=%u is_ahb=%d",
                   bo->bo_handle, bo->size, bo->res_handle, bo->is_ahb);
    return 0;
}

/* ------------------------------------------------------------------ */
/*  Device enumeration                                                */
/* ------------------------------------------------------------------ */

/* Allocate a single fake platform device.
 * Caller must free with drmFreeDevice() or free(). */
static drmDevicePtr alloc_fake_device(void)
{
    const char *card_path   = DRM_SHIM_CARD_NODE;
    const char *render_path = DRM_SHIM_RENDER_NODE;
    const char *fullname    = DRM_SHIM_PLATFORM_NAME;
    const char *compat_str  = DRM_SHIM_PLATFORM_NAME;

    size_t card_len   = strlen(card_path) + 1;
    size_t render_len = strlen(render_path) + 1;
    size_t fname_len  = strlen(fullname) + 1;
    size_t compat_len = strlen(compat_str) + 1;

    size_t total = 0;
    size_t off_device     = total; total += sizeof(drmDevice);
    size_t off_nodes      = total; total += sizeof(char *) * DRM_NODE_MAX;
    size_t off_card_str   = total; total += card_len;
    size_t off_render_str = total; total += render_len;
    total = ALIGN_UP(total, _Alignof(drmPlatformBusInfo));
    size_t off_businfo    = total; total += sizeof(drmPlatformBusInfo);
    total = ALIGN_UP(total, _Alignof(drmPlatformDeviceInfo));
    size_t off_devinfo    = total; total += sizeof(drmPlatformDeviceInfo);
    total = ALIGN_UP(total, sizeof(char *));
    size_t off_compat_arr = total; total += sizeof(char *) * 2;
    size_t off_compat_str = total; total += compat_len;

    char *block = calloc(1, total);
    if (!block)
        return NULL;

    drmDevice              *dev   = (drmDevice *)             (block + off_device);
    char                  **nodes = (char **)                 (block + off_nodes);
    char                   *card_s  = block + off_card_str;
    char                   *render_s = block + off_render_str;
    drmPlatformBusInfo     *bus   = (drmPlatformBusInfo *)    (block + off_businfo);
    drmPlatformDeviceInfo  *devi  = (drmPlatformDeviceInfo *) (block + off_devinfo);
    char                  **compat = (char **)                (block + off_compat_arr);
    char                   *comp_s = block + off_compat_str;

    memcpy(card_s,   card_path,   card_len);
    memcpy(render_s, render_path, render_len);

    nodes[DRM_NODE_PRIMARY] = card_s;
    nodes[DRM_NODE_RENDER]  = render_s;

    memcpy(bus->fullname, fullname, fname_len);

    memcpy(comp_s, compat_str, compat_len);
    compat[0] = comp_s;
    compat[1] = NULL;
    devi->compatible = compat;

    dev->available_nodes = (1 << DRM_NODE_RENDER) | (1 << DRM_NODE_PRIMARY);
    dev->nodes           = nodes;
    dev->bustype         = DRM_BUS_PLATFORM;
    dev->businfo.platform = bus;
    dev->deviceinfo.platform = devi;

    return dev;
}

int drmGetDevices2(uint32_t flags, drmDevicePtr devices[], int max_devices)
{
    {
        static const char msg[] = "[drm-shim] drmGetDevices2() called\n";
        ssize_t __attribute__((unused)) r = write(STDERR_FILENO, msg, sizeof(msg) - 1);
    }

    (void)flags;

    /* Count-only query. */
    if (devices == NULL || max_devices <= 0)
        return 1;

    drmDevicePtr dev = alloc_fake_device();
    if (!dev)
        return -ENOMEM;

    devices[0] = dev;

    DRM_SHIM_DEBUG("drmGetDevices2: returning 1 fake device (virtio_gpu)");
    return 1;
}

int drmGetDevices(drmDevicePtr devices[], int max_devices)
{
    return drmGetDevices2(0, devices, max_devices);
}

void drmFreeDevices(drmDevicePtr devices[], int count)
{
    if (!devices)
        return;
    for (int i = 0; i < count; i++) {
        free(devices[i]);
        devices[i] = NULL;
    }
}

int drmGetDevice2(int fd, uint32_t flags, drmDevicePtr *device)
{
    (void)flags;
    if (!device)
        return -EINVAL;

    /* Only return device for tracked DRM fds */
    if (!drm_fd_is_tracked(fd)) {
        *device = NULL;
        return -ENODEV;
    }

    *device = alloc_fake_device();
    if (!*device)
        return -ENOMEM;

    DRM_SHIM_DEBUG("drmGetDevice2: returning fake virtio_gpu platform device");
    return 0;
}

int drmGetDevice(int fd, drmDevicePtr *device)
{
    return drmGetDevice2(fd, 0, device);
}

void drmFreeDevice(drmDevicePtr *device)
{
    if (device && *device) {
        free(*device);
        *device = NULL;
    }
}


/* ------------------------------------------------------------------ */
/*  Version                                                           */
/* ------------------------------------------------------------------ */

drmVersionPtr drmGetVersion(int fd)
{
    (void)fd;

    {
        static const char msg[] = "[drm-shim] drmGetVersion() called\n";
        ssize_t __attribute__((unused)) r = write(STDERR_FILENO, msg, sizeof(msg) - 1);
    }

    drmVersionPtr v = calloc(1, sizeof(drmVersion));
    if (!v)
        return NULL;

    v->version_major      = DRM_SHIM_VERSION_MAJOR;
    v->version_minor      = DRM_SHIM_VERSION_MINOR;
    v->version_patchlevel = DRM_SHIM_VERSION_PATCH;

    v->name = shim_strdup(DRM_SHIM_DRIVER_NAME);
    v->date = shim_strdup(DRM_SHIM_DRIVER_DATE);
    v->desc = shim_strdup(DRM_SHIM_DRIVER_DESC);

    if (!v->name || !v->date || !v->desc) {
        drmFreeVersion(v);
        return NULL;
    }

    v->name_len = (int)strlen(DRM_SHIM_DRIVER_NAME);
    v->date_len = (int)strlen(DRM_SHIM_DRIVER_DATE);
    v->desc_len = (int)strlen(DRM_SHIM_DRIVER_DESC);

    DRM_SHIM_DEBUG("drmGetVersion: virtio_gpu 0.1.0");
    return v;
}

void drmFreeVersion(drmVersionPtr v)
{
    if (!v)
        return;
    free(v->name);
    free(v->date);
    free(v->desc);
    free(v);
}

/* ------------------------------------------------------------------ */
/*  Capabilities (libdrm API)                                         */
/* ------------------------------------------------------------------ */
/*
 * shim_version_ioctl — handle raw ioctl(fd, DRM_IOCTL_VERSION, &version).
 *
 * Unlike drmGetVersion() (which allocates a fresh struct), the raw ioctl
 * receives a pre-allocated struct drm_version from the caller (e.g. GBM).
 * We must fill the numeric fields and copy strings into the caller's buffers.
 * The kernel convention: set *_len to the actual string length regardless of
 * whether the buffer was large enough.
 */
static int shim_version_ioctl(void *arg)
{
    struct drm_version *v = arg;

    const char *name = DRM_SHIM_DRIVER_NAME;
    const char *date = DRM_SHIM_DRIVER_DATE;
    const char *desc = DRM_SHIM_DRIVER_DESC;

    size_t name_len = strlen(name);
    size_t date_len = strlen(date);
    size_t desc_len = strlen(desc);

    v->version_major      = DRM_SHIM_VERSION_MAJOR;
    v->version_minor      = DRM_SHIM_VERSION_MINOR;
    v->version_patchlevel = DRM_SHIM_VERSION_PATCH;

    /* Copy strings if caller provided buffers; set lengths always */
    if (v->name && v->name_len > 0) {
        size_t copy = v->name_len < name_len ? v->name_len : name_len;
        memcpy(v->name, name, copy);
    }
    v->name_len = name_len;

    if (v->date && v->date_len > 0) {
        size_t copy = v->date_len < date_len ? v->date_len : date_len;
        memcpy(v->date, date, copy);
    }
    v->date_len = date_len;

    if (v->desc && v->desc_len > 0) {
        size_t copy = v->desc_len < desc_len ? v->desc_len : desc_len;
        memcpy(v->desc, desc, copy);
    }
    v->desc_len = desc_len;

    {
        static const char msg[] = "[drm-shim] DRM_IOCTL_VERSION via raw ioctl() -> virtio_gpu\n";
        ssize_t __attribute__((unused)) r = write(STDERR_FILENO, msg, sizeof(msg) - 1);
    }

    return 0;
}



int drmGetCap(int fd, uint64_t capability, uint64_t *value)
{
    (void)fd;

    {
        static int first = 1;
        if (first) {
            first = 0;
            static const char msg[] = "[drm-shim] drmGetCap() first call\n";
            ssize_t __attribute__((unused)) r = write(STDERR_FILENO, msg, sizeof(msg) - 1);
        }
    }

    if (!value)
        return -EINVAL;

    switch (capability) {
    case DRM_CAP_PRIME:
        *value = DRM_PRIME_CAP_IMPORT | DRM_PRIME_CAP_EXPORT;
        return 0;
    case DRM_CAP_SYNCOBJ:
    case DRM_CAP_SYNCOBJ_TIMELINE:
    case DRM_CAP_DUMB_BUFFER:
        *value = 1;
        return 0;
#ifdef DRM_CAP_TIMESTAMP_MONOTONIC
    case DRM_CAP_TIMESTAMP_MONOTONIC:
        *value = 1;
        return 0;
#endif
    default:
        return -EINVAL;
    }
}

/* ------------------------------------------------------------------ */
/*  Authentication                                                    */
/* ------------------------------------------------------------------ */

int drmGetMagic(int fd, drm_magic_t *magic)
{
    (void)fd;

    if (!magic)
        return -EINVAL;

    *magic = 0x434F4445; /* "CODE" in ASCII */
    return 0;
}

int drmAuthMagic(int fd, drm_magic_t magic)
{
    (void)fd;
    (void)magic;
    return 0;
}

/* ------------------------------------------------------------------ */
/*  PRIME fd/handle exchange (libdrm API)                             */
/* ------------------------------------------------------------------ */

int drmPrimeHandleToFD(int fd, uint32_t handle, uint32_t flags, int *prime_fd)
{
    (void)fd;
    (void)flags;

    if (!prime_fd)
        return -EINVAL;

    pthread_mutex_lock(&bo_mutex);
    struct drm_shim_bo *bo = bo_lookup(handle);
    if (!bo) {
        pthread_mutex_unlock(&bo_mutex);
        return -ENOENT;
    }

    uint32_t res_handle = bo->res_handle;
    int shm_fd = bo->shm_fd;
    uint32_t w = bo->width;
    uint32_t h = bo->height;
    uint32_t st = bo->stride;
    bool is_ahb = bo->is_ahb;
    pthread_mutex_unlock(&bo_mutex);

    DRM_SHIM_LOGF("drmPrimeHandleToFD: bo=%u res=%u shm_fd=%d is_ahb=%d", handle, res_handle, shm_fd, is_ahb);

    /* AHB-backed: data is already in GPU memory, skip TRANSFER_GET2 but still sync */
    if (is_ahb && shm_fd >= 0) {
        DRM_SHIM_LOGF("drmPrimeHandleToFD: bo=%u AHB-backed, skip transfer", handle);
        pthread_mutex_lock(&vtest_mutex);
        vtest_busy_wait_for_resource(res_handle);
        pthread_mutex_unlock(&vtest_mutex);
        *prime_fd = dup(shm_fd);
        return (*prime_fd >= 0) ? 0 : -errno;
    }

    /* Tier 1: Flush GPU data via socket-based TRANSFER_GET2 then dup shm_fd */
    if (shm_fd >= 0) {
        DRM_SHIM_LOGF("drmPrimeHandleToFD: bo=%u tier1-shm, flushing %ux%u stride=%u", handle, w, h, st);
        pthread_mutex_lock(&vtest_mutex);
        vtest_transfer_get_for_export(res_handle, w, h);
        vtest_busy_wait_for_resource(res_handle);
        pthread_mutex_unlock(&vtest_mutex);

        /* Diagnostic: check if shm has non-zero data after flush */
        {
            void *probe = mmap(NULL, 4096, PROT_READ, MAP_SHARED, shm_fd, 0);
            if (probe != MAP_FAILED) {
                const uint8_t *p = (const uint8_t *)probe;
                int nonzero = 0;
                for (int i = 0; i < 256; i++) {
                    if (p[i]) nonzero++;
                }
                DRM_SHIM_LOGF("shm_probe: fd=%d first_256_bytes: %d non-zero, first16=[%02x %02x %02x %02x %02x %02x %02x %02x %02x %02x %02x %02x %02x %02x %02x %02x]",
                    shm_fd, nonzero,
                    p[0],p[1],p[2],p[3],p[4],p[5],p[6],p[7],
                    p[8],p[9],p[10],p[11],p[12],p[13],p[14],p[15]);
                munmap(probe, 4096);
            }
        }

        *prime_fd = dup(shm_fd);
        return (*prime_fd >= 0) ? 0 : -errno;
    }

    /* Tier 2: empty memfd placeholder */
    DRM_SHIM_LOGF("drmPrimeHandleToFD: bo=%u tier2-memfd placeholder", handle);
    *prime_fd = memfd_create("drm-shim-prime", MFD_CLOEXEC);
    return (*prime_fd >= 0) ? 0 : -errno;
}

int drmPrimeFDToHandle(int fd, int prime_fd, uint32_t *handle)
{
    (void)fd;

    if (!handle)
        return -EINVAL;

    /* Detect AHB dmabuf vs memfd via /proc/self/fd readlink.
     * memfds show as "/memfd:..." while dmabufs show as "anon_inode:dmabuf" or "/dmabuf:..." */
    off_t size = lseek(prime_fd, 0, SEEK_END);
    if (size < 0) size = 0;
    lseek(prime_fd, 0, SEEK_SET);
    bool is_ahb = false;
    {
        char link_path[64];
        char link_target[256];
        snprintf(link_path, sizeof(link_path), "/proc/self/fd/%d", prime_fd);
        ssize_t len = readlink(link_path, link_target, sizeof(link_target) - 1);
        if (len > 0) {
            link_target[len] = '\0';
            is_ahb = (strstr(link_target, "memfd:") == NULL);
            if (is_ahb) {
                DRM_SHIM_LOGF("drmPrimeFDToHandle: AHB detected (fd=%d -> %s)", prime_fd, link_target);
            }
        }
    }

    pthread_mutex_lock(&bo_mutex);

    /* Dedup: check if we already have a BO for this underlying buffer */
    struct drm_shim_bo *existing = bo_find_by_inode(prime_fd);
    if (existing) {
        *handle = existing->bo_handle;
        DRM_SHIM_LOGF("drmPrimeFDToHandle: dedup hit, reusing bo=%u (is_ahb=%d)",
                       existing->bo_handle, existing->is_ahb);
        pthread_mutex_unlock(&bo_mutex);
        return 0;
    }

    struct drm_shim_bo *bo = bo_alloc();
    if (!bo) {
        pthread_mutex_unlock(&bo_mutex);
        return -ENOMEM;
    }
    bo->shm_fd = dup(prime_fd);
    bo->size = (uint32_t)size;
    bo->res_handle = 0;  /* imported, no vtest resource */
    bo->is_ahb = is_ahb;
    *handle = bo->bo_handle;
    pthread_mutex_unlock(&bo_mutex);

    /* Try to recover res_handle from inode cache (survives gem_close) */
    if (inode_cache_lookup(prime_fd, bo)) {
        DRM_SHIM_LOGF("drmPrimeFDToHandle: inode_cache hit, restored res=%u for bo=%u",
                       bo->res_handle, bo->bo_handle);
    }

    DRM_SHIM_LOGF("drmPrimeFDToHandle: new bo=%u size=%u res=%u is_ahb=%d",
                   bo->bo_handle, bo->size, bo->res_handle, bo->is_ahb);
    return 0;
}

/* ------------------------------------------------------------------ */
/*  Bus ID and node helpers                                           */
/* ------------------------------------------------------------------ */

char *drmGetBusid(int fd)
{
    (void)fd;
    return strdup(DRM_SHIM_BUS_ID);
}

void drmFreeBusid(const char *busid)
{
    free((void *)busid);
}

int drmGetNodeTypeFromFd(int fd)
{
    (void)fd;
    return DRM_NODE_RENDER;
}

char *drmGetRenderDeviceNameFromFd(int fd)
{
    (void)fd;
    return strdup(DRM_SHIM_RENDER_NODE);
}

char *drmGetDeviceNameFromFd2(int fd)
{
    (void)fd;
    return strdup(DRM_SHIM_RENDER_NODE);
}

/* ------------------------------------------------------------------ */
/*  Generic ioctl dispatch and close                                  */
/* ------------------------------------------------------------------ */

int drmIoctl(int fd, unsigned long request, void *arg)
{
    {
        static int first = 1;
        if (first) {
            first = 0;
            static const char msg[] = "[drm-shim] drmIoctl() first call\n";
            ssize_t __attribute__((unused)) r = write(STDERR_FILENO, msg, sizeof(msg) - 1);
        }
    }

    /* The openat interceptor now handles all fd tracking — no tracking here */

    switch (request) {
    case DRM_IOCTL_VIRTGPU_GETPARAM:         return shim_getparam(arg);
    case DRM_IOCTL_VIRTGPU_GET_CAPS:         return shim_get_caps(arg);
    case DRM_IOCTL_VIRTGPU_RESOURCE_CREATE:  return shim_resource_create(arg);
    case DRM_IOCTL_VIRTGPU_MAP:              return shim_map(arg);
    case DRM_IOCTL_VIRTGPU_EXECBUFFER:       return shim_execbuffer(arg);
    case DRM_IOCTL_VIRTGPU_TRANSFER_TO_HOST: return shim_transfer_to_host(arg);
    case DRM_IOCTL_VIRTGPU_TRANSFER_FROM_HOST: return shim_transfer_from_host(arg);
    case DRM_IOCTL_VIRTGPU_WAIT:             return shim_wait(arg);
    case DRM_IOCTL_VIRTGPU_RESOURCE_INFO:    return shim_resource_info(arg);
    case DRM_IOCTL_GEM_CLOSE:               return shim_gem_close(arg);
    case DRM_IOCTL_GET_CAP:                 return shim_get_cap_ioctl(arg);
    case DRM_IOCTL_PRIME_HANDLE_TO_FD:      return shim_prime_handle_to_fd(arg);
    case DRM_IOCTL_PRIME_FD_TO_HANDLE:      return shim_prime_fd_to_handle(arg);
    case DRM_IOCTL_VERSION:                 return shim_version_ioctl(arg);
    case DRM_IOCTL_SET_VERSION:             return 0;
    case DRM_IOCTL_SET_CLIENT_CAP:          return 0;
    case DRM_IOCTL_GEM_OPEN:
    case DRM_IOCTL_GEM_FLINK:
        errno = ENOSYS;
        return -1;
    default:
        DRM_SHIM_DEBUG("drmIoctl: unhandled request, returning 0");
        return 0;
    }
}

int drmClose(int fd)
{
    drm_fd_remove(fd);
    return close(fd);
}

/* ------------------------------------------------------------------ */
/*  mmap interception                                                 */
/* ------------------------------------------------------------------ */

void *mmap(void *addr, size_t length, int prot, int flags, int fd, off_t offset)
{
    /* Safety check — real_mmap is normally set in the constructor */
    if (!real_mmap) {
        real_mmap = (real_mmap_fn)SHIM_DLSYM(RTLD_NEXT, "mmap");
        if (!real_mmap)
            _exit(99);
    }

    /* Check if this is a drm-shim resource mmap */
    if (drm_fd_is_tracked(fd) && offset >= (off_t)MMAP_OFFSET_BASE) {

        pthread_mutex_lock(&bo_mutex);
        struct drm_shim_bo *bo = bo_lookup_by_offset((uint64_t)offset);
        if (bo && bo->shm_fd >= 0) {
            DRM_SHIM_LOGF("mmap: bo=%u shm_fd=%d len=%zu off=%ld",
                          bo->bo_handle, bo->shm_fd, length, (long)offset);
            /* dup() the shm_fd to avoid TOCTOU race with concurrent gem_close */
            int shm_fd = dup(bo->shm_fd);
            pthread_mutex_unlock(&bo_mutex);

            if (shm_fd < 0)
                return real_mmap(addr, length, prot, flags, fd, offset);

            /* Map the dup'd shm fd instead (offset 0) */
            void *ptr = real_mmap(addr, length, prot, flags, shm_fd, 0);
            close(shm_fd);
            if (ptr != MAP_FAILED) {
                pthread_mutex_lock(&bo_mutex);
                /* Re-lookup in case table changed */
                bo = bo_lookup_by_offset((uint64_t)offset);
                if (bo)
                    bo->mmap_ptr = ptr;
                pthread_mutex_unlock(&bo_mutex);
            }
            return ptr;
        }
        pthread_mutex_unlock(&bo_mutex);
    }

    /* Not our fd — pass through */
    return real_mmap(addr, length, prot, flags, fd, offset);
}

/* Also intercept mmap64 (same implementation on 64-bit) */
void *mmap64(void *addr, size_t length, int prot, int flags, int fd, off64_t offset)
    __attribute__((alias("mmap")));

/* ------------------------------------------------------------------ */
/*  openat interception — track DRM fd before any ioctl               */
/*  Modern glibc implements open() as openat(AT_FDCWD, ...) internally*/
/*  so we must intercept openat() to reliably capture the DRM fd.     */
/* ------------------------------------------------------------------ */

int openat(int dirfd, const char *pathname, int flags, ...)
{
    /* Lazy-load real openat */
    if (!real_openat) {
        real_openat = (real_openat_fn)SHIM_DLSYM(RTLD_NEXT, "openat");
        if (!real_openat)
            _exit(99);
    }

    /* Extract optional mode_t argument (required for O_CREAT and O_TMPFILE) */
    mode_t mode = 0;
    if (flags & (O_CREAT
#ifdef __O_TMPFILE
                 | __O_TMPFILE
#endif
                )) {
        va_list ap;
        va_start(ap, flags);
        mode = (mode_t)va_arg(ap, int);
        va_end(ap);
    }

    int fd = real_openat(dirfd, pathname, flags, mode);

    /* Track if this is our DRM render node or card node */
#pragma GCC diagnostic push
#pragma GCC diagnostic ignored "-Wnonnull-compare"
    if (fd >= 0 && pathname) {
#pragma GCC diagnostic pop
        if (strcmp(pathname, "/dev/dri/renderD128") == 0 ||
            strcmp(pathname, "/dev/dri/card0") == 0) {
            drm_fd_add(fd);
            {
                /* Log with fd number using write()-safe int conversion */
                static const char prefix[] = "[drm-shim] Tracked DRM fd=";
                static const char suffix[] = " via openat()\n";
                char buf[64];
                int n = 0;
                /* Copy prefix */
                for (int i = 0; prefix[i]; i++)
                    buf[n++] = prefix[i];
                /* Convert fd to decimal */
                char digits[12];
                int dpos = 0;
                int tmp = fd;
                if (tmp == 0) {
                    digits[dpos++] = '0';
                } else {
                    while (tmp > 0) {
                        digits[dpos++] = '0' + (tmp % 10);
                        tmp /= 10;
                    }
                }
                while (dpos > 0)
                    buf[n++] = digits[--dpos];
                /* Copy suffix */
                for (int i = 0; suffix[i]; i++)
                    buf[n++] = suffix[i];
                ssize_t __attribute__((unused)) r =
                    write(STDERR_FILENO, buf, n);
            }
        }
    }

    return fd;
}

int openat64(int dirfd, const char *pathname, int flags, ...)
    __attribute__((alias("openat")));

/* open() delegates to openat(AT_FDCWD, ...) so all paths converge. */
int open(const char *pathname, int flags, ...)
{
    mode_t mode = 0;
    if (flags & (O_CREAT
#ifdef __O_TMPFILE
                 | __O_TMPFILE
#endif
                )) {
        va_list ap;
        va_start(ap, flags);
        mode = (mode_t)va_arg(ap, int);
        va_end(ap);
    }
    return openat(AT_FDCWD, pathname, flags, mode);
}

int open64(const char *pathname, int flags, ...)
    __attribute__((alias("open")));

/* ------------------------------------------------------------------ */
/*  stat interception — fake DRM device nodes as char devices         */
/*                                                                    */
/*  We intercept ALL stat entry points because glibc internal routing */
/*  varies across versions and architectures.  On aarch64 LP64,      */
/*  struct stat == struct stat64, so we cast freely.                  */
/*                                                                    */
/*  Each interceptor logs "first time called" + "faked" messages to   */
/*  help diagnose which entry point GBM/Mesa actually uses.           */
/* ------------------------------------------------------------------ */

static void drm_fake_stat_result(struct stat *buf)
{
    buf->st_mode = (buf->st_mode & ~S_IFMT) | S_IFCHR;
    buf->st_rdev = makedev(226, 128);
}

static int is_drm_path(const char *pathname)
{
    return pathname &&
           (strcmp(pathname, "/dev/dri/renderD128") == 0 ||
            strcmp(pathname, "/dev/dri/card0") == 0);
}

static int is_drm_fd(int fd)
{
    return drm_fd_is_tracked(fd);
}

/* ---------- fstatat (dirfd + path, modern glibc primary) ---------- */

int fstatat(int dirfd, const char *pathname, struct stat *buf, int flags)
{
    static int first = 0;
    if (!__atomic_exchange_n(&first, 1, __ATOMIC_RELAXED)) {
        static const char msg[] = "[drm-shim] fstatat called (first time)\n";
        ssize_t __attribute__((unused)) r = write(STDERR_FILENO, msg, sizeof(msg) - 1);
    }

    if (!real_fstatat) {
        real_fstatat = (real_fstatat_fn)SHIM_DLSYM(RTLD_NEXT, "fstatat");
        if (!real_fstatat) { errno = ENOSYS; return -1; }
    }

    int ret = real_fstatat(dirfd, pathname, buf, flags);
    if (ret != 0)
        return ret;

    if (is_drm_path(pathname)) {
        drm_fake_stat_result(buf);
        static const char msg[] =
            "[drm-shim] fstatat: faked DRM path as char device\n";
        ssize_t __attribute__((unused)) r = write(STDERR_FILENO, msg, sizeof(msg) - 1);
        return 0;
    }

    if (is_drm_fd(dirfd) && pathname && pathname[0] == '\0' &&
        (flags & AT_EMPTY_PATH)) {
        drm_fake_stat_result(buf);
        static const char msg[] =
            "[drm-shim] fstatat: faked DRM fd (AT_EMPTY_PATH)\n";
        ssize_t __attribute__((unused)) r = write(STDERR_FILENO, msg, sizeof(msg) - 1);
        return 0;
    }

    return ret;
}

/* ---------- fstatat64 ---------- */

int fstatat64(int dirfd, const char *pathname, struct stat64 *buf, int flags)
{
    static int first = 0;
    if (!__atomic_exchange_n(&first, 1, __ATOMIC_RELAXED)) {
        static const char msg[] = "[drm-shim] fstatat64 called (first time)\n";
        ssize_t __attribute__((unused)) r = write(STDERR_FILENO, msg, sizeof(msg) - 1);
    }

    typedef int (*fn_t)(int, const char *, struct stat64 *, int);
    static fn_t real_fn = NULL;
    if (!real_fn) { real_fn = (fn_t)SHIM_DLSYM(RTLD_NEXT, "fstatat64"); if (!real_fn) { errno = ENOSYS; return -1; } }

    int ret = real_fn(dirfd, pathname, buf, flags);
    if (ret != 0) return ret;

    if (is_drm_path(pathname)) {
        drm_fake_stat_result((struct stat *)buf);
        static const char msg[] = "[drm-shim] fstatat64: faked DRM path\n";
        ssize_t __attribute__((unused)) r = write(STDERR_FILENO, msg, sizeof(msg) - 1);
        return 0;
    }
    if (is_drm_fd(dirfd) && pathname && pathname[0] == '\0' && (flags & AT_EMPTY_PATH)) {
        drm_fake_stat_result((struct stat *)buf);
        static const char msg[] = "[drm-shim] fstatat64: faked DRM fd\n";
        ssize_t __attribute__((unused)) r = write(STDERR_FILENO, msg, sizeof(msg) - 1);
        return 0;
    }
    return ret;
}

/* ---------- stat (path-based) ---------- */

int stat(const char *pathname, struct stat *buf)
{
    static int first = 0;
    if (!__atomic_exchange_n(&first, 1, __ATOMIC_RELAXED)) {
        static const char msg[] = "[drm-shim] stat called (first time)\n";
        ssize_t __attribute__((unused)) r = write(STDERR_FILENO, msg, sizeof(msg) - 1);
    }

    typedef int (*fn_t)(const char *, struct stat *);
    static fn_t real_fn = NULL;
    if (!real_fn) { real_fn = (fn_t)SHIM_DLSYM(RTLD_NEXT, "stat"); if (!real_fn) { errno = ENOSYS; return -1; } }

    int ret = real_fn(pathname, buf);
    if (ret == 0 && is_drm_path(pathname)) {
        drm_fake_stat_result(buf);
        static const char msg[] = "[drm-shim] stat: faked DRM path\n";
        ssize_t __attribute__((unused)) r = write(STDERR_FILENO, msg, sizeof(msg) - 1);
    }
    return ret;
}

/* ---------- lstat (path-based, follows symlinks differently) ---------- */

int lstat(const char *pathname, struct stat *buf)
{
    static int first = 0;
    if (!__atomic_exchange_n(&first, 1, __ATOMIC_RELAXED)) {
        static const char msg[] = "[drm-shim] lstat called (first time)\n";
        ssize_t __attribute__((unused)) r = write(STDERR_FILENO, msg, sizeof(msg) - 1);
    }

    typedef int (*fn_t)(const char *, struct stat *);
    static fn_t real_fn = NULL;
    if (!real_fn) { real_fn = (fn_t)SHIM_DLSYM(RTLD_NEXT, "lstat"); if (!real_fn) { errno = ENOSYS; return -1; } }

    int ret = real_fn(pathname, buf);
    if (ret == 0 && is_drm_path(pathname)) {
        drm_fake_stat_result(buf);
        static const char msg[] = "[drm-shim] lstat: faked DRM path\n";
        ssize_t __attribute__((unused)) r = write(STDERR_FILENO, msg, sizeof(msg) - 1);
    }
    return ret;
}

/* ---------- stat64 ---------- */

int stat64(const char *pathname, struct stat64 *buf)
{
    static int first = 0;
    if (!__atomic_exchange_n(&first, 1, __ATOMIC_RELAXED)) {
        static const char msg[] = "[drm-shim] stat64 called (first time)\n";
        ssize_t __attribute__((unused)) r = write(STDERR_FILENO, msg, sizeof(msg) - 1);
    }

    typedef int (*fn_t)(const char *, struct stat64 *);
    static fn_t real_fn = NULL;
    if (!real_fn) { real_fn = (fn_t)SHIM_DLSYM(RTLD_NEXT, "stat64"); if (!real_fn) { errno = ENOSYS; return -1; } }

    int ret = real_fn(pathname, buf);
    if (ret == 0 && is_drm_path(pathname)) {
        drm_fake_stat_result((struct stat *)buf);
        static const char msg[] = "[drm-shim] stat64: faked DRM path\n";
        ssize_t __attribute__((unused)) r = write(STDERR_FILENO, msg, sizeof(msg) - 1);
    }
    return ret;
}

/* ---------- lstat64 ---------- */

int lstat64(const char *pathname, struct stat64 *buf)
{
    static int first = 0;
    if (!__atomic_exchange_n(&first, 1, __ATOMIC_RELAXED)) {
        static const char msg[] = "[drm-shim] lstat64 called (first time)\n";
        ssize_t __attribute__((unused)) r = write(STDERR_FILENO, msg, sizeof(msg) - 1);
    }

    typedef int (*fn_t)(const char *, struct stat64 *);
    static fn_t real_fn = NULL;
    if (!real_fn) { real_fn = (fn_t)SHIM_DLSYM(RTLD_NEXT, "lstat64"); if (!real_fn) { errno = ENOSYS; return -1; } }

    int ret = real_fn(pathname, buf);
    if (ret == 0 && is_drm_path(pathname)) {
        drm_fake_stat_result((struct stat *)buf);
        static const char msg[] = "[drm-shim] lstat64: faked DRM path\n";
        ssize_t __attribute__((unused)) r = write(STDERR_FILENO, msg, sizeof(msg) - 1);
    }
    return ret;
}

/* ---------- fstat (fd-based) ---------- */

int fstat(int fd, struct stat *buf)
{
    static int first = 0;
    if (!__atomic_exchange_n(&first, 1, __ATOMIC_RELAXED)) {
        static const char msg[] = "[drm-shim] fstat called (first time)\n";
        ssize_t __attribute__((unused)) r = write(STDERR_FILENO, msg, sizeof(msg) - 1);
    }

    typedef int (*fn_t)(int, struct stat *);
    static fn_t real_fn = NULL;
    if (!real_fn) { real_fn = (fn_t)SHIM_DLSYM(RTLD_NEXT, "fstat"); if (!real_fn) { errno = ENOSYS; return -1; } }

    int ret = real_fn(fd, buf);
    if (ret == 0 && is_drm_fd(fd)) {
        drm_fake_stat_result(buf);
        static const char msg[] = "[drm-shim] fstat: faked DRM fd\n";
        ssize_t __attribute__((unused)) r = write(STDERR_FILENO, msg, sizeof(msg) - 1);
    }
    return ret;
}

/* ---------- fstat64 ---------- */

int fstat64(int fd, struct stat64 *buf)
{
    static int first = 0;
    if (!__atomic_exchange_n(&first, 1, __ATOMIC_RELAXED)) {
        static const char msg[] = "[drm-shim] fstat64 called (first time)\n";
        ssize_t __attribute__((unused)) r = write(STDERR_FILENO, msg, sizeof(msg) - 1);
    }

    typedef int (*fn_t)(int, struct stat64 *);
    static fn_t real_fn = NULL;
    if (!real_fn) { real_fn = (fn_t)SHIM_DLSYM(RTLD_NEXT, "fstat64"); if (!real_fn) { errno = ENOSYS; return -1; } }

    int ret = real_fn(fd, buf);
    if (ret == 0 && is_drm_fd(fd)) {
        drm_fake_stat_result((struct stat *)buf);
        static const char msg[] = "[drm-shim] fstat64: faked DRM fd\n";
        ssize_t __attribute__((unused)) r = write(STDERR_FILENO, msg, sizeof(msg) - 1);
    }
    return ret;
}

/* ------------------------------------------------------------------ */
/*  Legacy glibc versioned stat wrappers (__xstat family)             */
/*  On glibc <2.33, stat() etc. are thin wrappers around these.      */
/*  On glibc >=2.33 these may not exist; dlsym returns NULL.         */
/* ------------------------------------------------------------------ */

/* ---------- __xstat (path-based, versioned) ---------- */

int __xstat(int ver, const char *pathname, struct stat *buf)
{
    static int first = 0;
    if (!__atomic_exchange_n(&first, 1, __ATOMIC_RELAXED)) {
        static const char msg[] = "[drm-shim] __xstat called (first time)\n";
        ssize_t __attribute__((unused)) r = write(STDERR_FILENO, msg, sizeof(msg) - 1);
    }

    typedef int (*fn_t)(int, const char *, struct stat *);
    static fn_t real_fn = NULL;
    if (!real_fn) { real_fn = (fn_t)SHIM_DLSYM(RTLD_NEXT, "__xstat"); if (!real_fn) { errno = ENOSYS; return -1; } }

    int ret = real_fn(ver, pathname, buf);
    if (ret == 0 && is_drm_path(pathname)) {
        drm_fake_stat_result(buf);
        static const char msg[] = "[drm-shim] __xstat: faked DRM path\n";
        ssize_t __attribute__((unused)) r = write(STDERR_FILENO, msg, sizeof(msg) - 1);
    }
    return ret;
}

/* ---------- __lxstat ---------- */

int __lxstat(int ver, const char *pathname, struct stat *buf)
{
    static int first = 0;
    if (!__atomic_exchange_n(&first, 1, __ATOMIC_RELAXED)) {
        static const char msg[] = "[drm-shim] __lxstat called (first time)\n";
        ssize_t __attribute__((unused)) r = write(STDERR_FILENO, msg, sizeof(msg) - 1);
    }

    typedef int (*fn_t)(int, const char *, struct stat *);
    static fn_t real_fn = NULL;
    if (!real_fn) { real_fn = (fn_t)SHIM_DLSYM(RTLD_NEXT, "__lxstat"); if (!real_fn) { errno = ENOSYS; return -1; } }

    int ret = real_fn(ver, pathname, buf);
    if (ret == 0 && is_drm_path(pathname)) {
        drm_fake_stat_result(buf);
        static const char msg[] = "[drm-shim] __lxstat: faked DRM path\n";
        ssize_t __attribute__((unused)) r = write(STDERR_FILENO, msg, sizeof(msg) - 1);
    }
    return ret;
}

/* ---------- __fxstat (fd-based, versioned) ---------- */

int __fxstat(int ver, int fd, struct stat *buf)
{
    static int first = 0;
    if (!__atomic_exchange_n(&first, 1, __ATOMIC_RELAXED)) {
        static const char msg[] = "[drm-shim] __fxstat called (first time)\n";
        ssize_t __attribute__((unused)) r = write(STDERR_FILENO, msg, sizeof(msg) - 1);
    }

    typedef int (*fn_t)(int, int, struct stat *);
    static fn_t real_fn = NULL;
    if (!real_fn) { real_fn = (fn_t)SHIM_DLSYM(RTLD_NEXT, "__fxstat"); if (!real_fn) { errno = ENOSYS; return -1; } }

    int ret = real_fn(ver, fd, buf);
    if (ret == 0 && is_drm_fd(fd)) {
        drm_fake_stat_result(buf);
        static const char msg[] = "[drm-shim] __fxstat: faked DRM fd\n";
        ssize_t __attribute__((unused)) r = write(STDERR_FILENO, msg, sizeof(msg) - 1);
    }
    return ret;
}

/* ---------- __xstat64 ---------- */

int __xstat64(int ver, const char *pathname, struct stat *buf)
{
    static int first = 0;
    if (!__atomic_exchange_n(&first, 1, __ATOMIC_RELAXED)) {
        static const char msg[] = "[drm-shim] __xstat64 called (first time)\n";
        ssize_t __attribute__((unused)) r = write(STDERR_FILENO, msg, sizeof(msg) - 1);
    }

    typedef int (*fn_t)(int, const char *, struct stat *);
    static fn_t real_fn = NULL;
    if (!real_fn) { real_fn = (fn_t)SHIM_DLSYM(RTLD_NEXT, "__xstat64"); if (!real_fn) { errno = ENOSYS; return -1; } }

    int ret = real_fn(ver, pathname, buf);
    if (ret == 0 && is_drm_path(pathname)) {
        drm_fake_stat_result(buf);
        static const char msg[] = "[drm-shim] __xstat64: faked DRM path\n";
        ssize_t __attribute__((unused)) r = write(STDERR_FILENO, msg, sizeof(msg) - 1);
    }
    return ret;
}

/* ---------- __lxstat64 ---------- */

int __lxstat64(int ver, const char *pathname, struct stat *buf)
{
    static int first = 0;
    if (!__atomic_exchange_n(&first, 1, __ATOMIC_RELAXED)) {
        static const char msg[] = "[drm-shim] __lxstat64 called (first time)\n";
        ssize_t __attribute__((unused)) r = write(STDERR_FILENO, msg, sizeof(msg) - 1);
    }

    typedef int (*fn_t)(int, const char *, struct stat *);
    static fn_t real_fn = NULL;
    if (!real_fn) { real_fn = (fn_t)SHIM_DLSYM(RTLD_NEXT, "__lxstat64"); if (!real_fn) { errno = ENOSYS; return -1; } }

    int ret = real_fn(ver, pathname, buf);
    if (ret == 0 && is_drm_path(pathname)) {
        drm_fake_stat_result(buf);
        static const char msg[] = "[drm-shim] __lxstat64: faked DRM path\n";
        ssize_t __attribute__((unused)) r = write(STDERR_FILENO, msg, sizeof(msg) - 1);
    }
    return ret;
}

/* ---------- __fxstat64 (fd-based, versioned 64-bit) ---------- */

int __fxstat64(int ver, int fd, struct stat *buf)
{
    static int first = 0;
    if (!__atomic_exchange_n(&first, 1, __ATOMIC_RELAXED)) {
        static const char msg[] = "[drm-shim] __fxstat64 called (first time)\n";
        ssize_t __attribute__((unused)) r = write(STDERR_FILENO, msg, sizeof(msg) - 1);
    }

    typedef int (*fn_t)(int, int, struct stat *);
    static fn_t real_fn = NULL;
    if (!real_fn) { real_fn = (fn_t)SHIM_DLSYM(RTLD_NEXT, "__fxstat64"); if (!real_fn) { errno = ENOSYS; return -1; } }

    int ret = real_fn(ver, fd, buf);
    if (ret == 0 && is_drm_fd(fd)) {
        drm_fake_stat_result(buf);
        static const char msg[] = "[drm-shim] __fxstat64: faked DRM fd\n";
        ssize_t __attribute__((unused)) r = write(STDERR_FILENO, msg, sizeof(msg) - 1);
    }
    return ret;
}

/* ---------- __fxstatat (dirfd+path, versioned) ---------- */

int __fxstatat(int ver, int dirfd, const char *pathname,
               struct stat *buf, int flags)
{
    static int first = 0;
    if (!__atomic_exchange_n(&first, 1, __ATOMIC_RELAXED)) {
        static const char msg[] = "[drm-shim] __fxstatat called (first time)\n";
        ssize_t __attribute__((unused)) r = write(STDERR_FILENO, msg, sizeof(msg) - 1);
    }

    typedef int (*fn_t)(int, int, const char *, struct stat *, int);
    static fn_t real_fn = NULL;
    if (!real_fn) { real_fn = (fn_t)SHIM_DLSYM(RTLD_NEXT, "__fxstatat"); if (!real_fn) { errno = ENOSYS; return -1; } }

    int ret = real_fn(ver, dirfd, pathname, buf, flags);
    if (ret != 0) return ret;

    if (is_drm_path(pathname)) {
        drm_fake_stat_result(buf);
        static const char msg[] = "[drm-shim] __fxstatat: faked DRM path\n";
        ssize_t __attribute__((unused)) r = write(STDERR_FILENO, msg, sizeof(msg) - 1);
        return 0;
    }
    if (is_drm_fd(dirfd) && pathname && pathname[0] == '\0' && (flags & AT_EMPTY_PATH)) {
        drm_fake_stat_result(buf);
        static const char msg[] = "[drm-shim] __fxstatat: faked DRM fd\n";
        ssize_t __attribute__((unused)) r = write(STDERR_FILENO, msg, sizeof(msg) - 1);
        return 0;
    }
    return ret;
}

/* ---------- __fxstatat64 (dirfd+path, versioned 64-bit) ---------- */

int __fxstatat64(int ver, int dirfd, const char *pathname,
                 struct stat *buf, int flags)
{
    static int first = 0;
    if (!__atomic_exchange_n(&first, 1, __ATOMIC_RELAXED)) {
        static const char msg[] = "[drm-shim] __fxstatat64 called (first time)\n";
        ssize_t __attribute__((unused)) r = write(STDERR_FILENO, msg, sizeof(msg) - 1);
    }

    typedef int (*fn_t)(int, int, const char *, struct stat *, int);
    static fn_t real_fn = NULL;
    if (!real_fn) { real_fn = (fn_t)SHIM_DLSYM(RTLD_NEXT, "__fxstatat64"); if (!real_fn) { errno = ENOSYS; return -1; } }

    int ret = real_fn(ver, dirfd, pathname, buf, flags);
    if (ret != 0) return ret;

    if (is_drm_path(pathname)) {
        drm_fake_stat_result(buf);
        static const char msg[] = "[drm-shim] __fxstatat64: faked DRM path\n";
        ssize_t __attribute__((unused)) r = write(STDERR_FILENO, msg, sizeof(msg) - 1);
        return 0;
    }
    if (is_drm_fd(dirfd) && pathname && pathname[0] == '\0' && (flags & AT_EMPTY_PATH)) {
        drm_fake_stat_result(buf);
        static const char msg[] = "[drm-shim] __fxstatat64: faked DRM fd\n";
        ssize_t __attribute__((unused)) r = write(STDERR_FILENO, msg, sizeof(msg) - 1);
        return 0;
    }
    return ret;
}

/* ------------------------------------------------------------------ */
/*  ioctl interception                                                */
/* ------------------------------------------------------------------ */

int ioctl(int fd, unsigned long request, ...)
{
    /* Lazy-load real ioctl */
    if (!real_ioctl) {
        real_ioctl = (real_ioctl_fn)SHIM_DLSYM(RTLD_NEXT, "ioctl");
        if (!real_ioctl)
            _exit(99);
    }

    /* Extract the vararg pointer */
    va_list ap;
    va_start(ap, request);
    void *arg = va_arg(ap, void *);
    va_end(ap);

    /* Check if this is one of our tracked DRM fds */
    if (drm_fd_is_tracked(fd)) {
        {
            static const char msg[] =
                "[drm-shim] ioctl: DRM fd matched, dispatching\n";
            ssize_t __attribute__((unused)) r =
                write(STDERR_FILENO, msg, sizeof(msg) - 1);
        }
        /* Route through our drmIoctl dispatch */
        return drmIoctl(fd, request, arg);
    }

    /* Diagnostic: log the first non-DRM ioctl after we've tracked a fd,
     * to confirm the ioctl interceptor is alive and being called. */
    if (__atomic_load_n(&drm_fd_count, __ATOMIC_ACQUIRE) > 0) {
        static int logged_first_other = 0;
        if (!__atomic_exchange_n(&logged_first_other, 1, __ATOMIC_RELAXED)) {
            static const char msg[] =
                "[drm-shim] ioctl: first non-DRM ioctl seen after tracking\n";
            ssize_t __attribute__((unused)) r =
                write(STDERR_FILENO, msg, sizeof(msg) - 1);
        }
    }

    /* Not our fd — pass through (cast to fixed-arg signature for ABI safety) */
    return ((int (*)(int, unsigned long, void *))real_ioctl)(fd, request, arg);
}

/* ------------------------------------------------------------------ */
/*  dlopen interception — trace DRI/Mesa/GPU library loads            */
/* ------------------------------------------------------------------ */

void *dlopen(const char *filename, int flags)
{
    if (!real_dlopen_ptr) {
        real_dlopen_ptr = (real_dlopen_fn)SHIM_DLSYM(RTLD_NEXT, "dlopen");
        if (!real_dlopen_ptr)
            return NULL;
    }

    int is_dri = filename && strstr(filename, "_dri.so");
    int is_gpu = filename && (is_dri || strstr(filename, "libdrm") ||
                              strstr(filename, "libEGL") || strstr(filename, "libgbm") ||
                              strstr(filename, "libLLVM") || strstr(filename, "virgl"));

    /* Trace mode: log ALL dlopen calls when DRM_SHIM_TRACE_DLOPEN=1 */
    static int trace_all = -1;
    if (trace_all < 0) {
        const char *env = getenv("DRM_SHIM_TRACE_DLOPEN");
        trace_all = (env && env[0] == '1') ? 1 : 0;
    }

    int should_log = is_gpu || trace_all;

    if (should_log && filename) {
        static const char prefix[] = "[drm-shim] dlopen: ";
        ssize_t __attribute__((unused)) r;
        r = write(STDERR_FILENO, prefix, sizeof(prefix) - 1);
        size_t len = strlen(filename);
        r = write(STDERR_FILENO, filename, len);
        r = write(STDERR_FILENO, "\n", 1);
    } else if (should_log && !filename) {
        static const char msg[] = "[drm-shim] dlopen: (NULL — self)\n";
        ssize_t __attribute__((unused)) r = write(STDERR_FILENO, msg, sizeof(msg) - 1);
    }

    void *result = real_dlopen_ptr(filename, flags);

    if (should_log) {
        if (result) {
            if (is_dri) {
                static const char msg[] = "[drm-shim] dlopen: DRI driver loaded OK\n";
                ssize_t __attribute__((unused)) r = write(STDERR_FILENO, msg, sizeof(msg) - 1);
            } else if (trace_all) {
                static const char msg[] = "[drm-shim] dlopen: OK\n";
                ssize_t __attribute__((unused)) r = write(STDERR_FILENO, msg, sizeof(msg) - 1);
            }
        } else {
            if (is_dri) {
                static const char msg[] = "[drm-shim] dlopen: DRI driver FAILED\n";
                ssize_t __attribute__((unused)) r = write(STDERR_FILENO, msg, sizeof(msg) - 1);
            } else {
                static const char msg[] = "[drm-shim] dlopen: FAILED\n";
                ssize_t __attribute__((unused)) r = write(STDERR_FILENO, msg, sizeof(msg) - 1);
            }
            /* Log dlerror for all failures (GPU + trace mode) */
            const char *err = dlerror();
            if (err) {
                static const char epfx[] = "[drm-shim] dlopen error: ";
                ssize_t __attribute__((unused)) r2;
                r2 = write(STDERR_FILENO, epfx, sizeof(epfx) - 1);
                r2 = write(STDERR_FILENO, err, strlen(err));
                r2 = write(STDERR_FILENO, "\n", 1);
            }
        }
    }

    return result;
}

/* ------------------------------------------------------------------ */
/*  dlsym interception — redirect Chromium's wayland & DRM lookups    */
/* ------------------------------------------------------------------ */

/*
 * Chromium dlopen's libwayland-client.so.0 and calls dlsym(handle, "wl_proxy_add_listener")
 * on the specific library handle, bypassing LD_PRELOAD symbol resolution.  It does the same
 * with libdrm.so — calling dlsym(handle, "drmGetDevices2") etc. instead of using the
 * LD_PRELOAD'd symbols.  By intercepting dlsym itself, we redirect those lookups to our
 * wrapper functions.
 *
 * Bootstrap: we can't use dlsym to get the real dlsym (infinite recursion).  We use
 * dlvsym(RTLD_NEXT, "dlsym", "GLIBC_2.17"), a GNU extension that resolves a specific
 * symbol version directly, bypassing our interceptor.  This avoids the portability
 * issues of __libc_dlsym (a glibc internal not exported on all distros).
 */
void *dlsym(void *handle, const char *symbol)
{
    /* Bootstrap: resolve real dlsym on first call */
    ensure_real_dlsym();
    if (!real_dlsym)
        return NULL;

    /* Electron child processes: skip interception, pass through */
    if (is_electron_child())
        return real_dlsym(handle, symbol);

#pragma GCC diagnostic push
#pragma GCC diagnostic ignored "-Wnonnull-compare"
    if (symbol) {
#pragma GCC diagnostic pop
        /* Intercept wayland function lookups — return our wrappers regardless
         * of which library handle the caller is searching in. */
        if (strcmp(symbol, "wl_proxy_add_listener") == 0) {
            static const char msg[] = "[drm-shim] dlsym intercepted: wl_proxy_add_listener\n";
            ssize_t __attribute__((unused)) r = write(STDERR_FILENO, msg, sizeof(msg) - 1);
            extern int wl_proxy_add_listener(void *, void **, void *);
            return (void *)wl_proxy_add_listener;
        }
        if (strcmp(symbol, "wl_display_connect") == 0) {
            static const char msg[] = "[drm-shim] dlsym intercepted: wl_display_connect\n";
            ssize_t __attribute__((unused)) r = write(STDERR_FILENO, msg, sizeof(msg) - 1);
            extern void *wl_display_connect(const char *);
            return (void *)wl_display_connect;
        }

        /* DRM function redirects — ensure Chromium resolves OUR implementations
         * even when using dlsym() on a specific libdrm handle. */
#define DRM_REDIRECT(name) \
        if (strcmp(symbol, #name) == 0) { \
            static int logged_##name = 0; \
            if (!logged_##name) { \
                DRM_SHIM_LOG("dlsym redirect: " #name " -> drm-shim"); \
                logged_##name = 1; \
            } \
            return (void *)name; \
        }
        DRM_REDIRECT(drmGetDevices2)
        DRM_REDIRECT(drmGetDevices)
        DRM_REDIRECT(drmFreeDevices)
        DRM_REDIRECT(drmGetDevice2)
        DRM_REDIRECT(drmGetDevice)
        DRM_REDIRECT(drmFreeDevice)
        DRM_REDIRECT(drmGetVersion)
        DRM_REDIRECT(drmFreeVersion)
        DRM_REDIRECT(drmGetCap)
        DRM_REDIRECT(drmIoctl)
        DRM_REDIRECT(drmGetMagic)
        DRM_REDIRECT(drmAuthMagic)
        DRM_REDIRECT(drmPrimeHandleToFD)
        DRM_REDIRECT(drmPrimeFDToHandle)
        DRM_REDIRECT(drmGetBusid)
        DRM_REDIRECT(drmFreeBusid)
        DRM_REDIRECT(drmClose)
        DRM_REDIRECT(drmGetNodeTypeFromFd)
        DRM_REDIRECT(drmGetRenderDeviceNameFromFd)
        DRM_REDIRECT(drmGetDeviceNameFromFd2)
#undef DRM_REDIRECT
    }

    return real_dlsym(handle, symbol);
}

/* ------------------------------------------------------------------ */
/*  Constructor — library init (vtest connection is deferred)         */
/* ------------------------------------------------------------------ */

/* ------------------------------------------------------------------ */
/*  fcntl interception                                                */
/* ------------------------------------------------------------------ */

/*
 * Mesa's dri2_initialize_gbm() calls fcntl(gbm_fd, F_DUPFD_CLOEXEC, 3)
 * to dup the GBM device fd.  Inside proot, F_DUPFD_CLOEXEC may fail on
 * certain fd types.  We intercept fcntl to:
 *   1. Provide a F_DUPFD + F_SETFD(FD_CLOEXEC) fallback when F_DUPFD_CLOEXEC
 *      fails on a tracked DRM fd.
 *   2. Track any successfully dup'd DRM fds so subsequent ioctls/mmaps work.
 */
static int shim_fcntl_impl(int fd, int cmd, long arg, real_fcntl_fn fn)
{
    int ret = fn(fd, cmd, arg);

    /* If F_DUPFD_CLOEXEC failed on a tracked DRM fd, fall back to
     * F_DUPFD + F_SETFD(FD_CLOEXEC).  proot's syscall translation
     * sometimes doesn't handle F_DUPFD_CLOEXEC correctly. */
    if (ret < 0 && cmd == F_DUPFD_CLOEXEC && drm_fd_is_tracked(fd)) {
        DRM_SHIM_LOG("fcntl F_DUPFD_CLOEXEC failed on DRM fd, trying fallback");
        ret = fn(fd, F_DUPFD, arg);
        if (ret >= 0) {
            fn(ret, F_SETFD, (long)FD_CLOEXEC);
            drm_fd_add(ret);
        }
        return ret;
    }

    /* Track successfully dup'd DRM fds */
    if (ret >= 0 && (cmd == F_DUPFD || cmd == F_DUPFD_CLOEXEC)
        && drm_fd_is_tracked(fd)) {
        drm_fd_add(ret);
    }

    return ret;
}

int fcntl(int fd, int cmd, ...)
{
    if (!real_fcntl) {
        real_fcntl = (real_fcntl_fn)SHIM_DLSYM(RTLD_NEXT, "fcntl");
        if (!real_fcntl)
            _exit(99);
    }

    va_list ap;
    va_start(ap, cmd);
    /* On AArch64 LP64, sizeof(long) == sizeof(void*), so pointer-valued
     * arguments (F_SETLK, F_GETLK, etc.) round-trip safely through long.
     * This is the standard pattern used by musl and bionic fcntl wrappers. */
    long arg = va_arg(ap, long);
    va_end(ap);

    return shim_fcntl_impl(fd, cmd, arg, real_fcntl);
}

/* Some glibc / bionic versions route through fcntl64 instead of fcntl.
 * Provide the same interceptor under that name. */
int fcntl64(int fd, int cmd, ...)
{
    if (!real_fcntl64) {
        real_fcntl64 = (real_fcntl_fn)SHIM_DLSYM(RTLD_NEXT, "fcntl64");
        /* Fall back to fcntl if fcntl64 symbol is not found */
        if (!real_fcntl64) {
            if (!real_fcntl)
                real_fcntl = (real_fcntl_fn)SHIM_DLSYM(RTLD_NEXT, "fcntl");
            real_fcntl64 = real_fcntl;
        }
        if (!real_fcntl64)
            _exit(99);
    }

    va_list ap;
    va_start(ap, cmd);
    /* On AArch64 LP64, sizeof(long) == sizeof(void*), so pointer-valued
     * arguments (F_SETLK, F_GETLK, etc.) round-trip safely through long.
     * This is the standard pattern used by musl and bionic fcntl wrappers. */
    long arg = va_arg(ap, long);
    va_end(ap);

    return shim_fcntl_impl(fd, cmd, arg, real_fcntl64);
}

__attribute__((constructor))
static void drm_shim_init(void)
{
    /* Skip initialization for Electron child processes (extensionHost,
     * fileWatcher, shared-process).  They inherit LD_PRELOAD but don't
     * use DRM/Wayland; our syscall intercepts cause SIGSEGV (code 11). */
    if (is_electron_child())
        return;

    /* Initialize all tracked fd slots to -1 */
    for (int i = 0; i < MAX_DRM_FDS; i++)
        drm_fds[i] = -1;

    /* Ignore SIGPIPE so a vtest server crash doesn't kill the process */
    signal(SIGPIPE, SIG_IGN);

    /* Resolve real syscall wrappers early (before any calls) */
    real_mmap = (real_mmap_fn)SHIM_DLSYM(RTLD_NEXT, "mmap");
    real_ioctl = (real_ioctl_fn)SHIM_DLSYM(RTLD_NEXT, "ioctl");
    real_open = (real_open_fn)SHIM_DLSYM(RTLD_NEXT, "open");
    real_openat = (real_openat_fn)SHIM_DLSYM(RTLD_NEXT, "openat");
    real_fstatat = (real_fstatat_fn)SHIM_DLSYM(RTLD_NEXT, "fstatat");
    real_dlopen_ptr = (real_dlopen_fn)SHIM_DLSYM(RTLD_NEXT, "dlopen");
    real_fcntl = (real_fcntl_fn)SHIM_DLSYM(RTLD_NEXT, "fcntl");
    real_fcntl64 = (real_fcntl_fn)SHIM_DLSYM(RTLD_NEXT, "fcntl64");
    if (!real_fcntl64)
        real_fcntl64 = real_fcntl;

    {
        static const char msg[] = "[drm-shim] *** drm-shim.so loaded (virtio_gpu bridge) ***\n";
        ssize_t __attribute__((unused)) r = write(STDERR_FILENO, msg, sizeof(msg) - 1);
    }

    {
        const char *env = getenv("DRM_SHIM_TRACE_DLOPEN");
        if (env && env[0] == '1') {
            static const char tmsg[] = "[drm-shim] DRM_SHIM_TRACE_DLOPEN=1 — tracing ALL dlopen calls\n";
            ssize_t __attribute__((unused)) r = write(STDERR_FILENO, tmsg, sizeof(tmsg) - 1);
        }
    }

    /* vtest connection is deferred — see vtest_ensure_connected() */
}
