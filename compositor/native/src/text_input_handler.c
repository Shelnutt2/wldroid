/*
 * text_input_handler.c — Text input / IME bridge between Android and Wayland
 *
 * Implements zwp_text_input_v3 for clients that support it (GTK4, Qt6),
 * and falls back to synthetic wlr_keyboard events for clients that don't
 * (Electron/VS Code).
 *
 * Architecture:
 *   Android IME → JNI → text_input_handle_commit_text()
 *     ├── text-input-v3 path: zwp_text_input_v3_send_commit_string + done
 *     └── fallback path: synthetic wlr_keyboard key press/release events
 *
 *   Wayland client → zwp_text_input_v3.enable → pipe byte → Kotlin shows IME
 */
#define _GNU_SOURCE
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <fcntl.h>
#include <errno.h>
#include <pthread.h>
#include <android/log.h>

#include <wayland-server-core.h>
#include <wlr/types/wlr_seat.h>
#include <wlr/types/wlr_keyboard.h>
#include <wlr/interfaces/wlr_keyboard.h>
#include <wlr/util/log.h>
#include <xkbcommon/xkbcommon.h>

#include "text-input-unstable-v3-server-protocol.h"
#include "compositor_server.h"
#include "text_input_handler.h"

#define LOG_TAG "TextInput"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)

/* ------------------------------------------------------------------ */
/* ASCII → evdev keycode lookup table (for synthetic key fallback)     */
/* ------------------------------------------------------------------ */

#define ASCII_MAX 128

static struct {
    xkb_keycode_t keycode;  /* evdev keycode (xkb keycode - 8) */
    bool needs_shift;
} ascii_to_key[ASCII_MAX];

static bool ascii_table_built = false;

/**
 * Scan the xkb keymap to build a codepoint → (evdev keycode, shift?) table.
 * Called once when the keyboard is available.
 */
static void build_ascii_table(struct wlr_keyboard *kb) {
    if (!kb || !kb->keymap) return;

    struct xkb_state *state = xkb_state_new(kb->keymap);
    if (!state) return;

    xkb_keycode_t min_kc = xkb_keymap_min_keycode(kb->keymap);
    xkb_keycode_t max_kc = xkb_keymap_max_keycode(kb->keymap);

    memset(ascii_to_key, 0, sizeof(ascii_to_key));

    /* Pass 1: scan all keycodes without modifiers (lowercase, digits, etc.) */
    for (xkb_keycode_t kc = min_kc; kc <= max_kc; kc++) {
        uint32_t cp = xkb_state_key_get_utf32(state, kc);
        if (cp > 0 && cp < ASCII_MAX && ascii_to_key[cp].keycode == 0) {
            ascii_to_key[cp].keycode = kc - 8;  /* xkb → evdev */
            ascii_to_key[cp].needs_shift = false;
        }
    }

    /* Pass 2: scan with Shift held (uppercase, symbols like !@# etc.) */
    xkb_mod_index_t shift_idx =
        xkb_keymap_mod_get_index(kb->keymap, XKB_MOD_NAME_SHIFT);
    if (shift_idx != XKB_MOD_INVALID) {
        struct xkb_state *shift_state = xkb_state_new(kb->keymap);
        if (shift_state) {
            /* Find the Shift keycode by trying each key */
            for (xkb_keycode_t kc = min_kc; kc <= max_kc; kc++) {
                xkb_state_update_key(shift_state, kc, XKB_KEY_DOWN);
                if (xkb_state_mod_index_is_active(shift_state, shift_idx,
                                                   XKB_STATE_MODS_DEPRESSED)) {
                    /* Shift is now held — scan all other keys */
                    for (xkb_keycode_t inner = min_kc; inner <= max_kc; inner++) {
                        if (inner == kc) continue; /* skip Shift itself */
                        uint32_t cp = xkb_state_key_get_utf32(shift_state, inner);
                        if (cp > 0 && cp < ASCII_MAX &&
                            ascii_to_key[cp].keycode == 0) {
                            ascii_to_key[cp].keycode = inner - 8;
                            ascii_to_key[cp].needs_shift = true;
                        }
                    }
                    xkb_state_update_key(shift_state, kc, XKB_KEY_UP);
                    break; /* found Shift, done */
                }
                xkb_state_update_key(shift_state, kc, XKB_KEY_UP);
            }
            xkb_state_unref(shift_state);
        }
    }

    xkb_state_unref(state);
    ascii_table_built = true;
    LOGD("ASCII keycode table built (%u–%u scanned)", min_kc, max_kc);
}

