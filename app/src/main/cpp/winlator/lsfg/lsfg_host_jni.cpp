// LSFG host-side diagnostic + cache-build JNI entry points. De-risk verdict:
// runs the host device-feature probe and the DXBC->SPIR-V translation of the
// user's Lossless.dll, then logs one line under tag "LSFG-HOST" that a Fold/
// AYANEO diagnostic capture (WinFgDiag streams *:V) reports back verbatim.
//
// Compiled directly into libvulkan_renderer.so so the JNI symbols are exported;
// includes only the clean C headers (no vk_dispatch.h dispatch macros).
#include <jni.h>
#include <android/log.h>
#include <time.h>

#include <cstdio>
#include <string>

#include "lsfg_probe.h"
#include "lsfg_dll.h"
#include "lsfg_host_bridge.h"

#define LOG_TAG "LSFG-HOST"
#define HOST_LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

#define SPIRV_MAGIC 0x07230203u

namespace {

std::string yn(bool v) { return v ? "PASS" : "FAIL"; }

int64_t now_ns() {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int64_t)ts.tv_sec * 1000000000LL + (int64_t)ts.tv_nsec;
}

const char* variant_name(LsfgVariant v) {
    switch (v) {
        case LSFG_VARIANT_FP16: return "spirv-fp16";
        case LSFG_VARIANT_FP32: return "spirv-fp32";
        case LSFG_VARIANT_DXBC: return "dxbc-translated";
        default: return "none";
    }
}

const char* status_name(int s) {
    switch (s) {
        case LSFG_OK: return "OK";
        case LSFG_NOT_INSTALLED: return "NOT_INSTALLED";
        case LSFG_UNREADABLE_FILE: return "UNREADABLE_FILE";
        case LSFG_NOT_PORTABLE_EXECUTABLE: return "NOT_PORTABLE_EXECUTABLE";
        case LSFG_MISSING_SHADERS: return "MISSING_SHADERS";
        case LSFG_TRANSLATION_FAILED: return "TRANSLATION_FAILED";
        case LSFG_CACHE_UNUSABLE: return "CACHE_UNUSABLE";
        default: return "UNKNOWN";
    }
}

std::string run_diag(const char* dll_path, const char* cache_path) {
    // 1) host device-feature probe (self-contained; system libvulkan.so)
    LsfgProbeResult pr;
    lsfg_probe_host(&pr);
    char probe_buf[512];
    snprintf(probe_buf, sizeof(probe_buf),
             "probe[dev='%s' vk=%u.%u %s memModel=%s storeWWF=%s extFmt=%s computeQ=%s fmts=%s "
             "-> %s]",
             pr.have_device ? pr.device_name : "none", pr.api_major, pr.api_minor,
             yn(pr.vulkan_1_3).c_str(), yn(pr.memory_model).c_str(),
             yn(pr.storage_write_without_format).c_str(), yn(pr.storage_extended_formats).c_str(),
             yn(pr.compute_queue).c_str(), yn(pr.required_formats).c_str(),
             pr.supported ? "SUPPORTED" : "UNSUPPORTED");

    // 2) DXBC->SPIR-V translation of the user's Lossless.dll into a shader cache
    const int64_t t0 = now_ns();
    const int status = LsfgHostBuildCache(dll_path, cache_path, /*prefer_fp16=*/false);
    const int64_t t1 = now_ns();
    const double elapsed_ms = (double)(t1 - t0) / 1.0e6;

    char trans_buf[512];
    if (status != LSFG_OK) {
        snprintf(trans_buf, sizeof(trans_buf), "translate[FAILED status=%d (%s) elapsed=%.1fms]",
                 status, status_name(status), elapsed_ms);
    } else {
        // 3) read the cache back, count words, validate SPIR-V magic per module
        LsfgModuleSet set;
        const int load = (int)lsfg_load_modules(cache_path, &set);
        if (load != LSFG_OK) {
            snprintf(trans_buf, sizeof(trans_buf),
                     "translate[cache built but reload FAILED status=%d (%s) elapsed=%.1fms]", load,
                     status_name(load), elapsed_ms);
        } else {
            uint64_t total_words = 0;
            uint32_t valid = 0;
            for (uint32_t i = 0; i < set.count; i++) {
                const LsfgModule& m = set.modules[i];
                total_words += m.word_count;
                if (m.words && m.word_count >= 5 && m.words[0] == SPIRV_MAGIC) valid++;
            }
            const double mb = (double)total_words * 4.0 / (1024.0 * 1024.0);
            snprintf(trans_buf, sizeof(trans_buf),
                     "translate[variant=%s modules=%u/%u words=%llu (%.2fMB) valid=%u/%u "
                     "elapsed=%.1fms]",
                     variant_name(set.variant), set.count, LSFG_SHADER_COUNT,
                     (unsigned long long)total_words, mb, valid, LSFG_SHADER_COUNT, elapsed_ms);
            lsfg_release_modules(&set);
        }
    }

    std::string verdict = std::string("verdict: ") + probe_buf + " | " + trans_buf;
    HOST_LOGI("%s", verdict.c_str());
    return verdict;
}

}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_winlator_star_core_LsfgHostDiag_nativeRunHostDiag(JNIEnv* env, jclass clazz,
                                                           jstring dllPath, jstring cachePath) {
    (void)clazz;
    const char* dll = dllPath ? env->GetStringUTFChars(dllPath, nullptr) : nullptr;
    const char* cache = cachePath ? env->GetStringUTFChars(cachePath, nullptr) : nullptr;

    std::string verdict;
    if (!dll || !cache) {
        verdict = "verdict: ERROR null path";
        HOST_LOGI("%s", verdict.c_str());
    } else {
        verdict = run_diag(dll, cache);
    }

    if (dll) env->ReleaseStringUTFChars(dllPath, dll);
    if (cache) env->ReleaseStringUTFChars(cachePath, cache);
    return env->NewStringUTF(verdict.c_str());
}
