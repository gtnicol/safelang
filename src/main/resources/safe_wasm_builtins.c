// SAFE Wasm builtins — compiled to Wasm, linked at load time.
// Imports allocator from the main module so all allocations share the same heap.

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <sys/stat.h>
#include <fcntl.h>
#include <unistd.h>
#include <errno.h>
#include <wasi/api.h>

// Forward declarations
double safe_atan(double x);
double safe_exp(double x);
double safe_log(double x);
int safe_list_new(int capacity);
static int list_append(int list, long long elem);
static int list_append_internal(int list, long long elem);
int safe_str_substring(int str, int start, int end);
int safe_list_contains(int list, long long elem);

// Bump allocator — exported so the main module can use it too (shared memory).
// The main module calls safe_set_heap() at startup to position the heap after its data section.
// heap_ptr starts at 0; safe_set_heap() always initialises it before any allocation.
static unsigned int heap_ptr = 0;

__attribute__((export_name("safe_set_heap")))
void safe_set_heap(unsigned int start) {
    // __heap_base is the wasm-ld symbol for the end of ALL static data —
    // heap must start at or above this to avoid overwriting builtins globals.
    extern unsigned char __heap_base;
    unsigned int base = ((unsigned int)&__heap_base + 7) & ~7;
    unsigned int aligned = (start + 7) & ~7;
    heap_ptr = (aligned > base) ? aligned : base;
}

__attribute__((export_name("safe_alloc")))
int safe_alloc_export(int size) {
    size = (size + 7) & ~7;
    unsigned int ptr = heap_ptr;
    heap_ptr += size;
    unsigned int pages = __builtin_wasm_memory_size(0);
    while (ptr + size > pages * 65536) {
        size_t previous_pages = __builtin_wasm_memory_grow(0, 16);
        if (previous_pages == (size_t)-1) {
            fprintf(stderr, "safe: out of memory (heap_ptr=%u, requested=%d)\n",
                    (unsigned)ptr, size);
            __builtin_trap();
        }
        pages = __builtin_wasm_memory_size(0);
    }
    return (int)ptr;
}

static int bump_alloc(int size) {
    return safe_alloc_export(size);
}

/* ===== Refcount infrastructure (Phase 1 scaffolding) =====
 *
 * The WASM runtime allocates with bump_alloc and stores raw byte layouts in
 * linear memory — there's no per-heap-object type struct like the native C
 * runtime's SAFEList etc. For Phase 1 we expose a refcounted allocator
 * (safe_rc_alloc / safe_rc_retain / safe_rc_release) that prepends an
 * 8-byte SAFEHeader, but we don't migrate the existing bump_alloc call
 * sites — that's Phase 2 work, selectively applied to the types that need
 * ownership tracking.
 *
 * The shared header from safe_refcount.h defines the layout; we re-declare
 * the struct locally because this compilation unit doesn't share the native
 * runtime's include path. Keep both definitions in sync. */

typedef struct {
    unsigned int refs;
    unsigned char kind;
    unsigned char size_class;
    unsigned short meta;
} SAFEHeader;

#define SAFE_REFS_IMMORTAL ((unsigned int)~0u)
/* Size classes must stay in sync with safe_refcount.h — the Java side
 * (WasmRuntimeContext) assumes the same bucket count and layout. */
#define SAFE_SIZECLASS_COUNT 13
#define SAFE_SIZECLASS_OVERSIZE 0xFFFFu

/* Cap on list length, mirroring SAFEValue.MAX_LIST_SIZE and the native runtime — enforced at the
 * append choke points so a "terminating" program cannot exhaust linear memory. */
#define SAFE_MAX_LIST_SIZE 10000000

/* ===== Cycle-collector color state (Bacon-Rajan), packed into refs =====
 * Ported from the native runtime (safe_refcount.h). The refcount word is
 * partitioned exactly as native: [31:30]=color, [29]=buffered, [28:0]=count.
 * SAFE_REFS_IMMORTAL (~0u) stays an exact-compare sentinel (a live object's
 * count never reaches 0x1FFFFFFF, so it never collides). Every refs access in
 * this file goes through these accessors so the packing stays consistent. */
#define SAFE_RC_COLOR_SHIFT 30
#define SAFE_RC_COLOR_MASK  (3u << SAFE_RC_COLOR_SHIFT)
#define SAFE_RC_BUFFERED    (1u << 29)
#define SAFE_RC_COUNT_MASK  ((1u << 29) - 1u)

#define SAFE_COLOR_BLACK  0u  /* in use or free */
#define SAFE_COLOR_GRAY   1u  /* possible member of a cycle (being marked) */
#define SAFE_COLOR_WHITE  2u  /* member of a garbage cycle */
#define SAFE_COLOR_PURPLE 3u  /* possible root of a cycle */

static unsigned int safe_rc_count(SAFEHeader* h) {
    return h->refs & SAFE_RC_COUNT_MASK;
}
static void safe_rc_set_count(SAFEHeader* h, unsigned int c) {
    h->refs = (h->refs & ~SAFE_RC_COUNT_MASK) | (c & SAFE_RC_COUNT_MASK);
}
static unsigned int safe_rc_color(SAFEHeader* h) {
    return (h->refs & SAFE_RC_COLOR_MASK) >> SAFE_RC_COLOR_SHIFT;
}
static void safe_rc_set_color(SAFEHeader* h, unsigned int color) {
    h->refs = (h->refs & ~SAFE_RC_COLOR_MASK) | ((color << SAFE_RC_COLOR_SHIFT) & SAFE_RC_COLOR_MASK);
}
static int safe_rc_buffered(SAFEHeader* h) {
    return (h->refs & SAFE_RC_BUFFERED) != 0;
}
static void safe_rc_set_buffered(SAFEHeader* h, int b) {
    if (b) h->refs |= SAFE_RC_BUFFERED; else h->refs &= ~SAFE_RC_BUFFERED;
}

static int safe_rc_sizeclass_for(int total) {
    if (total <= 16)     return 0;
    if (total <= 32)     return 1;
    if (total <= 64)     return 2;
    if (total <= 128)    return 3;
    if (total <= 256)    return 4;
    if (total <= 512)    return 5;
    if (total <= 1024)   return 6;
    if (total <= 2048)   return 7;
    if (total <= 4096)   return 8;
    if (total <= 8192)   return 9;
    if (total <= 16384)  return 10;
    if (total <= 32768)  return 11;
    if (total <= 65536)  return 12;
    return -1;
}

static int safe_rc_sizeclass_bytes(int cls) { return 16 << cls; }

/* Size-class free lists. Entries use the first 4 bytes of body as
 * a next-pointer (head-of-list, LIFO). */
static int __safe_freelist[SAFE_SIZECLASS_COUNT] = {0};

/* Refcounted allocator: [SAFEHeader][body]. Returns body offset. */
__attribute__((export_name("safe_rc_alloc")))
int safe_rc_alloc(int size, int kind, int meta) {
    int total = 8 + size;
    int cls = safe_rc_sizeclass_for(total);
    int raw;
    if (cls >= 0 && __safe_freelist[cls] != 0) {
        raw = __safe_freelist[cls];
        __safe_freelist[cls] = *((int*)raw);
    } else {
        int alloc = cls >= 0 ? safe_rc_sizeclass_bytes(cls) : total;
        raw = safe_alloc_export(alloc);
    }
    SAFEHeader* hdr = (SAFEHeader*)(raw);
    hdr->refs = 1;
    hdr->kind = (unsigned char)kind;
    hdr->meta = (unsigned short)meta;
    hdr->size_class = (unsigned char)(cls >= 0 ? cls : SAFE_SIZECLASS_OVERSIZE);
    return raw + 8;
}

__attribute__((export_name("safe_rc_retain")))
int safe_rc_retain(int body) {
    if (body == 0) return 0;
    SAFEHeader* hdr = (SAFEHeader*)(body - 8);
    if (hdr->refs != SAFE_REFS_IMMORTAL) {
        /* Bacon-Rajan: an incremented object is in use, so its color is black. */
        safe_rc_set_count(hdr, safe_rc_count(hdr) + 1);
        safe_rc_set_color(hdr, SAFE_COLOR_BLACK);
    }
    return body;
}

/* Is a SAFE tag a heap-refcounted pointer whose body carries a SAFEHeader?
 *
 * Phase 6 scope (everything): STRING (3), LIST (5), MAP (6), SET (7),
 * ENUM (9), OBJECT (10), CLOSURE (11), BYTES (12). STRING literals live
 * in the data section but have a reserved SAFEHeader stamped as
 * SAFE_REFS_IMMORTAL at _start, so retain/release on them short-circuit
 * harmlessly. Runtime-produced heap strings (safe_str_concat, etc.)
 * carry real refcounts that the codegen-emitted retain/release path
 * maintains. TUPLE (8) is tagged as LIST by visitTupleLiteral so tag 8
 * never flows through retain/release.
 *
 * Calling retain/release on a tag whose body isn't header-prefixed
 * would read garbage bytes and corrupt memory, so keep this predicate
 * in sync with the actual allocation paths (see safe_rc_alloc call
 * sites). */
static int safe_tag_is_heap(int tag) {
    return tag == 3   /* STRING */
        || tag == 5   /* LIST */
        || tag == 6   /* MAP */
        || tag == 7   /* SET */
        || tag == 9   /* ENUM */
        || tag == 10  /* OBJECT */
        || tag == 11  /* CLOSURE */
        || tag == 12  /* BYTES */;
}

/* Map a WASM tag code to the shared SAFE_KIND_* constant. Used by dispose
 * paths that operate on a tagged value rather than a body pointer. Returns
 * 0 for non-heap tags. */
__attribute__((unused))
static int safe_tag_to_kind(int tag) {
    switch (tag) {
        case 5:  return 1;  /* LIST */
        case 6:  return 2;  /* MAP */
        case 7:  return 9;  /* SET */
        case 8:  return 4;  /* TUPLE */
        case 9:  return 6;  /* ENUM */
        case 10: return 5;  /* OBJECT */
        case 11: return 7;  /* CLOSURE */
        case 12: return 3;  /* BYTES */
        default: return 0;
    }
}

/* Retain the pointer inside a tagged i64 if its tag is a heap kind. */
__attribute__((export_name("safe_rc_retain_tagged")))
long long safe_rc_retain_tagged(long long tagged) {
    int tag = (int)(tagged & 0xF);
    if (safe_tag_is_heap(tag)) {
        int body = (int)((unsigned long long)tagged >> 4);
        if (body != 0) {
            SAFEHeader* hdr = (SAFEHeader*)(body - 8);
            if (hdr->refs != SAFE_REFS_IMMORTAL) {
                safe_rc_set_count(hdr, safe_rc_count(hdr) + 1);
                safe_rc_set_color(hdr, SAFE_COLOR_BLACK);
            }
        }
    }
    return tagged;
}

static void safe_rc_release_internal(int body);

/* Release the pointer inside a tagged i64 if its tag is a heap kind. */
__attribute__((export_name("safe_rc_release_tagged")))
void safe_rc_release_tagged(long long tagged) {
    int tag = (int)(tagged & 0xF);
    if (safe_tag_is_heap(tag)) {
        int body = (int)((unsigned long long)tagged >> 4);
        safe_rc_release_internal(body);
    }
}

/* Forward-declared map layout constants (the full map implementation lives
 * below; dispose_map needs the offsets up here). Keep in sync with the
 * identically-named #defines later in this file.
 *
 * Bucket (32 bytes):
 *   [i64 key | i64 val | i32 hash | i32 state | i32 next_ord | i32 prev_ord]
 * Header (24 bytes):
 *   [i32 count | i32 cap | i32 tombstones | i32 head_ord | i32 tail_ord | i32 rsv]
 *
 * Insertion order is threaded through `next_ord`/`prev_ord` so iteration
 * matches the LinkedHashMap semantics that the interpreter / bytecode VM
 * also follow. Sentinel -1 means "end of chain". */
#define SAFE_MAP_HEADER_BYTES 24
#define SAFE_MAP_BUCKET_BYTES 32
#define SAFE_MAP_KEY_OFF      0
#define SAFE_MAP_VAL_OFF      8
#define SAFE_MAP_HASH_OFF     16
#define SAFE_MAP_STATE_OFF    20
#define SAFE_MAP_NEXT_OFF     24
#define SAFE_MAP_PREV_OFF     28
#define SAFE_MAP_COUNT_OFF    0
#define SAFE_MAP_CAP_OFF      4
#define SAFE_MAP_TOMB_OFF     8
#define SAFE_MAP_HEAD_OFF     12
#define SAFE_MAP_TAIL_OFF     16
#define SAFE_MAP_STATE_FILLED 1

/* Per-kind dispose: release heap-tagged children then return block to the
 * size-class free list. Kinds correspond to SAFE_KIND_* shared with native.
 * Container layouts:
 *   LIST, SET : [4 length | 4 cap | 8 * cap tagged elements]
 *               — meta = element kind; walk all `length` slots.
 *   MAP       : [4 keys-list-body | 4 vals-list-body]
 *               — release the two child lists which dispose their own
 *                 elements.
 *   BYTES     : [4 length | bytes...]                     no children.
 *   TUPLE     : same body shape as LIST.
 *               — meta = bitmap; release slot N only if bit N is set.
 *   OBJECT    : [4 typeid | 4 pad | 8 * N fields]
 *               — meta = field bitmap, fields at offset 8 + i*8.
 *   ENUM      : [4 typeid | 4 variantidx | 4 fieldcount | 8 * N fields]
 *               — meta = payload bitmap, fields at offset 12 + i*8.
 *   CLOSURE   : [4 table_idx | 4 pad | 8 * N captures]
 *               — meta = capture bitmap, captures at offset 8 + i*8.
 *   STRING    : same layout as BYTES; no children (for heap strings, Phase 6).
 * Bitmap-kinded objects with more than 8 heap slots need a side table —
 * documented but not yet emitted; the bitmap byte covers slots 0..7. */
static void safe_rc_dispose(int body, int kind, int meta) {
    switch (kind) {
        case 1: /* SAFE_KIND_LIST */
        case 9: /* SAFE_KIND_SET */ {
            int len = *(int*)body;
            for (int i = 0; i < len; i++) {
                long long elem = *(long long*)(body + 8 + i * 8);
                safe_rc_release_tagged(elem);
            }
            break;
        }
        case 2: /* SAFE_KIND_MAP */ {
            /* Two MAP blocks exist: the 8-byte indirection created by
             * safe_map_new (first word = bucket-body offset, second word
             * = 0 reserved), and the variable-size bucket body itself
             * (header contains count/cap/tombstones/head/tail/reserved).
             * We distinguish by cap: the indirection's second word is
             * always 0; the bucket body's cap is always a power-of-two
             * >= SAFE_MAP_INITIAL_CAP (8). */
            int first = *(int*)body;
            int cap_word = *(int*)(body + SAFE_MAP_CAP_OFF);
            if (cap_word == 0 && first != 0) {
                /* Indirection: cascade into bucket body. */
                safe_rc_release_internal(first);
            } else {
                /* Bucket body: walk occupied buckets, release key+value. */
                int cap = cap_word;
                for (int i = 0; i < cap; i++) {
                    int b = body + SAFE_MAP_HEADER_BYTES
                          + i * SAFE_MAP_BUCKET_BYTES;
                    if (*(int*)(b + SAFE_MAP_STATE_OFF) == SAFE_MAP_STATE_FILLED) {
                        long long k = *(long long*)(b + SAFE_MAP_KEY_OFF);
                        long long v = *(long long*)(b + SAFE_MAP_VAL_OFF);
                        safe_rc_release_tagged(k);
                        safe_rc_release_tagged(v);
                    }
                }
            }
            break;
        }
        case 4: /* SAFE_KIND_TUPLE */ {
            /* Tuples share the list body shape on WASM (visitTupleLiteral
             * builds via listCreate + listAppend); release each bitmap-set
             * slot. Walk the lesser of the actual length and the 16-slot
             * bitmap limit. */
            int len = *(int*)body;
            int limit = len < 16 ? len : 16;
            for (int i = 0; i < limit; i++) {
                if ((meta >> i) & 1) {
                    long long elem = *(long long*)(body + 8 + i * 8);
                    safe_rc_release_tagged(elem);
                }
            }
            break;
        }
        case 5: /* SAFE_KIND_OBJECT */
        case 7: /* SAFE_KIND_CLOSURE */ {
            /* Fields / captures at offset 8 + i*8, no stored field count —
             * walk the full bitmap (slots 0..15). Bits for absent slots are
             * 0 by construction of meta. */
            for (int i = 0; i < 16; i++) {
                if ((meta >> i) & 1) {
                    long long slot = *(long long*)(body + 8 + i * 8);
                    safe_rc_release_tagged(slot);
                }
            }
            break;
        }
        case 6: /* SAFE_KIND_ENUM */ {
            /* Payload at offset 12 + i*8; stored field count at offset 8
             * bounds the bitmap walk so unused bits never read stale
             * memory. */
            int fieldCount = *(int*)(body + 8);
            int limit = fieldCount < 16 ? fieldCount : 16;
            for (int i = 0; i < limit; i++) {
                if ((meta >> i) & 1) {
                    long long slot = *(long long*)(body + 12 + i * 8);
                    safe_rc_release_tagged(slot);
                }
            }
            break;
        }
        default:
            /* BYTES, STRING, RAW: no internal heap-tagged children. */
            break;
    }
}

