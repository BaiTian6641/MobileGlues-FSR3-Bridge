// Host-only scale transport. Actual bridge Java runs; no native renderer is supplied.
#define Java_com_googlebook_fsrbridge_Fsr3Bridge_nativeScale fixture_unused_scale
#include "core_fixture.cpp"
#undef Java_com_googlebook_fsrbridge_Fsr3Bridge_nativeScale
namespace { float scale = 1.75f; }
extern "C" {
JNIEXPORT void JNICALL Java_com_googlebook_fsrbridge_LegacySceneDepthContractTest_setScale(
        JNIEnv*, jclass, jfloat value) { scale = value; }
JNIEXPORT jfloat JNICALL Java_com_googlebook_fsrbridge_Fsr3Bridge_nativeScale(JNIEnv*, jclass) { return scale; }
}
