/*
 * jni_bridge.c — JNI entry points for the WLDroid Wayland compositor
 *
 * Native methods are registered dynamically via JNI_OnLoad + RegisterNatives
 * on the configurable class nu.shel.wldroid.compositor.CompositorServer.
 *
 * Exposed methods:
 *   nativeStartCompositor(Surface, String, String, boolean)
 *   nativeStopCompositor()
 *   nativeGetSocketName() → String
 *   nativeGetClientCount() → int
 *   nativeGetXWaylandDisplay() → String
 *   nativeResizeOutput(int, int)
 *   nativePauseCompositor()
 *   nativeResumeCompositor(Surface)
 *   nativeSendTouchEvent(int, int, float, float, long)
 *   nativeSendKeyEvent(int, int, long)
 *   nativeSendPointerMotion(float, float, long)
 *   nativeSendPointerButton(int, int, long)
 *   nativeSendPointerScroll(float, float, long)
 *   nativeCommitText(String)
 *   nativeImeShown()
 *   nativeImeHidden()
 *   nativeGetImePipeFd() → int
 *   nativeStartTestClient()
 *
 * The compositor event loop runs on a dedicated pthread so it never blocks
 * the Android UI thread.
 */
#include <jni.h>
#include <errno.h>
#include <pthread.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>

#include "compositor_server.h"
#include "input_handler.h"
#include "text_input_handler.h"
#include "test_client.h"

#define LOG_TAG "WLDroidCompositor"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

/* ------------------------------------------------------------------ */
/* JNI class path — configurable for embedding in different packages   */
/* ------------------------------------------------------------------ */

#ifndef JNI_CLASS_PATH
#define JNI_CLASS_PATH "nu/shel/wldroid/compositor/CompositorServer"
#endif

/* ------------------------------------------------------------------ */
/* Global state (single compositor instance)                           */
/* ------------------------------------------------------------------ */

static struct compositor_server *g_server = NULL;
static pthread_mutex_t g_server_mutex = PTHREAD_MUTEX_INITIALIZER;
static pthread_t g_compositor_thread;
static volatile int g_thread_running = 0;

/* ------------------------------------------------------------------ */
/* Event-loop thread                                                   */
/* ------------------------------------------------------------------ */

static void *compositor_thread_func(void *arg) {
    struct compositor_server *server = (struct compositor_server *)arg;
    LOGI("Compositor event loop starting");
    compositor_server_run(server);
    LOGI("Compositor event loop exited");
    return NULL;
}

/* ------------------------------------------------------------------ */
/* Native method implementations                                       */
/* ------------------------------------------------------------------ */

static void native_start_compositor(JNIEnv *env, jobject thiz,
                                    jobject surface, jstring cache_dir,
                                    jstring xkb_base_path,
                                    jboolean xwayland_enabled) {
    (void)thiz;

    pthread_mutex_lock(&g_server_mutex);
    if (g_server) {
        LOGE("Compositor already running — ignoring start request");
        pthread_mutex_unlock(&g_server_mutex);
        return;
    }

    /* Obtain the ANativeWindow from the Java Surface. */
    ANativeWindow *window = ANativeWindow_fromSurface(env, surface);
    if (!window) {
        LOGE("ANativeWindow_fromSurface returned NULL");
        pthread_mutex_unlock(&g_server_mutex);
        return;
    }

    /*
     * Wayland's wl_display_add_socket_auto() requires XDG_RUNTIME_DIR to
     * exist and be set. Android doesn't provide this by default.
     * Use the app's cacheDir/wayland-runtime (passed from Kotlin) which is
     * app-private and writable by the same UID. The proot child process
     * shares our UID, so no world-accessible permissions are needed.
     *
     * Always overwrite this process-global env var for the session being
     * started. A prior compositor session or host app may have set a different
     * runtime dir, and leaving that stale value in place makes the compositor
     * and launcher disagree about where the socket lives.
     */
    const char *dir = (*env)->GetStringUTFChars(env, cache_dir, NULL);
    if (dir) {
        LOGI("Setting XDG_RUNTIME_DIR=%s", dir);
        /*
         * Note: setenv() is not thread-safe per POSIX, but this is called
         * early during JNI initialization before the compositor thread starts,
         * so there are no concurrent readers.
         */
        setenv("XDG_RUNTIME_DIR", dir, 1);
        if (mkdir(dir, 0700) != 0 && errno != EEXIST) {
            LOGE("mkdir %s failed: %s", dir, strerror(errno));
        }
        (*env)->ReleaseStringUTFChars(env, cache_dir, dir);
    }

    /* Point xkbcommon at the bundled xkb-data extracted from app assets. */
    if (xkb_base_path) {
        const char *xkb_path = (*env)->GetStringUTFChars(env, xkb_base_path, NULL);
        if (xkb_path) {
            LOGI("Setting XKB_CONFIG_ROOT=%s", xkb_path);
            setenv("XKB_CONFIG_ROOT", xkb_path, 1);
            (*env)->ReleaseStringUTFChars(env, xkb_base_path, xkb_path);
        }
    }

    g_server = compositor_server_create(window, xwayland_enabled);
    /* The compositor's output now holds its own ANativeWindow reference,
     * so we always release the JNI-obtained reference. */
    ANativeWindow_release(window);
    if (!g_server) {
        LOGE("compositor_server_create failed");
        pthread_mutex_unlock(&g_server_mutex);
        return;
    }

    LOGI("Wayland socket: %s", compositor_server_get_socket(g_server));

    /* Spawn the event-loop thread. */
    g_thread_running = 1;
    int rc = pthread_create(&g_compositor_thread, NULL,
                            compositor_thread_func, g_server);
    if (rc != 0) {
        LOGE("pthread_create failed: %d", rc);
        compositor_server_destroy(g_server);
        g_server = NULL;
        g_thread_running = 0;
        pthread_mutex_unlock(&g_server_mutex);
        return;
    }

    LOGI("Compositor started on background thread");
    pthread_mutex_unlock(&g_server_mutex);
}

