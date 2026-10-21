// JNI helper: resolve the MobileGlues core already loaded by the renderer plugin
// and call its bridge entry points. This avoids loading a second copy with a
// separate EGL/GL state in the Minecraft class loader.
#include <jni.h>
#include <dlfcn.h>
#include <cstdio>

namespace {
void* g_core = nullptr;
using AvailableFn = jboolean (*)(JNIEnv*, jclass);
using SubmitFn = jint (*)(JNIEnv*, jclass);
using ScaleFn = jfloat (*)(JNIEnv*, jclass);
using ViewportFn = void (*)(JNIEnv*, jclass);
using CameraFn = void (*)(JNIEnv*, jclass, jfloatArray, jfloatArray, jdoubleArray);
using CameraInvalidFn = void (*)(JNIEnv*, jclass);
using BridgeModeFn = void (*)(JNIEnv*, jclass);
using TraceFn = jstring (*)(JNIEnv*, jclass, jint, jint, jint, jint);
using FinalScopeFn = void (*)(JNIEnv*, jclass, jboolean);
using SceneDepthFn = void (*)(JNIEnv*, jclass, jint);
AvailableFn g_available = nullptr;
SubmitFn g_submit = nullptr;
ScaleFn g_scale = nullptr;
ViewportFn g_viewport = nullptr;
CameraFn g_camera = nullptr;
CameraInvalidFn g_camera_invalid = nullptr;
BridgeModeFn g_bridge_mode = nullptr;
TraceFn g_trace = nullptr;
FinalScopeFn g_final_scope = nullptr;
SceneDepthFn g_scene_depth = nullptr;
} // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_com_googlebook_fsrbridge_NativeBridge_init(JNIEnv* env, jclass, jstring path) {
    if (g_core) return JNI_TRUE;
    // Never map a second core: the renderer must own and initialize this library first.
    const char* p = path ? env->GetStringUTFChars(path, nullptr) : nullptr;
    if (!p) return JNI_FALSE;
    void* candidate = dlopen(p, RTLD_NOW | RTLD_NOLOAD);
    env->ReleaseStringUTFChars(path, p);
    if (!candidate) return JNI_FALSE;
    auto next_available = reinterpret_cast<AvailableFn>(
        dlsym(candidate, "Java_com_googlebook_fsrbridge_Fsr3Bridge_nativeAvailable"));
    auto next_submit = reinterpret_cast<SubmitFn>(
        dlsym(candidate, "Java_com_googlebook_fsrbridge_Fsr3Bridge_nativeSubmit"));
    auto next_scale = reinterpret_cast<ScaleFn>(
        dlsym(candidate, "Java_com_googlebook_fsrbridge_Fsr3Bridge_nativeScale"));
    auto next_viewport = reinterpret_cast<ViewportFn>(
        dlsym(candidate, "Java_com_googlebook_fsrbridge_Fsr3Bridge_nativeViewport"));
    auto next_camera = reinterpret_cast<CameraFn>(
        dlsym(candidate, "Java_com_googlebook_fsrbridge_Fsr3Bridge_nativeCamera"));
    auto next_camera_invalid = reinterpret_cast<CameraInvalidFn>(
        dlsym(candidate, "Java_com_googlebook_fsrbridge_Fsr3Bridge_nativeCameraInvalid"));
    auto next_bridge_mode = reinterpret_cast<BridgeModeFn>(
        dlsym(candidate, "Java_com_googlebook_fsrbridge_Fsr3Bridge_nativeBridgeMode"));
    auto next_trace = reinterpret_cast<TraceFn>(
        dlsym(candidate, "Java_com_googlebook_fsrbridge_Fsr3Bridge_nativeTrace"));
    auto next_final_scope = reinterpret_cast<FinalScopeFn>(
        dlsym(candidate, "Java_com_googlebook_fsrbridge_Fsr3Bridge_nativeFinalScope"));
    auto next_scene_depth = reinterpret_cast<SceneDepthFn>(
        dlsym(candidate, "Java_com_googlebook_fsrbridge_Fsr3Bridge_nativeSetSceneDepth"));
    if (!(next_available && next_submit && next_scale && next_viewport && next_camera
            && next_camera_invalid && next_bridge_mode && next_final_scope && next_scene_depth)) {
        dlclose(candidate);
        return JNI_FALSE;
    }
    g_available = next_available;
    g_submit = next_submit;
    g_scale = next_scale;
    g_viewport = next_viewport;
    g_camera = next_camera;
    g_camera_invalid = next_camera_invalid;
    g_bridge_mode = next_bridge_mode;
    g_trace = next_trace; // Diagnostics are optional; rendering entry points above are mandatory.
    g_final_scope = next_final_scope;
    g_scene_depth = next_scene_depth;
    g_core = candidate;
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_googlebook_fsrbridge_NativeBridge_available(JNIEnv* env, jclass) {
    if (!g_available) return JNI_FALSE;
    jclass target = env->FindClass("com/googlebook/fsrbridge/Fsr3Bridge");
    if (!target) return JNI_FALSE;
    jboolean result = g_available(env, target);
    env->DeleteLocalRef(target);
    return result;
}

