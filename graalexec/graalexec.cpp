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

// ── nativeDiag v25: resolve the bind vtable-slot + dump a live bound SI ──
// The engine binds a script's handler table (SI+0x80) via a class virtual
// [entity_vtable+0x68] fired by the update tick 0x87077c — a runtime slot we
// can't pin statically. v25 reads it live: for each entity it prints the module
// offsets (ptr - base) of vtable slots [0x68]/[0x70]/[0x98]/[0x08] (the bind +
// onCreated + guard methods), follows entity+0x50 -> SI, and if SI+0x80 is bound
// dumps the handler vector (count/array + entry event names @+0xa8/+0xb0). This
// names the bind function to RE and shows the exact structure to reproduce.
// Purely read-only — no engine calls — every deref mincore()-guarded.
#include <sys/mman.h>

static bool addr_mapped(uintptr_t addr) {
    unsigned char vec = 0;
    return mincore((void*)(addr & ~(uintptr_t)4095), 4096, &vec) == 0;
}

// Android Scudo heap pointers have the tag 0xb400 in the top 16 bits.
static bool is_heap(uintptr_t v) {
    return v && (v >> 48) == 0xb400 && addr_mapped(v);
}

// A pointer into the mapped libqplayandroid.so image (vtables, code) — used to
// turn a runtime vtable slot back into a static VMA (ptr - g_base).
static bool is_module(uintptr_t v) {
    return g_base && v >= g_base && v < g_base + 0x0A00000 && addr_mapped(v);
}

// Interpret p as a GraalString [i32 len][i32 ref][utf8], or one level of
// indirection (p -> GraalString*). Returns "" if not a plausible ASCII name.
static std::string read_graalstr(uintptr_t p, int depth = 0) {
    if (!p || (p & 7) || !addr_mapped(p)) return "";
    int32_t len = *(int32_t*)p;
    if (len >= 1 && len <= 64 && addr_mapped(p + 8)) {
        const char* c = (const char*)(p + 8);
        std::string s;
        for (int i = 0; i < len; i++) {
            unsigned char ch = (unsigned char)c[i];
            if (ch < 0x20 || ch > 0x7e) { s.clear(); break; }
            s += (char)ch;
        }
        if (!s.empty()) return s;
    }
    if (depth == 0) return read_graalstr(*(uintptr_t*)p, 1);
    return "";
}