static void native_stop_compositor(JNIEnv *env, jobject thiz) {
    (void)env;
    (void)thiz;

    pthread_mutex_lock(&g_server_mutex);
    if (!g_server) {
        LOGI("No compositor running — nothing to stop");
        pthread_mutex_unlock(&g_server_mutex);
        return;
    }

    LOGI("Stopping compositor...");

    /* Ask the event loop to exit (thread-safe). */
    compositor_server_stop(g_server);

    /* Wait for the thread to finish. */
    if (g_thread_running) {
        pthread_join(g_compositor_thread, NULL);
        g_thread_running = 0;
    }

    compositor_server_destroy(g_server);
    g_server = NULL;

    LOGI("Compositor stopped and destroyed");
    pthread_mutex_unlock(&g_server_mutex);
}

static jint native_get_client_count(JNIEnv *env, jobject thiz) {
    (void)env;
    (void)thiz;
    jint count = 0;
    pthread_mutex_lock(&g_server_mutex);
    if (g_server) {
        count = compositor_server_get_client_count(g_server);
    }
    pthread_mutex_unlock(&g_server_mutex);
    return count;
}

static jstring native_get_socket_name(JNIEnv *env, jobject thiz) {
    (void)thiz;

    pthread_mutex_lock(&g_server_mutex);
    if (!g_server) {
        pthread_mutex_unlock(&g_server_mutex);
        return NULL;
    }
    const char *name = compositor_server_get_socket(g_server);
    jstring result = name ? (*env)->NewStringUTF(env, name) : NULL;
    pthread_mutex_unlock(&g_server_mutex);
    return result;
}

static jstring native_get_xwayland_display(JNIEnv *env, jobject thiz) {
    (void)thiz;

    pthread_mutex_lock(&g_server_mutex);
    if (!g_server) {
        pthread_mutex_unlock(&g_server_mutex);
        return NULL;
    }
    const char *name = compositor_server_get_xwayland_display(g_server);
    jstring result = name ? (*env)->NewStringUTF(env, name) : NULL;
    pthread_mutex_unlock(&g_server_mutex);
    return result;
}

static void native_resize_output(JNIEnv *env, jobject thiz,
                                 jint width, jint height) {
    (void)env;
    (void)thiz;
    pthread_mutex_lock(&g_server_mutex);
    if (g_server) {
        compositor_server_resize_output(g_server, width, height);
    }
    pthread_mutex_unlock(&g_server_mutex);
}

/* ------------------------------------------------------------------ */
/* Input forwarding                                                    */
/* ------------------------------------------------------------------ */

static void native_send_touch_event(JNIEnv *env, jobject thiz,
                                    jint id, jint action,
                                    jfloat x, jfloat y, jlong timestampMs) {
    (void)env; (void)thiz;
    pthread_mutex_lock(&g_server_mutex);
    if (!g_server) {
        pthread_mutex_unlock(&g_server_mutex);
        return;
    }
    uint32_t ts = (uint32_t)timestampMs;
    /* Android MotionEvent action constants */
    #define ANDROID_ACTION_DOWN   0
    #define ANDROID_ACTION_UP     1
    #define ANDROID_ACTION_MOVE   2
    #define ANDROID_ACTION_CANCEL 3

    switch (action) {
    case ANDROID_ACTION_DOWN:
        input_handler_send_touch_down(g_server, id, (double)x, (double)y, ts);
        break;
    case ANDROID_ACTION_UP:
        input_handler_send_touch_up(g_server, id, ts);
        break;
    case ANDROID_ACTION_MOVE:
        input_handler_send_touch_motion(g_server, id, (double)x, (double)y, ts);
        break;
    case ANDROID_ACTION_CANCEL:
        input_handler_send_touch_up(g_server, id, ts);
        break;
    }

    #undef ANDROID_ACTION_DOWN
    #undef ANDROID_ACTION_UP
    #undef ANDROID_ACTION_MOVE
    #undef ANDROID_ACTION_CANCEL
    pthread_mutex_unlock(&g_server_mutex);
}

