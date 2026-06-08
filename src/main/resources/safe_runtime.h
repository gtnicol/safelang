/*
 * SAFE Runtime Support Library
 * Header-only C runtime for SAFE language compiled programs.
 * Uses static inline functions to avoid linker issues with multiple includes.
 */
#ifndef SAFE_RUNTIME_H
#define SAFE_RUNTIME_H

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdarg.h>
#include <math.h>
#include <time.h>
#include <regex.h>
#include <dirent.h>
#include <sys/stat.h>
#include <sys/utsname.h>
#include <unistd.h>

/* ===== Uint checked arithmetic ===== */

static inline uint64_t safe_uint_sub(uint64_t a, uint64_t b) {
    if (b > a) { fprintf(stderr, "Unsigned integer cannot be negative: %lld\n", (long long)((int64_t)a - (int64_t)b)); exit(1); }
    return a - b;
}

static inline uint64_t safe_uint_check(int64_t v) {
    if (v < 0) { fprintf(stderr, "Unsigned integer cannot be negative: %lld\n", (long long)v); exit(1); }
    return (uint64_t)v;
}

/* ===== Checked division/modulo ===== */

static inline int64_t safe_int_div(int64_t a, int64_t b) {
    if (b == 0) { fprintf(stderr, "Division by zero\n"); exit(1); }
    if (a == INT64_MIN && b == -1) { fprintf(stderr, "Integer overflow: INT64_MIN / -1\n"); exit(1); }
    return a / b;
}

static inline int64_t safe_int_mod(int64_t a, int64_t b) {
    if (b == 0) { fprintf(stderr, "Division by zero\n"); exit(1); }
    return a % b;
}

static inline uint64_t safe_uint_div(uint64_t a, uint64_t b) {
    if (b == 0) { fprintf(stderr, "Division by zero\n"); exit(1); }
    return a / b;
}

static inline uint64_t safe_uint_mod(uint64_t a, uint64_t b) {
    if (b == 0) { fprintf(stderr, "Division by zero\n"); exit(1); }
    return a % b;
}

static inline double safe_float_div(double a, double b) {
    if (b == 0.0) { fprintf(stderr, "Division by zero\n"); exit(1); }
    return a / b;
}

static inline double safe_float_mod(double a, double b) {
    if (b == 0.0) { fprintf(stderr, "Division by zero\n"); exit(1); }
    return fmod(a, b);
}

/* ===== Arena Allocator ===== */

#define SAFE_ARENA_BLOCK_SIZE (1024 * 1024) /* 1 MB */

typedef struct SAFEArenaBlock {
    struct SAFEArenaBlock* next;
    size_t capacity;
    size_t used;
    char data[];
} SAFEArenaBlock;

static SAFEArenaBlock* __safe_arena_head = NULL;
static size_t __safe_arena_bytes = 0; /* cumulative bytes handed out (bump high-water) */

__attribute__((destructor))
static void __safe_arena_report(void) {
    const char* flag = getenv("SAFE_HEAP_REPORT");
    if (flag && flag[0] && flag[0] != '0') {
        fprintf(stderr, "safe: arena peak = %zu bytes\n", __safe_arena_bytes);
    }
}

static inline void* safe_arena_alloc(size_t size) {
    size = (size + 7) & ~(size_t)7; /* 8-byte alignment */
    __safe_arena_bytes += size;
    SAFEArenaBlock* block = __safe_arena_head;
    if (block && block->used + size <= block->capacity) {
        void* ptr = block->data + block->used;
        block->used += size;
        return ptr;
    }
    size_t capacity = size > SAFE_ARENA_BLOCK_SIZE ? size : SAFE_ARENA_BLOCK_SIZE;
    block = (SAFEArenaBlock*)malloc(sizeof(SAFEArenaBlock) + capacity);
    block->capacity = capacity;
    block->used = size;
    block->next = __safe_arena_head;
    __safe_arena_head = block;
    return block->data;
}

static inline char* safe_arena_strdup(const char* s) {
    if (!s) return NULL;
    size_t length = strlen(s) + 1;
    char* copy = (char*)safe_arena_alloc(length);
    memcpy(copy, s, length);
    return copy;
}

static inline void safe_arena_free(void) {
    SAFEArenaBlock* block = __safe_arena_head;
    while (block) {
        SAFEArenaBlock* next = block->next;
        free(block);
        block = next;
    }
    __safe_arena_head = NULL;
}

/* ===== Refcount infrastructure (Phase 3 allocator) =====
 * Refcounted STRUCTS are arena-allocated (fast bump, no per-alloc cost).
 * Their heap-side BUFFERS (list->data, map->buckets, bytes->data) are
 * malloc'd and freed via safe_dispose — that's where the big memory lives
 * anyway. Struct-body leaks cost ~24 bytes each; buffer reclamation wins
 * megabytes.
 *
 * Layout: [SAFEHeader][body]. Returned pointer is past the header.
 * safe_release decrements refs; at zero, safe_dispose frees children
 * (malloc'd buffers) and leaves the struct block in the arena. */
#include "safe_refcount.h"

/* Forward declarations for per-kind child release inside safe_dispose. */
static inline void safe_dispose(void* body);

/* Phase C: safe_alloc now uses malloc so struct/enum/tuple/bytes bodies
 * can be reclaimed via free() when refs hit 0. Previously this forwarded
 * to safe_arena_alloc (bump-only), which meant all non-buffer blocks
 * leaked for the process lifetime. The arena is still live for truly-
 * immortal allocations (string literals via safe_intern_string, scratch
 * buffers) — see safe_arena_alloc call sites. */
static inline void* safe_alloc(size_t size, uint8_t kind, uint16_t meta) {
    char* raw = (char*)malloc(sizeof(SAFEHeader) + size);
    SAFEHeader* hdr = (SAFEHeader*)raw;
    hdr->refs = 1;
    hdr->kind = kind;
    hdr->meta = meta;
    hdr->size_class = 0;
    return raw + sizeof(SAFEHeader);
}

static inline void safe_release(void* body) {
    if (!body) return;
    SAFEHeader* hdr = safe_header(body);
    if (hdr->refs == SAFE_REFS_IMMORTAL) return;
    if (--hdr->refs == 0) {
        safe_dispose(body);
        /* After child release, free the malloc'd block. IMMORTAL blocks
         * (string literals) short-circuit at the check above and never
         * reach here, so they stay put. */
        free((char*)body - sizeof(SAFEHeader));
    }
}

/* ===== List Support ===== */

typedef struct {
    void* data;
    int64_t length;
    int64_t capacity;
} SAFEList;

static inline SAFEList* safe_list_new(void) {
    SAFEList* list = (SAFEList*)safe_alloc(sizeof(SAFEList), SAFE_KIND_LIST, 0);
    list->capacity = 10;
    list->length = 0;
    list->data = malloc(list->capacity * sizeof(void*));
    return list;
}

/* Phase 5: typed list. `kind` encodes the element kind; dispose will
 * release each element if kind is heap-refcounted. Callers that don't
 * know the element type (stdlib-generic code, type-variable contexts)
 * continue to use safe_list_new() with kind=0 (no retention). */
static inline SAFEList* safe_list_new_typed(uint8_t kind) {
    SAFEList* list = (SAFEList*)safe_alloc(sizeof(SAFEList), SAFE_KIND_LIST, kind);
    list->capacity = 10;
    list->length = 0;
    list->data = malloc(list->capacity * sizeof(void*));
    return list;
}

static inline void safe_list_append(SAFEList* list, void* value) {
    if (list->length >= list->capacity) {
        list->capacity *= 2;
        list->data = realloc(list->data, list->capacity * sizeof(void*));
    }
    /* Phase 6: if the list is typed with a heap element kind, retain on
     * insert so the caller can safely release its own reference. */
    if (safe_kind_is_heap(safe_header(list)->meta)) {
        safe_retain(value);
    }
    ((void**)list->data)[list->length] = value;
    list->length++;
}

static inline int64_t safe_list_len(SAFEList* list) {
    return list ? list->length : 0;
}

static inline int64_t safe_list_get_int(SAFEList* list, int64_t index) {
    if (index < 0 || index >= list->length) { fprintf(stderr, "Index out of bounds: %lld (length %lld)\n", (long long)index, (long long)list->length); exit(1); }
    return *((int64_t*)((void**)list->data)[index]);
}

static inline double safe_list_get_float(SAFEList* list, int64_t index) {
    if (index < 0 || index >= list->length) { fprintf(stderr, "Index out of bounds: %lld (length %lld)\n", (long long)index, (long long)list->length); exit(1); }
    return *((double*)((void**)list->data)[index]);
}

static inline char* safe_list_get_str(SAFEList* list, int64_t index) {
    if (index < 0 || index >= list->length) { fprintf(stderr, "Index out of bounds: %lld (length %lld)\n", (long long)index, (long long)list->length); exit(1); }
    return (char*)((void**)list->data)[index];
}

static inline int safe_list_contains_int(SAFEList* list, int64_t value) {
    if (!list) return 0;
    for (int64_t i = 0; i < list->length; i++) {
        if (*((int64_t*)((void**)list->data)[i]) == value) return 1;
    }
    return 0;
}

static inline int safe_list_contains_str(SAFEList* list, const char* value) {
    if (!list) return 0;
    for (int64_t i = 0; i < list->length; i++) {
        if (strcmp((char*)((void**)list->data)[i], value) == 0) return 1;
    }
    return 0;
}

static inline int safe_list_contains_float(SAFEList* list, double value) {
    if (!list) return 0;
    for (int64_t i = 0; i < list->length; i++) {
        if (*((double*)((void**)list->data)[i]) == value) return 1;
    }
    return 0;
}

/* ===== List Slice/Reverse/Remove ===== */

static inline SAFEList* safe_list_slice(SAFEList* list, int64_t start, int64_t end) {
    SAFEList* result = safe_list_new();
    if (!list) return result;
    /* Match the interpreter (CollectionBuiltins.java:94) and the bytecode VM
       by trapping on out-of-range arguments rather than silently clamping.
       Clamping hides user errors and diverges from the other backends. */
    if (start < 0 || end < start || end > list->length) {
        fprintf(stderr,
                "slice: index out of bounds (start=%lld, end=%lld, size=%lld)\n",
                (long long)start, (long long)end, (long long)list->length);
        exit(1);
    }
    for (int64_t i = start; i < end; i++) {
        safe_list_append(result, ((void**)list->data)[i]);
    }
    return result;
}

static inline SAFEList* safe_list_reverse(SAFEList* list) {
    SAFEList* result = safe_list_new();
    if (!list) return result;
    /* i-- > 0 is the idiomatic reverse iteration — it avoids the "i starts at
       length-1" off-by-one bait and works transparently when length is 0. */
    for (int64_t i = list->length; i-- > 0; ) {
        safe_list_append(result, ((void**)list->data)[i]);
    }
    return result;
}

static inline SAFEList* safe_list_remove_int(SAFEList* list, int64_t value) {
    SAFEList* result = safe_list_new();
    if (!list) return result;
    for (int64_t i = 0; i < list->length; i++) {
        if (*((int64_t*)((void**)list->data)[i]) != value) {
            safe_list_append(result, ((void**)list->data)[i]);
        }
    }
    return result;
}

static inline SAFEList* safe_list_remove_str(SAFEList* list, const char* value) {
    SAFEList* result = safe_list_new();
    if (!list) return result;
    for (int64_t i = 0; i < list->length; i++) {
        if (strcmp((char*)((void**)list->data)[i], value) != 0) {
            safe_list_append(result, ((void**)list->data)[i]);
        }
    }
    return result;
}

