// Write C++ code here.
//
// Do not forget to dynamically load the C++ library into your application.
//
// For instance,
//
// In MainActivity.java:
//    static {
//       System.loadLibrary("gptmobile");
//    }
//
// Or, in MainActivity.kt:
//    companion object {
//      init {
//         System.loadLibrary("gptmobile")
//      }
//    }

#include <jni.h>
#include <android/log.h>
#include <vector>
#include <string>

#include "PowerServe/app/server/server_handler.hpp"
#include "PowerServe/app/server/local_server.hpp"

std::string jstring_to_str(JNIEnv* env, jstring jstr) {
    const jclass stringClass = env->GetObjectClass(jstr);
    const jmethodID getBytes = env->GetMethodID(stringClass, "getBytes", "(Ljava/lang/String;)[B");

    const jstring charsetName = env->NewStringUTF("UTF-8");
    const jbyteArray stringJbytes = (jbyteArray) env->CallObjectMethod(jstr, getBytes, charsetName);
    env->DeleteLocalRef(charsetName);

    const jsize length = env->GetArrayLength(stringJbytes);
    jbyte* pBytes = env->GetByteArrayElements(stringJbytes, nullptr);

    const std::string result(pBytes, pBytes + length);

    env->ReleaseByteArrayElements(stringJbytes, pBytes, JNI_ABORT);
    env->DeleteLocalRef(stringJbytes);

    return result;
}

jstring str_to_jstring(JNIEnv* env, const std::string &input) {
    //定义java String类 strClass
    jclass strClass = (env)->FindClass("java/lang/String");
    //获取String(byte[],String)的构造器,用于将本地byte[]数组转换为一个新String
    jmethodID ctorID = (env)->GetMethodID(strClass, "<init>", "([BLjava/lang/String;)V");
    //建立byte数组
    jbyteArray bytes = (env)->NewByteArray(input.size());
    //将char* 转换为byte数组
    (env)->SetByteArrayRegion(bytes, 0, input.size(), (jbyte*)input.data());
    // 设置String, 保存语言类型,用于byte数组转换至String时的参数
    jstring encoding = (env)->NewStringUTF("UTF-8");
    //将byte数组转换为java String,并输出
    return (jstring)(env)->NewObject(strClass, ctorID, bytes, encoding);
}

template<class T>
T *get_pointer(jlong ptr) {
    return (T *)(int64_t)ptr;
}

extern "C"
JNIEXPORT jlong JNICALL
Java_dev_chungjungsoo_gptmobile_data_server_PowerServeHolder_create_1power_1serve(JNIEnv *env,
                                                                                  jobject thiz,
                                                                                  jstring model_folder,
                                                                                  jstring lib_folder) {
    try {
        LocalServer *server_ptr  = new LocalServer(jstring_to_str(env, model_folder), jstring_to_str(env, lib_folder));
        const int64_t pointer_value = (int64_t)server_ptr;
        return (jlong)pointer_value;
    } catch (...) {
        __android_log_write(ANDROID_LOG_ERROR, "PowerServe", "Failed to create PowerServe server");
        return 0;
    }
}

extern "C"
JNIEXPORT void JNICALL
Java_dev_chungjungsoo_gptmobile_data_server_PowerServeHolder_destroy_1power_1serve(JNIEnv *env,
                                                                                   jobject thiz,
                                                                                   jlong java_ptr) {
    try {
        LocalServer *server_ptr = get_pointer<LocalServer>(java_ptr);
        delete server_ptr;
    } catch (...) {
        __android_log_write(ANDROID_LOG_ERROR, "PowerServe", "Failed to destroy PowerServe server");
    }
}

extern "C"
JNIEXPORT jlong JNICALL
Java_dev_chungjungsoo_gptmobile_data_server_PowerServeHolder_power_1serve_1chat_1completion(
        JNIEnv *env, jobject thiz, jlong java_ptr, jstring request) {
    try {
        LocalServer *server_ptr  = get_pointer<LocalServer>(java_ptr);

        LocalRequest local_request {
                jstring_to_str(env, request)
        };
        LocalResponse *response_ptr = server_ptr->create_chat_response(local_request);
        return (jlong)(int64_t)response_ptr;
    } catch (...) {
        __android_log_write(ANDROID_LOG_ERROR, "PowerServe", "Failed to create chat completion task");
        return 0;
    }
}

extern "C"
JNIEXPORT void JNICALL
Java_dev_chungjungsoo_gptmobile_data_server_PowerServeHolder_power_1serve_1destroy_1response(
        JNIEnv *env, jobject thiz, jlong server_java_ptr, jlong response_java_ptr) {
    try {
        LocalServer *server_ptr  = get_pointer<LocalServer>(server_java_ptr);
        LocalResponse *response_ptr = get_pointer<LocalResponse>(response_java_ptr);
        server_ptr->destroy_response(response_ptr);
    } catch (...) {
        __android_log_write(ANDROID_LOG_ERROR, "PowerServe", "Failed to destory chat completion task");
    }
}

extern "C"
JNIEXPORT jstring JNICALL
Java_dev_chungjungsoo_gptmobile_data_server_PowerServeHolder_power_1serve_1try_1fetch_1result(
        JNIEnv *env, jobject thiz, jlong server_java_ptr, jlong response_java_ptr) {
    try {
        LocalServer *server_ptr = get_pointer<LocalServer>(server_java_ptr);
        LocalResponse *response_ptr = get_pointer<LocalResponse>(response_java_ptr);

        std::optional<std::string> response_chunk = server_ptr->get_response(response_ptr);
        if (response_chunk.has_value()) {
            return str_to_jstring(env, response_chunk.value());
        }
        return nullptr;
    } catch (...) {
        __android_log_write(ANDROID_LOG_ERROR, "PowerServe",
                            "Failed to fetch chat completion task");
        return nullptr;
    }
}