static void native_send_key_event(JNIEnv *env, jobject thiz,
                                  jint androidKeyCode, jint action,
                                  jlong timestampMs) {
    (void)env; (void)thiz;
    pthread_mutex_lock(&g_server_mutex);
    if (!g_server) {
        pthread_mutex_unlock(&g_server_mutex);
        return;
    }
    /* action: 0 = KEY_DOWN (pressed), 1 = KEY_UP (released) */
    input_handler_send_key(g_server, androidKeyCode,
                           action == 0 ? 1 : 0,
                           (uint32_t)timestampMs);
    pthread_mutex_unlock(&g_server_mutex);
}

static void native_send_pointer_motion(JNIEnv *env, jobject thiz,
                                       jfloat x, jfloat y,
                                       jlong timestampMs) {
    (void)env; (void)thiz;
    pthread_mutex_lock(&g_server_mutex);
    if (!g_server) {
        pthread_mutex_unlock(&g_server_mutex);
        return;
    }
    input_handler_send_pointer_motion(g_server,
                                      (double)x, (double)y,
                                      (uint32_t)timestampMs);
    pthread_mutex_unlock(&g_server_mutex);
}

static void native_send_pointer_button(JNIEnv *env, jobject thiz,
                                       jint button, jint action,
                                       jlong timestampMs) {
    (void)env; (void)thiz;
    pthread_mutex_lock(&g_server_mutex);
    if (!g_server) {
        pthread_mutex_unlock(&g_server_mutex);
        return;
    }
    /* action: 0 = pressed, 1 = released */
    input_handler_send_pointer_button(g_server,
                                      (uint32_t)button,
                                      action == 0 ? 1 : 0,
                                      (uint32_t)timestampMs);
    pthread_mutex_unlock(&g_server_mutex);
}

static void native_send_pointer_scroll(JNIEnv *env, jobject thiz,
                                       jfloat dx, jfloat dy,
                                       jlong timestampMs) {
    (void)env; (void)thiz;
    pthread_mutex_lock(&g_server_mutex);
    if (!g_server) {
        pthread_mutex_unlock(&g_server_mutex);
        return;
    }
    input_handler_send_pointer_scroll(g_server,
                                      (double)dx, (double)dy,
                                      (uint32_t)timestampMs);
    pthread_mutex_unlock(&g_server_mutex);
}

/* ------------------------------------------------------------------ */
/* Text input / IME                                                    */
/* ------------------------------------------------------------------ */

static void native_commit_text(JNIEnv *env, jobject thiz, jstring text) {
    (void)thiz;
    if (!text) return;
    pthread_mutex_lock(&g_server_mutex);
    if (!g_server) {
        pthread_mutex_unlock(&g_server_mutex);
        return;
    }
    const char *str = (*env)->GetStringUTFChars(env, text, NULL);
    if (str) {
        text_input_handle_commit_text(g_server, str);
        (*env)->ReleaseStringUTFChars(env, text, str);
    }
    pthread_mutex_unlock(&g_server_mutex);
}

static void native_ime_shown(JNIEnv *env, jobject thiz) {
    (void)env; (void)thiz;
    pthread_mutex_lock(&g_server_mutex);
    if (g_server) text_input_handle_ime_shown(g_server);
    pthread_mutex_unlock(&g_server_mutex);
}

static void native_ime_hidden(JNIEnv *env, jobject thiz) {
    (void)env; (void)thiz;
    pthread_mutex_lock(&g_server_mutex);
    if (g_server) text_input_handle_ime_hidden(g_server);
    pthread_mutex_unlock(&g_server_mutex);
}

static jint native_get_ime_pipe_fd(JNIEnv *env, jobject thiz) {
    (void)env; (void)thiz;
    pthread_mutex_lock(&g_server_mutex);
    if (!g_server) {
        pthread_mutex_unlock(&g_server_mutex);
        return -1;
    }
    jint fd = g_server->ime_request_pipe[0];
    pthread_mutex_unlock(&g_server_mutex);
    return fd;
}

/* ------------------------------------------------------------------ */
/* Test client                                                         */
/* ------------------------------------------------------------------ */

