/*
 * xwayland_surface.c — X11 surface lifecycle management for XWayland
 *
 * Manages the lifecycle of X11 windows that connect via XWayland:
 *   1. new_surface    → allocate xwayland_view, listen for associate/destroy
 *   2. associate      → wl_surface is now valid; listen for map/unmap
 *   3. map            → create scene tree node, position, optionally focus
 *   4. unmap          → destroy scene tree node
 *   5. dissociate     → remove map/unmap listeners
 *   6. destroy        → free everything
 *
 * Override-redirect windows (menus, tooltips, popups) are positioned at
 * their X11 coordinates without receiving keyboard focus.  Managed windows
 * are focused on map and can request configure / fullscreen.
 *
 * This file compiles to empty when WLR_HAS_XWAYLAND is 0.
 */
#include <wlr/config.h>
#if WLR_HAS_XWAYLAND

#include <stdlib.h>
#include <wlr/types/wlr_scene.h>
#include <wlr/types/wlr_seat.h>
#include <wlr/xwayland.h>
#include <wlr/util/log.h>

#include "compositor_server.h"
#include "xwayland_surface.h"

/* ------------------------------------------------------------------ */
/* Per-surface tracking state                                          */
/* ------------------------------------------------------------------ */

struct xwayland_view {
    struct compositor_server *server;
    struct wlr_xwayland_surface *xsurface;
    struct wlr_scene_tree *scene_tree;

    struct wl_listener associate;
    struct wl_listener dissociate;
    struct wl_listener map;
    struct wl_listener unmap;
    struct wl_listener destroy;
    struct wl_listener request_configure;
    struct wl_listener request_fullscreen;
    struct wl_listener set_override_redirect;
};

/* ------------------------------------------------------------------ */
/* Focus helper                                                        */
/* ------------------------------------------------------------------ */

static void focus_xwayland_view(struct xwayland_view *view) {
    struct compositor_server *server = view->server;
    struct wlr_xwayland_surface *xsurface = view->xsurface;

    /* Don't focus override-redirect windows (menus, tooltips). */
    if (xsurface->override_redirect) {
        return;
    }

    wlr_xwayland_surface_activate(xsurface, true);

    if (view->scene_tree) {
        wlr_scene_node_raise_to_top(&view->scene_tree->node);
    }

    struct wlr_seat *seat = server->seat;
    struct wlr_keyboard *kb = wlr_seat_get_keyboard(seat);
    if (kb) {
        wlr_seat_keyboard_notify_enter(seat, xsurface->surface,
            kb->keycodes, kb->num_keycodes, &kb->modifiers);
    } else {
        wlr_seat_keyboard_notify_enter(seat, xsurface->surface,
            NULL, 0, NULL);
    }
}

/* ------------------------------------------------------------------ */
/* Lifecycle listeners                                                 */
/* ------------------------------------------------------------------ */

static void on_xwayland_surface_map(struct wl_listener *listener, void *data) {
    (void)data;
    struct xwayland_view *view = wl_container_of(listener, view, map);

    view->scene_tree = wlr_scene_subsurface_tree_create(
        &view->server->scene->tree, view->xsurface->surface);
    if (!view->scene_tree) {
        wlr_log(WLR_ERROR, "Failed to create scene tree for X11 surface");
        return;
    }

    /* Position at the X11-requested coordinates. */
    wlr_scene_node_set_position(&view->scene_tree->node,
        view->xsurface->x, view->xsurface->y);

    /* Store back-pointer for hit-testing (matches xdg_shell pattern). */
    view->scene_tree->node.data = view;

    focus_xwayland_view(view);

    wlr_log(WLR_INFO, "X11 surface mapped: \"%s\" (%dx%d @ %d,%d)%s",
             view->xsurface->title ? view->xsurface->title : "(untitled)",
             view->xsurface->width, view->xsurface->height,
             view->xsurface->x, view->xsurface->y,
             view->xsurface->override_redirect ? " [override-redirect]" : "");
}

static void on_xwayland_surface_unmap(struct wl_listener *listener, void *data) {
    (void)data;
    struct xwayland_view *view = wl_container_of(listener, view, unmap);

    if (view->scene_tree) {
        wlr_scene_node_destroy(&view->scene_tree->node);
        view->scene_tree = NULL;
    }

    wlr_log(WLR_DEBUG, "X11 surface unmapped: \"%s\"",
             view->xsurface->title ? view->xsurface->title : "(untitled)");
}

