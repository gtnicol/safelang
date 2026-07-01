/*
 * SAFE Reference Counting — authoritative shared header. Both the native C
 * runtime (safe_runtime.h) and the WASM runtime (safe_wasm_builtins.c)
 * include this file, and both Java codegens (CCodeGenerator, WasmCompiler)
 * emit retain/release calls that obey the rules laid out here.
 *
 * Memory layout
 *   Every refcounted heap allocation is preceded by an 8-byte SAFEHeader.
 *   safe_alloc returns a pointer to the body (past the header) so struct-
 *   field access remains layout-compatible; retain/release derive the
 *   header via `((SAFEHeader*)body) - 1`.
 *
 * Ownership rules (identical on both backends)
 *   1. A fresh allocation has refs=1; the first slot receiving it takes
 *      that reference without a retain.
 *   2. Aliased assignment `slot := other` retains `other` before the store.
 *   3. Overwriting or dropping a slot releases the old value. If the new
 *      value equals the old, retain+release cancel — no pointer guard
 *      needed unless the source is a fresh producer.
 *   4. Typed containers (LIST/MAP/SET) retain heap-kind elements on insert
 *      and release them on overwrite/dispose, driven by the `meta` byte.
 *   5. String literals and other program-lifetime objects use the
 *      SAFE_REFS_IMMORTAL sentinel — retain/release are no-ops on them.
 *
 * `meta` encoding per kind
 *   LIST, SET  -> element kind (SAFE_KIND_*; 0 means scalar, no retention)
 *   MAP        -> (key_kind << 4) | value_kind
 *   BYTES, RAW -> 0
 *   STRING     -> 0
 *   TUPLE      -> bitmap; bit N set means element N is heap-refcounted
 *                 (fields 0..7 tracked; overflow spills to a side table)
 *   OBJECT     -> bitmap over declared struct fields (0..7 fast path)
 *   ENUM       -> bitmap over variant payload slots (0..7 fast path)
 *   CLOSURE    -> bitmap over captured variables (0..7 fast path)
 *
 * This header provides:
 *   - SAFEHeader layout + kind constants + immortal sentinel
 *   - safe_header / safe_kind / safe_meta accessors
 *   - safe_retain / safe_mark_immortal (no dependencies)
 *   - safe_kind_is_heap predicate, safe_meta_bit helper for bitmap kinds
 *
 * Each including runtime provides:
 *   - void* safe_alloc(size_t size, uint8_t kind, uint8_t meta)
 *   - void  safe_release(void* body)
 *   - void  safe_dispose(void* body)     // invoked by safe_release at refs==0
 */
#ifndef SAFE_REFCOUNT_H
#define SAFE_REFCOUNT_H

#include <stdint.h>
#include <stddef.h>

typedef struct {
    uint32_t refs;
    uint8_t  kind;          /* SAFE_KIND_* — see meta-per-kind table at top */
    uint8_t  size_class;    /* 0..SAFE_SIZECLASS_COUNT-1; 0xFF = oversize */
    uint16_t meta;          /* elem-kind for LIST/SET/MAP (low byte),
                             * 16-bit bitmap for TUPLE/OBJECT/ENUM/CLOSURE.
                             * Widened from 8 bits in Phase-7 audit so
                             * closures/tuples/structs with up to 16 heap
                             * slots retain/release correctly. */
} SAFEHeader;

/* Size-class allocator: 13 power-of-two buckets from 16 bytes to 64 KB.
 * Each bucket holds freed blocks linked via the body (first pointer).
 * Stdlib page.SIZE = 4096, so a Page block is 4096 + 4 (length prefix)
 * + 8 (SAFEHeader) = 4108 bytes and falls into the 8192 class; without
 * a class this big every page-sized allocation leaks on release. */
#define SAFE_SIZECLASS_COUNT 13
#define SAFE_SIZECLASS_OVERSIZE 0xFFu

static inline int safe_sizeclass_for(size_t total_bytes) {
    if (total_bytes <= 16)     return 0;
    if (total_bytes <= 32)     return 1;
    if (total_bytes <= 64)     return 2;
    if (total_bytes <= 128)    return 3;
    if (total_bytes <= 256)    return 4;
    if (total_bytes <= 512)    return 5;
    if (total_bytes <= 1024)   return 6;
    if (total_bytes <= 2048)   return 7;
    if (total_bytes <= 4096)   return 8;
    if (total_bytes <= 8192)   return 9;
    if (total_bytes <= 16384)  return 10;
    if (total_bytes <= 32768)  return 11;
    if (total_bytes <= 65536)  return 12;
    return -1;
}

static inline size_t safe_sizeclass_bytes(int cls) {
    return (size_t)16 << cls;
}

/* Kind constants. Keep in sync across runtimes and codegen. */
#define SAFE_KIND_LIST    1
#define SAFE_KIND_MAP     2
#define SAFE_KIND_BYTES   3
#define SAFE_KIND_TUPLE   4
#define SAFE_KIND_OBJECT  5   /* user-defined struct */
#define SAFE_KIND_ENUM    6   /* boxed enum with associated data */
#define SAFE_KIND_CLOSURE 7
#define SAFE_KIND_STRING  8   /* heap-allocated string (not a literal) */
#define SAFE_KIND_SET     9
#define SAFE_KIND_RAW    10   /* char[] / byte buffers (no children to release) */