// Scan obj[0..0x100] for the first field that looks like a name string.
static std::string find_name(uintptr_t obj) {
    for (uint32_t o = 0; o <= 0x100; o += 8) {
        if (!addr_mapped(obj + o)) continue;
        std::string s = read_graalstr(*(uintptr_t*)(obj + o));
        if (s.size() >= 2) return s;
    }
    return "";
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_graal_exec_GraalExec_nativeDiag(JNIEnv* env, jclass) {
    if (!init_engine()) return env->NewStringUTF("ERR: engine not ready");

    std::string out;
    char tmp[256];

    // Level global is set during script ticks; sample briefly to catch one.
    volatile void** pLevel = (volatile void**)(g_base + 0x939bd0);
    void* level = nullptr;
    for (int i = 0; i < 200000 && !level; i++) level = (void*)*pLevel;
    void* reg = *(void**)(g_base + 0x939bc8);

    snprintf(tmp, sizeof(tmp), "level(939bd0)=%p\nreg(939bc8)=%p\n", level, reg);
    out += tmp;

    // Registry root fields — may lead to the level when the global is null.
    if (reg && addr_mapped((uintptr_t)reg)) {
        out += "reg:";
        for (uint32_t o = 0; o < 0x20; o += 8) {
            if (!addr_mapped((uintptr_t)reg + o)) break;
            snprintf(tmp, sizeof(tmp), " +%X=%p", o, *(void**)((uintptr_t)reg + o));
            out += tmp;
        }
        out += "\n";
    }

    if (!level) {
        out += "level=null — press Diag while in-world (move around first)\n";
        return env->NewStringUTF(out.c_str());
    }
    if (!addr_mapped((uintptr_t)level + 0xa0)) {
        out += "level+0xa0 unmapped\n";
        return env->NewStringUTF(out.c_str());
    }

    void* container = *(void**)((uintptr_t)level + 0xa0);
    if (!container || !addr_mapped((uintptr_t)container + 0x10)) {
        snprintf(tmp, sizeof(tmp), "container=%p (bad)\n", container);
        out += tmp;
        return env->NewStringUTF(out.c_str());
    }
    int32_t count = *(int32_t*)((uintptr_t)container + 0xc);
    void** arr = *(void***)((uintptr_t)container + 0x10);
    snprintf(tmp, sizeof(tmp), "actors: count=%d arr=%p\n", count, arr);
    out += tmp;

    if (count < 0 || count > 4096 || !arr || !addr_mapped((uintptr_t)arr)) {
        out += "actor array bad\n";
        return env->NewStringUTF(out.c_str());
    }

    snprintf(tmp, sizeof(tmp), "base=%p\n", (void*)g_base);
    out += tmp;

    // For the first entities: resolve the class vtable slots the tick calls
    // (bind=+0x68, onCreated=+0x70, guard=+0x98) to module offsets, then follow
    // entity+0x50 -> SI and dump SI+0x80 if a script is already bound.
    for (int i = 0; i < count && i < 6; i++) {
        if (!addr_mapped((uintptr_t)(arr + i))) break;
        uintptr_t ent = (uintptr_t)arr[i];
        if (!ent || (ent & 7) || !addr_mapped(ent)) continue;

        uintptr_t vt = *(uintptr_t*)ent;                 // entity C++ vtable
        snprintf(tmp, sizeof(tmp), "[%d]e=%p", i, (void*)ent);
        out += tmp;
        if (is_module(vt)) {
            snprintf(tmp, sizeof(tmp), " vt=+%lX", (unsigned long)(vt - g_base));
            out += tmp;
            const uint32_t slots[] = { 0x68, 0x70, 0x98, 0x08 };
            for (uint32_t s : slots) {
                if (!addr_mapped(vt + s)) continue;
                uintptr_t fn = *(uintptr_t*)(vt + s);
                if (is_module(fn))
                    snprintf(tmp, sizeof(tmp), " [%X]=+%lX", s, (unsigned long)(fn - g_base));
                else
                    snprintf(tmp, sizeof(tmp), " [%X]=%p", s, (void*)fn);
                out += tmp;
            }
        }
        out += "\n";

        // SI chain: entity+0x50 -> script instance
        uintptr_t si = addr_mapped(ent + 0x50) ? *(uintptr_t*)(ent + 0x50) : 0;
        if (!is_heap(si)) { out += "  +50: no SI\n"; continue; }
        uintptr_t si0  = addr_mapped(si)        ? *(uintptr_t*)(si)        : 0;
        uintptr_t si38 = addr_mapped(si + 0x38) ? *(uintptr_t*)(si + 0x38) : 0;
        uintptr_t si80 = addr_mapped(si + 0x80) ? *(uintptr_t*)(si + 0x80) : 0;
        snprintf(tmp, sizeof(tmp), "  SI=%p [0]%s +38=%s +80=%p\n",
                 (void*)si, (si0 == ent ? "=ENT" : "?"),
                 (is_heap(si38) ? "P" : "-"), (void*)si80);
        out += tmp;

        // If a handler table is already bound, dump it (the structure we must build)
        if (is_heap(si80)) {
            uint32_t hc  = addr_mapped(si80 + 0xc)  ? *(uint32_t*)(si80 + 0xc)  : 0;
            uintptr_t ha = addr_mapped(si80 + 0x10) ? *(uintptr_t*)(si80 + 0x10) : 0;
            snprintf(tmp, sizeof(tmp), "  SI+80 BOUND: count=%u arr=%p\n", hc, (void*)ha);
            out += tmp;
            for (uint32_t e = 0; e < hc && e < 5 && is_heap(ha); e++) {
                if (!addr_mapped(ha + e * 8)) break;
                uintptr_t entry = *(uintptr_t*)(ha + e * 8);
                if (!is_heap(entry)) continue;
                std::string n1 = addr_mapped(entry + 0xa8) ? read_graalstr(*(uintptr_t*)(entry + 0xa8)) : "";
                std::string n2 = addr_mapped(entry + 0xb0) ? read_graalstr(*(uintptr_t*)(entry + 0xb0)) : "";
                snprintf(tmp, sizeof(tmp), "    e%u: '%s' / '%s'\n", e, n1.c_str(), n2.c_str());
                out += tmp;
            }
        }
    }

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
