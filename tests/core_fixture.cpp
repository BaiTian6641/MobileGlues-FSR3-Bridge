// Host-only ABI fixture. It supplies no renderer and is never packaged with the mod.
#include <jni.h>
extern "C" {
JNIEXPORT jboolean JNICALL Java_com_googlebook_fsrbridge_Fsr3Bridge_nativeAvailable(JNIEnv*, jclass) { return JNI_TRUE; }
JNIEXPORT jint JNICALL Java_com_googlebook_fsrbridge_Fsr3Bridge_nativeSubmit(JNIEnv*, jclass) { return 23; }
#ifndef INCOMPLETE_ABI
JNIEXPORT jfloat JNICALL Java_com_googlebook_fsrbridge_Fsr3Bridge_nativeScale(JNIEnv*, jclass) { return 1.75f; }
JNIEXPORT void JNICALL Java_com_googlebook_fsrbridge_Fsr3Bridge_nativeViewport(JNIEnv*, jclass) { }
JNIEXPORT void JNICALL Java_com_googlebook_fsrbridge_Fsr3Bridge_nativeCamera(JNIEnv*, jclass, jfloatArray, jfloatArray, jdoubleArray) { }
JNIEXPORT void JNICALL Java_com_googlebook_fsrbridge_Fsr3Bridge_nativeCameraInvalid(JNIEnv*, jclass) { }
JNIEXPORT void JNICALL Java_com_googlebook_fsrbridge_Fsr3Bridge_nativeBridgeMode(JNIEnv*, jclass) { }
JNIEXPORT void JNICALL Java_com_googlebook_fsrbridge_Fsr3Bridge_nativeFinalScope(JNIEnv*, jclass, jboolean) { }
JNIEXPORT void JNICALL Java_com_googlebook_fsrbridge_Fsr3Bridge_nativeSetSceneDepth(JNIEnv*, jclass, jint) { }
#endif
}
