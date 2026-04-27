/*
 * input_handler.h — Android input event forwarding to wlroots seat
 *
 * Provides a thread-safe pipe+ring-buffer mechanism for the JNI/UI thread
 * to enqueue input events that are dispatched on the compositor event loop.
 */
#ifndef INPUT_HANDLER_H
#define INPUT_HANDLER_H

#include <stdint.h>

struct compositor_server; /* forward decl */

/**
 * Initialise input handling: creates pipe, fd event source, xkb keyboard.
 * Must be called on the compositor thread before the event loop starts.
 * Returns 0 on success, -1 on failure.
 */
int input_handler_init(struct compositor_server *server);

/**
 * Tear down input handling resources.  Called during server destroy.
 */
void input_handler_destroy(struct compositor_server *server);

/* ---- Thread-safe send functions (called from JNI / UI thread) ---- */

void input_handler_send_key(struct compositor_server *server,
                            int android_keycode, int pressed,
                            uint32_t timestamp_msec);

void input_handler_send_touch_down(struct compositor_server *server,
                                   int32_t touch_id, double x, double y,
                                   uint32_t timestamp_msec);

void input_handler_send_touch_motion(struct compositor_server *server,
                                     int32_t touch_id, double x, double y,
                                     uint32_t timestamp_msec);

void input_handler_send_touch_up(struct compositor_server *server,
                                 int32_t touch_id,
                                 uint32_t timestamp_msec);

void input_handler_send_pointer_motion(struct compositor_server *server,
                                       double x, double y,
                                       uint32_t timestamp_msec);

void input_handler_send_pointer_button(struct compositor_server *server,
                                       uint32_t button, int pressed,
                                       uint32_t timestamp_msec);

void input_handler_send_pointer_scroll(struct compositor_server *server,
                                       double dx, double dy,
                                       uint32_t timestamp_msec);

#endif /* INPUT_HANDLER_H */