static inline SAFEList* safe_list_remove_float(SAFEList* list, double value) {
    SAFEList* result = safe_list_new();
    if (!list) return result;
    for (int64_t i = 0; i < list->length; i++) {
        if (*((double*)((void**)list->data)[i]) != value) {
            safe_list_append(result, ((void**)list->data)[i]);
        }
    }
    return result;
}

static inline SAFEList* safe_list_remove_at(SAFEList* list, int64_t index) {
    SAFEList* result = safe_list_new();
    if (!list) return result;
    for (int64_t i = 0; i < list->length; i++) {
        if (i != index) {
            safe_list_append(result, ((void**)list->data)[i]);
        }
    }
    return result;
}

/* ===== Map Support ===== */

typedef struct SAFEMapEntry {
    int key_tag; /* 0=string, 1=int, 2=bool, 3=float */
    union {
        char* string_key;
        int64_t int_key;
        bool bool_key;
        double float_key;
    } key;
    int tag;  /* 0=int, 1=float, 2=string, 3=bool, 4=ptr */
    union {
        int64_t int_val;
        double float_val;
        char* string_val;
        bool bool_val;
        void* ptr_val;
    } value;
    struct SAFEMapEntry* next;
    struct SAFEMapEntry* order_next;
    struct SAFEMapEntry* order_prev;
} SAFEMapEntry;

typedef struct {
    SAFEMapEntry** buckets;
    int64_t capacity;
    int64_t length;
    SAFEMapEntry* head;
    SAFEMapEntry* tail;
} SAFEMap;

/* Phase 5: typed map. meta packs (key_kind << 4) | (value_kind & 0xF).
 * Dispose will release each value if value_kind is heap-refcounted;
 * keys are scalar (int/str) and not refcounted at present. */
static inline SAFEMap* safe_map_new_typed(uint8_t key_kind, uint8_t value_kind) {
    const uint8_t meta = (uint8_t)(((key_kind & 0xF) << 4) | (value_kind & 0xF));
    SAFEMap* map = (SAFEMap*)safe_alloc(sizeof(SAFEMap), SAFE_KIND_MAP, meta);
    map->capacity = 16;
    map->length = 0;
    map->buckets = (SAFEMapEntry**)calloc(map->capacity, sizeof(SAFEMapEntry*));
    map->head = NULL;
    map->tail = NULL;
    return map;
}

static inline SAFEMap* safe_map_new(void) {
    SAFEMap* map = (SAFEMap*)safe_alloc(sizeof(SAFEMap), SAFE_KIND_MAP, 0);
    map->capacity = 16;
    map->length = 0;
    map->buckets = (SAFEMapEntry**)calloc(map->capacity, sizeof(SAFEMapEntry*));
    map->head = NULL;
    map->tail = NULL;
    return map;
}

/* Append entry to insertion-order linked list */
static inline void safe_map_order_append(SAFEMap* map, SAFEMapEntry* entry) {
    entry->order_prev = map->tail;
    entry->order_next = NULL;
    if (map->tail) map->tail->order_next = entry;
    else map->head = entry;
    map->tail = entry;
}

/* Remove entry from insertion-order linked list */
static inline void safe_map_order_remove(SAFEMap* map, SAFEMapEntry* entry) {
    if (entry->order_prev) entry->order_prev->order_next = entry->order_next;
    else map->head = entry->order_next;
    if (entry->order_next) entry->order_next->order_prev = entry->order_prev;
    else map->tail = entry->order_prev;
}

static inline uint64_t safe_map_hash(const char* key, int64_t capacity) {
    uint64_t hash = 5381;
    while (*key) {
        hash = ((hash << 5) + hash) + (unsigned char)*key++;
    }
    return hash % (uint64_t)capacity;
}

static inline uint64_t safe_map_hash_int(int64_t key, int64_t capacity) {
    uint64_t hash = (uint64_t)key;
    hash = ((hash >> 16) ^ hash) * 0x45d9f3b;
    hash = ((hash >> 16) ^ hash) * 0x45d9f3b;
    hash = (hash >> 16) ^ hash;
    return hash % (uint64_t)capacity;
}

static inline SAFEMapEntry* safe_map_find(SAFEMap* map, const char* key) {
    uint64_t bucket = safe_map_hash(key, map->capacity);
    SAFEMapEntry* entry = map->buckets[bucket];
    while (entry) {
        if (entry->key_tag == 0 && strcmp(entry->key.string_key, key) == 0) return entry;
        entry = entry->next;
    }
    return NULL;
}

static inline SAFEMapEntry* safe_map_find_ikey(SAFEMap* map, int64_t key) {
    uint64_t bucket = safe_map_hash_int(key, map->capacity);
    SAFEMapEntry* entry = map->buckets[bucket];
    while (entry) {
        if (entry->key_tag == 1 && entry->key.int_key == key) return entry;
        entry = entry->next;
    }
    return NULL;
}

/* Float-key hash and find */
static inline uint64_t safe_map_hash_float(double key, int64_t capacity) {
    uint64_t bits;
    memcpy(&bits, &key, sizeof(bits));
    bits = ((bits >> 16) ^ bits) * 0x45d9f3b;
    bits = ((bits >> 16) ^ bits) * 0x45d9f3b;
    bits = (bits >> 16) ^ bits;
    return bits % (uint64_t)capacity;
}

static inline SAFEMapEntry* safe_map_find_fkey(SAFEMap* map, double key) {
    uint64_t bucket = safe_map_hash_float(key, map->capacity);
    SAFEMapEntry* entry = map->buckets[bucket];
    while (entry) {
        if (entry->key_tag == 3 && entry->key.float_key == key) return entry;
        entry = entry->next;
    }
    return NULL;
}

/* Float-keyed put functions */
static inline void safe_map_fkey_put_int(SAFEMap* map, double key, int64_t value) {
    SAFEMapEntry* existing = safe_map_find_fkey(map, key);
    if (existing) { existing->tag = 0; existing->value.int_val = value; return; }
    uint64_t bucket = safe_map_hash_float(key, map->capacity);
    SAFEMapEntry* entry = (SAFEMapEntry*)safe_arena_alloc(sizeof(SAFEMapEntry));
    entry->key_tag = 3; entry->key.float_key = key;
    entry->tag = 0; entry->value.int_val = value;
    entry->next = map->buckets[bucket]; map->buckets[bucket] = entry;
    safe_map_order_append(map, entry); map->length++;
}

static inline void safe_map_fkey_put_float(SAFEMap* map, double key, double value) {
    SAFEMapEntry* existing = safe_map_find_fkey(map, key);
    if (existing) { existing->tag = 1; existing->value.float_val = value; return; }
    uint64_t bucket = safe_map_hash_float(key, map->capacity);
    SAFEMapEntry* entry = (SAFEMapEntry*)safe_arena_alloc(sizeof(SAFEMapEntry));
    entry->key_tag = 3; entry->key.float_key = key;
    entry->tag = 1; entry->value.float_val = value;
    entry->next = map->buckets[bucket]; map->buckets[bucket] = entry;
    safe_map_order_append(map, entry); map->length++;
}

static inline void safe_map_fkey_put_str(SAFEMap* map, double key, const char* value) {
    SAFEMapEntry* existing = safe_map_find_fkey(map, key);
    if (existing) { existing->tag = 2; existing->value.string_val = safe_arena_strdup(value); return; }
    uint64_t bucket = safe_map_hash_float(key, map->capacity);
    SAFEMapEntry* entry = (SAFEMapEntry*)safe_arena_alloc(sizeof(SAFEMapEntry));
    entry->key_tag = 3; entry->key.float_key = key;
    entry->tag = 2; entry->value.string_val = safe_arena_strdup(value);
    entry->next = map->buckets[bucket]; map->buckets[bucket] = entry;
    safe_map_order_append(map, entry); map->length++;
}

static inline void safe_map_fkey_put_bool(SAFEMap* map, double key, bool value) {
    SAFEMapEntry* existing = safe_map_find_fkey(map, key);
    if (existing) { existing->tag = 3; existing->value.bool_val = value; return; }
    uint64_t bucket = safe_map_hash_float(key, map->capacity);
    SAFEMapEntry* entry = (SAFEMapEntry*)safe_arena_alloc(sizeof(SAFEMapEntry));
    entry->key_tag = 3; entry->key.float_key = key;
    entry->tag = 3; entry->value.bool_val = value;
    entry->next = map->buckets[bucket]; map->buckets[bucket] = entry;
    safe_map_order_append(map, entry); map->length++;
}

static inline void safe_map_fkey_put_ptr(SAFEMap* map, double key, void* value) {
    SAFEMapEntry* existing = safe_map_find_fkey(map, key);
    if (existing) { existing->tag = 4; existing->value.ptr_val = value; return; }
    uint64_t bucket = safe_map_hash_float(key, map->capacity);
    SAFEMapEntry* entry = (SAFEMapEntry*)safe_arena_alloc(sizeof(SAFEMapEntry));
    entry->key_tag = 3; entry->key.float_key = key;
    entry->tag = 4; entry->value.ptr_val = value;
    entry->next = map->buckets[bucket]; map->buckets[bucket] = entry;
    safe_map_order_append(map, entry); map->length++;
}

static inline void* safe_map_fkey_get_ptr(SAFEMap* map, double key) {
    SAFEMapEntry* entry = safe_map_find_fkey(map, key);
    return entry ? entry->value.ptr_val : NULL;
}

/* Float-keyed get functions */
static inline int64_t safe_map_fkey_get_int(SAFEMap* map, double key) {
    SAFEMapEntry* entry = safe_map_find_fkey(map, key);
    if (!entry) { fprintf(stderr, "Warning: map key %g not found, returning default\n", key); return 0; }
    return entry->value.int_val;
}

static inline double safe_map_fkey_get_float(SAFEMap* map, double key) {
    SAFEMapEntry* entry = safe_map_find_fkey(map, key);
    if (!entry) { fprintf(stderr, "Warning: map key %g not found, returning default\n", key); return 0.0; }
    return entry->value.float_val;
}

static inline char* safe_map_fkey_get_str(SAFEMap* map, double key) {
    SAFEMapEntry* entry = safe_map_find_fkey(map, key);
    if (!entry) { fprintf(stderr, "Warning: map key %g not found, returning default\n", key); return ""; }
    return entry->value.string_val;
}

static inline bool safe_map_fkey_get_bool(SAFEMap* map, double key) {
    SAFEMapEntry* entry = safe_map_find_fkey(map, key);
    if (!entry) { fprintf(stderr, "Warning: map key %g not found, returning default\n", key); return false; }
    return entry->value.bool_val;
}

static inline int safe_map_contains_fkey(SAFEMap* map, double key) {
    return safe_map_find_fkey(map, key) != NULL;
}

static inline int safe_map_fkey_remove(SAFEMap* map, double key) {
    uint64_t bucket = safe_map_hash_float(key, map->capacity);
    SAFEMapEntry* entry = map->buckets[bucket];
    SAFEMapEntry* previous = NULL;
    while (entry) {
        if (entry->key_tag == 3 && entry->key.float_key == key) {
            if (previous) previous->next = entry->next;
            else map->buckets[bucket] = entry->next;
            safe_map_order_remove(map, entry);
            map->length--;
            return 1;
        }
        previous = entry;
        entry = entry->next;
    }
    return 0;
}

/* String-keyed put functions */
static inline void safe_map_put_int(SAFEMap* map, const char* key, int64_t value) {
    SAFEMapEntry* existing = safe_map_find(map, key);
    if (existing) {
        existing->tag = 0;
        existing->value.int_val = value;
        return;
    }
    uint64_t bucket = safe_map_hash(key, map->capacity);
    SAFEMapEntry* entry = (SAFEMapEntry*)safe_arena_alloc(sizeof(SAFEMapEntry));
    entry->key_tag = 0;
    entry->key.string_key = safe_arena_strdup(key);
    entry->tag = 0;
    entry->value.int_val = value;
    entry->next = map->buckets[bucket];
    map->buckets[bucket] = entry;
    safe_map_order_append(map, entry);
    map->length++;
}

