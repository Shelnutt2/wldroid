/*
 * test_client.h — Built-in Wayland test client
 *
 * Starts an in-process Wayland client on a background thread that draws
 * a 4-quadrant color pattern (red / green / blue / yellow).  Uses
 * socketpair + wl_client_create so no filesystem socket is needed.
 */
#ifndef TEST_CLIENT_H
#define TEST_CLIENT_H

#include "compositor_server.h"

/**
 * Start an in-process Wayland test client on a background thread.
 *
 * Internally creates a socketpair, hands one end to wl_client_create()
 * (server side) and spawns a pthread that runs a minimal xdg-shell
 * client on the other end.
 *
 * Returns 0 on success, -1 on error.
 */
int test_client_start(struct compositor_server *server);

#endif /* TEST_CLIENT_H */
