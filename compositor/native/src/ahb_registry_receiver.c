/*
 * ahb_registry_receiver.c — Receives AHardwareBuffer handles from the virgl
 * server over a Unix domain socket and registers them in the AHB registry.
 *
 * Wire protocol (per message from client):
 *   [1 byte tag] [8 byte inode LE]
 *   - REGISTER (0x01):   followed by an AHB handle sent via
 *                         AHardwareBuffer_sendHandleToUnixSocket on the
 *                         client side; we receive with
 *                         AHardwareBuffer_recvHandleFromUnixSocket.
 *   - UNREGISTER (0x02): just the inode; removes the registry entry.
 */
#include "ahb_registry_receiver.h"
#include "ahb_registry.h"

#include <pthread.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>
#include <string.h>
#include <errno.h>
#include <inttypes.h>
#include <poll.h>
#include <android/hardware_buffer.h>
#include <wlr/util/log.h>

#define AHB_MSG_REGISTER   0x01
#define AHB_MSG_UNREGISTER 0x02

static pthread_t g_receiver_thread;
static int g_server_fd = -1;
static volatile int g_running = 0;

/* Read exactly |len| bytes from |fd|.  Returns 0 on success, -1 on error/EOF. */
static int read_exact(int fd, void *buf, size_t len) {
    size_t off = 0;
    while (off < len) {
        ssize_t n = read(fd, (char *)buf + off, len - off);
        if (n <= 0) {
            return -1;
        }
        off += (size_t)n;
    }
    return 0;
}

static void handle_client(int client_fd) {
    while (g_running) {
        uint8_t tag;
        if (read_exact(client_fd, &tag, 1) != 0) {
            break; /* EOF or error — client disconnected */
        }

        uint8_t inode_buf[8];
        if (read_exact(client_fd, inode_buf, 8) != 0) {
            wlr_log(WLR_ERROR, "AHB receiver: short read on inode");
            break;
        }
        uint64_t inode;
        memcpy(&inode, inode_buf, sizeof(inode)); /* little-endian */

        switch (tag) {
        case AHB_MSG_REGISTER: {
            AHardwareBuffer *ahb = NULL;
            int ret = AHardwareBuffer_recvHandleFromUnixSocket(client_fd, &ahb);
            if (ret != 0 || !ahb) {
                wlr_log(WLR_ERROR,
                         "AHB receiver: recvHandleFromUnixSocket failed "
                         "(inode=%" PRIu64 ", ret=%d)", inode, ret);
                break;
            }
            ahb_registry_register(inode, ahb);
            /* Registry acquires its own reference; release ours. */
            AHardwareBuffer_release(ahb);
            wlr_log(WLR_DEBUG,
                     "AHB receiver: registered inode=%" PRIu64, inode);
            break;
        }
        case AHB_MSG_UNREGISTER:
            ahb_registry_unregister(inode);
            wlr_log(WLR_DEBUG,
                     "AHB receiver: unregistered inode=%" PRIu64, inode);
            break;
        default:
            wlr_log(WLR_ERROR,
                     "AHB receiver: unknown tag 0x%02x", tag);
            /* Protocol error — drop client. */
            return;
        }
    }
}

static void *receiver_thread_func(void *arg) {
    (void)arg;

    while (g_running) {
        /* Use poll() so we can periodically check g_running. */
        struct pollfd pfd = {
            .fd = g_server_fd,
            .events = POLLIN,
        };
        int pr = poll(&pfd, 1, 500 /* ms */);
        if (pr < 0) {
            if (errno == EINTR) continue;
            wlr_log(WLR_ERROR, "AHB receiver: poll error: %s",
                     strerror(errno));
            break;
        }
        if (pr == 0) continue; /* timeout — recheck g_running */

        int client_fd = accept(g_server_fd, NULL, NULL);
        if (client_fd < 0) {
            if (!g_running) break;
            wlr_log(WLR_ERROR, "AHB receiver: accept failed: %s",
                     strerror(errno));
            continue;
        }

        wlr_log(WLR_INFO, "AHB receiver: client connected (fd=%d)",
                 client_fd);
        handle_client(client_fd);
        close(client_fd);
        wlr_log(WLR_INFO, "AHB receiver: client disconnected");
    }

    return NULL;
}

int ahb_registry_receiver_start(const char *socket_path) {
    int fd = socket(AF_UNIX, SOCK_STREAM, 0);
    if (fd < 0) {
        wlr_log(WLR_ERROR, "AHB receiver: socket() failed: %s",
                 strerror(errno));
        return -1;
    }

    /* Remove stale socket file if any. */
    unlink(socket_path);

    struct sockaddr_un addr;
    memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
    strncpy(addr.sun_path, socket_path, sizeof(addr.sun_path) - 1);

    if (bind(fd, (struct sockaddr *)&addr, sizeof(addr)) < 0) {
        wlr_log(WLR_ERROR, "AHB receiver: bind(%s) failed: %s",
                 socket_path, strerror(errno));
        close(fd);
        return -1;
    }

    if (listen(fd, 1) < 0) {
        wlr_log(WLR_ERROR, "AHB receiver: listen() failed: %s",
                 strerror(errno));
        close(fd);
        return -1;
    }

    g_server_fd = fd;
    g_running = 1;

    if (pthread_create(&g_receiver_thread, NULL,
                       receiver_thread_func, NULL) != 0) {
        wlr_log(WLR_ERROR, "AHB receiver: pthread_create failed: %s",
                 strerror(errno));
        g_running = 0;
        close(fd);
        g_server_fd = -1;
        return -1;
    }

    wlr_log(WLR_INFO, "AHB registry receiver listening on %s", socket_path);
    return 0;
}

void ahb_registry_receiver_stop(void) {
    if (!g_running) return;

    g_running = 0;

    if (g_server_fd >= 0) {
        shutdown(g_server_fd, SHUT_RDWR);
        close(g_server_fd);
        g_server_fd = -1;
    }

    pthread_join(g_receiver_thread, NULL);
    wlr_log(WLR_INFO, "AHB registry receiver stopped");
}
