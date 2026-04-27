/*
 * ahb_registry.h — Thread-safe inode→AHardwareBuffer* registry.
 *
 * The virgl server runs as a separate process and sends AHardwareBuffer
 * handles over a Unix domain socket. The ahb_registry_receiver thread
 * receives them and populates this registry. The compositor (wlroots
 * renderer) looks up AHBs by dmabuf inode when importing client buffers.
 */
#pragma once
#include <stdint.h>
#include <android/hardware_buffer.h>

/* Store an AHB under the given inode key.  Acquires a reference. */
void ahb_registry_register(uint64_t inode, AHardwareBuffer *ahb);

/* Remove the entry for |inode| and release its reference. */
void ahb_registry_unregister(uint64_t inode);

/* Look up an AHB by inode.  Returns NULL if not found.
 * On success the returned AHB has an acquired reference — the caller
 * must call AHardwareBuffer_release() when done. */
AHardwareBuffer *ahb_registry_lookup(uint64_t inode);

/* Release all entries and reset the registry. */
void ahb_registry_destroy(void);