static inline void safe_map_put_float(SAFEMap* map, const char* key, double value) {
    SAFEMapEntry* existing = safe_map_find(map, key);
    if (existing) {
        existing->tag = 1;
        existing->value.float_val = value;
        return;
    }
    uint64_t bucket = safe_map_hash(key, map->capacity);
    SAFEMapEntry* entry = (SAFEMapEntry*)safe_arena_alloc(sizeof(SAFEMapEntry));
    entry->key_tag = 0;
    entry->key.string_key = safe_arena_strdup(key);
    entry->tag = 1;
    entry->value.float_val = value;
    entry->next = map->buckets[bucket];
    map->buckets[bucket] = entry;
    safe_map_order_append(map, entry);
    map->length++;
}

static inline void safe_map_put_str(SAFEMap* map, const char* key, const char* value) {
    SAFEMapEntry* existing = safe_map_find(map, key);
    if (existing) {
        /* arena-allocated — old string value not individually freed */
        existing->tag = 2;
        existing->value.string_val = safe_arena_strdup(value);
        return;
    }
    uint64_t bucket = safe_map_hash(key, map->capacity);
    SAFEMapEntry* entry = (SAFEMapEntry*)safe_arena_alloc(sizeof(SAFEMapEntry));
    entry->key_tag = 0;
    entry->key.string_key = safe_arena_strdup(key);
    entry->tag = 2;
    entry->value.string_val = safe_arena_strdup(value);
    entry->next = map->buckets[bucket];
    map->buckets[bucket] = entry;
    safe_map_order_append(map, entry);
    map->length++;
}

static inline void safe_map_put_bool(SAFEMap* map, const char* key, bool value) {
    SAFEMapEntry* existing = safe_map_find(map, key);
    if (existing) {
        existing->tag = 3;
        existing->value.bool_val = value;
        return;
    }
    uint64_t bucket = safe_map_hash(key, map->capacity);
    SAFEMapEntry* entry = (SAFEMapEntry*)safe_arena_alloc(sizeof(SAFEMapEntry));
    entry->key_tag = 0;
    entry->key.string_key = safe_arena_strdup(key);
    entry->tag = 3;
    entry->value.bool_val = value;
    entry->next = map->buckets[bucket];
    map->buckets[bucket] = entry;
    safe_map_order_append(map, entry);
    map->length++;
}

/* String-keyed ptr put/get */
static inline void safe_map_put_ptr(SAFEMap* map, const char* key, void* value) {
    /* Phase 6: retain-on-insert when the map is typed with a heap value kind.
     * On overwrite, release the previous value so its refcount drops. */
    const uint8_t value_kind = safe_header(map)->meta & 0xF;
    const int retains = safe_kind_is_heap(value_kind);
    SAFEMapEntry* existing = safe_map_find(map, key);
    if (existing) {
        if (retains) {
            safe_retain(value);
            safe_release(existing->value.ptr_val);
        }
        existing->tag = 4;
        existing->value.ptr_val = value;
        return;
    }
    if (retains) safe_retain(value);
    uint64_t bucket = safe_map_hash(key, map->capacity);
    SAFEMapEntry* entry = (SAFEMapEntry*)safe_arena_alloc(sizeof(SAFEMapEntry));
    entry->key_tag = 0;
    entry->key.string_key = safe_arena_strdup(key);
    entry->tag = 4;
    entry->value.ptr_val = value;
    entry->next = map->buckets[bucket];
    map->buckets[bucket] = entry;
    safe_map_order_append(map, entry);
    map->length++;
}

static inline void* safe_map_get_ptr(SAFEMap* map, const char* key) {
    SAFEMapEntry* entry = safe_map_find(map, key);
    if (!entry) return NULL;
    /* Phase 7: mirror the retain-on-insert discipline. Get returns an
     * owned reference (refs += 1) when the value kind is heap, so the
     * caller can scope-release without freeing the map's copy. */
    if (safe_kind_is_heap(safe_header(map)->meta & 0xF)) {
        safe_retain(entry->value.ptr_val);
    }
    return entry->value.ptr_val;
}

/* Int-keyed put functions */
static inline void safe_map_ikey_put_int(SAFEMap* map, int64_t key, int64_t value) {
    SAFEMapEntry* existing = safe_map_find_ikey(map, key);
    if (existing) {
        existing->tag = 0;
        existing->value.int_val = value;
        return;
    }
    uint64_t bucket = safe_map_hash_int(key, map->capacity);
    SAFEMapEntry* entry = (SAFEMapEntry*)safe_arena_alloc(sizeof(SAFEMapEntry));
    entry->key_tag = 1;
    entry->key.int_key = key;
    entry->tag = 0;
    entry->value.int_val = value;
    entry->next = map->buckets[bucket];
    map->buckets[bucket] = entry;
    safe_map_order_append(map, entry);
    map->length++;
}

static inline void safe_map_ikey_put_str(SAFEMap* map, int64_t key, const char* value) {
    SAFEMapEntry* existing = safe_map_find_ikey(map, key);
    if (existing) {
        /* arena-allocated — old string value not individually freed */
        existing->tag = 2;
        existing->value.string_val = safe_arena_strdup(value);
        return;
    }
    uint64_t bucket = safe_map_hash_int(key, map->capacity);
    SAFEMapEntry* entry = (SAFEMapEntry*)safe_arena_alloc(sizeof(SAFEMapEntry));
    entry->key_tag = 1;
    entry->key.int_key = key;
    entry->tag = 2;
    entry->value.string_val = safe_arena_strdup(value);
    entry->next = map->buckets[bucket];
    map->buckets[bucket] = entry;
    safe_map_order_append(map, entry);
    map->length++;
}

static inline void safe_map_ikey_put_float(SAFEMap* map, int64_t key, double value) {
    SAFEMapEntry* existing = safe_map_find_ikey(map, key);
    if (existing) {
        existing->tag = 1;
        existing->value.float_val = value;
        return;
    }
    uint64_t bucket = safe_map_hash_int(key, map->capacity);
    SAFEMapEntry* entry = (SAFEMapEntry*)safe_arena_alloc(sizeof(SAFEMapEntry));
    entry->key_tag = 1;
    entry->key.int_key = key;
    entry->tag = 1;
    entry->value.float_val = value;
    entry->next = map->buckets[bucket];
    map->buckets[bucket] = entry;
    safe_map_order_append(map, entry);
    map->length++;
}

static inline void safe_map_ikey_put_bool(SAFEMap* map, int64_t key, bool value) {
    SAFEMapEntry* existing = safe_map_find_ikey(map, key);
    if (existing) {
        existing->tag = 3;
        existing->value.bool_val = value;
        return;
    }
    uint64_t bucket = safe_map_hash_int(key, map->capacity);
    SAFEMapEntry* entry = (SAFEMapEntry*)safe_arena_alloc(sizeof(SAFEMapEntry));
    entry->key_tag = 1;
    entry->key.int_key = key;
    entry->tag = 3;
    entry->value.bool_val = value;
    entry->next = map->buckets[bucket];
    map->buckets[bucket] = entry;
    safe_map_order_append(map, entry);
    map->length++;
}

/* Int-keyed ptr put/get */
static inline void safe_map_ikey_put_ptr(SAFEMap* map, int64_t key, void* value) {
    SAFEMapEntry* existing = safe_map_find_ikey(map, key);
    if (existing) {
        existing->tag = 4;
        existing->value.ptr_val = value;
        return;
    }
    uint64_t bucket = safe_map_hash_int(key, map->capacity);
    SAFEMapEntry* entry = (SAFEMapEntry*)safe_arena_alloc(sizeof(SAFEMapEntry));
    entry->key_tag = 1;
    entry->key.int_key = key;
    entry->tag = 4;
    entry->value.ptr_val = value;
    entry->next = map->buckets[bucket];
    map->buckets[bucket] = entry;
    safe_map_order_append(map, entry);
    map->length++;
}

static inline void* safe_map_ikey_get_ptr(SAFEMap* map, int64_t key) {
    SAFEMapEntry* entry = safe_map_find_ikey(map, key);
    return entry ? entry->value.ptr_val : NULL;
}

/* Int-keyed get functions */
static inline int64_t safe_map_ikey_get_int(SAFEMap* map, int64_t key) {
    SAFEMapEntry* entry = safe_map_find_ikey(map, key);
    if (!entry) { fprintf(stderr, "Warning: map key %lld not found, returning default\n", (long long)key); return 0; }
    return entry->value.int_val;
}

static inline char* safe_map_ikey_get_str(SAFEMap* map, int64_t key) {
    SAFEMapEntry* entry = safe_map_find_ikey(map, key);
    if (!entry) { fprintf(stderr, "Warning: map key %lld not found, returning default\n", (long long)key); return ""; }
    return entry->value.string_val;
}

static inline double safe_map_ikey_get_float(SAFEMap* map, int64_t key) {
    SAFEMapEntry* entry = safe_map_find_ikey(map, key);
    if (!entry) { fprintf(stderr, "Warning: map key %lld not found, returning default\n", (long long)key); return 0.0; }
    return entry->value.float_val;
}

static inline bool safe_map_ikey_get_bool(SAFEMap* map, int64_t key) {
    SAFEMapEntry* entry = safe_map_find_ikey(map, key);
    if (!entry) { fprintf(stderr, "Warning: map key %lld not found, returning default\n", (long long)key); return false; }
    return entry->value.bool_val;
}

/* String-keyed get functions
 * Note: missing key returns typed default and prints a warning.
 * C cannot return a tagged void like the interpreter does.
 * Use safe_map_has() / safe_map_contains() before access to avoid this. */
static inline int64_t safe_map_get_int(SAFEMap* map, const char* key) {
    SAFEMapEntry* entry = safe_map_find(map, key);
    if (!entry) { fprintf(stderr, "Warning: map key '%s' not found, returning default\n", key); return 0; }
    return entry->value.int_val;
}

static inline double safe_map_get_float(SAFEMap* map, const char* key) {
    SAFEMapEntry* entry = safe_map_find(map, key);
    if (!entry) { fprintf(stderr, "Warning: map key '%s' not found, returning default\n", key); return 0.0; }
    return entry->value.float_val;
}

static inline char* safe_map_get_str(SAFEMap* map, const char* key) {
    SAFEMapEntry* entry = safe_map_find(map, key);
    if (!entry) { fprintf(stderr, "Warning: map key '%s' not found, returning default\n", key); return ""; }
    return entry->value.string_val;
}

static inline bool safe_map_get_bool(SAFEMap* map, const char* key) {
    SAFEMapEntry* entry = safe_map_find(map, key);
    if (!entry) { fprintf(stderr, "Warning: map key '%s' not found, returning default\n", key); return false; }
    return entry->value.bool_val;
}

static inline int safe_map_contains(SAFEMap* map, const char* key) {
    return safe_map_find(map, key) != NULL;
}

static inline int safe_map_has(SAFEMap* map, const char* key) {
    return safe_map_find(map, key) != NULL;
}

static inline int safe_map_contains_ikey(SAFEMap* map, int64_t key) {
    return safe_map_find_ikey(map, key) != NULL;
}

static inline int safe_map_has_ikey(SAFEMap* map, int64_t key) {
    return safe_map_find_ikey(map, key) != NULL;
}

static inline int64_t safe_map_len(SAFEMap* map) {
    return map ? map->length : 0;
}

