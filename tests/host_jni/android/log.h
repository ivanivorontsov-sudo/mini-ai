// Host stub of <android/log.h> so ai_chat.cpp can be built and tested on Linux.
#pragma once
#include <stdio.h>
#include <stdarg.h>
enum { ANDROID_LOG_UNKNOWN, ANDROID_LOG_DEFAULT, ANDROID_LOG_VERBOSE, ANDROID_LOG_DEBUG, ANDROID_LOG_INFO, ANDROID_LOG_WARN, ANDROID_LOG_ERROR, ANDROID_LOG_FATAL, ANDROID_LOG_SILENT };
static inline int __android_log_is_loggable(int prio, const char *, int) { return prio >= ANDROID_LOG_WARN; }
static inline int __android_log_print(int, const char *tag, const char *fmt, ...) { va_list a; va_start(a, fmt); fprintf(stderr, "[%s] ", tag); int r = vfprintf(stderr, fmt, a); fputc('\n', stderr); va_end(a); return r; }
static inline int __android_log_write(int, const char *tag, const char *t) { return fprintf(stderr, "[%s] %s\n", tag, t); }
static inline int __android_log_vprint(int, const char *tag, const char *fmt, va_list a) { fprintf(stderr, "[%s] ", tag); return vfprintf(stderr, fmt, a); }
