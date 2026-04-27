/*
 * ahb_registry.c — Thread-safe inode → AHardwareBuffer* registry
 *
 * A simple fixed-capacity array protected by a mutex.  256 entries is
 * more than enough for the expected number of cross-process buffers.
 */
#include "ahb_registry.h"

#include <inttypes.h>
#include <pthread.h>
#include <string.h>
#include <wlr/util/log.h>

#define AHB_REGISTRY_MAX 256

struct ahb_entry {
    uint64_t inode;
    AHardwareBuffer *ahb;
};

static struct ahb_entry entries[AHB_REGISTRY_MAX];
static size_t count = 0;
static pthread_mutex_t lock = PTHREAD_MUTEX_INITIALIZER;

void ahb_registry_register(uint64_t inode, AHardwareBuffer *ahb) {
    pthread_mutex_lock(&lock);

    /* Check for duplicate — update in place if the inode already exists. */
    for (size_t i = 0; i < count; i++) {
        if (entries[i].inode == inode) {
            AHardwareBuffer_release(entries[i].ahb);
            AHardwareBuffer_acquire(ahb);
            entries[i].ahb = ahb;
            wlr_log(WLR_DEBUG, "ahb_registry: updated inode %" PRIu64, inode);
            pthread_mutex_unlock(&lock);
            return;
        }
    }

    if (count >= AHB_REGISTRY_MAX) {
        wlr_log(WLR_ERROR, "ahb_registry: full, cannot register inode %" PRIu64,
                 inode);
        pthread_mutex_unlock(&lock);
        return;
    }

    AHardwareBuffer_acquire(ahb);
    entries[count].inode = inode;
    entries[count].ahb = ahb;
    count++;

    wlr_log(WLR_DEBUG, "ahb_registry: registered inode %" PRIu64 " (%zu entries)",
             inode, count);
    pthread_mutex_unlock(&lock);
}

void ahb_registry_unregister(uint64_t inode) {
    pthread_mutex_lock(&lock);

    for (size_t i = 0; i < count; i++) {
        if (entries[i].inode == inode) {
            AHardwareBuffer_release(entries[i].ahb);
            /* Swap with last entry to keep the array compact. */
            entries[i] = entries[count - 1];
            memset(&entries[count - 1], 0, sizeof(entries[0]));
            count--;
            wlr_log(WLR_DEBUG,
                     "ahb_registry: unregistered inode %" PRIu64 " (%zu entries)",
                     inode, count);
            pthread_mutex_unlock(&lock);
            return;
        }
    }

    wlr_log(WLR_DEBUG, "ahb_registry: inode %" PRIu64 " not found for unregister",
             inode);
    pthread_mutex_unlock(&lock);
}

AHardwareBuffer *ahb_registry_lookup(uint64_t inode) {
    AHardwareBuffer *result = NULL;

    pthread_mutex_lock(&lock);

    for (size_t i = 0; i < count; i++) {
        if (entries[i].inode == inode) {
            AHardwareBuffer_acquire(entries[i].ahb);
            result = entries[i].ahb;
            break;
        }
    }

    pthread_mutex_unlock(&lock);
    return result;
}

void ahb_registry_destroy(void) {
    pthread_mutex_lock(&lock);

    for (size_t i = 0; i < count; i++) {
        AHardwareBuffer_release(entries[i].ahb);
    }
    memset(entries, 0, sizeof(entries));
    count = 0;

    wlr_log(WLR_DEBUG, "ahb_registry: destroyed all entries");
    pthread_mutex_unlock(&lock);
}