static inline int safe_map_remove(SAFEMap* map, const char* key) {
    uint64_t bucket = safe_map_hash(key, map->capacity);
    SAFEMapEntry* entry = map->buckets[bucket];
    SAFEMapEntry* previous = NULL;
    while (entry) {
        if (entry->key_tag == 0 && strcmp(entry->key.string_key, key) == 0) {
            if (previous) previous->next = entry->next;
            else map->buckets[bucket] = entry->next;
            safe_map_order_remove(map, entry);
            map->length--;
            return 1;
        }
        previous = entry;
        entry = entry->next;
    }
    return 0;
}

static inline int safe_map_ikey_remove(SAFEMap* map, int64_t key) {
    uint64_t bucket = safe_map_hash_int(key, map->capacity);
    SAFEMapEntry* entry = map->buckets[bucket];
    SAFEMapEntry* previous = NULL;
    while (entry) {
        if (entry->key_tag == 1 && entry->key.int_key == key) {
            if (previous) previous->next = entry->next;
            else map->buckets[bucket] = entry->next;
            safe_map_order_remove(map, entry);
            map->length--;
            return 1;
        }
        previous = entry;
        entry = entry->next;
    }
    return 0;
}

static inline SAFEList* safe_map_keys(SAFEMap* map) {
    SAFEList* list = safe_list_new();
    if (!map) return list;
    SAFEMapEntry* entry = map->head;
    while (entry) {
        if (entry->key_tag == 0) {
            safe_list_append(list, safe_arena_strdup(entry->key.string_key));
        } else if (entry->key_tag == 1) {
            int64_t* val = (int64_t*)safe_arena_alloc(sizeof(int64_t));
            *val = entry->key.int_key;
            safe_list_append(list, val);
        } else if (entry->key_tag == 3) {
            double* val = (double*)safe_arena_alloc(sizeof(double));
            *val = entry->key.float_key;
            safe_list_append(list, val);
        }
        entry = entry->order_next;
    }
    return list;
}

static inline SAFEList* safe_map_values_int(SAFEMap* map) {
    SAFEList* list = safe_list_new();
    if (!map) return list;
    SAFEMapEntry* entry = map->head;
    while (entry) {
        int64_t* val = (int64_t*)safe_arena_alloc(sizeof(int64_t));
        *val = entry->value.int_val;
        safe_list_append(list, val);
        entry = entry->order_next;
    }
    return list;
}

static inline SAFEList* safe_map_values_float(SAFEMap* map) {
    SAFEList* list = safe_list_new();
    if (!map) return list;
    SAFEMapEntry* entry = map->head;
    while (entry) {
        double* val = (double*)safe_arena_alloc(sizeof(double));
        *val = entry->value.float_val;
        safe_list_append(list, val);
        entry = entry->order_next;
    }
    return list;
}

static inline SAFEList* safe_map_values_str(SAFEMap* map) {
    SAFEList* list = safe_list_new();
    if (!map) return list;
    SAFEMapEntry* entry = map->head;
    while (entry) {
        safe_list_append(list, safe_arena_strdup(entry->value.string_val));
        entry = entry->order_next;
    }
    return list;
}

static inline SAFEList* safe_map_values_bool(SAFEMap* map) {
    SAFEList* list = safe_list_new();
    if (!map) return list;
    SAFEMapEntry* entry = map->head;
    while (entry) {
        int64_t* val = (int64_t*)safe_arena_alloc(sizeof(int64_t));
        *val = (int64_t)entry->value.bool_val;
        safe_list_append(list, val);
        entry = entry->order_next;
    }
    return list;
}

static inline SAFEList* safe_map_values_ptr(SAFEMap* map) {
    SAFEList* list = safe_list_new();
    if (!map) return list;
    SAFEMapEntry* entry = map->head;
    while (entry) {
        safe_list_append(list, entry->value.ptr_val);
        entry = entry->order_next;
    }
    return list;
}

/* ===== IO Functions ===== */

static inline void safe_println_str(const char* s) { printf("%s\n", s); }
static inline void safe_println_int(int64_t v) { printf("%lld\n", (long long)v); }
static inline void safe_println_uint(uint64_t v) { printf("%llu\n", (unsigned long long)v); }
static inline void safe_println_float(double v) { printf("%g\n", v); }
static inline void safe_println_bool(bool v) { printf("%s\n", v ? "true" : "false"); }

static inline void safe_print_str(const char* s) { printf("%s", s); }
static inline void safe_print_int(int64_t v) { printf("%lld", (long long)v); }
static inline void safe_print_uint(uint64_t v) { printf("%llu", (unsigned long long)v); }
static inline void safe_print_float(double v) { printf("%g", v); }
static inline void safe_print_bool(bool v) { printf("%s", v ? "true" : "false"); }

/* ===== String Functions ===== */

static inline char* safe_string_concat(const char* a, const char* b) {
    if (!a) a = "";
    if (!b) b = "";
    size_t la = strlen(a);
    size_t lb = strlen(b);
    char* result = (char*)safe_arena_alloc(la + lb + 1);
    memcpy(result, a, la);
    memcpy(result + la, b, lb);
    result[la + lb] = '\0';
    return result;
}

static inline int64_t safe_string_len(const char* s) {
    return s ? (int64_t)strlen(s) : 0;
}

static inline char* safe_substring(const char* s, int64_t start, int64_t end) {
    int64_t slen = s ? (int64_t)strlen(s) : 0;
    if (start < 0 || end < 0 || start > slen || end > slen || start > end) {
        fprintf(stderr, "Substring bounds out of range: start=%lld, end=%lld, length=%lld\n",
                (long long)start, (long long)end, (long long)slen);
        exit(1);
    }
    int64_t length = end - start;
    char* result = (char*)safe_arena_alloc(length + 1);
    strncpy(result, s + start, length);
    result[length] = '\0';
    return result;
}

static inline int64_t safe_indexof(const char* s, const char* target) {
    const char* found = strstr(s, target);
    return found ? (int64_t)(found - s) : -1;
}

static inline char* safe_charat(const char* s, int64_t index) {
    int64_t slen = s ? (int64_t)strlen(s) : 0;
    if (index < 0 || index >= slen) { fprintf(stderr, "String index out of bounds: %lld (length %lld)\n", (long long)index, (long long)slen); exit(1); }
    char* result = (char*)safe_arena_alloc(2);
    result[0] = s[index];
    result[1] = '\0';
    return result;
}

static inline char* safe_trim(const char* s) {
    while (*s == ' ' || *s == '\t' || *s == '\n' || *s == '\r') s++;
    if (*s == '\0') return safe_arena_strdup("");
    const char* end = s + strlen(s) - 1;
    while (end > s && (*end == ' ' || *end == '\t' || *end == '\n' || *end == '\r')) end--;
    size_t length = end - s + 1;
    char* result = (char*)safe_arena_alloc(length + 1);
    strncpy(result, s, length);
    result[length] = '\0';
    return result;
}

static inline char* safe_upper(const char* s) {
    size_t length = strlen(s);
    char* result = (char*)safe_arena_alloc(length + 1);
    for (size_t i = 0; i <= length; i++)
        result[i] = (s[i] >= 'a' && s[i] <= 'z') ? s[i] - 32 : s[i];
    return result;
}

static inline char* safe_lower(const char* s) {
    size_t length = strlen(s);
    char* result = (char*)safe_arena_alloc(length + 1);
    for (size_t i = 0; i <= length; i++)
        result[i] = (s[i] >= 'A' && s[i] <= 'Z') ? s[i] + 32 : s[i];
    return result;
}

static inline char* safe_string_reverse(const char* s) {
    size_t length = strlen(s);
    char* result = (char*)safe_arena_alloc(length + 1);
    for (size_t i = 0; i < length; i++) {
        result[i] = s[length - 1 - i];
    }
    result[length] = '\0';
    return result;
}

static inline char* safe_replace(const char* s, const char* target, const char* replacement) {
    size_t tlen = strlen(target);
    size_t rlen = strlen(replacement);
    size_t slen = strlen(s);
    if (tlen == 0) {
        char* copy = (char*)safe_arena_alloc(slen + 1);
        strcpy(copy, s);
        return copy;
    }
    size_t alloc = slen * (rlen + 1) + 1;
    if (rlen + 1 != 0 && alloc / (rlen + 1) < slen) alloc = slen + 1;  /* overflow guard */
    char* result = (char*)safe_arena_alloc(alloc);
    char* out = result;
    while (*s) {
        if (strncmp(s, target, tlen) == 0) {
            memcpy(out, replacement, rlen);
            out += rlen;
            s += tlen;
        } else {
            *out++ = *s++;
        }
    }
    *out = '\0';
    return result;
}

static inline bool safe_starts(const char* s, const char* prefix) {
    return strncmp(s, prefix, strlen(prefix)) == 0;
}

static inline bool safe_ends(const char* s, const char* suffix) {
    size_t slen = strlen(s);
    size_t suflen = strlen(suffix);
    if (suflen > slen) return false;
    return strcmp(s + slen - suflen, suffix) == 0;
}

static inline SAFEList* safe_string_chars(const char* s) {
    SAFEList* list = safe_list_new();
    if (!s) return list;
    size_t length = strlen(s);
    for (size_t i = 0; i < length; i++) {
        char* ch = (char*)safe_arena_alloc(2);
        ch[0] = s[i];
        ch[1] = '\0';
        safe_list_append(list, ch);
    }
    return list;
}

static inline SAFEList* safe_string_split(const char* s, const char* delim) {
    SAFEList* list = safe_list_new();
    if (!s || !delim) return list;
    size_t dlen = strlen(delim);
    if (dlen == 0) return list;
    const char* start = s;
    const char* found;
    while ((found = strstr(start, delim)) != NULL) {
        size_t length = found - start;
        char* part = (char*)safe_arena_alloc(length + 1);
        strncpy(part, start, length);
        part[length] = '\0';
        safe_list_append(list, part);
        start = found + dlen;
    }
    safe_list_append(list, safe_arena_strdup(start));
    return list;
}

static inline char* safe_string_join(SAFEList* list, const char* sep) {
    if (!list || list->length == 0) return safe_arena_strdup("");
    size_t total = 0;
    size_t seplen = sep ? strlen(sep) : 0;
    for (int64_t i = 0; i < list->length; i++) {
        total += strlen((char*)((void**)list->data)[i]);
        if (i > 0) total += seplen;
    }
    char* result = (char*)safe_arena_alloc(total + 1);
    size_t offset = 0;
    for (int64_t i = 0; i < list->length; i++) {
        if (i > 0 && sep) { memcpy(result + offset, sep, seplen); offset += seplen; }
        size_t len = strlen((char*)((void**)list->data)[i]);
        memcpy(result + offset, (char*)((void**)list->data)[i], len);
        offset += len;
    }
    result[offset] = '\0';
    return result;
}

/* ===== Conversion Functions ===== */

static inline char* safe_string_val(int64_t num) {
    char* str = (char*)safe_arena_alloc(64);
    snprintf(str, 64, "%lld", (long long)num);
    return str;
}

/* Render a double exactly as the interpreter does (Java's Double.toString):
 * shortest decimal that round-trips, always with a fractional digit, switching
 * to "d.dddEnn" scientific form outside [1e-3, 1e7). Finds the minimal
 * significant-digit count whose "%.*e" rendering parses back to the same
 * double, then reformats per Java's rules. */