/* ------------------------------------------------------------------ */
/* Synthetic key emission (fallback for clients without text-input-v3) */
/* ------------------------------------------------------------------ */

/* KEY_LEFTSHIFT evdev keycode */
#define EVDEV_KEY_LEFTSHIFT 42

static void ensure_keyboard_focus(struct compositor_server *server) {
    if (!server || !server->seat || !server->keyboard) return;
    if (server->seat->keyboard_state.focused_surface) return;

    struct compositor_toplevel *ct;
    wl_list_for_each(ct, &server->toplevels, link) {
        if (!ct->rendering_only && ct->toplevel && ct->toplevel->base) {
            wlr_seat_keyboard_notify_enter(server->seat, ct->toplevel->base->surface,
                server->keyboard->keycodes, server->keyboard->num_keycodes,
                &server->keyboard->modifiers);
            return;
        }
    }

    wl_list_for_each(ct, &server->toplevels, link) {
        if (ct->toplevel && ct->toplevel->base) {
            wlr_seat_keyboard_notify_enter(server->seat, ct->toplevel->base->surface,
                server->keyboard->keycodes, server->keyboard->num_keycodes,
                &server->keyboard->modifiers);
            return;
        }
    }
}

static void notify_synthetic_key(struct compositor_server *server,
                                 struct wlr_keyboard_key_event *ev) {
    wlr_keyboard_notify_key(server->keyboard, ev);
    if (server->seat) {
        wlr_seat_keyboard_notify_key(server->seat, ev->time_msec,
                                     ev->keycode, ev->state);
    }
}

static void emit_synthetic_keys(struct compositor_server *server,
                                 const char *text) {
    if (!server->keyboard) return;
    ensure_keyboard_focus(server);
    if (!ascii_table_built) build_ascii_table(server->keyboard);

    uint32_t timestamp = 0;

    const char *p = text;
    while (*p) {
        /* Decode one UTF-8 character to a Unicode codepoint */
        unsigned char c = (unsigned char)*p;
        uint32_t codepoint = 0;
        int bytes = 0;

        if (c < 0x80) {
            codepoint = c;
            bytes = 1;
        } else if (c < 0xC0) {
            p++; continue; /* stray continuation byte, skip */
        } else if (c < 0xE0) {
            codepoint = c & 0x1F;
            bytes = 2;
        } else if (c < 0xF0) {
            codepoint = c & 0x0F;
            bytes = 3;
        } else {
            codepoint = c & 0x07;
            bytes = 4;
        }

        for (int i = 1; i < bytes; i++) {
            if ((p[i] & 0xC0) != 0x80) { codepoint = 0; break; }
            codepoint = (codepoint << 6) | (p[i] & 0x3F);
        }
        p += bytes;

        if (codepoint == 0 || codepoint >= ASCII_MAX) {
            LOGD("Skipping non-ASCII codepoint U+%04X", codepoint);
            continue;
        }

        xkb_keycode_t evdev_kc = ascii_to_key[codepoint].keycode;
        if (evdev_kc == 0) {
            LOGD("No keycode mapping for U+%04X", codepoint);
            continue;
        }

        struct wlr_keyboard_key_event ev = {
            .time_msec = timestamp++,
            .update_state = true,
        };

        /* Press Shift if needed */
        if (ascii_to_key[codepoint].needs_shift) {
            ev.keycode = EVDEV_KEY_LEFTSHIFT;
            ev.state = WL_KEYBOARD_KEY_STATE_PRESSED;
            notify_synthetic_key(server, &ev);
        }

        /* Key press */
        ev.keycode = evdev_kc;
        ev.state = WL_KEYBOARD_KEY_STATE_PRESSED;
        ev.time_msec = timestamp++;
        notify_synthetic_key(server, &ev);

        /* Key release */
        ev.state = WL_KEYBOARD_KEY_STATE_RELEASED;
        ev.time_msec = timestamp++;
        notify_synthetic_key(server, &ev);

        /* Release Shift */
        if (ascii_to_key[codepoint].needs_shift) {
            ev.keycode = EVDEV_KEY_LEFTSHIFT;
            ev.state = WL_KEYBOARD_KEY_STATE_RELEASED;
            ev.time_msec = timestamp++;
            notify_synthetic_key(server, &ev);
        }
    }
}

/* ------------------------------------------------------------------ */
/* text-input-v3 protocol implementation                               */
/* ------------------------------------------------------------------ */

/** Per-client text input resource state. */
struct text_input {
    struct wl_resource *resource;
    struct wl_list link;  /* in text_inputs list */
    bool enabled;
    uint32_t serial;