/* ===== Bacon-Rajan synchronous cycle collector =====
 * Ported from the native runtime (safe_runtime.h). Reference counting alone
 * leaks cyclic graphs (a.next=b; b.next=a); the trial-deletion passes reclaim
 * them. Runs when the possible-roots buffer fills (SAFE_WASM_GC_THRESHOLD) and
 * once at program end (safe_collect_cycles called from _start). "Free" here
 * returns a block to its size-class free list — the WASM analogue of native's
 * free(). Non-re-entrant (safe_gc_running guard). */

#ifndef SAFE_WASM_GC_THRESHOLD
#define SAFE_WASM_GC_THRESHOLD 1024
#endif

/* Return a released block to its size-class free list (children already
 * handled by the caller — no dispose here). */
static void safe_gc_free_block(int body, SAFEHeader* hdr) {
    unsigned char cls = hdr->size_class;
    if (cls < SAFE_SIZECLASS_COUNT) {
        int raw = body - 8;
        *((int*)raw) = __safe_freelist[cls];
        __safe_freelist[cls] = raw;
    }
}

/* Double-buffered possible-roots buffers (linear-memory int arrays of body
 * offsets). One is active (accepting roots); a collection processes the other,
 * and they swap each run so roots buffered *during* a collection accumulate
 * for the next run without mutating the set being iterated — the WASM analogue
 * of native's snapshot-and-reset. Buffers grow via bump-alloc (the old buffer
 * is leaked, but growth is logarithmic and reaches a steady state). */
static int __safe_roots[2]     = {0, 0};
static int __safe_roots_len[2] = {0, 0};
static int __safe_roots_cap[2] = {0, 0};
static int __safe_roots_active = 0;
static int __safe_gc_running   = 0;

void safe_collect_cycles(void);
static void safe_gc_children(int body, int op);
static void safe_gc_dispatch(int body, int op);
static void safe_mark_gray(int body);
static void safe_scan_black(int body);
static void safe_scan(int body);
static void safe_collect_white(int body);

static int safe_wasm_kind_has_children(int kind) {
    switch (kind) {
        /* LIST(1) MAP(2) TUPLE(4) OBJECT(5) ENUM(6) CLOSURE(7) SET(9). */
        case 1: case 2: case 4: case 5: case 6: case 7: case 9: return 1;
        default: return 0;
    }
}

static void safe_roots_push(int body) {
    int a = __safe_roots_active;
    if (__safe_roots_len[a] == __safe_roots_cap[a]) {
        int ncap = __safe_roots_cap[a] ? __safe_roots_cap[a] * 2 : 256;
        int nbuf = safe_alloc_export(ncap * 4);
        for (int i = 0; i < __safe_roots_len[a]; i++)
            *((int*)(nbuf + i * 4)) = *((int*)(__safe_roots[a] + i * 4));
        __safe_roots[a] = nbuf;
        __safe_roots_cap[a] = ncap;
    }
    *((int*)(__safe_roots[a] + __safe_roots_len[a] * 4)) = body;
    __safe_roots_len[a]++;
}

static void safe_collect_possible_root(int body) {
    SAFEHeader* hdr = (SAFEHeader*)(body - 8);
    if (hdr->refs == SAFE_REFS_IMMORTAL) return;
    if (!safe_wasm_kind_has_children(hdr->kind)) return;
    if (safe_rc_color(hdr) == SAFE_COLOR_PURPLE) return; /* already a candidate */
    safe_rc_set_color(hdr, SAFE_COLOR_PURPLE);
    if (!safe_rc_buffered(hdr)) {
        safe_rc_set_buffered(hdr, 1);
        safe_roots_push(body);
        if (__safe_roots_len[__safe_roots_active] >= SAFE_WASM_GC_THRESHOLD) safe_collect_cycles();
    }
}

#define SAFE_GC_TRIAL_DEC 0
#define SAFE_GC_SCAN      1
#define SAFE_GC_RESTORE   2
#define SAFE_GC_COLLECT   3

/* Apply a pass op to a heap-tagged child (no-op for scalars/NULL). */
static void safe_gc_child(long long tagged, int op) {
    int tag = (int)(tagged & 0xF);
    if (!safe_tag_is_heap(tag)) return;
    int b = (int)((unsigned long long)tagged >> 4);
    if (b) safe_gc_dispatch(b, op);
}

/* Walk the heap children of `body`, applying `op` to each. Mirrors the per-kind
 * layout of safe_rc_dispose — keep the two in sync. MAP has two blocks: the
 * 8-byte indirection (child = bucket body, by offset) and the bucket body
 * (children = filled key/value slots). */
static void safe_gc_children(int body, int op) {
    SAFEHeader* hdr = (SAFEHeader*)(body - 8);
    switch (hdr->kind) {
        case 1: case 9: { /* LIST, SET */
            int len = *(int*)body;
            for (int i = 0; i < len; i++)
                safe_gc_child(*(long long*)(body + 8 + i * 8), op);
            break;
        }
        case 2: { /* MAP */
            int first = *(int*)body;
            int cap_word = *(int*)(body + SAFE_MAP_CAP_OFF);
            if (cap_word == 0 && first != 0) {
                safe_gc_dispatch(first, op); /* indirection -> bucket body */
            } else {
                int cap = cap_word;
                for (int i = 0; i < cap; i++) {
                    int bkt = body + SAFE_MAP_HEADER_BYTES + i * SAFE_MAP_BUCKET_BYTES;
                    if (*(int*)(bkt + SAFE_MAP_STATE_OFF) == SAFE_MAP_STATE_FILLED) {
                        safe_gc_child(*(long long*)(bkt + SAFE_MAP_KEY_OFF), op);
                        safe_gc_child(*(long long*)(bkt + SAFE_MAP_VAL_OFF), op);
                    }
                }
            }
            break;
        }
        case 4: { /* TUPLE (bitmap, slots at 8+i*8) */
            int len = *(int*)body;
            int limit = len < 16 ? len : 16;
            for (int i = 0; i < limit; i++)
                if ((hdr->meta >> i) & 1) safe_gc_child(*(long long*)(body + 8 + i * 8), op);
            break;
        }
        case 5: case 7: { /* OBJECT, CLOSURE (bitmap, slots at 8+i*8) */
            for (int i = 0; i < 16; i++)
                if ((hdr->meta >> i) & 1) safe_gc_child(*(long long*)(body + 8 + i * 8), op);
            break;
        }
        case 6: { /* ENUM (fieldcount at 8, payload at 12+i*8) */
            int fieldCount = *(int*)(body + 8);
            int limit = fieldCount < 16 ? fieldCount : 16;
            for (int i = 0; i < limit; i++)
                if ((hdr->meta >> i) & 1) safe_gc_child(*(long long*)(body + 12 + i * 8), op);
            break;
        }
        default: break;
    }
}

/* MarkRoots: gray each node, trial-decrementing children's counts. */
static void safe_gc_trial_dec(int body) {
    if (!body) return;
    SAFEHeader* h = (SAFEHeader*)(body - 8);
    if (h->refs == SAFE_REFS_IMMORTAL) return;
    if (safe_rc_count(h) > 0) safe_rc_set_count(h, safe_rc_count(h) - 1);
    safe_mark_gray(body);
}
static void safe_mark_gray(int body) {
    if (!body) return;
    SAFEHeader* h = (SAFEHeader*)(body - 8);
    if (h->refs == SAFE_REFS_IMMORTAL) return;
    if (safe_rc_color(h) != SAFE_COLOR_GRAY) {
        safe_rc_set_color(h, SAFE_COLOR_GRAY);
        safe_gc_children(body, SAFE_GC_TRIAL_DEC);
    }
}

/* ScanRoots: restore counts of externally reachable (black) subgraphs; leave
 * true garbage white. */
static void safe_gc_restore(int body) {
    if (!body) return;
    SAFEHeader* h = (SAFEHeader*)(body - 8);
    if (h->refs == SAFE_REFS_IMMORTAL) return;
    safe_rc_set_count(h, safe_rc_count(h) + 1);
    if (safe_rc_color(h) != SAFE_COLOR_BLACK) safe_scan_black(body);
}
static void safe_scan_black(int body) {
    if (!body) return;
    SAFEHeader* h = (SAFEHeader*)(body - 8);
    if (h->refs == SAFE_REFS_IMMORTAL) return;
    safe_rc_set_color(h, SAFE_COLOR_BLACK);
    safe_gc_children(body, SAFE_GC_RESTORE);
}
static void safe_scan(int body) {
    if (!body) return;
    SAFEHeader* h = (SAFEHeader*)(body - 8);
    if (h->refs == SAFE_REFS_IMMORTAL) return;
    if (safe_rc_color(h) == SAFE_COLOR_GRAY) {
        if (safe_rc_count(h) > 0) {
            safe_scan_black(body);
        } else {
            safe_rc_set_color(h, SAFE_COLOR_WHITE);
            safe_gc_children(body, SAFE_GC_SCAN);
        }
    }
}

/* CollectRoots: free the white cycle members. */
static void safe_collect_white(int body) {
    if (!body) return;
    SAFEHeader* h = (SAFEHeader*)(body - 8);
    if (h->refs == SAFE_REFS_IMMORTAL) return;
    if (safe_rc_color(h) == SAFE_COLOR_WHITE && !safe_rc_buffered(h)) {
        safe_rc_set_color(h, SAFE_COLOR_BLACK); /* set before recursion so a cycle frees once */
        safe_gc_children(body, SAFE_GC_COLLECT);
        safe_gc_free_block(body, h);
    }
}

static void safe_gc_dispatch(int body, int op) {
    switch (op) {
        case SAFE_GC_TRIAL_DEC: safe_gc_trial_dec(body); break;
        case SAFE_GC_SCAN:      safe_scan(body); break;
        case SAFE_GC_RESTORE:   safe_gc_restore(body); break;
        case SAFE_GC_COLLECT:   safe_collect_white(body); break;
    }
}

__attribute__((export_name("safe_collect_cycles")))
void safe_collect_cycles(void) {
    if (__safe_gc_running) return;
    __safe_gc_running = 1;

    /* Flip active: roots buffered while disposing corpses below accumulate in
     * the other buffer for the next run, leaving the processed set stable. */
    int a = __safe_roots_active;
    int spare = 1 - a;
    __safe_roots_len[spare] = 0;
    __safe_roots_active = spare;

    int base = __safe_roots[a];
    int n = __safe_roots_len[a];

    /* MarkRoots: gray every still-purple, still-referenced root; drop the rest,
     * freeing any that refcounting already reduced to a black/count-0 corpse. */
    int kept = 0;
    for (int i = 0; i < n; i++) {
        int s = *((int*)(base + i * 4));
        SAFEHeader* h = (SAFEHeader*)(s - 8);
        if (safe_rc_color(h) == SAFE_COLOR_PURPLE && safe_rc_count(h) > 0) {
            safe_mark_gray(s);
            *((int*)(base + kept * 4)) = s;
            kept++;
        } else {
            safe_rc_set_buffered(h, 0);
            if (safe_rc_color(h) == SAFE_COLOR_BLACK && safe_rc_count(h) == 0) {
                safe_rc_dispose(s, h->kind, h->meta);
                safe_gc_free_block(s, h);
            }
        }
    }
    n = kept;

    /* ScanRoots. */
    for (int i = 0; i < n; i++) safe_scan(*((int*)(base + i * 4)));

    /* CollectRoots: free the white cycles. */
    for (int i = 0; i < n; i++) {
        int s = *((int*)(base + i * 4));
        safe_rc_set_buffered((SAFEHeader*)(s - 8), 0);
        safe_collect_white(s);
    }

    __safe_roots_len[a] = 0;
    __safe_gc_running = 0;
}

static void safe_rc_release_internal(int body) {
    if (body == 0) return;
    SAFEHeader* hdr = (SAFEHeader*)(body - 8);
    if (hdr->refs == SAFE_REFS_IMMORTAL) return;
    unsigned int count = safe_rc_count(hdr);
    if (count == 0) return; /* defensive — already released */
    count -= 1;
    safe_rc_set_count(hdr, count);
    if (count == 0) {
        if (safe_rc_buffered(hdr)) {
            /* Sitting in the roots buffer; freeing now would dangle that entry.
             * Leave a black/count-0 corpse for MarkRoots to dispose and free. */
            safe_rc_set_color(hdr, SAFE_COLOR_BLACK);
        } else {
            safe_rc_dispose(body, hdr->kind, hdr->meta);
            safe_gc_free_block(body, hdr);
        }
    } else {
        /* A dropped ref to a container could be the last external pointer
         * keeping a cycle alive — buffer it as a possible cycle root. */
        safe_collect_possible_root(body);
    }
}

__attribute__((export_name("safe_rc_release")))
void safe_rc_release(int body) {
    safe_rc_release_internal(body);
}

__attribute__((export_name("safe_rc_mark_immortal")))
void safe_rc_mark_immortal(int body) {
    if (body == 0) return;
    SAFEHeader* hdr = (SAFEHeader*)(body - 8);
    hdr->refs = SAFE_REFS_IMMORTAL;
}

__attribute__((export_name("safe_heap_report")))
void safe_heap_report(void) {
    const char* flag = getenv("SAFE_HEAP_REPORT");
    if (flag && flag[0] && flag[0] != '0') {
        fprintf(stderr, "safe: wasm heap peak = %u bytes\n", (unsigned)heap_ptr);
    }
}

#define SAFE_MAP_TRACE 0
#if SAFE_MAP_TRACE
#define MAP_TRACE_PUT(map, key, value) \
    fprintf(stderr, "[map][put] map=%d key=0x%llx key_tag=%d value=0x%llx value_tag=%d\n", \
        map, (unsigned long long)key >> 4, (int)(key & 0xF), (unsigned long long)value >> 4, (int)(value & 0xF))
#define MAP_TRACE_GET(map, key, result) \
    fprintf(stderr, "[map][get] map=%d key=0x%llx key_tag=%d result=0x%llx result_tag=%d\n", \
        map, (unsigned long long)key >> 4, (int)(key & 0xF), (unsigned long long)result >> 4, (int)(result & 0xF))
#define MAP_TRACE_CONTAINS(map, key, result) \
    fprintf(stderr, "[map][contains] map=%d key=0x%llx key_tag=%d result=%d\n", \
        map, (unsigned long long)key >> 4, (int)(key & 0xF), result)
#define MAP_TRACE_REMOVE(map, key, result) \
    fprintf(stderr, "[map][remove] map=%d key=0x%llx key_tag=%d result=%d\n", \
        map, (unsigned long long)key >> 4, (int)(key & 0xF), result)
#else
#define MAP_TRACE_PUT(map, key, value) ((void)0)
#define MAP_TRACE_GET(map, key, result) ((void)0)
#define MAP_TRACE_CONTAINS(map, key, result) ((void)0)
#define MAP_TRACE_REMOVE(map, key, result) ((void)0)
#endif

// ==================== Math builtins ====================

__attribute__((export_name("safe_sin")))
double safe_sin(double x) {
    double pi = 3.14159265358979323846;
    while (x > pi) x -= 2.0 * pi;
    while (x < -pi) x += 2.0 * pi;
    double term = x, sum = x;
    for (int i = 1; i < 12; i++) { term *= -x * x / ((2*i) * (2*i + 1)); sum += term; }
    return sum;
}

__attribute__((export_name("safe_cos")))
double safe_cos(double x) {
    double pi = 3.14159265358979323846;
    while (x > pi) x -= 2.0 * pi;
    while (x < -pi) x += 2.0 * pi;
    double term = 1.0, sum = 1.0;
    for (int i = 1; i < 12; i++) { term *= -x * x / ((2*i - 1) * (2*i)); sum += term; }
    return sum;
}

__attribute__((export_name("safe_tan")))
double safe_tan(double x) { return safe_sin(x) / safe_cos(x); }

__attribute__((export_name("safe_exp")))
double safe_exp(double x) {
    double term = 1.0, sum = 1.0;
    for (int i = 1; i < 30; i++) { term *= x / i; sum += term; }
    return sum;
}