static inline char* safe_double_to_string(double value) {
    if (isnan(value)) return safe_arena_strdup("NaN");
    if (isinf(value)) return safe_arena_strdup(value < 0 ? "-Infinity" : "Infinity");
    if (value == 0.0) return safe_arena_strdup(signbit(value) ? "-0.0" : "0.0");

    char buf[40];
    int prec;
    for (prec = 1; prec <= 17; prec++) {
        snprintf(buf, sizeof(buf), "%.*e", prec - 1, value);
        if (strtod(buf, NULL) == value) break;
    }
    /* Parse buf: [-]d[.ddd]e[+-]NN into sign, significant digits, exponent. */
    const char* p = buf;
    int neg = 0;
    if (*p == '-') { neg = 1; p++; }
    char digs[40];
    int nd = 0;
    digs[nd++] = *p++;
    if (*p == '.') { p++; while (*p >= '0' && *p <= '9') digs[nd++] = *p++; }
    p++; /* skip 'e' */
    int esign = 1;
    if (*p == '+') p++; else if (*p == '-') { esign = -1; p++; }
    int exp = atoi(p) * esign;
    digs[nd] = '\0';

    char out[80];
    int o = 0;
    if (neg) out[o++] = '-';
    if (exp >= -3 && exp <= 6) {
        if (exp >= 0) {
            int intdigits = exp + 1;
            for (int i = 0; i < intdigits; i++) out[o++] = (i < nd) ? digs[i] : '0';
            out[o++] = '.';
            if (nd > intdigits) { for (int i = intdigits; i < nd; i++) out[o++] = digs[i]; }
            else out[o++] = '0';
        } else {
            out[o++] = '0'; out[o++] = '.';
            for (int i = 0; i < (-exp - 1); i++) out[o++] = '0';
            for (int i = 0; i < nd; i++) out[o++] = digs[i];
        }
    } else {
        out[o++] = digs[0];
        out[o++] = '.';
        if (nd > 1) { for (int i = 1; i < nd; i++) out[o++] = digs[i]; }
        else out[o++] = '0';
        out[o++] = 'E';
        o += sprintf(out + o, "%d", exp);
    }
    out[o] = '\0';
    char* result = (char*)safe_arena_alloc((size_t)o + 1);
    memcpy(result, out, (size_t)o + 1);
    return result;
}

static inline char* safe_string_val_float(double num) {
    return safe_double_to_string(num);
}

static inline char* safe_string_val_bool(bool b) {
    return safe_arena_strdup(b ? "true" : "false");
}

static inline int64_t safe_int_val(const char* str) {
    return strtoll(str, NULL, 10);
}

static inline double safe_float_val(const char* str) {
    return strtod(str, NULL);
}

/* Render a list as "[e0, e1, ...]", matching the interpreter's formatting
 * (SAFEValue.join): elements separated by ", ", wrapped in square brackets,
 * strings unquoted. The element `kind` is supplied by the code generator from
 * the static element type — the runtime cannot recover it for scalar lists,
 * whose elements are boxed as bare int64_t/double or stored (strings) as char*.
 * kind: 0=int, 1=float, 2=string, 3=bool, 4=uint. */
static inline char* safe_list_to_string(SAFEList* list, int kind) {
    if (!list || list->length == 0) return safe_arena_strdup("[]");
    int64_t n = list->length;
    char** parts = (char**)malloc((size_t)n * sizeof(char*));
    size_t total = 2; /* '[' and ']' */
    for (int64_t i = 0; i < n; i++) {
        void* slot = ((void**)list->data)[i];
        char* part;
        switch (kind) {
            case 1: part = safe_string_val_float(*(double*)slot); break;
            case 2: part = (char*)slot; break;
            case 3: part = safe_string_val_bool(*(int64_t*)slot != 0); break;
            case 4:
                part = (char*)safe_arena_alloc(64);
                snprintf(part, 64, "%llu", (unsigned long long)*(uint64_t*)slot);
                break;
            default: part = safe_string_val(*(int64_t*)slot); break;
        }
        parts[i] = part;
        total += strlen(part);
        if (i > 0) total += 2; /* ", " */
    }
    char* result = (char*)safe_arena_alloc(total + 1);
    size_t offset = 0;
    result[offset++] = '[';
    for (int64_t i = 0; i < n; i++) {
        if (i > 0) { result[offset++] = ','; result[offset++] = ' '; }
        size_t len = strlen(parts[i]);
        memcpy(result + offset, parts[i], len);
        offset += len;
    }
    result[offset++] = ']';
    result[offset] = '\0';
    free(parts);
    return result;
}

static inline char* safe_string_val_uint(uint64_t num) {
    char* str = (char*)safe_arena_alloc(64);
    snprintf(str, 64, "%llu", (unsigned long long)num);
    return str;
}

/* Join `count` already-rendered string parts with `sep`, wrapped in `open`/`close`.
 * Used by the generated recursive stringifiers for lists, tuples, sets, and maps. */
static inline char* safe_join(const char** parts, int count,
                              const char* open, const char* sep, const char* close) {
    size_t seplen = strlen(sep);
    size_t total = strlen(open) + strlen(close);
    for (int i = 0; i < count; i++) {
        total += strlen(parts[i]);
        if (i > 0) total += seplen;
    }
    char* result = (char*)safe_arena_alloc(total + 1);
    size_t offset = 0;
    size_t len = strlen(open);
    memcpy(result + offset, open, len); offset += len;
    for (int i = 0; i < count; i++) {
        if (i > 0) { memcpy(result + offset, sep, seplen); offset += seplen; }
        len = strlen(parts[i]);
        memcpy(result + offset, parts[i], len); offset += len;
    }
    len = strlen(close);
    memcpy(result + offset, close, len); offset += len;
    result[offset] = '\0';
    return result;
}

/* Concatenate `count` string parts with no separator. Used by the generated
 * struct/enum stringifiers, which interleave field labels with rendered values. */
static inline char* safe_concat(const char** parts, int count) {
    return safe_join(parts, count, "", "", "");
}

static inline bool safe_bool_val(const char* str) {
    return str && (str[0] == 't' || str[0] == 'T' || strcmp(str, "1") == 0);
}

/* ===== Math Functions ===== */

static inline double safe_min_float(double a, double b) { return a < b ? a : b; }
static inline double safe_max_float(double a, double b) { return a > b ? a : b; }
static inline int64_t safe_min_int(int64_t a, int64_t b) { return a < b ? a : b; }
static inline int64_t safe_max_int(int64_t a, int64_t b) { return a > b ? a : b; }

/* Generic min/max for backward compatibility (double) */
static inline double safe_min(double a, double b) { return a < b ? a : b; }
static inline double safe_max(double a, double b) { return a > b ? a : b; }

/* ===== Range Function ===== */

static inline SAFEList* safe_range(int64_t start, int64_t end) {
    SAFEList* list = safe_list_new();
    for (int64_t i = start; i < end; i++) {
        int64_t* val = (int64_t*)safe_arena_alloc(sizeof(int64_t));
        *val = i;
        safe_list_append(list, val);
    }
    return list;
}

#define SAFE_MAX_LIST_SIZE 10000000

static inline void safe_range_overflow(void) {
    fprintf(stderr, "range size exceeds maximum of %d\n", SAFE_MAX_LIST_SIZE);
    exit(1);
}

/* Guarded range construction, mirroring runtime/RangeSemantics: cap at SAFE_MAX_LIST_SIZE
 * and detect signed overflow on the step so an extreme span traps instead of wrapping. */
static inline SAFEList* safe_range_step(int64_t start, int64_t end, int64_t step) {
    if (step == 0) { fprintf(stderr, "Range step cannot be zero\n"); exit(1); }
    SAFEList* list = safe_list_new();
    if ((step > 0 && start > end) || (step < 0 && start < end)) return list;
    int64_t size = 0;
    for (int64_t i = start; (step > 0) ? (i <= end) : (i >= end); ) {
        if (size >= SAFE_MAX_LIST_SIZE) safe_range_overflow();
        int64_t* val = (int64_t*)safe_arena_alloc(sizeof(int64_t));
        *val = i;
        safe_list_append(list, val);
        size++;
        int64_t next;
        if (__builtin_add_overflow(i, step, &next)) break;
        i = next;
    }
    return list;
}

static inline SAFEList* safe_range_inclusive(int64_t start, int64_t end) {
    return safe_range_step(start, end, 1);
}

/* ===== Time Function ===== */

static inline int64_t safe_time(void) {
    struct timespec ts;
    clock_gettime(CLOCK_REALTIME, &ts);
    return (int64_t)ts.tv_sec * 1000 + (int64_t)ts.tv_nsec / 1000000;
}

/* ===== Host identity (OS, ARCH, OS_VERSION, PLATFORM) =====
 * Resolved at C runtime via uname(2), cached on first call. Mirrors the interpreter and
 * bytecode VM which read these from BuiltinRegistry.variables() at JVM startup. Before this
 * change, CCodeGenerator hardcoded System.getProperty("os.name") at codegen time, so a binary
 * compiled on Linux reported "Linux" even when run on macOS.
 */
static inline const char* safe_host(const int which) {
    static char os[128] = {0};
    static char arch[128] = {0};
    static char version[128] = {0};
    static int cached = 0;
    if (!cached) {
        struct utsname info;
        if (uname(&info) == 0) {
            snprintf(os, sizeof(os), "%s", info.sysname);
            snprintf(arch, sizeof(arch), "%s", info.machine);
            snprintf(version, sizeof(version), "%s", info.release);
        } else {
            snprintf(os, sizeof(os), "unknown");
            snprintf(arch, sizeof(arch), "unknown");
            snprintf(version, sizeof(version), "unknown");
        }
        cached = 1;
    }
    switch (which) {
        case 0: return os;
        case 1: return arch;
        case 2: return version;
        default: return os;
    }
}

static inline const char* safe_os(void) { return safe_host(0); }
static inline const char* safe_arch(void) { return safe_host(1); }
static inline const char* safe_osversion(void) { return safe_host(2); }
static inline const char* safe_platform(void) { return safe_host(0); }

/* ===== Heap struct helper for tuple storage ===== */

static inline void* safe_heap_struct(const void* data, size_t size) {
    void* p = malloc(size);
    memcpy(p, data, size);
    return p;
}

/* ===== SAFEValue Union (for mixed-type containers) ===== */

typedef union {
    int64_t int_val;
    uint64_t uint_val;
    double float_val;
    char* string_val;
    bool bool_val;
    SAFEList* list_val;
    SAFEMap* map_val;
    void* ptr_val;
} SAFEValue;

/* ===== Tuple Support ===== */

#define SAFE_MAX_TUPLE_SIZE 64

typedef struct {
    int count;
    SAFEValue elements[SAFE_MAX_TUPLE_SIZE];
} SAFETuple;

static inline SAFETuple safe_tuple_new(int count) {
    if (count > SAFE_MAX_TUPLE_SIZE) {
        fprintf(stderr, "Tuple size %d exceeds maximum of %d\n", count, SAFE_MAX_TUPLE_SIZE);
        exit(1);
    }
    SAFETuple tuple;
    memset(&tuple, 0, sizeof(SAFETuple));
    tuple.count = count;
    return tuple;
}

/* ===== Set Support ===== */

typedef struct {
    SAFEValue* data;
    int64_t length;
    int64_t capacity;
    int tag; /* 0=int, 1=float, 2=string, 3=bool */
} SAFESet;

static inline int safe_value_equals(SAFEValue a, SAFEValue b, int tag) {
    switch (tag) {
        case 0: return a.int_val == b.int_val;
        case 1: return a.float_val == b.float_val;
        case 2: return a.string_val && b.string_val && strcmp(a.string_val, b.string_val) == 0;
        case 3: return a.bool_val == b.bool_val;
        default: return a.int_val == b.int_val;
    }
}

static inline SAFESet* safe_set_new(void) {
    SAFESet* set = (SAFESet*)safe_alloc(sizeof(SAFESet), SAFE_KIND_SET, 0);
    set->capacity = 8;
    set->length = 0;
    set->tag = 0;
    set->data = (SAFEValue*)malloc(set->capacity * sizeof(SAFEValue));
    return set;
}