    bool pending_cursor_rectangle_set;
    int32_t pending_cursor_x;
    int32_t pending_cursor_y;
    int32_t pending_cursor_width;
    int32_t pending_cursor_height;

    bool cursor_rectangle_set;
    int32_t cursor_x;
    int32_t cursor_y;
    int32_t cursor_width;
    int32_t cursor_height;
};

static struct wl_list text_inputs;           /* all text_input resources */
static struct text_input *active_text_input = NULL;
static struct compositor_server *g_text_input_server = NULL;

struct pending_text_commit {
    char *text;
    struct pending_text_commit *next;
};

static pthread_mutex_t pending_text_mutex = PTHREAD_MUTEX_INITIALIZER;
static struct pending_text_commit *pending_text_head = NULL;
static struct pending_text_commit *pending_text_tail = NULL;

static int on_text_input_event(int fd, uint32_t mask, void *data);
static void clear_pending_text_commits(void);

/* ---- Pipe helpers: signal Kotlin to show/hide IME ---- */

static void request_ime_show(struct compositor_server *server) {
    if (server->ime_request_pipe[1] >= 0) {
        char cmd = 'S'; /* Show */
        (void)write(server->ime_request_pipe[1], &cmd, 1);
    }
}

static void request_ime_hide(struct compositor_server *server) {
    if (server->ime_request_pipe[1] >= 0) {
        char cmd = 'H'; /* Hide */
        (void)write(server->ime_request_pipe[1], &cmd, 1);
    }
}

/* ---- zwp_text_input_v3 request handlers ---- */

static void ti_destroy(struct wl_client *client,
                        struct wl_resource *resource) {
    (void)client;
    wl_resource_destroy(resource);
}

static void ti_enable(struct wl_client *client,
                       struct wl_resource *resource) {
    (void)client;
    struct text_input *ti = wl_resource_get_user_data(resource);
    ti->enabled = true;
    active_text_input = ti;

    /* Ask Kotlin to show the soft keyboard */
    if (g_text_input_server) {
        request_ime_show(g_text_input_server);
    }
    LOGD("text-input-v3: enabled");
}

static void ti_disable(struct wl_client *client,
                        struct wl_resource *resource) {
    (void)client;
    struct text_input *ti = wl_resource_get_user_data(resource);
    ti->enabled = false;
    if (active_text_input == ti) {
        active_text_input = NULL;
    }

    /* Ask Kotlin to hide the soft keyboard */
    if (g_text_input_server) {
        request_ime_hide(g_text_input_server);
    }
    LOGD("text-input-v3: disabled");
}

static void ti_set_surrounding_text(struct wl_client *client,
                                     struct wl_resource *resource,
                                     const char *text,
                                     int32_t cursor,
                                     int32_t anchor) {
    (void)client; (void)resource; (void)text; (void)cursor; (void)anchor;
    /* Noted but not forwarded to Android IME currently. */
}

static void ti_set_text_change_cause(struct wl_client *client,
                                      struct wl_resource *resource,
                                      uint32_t cause) {
    (void)client; (void)resource; (void)cause;
}

static void ti_set_content_type(struct wl_client *client,
                                 struct wl_resource *resource,
                                 uint32_t hint,
                                 uint32_t purpose) {
    (void)client; (void)resource; (void)hint; (void)purpose;
}

static void ti_set_cursor_rectangle(struct wl_client *client,
                                     struct wl_resource *resource,
                                     int32_t x, int32_t y,
                                     int32_t width, int32_t height) {
    (void)client;
    struct text_input *ti = wl_resource_get_user_data(resource);
    if (!ti) return;

    ti->pending_cursor_rectangle_set = true;
    ti->pending_cursor_x = x;
    ti->pending_cursor_y = y;
    ti->pending_cursor_width = width;
    ti->pending_cursor_height = height;
}

static void ti_commit(struct wl_client *client,
                       struct wl_resource *resource) {
    (void)client;
    struct text_input *ti = wl_resource_get_user_data(resource);
    if (!ti) return;

    if (ti->pending_cursor_rectangle_set) {
        ti->cursor_rectangle_set = true;
        ti->cursor_x = ti->pending_cursor_x;
        ti->cursor_y = ti->pending_cursor_y;
        ti->cursor_width = ti->pending_cursor_width;
        ti->cursor_height = ti->pending_cursor_height;
        ti->pending_cursor_rectangle_set = false;
        LOGD("text-input-v3: cursor rectangle %d,%d %dx%d",
             ti->cursor_x, ti->cursor_y, ti->cursor_width, ti->cursor_height);
    }
}