extern "C" JNIEXPORT jfloat JNICALL
Java_com_googlebook_fsrbridge_NativeBridge_scale(JNIEnv* env, jclass) {
    if (!g_scale) return 1.0f;
    jclass target = env->FindClass("com/googlebook/fsrbridge/Fsr3Bridge");
    if (!target) return 1.0f;
    jfloat result = g_scale(env, target);
    env->DeleteLocalRef(target);
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_com_googlebook_fsrbridge_NativeBridge_viewport(JNIEnv* env, jclass) {
    if (!g_viewport) return;
    jclass target = env->FindClass("com/googlebook/fsrbridge/Fsr3Bridge");
    if (!target) return;
    g_viewport(env, target);
    env->DeleteLocalRef(target);
}

extern "C" JNIEXPORT void JNICALL
Java_com_googlebook_fsrbridge_NativeBridge_bridgeMode(JNIEnv* env, jclass) {
    if (!g_bridge_mode) return;
    jclass target = env->FindClass("com/googlebook/fsrbridge/Fsr3Bridge");
    if (!target) return;
    g_bridge_mode(env, target);
    env->DeleteLocalRef(target);
}

extern "C" JNIEXPORT void JNICALL
Java_com_googlebook_fsrbridge_NativeBridge_invalidateCamera(JNIEnv* env, jclass) {
    if (!g_camera_invalid) return;
    jclass target = env->FindClass("com/googlebook/fsrbridge/Fsr3Bridge");
    if (!target) return;
    g_camera_invalid(env, target);
    env->DeleteLocalRef(target);
}

extern "C" JNIEXPORT void JNICALL
Java_com_googlebook_fsrbridge_NativeBridge_camera(JNIEnv* env, jclass,
                                                  jfloatArray modelView, jfloatArray projection,
                                                  jdoubleArray cameraPosition) {
    if (!g_camera) return;
    jclass target = env->FindClass("com/googlebook/fsrbridge/Fsr3Bridge");
    if (!target) return;
    g_camera(env, target, modelView, projection, cameraPosition);
    env->DeleteLocalRef(target);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_googlebook_fsrbridge_NativeBridge_submit(JNIEnv* env, jclass) {
    if (!g_submit) return -1;
    jclass target = env->FindClass("com/googlebook/fsrbridge/Fsr3Bridge");
    if (!target) return -1;
    jint result = g_submit(env, target);
    env->DeleteLocalRef(target);
    return result;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_googlebook_fsrbridge_NativeBridge_trace(JNIEnv* env, jclass, jint op, jint role,
                                                 jint frame, jint event) {
    if (!g_trace) return nullptr;
    jclass target = env->FindClass("com/googlebook/fsrbridge/Fsr3Bridge");
    if (!target) return nullptr;
    jstring result = g_trace(env, target, op, role, frame, event);
    env->DeleteLocalRef(target);
    return result;
}

extern "C" JNIEXPORT void JNICALL
Java_com_googlebook_fsrbridge_NativeBridge_finalScope(JNIEnv* env, jclass, jboolean active) {
    if (!g_final_scope) return;
    jclass target = env->FindClass("com/googlebook/fsrbridge/Fsr3Bridge");
    if (!target) return;
    g_final_scope(env, target, active);
    env->DeleteLocalRef(target);
}

extern "C" JNIEXPORT void JNICALL
Java_com_googlebook_fsrbridge_NativeBridge_setSceneDepth(JNIEnv* env, jclass, jint texture) {
    if (!g_scene_depth) return;
    jclass target = env->FindClass("com/googlebook/fsrbridge/Fsr3Bridge");
    if (!target) return;
    g_scene_depth(env, target, texture);
    env->DeleteLocalRef(target);
}