/* Phase 5: typed set. meta = element kind; dispose releases each heap
 * element when the set is freed. */
static inline SAFESet* safe_set_new_typed(uint8_t kind) {
    SAFESet* set = (SAFESet*)safe_alloc(sizeof(SAFESet), SAFE_KIND_SET, kind);
    set->capacity = 8;
    set->length = 0;
    set->tag = 0;
    set->data = (SAFEValue*)malloc(set->capacity * sizeof(SAFEValue));
    return set;
}

static inline int safe_set_contains(SAFESet* set, SAFEValue value) {
    if (!set) return 0;
    for (int64_t i = 0; i < set->length; i++) {
        if (safe_value_equals(set->data[i], value, set->tag)) return 1;
    }
    return 0;
}

static inline void safe_set_add_mut(SAFESet* set, SAFEValue value) {
    if (safe_set_contains(set, value)) return;
    if (set->length >= set->capacity) {
        set->capacity *= 2;
        set->data = (SAFEValue*)realloc(set->data, set->capacity * sizeof(SAFEValue));
    }
    set->data[set->length++] = value;
}

static inline SAFESet* safe_set_add(SAFESet* set, SAFEValue value) {
    SAFESet* result = safe_set_new();
    result->tag = set->tag;
    for (int64_t i = 0; i < set->length; i++) {
        safe_set_add_mut(result, set->data[i]);
    }
    safe_set_add_mut(result, value);
    return result;
}

static inline int64_t safe_set_len(SAFESet* set) {
    return set ? set->length : 0;
}

static inline SAFESet* safe_set_union(SAFESet* a, SAFESet* b) {
    SAFESet* result = safe_set_new();
    result->tag = a ? a->tag : (b ? b->tag : 0);
    if (a) for (int64_t i = 0; i < a->length; i++) safe_set_add_mut(result, a->data[i]);
    if (b) for (int64_t i = 0; i < b->length; i++) safe_set_add_mut(result, b->data[i]);
    return result;
}

static inline SAFESet* safe_set_intersect(SAFESet* a, SAFESet* b) {
    SAFESet* result = safe_set_new();
    result->tag = a ? a->tag : 0;
    if (!a || !b) return result;
    for (int64_t i = 0; i < a->length; i++) {
        if (safe_set_contains(b, a->data[i])) {
            safe_set_add_mut(result, a->data[i]);
        }
    }
    return result;
}

static inline SAFESet* safe_set_difference(SAFESet* a, SAFESet* b) {
    SAFESet* result = safe_set_new();
    result->tag = a ? a->tag : 0;
    if (!a) return result;
    for (int64_t i = 0; i < a->length; i++) {
        if (!b || !safe_set_contains(b, a->data[i])) {
            safe_set_add_mut(result, a->data[i]);
        }
    }
    return result;
}

/* ===== Closure Support ===== */

typedef struct {
    void* fn;
    void* context;
    int arity;
    /* Heap-capture bitmap: bit N set = context[N] is a heap-RC pointer
     * that dispose_closure must release on teardown. 16 bits match the
     * widened SAFEHeader.meta so safe_closure_box can propagate it
     * straight into the header. */
    uint16_t meta;
} SAFEClosure;

static inline SAFEClosure safe_closure_new(void* fn, void* context, int arity) {
    SAFEClosure closure;
    closure.fn = fn;
    closure.context = context;
    closure.arity = arity;
    closure.meta = 0;
    return closure;
}

/* Build a closure value together with its heap-capture bitmap. Emitted by
 * CLambdaCompiler whenever at least one captured variable is heap-RC. */
static inline SAFEClosure safe_closure_new_meta(void* fn, void* context, int arity, uint16_t meta) {
    SAFEClosure closure;
    closure.fn = fn;
    closure.context = context;
    closure.arity = arity;
    closure.meta = meta;
    return closure;
}

static inline SAFEClosure* safe_closure_box(SAFEClosure closure) {
    SAFEClosure* boxed = (SAFEClosure*)safe_alloc(sizeof(SAFEClosure), SAFE_KIND_CLOSURE, closure.meta);
    *boxed = closure;
    return boxed;
}

/* ===== Regex Support ===== */

/* Translate common regex shortcuts (\s, \d, \w, \S, \D, \W) to POSIX character classes */
static inline char* safe_regex_translate(const char* pattern) {
    size_t len = strlen(pattern);
    size_t capacity = len * 4 + 1;
    char* result = (char*)malloc(capacity);
    size_t offset = 0;
    for (size_t i = 0; i < len; i++) {
        if (pattern[i] == '\\' && i + 1 < len) {
            const char* replacement = NULL;
            switch (pattern[i + 1]) {
                case 's': replacement = "[[:space:]]"; break;
                case 'S': replacement = "[^[:space:]]"; break;
                case 'd': replacement = "[[:digit:]]"; break;
                case 'D': replacement = "[^[:digit:]]"; break;
                case 'w': replacement = "[[:alnum:]_]"; break;
                case 'W': replacement = "[^[:alnum:]_]"; break;
            }
            if (replacement) {
                size_t rlen = strlen(replacement);
                if (offset + rlen >= capacity) {
                    capacity = (offset + rlen) * 2;
                    result = (char*)realloc(result, capacity);
                }
                memcpy(result + offset, replacement, rlen);
                offset += rlen;
                i++;
                continue;
            }
        }
        if (offset + 1 >= capacity) {
            capacity *= 2;
            result = (char*)realloc(result, capacity);
        }
        result[offset++] = pattern[i];
    }
    result[offset] = '\0';
    return result;
}

static inline bool safe_regex_matches(const char* s, const char* pattern) {
    char* translated = safe_regex_translate(pattern);
    regex_t regex;
    if (regcomp(&regex, translated, REG_EXTENDED | REG_NOSUB) != 0) { free(translated); return false; }
    int result = regexec(&regex, s, 0, NULL, 0);
    regfree(&regex);
    free(translated);
    return result == 0;
}

static inline SAFEList* safe_regex_find(const char* s, const char* pattern) {
    SAFEList* list = safe_list_new();
    char* translated = safe_regex_translate(pattern);
    regex_t regex;
    if (regcomp(&regex, translated, REG_EXTENDED) != 0) { free(translated); return list; }
    regmatch_t match;
    const char* cursor = s;
    while (regexec(&regex, cursor, 1, &match, 0) == 0) {
        int64_t len = match.rm_eo - match.rm_so;
        char* found = (char*)safe_arena_alloc(len + 1);
        memcpy(found, cursor + match.rm_so, len);
        found[len] = '\0';
        safe_list_append(list, found);
        cursor += match.rm_eo;
        if (match.rm_eo == match.rm_so) cursor++;
        if (*cursor == '\0') break;
    }
    regfree(&regex);
    free(translated);
    return list;
}

static inline char* safe_regex_replace(const char* s, const char* pattern, const char* replacement) {
    char* translated = safe_regex_translate(pattern);
    regex_t regex;
    if (regcomp(&regex, translated, REG_EXTENDED) != 0) {
        free(translated);
        return safe_arena_strdup(s);
    }
    size_t capacity = strlen(s) * 2 + 1;
    char* result = (char*)malloc(capacity);
    result[0] = '\0';
    size_t offset = 0;
    regmatch_t match;
    const char* cursor = s;
    while (regexec(&regex, cursor, 1, &match, 0) == 0) {
        size_t prefix = match.rm_so;
        size_t needed = offset + prefix + strlen(replacement) + 1;
        if (needed > capacity) {
            capacity = needed * 2;
            result = (char*)realloc(result, capacity);
        }
        memcpy(result + offset, cursor, prefix);
        offset += prefix;
        memcpy(result + offset, replacement, strlen(replacement));
        offset += strlen(replacement);
        result[offset] = '\0';
        cursor += match.rm_eo;
        if (match.rm_eo == match.rm_so) {
            if (*cursor == '\0') break;
            if (offset + 2 > capacity) {
                capacity *= 2;
                result = (char*)realloc(result, capacity);
            }
            result[offset++] = *cursor++;
            result[offset] = '\0';
        }
    }
    size_t remaining = strlen(cursor);
    if (offset + remaining + 1 > capacity) {
        capacity = offset + remaining + 1;
        result = (char*)realloc(result, capacity);
    }
    memcpy(result + offset, cursor, remaining);
    offset += remaining;
    result[offset] = '\0';
    regfree(&regex);
    free(translated);
    char* arena_result = safe_arena_strdup(result);
    free(result);
    return arena_result;
}

/* ===== Directory Support ===== */

static inline SAFEList* safe_listdir(const char* path) {
    SAFEList* list = safe_list_new();
    DIR* dir = opendir(path);
    if (!dir) return list;
    struct dirent* entry;
    while ((entry = readdir(dir)) != NULL) {
        if (strcmp(entry->d_name, ".") == 0 || strcmp(entry->d_name, "..") == 0) continue;
        safe_list_append(list, safe_arena_strdup(entry->d_name));
    }
    closedir(dir);
    return list;
}

static inline bool safe_mkdir(const char* path) {
    return mkdir(path, 0755) == 0;
}

static inline bool safe_rmdir(const char* path) {
    return rmdir(path) == 0;
}

static inline bool safe_isdir(const char* path) {
    struct stat info;
    if (stat(path, &info) != 0) return false;
    return S_ISDIR(info.st_mode);
}

// ======================== Input ========================

static inline char* safe_input(const char* prompt) {
    if (prompt && prompt[0]) printf("%s", prompt);
    char buffer[4096];
    if (fgets(buffer, sizeof(buffer), stdin) != NULL) {
        size_t len = strlen(buffer);
        if (len > 0 && buffer[len-1] == '\n') buffer[len-1] = '\0';
        return safe_arena_strdup(buffer);
    }
    return safe_arena_strdup("");
}

// ======================== Args ========================

static int __safe_argc = 0;
static char** __safe_argv = NULL;

static inline void safe_init_args(int argc, char** argv) {
    __safe_argc = argc;
    __safe_argv = argv;
}

static inline SAFEList* safe_args(void) {
    SAFEList* list = safe_list_new();
    for (int i = 1; i < __safe_argc; i++) {
        safe_list_append(list, safe_arena_strdup(__safe_argv[i]));
    }
    return list;
}

/* ===== Bytes Support ===== */

typedef struct {
    uint8_t* data;
    int64_t length;
} SAFEBytes;

static inline SAFEBytes* safe_bytes_new(int64_t length) {
    SAFEBytes* b = (SAFEBytes*)safe_alloc(sizeof(SAFEBytes), SAFE_KIND_BYTES, 0);
    b->length = length;
    b->data = (uint8_t*)calloc(length, 1);
    return b;
}

static inline int64_t safe_bytes_get(SAFEBytes* b, int64_t index) {
    if (index < 0 || index >= b->length) {
        fprintf(stderr, "bget: index out of bounds %lld (length %lld)\n", (long long)index, (long long)b->length);
        exit(1);
    }
    return b->data[index];
}

static inline SAFEBytes* safe_bytes_set(SAFEBytes* b, int64_t index, int64_t value) {
    if (index < 0 || index >= b->length) {
        fprintf(stderr, "bset: index out of bounds\n"); exit(1);
    }
    SAFEBytes* result = safe_bytes_new(b->length);
    memcpy(result->data, b->data, b->length);
    result->data[index] = (uint8_t)(value & 0xFF);
    return result;
}

static inline SAFEBytes* safe_bytes_slice(SAFEBytes* b, int64_t start, int64_t end) {
    if (start < 0 || end < start || end > b->length) {
        fprintf(stderr, "bslice: invalid range\n"); exit(1);
    }
    int64_t len = end - start;
    SAFEBytes* result = safe_bytes_new(len);
    memcpy(result->data, b->data + start, len);
    return result;
}

