// KGSL GPU "turbo" (max clocks) — the NON-ROOT half of the "Lock GPU to max clock" toggle.
//
// adrenotools_set_turbo() issues IOCTL_KGSL_SETPROPERTY(KGSL_PROP_PWRCTRL) on a plain
// open("/dev/kgsl-3d0") — the same device node the Vulkan driver already uses, which the app can
// open itself. No su, no sysfs, so this works for every user. Adreno/KGSL only; on any other GPU
// the open() fails and the call is a silent no-op.
//
// The kernel's own thermal limits still apply on top of this (unlike the root sysfs path, which can
// be combined with thermal-disable). See perf/PerfGpuTurbo.kt for the Kotlin side.

#include <jni.h>
#include <adrenotools/driver.h>

JNIEXPORT void JNICALL
Java_com_winlator_star_perf_PerfGpuTurbo_nativeSetTurbo(JNIEnv *env, jclass clazz, jboolean on) {
    (void) env;
    (void) clazz;
    adrenotools_set_turbo(on == JNI_TRUE);
}