/** Called when the text_input wl_resource is destroyed. */
static void ti_resource_destroy(struct wl_resource *resource) {
    struct text_input *ti = wl_resource_get_user_data(resource);
    if (!ti) return;

    if (active_text_input == ti) {
        active_text_input = NULL;
    }
    wl_list_remove(&ti->link);
    free(ti);
}

static const struct zwp_text_input_v3_interface text_input_impl = {
    .destroy = ti_destroy,
    .enable = ti_enable,
    .disable = ti_disable,
    .set_surrounding_text = ti_set_surrounding_text,
    .set_text_change_cause = ti_set_text_change_cause,
    .set_content_type = ti_set_content_type,
    .set_cursor_rectangle = ti_set_cursor_rectangle,
    .commit = ti_commit,
};

/* ---- zwp_text_input_manager_v3 ---- */

static void manager_destroy(struct wl_client *client,
                             struct wl_resource *resource) {
    (void)client;
    wl_resource_destroy(resource);
}

static void manager_get_text_input(struct wl_client *client,
                                    struct wl_resource *resource,
                                    uint32_t id,
                                    struct wl_resource *seat) {
    (void)seat;
    struct text_input *ti = calloc(1, sizeof(*ti));
    if (!ti) {
        wl_client_post_no_memory(client);
        return;
    }

    ti->resource = wl_resource_create(client,
        &zwp_text_input_v3_interface,
        wl_resource_get_version(resource), id);
    if (!ti->resource) {
        free(ti);
        wl_client_post_no_memory(client);
        return;
    }

    wl_resource_set_implementation(ti->resource, &text_input_impl,
                                   ti, ti_resource_destroy);
    wl_list_insert(&text_inputs, &ti->link);
    LOGD("text-input-v3: new text_input created");
}

static const struct zwp_text_input_manager_v3_interface manager_impl = {
    .destroy = manager_destroy,
    .get_text_input = manager_get_text_input,
};

static void bind_manager(struct wl_client *client, void *data,
                          uint32_t version, uint32_t id) {
    (void)data;
    struct wl_resource *resource = wl_resource_create(client,
        &zwp_text_input_manager_v3_interface, version, id);
    if (!resource) {
        wl_client_post_no_memory(client);
        return;
    }
    wl_resource_set_implementation(resource, &manager_impl, data, NULL);
}

/* ------------------------------------------------------------------ */
/* Public API                                                          */
/* ------------------------------------------------------------------ */

int text_input_handler_init(struct compositor_server *server) {
    wl_list_init(&text_inputs);
    g_text_input_server = server;
    server->ime_request_pipe[0] = -1;
    server->ime_request_pipe[1] = -1;
    server->text_input_pipe[0] = -1;
    server->text_input_pipe[1] = -1;

    /* IME request pipe: compositor writes 'S'/'H', Kotlin reads. */
    if (pipe2(server->ime_request_pipe, O_CLOEXEC | O_NONBLOCK) != 0) {
        wlr_log(WLR_ERROR, "text_input: pipe2 failed");
        return -1;
    }

    /* Text commit pipe: JNI thread queues text, compositor event loop sends protocol events. */
    if (pipe2(server->text_input_pipe, O_CLOEXEC | O_NONBLOCK) != 0) {
        wlr_log(WLR_ERROR, "text_input: text pipe2 failed");
        close(server->ime_request_pipe[0]);
        close(server->ime_request_pipe[1]);
        server->ime_request_pipe[0] = -1;
        server->ime_request_pipe[1] = -1;
        return -1;
    }
    struct wl_event_loop *ev_loop = wl_display_get_event_loop(server->wl_display);
    server->text_input_event_source = wl_event_loop_add_fd(
        ev_loop, server->text_input_pipe[0], WL_EVENT_READABLE,
        on_text_input_event, server);

    /* Register zwp_text_input_manager_v3 global (version 1). */
    server->text_input_manager = wl_global_create(server->wl_display,
        &zwp_text_input_manager_v3_interface, 1, server, bind_manager);
    if (!server->text_input_manager) {
        wlr_log(WLR_ERROR, "text_input: wl_global_create failed");
        if (server->text_input_event_source) {
            wl_event_source_remove(server->text_input_event_source);
            server->text_input_event_source = NULL;
        }
        close(server->ime_request_pipe[0]);
        close(server->ime_request_pipe[1]);
        close(server->text_input_pipe[0]);
        close(server->text_input_pipe[1]);
        server->ime_request_pipe[0] = -1;
        server->ime_request_pipe[1] = -1;
        server->text_input_pipe[0] = -1;
        server->text_input_pipe[1] = -1;
        return -1;
    }

    /* Pre-build the ASCII keycode table if keyboard is already up. */
    if (server->keyboard) {
        build_ascii_table(server->keyboard);
    }

    wlr_log(WLR_INFO, "Text input handler initialized");
    return 0;
}

