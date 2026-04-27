/*
 * ahb_registry_receiver.h — Background thread that listens on a Unix domain
 * socket for AHB handle transfers from the virgl server process and registers
 * them in the AHB registry.
 */
#pragma once

/* Start AHB registry receiver thread.
 * socket_path: Unix domain socket path to listen on.
 * Returns 0 on success, -1 on failure. */
int ahb_registry_receiver_start(const char *socket_path);

/* Stop receiver thread and close socket. */
void ahb_registry_receiver_stop(void);
