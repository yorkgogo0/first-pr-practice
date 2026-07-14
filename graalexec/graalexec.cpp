/**
 * libgraalexec.so — GraalOnline Era GS2 executor native bridge
 * Offsets RE'd from libqplayandroid.so (ARM64, this APK version).
 * Loaded by com.graal.exec.GraalExec via System.loadLibrary("graalexec").
 */
#include <jni.h>
#include <android/log.h>
#include <link.h>
#include <string.h>
#include <stdlib.h>
#include <stdint.h>

#define TAG "GraalExec"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// ── Function pointer types (signatures from Frida NativeFunction analysis) ──────
typedef void  (*masterLoader_t)          (void*, void*);
typedef void  (*initDescriptor_t)        (void*);
typedef void* (*registerInSystem_t)      (void*);
typedef void  (*addToUpdateQueue_t)      (void*);
typedef void  (*attachToManager_t)       (void*, void*);
typedef void  (*registerScriptInSystem_t)(void*, void*, void*);
typedef void* (*findFunc_t)              (void*, void*);

// ── Globals ──────────────────────────────────────────────────────────────────────
static uintptr_t g_base = 0;

static masterLoader_t           g_masterLoader           = nullptr;
static initDescriptor_t         g_initDescriptor         = nullptr;
static registerInSystem_t       g_registerInSystem       = nullptr;
static addToUpdateQueue_t       g_addToUpdateQueue       = nullptr;
static attachToManager_t        g_attachToManager        = nullptr;
static registerScriptInSystem_t g_registerScriptInSystem = nullptr;
static findFunc_t               g_findFunc               = nullptr;

// Offsets from libqplayandroid.so base (verified via Frida RE)
#define OFF_MASTER_LOADER             0x869794
#define OFF_INIT_DESCRIPTOR           0x866200
#define OFF_REGISTER_IN_SYSTEM        0x8212B0
#define OFF_ADD_TO_UPDATE_QUEUE       0x86A884
#define OFF_ATTACH_TO_MANAGER         0x8715C0
#define OFF_REGISTER_SCRIPT_IN_SYSTEM 0x680858
#define OFF_FIND_FUNC                 0x67A35C
#define OFF_VTABLE_EVENTS             0x8A2940
#define OFF_GLOBAL_CTX                0x939BD0
#define OFF_QWORD_93BEF0              0x93BEF0

// ── Library base finder ──────────────────────────────────────────────────────────
static int find_lib_cb(struct dl_phdr_info* info, size_t, void* data) {
    uintptr_t* out = (uintptr_t*)data;
    if (*out) return 0;
    if (info->dlpi_name && strstr(info->dlpi_name, "libqplayandroid.so")) {
        *out = (uintptr_t)info->dlpi_addr;
        return 1;
    }
    return 0;
}

static bool init_engine() {
    if (g_base) return true;
    dl_iterate_phdr(find_lib_cb, &g_base);
    if (!g_base) { LOGE("libqplayandroid.so not loaded yet"); return false; }
    LOGI("libqplayandroid base=0x%lx", (unsigned long)g_base);

    g_masterLoader           = (masterLoader_t)          (g_base + OFF_MASTER_LOADER);
    g_initDescriptor         = (initDescriptor_t)        (g_base + OFF_INIT_DESCRIPTOR);
    g_registerInSystem       = (registerInSystem_t)      (g_base + OFF_REGISTER_IN_SYSTEM);
    g_addToUpdateQueue       = (addToUpdateQueue_t)      (g_base + OFF_ADD_TO_UPDATE_QUEUE);
    g_attachToManager        = (attachToManager_t)       (g_base + OFF_ATTACH_TO_MANAGER);
    g_registerScriptInSystem = (registerScriptInSystem_t)(g_base + OFF_REGISTER_SCRIPT_IN_SYSTEM);
    g_findFunc               = (findFunc_t)              (g_base + OFF_FIND_FUNC);
    LOGI("All GS2 engine functions mapped");
    return true;
}

// ── Graal string helpers (matches overlay.js createGraalString) ──────────────────
static void* make_graal_string(const char* str, size_t len) {
    uint8_t* buf = (uint8_t*)calloc(len + 9, 1);
    *(int32_t*)buf        = (int32_t)len;
    *(int32_t*)(buf + 4)  = 1;
    memcpy(buf + 8, str, len);
    void** pBuf = (void**)malloc(sizeof(void*));
    *pBuf = buf;
    return pBuf;
}

static void* make_graal_string_s(const char* str) {
    return make_graal_string(str, strlen(str));
}

// ── JNI_OnLoad ──────────────────────────────────────────────────────────────────
extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM*, void*) {
    init_engine();   // best-effort at load time; engine may not be loaded yet
    return JNI_VERSION_1_6;
}