__attribute__((export_name("safe_log")))
double safe_log(double x) {
    if (x <= 0) return -1e308;
    double y = 0.0;
    if (x > 1) { double t = x; while (t > 2.718281828) { y += 1; t /= 2.718281828; } }
    else { double t = x; while (t < 1) { y -= 1; t *= 2.718281828; } }
    for (int i = 0; i < 50; i++) { double ey = safe_exp(y); y = y + (x - ey) / ey; }
    return y;
}

__attribute__((export_name("safe_log10")))
double safe_log10(double x) { return safe_log(x) / 2.302585092994046; }

__attribute__((export_name("safe_pow")))
double safe_pow(double base, double e) {
    if (e == 0.0) return 1.0;
    if (base == 0.0) return 0.0;
    long ie = (long)e;
    if ((double)ie == e && ie >= 0) { double r = 1.0; for (long i = 0; i < ie; i++) r *= base; return r; }
    return safe_exp(e * safe_log(base));
}

__attribute__((export_name("safe_asin")))
double safe_asin(double x) {
    // Use atan identity: asin(x) = atan(x / sqrt(1 - x*x))
    if (x >= 1.0) return 1.5707963267948966;
    if (x <= -1.0) return -1.5707963267948966;
    double denom = 1.0 - x * x;
    if (denom <= 0.0) return x > 0 ? 1.5707963267948966 : -1.5707963267948966;
    // sqrt via Newton's method
    double t = denom;
    for (int i = 0; i < 20; i++) { t = 0.5 * (t + denom / t); }
    return safe_atan(x / t);
}

__attribute__((export_name("safe_acos")))
double safe_acos(double x) { return 1.5707963267948966 - safe_asin(x); }

__attribute__((export_name("safe_atan")))
double safe_atan(double x) {
    // CORDIC-like range reduction + Taylor series
    if (x > 1.0) return 1.5707963267948966 - safe_atan(1.0/x);
    if (x < -1.0) return -1.5707963267948966 - safe_atan(1.0/x);
    // Further reduce: if |x| > 0.4142, use atan(x) = pi/6 + atan((x*sqrt3 - 1)/(x + sqrt3))
    if (x > 0.4142135623730950) {
        double s3 = 1.7320508075688772;
        return 0.5235987755982988 + safe_atan((x * s3 - 1.0) / (x + s3));
    }
    if (x < -0.4142135623730950) {
        double s3 = 1.7320508075688772;
        return -0.5235987755982988 + safe_atan((x * s3 + 1.0) / (x - s3));
    }
    // Taylor for |x| <= 0.4142
    double x2 = x * x, term = x, sum = x;
    for (int i = 1; i < 20; i++) { term *= -x2; sum += term / (2*i + 1); }
    return sum;
}

__attribute__((export_name("safe_atan2")))
double safe_atan2(double y, double x) {
    double pi = 3.14159265358979323846;
    if (x > 0) return safe_atan(y / x);
    if (x < 0 && y >= 0) return safe_atan(y / x) + pi;
    if (x < 0 && y < 0) return safe_atan(y / x) - pi;
    if (x == 0 && y > 0) return pi / 2;
    if (x == 0 && y < 0) return -pi / 2;
    return 0;
}

// ==================== String builtins ====================
// These operate on length-prefixed strings: [i32 len][bytes...]
// They use the internal bump allocator for results.

__attribute__((export_name("safe_str_upper")))
int safe_str_upper(int ptr) {
    int len = *(int*)ptr;
    int result = safe_rc_alloc(4 + len, 8 /* SAFE_KIND_STRING */, 0);
    *(int*)result = len;
    for (int i = 0; i < len; i++) {
        unsigned char c = *((unsigned char*)(ptr + 4 + i));
        if (c >= 'a' && c <= 'z') c -= 32;
        *((unsigned char*)(result + 4 + i)) = c;
    }
    return result;
}

__attribute__((export_name("safe_str_lower")))
int safe_str_lower(int ptr) {
    int len = *(int*)ptr;
    int result = safe_rc_alloc(4 + len, 8 /* SAFE_KIND_STRING */, 0);
    *(int*)result = len;
    for (int i = 0; i < len; i++) {
        unsigned char c = *((unsigned char*)(ptr + 4 + i));
        if (c >= 'A' && c <= 'Z') c += 32;
        *((unsigned char*)(result + 4 + i)) = c;
    }
    return result;
}

__attribute__((export_name("safe_str_trim")))
int safe_str_trim(int ptr) {
    int len = *(int*)ptr;
    int start = 0, end = len;
    while (start < len) { unsigned char c = *((unsigned char*)(ptr + 4 + start)); if (c != ' ' && c != '\t' && c != '\n' && c != '\r') break; start++; }
    while (end > start) { unsigned char c = *((unsigned char*)(ptr + 4 + end - 1)); if (c != ' ' && c != '\t' && c != '\n' && c != '\r') break; end--; }
    int rlen = end - start;
    int result = safe_rc_alloc(4 + rlen, 8 /* SAFE_KIND_STRING */, 0);
    *(int*)result = rlen;
    for (int i = 0; i < rlen; i++) *((unsigned char*)(result + 4 + i)) = *((unsigned char*)(ptr + 4 + start + i));
    return result;
}

__attribute__((export_name("safe_str_replace")))
int safe_str_replace(int str, int old, int rep) {
    int slen = *(int*)str, olen = *(int*)old, nlen = *(int*)rep;
    for (int i = 0; i <= slen - olen; i++) {
        int match = 1;
        for (int j = 0; j < olen; j++) if (*((unsigned char*)(str + 4 + i + j)) != *((unsigned char*)(old + 4 + j))) { match = 0; break; }
        if (match) {
            int rlen = slen - olen + nlen;
            int result = safe_rc_alloc(4 + rlen, 8 /* SAFE_KIND_STRING */, 0);
            *(int*)result = rlen;
            for (int j = 0; j < i; j++) *((unsigned char*)(result + 4 + j)) = *((unsigned char*)(str + 4 + j));
            for (int j = 0; j < nlen; j++) *((unsigned char*)(result + 4 + i + j)) = *((unsigned char*)(rep + 4 + j));
            for (int j = i + olen; j < slen; j++) *((unsigned char*)(result + 4 + j - olen + nlen)) = *((unsigned char*)(str + 4 + j));
            return result;
        }
    }
    /* No match: return input, but retain so the caller sees a fresh-owning
     * reference. Without this the caller's refs=1 and str's own refs=1 are
     * the same slot, and later releases double-decrement it. */
    safe_rc_retain_tagged(((long long)(unsigned int)str << 4) | 3);
    return str;
}

__attribute__((export_name("safe_str_starts")))
int safe_str_starts(int str, int prefix) {
    int slen = *(int*)str, plen = *(int*)prefix;
    if (plen > slen) return 0;
    for (int i = 0; i < plen; i++) if (*((unsigned char*)(str + 4 + i)) != *((unsigned char*)(prefix + 4 + i))) return 0;
    return 1;
}

__attribute__((export_name("safe_str_ends")))
int safe_str_ends(int str, int suffix) {
    int slen = *(int*)str, xlen = *(int*)suffix;
    if (xlen > slen) return 0;
    int offset = slen - xlen;
    for (int i = 0; i < xlen; i++) if (*((unsigned char*)(str + 4 + offset + i)) != *((unsigned char*)(suffix + 4 + i))) return 0;
    return 1;
}

__attribute__((export_name("safe_str_repeat")))
int safe_str_repeat(int str, int n) {
    int slen = *(int*)str;
    int rlen = slen * n;
    int result = safe_rc_alloc(4 + rlen, 8 /* SAFE_KIND_STRING */, 0);
    *(int*)result = rlen;
    for (int i = 0; i < n; i++)
        for (int j = 0; j < slen; j++)
            *((unsigned char*)(result + 4 + i * slen + j)) = *((unsigned char*)(str + 4 + j));
    return result;
}

__attribute__((export_name("safe_str_reversed")))
int safe_str_reversed(int str) {
    int len = *(int*)str;
    int result = safe_rc_alloc(4 + len, 8 /* SAFE_KIND_STRING */, 0);
    *(int*)result = len;
    for (int i = 0; i < len; i++) *((unsigned char*)(result + 4 + i)) = *((unsigned char*)(str + 4 + len - 1 - i));
    return result;
}

__attribute__((export_name("safe_str_substring")))
int safe_str_substring(int str, int start, int end) {
    int len = *(int*)str;
    if (start < 0) start = 0;
    if (end > len) end = len;
    if (start >= end) {
        int result = safe_rc_alloc(4, 8 /* SAFE_KIND_STRING */, 0);
        *(int*)result = 0;
        return result;
    }
    int count = end - start;
    int result = safe_rc_alloc(4 + count, 8 /* SAFE_KIND_STRING */, 0);
    *(int*)result = count;
    for (int i = 0; i < count; i++)
        *((unsigned char*)(result + 4 + i)) = *((unsigned char*)(str + 4 + start + i));
    return result;
}

__attribute__((export_name("safe_str_indexof")))
int safe_str_indexof(int str, int search) {
    int len = *(int*)str;
    int slen = *(int*)search;
    if (slen == 0) return 0;
    for (int i = 0; i <= len - slen; i++) {
        int match = 1;
        for (int j = 0; j < slen; j++) {
            if (*((unsigned char*)(str + 4 + i + j)) != *((unsigned char*)(search + 4 + j))) {
                match = 0; break;
            }
        }
        if (match) return i;
    }
    return -1;
}

__attribute__((export_name("safe_str_charat")))
int safe_str_charat(int str, int index) {
    int len = *(int*)str;
    if (index < 0 || index >= len) {
        int result = safe_rc_alloc(4, 8 /* SAFE_KIND_STRING */, 0);
        *(int*)result = 0;
        return result;
    }
    int result = safe_rc_alloc(5, 8 /* SAFE_KIND_STRING */, 0);
    *(int*)result = 1;
    *((unsigned char*)(result + 4)) = *((unsigned char*)(str + 4 + index));
    return result;
}

__attribute__((export_name("safe_str_split")))
int safe_str_split(int str, int delim) {
    int len = *(int*)str;
    int dlen = *(int*)delim;
    // Create a list of substrings
    int list = safe_list_new(0);
    int start = 0;
    for (int i = 0; i <= len - dlen; i++) {
        int match = 1;
        for (int j = 0; j < dlen; j++) {
            if (*((unsigned char*)(str + 4 + i + j)) != *((unsigned char*)(delim + 4 + j))) {
                match = 0; break;
            }
        }
        if (match) {
            int sub = safe_str_substring(str, start, i);
            long long tagged = ((long long)sub << 4) | 3; // TAG_STRING = 3
            list = list_append_internal(list, tagged);
            start = i + dlen;
            i = start - 1; // will be incremented by loop
        }
    }
    // Add remaining substring
    int sub = safe_str_substring(str, start, len);
    long long tagged = ((long long)sub << 4) | 3;
    list = list_append_internal(list, tagged);
    return list;
}

__attribute__((export_name("safe_str_chars")))
int safe_str_chars(int str) {
    int len = *(int*)str;
    int list = safe_list_new(len);
    for (int i = 0; i < len; i++) {
        // Create a 1-char string for each byte
        int ch = safe_rc_alloc(5, 8 /* SAFE_KIND_STRING */, 0);
        *(int*)ch = 1;
        *((unsigned char*)(ch + 4)) = *((unsigned char*)(str + 4 + i));
        long long tagged = ((long long)ch << 4) | 3; // TAG_STRING = 3
        list = list_append_internal(list, tagged);
    }
    return list;
}

__attribute__((export_name("safe_ordinal")))
int safe_ordinal(int str) {
    // Return the byte value of the first character
    if (*(int*)str == 0) return 0;
    return *((unsigned char*)(str + 4));
}

__attribute__((export_name("safe_charcode")))
int safe_charcode(int code) {
    // Create a 1-char string from a byte value
    int result = safe_rc_alloc(5, 8 /* SAFE_KIND_STRING */, 0);
    *(int*)result = 1;
    *((unsigned char*)(result + 4)) = (unsigned char)code;
    return result;
}

// ==================== Collection builtins ====================
// These operate on the tagged value format:
//   Tagged i64: bits 0-3 = tag, bits 4-63 = payload (value or pointer << 4)
//   String: [i32 len][bytes...]
//   List:   [i32 len][i32 cap][i64 elements...]
//   Map:    [i32 keys_ptr][i32 values_ptr]  (keys/values are lists)

// Helper: compare two tagged values for equality
static int tagged_eq(long long a, long long b) {
    int tag_a = (int)(a & 0xF);
    int tag_b = (int)(b & 0xF);
    if (tag_a != tag_b) return 0;
    if (tag_a == 3) { // string
        int pa = (int)((unsigned long long)a >> 4);
        int pb = (int)((unsigned long long)b >> 4);
        if (pa == pb) return 1;
        int la = *(int*)pa, lb = *(int*)pb;
        if (la != lb) return 0;
        for (int i = 0; i < la; i++)
            if (*((unsigned char*)(pa+4+i)) != *((unsigned char*)(pb+4+i))) return 0;
        return 1;
    }
    return a == b;
}

// Helper: compare two tagged values for ordering.
static int tagged_compare(long long a, long long b) {
    int tag_a = (int)(a & 0xF);
    int tag_b = (int)(b & 0xF);
    if (tag_a != tag_b) return tag_a < tag_b ? -1 : 1;
    switch (tag_a) {
        case 0: { // int
            long long va = a >> 4;
            long long vb = b >> 4;
            if (va < vb) return -1;
            if (va > vb) return 1;
            return 0;
        }
        case 1: { // float
            long long bits_a = a & ~(long long)0xF;
            long long bits_b = b & ~(long long)0xF;
            double va, vb;
            __builtin_memcpy(&va, &bits_a, sizeof(double));
            __builtin_memcpy(&vb, &bits_b, sizeof(double));
            if (va < vb) return -1;
            if (va > vb) return 1;
            return 0;
        }
        case 2: { // bool
            int va = (int)((unsigned long long)a >> 4);
            int vb = (int)((unsigned long long)b >> 4);
            if (va < vb) return -1;
            if (va > vb) return 1;
            return 0;
        }
        case 3: { // string
            int pa = (int)((unsigned long long)a >> 4);
            int pb = (int)((unsigned long long)b >> 4);
            int la = *(int*)pa;
            int lb = *(int*)pb;
            int limit = la < lb ? la : lb;
            for (int i = 0; i < limit; i++) {
                unsigned char ca = *((unsigned char*)(pa + 4 + i));
                unsigned char cb = *((unsigned char*)(pb + 4 + i));
                if (ca < cb) return -1;
                if (ca > cb) return 1;
            }
            if (la < lb) return -1;
            if (la > lb) return 1;
            return 0;
        }
        case 13: { // uint
            unsigned long long va = (unsigned long long)a >> 4;
            unsigned long long vb = (unsigned long long)b >> 4;
            if (va < vb) return -1;
            if (va > vb) return 1;
            return 0;
        }
        default:
            if (a < b) return -1;
            if (a > b) return 1;
            return 0;
    }
}

// Helper: get list length
static int list_len(int ptr) { return *(int*)ptr; }

// Helper: get list element
static long long list_get(int ptr, int idx) {
    return *(long long*)(ptr + 8 + idx * 8);
}

// Helper: append to list, returns list ptr. Grow path creates a header-
// prefixed block via safe_rc_alloc; OLD block is not mutated or released
// (caller owns it and will release it via codegen-emitted release-on-
// reassignment). Mirrors native safe_list_append_copy semantics.
//
// Phase B unique-owner fast path: when the caller holds the only
// reference (refs==1), the grow path MOVES elements into the new block
// instead of copy+retain. The old block's length is zeroed so when the
// caller releases it, dispose skips the element walk — net savings are
// N retains + N releases per grow.
static int list_append(int ptr, long long val) {
    int len = *(int*)ptr;
    if (len >= SAFE_MAX_LIST_SIZE) {
        fprintf(stderr, "list size exceeds maximum of %d\n", SAFE_MAX_LIST_SIZE);
        __builtin_trap();
    }
    int cap = *(int*)(ptr + 4);
    if (len < cap) {
        *(long long*)(ptr + 8 + len * 8) = val;
        *(int*)ptr = len + 1;
        return ptr;
    }
    int newcap = cap < 4 ? 8 : cap * 2;
    SAFEHeader* oldhdr = (SAFEHeader*)(ptr - 8);
    /* Unique-owner check must read the packed count, not the raw refs word —
     * a buffered/colored block has high bits set (see safe_rc accessors). */
    int unique = (safe_rc_count(oldhdr) == 1);
    int newptr = safe_rc_alloc(8 + newcap * 8, oldhdr->kind, oldhdr->meta);
    /* safe_rc_alloc may have changed `ptr`'s refs? No — it only allocates.
     * But re-read the header pointer in case the allocator relocated
     * state (it doesn't today, but this is defensive). */
    *(int*)newptr = len + 1;
    *(int*)(newptr + 4) = newcap;
    if (unique) {
        /* Move elements without retaining; caller's release of old block
         * sees length=0 and skips the element walk. */
        for (int i = 0; i < len; i++) {
            *(long long*)(newptr + 8 + i * 8) = *(long long*)(ptr + 8 + i * 8);
        }
        *(int*)ptr = 0;
    } else {
        /* Shared: retain each element so the new block owns its own refs. */
        for (int i = 0; i < len; i++) {
            long long elem = *(long long*)(ptr + 8 + i * 8);
            safe_rc_retain_tagged(elem);
            *(long long*)(newptr + 8 + i * 8) = elem;
        }
    }
    *(long long*)(newptr + 8 + len * 8) = val;
    return newptr;
}

