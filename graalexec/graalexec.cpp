/**
 * libgraalexec.so — GraalOnline Era GS2 executor native bridge
 *
 * Injection pipeline verified from ohko's reverse engineering post:
 *   sub_6614E4(ctx, name)          → find-or-create script object
 *   sub_84FFC0(scriptObj, bytecode) → bind compiled bytecode to object
 *   sub_5C2608(tableRoot, hash, nm) → look up event block in function table
 *   sub_84C254(eventBlock, 0)       → execute the event
 *
 * Global context: *(void**)(base + 0x920630)   [qword_920630]
 * Function table in script object: offset +80
 */
#include <jni.h>
#include <android/log.h>
#include <link.h>
#include <string.h>
#include <stdlib.h>
#include <stdint.h>
#include <string>
#include "compiler/GS2Context.h"

#define TAG "GraalExec"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

// ── Verified offsets from ohko's RE of libqplayandroid.so ───────────────────────
#define OFF_GLOBAL_CTX_PTR    0x920630  // qword_920630: pointer to game context struct
#define OFF_FIND_OR_CREATE    0x6614E4  // sub_6614E4(ctx, nameStr) → script object (creates if missing)
#define OFF_BIND_BYTECODE     0x84FFC0  // sub_84FFC0(scriptObj, bytecode): parse bytecode into script
#define OFF_FIND_IN_TABLE     0x5C2608  // findInTable(tableRoot, hash, nameStr) → event block
#define OFF_EXEC_EVENT        0x84C254  // execEvent(eventBlock, 0): run the event now
#define SCRIPT_FUNC_TABLE_OFF 80        // offset of function table ptr within script object

typedef void* (*findOrCreate_t)(void* ctx, void* nameStr);
typedef void  (*bindBytecode_t)(void* scriptObj, void* bytecode);
typedef void* (*findInTable_t) (void* tableRoot, uint32_t hash, void* nameStr);
typedef void* (*execEvent_t)   (void* eventBlock, void* unused);

static uintptr_t g_base = 0;

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
    if (!g_base) { LOGE("libqplayandroid.so not mapped yet"); return false; }
    LOGI("libqplayandroid base=0x%lx", (unsigned long)g_base);
    return true;
}

// ── GraalString helpers ─────────────────────────────────────────────────────────
// Format confirmed by ohko: buf = [int32 len][int32 refcount=1][UTF8 chars]
// Returned as pBuf → &buf (pointer-to-pointer, matches engine's internal layout)
static void* make_graal_string(const char* str, size_t len) {
    uint8_t* buf = (uint8_t*)malloc(len + 9);
    memset(buf, 0, len + 9);
    *(int32_t*)buf       = (int32_t)len;
    *(int32_t*)(buf + 4) = 1;
    memcpy(buf + 8, str, len);
    void** pBuf = (void**)malloc(sizeof(void*));
    *pBuf = buf;
    return (void*)pBuf;
}
static void* make_graal_string_s(const char* str) {
    return make_graal_string(str, strlen(str));
}

// ── GraalHash ──────────────────────────────────────────────────────────────────
// From ohko's getGraalHash JS: h=5381, h=((h*17)^char)>>>0, uppercase folded to lower
static uint32_t graal_hash(const char* name) {
    uint32_t h = 5381;
    for (const char* p = name; *p; p++) {
        uint32_t c = (uint32_t)(unsigned char)*p;
        if (c >= 0x41 && c <= 0x5A) c += 0x20; // fold to lowercase
        h = ((h * 17) ^ c);
    }
    return h;
}

// ── JNI_OnLoad ──────────────────────────────────────────────────────────────────
extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM*, void*) {
    init_engine();
    return JNI_VERSION_1_6;
}

// ── nativeIsReady ────────────────────────────────────────────────────────────────
extern "C" JNIEXPORT jboolean JNICALL
Java_com_graal_exec_GraalExec_nativeIsReady(JNIEnv*, jclass) {
    return init_engine() ? JNI_TRUE : JNI_FALSE;
}

