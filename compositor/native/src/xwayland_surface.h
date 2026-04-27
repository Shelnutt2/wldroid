/*
 * xwayland_surface.h — X11 surface lifecycle management for XWayland
 *
 * Handles X11 windows that connect via XWayland, mapping them into the
 * wlroots scene graph so they render alongside native Wayland clients.
 */
#ifndef XWAYLAND_SURFACE_H
#define XWAYLAND_SURFACE_H

#include <wlr/config.h>
#if WLR_HAS_XWAYLAND

#include <wayland-server-core.h>

struct compositor_server;

/**
 * Handle a new XWayland surface.  Called from compositor_server.c when
 * wlr_xwayland emits the new_surface signal.
 */
void on_new_xwayland_surface(struct wl_listener *listener, void *data);

#endif /* WLR_HAS_XWAYLAND */
#endif /* XWAYLAND_SURFACE_H */