static inline SAFEBytes* safe_bytes_concat(SAFEBytes* a, SAFEBytes* b) {
    SAFEBytes* result = safe_bytes_new(a->length + b->length);
    memcpy(result->data, a->data, a->length);
    memcpy(result->data + a->length, b->data, b->length);
    return result;
}

static inline int64_t safe_bytes_len(SAFEBytes* b) {
    return b ? b->length : 0;
}

static inline SAFEBytes* safe_bytes_encode(const char* s) {
    int64_t len = strlen(s);
    SAFEBytes* b = safe_bytes_new(len);
    memcpy(b->data, s, len);
    return b;
}

static inline char* safe_bytes_decode(SAFEBytes* b) {
    char* s = (char*)malloc(b->length + 1);
    memcpy(s, b->data, b->length);
    s[b->length] = '\0';
    return s;
}

static inline SAFEBytes* safe_bytes_pack(int64_t value, int64_t width) {
    SAFEBytes* b = safe_bytes_new(width);
    for (int64_t i = width - 1; i >= 0; i--) {
        b->data[i] = (uint8_t)(value >> ((width - 1 - i) * 8));
    }
    return b;
}

static inline int64_t safe_bytes_unpack(SAFEBytes* b, int64_t offset, int64_t width) {
    if (offset < 0 || width < 0 || width > b->length || offset > b->length - width) {
        fprintf(stderr, "unpack: out of bounds\n"); exit(1);
    }
    int64_t result = 0;
    for (int64_t i = 0; i < width; i++) {
        result = (result << 8) | b->data[offset + i];
    }
    return result;
}

static inline SAFEBytes* safe_bytes_patch(SAFEBytes* b, int64_t offset, SAFEBytes* patch) {
    if (offset < 0 || offset + patch->length > b->length) {
        fprintf(stderr, "bpatch: out of bounds\n"); exit(1);
    }
    SAFEBytes* result = safe_bytes_new(b->length);
    memcpy(result->data, b->data, b->length);
    memcpy(result->data + offset, patch->data, patch->length);
    return result;
}

static inline int64_t safe_bytes_compare(SAFEBytes* a, SAFEBytes* b) {
    int64_t min = a->length < b->length ? a->length : b->length;
    for (int64_t i = 0; i < min; i++) {
        if (a->data[i] != b->data[i]) return a->data[i] < b->data[i] ? -1 : 1;
    }
    if (a->length != b->length) return a->length < b->length ? -1 : 1;
    return 0;
}

static inline char* safe_bytes_hex(SAFEBytes* b) {
    char* hex = (char*)malloc(b->length * 2 + 1);
    for (int64_t i = 0; i < b->length; i++) {
        sprintf(hex + i * 2, "%02x", b->data[i]);
    }
    hex[b->length * 2] = '\0';
    return hex;
}

/* ===== Binary File I/O ===== */

#define SAFE_MAX_BHANDLES 256
static FILE* __safe_bhandles[SAFE_MAX_BHANDLES];
static int __safe_next_bhandle = 0;

static inline int64_t safe_bopen(const char* path, const char* mode) {
    const char* fmode = "rb";
    if (strcmp(mode, "w") == 0) fmode = "w+b";
    else if (strcmp(mode, "rw") == 0) fmode = "r+b";
    FILE* f = fopen(path, fmode);
    if (!f && strcmp(mode, "rw") == 0) f = fopen(path, "w+b");
    if (!f) { fprintf(stderr, "bopen: cannot open %s\n", path); exit(1); }
    /* Reuse closed handles first */
    int id = -1;
    for (int i = 0; i < __safe_next_bhandle && i < SAFE_MAX_BHANDLES; i++) {
        if (__safe_bhandles[i] == NULL) { id = i; break; }
    }
    if (id < 0) {
        if (__safe_next_bhandle >= SAFE_MAX_BHANDLES) {
            fprintf(stderr, "bopen: too many open files (max %d)\n", SAFE_MAX_BHANDLES);
            fclose(f);
            exit(1);
        }
        id = __safe_next_bhandle++;
    }
    __safe_bhandles[id] = f;
    return id;
}

static inline void safe_bclose(int64_t handle) {
    if (handle >= 0 && handle < SAFE_MAX_BHANDLES && __safe_bhandles[handle]) {
        fclose(__safe_bhandles[handle]);
        __safe_bhandles[handle] = NULL;
    }
}

static inline FILE* safe_bhandle(int64_t handle) {
    if (handle < 0 || handle >= SAFE_MAX_BHANDLES || !__safe_bhandles[handle]) {
        fprintf(stderr, "Invalid binary file handle: %lld\n", (long long)handle);
        exit(1);
    }
    return __safe_bhandles[handle];
}

static inline SAFEBytes* safe_bread(int64_t handle, int64_t count) {
    FILE* f = safe_bhandle(handle);
    SAFEBytes* b = safe_bytes_new(count);
    size_t read = fread(b->data, 1, count, f);
    b->length = read;
    return b;
}

static inline int64_t safe_bwrite(int64_t handle, SAFEBytes* data) {
    return fwrite(data->data, 1, data->length, safe_bhandle(handle));
}

static inline void safe_bseek(int64_t handle, int64_t offset) {
    fseek(safe_bhandle(handle), offset, SEEK_SET);
}

static inline int64_t safe_bsize(const char* path) {
    FILE* f = fopen(path, "rb");
    if (!f) return 0;
    fseek(f, 0, SEEK_END);
    int64_t size = ftell(f);
    fclose(f);
    return size;
}

static inline void safe_bflush(int64_t handle) {
    if (handle >= 0 && handle < SAFE_MAX_BHANDLES && __safe_bhandles[handle]) {
        fflush(__safe_bhandles[handle]);
    }
}

/* ===== Hash Functions ===== */

static inline int64_t safe_hash_fnv(SAFEBytes* b) {
    uint64_t hash = 0xcbf29ce484222325ULL;
    for (int64_t i = 0; i < b->length; i++) {
        hash ^= b->data[i];
        hash *= 0x100000001b3ULL;
    }
    return (int64_t)hash;
}

static inline int64_t safe_hash_crc(SAFEBytes* b) {
    uint32_t crc = 0xFFFFFFFF;
    for (int64_t i = 0; i < b->length; i++) {
        crc ^= b->data[i];
        for (int j = 0; j < 8; j++) {
            crc = (crc >> 1) ^ (0xEDB88320 & -(crc & 1));
        }
    }
    return (int64_t)(crc ^ 0xFFFFFFFF);
}

static inline int64_t safe_hash_murmur(SAFEBytes* b) {
    const uint64_t c1 = 0x87c37b91114253d5ULL;
    const uint64_t c2 = 0x4cf5ad432745937fULL;
    int64_t length = b->length;
    uint64_t h1 = 0;
    int blocks = length / 8;
    for (int i = 0; i < blocks; i++) {
        uint64_t k1 = 0;
        for (int j = 7; j >= 0; j--) {
            k1 = (k1 << 8) | b->data[i * 8 + j];
        }
        k1 *= c1;
        k1 = (k1 << 31) | (k1 >> 33);
        k1 *= c2;
        h1 ^= k1;
        h1 = (h1 << 27) | (h1 >> 37);
        h1 = h1 * 5 + 0x52dce729;
    }
    uint64_t k1 = 0;
    int tail = blocks * 8;
    switch (length - tail) {
        case 7: k1 ^= (uint64_t)b->data[tail + 6] << 48; /* fall through */
        case 6: k1 ^= (uint64_t)b->data[tail + 5] << 40; /* fall through */
        case 5: k1 ^= (uint64_t)b->data[tail + 4] << 32; /* fall through */
        case 4: k1 ^= (uint64_t)b->data[tail + 3] << 24; /* fall through */
        case 3: k1 ^= (uint64_t)b->data[tail + 2] << 16; /* fall through */
        case 2: k1 ^= (uint64_t)b->data[tail + 1] << 8;  /* fall through */
        case 1:
            k1 ^= b->data[tail];
            k1 *= c1;
            k1 = (k1 << 31) | (k1 >> 33);
            k1 *= c2;
            h1 ^= k1;
    }
    h1 ^= length;
    h1 ^= h1 >> 33;
    h1 *= 0xff51afd7ed558ccdULL;
    h1 ^= h1 >> 33;
    h1 *= 0xc4ceb9fe1a85ec53ULL;
    h1 ^= h1 >> 33;
    return (int64_t)h1;
}

/* ===== Additional builtins for storage modules ===== */

static inline char* safe_bytes_tostr(SAFEBytes* b) {
    char* s = (char*)malloc(b->length + 1);
    memcpy(s, b->data, b->length);
    s[b->length] = '\0';
    return s;
}

static inline SAFEList* safe_list_append_copy(SAFEList* list, void* element) {
    /* Phase 2 unique-owner fast path: if the caller is the sole reference
     * holder (refs == 1), mutate in place — no copy, no new allocation.
     * This is the core memory-reclamation mechanism for functional-style
     * `x = append(x, v)` loops. safe_list_append handles retain-on-insert
     * based on list meta. */
    if (list && safe_header(list)->refs == 1) {
        safe_list_append(list, element);
        return list;
    }
    /* Copy path: new list inherits the source's element-kind meta so
     * retain-on-append works identically. Existing elements are retained
     * (each append goes through the retain path above). */
    SAFEList* result = (SAFEList*)safe_alloc(sizeof(SAFEList), SAFE_KIND_LIST,
                                             list ? safe_header(list)->meta : 0);
    result->capacity = (list && list->capacity > 0) ? list->capacity : 10;
    result->length = 0;
    result->data = malloc(result->capacity * sizeof(void*));
    if (list) {
        for (int64_t i = 0; i < list->length; i++) {
            safe_list_append(result, ((void**)list->data)[i]);
        }
    }
    safe_list_append(result, element);
    return result;
}

static inline SAFEList* safe_list_append_copy_int(SAFEList* list, int64_t element) {
    if (list && safe_header(list)->refs == 1) {
        int64_t* val = (int64_t*)malloc(sizeof(int64_t));
        *val = element;
        safe_list_append(list, val);
        return list;
    }
    SAFEList* result = safe_list_new();
    for (int64_t i = 0; i < list->length; i++) {
        safe_list_append(result, ((void**)list->data)[i]);
    }
    int64_t* val = (int64_t*)malloc(sizeof(int64_t));
    *val = element;
    safe_list_append(result, val);
    return result;
}

static inline SAFEList* safe_list_sort(SAFEList* list) {
    /* Simple insertion sort on string keys */
    int64_t n = list->length;
    SAFEList* result = safe_list_new();
    for (int64_t i = 0; i < n; i++) {
        safe_list_append(result, ((void**)list->data)[i]);
    }
    for (int64_t i = 1; i < n; i++) {
        char* key = (char*)((void**)result->data)[i];
        int64_t j = i - 1;
        while (j >= 0 && strcmp((char*)((void**)result->data)[j], key) > 0) {
            ((void**)result->data)[j + 1] = ((void**)result->data)[j];
            j--;
        }
        ((void**)result->data)[j + 1] = key;
    }
    return result;
}

static inline int safe_delete(const char* path) {
    return unlink(path) == 0 ? 1 : 0;
}

static inline int safe_exists(const char* path) {
    return access(path, F_OK) == 0 ? 1 : 0;
}

static inline char* safe_getenv(const char* name) {
    const char* val = getenv(name);
    if (val == NULL) return "";
    char* result = (char*)safe_arena_alloc(strlen(val) + 1);
    strcpy(result, val);
    return result;
}

/* ===== Text File I/O Support ===== */

#define SAFE_MAX_FHANDLES 256
static FILE* __safe_fhandles[SAFE_MAX_FHANDLES];
static int __safe_next_fhandle = 0;

