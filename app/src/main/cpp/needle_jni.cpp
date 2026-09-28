// JNI bridge to the Needle 3 engine (needle.h). The engine holds one
// process-global, non-thread-safe model, so NeedleEngine.kt serialises every
// call onto a single thread.

#include <jni.h>
#include <android/log.h>

#include <cerrno>
#include <cstring>
#include <string>
#include <vector>

#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

#ifdef NEEDLE_AVAILABLE
#include "needle.h"
#endif

#define LOG_TAG "NeedleJNI"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

std::string g_error;

std::string toString(JNIEnv* env, jstring value) {
    if (value == nullptr) return {};
    const char* chars = env->GetStringUTFChars(value, nullptr);
    std::string out(chars);
    env->ReleaseStringUTFChars(value, chars);
    return out;
}

// Java's modified UTF-8 cannot carry 4-byte sequences (emoji), so strings the
// engine returns are converted through a byte[] and decoded on the Kotlin side.
jbyteArray toBytes(JNIEnv* env, const char* data, size_t size) {
    jbyteArray out = env->NewByteArray(static_cast<jsize>(size));
    env->SetByteArrayRegion(out, 0, static_cast<jsize>(size), reinterpret_cast<const jbyte*>(data));
    return out;
}

std::string fromBytes(JNIEnv* env, jbyteArray value) {
    if (value == nullptr) return {};
    jsize size = env->GetArrayLength(value);
    std::string out(static_cast<size_t>(size), '\0');
    env->GetByteArrayRegion(value, 0, size, reinterpret_cast<jbyte*>(out.data()));
    return out;
}

#ifdef NEEDLE_AVAILABLE
void captureEngineError(const char* fallback) {
    const char* detail = needle_last_error();
    g_error = (detail != nullptr && detail[0] != '\0') ? detail : fallback;
}
#endif

}  // namespace

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_farzanshibu_meowclaw_llm_needle_NeedleNative_nativeAvailable(JNIEnv*, jclass) {
#ifdef NEEDLE_AVAILABLE
    return JNI_TRUE;
#else
    return JNI_FALSE;
#endif
}

JNIEXPORT jint JNICALL
Java_com_farzanshibu_meowclaw_llm_needle_NeedleNative_nativeLoad(JNIEnv* env, jclass, jstring path) {
#ifdef NEEDLE_AVAILABLE
    const std::string file = toString(env, path);
    int fd = open(file.c_str(), O_RDONLY | O_CLOEXEC);
    if (fd < 0) {
        g_error = "cannot open weights: " + std::string(strerror(errno));
        return -1;
    }
    struct stat st {};
    if (fstat(fd, &st) != 0 || st.st_size <= 0) {
        close(fd);
        g_error = "weights file is empty or unreadable";
        return -1;
    }
    void* mapped = mmap(nullptr, static_cast<size_t>(st.st_size), PROT_READ, MAP_PRIVATE, fd, 0);
    close(fd);
    if (mapped == MAP_FAILED) {
        g_error = "cannot map weights: " + std::string(strerror(errno));
        return -1;
    }
    const int rc = needle_load(static_cast<const unsigned char*>(mapped),
                               static_cast<unsigned long long>(st.st_size));
    // The engine copies what it needs; keeping the clean, file-backed mapping
    // alive costs no RAM and stays safe if a future engine reads in place.
    if (rc < 0) {
        captureEngineError("needle_load failed");
        munmap(mapped, static_cast<size_t>(st.st_size));
    }
    return rc;
#else
    g_error = "Needle engine is not built for this CPU architecture";
    return -1;
#endif
}

JNIEXPORT jint JNICALL
Java_com_farzanshibu_meowclaw_llm_needle_NeedleNative_nativeInit(
        JNIEnv* env, jclass, jbyteArray system, jbyteArray tools, jstring indexPath) {
#ifdef NEEDLE_AVAILABLE
    const std::string systemText = fromBytes(env, system);
    const std::string toolsJson = fromBytes(env, tools);
    const std::string index = toString(env, indexPath);
    const int rc = needle_init(systemText.c_str(), toolsJson.c_str(),
                               indexPath == nullptr ? nullptr : index.c_str());
    if (rc < 0) captureEngineError("needle_init failed");
    return rc;
#else
    g_error = "Needle engine is not built for this CPU architecture";
    return -1;
#endif
}

JNIEXPORT jbyteArray JNICALL
Java_com_farzanshibu_meowclaw_llm_needle_NeedleNative_nativeComplete(
        JNIEnv* env, jclass, jbyteArray input, jint maxNewTokens) {
#ifdef NEEDLE_AVAILABLE
    const std::string text = fromBytes(env, input);
    std::vector<char> out(65536, '\0');
    const int rc = needle_complete(text.c_str(), maxNewTokens, out.data(), static_cast<int>(out.size()));
    if (rc < 0) {
        captureEngineError(out[0] != '\0' ? out.data() : "needle_complete failed");
        LOGE("needle_complete failed (%d): %s", rc, g_error.c_str());
        return nullptr;
    }
    return toBytes(env, out.data(), strnlen(out.data(), out.size()));
#else
    g_error = "Needle engine is not built for this CPU architecture";
    return nullptr;
#endif
}

JNIEXPORT void JNICALL
Java_com_farzanshibu_meowclaw_llm_needle_NeedleNative_nativeReset(JNIEnv*, jclass) {
#ifdef NEEDLE_AVAILABLE
    needle_reset();
#endif
}

JNIEXPORT jbyteArray JNICALL
Java_com_farzanshibu_meowclaw_llm_needle_NeedleNative_nativeLastError(JNIEnv* env, jclass) {
    return toBytes(env, g_error.data(), g_error.size());
}

}  // extern "C"