/* Sentinel marking a value as immortal — retain/release are no-ops. Used for
 * string literals and other program-lifetime values. */
#define SAFE_REFS_IMMORTAL ((uint32_t)~0u)

/* ===== Cycle-collector color state (Bacon-Rajan), packed into refs =====
 * The native runtime adds a synchronous trial-deletion cycle collector so
 * cyclic object graphs (a.next=b; b.next=a) don't leak. The refcount word is
 * partitioned: [31:30]=color, [29]=buffered, [28:0]=count. SAFE_REFS_IMMORTAL
 * (~0u) stays an exact-compare sentinel — a live object's count never reaches
 * 0x1FFFFFFF, so it never collides. WASM has its own header/runtime and is
 * unaffected by these. */
#define SAFE_RC_COLOR_SHIFT 30
#define SAFE_RC_COLOR_MASK  (3u << SAFE_RC_COLOR_SHIFT)
#define SAFE_RC_BUFFERED    (1u << 29)
#define SAFE_RC_COUNT_MASK  ((1u << 29) - 1u)

#define SAFE_COLOR_BLACK  0u  /* in use or free */
#define SAFE_COLOR_GRAY   1u  /* possible member of a cycle (being marked) */
#define SAFE_COLOR_WHITE  2u  /* member of a garbage cycle */
#define SAFE_COLOR_PURPLE 3u  /* possible root of a cycle */

static inline uint32_t safe_rc_count(SAFEHeader* h) {
    return h->refs & SAFE_RC_COUNT_MASK;
}
static inline void safe_rc_set_count(SAFEHeader* h, uint32_t c) {
    h->refs = (h->refs & ~SAFE_RC_COUNT_MASK) | (c & SAFE_RC_COUNT_MASK);
}
static inline uint32_t safe_rc_color(SAFEHeader* h) {
    return (h->refs & SAFE_RC_COLOR_MASK) >> SAFE_RC_COLOR_SHIFT;
}
static inline void safe_rc_set_color(SAFEHeader* h, uint32_t color) {
    h->refs = (h->refs & ~SAFE_RC_COLOR_MASK) | ((color << SAFE_RC_COLOR_SHIFT) & SAFE_RC_COLOR_MASK);
}
static inline int safe_rc_buffered(SAFEHeader* h) {
    return (h->refs & SAFE_RC_BUFFERED) != 0;
}
static inline void safe_rc_set_buffered(SAFEHeader* h, int b) {
    if (b) h->refs |= SAFE_RC_BUFFERED; else h->refs &= ~SAFE_RC_BUFFERED;
}

/* Locate the header given a body pointer. */
static inline SAFEHeader* safe_header(void* body) {
    return ((SAFEHeader*)body) - 1;
}

/* Increment refcount. NULL- and immortal-safe. Returns `body` for chaining.
 * Bacon-Rajan: an incremented object is in use, so its color becomes black. */
static inline void* safe_retain(void* body) {
    if (body) {
        SAFEHeader* hdr = safe_header(body);
        if (hdr->refs != SAFE_REFS_IMMORTAL) {
            safe_rc_set_count(hdr, safe_rc_count(hdr) + 1);
            safe_rc_set_color(hdr, SAFE_COLOR_BLACK);
        }
    }
    return body;
}

/* Mark a body as immortal (retain/release no-op from this point on). */
static inline void safe_mark_immortal(void* body) {
    if (body) safe_header(body)->refs = SAFE_REFS_IMMORTAL;
}

static inline uint8_t safe_kind(void* body) {
    return body ? safe_header(body)->kind : 0;
}

static inline uint16_t safe_meta(void* body) {
    return body ? safe_header(body)->meta : 0;
}

/* Is the kind code a heap-allocated refcounted type whose pointer carries
 * a SAFEHeader that retain/release can operate on? STRING is heap when its
 * block was produced by safe_alloc (concat/substring/etc.); literals use
 * SAFE_REFS_IMMORTAL so retain/release still short-circuit harmlessly. */
static inline int safe_kind_is_heap(uint8_t kind) {
    switch (kind) {
        case SAFE_KIND_LIST:
        case SAFE_KIND_MAP:
        case SAFE_KIND_BYTES:
        case SAFE_KIND_TUPLE:
        case SAFE_KIND_OBJECT:
        case SAFE_KIND_ENUM:
        case SAFE_KIND_CLOSURE:
        case SAFE_KIND_STRING:
        case SAFE_KIND_SET:
            return 1;
        default:
            return 0;
    }
}

/* Bitmap-kinded meta helper. For TUPLE/OBJECT/ENUM/CLOSURE, bit N of meta
 * signals "slot N is heap-refcounted". Dispose paths use this to know
 * which slots to release. Slot counts above 16 require a side table —
 * codegen emits a compile-time diagnostic before reaching that limit. */
static inline int safe_meta_bit(uint16_t meta, int slot) {
    return (slot >= 0 && slot < 16) ? ((meta >> slot) & 1) : 0;
}

#endif /* SAFE_REFCOUNT_H */
