#include "engine.h"
#include <jni.h>
#include <exception>

namespace {
mel::Engine engine;
std::string utf8(JNIEnv *env, jstring value) {
    jclass cls = env->FindClass("java/lang/String");
    auto method = env->GetMethodID(cls, "getBytes", "(Ljava/lang/String;)[B");
    jstring charset = env->NewStringUTF("UTF-8");
    auto bytes = static_cast<jbyteArray>(env->CallObjectMethod(value, method, charset));
    std::string result(env->GetArrayLength(bytes), '\0');
    env->GetByteArrayRegion(bytes, 0, result.size(), reinterpret_cast<jbyte *>(result.data()));
    env->DeleteLocalRef(bytes); env->DeleteLocalRef(charset); env->DeleteLocalRef(cls);
    return result;
}
jstring string(JNIEnv *env, const std::string &value) {
    jclass cls = env->FindClass("java/lang/String");
    auto constructor = env->GetMethodID(cls, "<init>", "([BLjava/lang/String;)V");
    auto bytes = env->NewByteArray(value.size());
    env->SetByteArrayRegion(bytes, 0, value.size(), reinterpret_cast<const jbyte *>(value.data()));
    auto charset = env->NewStringUTF("UTF-8");
    auto result = static_cast<jstring>(env->NewObject(cls, constructor, bytes, charset));
    env->DeleteLocalRef(bytes); env->DeleteLocalRef(charset); env->DeleteLocalRef(cls);
    return result;
}
std::vector<mel::Turn> turns(JNIEnv *env, jobjectArray roles, jobjectArray contents) {
    std::vector<mel::Turn> result;
    for (int i = 0; i < env->GetArrayLength(roles); ++i) {
        auto role = static_cast<jstring>(env->GetObjectArrayElement(roles, i));
        auto content = static_cast<jstring>(env->GetObjectArrayElement(contents, i));
        result.push_back({utf8(env, role), utf8(env, content)});
        env->DeleteLocalRef(role); env->DeleteLocalRef(content);
    }
    return result;
}
void error(JNIEnv *env, const std::exception &e) {
    // ThrowNew uses modified UTF-8; construct the exception with a proper UTF-8 String.
    auto cls = env->FindClass("java/lang/IllegalStateException");
    auto constructor = env->GetMethodID(cls, "<init>", "(Ljava/lang/String;)V");
    auto message = string(env, e.what());
    auto exception = static_cast<jthrowable>(env->NewObject(cls, constructor, message));
    env->Throw(exception);
    env->DeleteLocalRef(message); env->DeleteLocalRef(exception); env->DeleteLocalRef(cls);
}
}
extern "C" JNIEXPORT void JNICALL Java_jp_mel_chat_local_LlamaBridge_begin(JNIEnv *, jobject) { engine.begin(); }
extern "C" JNIEXPORT void JNICALL Java_jp_mel_chat_local_LlamaBridge_cancel(JNIEnv *, jobject) { engine.cancel(); }
extern "C" JNIEXPORT void JNICALL Java_jp_mel_chat_local_LlamaBridge_unload(JNIEnv *, jobject) { engine.unload(); }
extern "C" JNIEXPORT void JNICALL Java_jp_mel_chat_local_LlamaBridge_load(JNIEnv *env, jobject, jstring path, jint context, jint threads) {
    try { engine.load(utf8(env, path), context, threads); } catch (const std::exception &e) { error(env, e); }
}
extern "C" JNIEXPORT jint JNICALL Java_jp_mel_chat_local_LlamaBridge_count(JNIEnv *env, jobject, jobjectArray roles, jobjectArray contents) {
    try { return engine.count(turns(env, roles, contents)); } catch (const std::exception &e) { error(env, e); return 0; }
}
extern "C" JNIEXPORT jstring JNICALL Java_jp_mel_chat_local_LlamaBridge_generate(JNIEnv *env, jobject, jobjectArray roles, jobjectArray contents, jfloat temperature, jint limit) {
    try { return string(env, engine.generate(turns(env, roles, contents), temperature, limit)); } catch (const std::exception &e) { error(env, e); return nullptr; }
}