/* Internal-only list-append that also releases the old block when the
 * grow path forks to a new allocation. Use from runtime C code (where
 * there's no codegen release-on-reassignment emitted for the caller)
 * so orphaned blocks make it back to the size-class free list. */
static int list_append_internal(int ptr, long long val) {
    int newptr = list_append(ptr, val);
    if (newptr != ptr) safe_rc_release_internal(ptr);
    return newptr;
}

__attribute__((export_name("safe_list_contains")))
int safe_list_contains(int list, long long elem) {
    int len = list_len(list);
    for (int i = 0; i < len; i++)
        if (tagged_eq(list_get(list, i), elem)) return 1;
    return 0;
}

__attribute__((export_name("safe_set_union")))
int safe_set_union(int a, int b) {
    int alen = list_len(a), blen = list_len(b);
    int cap = alen + blen;
    SAFEHeader* ahdr = (SAFEHeader*)(a - 8);
    int result = safe_rc_alloc(8 + cap * 8, 9 /* SAFE_KIND_SET */, ahdr->meta);
    *(int*)result = 0; *(int*)(result + 4) = cap;
    // Copy all from a — retain each element so the result set owns its own
    // reference (list_append_internal's fast path does a raw store).
    for (int i = 0; i < alen; i++) {
        long long elem = list_get(a, i);
        safe_rc_retain_tagged(elem);
        result = list_append_internal(result, elem);
    }
    // Add from b if not already present
    for (int i = 0; i < blen; i++) {
        long long elem = list_get(b, i);
        if (!safe_list_contains(result, elem)) {
            safe_rc_retain_tagged(elem);
            result = list_append_internal(result, elem);
        }
    }
    return result;
}

__attribute__((export_name("safe_set_intersect")))
int safe_set_intersect(int a, int b) {
    int alen = list_len(a);
    SAFEHeader* ahdr = (SAFEHeader*)(a - 8);
    int result = safe_rc_alloc(8 + alen * 8, 9 /* SAFE_KIND_SET */, ahdr->meta);
    *(int*)result = 0; *(int*)(result + 4) = alen;
    for (int i = 0; i < alen; i++) {
        long long elem = list_get(a, i);
        if (safe_list_contains(b, elem)) {
            safe_rc_retain_tagged(elem);
            result = list_append_internal(result, elem);
        }
    }
    return result;
}

__attribute__((export_name("safe_set_difference")))
int safe_set_difference(int a, int b) {
    int alen = list_len(a);
    SAFEHeader* ahdr = (SAFEHeader*)(a - 8);
    int result = safe_rc_alloc(8 + alen * 8, 9 /* SAFE_KIND_SET */, ahdr->meta);
    *(int*)result = 0; *(int*)(result + 4) = alen;
    for (int i = 0; i < alen; i++) {
        long long elem = list_get(a, i);
        if (!safe_list_contains(b, elem)) {
            safe_rc_retain_tagged(elem);
            result = list_append_internal(result, elem);
        }
    }
    return result;
}

// ==================== Map builtins (open-addressing hash map) ====================
//
// Body layout (Phase A — WASM hash map):
//   [i32 count] [i32 cap] [i32 tombstones] [i32 reserved] [bucket[cap]]
//
// Bucket (24 bytes):
//   [i64 key_tagged] [i64 val_tagged] [i32 hash] [i32 state]
//   state: 0 = empty, 1 = occupied, 2 = tombstone
//
// Growth: cap starts at 8 and doubles when (count + tombstones) > cap*3/4.
// cap is always a power of two so probe masking uses a bitwise AND.
//
// Every occupied bucket holds owning references to its key and value;
// dispose_map walks occupied buckets and releases both.

/* Map layout constants — most are forward-declared at the top of the file
 * so dispose_map can reach them. Only the ones not needed by dispose are
 * declared here. */
#define SAFE_MAP_INITIAL_CAP  8
#define SAFE_MAP_STATE_EMPTY  0
#define SAFE_MAP_STATE_DELETE 2

static int map_body_bytes(int cap) {
    return SAFE_MAP_HEADER_BYTES + cap * SAFE_MAP_BUCKET_BYTES;
}

static int map_bucket_addr(int body, int index) {
    return body + SAFE_MAP_HEADER_BYTES + index * SAFE_MAP_BUCKET_BYTES;
}

static int map_count(int body)      { return *(int*)(body + SAFE_MAP_COUNT_OFF); }
static int map_cap(int body)        { return *(int*)(body + SAFE_MAP_CAP_OFF); }
static int map_tombstones(int body) { return *(int*)(body + SAFE_MAP_TOMB_OFF); }
static int map_head(int body)       { return *(int*)(body + SAFE_MAP_HEAD_OFF); }
static int map_tail(int body)       { return *(int*)(body + SAFE_MAP_TAIL_OFF); }

static void map_set_count(int body, int v)      { *(int*)(body + SAFE_MAP_COUNT_OFF) = v; }
static void map_set_cap(int body, int v)        { *(int*)(body + SAFE_MAP_CAP_OFF) = v; }
static void map_set_tombstones(int body, int v) { *(int*)(body + SAFE_MAP_TOMB_OFF) = v; }
static void map_set_head(int body, int v)       { *(int*)(body + SAFE_MAP_HEAD_OFF) = v; }
static void map_set_tail(int body, int v)       { *(int*)(body + SAFE_MAP_TAIL_OFF) = v; }

/* Insertion-order chain helpers: link a fresh bucket at the tail, unlink
 * a removed bucket from the chain. Indices of -1 are sentinel. */
static void map_link_tail(int body, int idx) {
    int tail = map_tail(body);
    int b = map_bucket_addr(body, idx);
    *(int*)(b + SAFE_MAP_PREV_OFF) = tail;
    *(int*)(b + SAFE_MAP_NEXT_OFF) = -1;
    if (tail == -1) {
        map_set_head(body, idx);
    } else {
        int tb = map_bucket_addr(body, tail);
        *(int*)(tb + SAFE_MAP_NEXT_OFF) = idx;
    }
    map_set_tail(body, idx);
}

static void map_unlink(int body, int idx) {
    int b = map_bucket_addr(body, idx);
    int prev = *(int*)(b + SAFE_MAP_PREV_OFF);
    int next = *(int*)(b + SAFE_MAP_NEXT_OFF);
    if (prev == -1) map_set_head(body, next);
    else *(int*)(map_bucket_addr(body, prev) + SAFE_MAP_NEXT_OFF) = next;
    if (next == -1) map_set_tail(body, prev);
    else *(int*)(map_bucket_addr(body, next) + SAFE_MAP_PREV_OFF) = prev;
}

/* FNV-1a over raw bytes (32-bit). Used by the map for STRING/BYTES keys. */
static unsigned int map_hash_bytes(const unsigned char* data, int len) {
    unsigned int h = 2166136261u;
    for (int i = 0; i < len; i++) {
        h ^= data[i];
        h *= 16777619u;
    }
    return h ? h : 1; /* avoid zero hash (distinguishes cleanly on some codepaths) */
}

/* Hash a tagged i64 map key. The value's tag drives the hash function so
 * keys of different representations still collide on semantic equality:
 *   STRING/BYTES: FNV-1a on the length-prefixed payload.
 *   INT/UINT/BOOL/FLOAT/VOID: mix the 60-bit payload bits.
 *   Other heap kinds (LIST/MAP/SET/TUPLE/OBJECT/ENUM/CLOSURE): hash by
 *     body pointer (keys of composite types use pointer identity). */
static unsigned int safe_hash_tagged(long long tagged) {
    int tag = (int)(tagged & 0xF);
    unsigned long long payload = ((unsigned long long)tagged) >> 4;
    if (tag == 3 /* STRING */ || tag == 12 /* BYTES */) {
        int body = (int)payload;
        if (body == 0) return 1;
        int len = *(int*)body;
        return map_hash_bytes((const unsigned char*)(body + 4), len);
    }
    /* Scalar / heap-pointer fallback: splitmix-style bit mix so low-bit
     * patterns in payloads spread across the hash range. */
    unsigned long long x = (unsigned long long)tagged;
    x ^= x >> 33;
    x *= 0xff51afd7ed558ccdULL;
    x ^= x >> 33;
    x *= 0xc4ceb9fe1a85ec53ULL;
    x ^= x >> 33;
    unsigned int h = (unsigned int)(x ^ (x >> 32));
    return h ? h : 1;
}

/* Probe the bucket array for `key`. Returns:
 *   state == FILLED : matching bucket index (key is present).
 *   state == EMPTY  : first empty or tombstone bucket (where insert goes).
 * `out_first_tombstone` holds the first tombstone hit so insertions reuse
 * tombstones before walking further.
 *
 * Caller guarantees cap > 0. Uses power-of-two mask. */
static int map_probe(int body, long long key, unsigned int key_hash,
                     int* out_state) {
    int cap = map_cap(body);
    unsigned int mask = (unsigned int)(cap - 1);
    unsigned int i = key_hash & mask;
    int first_tombstone = -1;
    /* We guarantee (count + tombstones) < cap before probing so the loop
     * terminates at an empty bucket. Cap the iteration at cap just in
     * case. */
    for (int steps = 0; steps < cap; steps++) {
        int b = map_bucket_addr(body, (int)i);
        int state = *(int*)(b + SAFE_MAP_STATE_OFF);
        if (state == SAFE_MAP_STATE_EMPTY) {
            *out_state = SAFE_MAP_STATE_EMPTY;
            return first_tombstone >= 0 ? first_tombstone : (int)i;
        }
        if (state == SAFE_MAP_STATE_DELETE) {
            if (first_tombstone < 0) first_tombstone = (int)i;
        } else { /* FILLED */
            unsigned int h = (unsigned int)*(int*)(b + SAFE_MAP_HASH_OFF);
            if (h == key_hash) {
                long long bucket_key = *(long long*)(b + SAFE_MAP_KEY_OFF);
                if (tagged_eq(bucket_key, key)) {
                    *out_state = SAFE_MAP_STATE_FILLED;
                    return (int)i;
                }
            }
        }
        i = (i + 1) & mask;
    }
    /* Table entirely full (shouldn't happen after a well-behaved grow). */
    *out_state = first_tombstone >= 0 ? SAFE_MAP_STATE_EMPTY : SAFE_MAP_STATE_FILLED;
    return first_tombstone >= 0 ? first_tombstone : 0;
}

static void map_init_buckets(int body, int cap) {
    map_set_count(body, 0);
    map_set_cap(body, cap);
    map_set_tombstones(body, 0);
    map_set_head(body, -1);
    map_set_tail(body, -1);
    *(int*)(body + 20) = 0; /* reserved */
    for (int i = 0; i < cap; i++) {
        int b = map_bucket_addr(body, i);
        *(long long*)(b + SAFE_MAP_KEY_OFF) = 0;
        *(long long*)(b + SAFE_MAP_VAL_OFF) = 0;
        *(int*)(b + SAFE_MAP_HASH_OFF) = 0;
        *(int*)(b + SAFE_MAP_STATE_OFF) = SAFE_MAP_STATE_EMPTY;
        *(int*)(b + SAFE_MAP_NEXT_OFF) = -1;
        *(int*)(b + SAFE_MAP_PREV_OFF) = -1;
    }
}

/* Rehash `map` into a fresh block of `new_cap` buckets. Walks the old
 * insertion-order chain so the new table ends up with the same order.
 * Transfers ownership (key/value pointers move, no retain/release). */
static int map_grow(int map, int new_cap) {
    int old = *(int*)map;
    int old_cap = map_cap(old);
    int new_body = safe_rc_alloc(map_body_bytes(new_cap), 2 /* SAFE_KIND_MAP */, 0);
    map_init_buckets(new_body, new_cap);
    /* Walk the old insertion-order chain to preserve ordering. */
    int idx = map_head(old);
    while (idx != -1) {
        int ob = map_bucket_addr(old, idx);
        int next = *(int*)(ob + SAFE_MAP_NEXT_OFF);
        long long key = *(long long*)(ob + SAFE_MAP_KEY_OFF);
        long long val = *(long long*)(ob + SAFE_MAP_VAL_OFF);
        unsigned int h = (unsigned int)*(int*)(ob + SAFE_MAP_HASH_OFF);
        int probe_state;
        int slot = map_probe(new_body, key, h, &probe_state);
        int nb = map_bucket_addr(new_body, slot);
        *(long long*)(nb + SAFE_MAP_KEY_OFF) = key;
        *(long long*)(nb + SAFE_MAP_VAL_OFF) = val;
        *(int*)(nb + SAFE_MAP_HASH_OFF) = (int)h;
        *(int*)(nb + SAFE_MAP_STATE_OFF) = SAFE_MAP_STATE_FILLED;
        map_set_count(new_body, map_count(new_body) + 1);
        map_link_tail(new_body, slot);
        /* Mark old bucket empty so dispose_map won't re-release. */
        *(int*)(ob + SAFE_MAP_STATE_OFF) = SAFE_MAP_STATE_EMPTY;
        idx = next;
    }
    /* Clear old header to prevent double-release during dispose. */
    (void)old_cap;
    map_set_count(old, 0);
    map_set_tombstones(old, 0);
    map_set_head(old, -1);
    map_set_tail(old, -1);
    safe_rc_release_internal(old);
    *(int*)map = new_body;
    return map;
}

/* Indirection: map body lives at *(int*)(map_ptr). map_ptr itself is a
 * small 8-byte header-prefixed block that holds (buckets_body_offset,
 * reserved). Keeping this level of indirection matches the old layout so
 * callers don't need to change how they carry a map pointer around. */

__attribute__((export_name("safe_map_new")))
int safe_map_new(void) {
    int ptr = safe_rc_alloc(8, 2 /* SAFE_KIND_MAP */, 0);
    int body = safe_rc_alloc(map_body_bytes(SAFE_MAP_INITIAL_CAP),
                             2 /* SAFE_KIND_MAP */, 0);
    map_init_buckets(body, SAFE_MAP_INITIAL_CAP);
    *(int*)ptr = body;
    *(int*)(ptr + 4) = 0; /* reserved */
    return ptr;
}

__attribute__((export_name("safe_map_put")))
int safe_map_put(int map, long long key, long long val) {
    int body = *(int*)map;
    MAP_TRACE_PUT(map, key, val);
    /* Grow trigger: (count + tombstones) * 4 >= cap * 3 → load factor 0.75. */
    int cap = map_cap(body);
    int total = map_count(body) + map_tombstones(body);
    if (total * 4 >= cap * 3) {
        map = map_grow(map, cap * 2);
        body = *(int*)map;
    }
    unsigned int h = safe_hash_tagged(key);
    int probe_state;
    int slot = map_probe(body, key, h, &probe_state);
    int b = map_bucket_addr(body, slot);
    if (probe_state == SAFE_MAP_STATE_FILLED) {
        /* Overwrite: retain new value, release old. */
        long long old = *(long long*)(b + SAFE_MAP_VAL_OFF);
        safe_rc_retain_tagged(val);
        safe_rc_release_tagged(old);
        *(long long*)(b + SAFE_MAP_VAL_OFF) = val;
        return map;
    }
    /* Insert (empty or tombstone slot). */
    safe_rc_retain_tagged(key);
    safe_rc_retain_tagged(val);
    int was_tombstone = (*(int*)(b + SAFE_MAP_STATE_OFF) == SAFE_MAP_STATE_DELETE);
    *(long long*)(b + SAFE_MAP_KEY_OFF) = key;
    *(long long*)(b + SAFE_MAP_VAL_OFF) = val;
    *(int*)(b + SAFE_MAP_HASH_OFF) = (int)h;
    *(int*)(b + SAFE_MAP_STATE_OFF) = SAFE_MAP_STATE_FILLED;
    map_set_count(body, map_count(body) + 1);
    if (was_tombstone) map_set_tombstones(body, map_tombstones(body) - 1);
    /* Append to insertion-order chain so iteration order matches the
     * interpreter's LinkedHashMap semantics. */
    map_link_tail(body, slot);
    return map;
}