static inline int64_t safe_fopen(const char* path, const char* mode) {
    const char* fmode = "r";
    if (strcmp(mode, "w") == 0) fmode = "w";
    else if (strcmp(mode, "a") == 0) fmode = "a";
    else if (strcmp(mode, "rw") == 0) fmode = "r+";
    FILE* f = fopen(path, fmode);
    if (!f && strcmp(mode, "rw") == 0) f = fopen(path, "w+");
    if (!f) return -1;
    int id = __safe_next_fhandle++;
    __safe_fhandles[id] = f;
    return id;
}

static inline void safe_fclose(int64_t handle) {
    if (handle >= 0 && handle < SAFE_MAX_FHANDLES && __safe_fhandles[handle]) {
        fclose(__safe_fhandles[handle]);
        __safe_fhandles[handle] = NULL;
    }
}

static inline char* safe_fread(int64_t handle) {
    FILE* f = __safe_fhandles[handle];
    if (!f) return safe_arena_strdup("");
    long start = ftell(f);
    fseek(f, 0, SEEK_END);
    long size = ftell(f);
    fseek(f, start, SEEK_SET);
    long remaining = size - start;
    char* buffer = (char*)safe_arena_alloc(remaining + 1);
    size_t read = fread(buffer, 1, remaining, f);
    buffer[read] = '\0';
    return buffer;
}

static inline void safe_fwrite(int64_t handle, const char* content) {
    FILE* f = __safe_fhandles[handle];
    if (f && content) {
        fputs(content, f);
    }
}

static inline char* safe_read(const char* path) {
    FILE* f = fopen(path, "r");
    if (!f) return safe_arena_strdup("");
    fseek(f, 0, SEEK_END);
    long size = ftell(f);
    fseek(f, 0, SEEK_SET);
    char* buffer = (char*)safe_arena_alloc(size + 1);
    size_t read = fread(buffer, 1, size, f);
    buffer[read] = '\0';
    fclose(f);
    return buffer;
}

static inline void safe_write(const char* path, const char* content) {
    FILE* f = fopen(path, "w");
    if (f) {
        if (content) fputs(content, f);
        fclose(f);
    }
}

// Legacy aliases for old builtin read/write (IDs 35,36) used by csv/json/xml modules
static inline char* safe_rawread(const char* path) { return safe_read(path); }
static inline void safe_rawwrite(const char* path, const char* content) { safe_write(path, content); }

// Path-based append (file builtin id 37): open in "a" mode and write.
static inline void safe_append(const char* path, const char* content) {
    FILE* f = fopen(path, "a");
    if (f) {
        if (content) fputs(content, f);
        fclose(f);
    }
}

// Path-based line read (file builtin id 40): open, read line by line, return list.
static inline SAFEList* safe_pathlines(const char* path) {
    SAFEList* list = safe_list_new();
    FILE* f = fopen(path, "r");
    if (!f) return list;
    char line[4096];
    while (fgets(line, sizeof(line), f)) {
        size_t length = strlen(line);
        if (length > 0 && line[length - 1] == '\n') line[--length] = '\0';
        if (length > 0 && line[length - 1] == '\r') line[--length] = '\0';
        safe_list_append(list, safe_arena_strdup(line));
    }
    fclose(f);
    return list;
}

static inline SAFEList* safe_flines(int64_t handle) {
    SAFEList* list = safe_list_new();
    FILE* f = __safe_fhandles[handle];
    if (!f) return list;
    char line[4096];
    while (fgets(line, sizeof(line), f)) {
        size_t length = strlen(line);
        if (length > 0 && line[length - 1] == '\n') line[--length] = '\0';
        if (length > 0 && line[length - 1] == '\r') line[--length] = '\0';
        safe_list_append(list, safe_arena_strdup(line));
    }
    return list;
}

static inline bool safe_fvalid(int64_t handle) {
    return handle >= 0 && handle < SAFE_MAX_FHANDLES && __safe_fhandles[handle] != NULL;
}

/* ===== Recursion Depth Guard ===== */

#define SAFE_MAX_RECURSION 1000
static int __safe_recursion_depth = 0;

static void safe_check_recursion(const char* name) {
    if (++__safe_recursion_depth > SAFE_MAX_RECURSION) {
        fprintf(stderr, "Maximum recursion depth (%d) exceeded for: %s\n", SAFE_MAX_RECURSION, name);
        exit(1);
    }
}

/* ===== Decreases Clause Stack ===== */

#define SAFE_DECREASES_STACK_SIZE 1024

typedef struct {
    int64_t values[SAFE_DECREASES_STACK_SIZE];
    int sp;
} SAFEDecreasesStack;

static void safe_check_decreases_push(SAFEDecreasesStack* stack, int64_t value, const char* name) {
    if (stack->sp >= SAFE_DECREASES_STACK_SIZE) {
        fprintf(stderr, "Decreases stack overflow for: %s\n", name);
        exit(1);
    }
    stack->values[stack->sp++] = value;
}

/* ===== safe_dispose — per-kind teardown =====
 * Called by safe_release at refs → 0. Releases heap-refcounted children
 * based on the container's kind/meta, then frees malloc'd buffers. The
 * header+struct block lives in the arena and is retained until process
 * exit.
 *
 * Dispatch uses a function table indexed by kind — each kind's teardown
 * lives in a small inlinable helper. dispose_enum walks a secondary
 * table registered per recursive enum type (see safe_register_enum),
 * which codegen populates from CEnumGenerator.
 *
 * Meta encoding recap:
 *   LIST / SET : meta = element kind
 *   MAP        : meta = (key_kind<<4) | value_kind
 *   TUPLE      : meta = bitmap — bit N set means element N is heap
 *   OBJECT     : meta = bitmap over declared struct fields (native
 *                structs are value-typed so no OBJECT allocations reach
 *                here today; kept for future use)
 *   ENUM       : meta = variant index within the registered type table
 *                — codegen emits the matching dispose fns via
 *                  safe_register_enum()
 *   CLOSURE    : meta = bitmap over captures (bit N set = capture N
 *                is heap-kind)
 *   STRING     : meta = 0 (dispose is a no-op: heap strings store the
 *                payload inline with the length prefix, so freeing the
 *                block is all that's needed)
 *   BYTES, RAW : meta = 0
 */

static inline void safe_dispose_list(void* body) {
    SAFEList* l = (SAFEList*)body;
    const uint8_t meta = safe_header(body)->meta;
    if (safe_kind_is_heap(meta)) {
        void** slots = (void**)l->data;
        for (int64_t i = 0; i < l->length; i++) safe_release(slots[i]);
    }
    if (l->data) { free(l->data); l->data = NULL; }
}

static inline void safe_dispose_map(void* body) {
    SAFEMap* m = (SAFEMap*)body;
    const uint8_t meta = safe_header(body)->meta;
    const uint8_t value_kind = (uint8_t)(meta & 0xF);
    if (safe_kind_is_heap(value_kind)) {
        for (SAFEMapEntry* e = m->head; e; e = e->order_next) {
            safe_release(e->value.ptr_val);
        }
    }
    if (m->buckets) { free(m->buckets); m->buckets = NULL; }
}

static inline void safe_dispose_bytes(void* body) {
    SAFEBytes* b = (SAFEBytes*)body;
    if (b->data) { free(b->data); b->data = NULL; }
}

static inline void safe_dispose_set(void* body) {
    SAFESet* s = (SAFESet*)body;
    const uint8_t meta = safe_header(body)->meta;
    if (safe_kind_is_heap(meta)) {
        for (int64_t i = 0; i < s->length; i++) {
            safe_release(s->data[i].ptr_val);
        }
    }
    if (s->data) { free(s->data); s->data = NULL; }
}

static inline void safe_dispose_tuple(void* body) {
    SAFETuple* t = (SAFETuple*)body;
    const uint16_t meta = safe_header(body)->meta;
    if (meta != 0) {
        const int count = t->count < 16 ? t->count : 16;
        for (int i = 0; i < count; i++) {
            if ((meta >> i) & 1u) safe_release(t->elements[i].ptr_val);
        }
    }
}

/* Closure dispose walks the capture bitmap in header.meta. The captures
 * pointer (SAFEClosure.context) is itself safe_alloc'd (Phase 5.1), so
 * releasing it feeds the block back to the free list — dispose_closure
 * only has to drop each capture's refcount. */
static inline void safe_dispose_closure(void* body) {
    SAFEClosure* c = (SAFEClosure*)body;
    if (c->context) {
        const uint16_t meta = safe_header(body)->meta;
        if (meta != 0) {
            void** slots = (void**)c->context;
            for (int i = 0; i < 16; i++) {
                if ((meta >> i) & 1u) safe_release(slots[i]);
            }
        }
        safe_release(c->context);
        c->context = NULL;
    }
}

/* Per-recursive-enum dispose registry. Each recursive enum type emits
 * one dispose function (generated by CEnumGenerator) that switches on
 * the tag and releases the heap fields of the active variant. The type
 * id returned from safe_register_enum() is stashed in header.size_class
 * at allocation time so safe_dispose_enum can dispatch back here.
 *
 * Native's safe_alloc uses the arena (not the size-class free list), so
 * size_class is otherwise unused — we repurpose it as a per-block type
 * tag. WASM handles enums differently (bitmap walk via body layout) so
 * this registry is native-only. */
typedef void (*safe_dispose_fn)(void* body);

/* Max 255 distinct recursive enum types (size_class is now 8 bits after
 * the Phase-7 meta widening). 0 is reserved for "not registered". SAFE
 * programs in the wild have <10 recursive enum types — this ceiling is
 * effectively unbounded in practice. */
#define SAFE_MAX_ENUM_TYPES 255
static safe_dispose_fn safe_enum_dispatch[SAFE_MAX_ENUM_TYPES];
static int safe_enum_type_count = 0;

/* Register a recursive enum's dispose function. Returns the 1-based id
 * the caller should stamp into header.size_class at each variant
 * allocation; 0 means "no dispatch" (the dispose path is a no-op, same
 * as pre-Phase-5 behaviour). */
static inline int safe_register_enum(safe_dispose_fn fn) {
    if (safe_enum_type_count >= SAFE_MAX_ENUM_TYPES || !fn) return 0;
    int id = ++safe_enum_type_count;  /* 1-based */
    safe_enum_dispatch[id - 1] = fn;
    return id;
}

static inline void safe_dispose_enum(void* body) {
    uint8_t id = safe_header(body)->size_class;
    if (id == 0 || id > (uint8_t)safe_enum_type_count) return;
    safe_enum_dispatch[id - 1](body);
}

/* String dispose — heap strings (Phase 6 onwards) store the payload
 * inline. Nothing to release; freeing the block via the free list is
 * handled by safe_release. */
static inline void safe_dispose_string(void* body) { (void)body; }

/* Dispatch table indexed by SAFE_KIND_*. Unhandled kinds are no-ops. */
static inline void safe_dispose(void* body) {
    if (!body) return;
    const uint8_t kind = safe_header(body)->kind;
    switch (kind) {
        case SAFE_KIND_LIST:    safe_dispose_list(body); break;
        case SAFE_KIND_MAP:     safe_dispose_map(body); break;
        case SAFE_KIND_BYTES:   safe_dispose_bytes(body); break;
        case SAFE_KIND_SET:     safe_dispose_set(body); break;
        case SAFE_KIND_TUPLE:   safe_dispose_tuple(body); break;
        case SAFE_KIND_CLOSURE: safe_dispose_closure(body); break;
        case SAFE_KIND_ENUM:    safe_dispose_enum(body); break;
        case SAFE_KIND_STRING:  safe_dispose_string(body); break;
        default: break;
    }
}

#endif /* SAFE_RUNTIME_H */
