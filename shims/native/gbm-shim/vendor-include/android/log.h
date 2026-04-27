/*
 * log.h — Vendored minimal stub for cross-compilation
 *
 * Provides just enough of the Android NDK <android/log.h> interface
 * for gbm_ahb.c to compile with aarch64-linux-gnu-gcc.
 */
#ifndef ANDROID_LOG_H_STUB
#define ANDROID_LOG_H_STUB

typedef enum android_LogPriority {
    ANDROID_LOG_DEBUG = 3,
    ANDROID_LOG_ERROR = 6,
} android_LogPriority;

int __android_log_print(int prio, const char *tag, const char *fmt, ...)
    __attribute__((format(printf, 3, 4)));

#endif /* ANDROID_LOG_H_STUB */