static void on_xwayland_surface_associate(struct wl_listener *listener,
                                           void *data) {
    (void)data;
    struct xwayland_view *view = wl_container_of(listener, view, associate);

    /* Now xsurface->surface is valid — listen for map/unmap. */
    view->map.notify = on_xwayland_surface_map;
    wl_signal_add(&view->xsurface->surface->events.map, &view->map);

    view->unmap.notify = on_xwayland_surface_unmap;
    wl_signal_add(&view->xsurface->surface->events.unmap, &view->unmap);
}

static void on_xwayland_surface_dissociate(struct wl_listener *listener,
                                            void *data) {
    (void)data;
    struct xwayland_view *view = wl_container_of(listener, view, dissociate);

    wl_list_remove(&view->map.link);
    wl_list_remove(&view->unmap.link);
}

static void on_xwayland_surface_destroy(struct wl_listener *listener,
                                         void *data) {
    (void)data;
    struct xwayland_view *view = wl_container_of(listener, view, destroy);

    /* Remove all listeners. */
    wl_list_remove(&view->associate.link);
    wl_list_remove(&view->dissociate.link);
    wl_list_remove(&view->destroy.link);
    wl_list_remove(&view->request_configure.link);
    wl_list_remove(&view->request_fullscreen.link);
    wl_list_remove(&view->set_override_redirect.link);

    /* Scene tree is already destroyed via unmap → dissociate path. */
    free(view);
}

/* ------------------------------------------------------------------ */
/* Configure / fullscreen / override-redirect handlers                 */
/* ------------------------------------------------------------------ */

static void on_xwayland_request_configure(struct wl_listener *listener,
                                           void *data) {
    struct xwayland_view *view =
        wl_container_of(listener, view, request_configure);
    struct wlr_xwayland_surface_configure_event *event = data;

    /* Acknowledge the configure with the requested geometry. */
    wlr_xwayland_surface_configure(view->xsurface,
        event->x, event->y, event->width, event->height);

    /* Update scene position if mapped. */
    if (view->scene_tree) {
        wlr_scene_node_set_position(&view->scene_tree->node,
            event->x, event->y);
    }
}

static void on_xwayland_request_fullscreen(struct wl_listener *listener,
                                            void *data) {
    (void)data;
    struct xwayland_view *view =
        wl_container_of(listener, view, request_fullscreen);
    struct wlr_output *output = view->server->output;

    if (output && view->scene_tree) {
        wlr_xwayland_surface_configure(view->xsurface,
            0, 0, output->width, output->height);
        wlr_scene_node_set_position(&view->scene_tree->node, 0, 0);
    }
}

static void on_xwayland_set_override_redirect(struct wl_listener *listener,
                                               void *data) {
    (void)data;
    struct xwayland_view *view =
        wl_container_of(listener, view, set_override_redirect);

    wlr_log(WLR_DEBUG, "X11 surface override_redirect changed to %d: \"%s\"",
             view->xsurface->override_redirect,
             view->xsurface->title ? view->xsurface->title : "(untitled)");
}

/* ------------------------------------------------------------------ */
/* new_surface handler (called from compositor_server.c)               */
/* ------------------------------------------------------------------ */

void on_new_xwayland_surface(struct wl_listener *listener, void *data) {
    struct compositor_server *server =
        wl_container_of(listener, server, new_xwayland_surface);
    struct wlr_xwayland_surface *xsurface = data;

    struct xwayland_view *view = calloc(1, sizeof(*view));
    if (!view) {
        wlr_log(WLR_ERROR, "Failed to allocate xwayland_view");
        return;
    }

    view->server = server;
    view->xsurface = xsurface;
    view->scene_tree = NULL;

    /* Wire up listeners for the surface lifecycle. */
    view->associate.notify = on_xwayland_surface_associate;
    wl_signal_add(&xsurface->events.associate, &view->associate);

    view->dissociate.notify = on_xwayland_surface_dissociate;
    wl_signal_add(&xsurface->events.dissociate, &view->dissociate);

    view->destroy.notify = on_xwayland_surface_destroy;
    wl_signal_add(&xsurface->events.destroy, &view->destroy);

    view->request_configure.notify = on_xwayland_request_configure;
    wl_signal_add(&xsurface->events.request_configure,
                  &view->request_configure);

    view->request_fullscreen.notify = on_xwayland_request_fullscreen;
    wl_signal_add(&xsurface->events.request_fullscreen,
                  &view->request_fullscreen);

    view->set_override_redirect.notify = on_xwayland_set_override_redirect;
    wl_signal_add(&xsurface->events.set_override_redirect,
                  &view->set_override_redirect);

    wlr_log(WLR_DEBUG, "New XWayland surface: window_id=0x%x",
             xsurface->window_id);
}

#endif /* WLR_HAS_XWAYLAND */
