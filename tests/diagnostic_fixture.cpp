// Host-only diagnostic transport fixture. No rendering or GPU is simulated.
#define Java_com_googlebook_fsrbridge_Fsr3Bridge_nativeSubmit fixture_unused_submit
#include "core_fixture.cpp"
#undef Java_com_googlebook_fsrbridge_Fsr3Bridge_nativeSubmit

namespace {
int submit_result = -23;
bool submit_throws = false;
bool trace_throws = false;
int submit_calls = 0;
int trace_calls = 0;
}
extern "C" {
JNIEXPORT void JNICALL Java_com_googlebook_fsrbridge_DiagnosticFlowTest_control(
        JNIEnv*, jclass, jint result, jboolean fail_submit, jboolean fail_trace) {
    submit_result = result;
    submit_throws = fail_submit;
    trace_throws = fail_trace;
}
JNIEXPORT jint JNICALL Java_com_googlebook_fsrbridge_DiagnosticFlowTest_calls(JNIEnv*, jclass, jboolean trace) {
    return trace ? trace_calls : submit_calls;
}
JNIEXPORT jint JNICALL Java_com_googlebook_fsrbridge_Fsr3Bridge_nativeSubmit(JNIEnv* env, jclass) {
    ++submit_calls;
    if (submit_throws) {
        jclass error = env->FindClass("java/lang/IllegalStateException");
        env->ThrowNew(error, "private/path must not enter trace JSON");
        env->DeleteLocalRef(error);
    }
    return submit_result;
}
JNIEXPORT jstring JNICALL Java_com_googlebook_fsrbridge_Fsr3Bridge_nativeTrace(
        JNIEnv* env, jclass, jint, jint, jint, jint) {
    ++trace_calls;
    if (trace_throws) {
        jclass error = env->FindClass("java/lang/IllegalStateException");
        env->ThrowNew(error, "injected optional trace failure");
        env->DeleteLocalRef(error);
        return nullptr;
    }
    return env->NewStringUTF("{\"host_transport_fixture\":true}");
}
}
