// win-fg — logging. On Android goes to logcat (tag "win-fg") so device bring-up
// can trace the layer; elsewhere falls back to stderr.
#pragma once
#if defined(__ANDROID__)
  #include <android/log.h>
  #define WFG_LOGI(...) __android_log_print(ANDROID_LOG_INFO,  "win-fg", __VA_ARGS__)
  #define WFG_LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "win-fg", __VA_ARGS__)
#else
  #include <cstdio>
  #define WFG_LOGI(...) do { std::fprintf(stderr, "[win-fg] " __VA_ARGS__); std::fprintf(stderr, "\n"); } while(0)
  #define WFG_LOGE(...) do { std::fprintf(stderr, "[win-fg][E] " __VA_ARGS__); std::fprintf(stderr, "\n"); } while(0)
#endif

// Runtime-gated granular debug trace. The gate is a RUNTIME bool (Config::debug),
// not a compile-time switch — a shipping .so carries the full trail but pays only
// a predicted-not-taken bool test per step when debug is off (no logcat write, no
// arg evaluation of the message). Same "win-fg" tag so the app captures it under
// its own UID. Used to step the present path so a freeze leaves an obvious last
// line at the stage that hung. Usage: WFG_LOGD(dbg, "P#%llu step ...", n);
#define WFG_LOGD(cond, ...) do { if (cond) WFG_LOGI(__VA_ARGS__); } while(0)
