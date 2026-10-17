#include <jni.h>
#include <string>
#include <vector>

namespace {
std::string toHex(JNIEnv* env, jbyteArray bytes) {
    jclass digestClass = env->FindClass("java/security/MessageDigest");
    if (digestClass == nullptr) return {};
    jmethodID getInstance = env->GetStaticMethodID(
        digestClass, "getInstance", "(Ljava/lang/String;)Ljava/security/MessageDigest;");
    jmethodID digestMethod = env->GetMethodID(digestClass, "digest", "([B)[B");
    if (getInstance == nullptr || digestMethod == nullptr) return {};

    jstring algorithm = env->NewStringUTF("SHA-256");
    jobject digest = env->CallStaticObjectMethod(digestClass, getInstance, algorithm);
    env->DeleteLocalRef(algorithm);
    if (env->ExceptionCheck() || digest == nullptr) {
        env->ExceptionClear();
        return {};
    }
    auto hash = static_cast<jbyteArray>(env->CallObjectMethod(digest, digestMethod, bytes));
    if (env->ExceptionCheck() || hash == nullptr) {
        env->ExceptionClear();
        env->DeleteLocalRef(digest);
        return {};
    }
    const jsize length = env->GetArrayLength(hash);
    std::vector<jbyte> data(static_cast<size_t>(length));
    env->GetByteArrayRegion(hash, 0, length, data.data());
    env->DeleteLocalRef(hash);
    env->DeleteLocalRef(digest);

    static const char hex[] = "0123456789ABCDEF";
    std::string result;
    result.reserve(static_cast<size_t>(length) * 2);
    for (jbyte value : data) {
        const unsigned char byte = static_cast<unsigned char>(value);
        result.push_back(hex[(byte >> 4) & 0x0F]);
        result.push_back(hex[byte & 0x0F]);
    }
    return result;
}

std::string normalizeFingerprint(const char* value) {
    std::string result;
    if (value == nullptr) return result;
    for (const char* p = value; *p; ++p) {
        const char c = *p;
        if (c >= '0' && c <= '9') result.push_back(c);
        else if (c >= 'a' && c <= 'f') result.push_back(static_cast<char>(c - 'a' + 'A'));
        else if (c >= 'A' && c <= 'F') result.push_back(c);
    }
    return result;
}
}  // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_com_digitalsmod_digitalliquid_NativeIntegrity_nativeDigestMatches(
    JNIEnv* env, jobject /*thiz*/, jbyteArray certificateBytes, jstring expectedHex) {
    if (certificateBytes == nullptr || expectedHex == nullptr) return JNI_FALSE;
    const char* expectedChars = env->GetStringUTFChars(expectedHex, nullptr);
    if (expectedChars == nullptr) return JNI_FALSE;
    const std::string expected = normalizeFingerprint(expectedChars);
    env->ReleaseStringUTFChars(expectedHex, expectedChars);
    if (expected.size() != 64) return JNI_FALSE;

    const std::string actual = toHex(env, certificateBytes);
    if (actual.size() != expected.size()) return JNI_FALSE;
    unsigned int difference = 0;
    for (size_t i = 0; i < actual.size(); ++i) {
        difference |= static_cast<unsigned int>(actual[i] ^ expected[i]);
    }
    return difference == 0 ? JNI_TRUE : JNI_FALSE;
}