// ── nativeIsReady: returns true once libqplayandroid.so is mapped ────────────────
extern "C" JNIEXPORT jboolean JNICALL
Java_com_graal_exec_GraalExec_nativeIsReady(JNIEnv*, jclass) {
    return init_engine() ? JNI_TRUE : JNI_FALSE;
}

// ── nativeInject: takes compiled GS2 bytecode (byte[]) and injects it ──────────
extern "C" JNIEXPORT jstring JNICALL
Java_com_graal_exec_GraalExec_nativeInject(JNIEnv* env, jclass, jbyteArray arr, jint len) {
    if (!init_engine()) return env->NewStringUTF("ERR: engine not ready");

    // Payload: int32 length, int32 999, then bytecode (matches overlay.js)
    jbyte* raw = env->GetByteArrayElements(arr, nullptr);
    if (!raw) return env->NewStringUTF("ERR: null bytecode");

    uint8_t* payload = (uint8_t*)calloc(len + 8, 1);
    *(int32_t*)payload       = len;
    *(int32_t*)(payload + 4) = 999;
    memcpy(payload + 8, (uint8_t*)raw, (size_t)len);
    env->ReleaseByteArrayElements(arr, raw, JNI_ABORT);

    // Read global context pointers
    void*  globalCtx  = *(void**)(g_base + OFF_GLOBAL_CTX);
    void*  scriptSys  = *(void**)(g_base + OFF_QWORD_93BEF0);
    if (!globalCtx || !scriptSys) {
        free(payload);
        return env->NewStringUTF("ERR: game not in world yet");
    }

    // Generate unique name "Mod_XXXXXX"
    static int s_seq = 100000;
    char nameBuf[32];
    snprintf(nameBuf, sizeof(nameBuf), "Mod_%d", s_seq++);
    void* namePtr  = make_graal_string_s(nameBuf);
    void* dummyPtr = calloc(sizeof(void*), 1);

    // Register script slot
    g_registerScriptInSystem(scriptSys, namePtr, dummyPtr);

    // Find the weapon/script object
    void* weaponObj = g_findFunc(scriptSys, namePtr);
    if (!weaponObj) {
        free(payload); return env->NewStringUTF("ERR: registerScript returned null");
    }
    *((uint8_t*)weaponObj + 22) = 6;   // set type flag

    // Build event descriptor (136 bytes, two 24-byte vtable event structs)
    void* vtableEvents = (void*)(g_base + OFF_VTABLE_EVENTS);
    uint8_t* desc = (uint8_t*)calloc(136, 1);
    void* v19 = calloc(24, 1);
    void* v20 = calloc(24, 1);
    *(void**)v19 = vtableEvents;
    *(void**)v20 = vtableEvents;

    *(void**)(desc +  0) = weaponObj;
    *(void**)(desc + 64) = v19;
    *(void**)(desc + 40) = v20;
    desc[88]  = 1;
    *(int32_t*)(desc + 92) = 10000;
    desc[104] = 0;

    g_initDescriptor(desc);
    g_registerInSystem(weaponObj);
    *(void**)((uint8_t*)weaponObj + 80) = desc;

    // Attach payload and call masterLoader
    void** pPayload = (void**)malloc(sizeof(void*));
    *pPayload = payload;
    g_masterLoader(desc, pPayload);

    // Clear skip flags in the loaded list
    void* listPtr = *(void**)((uint8_t*)desc + 64);
    if (listPtr) {
        int count = *(int32_t*)((uint8_t*)listPtr + 12);
        if (count > 20) count = 20;
        for (int i = 0; i < count; i++) {
            void* item = *(void**)((uint8_t*)listPtr + 16 + i * 8);
            if (item) *((uint8_t*)item + 49) = 0;
        }
    }

    // Call vtable[144] on weaponObj
    void** vtable = *(void***)weaponObj;
    if (vtable) {
        typedef void (*vfn_t)(void*, void*);
        vfn_t fn144 = (vfn_t)vtable[18];   // vtable[144] = offset 18 (144/8=18)
        fn144(weaponObj, nullptr);
    }

    // Finalize descriptor timing from global context
    double gameTime = *(double*)((uint8_t*)globalCtx + 184);
    desc[20]  = 0; desc[73]  = 1; desc[104] = 1; desc[232] = 1; desc[233] = 1;
    *(double*)(desc + 16) = 1.0;
    *(double*)(desc + 24) = gameTime + 0.5;
    desc[32]  = 0; desc[72]  = 0; desc[88]  = 1;
    *(int32_t*)(desc + 92) = 10000;

    g_addToUpdateQueue(weaponObj);
    g_attachToManager(globalCtx, weaponObj);

    LOGI("Injected %s (%d bytes)", nameBuf, len);
    // Note: desc/payload/weaponObj not freed — GS2 engine holds references

    char result[64];
    snprintf(result, sizeof(result), "OK: injected %s", nameBuf);
    return env->NewStringUTF(result);
}