// ── nativeCompile ────────────────────────────────────────────────────────────────
extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_graal_exec_GraalExec_nativeCompile(JNIEnv* env, jclass, jstring jsrc) {
    const char* src = env->GetStringUTFChars(jsrc, nullptr);
    if (!src) return nullptr;
    std::string script(src);
    env->ReleaseStringUTFChars(jsrc, src);

    auto result = GS2Context::Compile(script, "level", "ZEXEC", false);
    if (!result.success || result.bytecode.length() == 0) {
        for (const auto& err : result.errors)
            LOGE("GS2 compile: %s", err.msg().c_str());
        return nullptr;
    }

    LOGI("Compiled %zu bytes", result.bytecode.length());
    jsize len = (jsize)result.bytecode.length();
    jbyteArray arr = env->NewByteArray(len);
    env->SetByteArrayRegion(arr, 0, len,
        reinterpret_cast<const jbyte*>(result.bytecode.buffer()));
    return arr;
}

// ── nativeDiag: scan ctx struct for non-null pointers to find script manager offset ──
// ctx+0xB30 returned null so we scan 0x800..0xD00 for candidates.
// Safe: read-only, no engine calls.
extern "C" JNIEXPORT jstring JNICALL
Java_com_graal_exec_GraalExec_nativeDiag(JNIEnv* env, jclass) {
    if (!init_engine()) return env->NewStringUTF("ERR: engine not ready");

    void* globalCtx = *(void**)(g_base + OFF_GLOBAL_CTX_PTR);
    if (!globalCtx) return env->NewStringUTF("ERR: ctx=null (not in world)");

    // Scan ctx+0x800 .. ctx+0xD00 for non-null pointer-sized slots.
    // Log up to 16 hits as "off:val" pairs. Each hit is a candidate for the script manager.
    std::string out;
    out.reserve(512);
    char tmp[64];
    snprintf(tmp, sizeof(tmp), "ctx=%p\n", globalCtx);
    out += tmp;

    int hits = 0;
    for (uint32_t off = 0x800; off < 0xD00 && hits < 16; off += 8) {
        void* val = *(void**)((uint8_t*)globalCtx + off);
        if (val) {
            snprintf(tmp, sizeof(tmp), "+%X:%p\n", off, val);
            out += tmp;
            hits++;
        }
    }
    if (hits == 0) out += "no non-null ptrs in 0x800..0xD00\n";

    // Also show the known offset result for reference
    void* atB30 = *(void**)((uint8_t*)globalCtx + 0xB30);
    snprintf(tmp, sizeof(tmp), "B30=%p", atB30);
    out += tmp;

    return env->NewStringUTF(out.c_str());
}

// ── nativeInject ─────────────────────────────────────────────────────────────────
// Phase A (safe probe): just call sub_6614E4, log the returned pointer, return.
// Phase B (bind): if Phase A doesn't crash, uncomment bindBytecode call below.
extern "C" JNIEXPORT jstring JNICALL
Java_com_graal_exec_GraalExec_nativeInject(JNIEnv* env, jclass, jbyteArray arr, jint len) {
    if (!init_engine()) return env->NewStringUTF("ERR: engine not ready");

    void* globalCtx = *(void**)(g_base + OFF_GLOBAL_CTX_PTR);
    if (!globalCtx) return env->NewStringUTF("ERR: ctx=null (not in world)");

    // Copy bytecode now so we have it ready
    jbyte* raw = env->GetByteArrayElements(arr, nullptr);
    if (!raw) return env->NewStringUTF("ERR: null bytecode");
    uint8_t* bytecodeBuf = (uint8_t*)malloc((size_t)len);
    memcpy(bytecodeBuf, raw, (size_t)len);
    env->ReleaseByteArrayElements(arr, raw, JNI_ABORT);

    // Phase A: call sub_6614E4(ctx, name) to find/create the script object.
    // If THIS causes the crash, we know sub_6614E4 is wrong and we need a different offset.
    auto findOrCreate = (findOrCreate_t)(g_base + OFF_FIND_OR_CREATE);
    void* nameStr = make_graal_string_s("ZEXEC");
    void* scriptObj = findOrCreate(globalCtx, nameStr);
    if (!scriptObj) {
        free(bytecodeBuf);
        char r[64]; snprintf(r, sizeof(r), "ERR: findOrCreate=null ctx=%p", globalCtx);
        return env->NewStringUTF(r);
    }
    LOGI("scriptObj=%p len=%d", scriptObj, len);

    // Phase B: bind bytecode. Comment this out if Phase A alone crashes.
    auto bindBytecode = (bindBytecode_t)(g_base + OFF_BIND_BYTECODE);
    bindBytecode(scriptObj, bytecodeBuf);
    LOGI("Bytecode bound");

    char result[128];
    snprintf(result, sizeof(result), "OK: obj=%p bc=%d bytes", scriptObj, len);
    return env->NewStringUTF(result);
}