static void native_start_test_client(JNIEnv *env, jobject thiz) {
    (void)env; (void)thiz;
    pthread_mutex_lock(&g_server_mutex);
    if (!g_server) {
        LOGW("nativeStartTestClient: no server");
        pthread_mutex_unlock(&g_server_mutex);
        return;
    }
    if (test_client_start(g_server) != 0) {
        LOGW("nativeStartTestClient: failed to start test client");
    }
    pthread_mutex_unlock(&g_server_mutex);
}

/* ------------------------------------------------------------------ */
/* Pause / resume                                                      */
/* ------------------------------------------------------------------ */

static void native_pause_compositor(JNIEnv *env, jobject thiz) {
    (void)env;
    (void)thiz;

    pthread_mutex_lock(&g_server_mutex);
    if (!g_server || !g_thread_running) {
        pthread_mutex_unlock(&g_server_mutex);
        return;
    }
    compositor_server_pause(g_server);
    pthread_mutex_unlock(&g_server_mutex);
}

static void native_resume_compositor(JNIEnv *env, jobject thiz,
                                     jobject surface) {
    (void)thiz;

    pthread_mutex_lock(&g_server_mutex);
    if (!g_server || !g_thread_running) {
        pthread_mutex_unlock(&g_server_mutex);
        return;
    }
    ANativeWindow *window = ANativeWindow_fromSurface(env, surface);
    if (!window) {
        LOGE("native_resume_compositor: ANativeWindow_fromSurface returned NULL");
        pthread_mutex_unlock(&g_server_mutex);
        return;
    }
    compositor_server_resume(g_server, window);
    ANativeWindow_release(window);  /* resume() acquires its own ref */
    pthread_mutex_unlock(&g_server_mutex);
}

/* ------------------------------------------------------------------ */
/* JNI_OnLoad — dynamic registration via RegisterNatives               */
/* ------------------------------------------------------------------ */

static const JNINativeMethod g_methods[] = {
    /* Compositor lifecycle */
    {"nativeStartCompositor",  "(Landroid/view/Surface;Ljava/lang/String;Ljava/lang/String;Z)V",
                                (void *)native_start_compositor},
    {"nativeStopCompositor",   "()V",
                                (void *)native_stop_compositor},
    {"nativeGetSocketName",    "()Ljava/lang/String;",
                                (void *)native_get_socket_name},
    {"nativeGetClientCount",   "()I",
                                (void *)native_get_client_count},
    {"nativeGetXWaylandDisplay", "()Ljava/lang/String;",
                                (void *)native_get_xwayland_display},
    {"nativeResizeOutput",     "(II)V",
                                (void *)native_resize_output},
    {"nativePauseCompositor",  "()V",
                                (void *)native_pause_compositor},
    {"nativeResumeCompositor", "(Landroid/view/Surface;)V",
                                (void *)native_resume_compositor},

    /* Input */
    {"nativeSendTouchEvent",   "(IIFFJ)V",
                                (void *)native_send_touch_event},
    {"nativeSendKeyEvent",     "(IIJ)V",
                                (void *)native_send_key_event},
    {"nativeSendPointerMotion","(FFJ)V",
                                (void *)native_send_pointer_motion},
    {"nativeSendPointerButton","(IIJ)V",
                                (void *)native_send_pointer_button},
    {"nativeSendPointerScroll","(FFJ)V",
                                (void *)native_send_pointer_scroll},

    /* IME */
    {"nativeCommitText",       "(Ljava/lang/String;)V",
                                (void *)native_commit_text},
    {"nativeImeShown",         "()V",
                                (void *)native_ime_shown},
    {"nativeImeHidden",        "()V",
                                (void *)native_ime_hidden},
    {"nativeGetImePipeFd",     "()I",
                                (void *)native_get_ime_pipe_fd},

    /* Test */
    {"nativeStartTestClient",  "()V",
                                (void *)native_start_test_client},
};

JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void)reserved;
    JNIEnv *env;
    if ((*vm)->GetEnv(vm, (void **)&env, JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }

    jclass clazz = (*env)->FindClass(env, JNI_CLASS_PATH);
    if (!clazz) {
        LOGE("JNI_OnLoad: class not found: %s", JNI_CLASS_PATH);
        return JNI_ERR;
    }

    int count = sizeof(g_methods) / sizeof(g_methods[0]);
    if ((*env)->RegisterNatives(env, clazz, g_methods, count) < 0) {
        LOGE("JNI_OnLoad: RegisterNatives failed for %s", JNI_CLASS_PATH);
        return JNI_ERR;
    }

    LOGI("JNI_OnLoad: registered %d native methods on %s", count, JNI_CLASS_PATH);
    return JNI_VERSION_1_6;
}
