#include <android/log.h>
#include "jni.h"
#include "fake_dlfcn.h"
#include <pthread.h>
#include <fcntl.h>
#include <sys/mman.h>

#define LOG_TAG "MetaLoader"
#define LOGI(...) __android_log_write(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_write(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static JavaVM *get_java_vm() {
    void *handle = dlopen_ex("/system/lib64/libandroid_runtime.so", 2);
    void *get_env = dlsym_ex(handle, "_ZN7android14AndroidRuntime9getJNIEnvEv");
    JNIEnv *env = ((JNIEnv *(*)()) get_env)();
    JavaVM *jvm;
    if (env->GetJavaVM(&jvm) != JNI_OK) {
        LOGI("get jvm failed");
        return nullptr;
    }
    return jvm;
}

static void clear_exception(JNIEnv *env) {
    if (auto exception = env->ExceptionOccurred()) {
        env->ExceptionClear();
        static jclass log = (jclass) env->NewGlobalRef(env->FindClass("android/util/Log"));
        static jmethodID toString = env->GetStaticMethodID(
                log, "getStackTraceString", "(Ljava/lang/Throwable;)Ljava/lang/String;");
        jstring str = (jstring) env->CallStaticObjectMethod(log, toString, exception);
        const char *s = env->GetStringUTFChars(str, 0);
        LOGE(s);
        env->ReleaseStringUTFChars(str, s);
        env->DeleteLocalRef(exception);
    }
}

static jobject load_dex(JNIEnv *env, void *dex, int size) {
    auto classloader = env->FindClass("java/lang/ClassLoader");
    clear_exception(env);

    auto getsyscl_mid = env->GetStaticMethodID(
            classloader, "getSystemClassLoader", "()Ljava/lang/ClassLoader;");
    clear_exception(env);

    auto sys_classloader = env->CallStaticObjectMethod(classloader, getsyscl_mid);
    clear_exception(env);

    if (!sys_classloader) [[unlikely]] {
        LOGI("getSystemClassLoader failed!!!");
        return nullptr;
    }
    auto in_memory_classloader = env->FindClass("dalvik/system/InMemoryDexClassLoader");
    auto initMid = env->GetMethodID(in_memory_classloader, "<init>",
                                    "(Ljava/nio/ByteBuffer;Ljava/lang/ClassLoader;)V");
    auto dex_buffer = env->NewDirectByteBuffer(dex, size);
    if (auto my_cl = env->NewObject(in_memory_classloader, initMid,
                                    dex_buffer, sys_classloader)) {
        env->DeleteLocalRef(dex_buffer);
        return env->NewGlobalRef(my_cl);
    } else {
        return nullptr;
    }
}

static void enter_java_world(JNIEnv *env, jobject classloader) {
    LOGI("enter_java_world");
    jclass clz = env->FindClass("dalvik/system/DexClassLoader");
    jmethodID mid = env->GetMethodID(clz, "loadClass",
                                     "(Ljava/lang/String;)Ljava/lang/Class;");
    if (!mid) {
        mid = env->GetMethodID(clz, "findClass",
                               "(Ljava/lang/String;)Ljava/lang/Class;");
    }
    jstring entry_class_name = env->NewStringUTF("cn.tinyhai.xposed.meta_loader.LoaderEntry");
    jclass entry_clazz = (jclass) env->CallObjectMethod(classloader, mid, entry_class_name);

    env->DeleteLocalRef(entry_class_name);

    jmethodID main_mid = env->GetStaticMethodID(entry_clazz, "entry", "()V");
    env->CallStaticVoidMethod(entry_clazz, main_mid);
}

static int load_meta_loader(int *fd_p, int *size_p) {
    const char *dex_path = "/data/local/tmp/lspatch/metaloader.dex";
    int fd, size;

    fd = open(dex_path, O_RDONLY);
    if (fd < 0) {
        return -1;
    }

    size = lseek(fd, 0, SEEK_END);
    if (size <= 0) {
        return -1;
    }
    *fd_p = fd;
    *size_p = size;

    return 0;
}

static void *mmap_dex(int fd, int size) {
    void *addr = mmap(nullptr, size, PROT_READ, MAP_SHARED, fd, 0);
    if (addr != MAP_FAILED) {
        return addr;
    }
    return nullptr;
}

static void munmap_dex(void *addr, int size) {
    if (addr == nullptr) {
        return;
    }
    munmap(addr, size);
}

static void *run(void *arg) {
    JavaVM *vm = (JavaVM *) arg;
    JNIEnv *env;
    int fd, size;
    vm->AttachCurrentThread(&env, nullptr);
    if (load_meta_loader(&fd, &size) < 0) {
        LOGI("load metaloader.dex failed");
    } else {
        void *dex = mmap_dex(fd, size);
        jobject class_loader = load_dex(env, dex, size);
        enter_java_world(env, class_loader);
        munmap_dex(dex, size);
    }
    if (env) {
        vm->DetachCurrentThread();
    }
    return nullptr;
}

__attribute__((constructor))
void inject_entry() {
    JavaVM *vm = get_java_vm();
    pthread_t worker;
    pthread_create(&worker, nullptr, run, (void *) vm);
    pthread_detach(worker);
}