__attribute__((export_name("safe_map_get")))
long long safe_map_get(int map, long long key) {
    int body = *(int*)map;
    if (map_count(body) == 0) {
        MAP_TRACE_GET(map, key, (long long)4);
        return (long long)4;
    }
    unsigned int h = safe_hash_tagged(key);
    int probe_state;
    int slot = map_probe(body, key, h, &probe_state);
    if (probe_state == SAFE_MAP_STATE_FILLED) {
        long long result = *(long long*)(map_bucket_addr(body, slot) + SAFE_MAP_VAL_OFF);
        /* Return owning reference so caller scope-release balances. */
        safe_rc_retain_tagged(result);
        MAP_TRACE_GET(map, key, result);
        return result;
    }
    MAP_TRACE_GET(map, key, (long long)4);
    return (long long)4; /* TAG_VOID */
}

__attribute__((export_name("safe_map_len")))
int safe_map_len(int map) {
    return map_count(*(int*)map);
}

/* Build a fresh list of keys / values in insertion order (matches the
 * LinkedHashMap-style semantics the interpreter / bytecode VM provide).
 * Each returned element is retained so the caller owns its reference. */
static int safe_map_build_list(int map, int want_values) {
    int body = *(int*)map;
    int count = map_count(body);
    int capacity = count < 4 ? 4 : count;
    int list = safe_rc_alloc(8 + capacity * 8, 1 /* SAFE_KIND_LIST */, 0);
    *(int*)list = 0;
    *(int*)(list + 4) = capacity;
    int idx = map_head(body);
    while (idx != -1) {
        int b = map_bucket_addr(body, idx);
        long long v = want_values ? *(long long*)(b + SAFE_MAP_VAL_OFF)
                                  : *(long long*)(b + SAFE_MAP_KEY_OFF);
        safe_rc_retain_tagged(v);
        list = list_append_internal(list, v);
        idx = *(int*)(b + SAFE_MAP_NEXT_OFF);
    }
    return list;
}

__attribute__((export_name("safe_map_keys")))
int safe_map_keys(int map) { return safe_map_build_list(map, 0); }

__attribute__((export_name("safe_map_vals")))
int safe_map_vals(int map) { return safe_map_build_list(map, 1); }

// ==================== Bytes patch ====================

__attribute__((export_name("safe_bpatch")))
int safe_bpatch(int bytes, int offset, int patch) {
    int blen = *(int*)bytes;
    int plen = *(int*)patch;
    int result = safe_rc_alloc(4 + blen, 3 /* SAFE_KIND_BYTES */, 0);
    *(int*)result = blen;
    for (int i = 0; i < blen; i++)
        *((unsigned char*)(result + 4 + i)) = *((unsigned char*)(bytes + 4 + i));
    for (int i = 0; i < plen && offset + i < blen; i++)
        *((unsigned char*)(result + 4 + offset + i)) = *((unsigned char*)(patch + 4 + i));
    return result;
}

// ==================== String-to-number conversions ====================
__attribute__((export_name("safe_str_to_int")))
long long safe_str_to_int(int ptr) {
    int len = *(int*)ptr;
    const unsigned char* s = (const unsigned char*)(ptr + 4);
    long long val = 0;
    int i = 0;
    int neg = 0;
    if (i < len && s[i] == '-') { neg = 1; i++; }
    for (; i < len; i++) {
        unsigned char c = s[i];
        if (c < '0' || c > '9') break;
        val = val * 10 + (c - '0');
    }
    return neg ? -val : val;
}

__attribute__((export_name("safe_str_to_float")))
double safe_str_to_float(int ptr) {
    int len = *(int*)ptr;
    const unsigned char* s = (const unsigned char*)(ptr + 4);
    double val = 0.0;
    int i = 0;
    int neg = 0;
    if (i < len && s[i] == '-') { neg = 1; i++; }
    for (; i < len && s[i] != '.' && s[i] != 'e' && s[i] != 'E'; i++)
        val = val * 10.0 + (s[i] - '0');
    if (i < len && s[i] == '.') {
        i++;
        double frac = 0.1;
        for (; i < len && s[i] != 'e' && s[i] != 'E'; i++) {
            val += (s[i] - '0') * frac;
            frac *= 0.1;
        }
    }
    if (i < len && (s[i] == 'e' || s[i] == 'E')) {
        i++;
        int eneg = 0;
        if (i < len && s[i] == '-') { eneg = 1; i++; }
        else if (i < len && s[i] == '+') i++;
        int exp = 0;
        for (; i < len; i++) exp = exp * 10 + (s[i] - '0');
        double mul = 1.0;
        for (int j = 0; j < exp; j++) mul *= 10.0;
        val = eneg ? val / mul : val * mul;
    }
    return neg ? -val : val;
}

__attribute__((export_name("safe_set_add")))
int safe_set_add(int set, long long elem) {
    if (safe_list_contains(set, elem)) return set;
    /* list_append stores the element raw on the fast path; retain so the
     * set owns its own reference. */
    safe_rc_retain_tagged(elem);
    return list_append(set, elem);
}

__attribute__((export_name("safe_list_unique")))
int safe_list_unique(int list) {
    int len = list_len(list);
    SAFEHeader* src = (SAFEHeader*)(list - 8);
    int result = safe_rc_alloc(8 + len * 8, 1 /* SAFE_KIND_LIST */, src->meta);
    *(int*)result = 0; *(int*)(result + 4) = len;
    for (int i = 0; i < len; i++) {
        long long elem = list_get(list, i);
        if (!safe_list_contains(result, elem)) {
            /* Retain so the unique'd list owns its own reference. */
            safe_rc_retain_tagged(elem);
            result = list_append_internal(result, elem);
        }
    }
    return result;
}

__attribute__((export_name("safe_list_sort")))
int safe_list_sort(int list) {
    int len = list_len(list);
    int cap = len < 4 ? 4 : len;
    SAFEHeader* src = (SAFEHeader*)(list - 8);
    int result = safe_rc_alloc(8 + cap * 8, 1 /* SAFE_KIND_LIST */, src->meta);
    *(int*)result = len;
    *(int*)(result + 4) = cap;
    for (int i = 0; i < len; i++) {
        long long elem = list_get(list, i);
        safe_rc_retain_tagged(elem);
        *(long long*)(result + 8 + i * 8) = elem;
    }
    for (int i = 1; i < len; i++) {
        long long key = *(long long*)(result + 8 + i * 8);
        int j = i - 1;
        while (j >= 0 && tagged_compare(*(long long*)(result + 8 + j * 8), key) > 0) {
            *(long long*)(result + 8 + (j + 1) * 8) = *(long long*)(result + 8 + j * 8);
            j--;
        }
        *(long long*)(result + 8 + (j + 1) * 8) = key;
    }
    return result;
}

__attribute__((export_name("safe_murmur")))
long long safe_murmur(int bytes) {
    int len = *(int*)bytes;
    unsigned char *data = (unsigned char*)(bytes + 4);
    unsigned int seed = 0;
    unsigned int c1 = 0xcc9e2d51, c2 = 0x1b873593;
    unsigned int h = seed;
    int nblocks = len / 4;
    for (int i = 0; i < nblocks; i++) {
        unsigned int k = ((unsigned int)data[i*4]) | ((unsigned int)data[i*4+1] << 8)
                       | ((unsigned int)data[i*4+2] << 16) | ((unsigned int)data[i*4+3] << 24);
        k *= c1; k = (k << 15) | (k >> 17); k *= c2;
        h ^= k; h = (h << 13) | (h >> 19); h = h * 5 + 0xe6546b64;
    }
    unsigned int tail = 0;
    const unsigned char *t = data + nblocks * 4;
    int rem = len & 3;
    if (rem == 3) tail ^= (unsigned int)t[2] << 16;
    if (rem >= 2) tail ^= (unsigned int)t[1] << 8;
    if (rem >= 1) { tail ^= t[0]; tail *= c1; tail = (tail << 15) | (tail >> 17); tail *= c2; h ^= tail; }
    h ^= (unsigned int)len;
    h ^= h >> 16; h *= 0x85ebca6b; h ^= h >> 13; h *= 0xc2b2ae35; h ^= h >> 16;
    return (long long)(unsigned long long)(unsigned int)h;
}

// ==================== Bytes builtins ====================
// Bytes layout: [i32 length][raw bytes...]

__attribute__((export_name("safe_balloc")))
int safe_balloc(int size) {
    int result = safe_rc_alloc(4 + size, 3 /* SAFE_KIND_BYTES */, 0);
    *(int*)result = size;
    for (int i = 0; i < size; i++) *((unsigned char*)(result + 4 + i)) = 0;
    return result;
}

__attribute__((export_name("safe_blen")))
int safe_blen(int ptr) { return *(int*)ptr; }

__attribute__((export_name("safe_bget")))
int safe_bget(int ptr, int idx) {
    return *((unsigned char*)(ptr + 4 + idx));
}

__attribute__((export_name("safe_bset")))
int safe_bset(int ptr, int idx, int val) {
    // Return new bytes with the value set (immutable semantics)
    int len = *(int*)ptr;
    int result = safe_rc_alloc(4 + len, 3 /* SAFE_KIND_BYTES */, 0);
    *(int*)result = len;
    for (int i = 0; i < len; i++) *((unsigned char*)(result + 4 + i)) = *((unsigned char*)(ptr + 4 + i));
    *((unsigned char*)(result + 4 + idx)) = (unsigned char)val;
    return result;
}

__attribute__((export_name("safe_bslice")))
int safe_bslice(int ptr, int start, int end) {
    int len = end - start;
    int result = safe_rc_alloc(4 + len, 3 /* SAFE_KIND_BYTES */, 0);
    *(int*)result = len;
    for (int i = 0; i < len; i++) *((unsigned char*)(result + 4 + i)) = *((unsigned char*)(ptr + 4 + start + i));
    return result;
}

__attribute__((export_name("safe_bconcat")))
int safe_bconcat(int a, int b) {
    int la = *(int*)a, lb = *(int*)b;
    int result = safe_rc_alloc(4 + la + lb, 3 /* SAFE_KIND_BYTES */, 0);
    *(int*)result = la + lb;
    for (int i = 0; i < la; i++) *((unsigned char*)(result + 4 + i)) = *((unsigned char*)(a + 4 + i));
    for (int i = 0; i < lb; i++) *((unsigned char*)(result + 4 + la + i)) = *((unsigned char*)(b + 4 + i));
    return result;
}

__attribute__((export_name("safe_bencode")))
int safe_bencode(int str) {
    // String to bytes (same layout, just copy)
    int len = *(int*)str;
    int result = safe_rc_alloc(4 + len, 3 /* SAFE_KIND_BYTES */, 0);
    *(int*)result = len;
    for (int i = 0; i < len; i++) *((unsigned char*)(result + 4 + i)) = *((unsigned char*)(str + 4 + i));
    return result;
}

__attribute__((export_name("safe_bdecode")))
int safe_bdecode(int bytes) {
    // Bytes to string. Result is a heap string (Phase 6): header-prefixed
    // so retain/release on STRING-tagged values work uniformly.
    int len = *(int*)bytes;
    int result = safe_rc_alloc(4 + len, 8 /* SAFE_KIND_STRING */, 0);
    *(int*)result = len;
    for (int i = 0; i < len; i++) *((unsigned char*)(result + 4 + i)) = *((unsigned char*)(bytes + 4 + i));
    return result;
}

__attribute__((export_name("safe_bpack")))
int safe_bpack(long long val, int width) {
    int result = safe_rc_alloc(4 + width, 3 /* SAFE_KIND_BYTES */, 0);
    *(int*)result = width;
    for (int i = 0; i < width; i++)
        *((unsigned char*)(result + 4 + i)) = (unsigned char)((val >> (i * 8)) & 0xFF);
    return result;
}

__attribute__((export_name("safe_bunpack")))
long long safe_bunpack(int bytes, int offset, int width) {
    long long val = 0;
    for (int i = 0; i < width; i++)
        val |= ((long long)(*((unsigned char*)(bytes + 4 + offset + i)))) << (i * 8);
    return val;
}

__attribute__((export_name("safe_bhex")))
int safe_bhex(int bytes) {
    int len = *(int*)bytes;
    int rlen = len * 2;
    int result = safe_rc_alloc(4 + rlen, 3 /* SAFE_KIND_BYTES */, 0);
    *(int*)result = rlen;
    const char hex[] = "0123456789abcdef";
    for (int i = 0; i < len; i++) {
        unsigned char b = *((unsigned char*)(bytes + 4 + i));
        *((unsigned char*)(result + 4 + i*2)) = hex[b >> 4];
        *((unsigned char*)(result + 4 + i*2 + 1)) = hex[b & 0xF];
    }
    return result;
}

__attribute__((export_name("safe_bcompare")))
int safe_bcompare(int a, int b) {
    int la = *(int*)a, lb = *(int*)b;
    int minlen = la < lb ? la : lb;
    for (int i = 0; i < minlen; i++) {
        int ca = *((unsigned char*)(a + 4 + i));
        int cb = *((unsigned char*)(b + 4 + i));
        if (ca < cb) return -1;
        if (ca > cb) return 1;
    }
    if (la < lb) return -1;
    if (la > lb) return 1;
    return 0;
}

// ==================== Random number generator ====================
// xorshift64 PRNG

static unsigned long long rng_state = 0x12345678DEADBEEF;

__attribute__((export_name("safe_seed")))
void safe_seed(long long s) { rng_state = (unsigned long long)s; if (rng_state == 0) rng_state = 1; }

static unsigned long long xorshift64(void) {
    unsigned long long x = rng_state;
    x ^= x << 13;
    x ^= x >> 7;
    x ^= x << 17;
    rng_state = x;
    return x;
}

__attribute__((export_name("safe_rand")))
double safe_rand(void) {
    return (double)(xorshift64() & 0x1FFFFFFFFFFFFF) / (double)0x20000000000000;
}

__attribute__((export_name("safe_randint")))
long long safe_randint(long long lo, long long hi) {
    if (hi <= lo) return lo;
    unsigned long long range = (unsigned long long)(hi - lo);
    return lo + (long long)(xorshift64() % range);
}

__attribute__((export_name("safe_time")))
long long safe_time(void) {
    // Return current time in milliseconds via WASI clock
    struct timespec ts;
    if (clock_gettime(0, &ts) != 0) return 0;
    return (long long)ts.tv_sec * 1000 + (long long)ts.tv_nsec / 1000000;
}

// ==================== Hash builtins ====================

__attribute__((export_name("safe_fnv")))
long long safe_fnv(int bytes) {
    int len = *(int*)bytes;
    unsigned long long hash = 0xcbf29ce484222325ULL;
    for (int i = 0; i < len; i++) {
        hash ^= *((unsigned char*)(bytes + 4 + i));
        hash *= 0x100000001b3ULL;
    }
    return (long long)hash;
}

// ==================== Regex engine ====================
// Simple backtracking NFA supporting: . \d \w \s * + ? ^ $ [chars]

