/*
 * text_input_handler.h — Text input / IME bridge between Android and Wayland
 *
 * Implements zwp_text_input_v3 for clients that support it (GTK4, Qt6),
 * and falls back to synthetic wlr_keyboard events for clients that don't
 * (Electron/VS Code).
 */
#ifndef TEXT_INPUT_HANDLER_H
#define TEXT_INPUT_HANDLER_H

#include <stdbool.h>
#include <stdint.h>

struct compositor_server;

/**
 * Initialize text input support: creates zwp_text_input_manager_v3 global,
 * IME request pipe, and builds character→keycode lookup table.
 * Returns 0 on success, -1 on failure.
 */
int text_input_handler_init(struct compositor_server *server);

/**
 * Tear down text input resources.
 */
void text_input_handler_destroy(struct compositor_server *server);

/**
 * Handle committed text from Android IME.
 * If an active text-input-v3 client exists, sends commit_string.
 * Otherwise, emits synthetic keyboard events for each character.
 * Thread-safe (can be called from JNI thread).
 */
void text_input_handle_commit_text(struct compositor_server *server,
                                    const char *text);

/**
 * Handle delete-surrounding-text from Android IME.
 * Thread-safe (can be called from JNI thread).
 */
void text_input_handle_delete_surrounding_text(struct compositor_server *server,
                                                uint32_t before_length,
                                                uint32_t after_length);

/** Whether a zwp_text_input_v3 resource is currently enabled. */
bool text_input_has_active_text_input(void);

/** Notify the compositor that the Android soft keyboard was shown. */
void text_input_handle_ime_shown(struct compositor_server *server);

/** Notify the compositor that the Android soft keyboard was hidden. */
void text_input_handle_ime_hidden(struct compositor_server *server);

#endif /* TEXT_INPUT_HANDLER_H */