void text_input_handler_destroy(struct compositor_server *server) {
    if (server->text_input_manager) {
        wl_global_destroy(server->text_input_manager);
        server->text_input_manager = NULL;
    }

    if (server->ime_request_pipe[0] >= 0) {
        close(server->ime_request_pipe[0]);
        close(server->ime_request_pipe[1]);
        server->ime_request_pipe[0] = -1;
        server->ime_request_pipe[1] = -1;
    }
    if (server->text_input_event_source) {
        wl_event_source_remove(server->text_input_event_source);
        server->text_input_event_source = NULL;
    }
    if (server->text_input_pipe[0] >= 0) {
        close(server->text_input_pipe[0]);
        close(server->text_input_pipe[1]);
        server->text_input_pipe[0] = -1;
        server->text_input_pipe[1] = -1;
    }
    clear_pending_text_commits();

    active_text_input = NULL;
    g_text_input_server = NULL;
}

static void dispatch_commit_text(struct compositor_server *server,
                                 const char *text) {
    if (!text || !*text) return;

    if (active_text_input && active_text_input->enabled) {
        /* Send via text-input-v3 protocol on the compositor event loop. */
        zwp_text_input_v3_send_commit_string(active_text_input->resource, text);
        active_text_input->serial++;
        zwp_text_input_v3_send_done(active_text_input->resource,
                                     active_text_input->serial);
        LOGD("Committed text via text-input-v3 (%zu bytes)", strlen(text));
    } else {
        /* Fallback: inject synthetic keyboard events through the seat path. */
        emit_synthetic_keys(server, text);
        LOGD("Committed text via synthetic keys (%zu bytes)", strlen(text));
    }
}

static void enqueue_text_commit(const char *text) {
    struct pending_text_commit *commit = calloc(1, sizeof(*commit));
    if (!commit) return;
    commit->text = strdup(text);
    if (!commit->text) {
        free(commit);
        return;
    }

    pthread_mutex_lock(&pending_text_mutex);
    if (pending_text_tail) {
        pending_text_tail->next = commit;
    } else {
        pending_text_head = commit;
    }
    pending_text_tail = commit;
    pthread_mutex_unlock(&pending_text_mutex);
}

static struct pending_text_commit *pop_text_commit(void) {
    pthread_mutex_lock(&pending_text_mutex);
    struct pending_text_commit *commit = pending_text_head;
    if (commit) {
        pending_text_head = commit->next;
        if (!pending_text_head) {
            pending_text_tail = NULL;
        }
    }
    pthread_mutex_unlock(&pending_text_mutex);
    return commit;
}

static void clear_pending_text_commits(void) {
    struct pending_text_commit *commit;
    while ((commit = pop_text_commit()) != NULL) {
        free(commit->text);
        free(commit);
    }
}

static int on_text_input_event(int fd, uint32_t mask, void *data) {
    (void)mask;
    struct compositor_server *server = data;

    char buf[64];
    ssize_t n;
    do {
        n = read(fd, buf, sizeof(buf));
    } while (n > 0);
    if (n < 0 && errno != EAGAIN) {
        wlr_log(WLR_ERROR, "text input pipe read error: %s", strerror(errno));
        return 0;
    }

    struct pending_text_commit *commit;
    while ((commit = pop_text_commit()) != NULL) {
        dispatch_commit_text(server, commit->text);
        free(commit->text);
        free(commit);
    }
    return 0;
}

void text_input_handle_commit_text(struct compositor_server *server,
                                    const char *text) {
    if (!server || !text || !*text) return;
    enqueue_text_commit(text);
    if (server->text_input_pipe[1] >= 0) {
        char c = 't';
        (void)write(server->text_input_pipe[1], &c, 1);
    }
}

bool text_input_has_active_text_input(void) {
    return active_text_input && active_text_input->enabled;
}

void text_input_handle_ime_shown(struct compositor_server *server) {
    (void)server;
    LOGD("IME shown");
}

void text_input_handle_ime_hidden(struct compositor_server *server) {
    (void)server;
    LOGD("IME hidden");
}