static int re_is_digit(unsigned char c) { return c >= '0' && c <= '9'; }
static int re_is_word(unsigned char c) { return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_'; }
static int re_is_space(unsigned char c) { return c == ' ' || c == '\t' || c == '\n' || c == '\r'; }

// Match a character against a pattern atom starting at pat[pi]
// Returns bytes consumed by pattern, or -1 if no match
// Sets *pat_len to pattern bytes consumed
static int re_match_char(unsigned char c, const unsigned char *pat, int pi, int plen, int *pat_len) {
    if (pi >= plen) { *pat_len = 0; return 0; }
    if (pat[pi] == '\\' && pi + 1 < plen) {
        *pat_len = 2;
        unsigned char esc = pat[pi+1];
        if (esc == 'd') return re_is_digit(c);
        if (esc == 'D') return !re_is_digit(c);
        if (esc == 'w') return re_is_word(c);
        if (esc == 'W') return !re_is_word(c);
        if (esc == 's') return re_is_space(c);
        if (esc == 'S') return !re_is_space(c);
        return c == esc;
    }
    if (pat[pi] == '[') {
        // Character class
        int j = pi + 1, neg = 0;
        if (j < plen && pat[j] == '^') { neg = 1; j++; }
        int found = 0;
        while (j < plen && pat[j] != ']') {
            if (j+2 < plen && pat[j+1] == '-' && pat[j+2] != ']') {
                if (c >= pat[j] && c <= pat[j+2]) found = 1;
                j += 3;
            } else {
                if (c == pat[j]) found = 1;
                j++;
            }
        }
        *pat_len = j < plen ? j - pi + 1 : j - pi;
        return neg ? !found : found;
    }
    *pat_len = 1;
    if (pat[pi] == '.') return c != 0;
    return c == pat[pi];
}

// Recursive match: tries to match pat[pi..plen] against str[si..slen]
// Returns 1 if match from si, stores match end in *end
static int re_match(const unsigned char *str, int si, int slen,
                    const unsigned char *pat, int pi, int plen, int *end) {
    while (pi < plen) {
        if (pat[pi] == '$' && pi == plen - 1) { *end = si; return si == slen; }
        if (pat[pi] == '(') { pi++; continue; }  // skip groups (basic)
        if (pat[pi] == ')') { pi++; continue; }

        // Look ahead for quantifier
        int pat_len = 0;
        // Pre-scan pat_len for this atom
        if (pat[pi] == '\\' && pi+1 < plen) pat_len = 2;
        else if (pat[pi] == '[') {
            int j = pi+1;
            if (j < plen && pat[j] == '^') j++;
            while (j < plen && pat[j] != ']') j++;
            pat_len = j < plen ? j - pi + 1 : j - pi;
        } else pat_len = 1;

        int quant = 0; // 0=none 1=? 2=* 3=+
        int after = pi + pat_len;
        if (after < plen) {
            if (pat[after] == '?') { quant = 1; after++; }
            else if (pat[after] == '*') { quant = 2; after++; }
            else if (pat[after] == '+') { quant = 3; after++; }
        }

        if (quant == 0) {
            // Must match one
            if (si >= slen) return 0;
            int pl = 0;
            if (!re_match_char(str[si], pat, pi, plen, &pl)) return 0;
            si++; pi = after;
        } else if (quant == 1) {
            // Optional: try with and without
            int pl = 0;
            if (si < slen && re_match_char(str[si], pat, pi, plen, &pl)) {
                int e2;
                if (re_match(str, si+1, slen, pat, after, plen, &e2)) { *end = e2; return 1; }
            }
            pi = after; // try without
        } else if (quant == 2 || quant == 3) {
            // Greedy: match as many as possible, then try rest
            int count = 0, pl = 0;
            int starts[256]; starts[0] = si;
            while (si < slen && count < 255) {
                if (!re_match_char(str[si], pat, pi, plen, &pl)) break;
                si++; count++;
                starts[count] = si;
            }
            if (quant == 3 && count == 0) return 0; // + needs at least 1
            for (int k = count; k >= (quant == 3 ? 1 : 0); k--) {
                int e2;
                if (re_match(str, starts[k], slen, pat, after, plen, &e2)) { *end = e2; return 1; }
            }
            return 0;
        }
    }
    *end = si;
    return 1;
}

// Find first match of pat in str starting at si, return start and end
static int re_find(const unsigned char *str, int slen,
                   const unsigned char *pat, int plen,
                   int start_si, int *match_end) {
    int anchored = plen > 0 && pat[0] == '^';
    int pi = anchored ? 1 : 0;
    int from = start_si;
    int limit = anchored ? (from + 1) : slen + 1;
    for (int si = from; si < limit; si++) {
        int end;
        if (re_match(str, si, slen, pat, pi, plen, &end)) {
            *match_end = end;
            return si;
        }
    }
    return -1;
}

static const unsigned char *str_data(int ptr) { return (const unsigned char*)(ptr + 4); }
static int str_len(int ptr) { return *(int*)ptr; }

__attribute__((export_name("safe_regex_matches")))
int safe_regex_matches(int str_ptr, int pat_ptr) {
    int sl = str_len(str_ptr), pl = str_len(pat_ptr);
    const unsigned char *s = str_data(str_ptr);
    const unsigned char *p = str_data(pat_ptr);
    // For full match without anchors, try to find any match in string
    int anchored_start = pl > 0 && p[0] == '^';
    int anchored_end   = pl > 0 && p[pl-1] == '$';
    if (anchored_start && anchored_end) {
        // Full match
        int end;
        return re_match(s, 0, sl, p, 1, pl - 1, &end) && end == sl;
    }
    // Otherwise: find any match in string
    int end;
    int start = re_find(s, sl, p, pl, 0, &end);
    return start >= 0;
}

__attribute__((export_name("safe_regex_findall")))
int safe_regex_findall(int str_ptr, int pat_ptr) {
    int sl = str_len(str_ptr), pl = str_len(pat_ptr);
    const unsigned char *s = str_data(str_ptr);
    const unsigned char *p = str_data(pat_ptr);
    // Allocate result list (SAFE_KIND_LIST so list_append_internal can grow it).
    int result = safe_rc_alloc(8 + 16 * 8, 1 /* SAFE_KIND_LIST */, 0);
    *(int*)result = 0; *(int*)(result + 4) = 16;
    int si = 0;
    int max_iters = sl + 1;
    while (si <= sl && max_iters-- > 0) {
        int end;
        int start = re_find(s, sl, p, pl, si, &end);
        if (start < 0) break;
        // Extract match substring
        int mlen = end - start;
        int sub = safe_rc_alloc(4 + mlen + 1, 8 /* SAFE_KIND_STRING */, 0);
        *(int*)sub = mlen;
        for (int i = 0; i < mlen; i++) *((unsigned char*)(sub + 4 + i)) = s[start + i];
        // Tag as string and append to result
        long long tagged = ((long long)(unsigned int)sub << 4) | 3; // TAG_STRING=3
        result = list_append_internal(result, tagged);
        si = end > start ? end : end + 1; // advance past match
    }
    return result;
}

__attribute__((export_name("safe_regex_replaceall")))
int safe_regex_replaceall(int str_ptr, int pat_ptr, int repl_ptr) {
    int sl = str_len(str_ptr), pl = str_len(pat_ptr), rl = str_len(repl_ptr);
    const unsigned char *s = str_data(str_ptr);
    const unsigned char *p = str_data(pat_ptr);
    const unsigned char *r = str_data(repl_ptr);
    // Build result by scanning for matches
    int out_cap = sl * 2 + 16;
    int out = safe_rc_alloc(4 + out_cap, 8 /* SAFE_KIND_STRING */, 0);
    *(int*)out = 0;
    int si = 0, out_len = 0;
    while (si <= sl) {
        int end;
        int start = re_find(s, sl, p, pl, si, &end);
        if (start < 0) {
            // Copy rest of string
            for (int i = si; i < sl; i++) {
                if (out_len + 1 > out_cap) { out_cap *= 2; int n = safe_rc_alloc(4 + out_cap, 8 /* SAFE_KIND_STRING */, 0); *(int*)n = out_len; for (int k = 0; k < out_len; k++) *((unsigned char*)(n+4+k)) = *((unsigned char*)(out+4+k)); safe_rc_release_internal(out); out = n; }
                *((unsigned char*)(out + 4 + out_len++)) = s[i];
            }
            break;
        }
        // Copy chars before match
        for (int i = si; i < start; i++) {
            if (out_len + 1 > out_cap) { out_cap *= 2; int n = safe_rc_alloc(4 + out_cap, 8 /* SAFE_KIND_STRING */, 0); *(int*)n = out_len; for (int k = 0; k < out_len; k++) *((unsigned char*)(n+4+k)) = *((unsigned char*)(out+4+k)); safe_rc_release_internal(out); out = n; }
            *((unsigned char*)(out + 4 + out_len++)) = s[i];
        }
        // Copy replacement
        for (int i = 0; i < rl; i++) {
            if (out_len + 1 > out_cap) { out_cap *= 2; int n = safe_rc_alloc(4 + out_cap, 8 /* SAFE_KIND_STRING */, 0); *(int*)n = out_len; for (int k = 0; k < out_len; k++) *((unsigned char*)(n+4+k)) = *((unsigned char*)(out+4+k)); safe_rc_release_internal(out); out = n; }
            *((unsigned char*)(out + 4 + out_len++)) = r[i];
        }
        si = end > start ? end : end + 1;
    }
    *(int*)out = out_len;
    return out;
}

__attribute__((export_name("safe_crc32")))
long long safe_crc32(int bytes) {
    int len = *(int*)bytes;
    unsigned int crc = 0xFFFFFFFF;
    for (int i = 0; i < len; i++) {
        crc ^= *((unsigned char*)(bytes + 4 + i));
        for (int j = 0; j < 8; j++)
            crc = (crc >> 1) ^ (0xEDB88320 & (-(crc & 1)));
    }
    return (long long)(crc ^ 0xFFFFFFFF);
}

// ==================== WASI direct file I/O ====================
// wasi/api.h is already included above; do NOT re-declare __wasi_* functions.
// The local struct types below have the same binary layout as __wasi_ciovec_t /
// __wasi_iovec_t in wasm32 (all fields are 32-bit), so pointer casts are safe.
// Suppress incompatible-pointer-types warnings for the whole file I/O block.
#pragma clang diagnostic push
#pragma clang diagnostic ignored "-Wincompatible-pointer-types"

static int cstr_to_safe(const char* s, int len); // forward declaration

// Local iovec: {int buf, int buf_len} — same ABI as __wasi_ciovec_t/__wasi_iovec_t in wasm32.
typedef struct { int buf; int buf_len; } wasi_iovec_t;

#define WASI_WHENCE_SET __WASI_WHENCE_SET
#define WASI_WHENCE_CUR __WASI_WHENCE_CUR
#define WASI_WHENCE_END __WASI_WHENCE_END
// WASI oflags: CREAT=0x1, DIRECTORY=0x2, EXCL=0x4, TRUNC=0x8 (matches wasip1.h)
#define WASI_O_CREAT  __WASI_OFLAGS_CREAT   // 0x1
#define WASI_O_TRUNC  __WASI_OFLAGS_TRUNC   // 0x8
// All 30 defined WASI Preview1 rights (bits 0-29); bits 30+ are invalid in wasmtime.
#define WASI_RIGHTS_ALL 0x3FFFFFFFULL

static void wasi_close_ignore(int fd) {
    __wasi_errno_t status = __wasi_fd_close((__wasi_fd_t)fd);
    (void)status;
}

static int wasi_read_some(int fd, int buf_ptr, int len, int* out_read) {
    wasi_iovec_t iov;
    iov.buf = buf_ptr;
    iov.buf_len = len;
    __wasi_size_t nread = 0;
    __wasi_errno_t status = __wasi_fd_read((__wasi_fd_t)fd, &iov, 1, &nread);
    *out_read = (int)nread;
    return status == 0 ? 0 : -1;
}

static int wasi_write_all(int fd, int buf_ptr, int len) {
    int offset = 0;
    while (offset < len) {
        wasi_iovec_t iov;
        iov.buf = buf_ptr + offset;
        iov.buf_len = len - offset;
        __wasi_size_t nwritten = 0;
        __wasi_errno_t status = __wasi_fd_write((__wasi_fd_t)fd, &iov, 1, &nwritten);
        if (status != 0 || nwritten == 0) return -1;
        offset += (int)nwritten;
    }
    return 0;
}

static int wasi_seek_to(int fd, long long offset, __wasi_whence_t whence, __wasi_filesize_t* new_offset) {
    __wasi_errno_t status = __wasi_fd_seek((__wasi_fd_t)fd, offset, whence, new_offset);
    return status == 0 ? 0 : -1;
}

__attribute__((export_name("safe_args")))
int safe_args(void) {
    int argc = 0;
    int buf_size = 0;
    if (__wasi_args_sizes_get(&argc, &buf_size) != 0 || argc <= 1 || buf_size <= 0) {
        return safe_list_new(0);
    }

    int argv_ptrs = bump_alloc((int)(argc * (int)sizeof(uint8_t*)));
    int argv_buf = bump_alloc(buf_size);
    if (__wasi_args_get((uint8_t**)(long)argv_ptrs, (uint8_t*)(long)argv_buf) != 0) {
        return safe_list_new(0);
    }

    int list = safe_list_new(argc - 1);
    for (int i = 1; i < argc; i++) {
        const char* arg = (const char*)((uint8_t**)(long)argv_ptrs)[i];
        int len = 0;
        while (arg[len]) len++;
        long long tagged = ((long long)cstr_to_safe(arg, len) << 4) | 3;
        list = list_append_internal(list, tagged);
    }
    return list;
}

__attribute__((export_name("safe_input")))
int safe_input(int prompt_ptr) {
    if (prompt_ptr) {
        int prompt_len = *(int*)prompt_ptr;
        if (prompt_len > 0) {
            (void)wasi_write_all(1, prompt_ptr + 4, prompt_len);
        }
    }

    char buffer[4096];
    int len = 0;
    while (len < (int)sizeof(buffer) - 1) {
        int n = 0;
        if (wasi_read_some(0, (int)(long)&buffer[len], 1, &n) != 0 || n <= 0) {
            break;
        }
        if (buffer[len] == '\n') {
            break;
        }
        if (buffer[len] != '\r') {
            len++;
        }
    }
    buffer[len] = '\0';
    return cstr_to_safe(buffer, len);
}

// Helper: find the preopened dir fd and relative path for a given absolute path.
// Returns dirfd >= 0 on success; fills rel_path_buf and *rel_len.
static int find_preopen(const char* abs_path, int abs_len, char* rel_path_buf, int* rel_len) {
    uint8_t name_buf[512];
    for (int fd = 3; fd < 32; fd++) {
        __wasi_prestat_t ps;
        if (__wasi_fd_prestat_get((__wasi_fd_t)fd, &ps) != 0) break;
        int nlen = (int)ps.u.dir.pr_name_len;
        if (nlen <= 0 || nlen >= 512) continue;
        if (__wasi_fd_prestat_dir_name((__wasi_fd_t)fd, name_buf, (size_t)nlen) != 0) continue;
        name_buf[nlen] = '\0';
        int match_len = nlen;
        if (nlen > 0 && name_buf[nlen-1] == (uint8_t)'/') match_len = nlen - 1;
        if (match_len > abs_len) continue;
        int ok = 1;
        for (int i = 0; i < match_len; i++) {
            if ((uint8_t)abs_path[i] != name_buf[i]) { ok = 0; break; }
        }
        if (!ok) continue;
        if (abs_len > match_len && abs_path[match_len] != '/') continue;
        int skip = match_len;
        if (skip < abs_len && abs_path[skip] == '/') skip++;
        *rel_len = abs_len - skip;
        for (int i = 0; i < *rel_len; i++) rel_path_buf[i] = abs_path[skip + i];
        rel_path_buf[*rel_len] = '\0'; // null-terminate for __wasi_path_open
        return fd;
    }
    return -1;
}

// Helper: write file using direct WASI calls
static void wasi_write_file(const char* path, int path_len, const char* data, int data_len) {
    char rel[512]; int rlen;
    int dirfd = find_preopen(path, path_len, rel, &rlen);
    if (dirfd < 0) return;
    int file_fd = -1;
    if (__wasi_path_open(dirfd, 0, rel, WASI_O_CREAT | WASI_O_TRUNC,
                         WASI_RIGHTS_ALL, WASI_RIGHTS_ALL, 0, &file_fd) != 0) return;
    if (data_len > 0 && wasi_write_all(file_fd, (int)(long)data, data_len) != 0) {
        wasi_close_ignore(file_fd);
        return;
    }
    wasi_close_ignore(file_fd);
}

// Helper: read file using direct WASI calls; returns SAFE string ptr
static int wasi_read_file(const char* path, int path_len) {
    char rel[512]; int rlen;
    int dirfd = find_preopen(path, path_len, rel, &rlen);
    if (dirfd < 0) return cstr_to_safe("", 0);
    int file_fd = -1;
    if (__wasi_path_open(dirfd, 0, rel, 0,
                         WASI_RIGHTS_ALL, WASI_RIGHTS_ALL, 0, &file_fd) != 0) return cstr_to_safe("", 0);
    // read contents (heap string, intermediate blocks released on grow)
    int out_ptr = safe_rc_alloc(4, 8 /* SAFE_KIND_STRING */, 0);
    *(int*)out_ptr = 0; int out_len = 0;
    while (1) {
        unsigned char buf[4096];
        int n = 0;
        if (wasi_read_some(file_fd, (int)(long)buf, (int)sizeof(buf), &n) != 0 || n <= 0) break;
        int new_out = safe_rc_alloc(4 + out_len + n, 8 /* SAFE_KIND_STRING */, 0);
        *(int*)new_out = out_len + n;
        for (int i = 0; i < out_len; i++) *((unsigned char*)(new_out+4+i)) = *((unsigned char*)(out_ptr+4+i));
        for (int i = 0; i < n; i++) *((unsigned char*)(new_out+4+out_len+i)) = buf[i];
        safe_rc_release_internal(out_ptr);
        out_ptr = new_out; out_len += n;
    }
    wasi_close_ignore(file_fd);
    return out_ptr;
}

// Helper: check if file exists
static int wasi_file_exists(const char* path, int path_len) {
    char rel[512]; int rlen;
    int dirfd = find_preopen(path, path_len, rel, &rlen);
    if (dirfd < 0) return 0;
    __wasi_filestat_t stat;
    return __wasi_path_filestat_get(dirfd, 0, rel, &stat) == 0 ? 1 : 0;
}

// Helper: delete file
static int wasi_unlink_file(const char* path, int path_len) {
    char rel[512]; int rlen;
    int dirfd = find_preopen(path, path_len, rel, &rlen);
    if (dirfd < 0) return 0;
    return __wasi_path_unlink_file(dirfd, rel) == 0 ? 1 : 0;
}

// ==================== File I/O ====================
// File handle table: indices 0-31, stores raw WASI fds

#define MAX_HANDLES 32
static int file_fds[MAX_HANDLES];

// Helper: convert SAFE string (len-prefixed) to null-terminated C string in bump alloc
static char* safe_to_cstr(int ptr) {
    int len = *(int*)ptr;
    char* buf = (char*)(long)bump_alloc(len + 1);
    for (int i = 0; i < len; i++) buf[i] = *((char*)(ptr + 4 + i));
    buf[len] = '\0';
    return buf;
}

// Helper: allocate SAFE string from C buffer (heap string).
static int cstr_to_safe(const char* s, int len) {
    int result = safe_rc_alloc(4 + len, 8 /* SAFE_KIND_STRING */, 0);
    *(int*)result = len;
    for (int i = 0; i < len; i++) *((unsigned char*)(result + 4 + i)) = (unsigned char)s[i];
    return result;
}

// Allocate a handle slot; returns index or -1
static int alloc_handle(int fd) {
    for (int i = 0; i < MAX_HANDLES; i++) {
        if (file_fds[i] == 0) { file_fds[i] = fd + 1; return i; } // store fd+1 so 0=empty
    }
    return -1;
}
static int get_fd(int h) {
    if (h < 0 || h >= MAX_HANDLES || file_fds[h] == 0) return -1;
    return file_fds[h] - 1;
}

__attribute__((export_name("safe_fileload")))
int safe_fileload(int path_ptr) {
    int path_len = *(int*)path_ptr;
    char* path = safe_to_cstr(path_ptr);
    return wasi_read_file(path, path_len);
}

__attribute__((export_name("safe_filesave")))
void safe_filesave(int path_ptr, int content_ptr) {
    int path_len = *(int*)path_ptr;
    char* path = safe_to_cstr(path_ptr);
    int len = *(int*)content_ptr;
    wasi_write_file(path, path_len, (char*)(content_ptr + 4), len);
}

__attribute__((export_name("safe_fileexists")))
int safe_fileexists(int path_ptr) {
    int path_len = *(int*)path_ptr;
    char* path = safe_to_cstr(path_ptr);
    return wasi_file_exists(path, path_len);
}

__attribute__((export_name("safe_filedelete")))
int safe_filedelete(int path_ptr) {
    int path_len = *(int*)path_ptr;
    char* path = safe_to_cstr(path_ptr);
    return wasi_unlink_file(path, path_len);
}

__attribute__((export_name("safe_mkdir")))
int safe_mkdir_fn(int path_ptr) {
    int path_len = *(int*)path_ptr;
    char* path = safe_to_cstr(path_ptr);
    char rel[512]; int rlen;
    int dirfd = find_preopen(path, path_len, rel, &rlen);
    if (dirfd < 0) return 0;
    return __wasi_path_create_directory((__wasi_fd_t)dirfd, rel) == 0 ? 1 : 0;
}

__attribute__((export_name("safe_rmdir")))
int safe_rmdir_fn(int path_ptr) {
    int path_len = *(int*)path_ptr;
    char* path = safe_to_cstr(path_ptr);
    char rel[512]; int rlen;
    int dirfd = find_preopen(path, path_len, rel, &rlen);
    if (dirfd < 0) return 0;
    return __wasi_path_remove_directory((__wasi_fd_t)dirfd, rel) == 0 ? 1 : 0;
}

__attribute__((export_name("safe_isdir")))
int safe_isdir(int path_ptr) {
    char* path = safe_to_cstr(path_ptr);
    struct stat st;
    if (stat(path, &st) != 0) return 0;
    return S_ISDIR(st.st_mode) ? 1 : 0;
}

__attribute__((export_name("safe_listdir")))
int safe_listdir(int path_ptr) {
    // Return empty list (opendir requires malloc)
    (void)path_ptr;
    int result = safe_rc_alloc(8, 1 /* SAFE_KIND_LIST */, 0);
    *(int*)result = 0; *(int*)(result+4) = 0;
    return result;
}

// WASI-based do_slurp using fd_read. Returns a heap STRING; intermediate
// blocks are released so the size-class free list can recycle them.
static int wasi_do_slurp(int fd) {
    unsigned char buf[4096];
    int out_ptr = safe_rc_alloc(4, 8 /* SAFE_KIND_STRING */, 0);
    *(int*)out_ptr = 0;
    int out_len = 0;
    while (1) {
        int n = 0;
        if (wasi_read_some(fd, (int)(long)buf, (int)sizeof(buf), &n) != 0 || n <= 0) break;
        int new_out = safe_rc_alloc(4 + out_len + n, 8 /* SAFE_KIND_STRING */, 0);
        *(int*)new_out = out_len + n;
        for (int i = 0; i < out_len; i++)
            *((unsigned char*)(new_out + 4 + i)) = *((unsigned char*)(out_ptr + 4 + i));
        for (int i = 0; i < n; i++)
            *((unsigned char*)(new_out + 4 + out_len + i)) = buf[i];
        safe_rc_release_internal(out_ptr);
        out_ptr = new_out;
        out_len += n;
    }
    return out_ptr;
}

// Text file handle ops
__attribute__((export_name("safe_fileopen")))
int safe_fileopen(int path_ptr, int mode_ptr) {
    int path_len = *(int*)path_ptr;
    char* path = safe_to_cstr(path_ptr);
    int mlen = *(int*)mode_ptr;
    char mode_c = mlen > 0 ? *((char*)(mode_ptr + 4)) : 'r';
    int oflags, rights;
    if (mode_c == 'w') { oflags = 0x1 | 0x8; rights = 0x40; } // CREAT|TRUNC, FD_WRITE
    else if (mode_c == 'a') { oflags = 0x1; rights = 0x40 | 0x4; } // CREAT, FD_WRITE|FD_SEEK
    else { oflags = 0; rights = 0x2; } // FD_READ
    char rel[512]; int rlen;
    int dirfd = find_preopen(path, path_len, rel, &rlen);
    if (dirfd < 0) return -1;
    int file_fd = -1;
    int rc = __wasi_path_open(dirfd, 0, rel, oflags, (long long)rights, 0, 0, &file_fd);
    if (rc != 0 || file_fd < 0) return -1;
    int h = alloc_handle(file_fd);
    if (h < 0) { wasi_close_ignore(file_fd); return -1; }
    return h;
}

__attribute__((export_name("safe_fileclose")))
void safe_fileclose(int h) {
    int fd = get_fd(h);
    if (fd >= 0) { wasi_close_ignore(fd); file_fds[h] = 0; }
}

__attribute__((export_name("safe_fileread")))
int safe_fileread(int h) {
    int fd = get_fd(h);
    if (fd < 0) return cstr_to_safe("", 0);
    return wasi_do_slurp(fd);
}

__attribute__((export_name("safe_filewrite")))
int safe_filewrite(int h, int content_ptr) {
    int fd = get_fd(h);
    if (fd < 0) return -1;
    int len = *(int*)content_ptr;
    if (len > 0 && wasi_write_all(fd, content_ptr + 4, len) != 0) return -1;
    return 0;
}

__attribute__((export_name("safe_filereadlines")))
int safe_filereadlines(int h) {
    int result = safe_rc_alloc(8, 1 /* SAFE_KIND_LIST */, 0);
    *(int*)result = 0; *(int*)(result+4) = 0;
    int fd = get_fd(h);
    if (fd < 0) return result;
    unsigned char line[4096];
    int line_len = 0;
    unsigned char c;
    while (1) {
        int n = 0;
        if (wasi_read_some(fd, (int)(long)&c, 1, &n) != 0 || n <= 0) break;
        if (c == '\n') {
            if (line_len > 0 && line[line_len-1] == '\r') line_len--;
            long long tagged = ((long long)(long)cstr_to_safe((char*)line, line_len) << 4) | 3;
            result = list_append_internal(result, tagged);
            line_len = 0;
        } else if (line_len < (int)sizeof(line) - 1) {
            line[line_len++] = c;
        }
    }
    if (line_len > 0) {
        long long tagged = ((long long)(long)cstr_to_safe((char*)line, line_len) << 4) | 3;
        result = list_append_internal(result, tagged);
    }
    return result;
}

// Binary file handle ops (shared handle table)
// Note: all binary modes use O_RDWR to allow both read and write (matches Java RandomAccessFile "rw")
__attribute__((export_name("safe_bopen")))
int safe_bopen(int path_ptr, int mode_ptr) {
    int path_len = *(int*)path_ptr;
    char* path = safe_to_cstr(path_ptr);
    int mlen = *(int*)mode_ptr;
    char mode_c = mlen > 0 ? *((char*)(mode_ptr + 4)) : 'r';
    int oflags; long long rights = 0x2 | 0x40; // FD_READ | FD_WRITE
    if (mode_c == 'w') oflags = 0x1 | 0x8; // CREAT | TRUNC
    else if (mode_c == 'a') oflags = 0x1;   // CREAT
    else oflags = 0;                          // read-write
    char rel[512]; int rlen;
    int dirfd = find_preopen(path, path_len, rel, &rlen);
    if (dirfd < 0) return -1;
    int file_fd = -1;
    int rc = __wasi_path_open(dirfd, 0, rel, oflags, rights, 0, 0, &file_fd);
    if (rc != 0 || file_fd < 0) return -1;
    int h = alloc_handle(file_fd);
    if (h < 0) { wasi_close_ignore(file_fd); return -1; }
    return h;
}

__attribute__((export_name("safe_bclose")))
void safe_bclose(int h) {
    safe_fileclose(h);
}

__attribute__((export_name("safe_bread")))
int safe_bread(int h, int n_bytes) {
    int result = safe_rc_alloc(4 + n_bytes, 3 /* SAFE_KIND_BYTES */, 0);
    *(int*)result = 0;
    int fd = get_fd(h);
    if (fd < 0) return result;
    int n = 0;
    if (wasi_read_some(fd, result + 4, n_bytes, &n) != 0) n = 0;
    if (n < 0) n = 0;
    *(int*)result = n;
    return result;
}

__attribute__((export_name("safe_bwrite")))
void safe_bwrite(int h, int bytes_ptr) {
    int fd = get_fd(h);
    if (fd < 0) return;
    int len = *(int*)bytes_ptr;
    if (len > 0) (void)wasi_write_all(fd, bytes_ptr + 4, len);
}

__attribute__((export_name("safe_bseek")))
void safe_bseek(int h, long long offset) {
    int fd = get_fd(h);
    if (fd < 0) return;
    __wasi_filesize_t newoff = 0;
    (void)wasi_seek_to(fd, offset, WASI_WHENCE_SET, &newoff);
}

__attribute__((export_name("safe_bsize")))
long long safe_bsize(int path_ptr) {
    int path_len = *(int*)path_ptr;
    char* path = safe_to_cstr(path_ptr);
    char rel[512];
    int rel_len = 0;
    int dirfd = find_preopen(path, path_len, rel, &rel_len);
    if (dirfd < 0) return 0;

    int file_fd = -1;
    if (__wasi_path_open(dirfd, 0, rel, 0, WASI_RIGHTS_ALL, 0, 0, &file_fd) != 0 || file_fd < 0) {
        return 0;
    }

    __wasi_filesize_t size = 0;
    if (wasi_seek_to(file_fd, 0, WASI_WHENCE_END, &size) != 0) {
        wasi_close_ignore(file_fd);
        return 0;
    }
    wasi_close_ignore(file_fd);
    return (long long)size;
}

__attribute__((export_name("safe_bflush")))
void safe_bflush(int h) {
    // POSIX write is unbuffered; no-op
    (void)h;
}

__attribute__((export_name("safe_filevalid")))
int safe_filevalid(int h) {
    return (h >= 0 && h < MAX_HANDLES && file_fds[h] != 0) ? 1 : 0;
}

// File append (O_APPEND mode)
__attribute__((export_name("safe_fileappend")))
void safe_fileappend(int path_ptr, int content_ptr) {
    int path_len = *(int*)path_ptr;
    char* path = safe_to_cstr(path_ptr);
    int len = *(int*)content_ptr;
    char rel[512]; int rlen;
    int dirfd = find_preopen(path, path_len, rel, &rlen);
    if (dirfd < 0) return;
    int file_fd = -1;
    // OFLAGS_CREAT=0x1, RIGHTS_FD_WRITE=0x40, RIGHTS_FD_SEEK=0x4, FDFLAGS_APPEND=0x1
    int rc = __wasi_path_open(dirfd, 0, rel, 0x1, 0x44, 0, 0x1, &file_fd);
    if (rc != 0 || file_fd < 0) return;
    if (len > 0 && wasi_write_all(file_fd, content_ptr + 4, len) != 0) {
        wasi_close_ignore(file_fd);
        return;
    }
    wasi_close_ignore(file_fd);
}

// ==================== Environment variables ====================
// Uses wasi/api.h __wasi_environ_sizes_get and __wasi_environ_get (already included).
// We scan the environ buffer for "NAME=VALUE\0" entries.

// Static environ cache (populated on first call)
#define ENV_MAX_VARS 256
#define ENV_BUF_SIZE 65536
static int env_initialized = 0;
static uint8_t env_buf[ENV_BUF_SIZE];
static uint8_t* env_vars[ENV_MAX_VARS];
static int env_count = 0;

static void init_env(void) {
    if (env_initialized) return;
    env_initialized = 1;
    int count = 0, buf_size = 0;
    if (__wasi_environ_sizes_get(&count, &buf_size) != 0) return;
    if (count > ENV_MAX_VARS) count = ENV_MAX_VARS;
    if (buf_size > ENV_BUF_SIZE) buf_size = ENV_BUF_SIZE;
    if (__wasi_environ_get(env_vars, env_buf) != 0) return;
    env_count = count;
}

__attribute__((export_name("safe_getenv")))
int safe_getenv(int name_ptr) {
    init_env();
    int nlen = *(int*)name_ptr;
    const char* name = (const char*)(name_ptr + 4);
    for (int i = 0; i < env_count; i++) {
        const char* entry = (const char*)env_vars[i];
        // Check if entry starts with name + '='
        int j = 0;
        while (j < nlen && entry[j] == name[j]) j++;
        if (j == nlen && entry[j] == '=') {
            // Found: return value string
            const char* val = entry + j + 1;
            int vlen = 0;
            while (val[vlen]) vlen++;
            return cstr_to_safe(val, vlen);
        }
    }
    // Not found: return empty string
    return cstr_to_safe("", 0);
}

#pragma clang diagnostic pop   // end -Wincompatible-pointer-types suppression (file I/O + environ)

// ==================== SAFE value tag constants ====================
#define SAFE_TAG_BITS 4
#define SAFE_TAG_INT   0
#define SAFE_TAG_FLOAT 1
#define SAFE_TAG_BOOL  2
#define SAFE_TAG_STR   3
#define SAFE_TAG_VOID  4
#define SAFE_TAG_LIST  5
#define SAFE_TAG_MAP   6
#define SAFE_TAG_SET   7
#define SAFE_TAG_TUPLE 8
#define SAFE_TAG_ENUM  9
#define SAFE_TAG_OBJ  10
#define SAFE_TAG_CLOS 11
#define SAFE_TAG_BYTES 12
#define SAFE_TAG_UINT 13

// ==================== String runtime ====================
// String layout: [i32 len][utf-8 bytes...]

// Create a heap string from a C string literal (no null terminator in result)
static int safe_str_cstr(const char* s, int len) {
    int r = safe_rc_alloc(4 + len, 8 /* SAFE_KIND_STRING */, 0);
    *(int*)r = len;
    for (int i = 0; i < len; i++) ((unsigned char*)(r+4))[i] = (unsigned char)s[i];
    return r;
}

__attribute__((export_name("safe_str_len")))
int safe_str_len(int ptr) { return *(int*)ptr; }

__attribute__((export_name("safe_str_eq")))
int safe_str_eq(int a, int b) {
    if (a == b) return 1;
    int la = *(int*)a, lb = *(int*)b;
    if (la != lb) return 0;
    unsigned char* pa = (unsigned char*)(a+4);
    unsigned char* pb = (unsigned char*)(b+4);
    for (int i = 0; i < la; i++) if (pa[i] != pb[i]) return 0;
    return 1;
}

__attribute__((export_name("safe_str_concat")))
int safe_str_concat(int a, int b) {
    int la = *(int*)a, lb = *(int*)b;
    int r = safe_rc_alloc(4 + la + lb, 8 /* SAFE_KIND_STRING */, 0);
    *(int*)r = la + lb;
    unsigned char* dst = (unsigned char*)(r+4);
    unsigned char* sa  = (unsigned char*)(a+4);
    unsigned char* sb  = (unsigned char*)(b+4);
    for (int i = 0; i < la; i++) dst[i]    = sa[i];
    for (int i = 0; i < lb; i++) dst[la+i] = sb[i];
    return r;
}

__attribute__((export_name("safe_str_from_int")))
int safe_str_from_int(long long val) {
    if (val == 0) return safe_str_cstr("0", 1);
    char buf[32]; int pos = 32; int neg = val < 0;
    unsigned long long v = neg ? (unsigned long long)(-val) : (unsigned long long)val;
    while (v > 0) { buf[--pos] = (char)('0' + (int)(v % 10)); v /= 10; }
    if (neg) buf[--pos] = '-';
    return safe_str_cstr(buf + pos, 32 - pos);
}

__attribute__((export_name("safe_str_from_uint")))
int safe_str_from_uint(long long val) {
    if (val == 0) return safe_str_cstr("0", 1);
    char buf[32]; int pos = 32;
    unsigned long long v = (unsigned long long)val;
    while (v > 0) { buf[--pos] = (char)('0' + (int)(v % 10)); v /= 10; }
    return safe_str_cstr(buf + pos, 32 - pos);
}

__attribute__((export_name("safe_str_from_bool")))
int safe_str_from_bool(int val) {
    return val ? safe_str_cstr("true", 4) : safe_str_cstr("false", 5);
}

// Minimal float→string: format with up to 14 significant digits, trim trailing zeros.
__attribute__((export_name("safe_str_from_float")))
int safe_str_from_float(double val) {
    // Special values
    if (val != val) return safe_str_cstr("NaN", 3);
    double inf = 1.0/0.0;
    if (val == inf)  return safe_str_cstr("Infinity", 8);
    if (val == -inf) return safe_str_cstr("-Infinity", 9);

    // Format integer part + fraction using simple decimal expansion
    int neg = val < 0.0; if (neg) val = -val;
    // Extract integer and fractional parts
    long long whole = (long long)val;
    double frac = val - (double)whole;

    // Build digits in a fixed buffer
    char buf[128]; int pos = 64; // write from middle outward

    // Integer part (reversed)
    if (whole == 0) { buf[--pos] = '0'; }
    else {
        unsigned long long w = (unsigned long long)whole;
        while (w > 0) { buf[--pos] = (char)('0' + (int)(w%10)); w /= 10; }
    }
    if (neg) buf[--pos] = '-';
    int int_len = 64 - pos;

    // Fractional part: up to 14 significant digits beyond integer
    char frac_buf[20]; int frac_len = 0;
    // Compute fractional digits
    double f = frac;
    for (int i = 0; i < 14 && (f > 0.0 || frac_len == 0); i++) {
        f *= 10.0;
        int d = (int)f;
        if (d > 9) d = 9;
        frac_buf[frac_len++] = (char)('0' + d);
        f -= (double)d;
    }
    // Trim trailing zeros, keep at least 1
    while (frac_len > 1 && frac_buf[frac_len-1] == '0') frac_len--;

    int total = int_len + 1 + frac_len; // +1 for '.'
    int r = safe_rc_alloc(4 + total, 8 /* SAFE_KIND_STRING */, 0);
    *(int*)r = total;
    unsigned char* dst = (unsigned char*)(r+4);
    for (int i = 0; i < int_len; i++) dst[i] = (unsigned char)buf[pos+i];
    dst[int_len] = '.';
    for (int i = 0; i < frac_len; i++) dst[int_len+1+i] = (unsigned char)frac_buf[i];
    return r;
}

// ==================== Value equality and to-string ====================
// Compare two tagged i64 values for equality (string equality is by content)
__attribute__((export_name("safe_values_eq")))
int safe_values_eq(long long a, long long b) {
    if (a == b) return 1;
    int ta = (int)(a & 0xF), tb = (int)(b & 0xF);
    if (ta != tb) return 0;
    if (ta == SAFE_TAG_STR) {
        int pa = (int)((unsigned long long)a >> SAFE_TAG_BITS);
        int pb = (int)((unsigned long long)b >> SAFE_TAG_BITS);
        return safe_str_eq(pa, pb);
    }
    return 0;
}

// Forward decls for to_string list/map support
static int safe_list_len_raw(int list);
static long long safe_list_get_raw(int list, int index);

static int safe_tagged_to_str(long long tagged);

// Convert list to "[e0, e1, ...]"
static int list_to_str(int list) {
    int n = safe_list_len_raw(list);
    int r = safe_str_cstr("[", 1);
    for (int i = 0; i < n; i++) {
        if (i > 0) r = safe_str_concat(r, safe_str_cstr(", ", 2));
        long long elem = safe_list_get_raw(list, i);
        int s = safe_tagged_to_str(elem);
        r = safe_str_concat(r, s);
    }
    return safe_str_concat(r, safe_str_cstr("]", 1));
}

// Convert map to "{k: v, ...}" in insertion order (walks the linked chain
// threaded through the bucket array — matches LinkedHashMap semantics).
static int map_to_str(int map) {
    int body = *(int*)map;
    int r = safe_str_cstr("{", 1);
    int emitted = 0;
    int idx = *(int*)(body + SAFE_MAP_HEAD_OFF);
    while (idx != -1) {
        int b = body + SAFE_MAP_HEADER_BYTES + idx * SAFE_MAP_BUCKET_BYTES;
        if (emitted > 0) r = safe_str_concat(r, safe_str_cstr(", ", 2));
        long long k = *(long long*)(b + SAFE_MAP_KEY_OFF);
        long long v = *(long long*)(b + SAFE_MAP_VAL_OFF);
        r = safe_str_concat(r, safe_tagged_to_str(k));
        r = safe_str_concat(r, safe_str_cstr(": ", 2));
        r = safe_str_concat(r, safe_tagged_to_str(v));
        emitted++;
        idx = *(int*)(b + SAFE_MAP_NEXT_OFF);
    }
    return safe_str_concat(r, safe_str_cstr("}", 1));
}

static int safe_tagged_to_str(long long tagged) {
    int tag = (int)(tagged & 0xF);
    if (tag == SAFE_TAG_STR)  return (int)((unsigned long long)tagged >> SAFE_TAG_BITS);
    if (tag == SAFE_TAG_INT)  return safe_str_from_int(tagged >> SAFE_TAG_BITS);
    if (tag == SAFE_TAG_UINT) return safe_str_from_uint(tagged >> SAFE_TAG_BITS);
    if (tag == SAFE_TAG_BOOL) return safe_str_from_bool((int)(tagged >> SAFE_TAG_BITS));
    if (tag == SAFE_TAG_FLOAT) {
        long long bits = tagged & ~(long long)0xF;
        double val; __builtin_memcpy(&val, &bits, 8);
        return safe_str_from_float(val);
    }
    if (tag == SAFE_TAG_VOID)  return safe_str_cstr("void", 4);
    if (tag == SAFE_TAG_LIST || tag == SAFE_TAG_SET)
        return list_to_str((int)((unsigned long long)tagged >> SAFE_TAG_BITS));
    if (tag == SAFE_TAG_MAP)
        return map_to_str((int)((unsigned long long)tagged >> SAFE_TAG_BITS));
    if (tag == SAFE_TAG_BYTES) return safe_str_cstr("<bytes>", 7);
    return safe_str_cstr("", 0);
}

__attribute__((export_name("safe_to_string")))
int safe_to_string(long long tagged) { return safe_tagged_to_str(tagged); }

// ==================== List runtime ====================
// Layout: [i32 length][i32 capacity][i64 elements...]

__attribute__((export_name("safe_list_new")))
int safe_list_new(int cap) {
    if (cap < 0) cap = 0;
    int r = safe_rc_alloc(8 + cap * 8, 1 /* SAFE_KIND_LIST */, 0);
    *(int*)r = 0; *(int*)(r+4) = cap;
    return r;
}

static int safe_list_len_raw(int list) { return *(int*)list; }
static long long safe_list_get_raw(int list, int i) {
    return *(long long*)(list + 8 + i * 8);
}

__attribute__((export_name("safe_list_len")))
int safe_list_len(int list) { return *(int*)list; }

__attribute__((export_name("safe_list_get")))
long long safe_list_get(int list, int index) {
    long long result = *(long long*)(list + 8 + index * 8);
    /* Return an owning reference so the caller's scope-release is
     * balanced. Otherwise the codegen-emitted release on a heap-typed
     * temporary would dip below the list's own refcount. */
    safe_rc_retain_tagged(result);
    return result;
}

__attribute__((export_name("safe_list_set")))
void safe_list_set(int list, int index, long long value) {
    *(long long*)(list + 8 + index * 8) = value;
}

__attribute__((export_name("safe_list_append")))
int safe_list_append(int list, long long value) {
    if (*(int*)list >= SAFE_MAX_LIST_SIZE) {
        fprintf(stderr, "list size exceeds maximum of %d\n", SAFE_MAX_LIST_SIZE);
        __builtin_trap();
    }
    /* Retain heap-tagged value so the list owns its reference; dispose
     * iterates elements and releases each. */
    safe_rc_retain_tagged(value);
    int len = *(int*)list, cap = *(int*)(list+4);
    if (len < cap) {
        *(long long*)(list + 8 + len * 8) = value;
        *(int*)list = len + 1;
        return list;
    }
    int newcap = (cap < 4) ? 8 : cap * 2;
    SAFEHeader* oldhdr = (SAFEHeader*)(list - 8);
    /* Unique-owner check reads the packed count, not the raw refs word. */
    int unique = (safe_rc_count(oldhdr) == 1);
    int nl = safe_rc_alloc(8 + newcap * 8, oldhdr->kind, oldhdr->meta);
    *(int*)nl = len + 1; *(int*)(nl+4) = newcap;
    if (unique) {
        /* Phase B unique-owner fast path: move elements without retaining.
         * Caller's release-on-reassignment disposes the old block; its
         * length is zeroed so dispose skips the element walk. */
        for (int i = 0; i < len; i++) {
            *(long long*)(nl + 8 + i*8) = *(long long*)(list + 8 + i*8);
        }
        *(int*)list = 0;
    } else {
        /* Shared: new block retains each element. */
        for (int i = 0; i < len; i++) {
            long long elem = *(long long*)(list + 8 + i*8);
            safe_rc_retain_tagged(elem);
            *(long long*)(nl + 8 + i*8) = elem;
        }
    }
    *(long long*)(nl + 8 + len*8) = value;
    return nl;
}

__attribute__((export_name("safe_list_remove_at")))
int safe_list_remove_at(int list, int index) {
    int len = *(int*)list;
    int nl = safe_list_new(len > 1 ? len - 1 : 0);
    for (int i = 0; i < len; i++) {
        if (i == index) continue;
        long long v = *(long long*)(list + 8 + i*8);
        *(long long*)(nl + 8 + (i < index ? i : i-1)*8) = v;
    }
    if (len > 0) *(int*)nl = len - 1;
    return nl;
}

__attribute__((export_name("safe_list_slice")))
int safe_list_slice(int list, int start, int end) {
    int len = *(int*)list;
    if (start < 0) start = 0;
    if (end > len) end = len;
    int n = end - start; if (n < 0) n = 0;
    int nl = safe_list_new(n);
    *(int*)nl = n;
    for (int i = 0; i < n; i++)
        *(long long*)(nl + 8 + i*8) = *(long long*)(list + 8 + (start+i)*8);
    return nl;
}

__attribute__((export_name("safe_list_concat")))
int safe_list_concat(int a, int b) {
    int la = *(int*)a, lb = *(int*)b;
    int nl = safe_list_new(la + lb);
    *(int*)nl = la + lb;
    for (int i = 0; i < la; i++)
        *(long long*)(nl + 8 + i*8) = *(long long*)(a + 8 + i*8);
    for (int i = 0; i < lb; i++)
        *(long long*)(nl + 8 + (la+i)*8) = *(long long*)(b + 8 + i*8);
    return nl;
}

__attribute__((export_name("safe_list_reverse")))
int safe_list_reverse(int list) {
    int len = *(int*)list;
    int nl = safe_list_new(len);
    *(int*)nl = len;
    for (int i = 0; i < len; i++)
        *(long long*)(nl + 8 + i*8) = *(long long*)(list + 8 + (len-1-i)*8);
    return nl;
}

// ==================== Map additions (Phase A hash map) ====================
// safe_map_contains / remove / values delegate to the bucket-based layout
// defined earlier in this file.

__attribute__((export_name("safe_map_contains")))
int safe_map_contains(int map, long long key) {
    int body = *(int*)map;
    if (map_count(body) == 0) {
        MAP_TRACE_CONTAINS(map, key, 0);
        return 0;
    }
    unsigned int h = safe_hash_tagged(key);
    int probe_state;
    map_probe(body, key, h, &probe_state);
    int found = probe_state == SAFE_MAP_STATE_FILLED ? 1 : 0;
    MAP_TRACE_CONTAINS(map, key, found);
    return found;
}

__attribute__((export_name("safe_map_values")))
int safe_map_values(int map) { return safe_map_build_list(map, 1); }

__attribute__((export_name("safe_map_remove")))
int safe_map_remove(int map, long long key) {
    int body = *(int*)map;
    if (map_count(body) == 0) {
        MAP_TRACE_REMOVE(map, key, 0);
        return map;
    }
    unsigned int h = safe_hash_tagged(key);
    int probe_state;
    int slot = map_probe(body, key, h, &probe_state);
    if (probe_state != SAFE_MAP_STATE_FILLED) {
        MAP_TRACE_REMOVE(map, key, 0);
        return map;
    }
    int b = map_bucket_addr(body, slot);
    long long old_key = *(long long*)(b + SAFE_MAP_KEY_OFF);
    long long old_val = *(long long*)(b + SAFE_MAP_VAL_OFF);
    safe_rc_release_tagged(old_key);
    safe_rc_release_tagged(old_val);
    /* Unlink from insertion-order chain before wiping the slot. */
    map_unlink(body, slot);
    *(long long*)(b + SAFE_MAP_KEY_OFF) = 0;
    *(long long*)(b + SAFE_MAP_VAL_OFF) = 0;
    *(int*)(b + SAFE_MAP_HASH_OFF) = 0;
    *(int*)(b + SAFE_MAP_STATE_OFF) = SAFE_MAP_STATE_DELETE;
    *(int*)(b + SAFE_MAP_NEXT_OFF) = -1;
    *(int*)(b + SAFE_MAP_PREV_OFF) = -1;
    map_set_count(body, map_count(body) - 1);
    map_set_tombstones(body, map_tombstones(body) + 1);
    MAP_TRACE_REMOVE(map, key, 1);
    return map;
}

// ==================== Print runtime ====================
// Write bytes to stdout (fd=1)
static void safe_write_stdout(int ptr, int len) {
    // ptr points to bytes (raw, NOT a SAFE string pointer)
    write(1, (const char*)ptr, len);
}

__attribute__((export_name("safe_print_str")))
void safe_print_str(int str_ptr) {
    int len = *(int*)str_ptr;
    safe_write_stdout(str_ptr + 4, len);
}

__attribute__((export_name("safe_print_tagged")))
void safe_print_tagged(long long tagged) {
    int s = safe_tagged_to_str(tagged);
    safe_print_str(s);
}

__attribute__((export_name("safe_println_tagged")))
void safe_println_tagged(long long tagged) {
    safe_print_tagged(tagged);
    char nl = '\n';
    safe_write_stdout((int)&nl, 1);
}

// ==================== Contract failure trap ====================
// Write a SAFE string (length-prefixed) followed by a newline to stderr,
// then exit with status 1. Used by the WASM backend's prologue/epilogue
// emission when a requires/ensures/decreases contract is violated.
__attribute__((export_name("safe_trap_with_message")))
void safe_trap_with_message(int str_ptr) {
    int len = *(int*)str_ptr;
    static const char nl = '\n';
    wasi_iovec_t parts[2];
    parts[0].buf = str_ptr + 4;
    parts[0].buf_len = len;
    parts[1].buf = (int)(intptr_t)&nl;
    parts[1].buf_len = 1;
    __wasi_size_t nwritten = 0;
    (void)__wasi_fd_write((__wasi_fd_t)2, (const __wasi_ciovec_t*)parts, 2, &nwritten);
    __wasi_proc_exit(1);
}
