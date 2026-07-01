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
#include <strings.h>
#include <signal.h>
#include <sys/wait.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <netdb.h>
#include <limits.h>
#include <poll.h>

/* ===== Deploy-time host policy (env vars, mirrors JvmRuntime.policyFromEnv) =====
 *
 * A native binary built with capabilities granted (the trusted default) is confined at deploy
 * time by four environment variables — SAFE_FS_ROOT, SAFE_NET_ALLOW, SAFE_EXEC_ALLOW,
 * SAFE_SERVE_BIND — exactly as the JVM jar is (see runtime/HostPolicy + JvmRuntime.policyFromEnv).
 * Each unset var means "no restriction" for that axis. Enforcement lives at the file/binary/exec
 * /http builtin seams below. The runtime is single-threaded, so the resolver uses static buffers. */

static char* __safe_fs_root = NULL;      /* realpath'd jail root; NULL => no jail */
static char** __safe_net_allow = NULL;   /* egress host allowlist; NULL => any (SSRF-guarded) */
static int __safe_net_allow_n = 0;
static char** __safe_exec_allow = NULL;  /* exec argv0 allowlist; NULL => any command */
static int __safe_exec_allow_n = 0;
static char* __safe_serve_bind = NULL;   /* http server bind address; NULL => 127.0.0.1 */

/* Cap on list length, mirroring SAFEValue.MAX_LIST_SIZE — enforced at the append choke point and by
 * the range builder so a "terminating" program cannot exhaust memory. */
#define SAFE_MAX_LIST_SIZE 10000000

static inline char** safe_split_csv(const char* s, int* count) {
    int n = 1;
    for (const char* p = s; *p; p++) if (*p == ',') n++;
    char** arr = (char**)malloc(sizeof(char*) * (size_t)n);
    int idx = 0;
    const char* start = s;
    for (const char* p = s;; p++) {
        if (*p == ',' || *p == '\0') {
            size_t len = (size_t)(p - start);
            char* item = (char*)malloc(len + 1);
            memcpy(item, start, len);
            item[len] = '\0';
            arr[idx++] = item;
            if (!*p) break;
            start = p + 1;
        }
    }
    *count = idx;
    return arr;
}

static inline void safe_init_policy_from_env(void) {
    const char* root = getenv("SAFE_FS_ROOT");
    if (root && root[0]) {
        char resolved[PATH_MAX];
        __safe_fs_root = realpath(root, resolved) ? strdup(resolved) : strdup(root);
    }
    const char* net = getenv("SAFE_NET_ALLOW");
    if (net && net[0]) __safe_net_allow = safe_split_csv(net, &__safe_net_allow_n);
    const char* exec = getenv("SAFE_EXEC_ALLOW");
    if (exec && exec[0]) __safe_exec_allow = safe_split_csv(exec, &__safe_exec_allow_n);
    const char* bind = getenv("SAFE_SERVE_BIND");
    if (bind && bind[0]) __safe_serve_bind = strdup(bind);
}

static inline void safe_policy_deny_path(const char* path) {
    fprintf(stderr, "safe: path escapes the sandbox root: %s\n", path);
    exit(1);
}

/* Lexically normalize an absolute path (collapse ".", "..", duplicate slashes) into out,
 * which must hold at least strlen(in)+2 bytes. Mirrors java.nio Path.normalize for an absolute
 * path (a leading ".." at the root is dropped). */
static inline void safe_lexnorm(const char* in, char* out) {
    char* w = out;
    char* marks[PATH_MAX];
    int nseg = 0;
    const char* p = in;
    while (*p) {
        while (*p == '/') p++;
        if (!*p) break;
        const char* start = p;
        while (*p && *p != '/') p++;
        size_t len = (size_t)(p - start);
        if (len == 1 && start[0] == '.') continue;
        if (len == 2 && start[0] == '.' && start[1] == '.') {
            if (nseg > 0) { nseg--; w = marks[nseg]; }
            continue;
        }
        marks[nseg++] = w;
        *w++ = '/';
        memcpy(w, start, len);
        w += len;
    }
    if (w == out) *w++ = '/';
    *w = '\0';
}

static inline int safe_under_root(const char* p, size_t rootlen) {
    return strncmp(p, __safe_fs_root, rootlen) == 0
        && (p[rootlen] == '\0' || p[rootlen] == '/');
}

/* Resolve a caller-supplied path against the filesystem jail (mirrors HostPolicy.resolve): with
 * no root, return path unchanged; otherwise confine under the root (escape => fatal) and return
 * the canonical, symlink-collapsed path. Returns a pointer into a static buffer. */
static inline const char* safe_check_path(const char* path) {
    if (!__safe_fs_root) return path;
    static char candidate[PATH_MAX];
    static char norm[PATH_MAX];
    static char result[PATH_MAX];
    if (path[0] == '/') snprintf(candidate, sizeof(candidate), "%s", path);
    else snprintf(candidate, sizeof(candidate), "%s/%s", __safe_fs_root, path);
    safe_lexnorm(candidate, norm);
    size_t rootlen = strlen(__safe_fs_root);
    if (!safe_under_root(norm, rootlen)) safe_policy_deny_path(path);
    /* realpath the deepest existing ancestor, then reattach the not-yet-existing tail. */
    char probe[PATH_MAX];
    snprintf(probe, sizeof(probe), "%s", norm);
    while (access(probe, F_OK) != 0) {
        char* slash = strrchr(probe, '/');
        if (!slash || slash == probe) { probe[0] = '/'; probe[1] = '\0'; break; }
        *slash = '\0';
    }
    char real[PATH_MAX];
    if (!realpath(probe, real)) {
        snprintf(result, sizeof(result), "%s", norm);
        return result;
    }
    size_t plen = strlen(probe);
    const char* tail = (plen <= 1) ? norm : (norm + plen);
    char combined[PATH_MAX];
    snprintf(combined, sizeof(combined), "%s%s", real, tail);
    safe_lexnorm(combined, result);
    if (!safe_under_root(result, rootlen)) safe_policy_deny_path(path);
    return result;
}

static inline int safe_check_exec(const char* argv0) {
    if (!__safe_exec_allow) return 1;
    for (int i = 0; i < __safe_exec_allow_n; i++)
        if (strcmp(__safe_exec_allow[i], argv0) == 0) return 1;
    return 0;
}

/* Extract the host from a URL (scheme://[user@]host[:port]/...), IPv6 literals unbracketed. */
static inline void safe_url_host(const char* url, char* host, size_t cap) {
    host[0] = '\0';
    const char* p = strstr(url, "://");
    p = p ? p + 3 : url;
    const char* at = strchr(p, '@');
    const char* slash = strchr(p, '/');
    if (at && (!slash || at < slash)) p = at + 1;
    size_t i = 0;
    if (*p == '[') {
        p++;
        while (*p && *p != ']' && i < cap - 1) host[i++] = *p++;
    } else {
        while (*p && *p != ':' && *p != '/' && *p != '?' && *p != '#' && i < cap - 1)
            host[i++] = *p++;
    }
    host[i] = '\0';
}

/* True when an address is loopback / link-local (incl. 169.254.169.254 metadata) / site-local /
 * any-local / multicast — the SSRF blocklist, matching HostPolicy.isInternal. */
static inline int safe_addr_internal(const struct addrinfo* ai) {
    if (ai->ai_family == AF_INET) {
        uint32_t a = ntohl(((struct sockaddr_in*)ai->ai_addr)->sin_addr.s_addr);
        uint8_t b0 = (a >> 24) & 0xff, b1 = (a >> 16) & 0xff;
        if (b0 == 127) return 1;                       /* 127/8 loopback */
        if (b0 == 10) return 1;                        /* 10/8 */
        if (b0 == 172 && b1 >= 16 && b1 <= 31) return 1; /* 172.16/12 */
        if (b0 == 192 && b1 == 168) return 1;          /* 192.168/16 */
        if (b0 == 169 && b1 == 254) return 1;          /* 169.254/16 link-local + metadata */
        if (b0 == 100 && (b1 & 0xc0) == 0x40) return 1; /* 100.64/10 carrier-grade NAT */
        if (a == 0) return 1;                          /* 0.0.0.0 any-local */
        if (b0 >= 224) return 1;                       /* 224/4 multicast + reserved */
        return 0;
    }
    if (ai->ai_family == AF_INET6) {
        const uint8_t* b = ((struct sockaddr_in6*)ai->ai_addr)->sin6_addr.s6_addr;
        int nonzero = 0;
        for (int i = 0; i < 16; i++) if (b[i]) { nonzero = 1; break; }
        if (!nonzero) return 1;                        /* :: any-local */
        int prefix0 = 1;
        for (int i = 0; i < 15; i++) if (b[i]) { prefix0 = 0; break; }
        if (prefix0 && b[15] == 1) return 1;           /* ::1 loopback */
        if (b[0] == 0xfe && (b[1] & 0xc0) == 0x80) return 1; /* fe80::/10 link-local */
        if (b[0] == 0xfe && (b[1] & 0xc0) == 0xc0) return 1; /* fec0::/10 site-local */
        if ((b[0] & 0xfe) == 0xfc) return 1;           /* fc00::/7 unique-local (ULA) */
        if (b[0] == 0xff) return 1;                    /* ff00::/8 multicast */
        return 0;
    }
    return 0;
}

/* Egress policy for an HTTP client request (mirrors HostPolicy.egressAllowed): with an allowlist,
 * only listed hosts are reachable; otherwise any host except one resolving to an internal address. */
static inline int safe_check_egress(const char* url) {
    char host[256];
    safe_url_host(url, host, sizeof(host));
    if (!host[0]) return 0;
    if (__safe_net_allow) {
        for (int i = 0; i < __safe_net_allow_n; i++)
            if (strcasecmp(__safe_net_allow[i], host) == 0) return 1;
        return 0;
    }
    struct addrinfo hints;
    struct addrinfo* ai = NULL;
    memset(&hints, 0, sizeof(hints));
    hints.ai_family = AF_UNSPEC;
    hints.ai_socktype = SOCK_STREAM;
    if (getaddrinfo(host, NULL, &hints, &ai) != 0) return 0;
    int internal = 0;
    for (struct addrinfo* p = ai; p; p = p->ai_next)
        if (safe_addr_internal(p)) { internal = 1; break; }
    freeaddrinfo(ai);
    return internal ? 0 : 1;
}

/* The http server bind address (network byte order), from SAFE_SERVE_BIND; default loopback.
 * Only IPv4 literals are honored (the server is AF_INET); anything else falls back to loopback. */
static inline uint32_t safe_bind_addr(void) {
    struct in_addr a;
    if (__safe_serve_bind && inet_pton(AF_INET, __safe_serve_bind, &a) == 1) return a.s_addr;
    return htonl(INADDR_LOOPBACK);
}

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

/* Allocation failure → the runtime's standard deterministic trap, not a NULL-deref segfault. */
static inline void safe_oom(void) {
    fprintf(stderr, "safe: out of memory\n");
    exit(1);
}
static inline void* safe_xmalloc(size_t n) {
    void* p = malloc(n);
    if (!p) safe_oom();
    return p;
}
static inline void* safe_xrealloc(void* p, size_t n) {
    void* q = realloc(p, n);
    if (!q) safe_oom();
    return q;
}
static inline void* safe_xcalloc(size_t count, size_t size) {
    void* p = calloc(count, size);
    if (!p) safe_oom();
    return p;
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
    block = (SAFEArenaBlock*)safe_xmalloc(sizeof(SAFEArenaBlock) + capacity);
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

/* Cycle collector (Bacon-Rajan) — defined after the dispose block below. */
static inline void safe_collect_possible_root(void* body);
static void safe_collect_cycles(void);

/* Phase C: safe_alloc now uses malloc so struct/enum/tuple/bytes bodies
 * can be reclaimed via free() when refs hit 0. Previously this forwarded
 * to safe_arena_alloc (bump-only), which meant all non-buffer blocks
 * leaked for the process lifetime. The arena is still live for truly-
 * immortal allocations (string literals via safe_intern_string, scratch
 * buffers) — see safe_arena_alloc call sites. */
static inline void* safe_alloc(size_t size, uint8_t kind, uint16_t meta) {
    char* raw = (char*)safe_xmalloc(sizeof(SAFEHeader) + size);
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
    uint32_t count = safe_rc_count(hdr);
    if (count == 0) return; /* defensive — already released */
    count -= 1;
    safe_rc_set_count(hdr, count);
    if (count == 0) {
        if (safe_rc_buffered(hdr)) {
            /* This block is sitting in the cycle-collector roots buffer; freeing
             * it now would leave a dangling pointer there. Leave it black/count-0
             * for the collector's MarkRoots pass to dispose and free. */
            safe_rc_set_color(hdr, SAFE_COLOR_BLACK);
        } else {
            safe_dispose(body);
            /* After child release, free the malloc'd block. IMMORTAL blocks
             * (string literals) short-circuit at the check above and never
             * reach here, so they stay put. */
            free((char*)body - sizeof(SAFEHeader));
        }
    } else {
        /* Still referenced — but a dropped reference to a container could be
         * the last external pointer keeping a cycle alive. Buffer it as a
         * possible cycle root for the collector to examine. */
        safe_collect_possible_root(body);
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
    list->data = safe_xmalloc(list->capacity * sizeof(void*));
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
    list->data = safe_xmalloc(list->capacity * sizeof(void*));
    return list;
}

static inline void safe_list_append(SAFEList* list, void* value) {
    if (list->length >= SAFE_MAX_LIST_SIZE) {
        fprintf(stderr, "list size exceeds maximum of %d\n", SAFE_MAX_LIST_SIZE);
        exit(1);
    }
    if (list->length >= list->capacity) {
        list->capacity *= 2;
        list->data = safe_xrealloc(list->data, list->capacity * sizeof(void*));
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
    map->buckets = (SAFEMapEntry**)safe_xcalloc(map->capacity, sizeof(SAFEMapEntry*));
    map->head = NULL;
    map->tail = NULL;
    return map;
}

static inline SAFEMap* safe_map_new(void) {
    SAFEMap* map = (SAFEMap*)safe_alloc(sizeof(SAFEMap), SAFE_KIND_MAP, 0);
    map->capacity = 16;
    map->length = 0;
    map->buckets = (SAFEMapEntry**)safe_xcalloc(map->capacity, sizeof(SAFEMapEntry*));
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

#define SAFE_MAX_TUPLE_SIZE 16

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
    set->data = (SAFEValue*)safe_xmalloc(set->capacity * sizeof(SAFEValue));
    return set;
}

/* Phase 5: typed set. meta = element kind; dispose releases each heap
 * element when the set is freed. */
static inline SAFESet* safe_set_new_typed(uint8_t kind) {
    SAFESet* set = (SAFESet*)safe_alloc(sizeof(SAFESet), SAFE_KIND_SET, kind);
    set->capacity = 8;
    set->length = 0;
    set->tag = 0;
    set->data = (SAFEValue*)safe_xmalloc(set->capacity * sizeof(SAFEValue));
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
        set->data = (SAFEValue*)safe_xrealloc(set->data, set->capacity * sizeof(SAFEValue));
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
    path = safe_check_path(path);
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
    return mkdir(safe_check_path(path), 0755) == 0;
}

static inline bool safe_rmdir(const char* path) {
    return rmdir(safe_check_path(path)) == 0;
}

static inline bool safe_isdir(const char* path) {
    struct stat info;
    if (stat(safe_check_path(path), &info) != 0) return false;
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
    if (length < 0) {
        fprintf(stderr, "safe: negative bytes length %lld\n", (long long)length);
        exit(1);
    }
    SAFEBytes* b = (SAFEBytes*)safe_alloc(sizeof(SAFEBytes), SAFE_KIND_BYTES, 0);
    b->length = length;
    b->data = (uint8_t*)safe_xcalloc((size_t)length, 1);
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
    path = safe_check_path(path);
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
    FILE* f = fopen(safe_check_path(path), "rb");
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
     * holder (count == 1), mutate in place — no copy, no new allocation.
     * This is the core memory-reclamation mechanism for functional-style
     * `x = append(x, v)` loops. safe_list_append handles retain-on-insert
     * based on list meta. Test the COUNT (not the raw refs word): once the
     * cycle collector buffers the list (a release to count>0 sets the
     * PURPLE/buffered bits), a raw `refs == 1` test fails and forces the
     * copy path every call — O(n^2) re-retains of every element. */
    if (list && safe_rc_count(safe_header(list)) == 1) {
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
    result->data = safe_xmalloc(result->capacity * sizeof(void*));
    if (list) {
        for (int64_t i = 0; i < list->length; i++) {
            safe_list_append(result, ((void**)list->data)[i]);
        }
    }
    safe_list_append(result, element);
    return result;
}

/* Like safe_list_append_copy, but TRANSFERS ownership of `element` into the list instead of
 * retaining it. Used by codegen when inserting a freshly boxed element (`__tmp = safe_alloc(...)`,
 * refs==1): the box's single creation ref becomes the list's ref — no retain, so no matching release
 * is needed and the box is never released at count>0 (never buffered as a cycle-collector root). On
 * disposal the list releases the element exactly once, freeing it. `element` must be freshly owned by
 * the caller (refs==1, not aliased). */
static inline SAFEList* safe_list_append_move(SAFEList* list, void* element) {
    /* Raw insert helper: append the pointer without the retain-on-insert that safe_list_append does. */
    if (list && safe_rc_count(safe_header(list)) == 1) {
        if (list->length >= list->capacity) {
            list->capacity *= 2;
            list->data = safe_xrealloc(list->data, list->capacity * sizeof(void*));
        }
        ((void**)list->data)[list->length++] = element;
        return list;
    }
    SAFEList* result = (SAFEList*)safe_alloc(sizeof(SAFEList), SAFE_KIND_LIST,
                                             list ? safe_header(list)->meta : 0);
    result->capacity = (list && list->capacity > 0) ? list->capacity : 10;
    result->length = 0;
    result->data = safe_xmalloc(result->capacity * sizeof(void*));
    if (list) {
        for (int64_t i = 0; i < list->length; i++) {
            safe_list_append(result, ((void**)list->data)[i]);
        }
    }
    if (result->length >= result->capacity) {
        result->capacity *= 2;
        result->data = safe_xrealloc(result->data, result->capacity * sizeof(void*));
    }
    ((void**)result->data)[result->length++] = element;
    return result;
}

static inline SAFEList* safe_list_append_copy_int(SAFEList* list, int64_t element) {
    if (list && safe_rc_count(safe_header(list)) == 1) {
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
    return unlink(safe_check_path(path)) == 0 ? 1 : 0;
}

static inline int safe_exists(const char* path) {
    return access(safe_check_path(path), F_OK) == 0 ? 1 : 0;
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
    path = safe_check_path(path);
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
    FILE* f = fopen(safe_check_path(path), "r");
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
    FILE* f = fopen(safe_check_path(path), "w");
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
    FILE* f = fopen(safe_check_path(path), "a");
    if (f) {
        if (content) fputs(content, f);
        fclose(f);
    }
}

// Path-based line read (file builtin id 40): open, read line by line, return list.
static inline SAFEList* safe_pathlines(const char* path) {
    SAFEList* list = safe_list_new();
    FILE* f = fopen(safe_check_path(path), "r");
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

/* ===== Streaming file I/O (s* builtins) =====
 * Incremental read/write over the shared file-handle table (__safe_fhandles), rather than
 * buffering the whole file. Mirrors the interpreter/VM/JVM StreamHandle. */
#define SAFE_STREAM_MAX_LINE (64L * 1024 * 1024)  /* cap one line, matching StreamHandle.MAX_READ */

static inline int64_t safe_sopen(const char* path, const char* mode) {
    return safe_fopen(path, mode);  /* modes r/w/a; reuses the file-handle table */
}

static inline void safe_sclose(int64_t handle) {
    safe_fclose(handle);
}

/* Read up to count chars; returns an arena string (empty at EOF), or NULL on a bad handle/count. */
static inline char* safe_sread(int64_t handle, int64_t count) {
    if (handle < 0 || handle >= SAFE_MAX_FHANDLES) return NULL;
    FILE* f = __safe_fhandles[handle];
    if (!f || count < 0 || count > SAFE_STREAM_MAX_LINE) return NULL;
    char* buffer = (char*)safe_arena_alloc(count + 1);
    size_t read = fread(buffer, 1, (size_t)count, f);
    buffer[read] = '\0';
    return buffer;
}

/* Write content through; returns 0 on success, -1 on a bad handle or write error. */
static inline int safe_swrite(int64_t handle, const char* content) {
    if (handle < 0 || handle >= SAFE_MAX_FHANDLES) return -1;
    FILE* f = __safe_fhandles[handle];
    if (!f) return -1;
    if (content && fputs(content, f) == EOF) return -1;
    return 0;
}

static inline int safe_sflush(int64_t handle) {
    if (handle < 0 || handle >= SAFE_MAX_FHANDLES) return -1;
    FILE* f = __safe_fhandles[handle];
    if (!f) return -1;
    return fflush(f) == 0 ? 0 : -1;
}

/* Read one line (terminator stripped, \n / \r / \r\n), bounded at SAFE_STREAM_MAX_LINE so a
 * newline-less line cannot OOM the host. Returns 1 and sets *out on success, 0 at EOF, -1 on a
 * bad handle or over-long line. */
static inline int safe_sline(int64_t handle, char** out) {
    if (handle < 0 || handle >= SAFE_MAX_FHANDLES) return -1;
    FILE* f = __safe_fhandles[handle];
    if (!f) return -1;
    int c = fgetc(f);
    if (c == EOF) return 0;
    size_t cap = 256, len = 0;
    char* buffer = (char*)safe_arena_alloc(cap);
    while (c != EOF && c != '\n') {
        if (c == '\r') {
            int next = fgetc(f);
            if (next != '\n' && next != EOF) ungetc(next, f);  /* lone \r terminates */
            break;
        }
        if ((long)len >= SAFE_STREAM_MAX_LINE) return -1;
        if (len + 1 >= cap) {
            cap *= 2;
            char* grown = (char*)safe_arena_alloc(cap);
            memcpy(grown, buffer, len);
            buffer = grown;
        }
        buffer[len++] = (char)c;
        c = fgetc(f);
    }
    buffer[len] = '\0';
    *out = buffer;
    return 1;
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
/* A per-enum-type child visitor: applies `visit` to each heap-refcounted
 * payload slot of the active variant. The dispose path passes safe_release;
 * the cycle collector passes its own trial-decrement / scan callbacks. */
typedef void (*safe_visit_fn)(void* body, void (*visit)(void*));

/* Max 255 distinct recursive enum types (size_class is now 8 bits after
 * the Phase-7 meta widening). 0 is reserved for "not registered". SAFE
 * programs in the wild have <10 recursive enum types — this ceiling is
 * effectively unbounded in practice. */
#define SAFE_MAX_ENUM_TYPES 255
static safe_visit_fn safe_enum_dispatch[SAFE_MAX_ENUM_TYPES];
static int safe_enum_type_count = 0;

/* Register a recursive enum's child visitor. Returns the 1-based id the
 * caller should stamp into header.size_class at each variant allocation;
 * 0 means "no dispatch" (the dispose path is a no-op). */
static inline int safe_register_enum(safe_visit_fn fn) {
    if (safe_enum_type_count >= SAFE_MAX_ENUM_TYPES || !fn) return 0;
    int id = ++safe_enum_type_count;  /* 1-based */
    safe_enum_dispatch[id - 1] = fn;
    return id;
}

/* Apply `visit` to each heap child of an enum body. */
static inline void safe_visit_enum(void* body, void (*visit)(void*)) {
    uint8_t id = safe_header(body)->size_class;
    if (id == 0 || id > (uint8_t)safe_enum_type_count) return;
    safe_enum_dispatch[id - 1](body, visit);
}

static inline void safe_dispose_enum(void* body) {
    safe_visit_enum(body, safe_release);
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

/* ===== Cycle collector (Bacon-Rajan synchronous trial deletion) =====
 * Reference counting alone leaks cycles (a.next=b; b.next=a). This collector
 * reclaims them. It composes with refcounting: safe_release buffers a dropped
 * container as a "possible root" (purple); when the buffer fills (or at
 * shutdown) safe_collect_cycles runs the trial-deletion passes. Native only —
 * WASM has its own runtime. */

/* Kinds that can hold references to other heap objects (and so participate in
 * cycles). Scalars/strings/bytes never do. Native structs are value-typed
 * (their fields are inlined), so OBJECT is excluded too. */
static inline int safe_kind_has_children(uint8_t kind) {
    switch (kind) {
        case SAFE_KIND_LIST:
        case SAFE_KIND_MAP:
        case SAFE_KIND_SET:
        case SAFE_KIND_TUPLE:
        case SAFE_KIND_ENUM:
        case SAFE_KIND_CLOSURE:
            return 1;
        default:
            return 0;
    }
}

/* Apply `visit` to every heap child reference of `body` (no buffer freeing).
 * Mirrors the per-kind walks in safe_dispose_* but with a caller-supplied
 * callback so the collector can trial-decrement / scan / collect. */
static inline void safe_children(void* body, void (*visit)(void*)) {
    if (!body) return;
    SAFEHeader* hdr = safe_header(body);
    switch (hdr->kind) {
        case SAFE_KIND_LIST: {
            SAFEList* l = (SAFEList*)body;
            if (safe_kind_is_heap((uint8_t)hdr->meta)) {
                void** slots = (void**)l->data;
                for (int64_t i = 0; i < l->length; i++) visit(slots[i]);
            }
            break;
        }
        case SAFE_KIND_MAP: {
            SAFEMap* m = (SAFEMap*)body;
            if (safe_kind_is_heap((uint8_t)(hdr->meta & 0xF))) {
                for (SAFEMapEntry* e = m->head; e; e = e->order_next) visit(e->value.ptr_val);
            }
            break;
        }
        case SAFE_KIND_SET: {
            SAFESet* s = (SAFESet*)body;
            if (safe_kind_is_heap((uint8_t)hdr->meta)) {
                for (int64_t i = 0; i < s->length; i++) visit(s->data[i].ptr_val);
            }
            break;
        }
        case SAFE_KIND_TUPLE: {
            SAFETuple* t = (SAFETuple*)body;
            const uint16_t meta = hdr->meta;
            if (meta) {
                const int count = t->count < 16 ? (int)t->count : 16;
                for (int i = 0; i < count; i++) {
                    if ((meta >> i) & 1u) visit(t->elements[i].ptr_val);
                }
            }
            break;
        }
        case SAFE_KIND_CLOSURE: {
            SAFEClosure* c = (SAFEClosure*)body;
            if (c->context) {
                const uint16_t meta = hdr->meta;
                if (meta) {
                    void** slots = (void**)c->context;
                    for (int i = 0; i < 16; i++) {
                        if ((meta >> i) & 1u) visit(slots[i]);
                    }
                }
                visit(c->context);
            }
            break;
        }
        case SAFE_KIND_ENUM:
            safe_visit_enum(body, visit);
            break;
        default:
            break;
    }
}

/* Free a collected block's malloc'd side buffer (NOT its children — the
 * collector frees the whole white cycle by recursion, without refcounting). */
static inline void safe_free_buffers(void* body) {
    SAFEHeader* hdr = safe_header(body);
    switch (hdr->kind) {
        case SAFE_KIND_LIST: { SAFEList* l = (SAFEList*)body; if (l->data) { free(l->data); l->data = NULL; } break; }
        case SAFE_KIND_MAP:  { SAFEMap* m = (SAFEMap*)body; if (m->buckets) { free(m->buckets); m->buckets = NULL; } break; }
        case SAFE_KIND_SET:  { SAFESet* s = (SAFESet*)body; if (s->data) { free(s->data); s->data = NULL; } break; }
        case SAFE_KIND_BYTES:{ SAFEBytes* b = (SAFEBytes*)body; if (b->data) { free(b->data); b->data = NULL; } break; }
        default: break;
    }
}

/* Possible-roots buffer. */
static void** safe_roots = NULL;
static size_t safe_roots_len = 0;
static size_t safe_roots_cap = 0;
#ifndef SAFE_GC_THRESHOLD
#define SAFE_GC_THRESHOLD 1024
#endif

static inline void safe_collect_possible_root(void* body) {
    SAFEHeader* hdr = safe_header(body);
    if (hdr->refs == SAFE_REFS_IMMORTAL) return;
    if (!safe_kind_has_children(hdr->kind)) return;
    if (safe_rc_color(hdr) == SAFE_COLOR_PURPLE) return; /* already a candidate */
    safe_rc_set_color(hdr, SAFE_COLOR_PURPLE);
    if (!safe_rc_buffered(hdr)) {
        safe_rc_set_buffered(hdr, 1);
        if (safe_roots_len == safe_roots_cap) {
            size_t ncap = safe_roots_cap ? safe_roots_cap * 2 : 256;
            safe_roots = (void**)safe_xrealloc(safe_roots, ncap * sizeof(void*));
            safe_roots_cap = ncap;
        }
        safe_roots[safe_roots_len++] = body;
        if (safe_roots_len >= SAFE_GC_THRESHOLD) safe_collect_cycles();
    }
}

/* --- Trial-deletion passes --- */
static void safe_mark_gray(void* body);
static void safe_gc_trial_dec(void* child) {
    if (!child) return;
    SAFEHeader* h = safe_header(child);
    if (h->refs == SAFE_REFS_IMMORTAL) return;
    if (safe_rc_count(h) > 0) safe_rc_set_count(h, safe_rc_count(h) - 1);
    safe_mark_gray(child);
}
static void safe_mark_gray(void* body) {
    if (!body) return;
    SAFEHeader* hdr = safe_header(body);
    if (hdr->refs == SAFE_REFS_IMMORTAL) return;
    if (safe_rc_color(hdr) != SAFE_COLOR_GRAY) {
        safe_rc_set_color(hdr, SAFE_COLOR_GRAY);
        safe_children(body, safe_gc_trial_dec);
    }
}

static void safe_scan_black(void* body);
static void safe_gc_restore(void* child) {
    if (!child) return;
    SAFEHeader* h = safe_header(child);
    if (h->refs == SAFE_REFS_IMMORTAL) return;
    safe_rc_set_count(h, safe_rc_count(h) + 1);
    if (safe_rc_color(h) != SAFE_COLOR_BLACK) safe_scan_black(child);
}
static void safe_scan_black(void* body) {
    if (!body) return;
    SAFEHeader* hdr = safe_header(body);
    if (hdr->refs == SAFE_REFS_IMMORTAL) return;
    safe_rc_set_color(hdr, SAFE_COLOR_BLACK);
    safe_children(body, safe_gc_restore);
}
static void safe_scan(void* body) {
    if (!body) return;
    SAFEHeader* hdr = safe_header(body);
    if (hdr->refs == SAFE_REFS_IMMORTAL) return;
    if (safe_rc_color(hdr) == SAFE_COLOR_GRAY) {
        if (safe_rc_count(hdr) > 0) {
            safe_scan_black(body);
        } else {
            safe_rc_set_color(hdr, SAFE_COLOR_WHITE);
            safe_children(body, safe_scan);
        }
    }
}

/* Count of blocks reclaimed from cycles — for tests / SAFE_HEAP_REPORT. */
static size_t safe_gc_collected = 0;

static void safe_collect_white(void* body) {
    if (!body) return;
    SAFEHeader* hdr = safe_header(body);
    if (hdr->refs == SAFE_REFS_IMMORTAL) return;
    if (safe_rc_color(hdr) == SAFE_COLOR_WHITE && !safe_rc_buffered(hdr)) {
        safe_rc_set_color(hdr, SAFE_COLOR_BLACK); /* set before recursion so the cycle is freed once */
        safe_children(body, safe_collect_white);
        safe_free_buffers(body);
        free((char*)body - sizeof(SAFEHeader));
        safe_gc_collected++;
    }
}

/* Guards against re-entrant collection: disposing a corpse below releases its
 * children, which can re-buffer possible roots and re-hit SAFE_GC_THRESHOLD.
 * A nested safe_collect_cycles would then free objects the outer pass is still
 * iterating — a use-after-free. The nested call becomes a no-op; the freshly
 * buffered roots are collected on the next (non-nested) run. */
static int safe_gc_running = 0;

/* Run the trial-deletion cycle collector over the buffered possible roots. */
static void safe_collect_cycles(void) {
    if (safe_gc_running) return;
    safe_gc_running = 1;
    /* Snapshot the roots and give the global buffer a fresh start, so possible
     * roots buffered by child releases DURING this collection accumulate for the
     * NEXT run instead of mutating (or reallocating) the set we are iterating. */
    void** work = safe_roots;
    size_t work_len = safe_roots_len;
    safe_roots = NULL;
    safe_roots_len = 0;
    safe_roots_cap = 0;

    /* MarkRoots: gray every still-purple, still-referenced root; drop the rest,
     * freeing any that refcounting already reduced to a black/count-0 corpse. */
    size_t kept = 0;
    for (size_t i = 0; i < work_len; i++) {
        void* s = work[i];
        SAFEHeader* h = safe_header(s);
        if (safe_rc_color(h) == SAFE_COLOR_PURPLE && safe_rc_count(h) > 0) {
            safe_mark_gray(s);
            work[kept++] = s;
        } else {
            safe_rc_set_buffered(h, 0);
            if (safe_rc_color(h) == SAFE_COLOR_BLACK && safe_rc_count(h) == 0) {
                safe_dispose(s);
                free((char*)s - sizeof(SAFEHeader));
            }
        }
    }
    work_len = kept;
    /* ScanRoots: restore counts for externally reachable subgraphs (black),
     * leave true garbage white. */
    for (size_t i = 0; i < work_len; i++) safe_scan(work[i]);
    /* CollectRoots: free the white cycles. */
    for (size_t i = 0; i < work_len; i++) {
        void* s = work[i];
        safe_rc_set_buffered(safe_header(s), 0);
        safe_collect_white(s);
    }
    free(work);
    safe_gc_running = 0;
}

/* ===== Network / process resource limits (mirror the JVM-family defaults) ===== */
#define SAFE_EXEC_TIMEOUT_MS        30000
#define SAFE_EXEC_MAX_CAPTURE       (16 * 1024 * 1024)   /* per stream */
#define SAFE_CLIENT_MAX_RESPONSE    (32L * 1024 * 1024)
#define SAFE_SERVER_MAX_BODY        (8 * 1024 * 1024)
#define SAFE_SERVER_MAX_HEADER_BYTES (64 * 1024)
#define SAFE_SERVER_MAX_HEADERS     100
#define SAFE_SERVER_MAX_LINE        (16 * 1024)
#define SAFE_SERVER_READ_TIMEOUT    3       /* seconds, per connection (bounds slowloris) */
#define SAFE_SERVER_ACCEPT_POLL_MS  1000

/* Monotonic milliseconds for deadline math (independent of wall-clock changes). */
static inline long safe_now_ms(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (long)ts.tv_sec * 1000 + ts.tv_nsec / 1000000;
}

/* True when a header name/value carries no CR/LF/NUL, so it is safe to emit on a header line. */
static inline int safe_header_clean(const char* s) {
    for (; s && *s; s++) {
        if (*s == '\r' || *s == '\n') return 0;
    }
    return 1;
}

/* ===== Process execution (system:exec) ===== */

typedef struct {
    int ok;
    int64_t status;
    char* out;
    char* err;
    char* error;
} SafeExecResult;

/* Drain two pipe fds concurrently with poll(), bounded by a wall-clock deadline and a per-stream
 * capture cap. Both buffers are NUL-terminated; ownership transfers to the caller. Returns
 * 0 = ok, 1 = capture cap exceeded, 2 = deadline exceeded, 3 = out of memory. */
static inline int safe_drain2(int fout, int ferr, char** outp, char** errp, long deadline_ms) {
    size_t ocap = 256, olen = 0, ecap = 256, elen = 0;
    char* obuf = (char*)malloc(ocap);
    char* ebuf = (char*)malloc(ecap);
    if (!obuf || !ebuf) { free(obuf); free(ebuf); *outp = NULL; *errp = NULL; return 3; }
    int oopen = 1, eopen = 1, status = 0;
    char tmp[4096];
    while (oopen || eopen) {
        long remaining = deadline_ms - safe_now_ms();
        if (remaining <= 0) { status = 2; break; }
        struct pollfd fds[2];
        int n = 0;
        if (oopen) { fds[n].fd = fout; fds[n].events = POLLIN; n++; }
        if (eopen) { fds[n].fd = ferr; fds[n].events = POLLIN; n++; }
        int pr = poll(fds, n, (int)(remaining > 1000 ? 1000 : remaining));
        if (pr < 0) break;
        if (pr == 0) continue;
        for (int i = 0; i < n; i++) {
            if (!(fds[i].revents & (POLLIN | POLLHUP | POLLERR))) continue;
            int isout = (fds[i].fd == fout);
            ssize_t r = read(fds[i].fd, tmp, sizeof(tmp));
            if (r <= 0) { if (isout) oopen = 0; else eopen = 0; continue; }
            size_t* len = isout ? &olen : &elen;
            size_t* cap = isout ? &ocap : &ecap;
            char** buf = isout ? &obuf : &ebuf;
            if (*len + (size_t)r > (size_t)SAFE_EXEC_MAX_CAPTURE) { status = 1; goto done; }
            if (*len + (size_t)r + 1 > *cap) {
                while (*len + (size_t)r + 1 > *cap) *cap *= 2;
                char* nb = (char*)realloc(*buf, *cap);
                if (!nb) { status = 3; goto done; }
                *buf = nb;
            }
            memcpy(*buf + *len, tmp, (size_t)r);
            *len += (size_t)r;
        }
    }
done:
    obuf[olen] = '\0'; ebuf[elen] = '\0';
    *outp = obuf; *errp = ebuf;
    return status;
}

static inline SafeExecResult safe_sys_exec(SAFEList* command) {
    SafeExecResult res = {0, 0, NULL, NULL, NULL};
    int64_t argc = safe_list_len(command);
    if (argc <= 0) { res.error = "Empty command"; return res; }
    char** argv = (char**)malloc(sizeof(char*) * (size_t)(argc + 1));
    if (!argv) { res.error = "out of memory"; return res; }
    for (int64_t i = 0; i < argc; i++) argv[i] = safe_list_get_str(command, i);
    argv[argc] = NULL;
    if (!safe_check_exec(argv[0])) {
        free(argv);
        res.error = "command not permitted by exec allowlist";
        return res;
    }
    int outp[2], errp[2];
    if (pipe(outp) != 0 || pipe(errp) != 0) { free(argv); res.error = "pipe failed"; return res; }
    pid_t pid = fork();
    if (pid < 0) { free(argv); res.error = "fork failed"; return res; }
    if (pid == 0) {
        dup2(outp[1], STDOUT_FILENO); dup2(errp[1], STDERR_FILENO);
        close(outp[0]); close(outp[1]); close(errp[0]); close(errp[1]);
        execvp(argv[0], argv);
        _exit(127);
    }
    close(outp[1]); close(errp[1]);
    long deadline = safe_now_ms() + SAFE_EXEC_TIMEOUT_MS;
    int drain = safe_drain2(outp[0], errp[0], &res.out, &res.err, deadline);
    close(outp[0]); close(errp[0]);
    free(argv);
    if (drain != 0) {
        /* A runaway child is killed; the cap/deadline breach is reported as Err. */
        kill(pid, SIGKILL);
        waitpid(pid, NULL, 0);
        res.error = drain == 1 ? "command output exceeds limit"
                  : drain == 2 ? "command timed out"
                               : "out of memory";
        return res;
    }
    int wstatus = 0;
    waitpid(pid, &wstatus, 0);
    res.ok = 1;
    res.status = WIFEXITED(wstatus) ? WEXITSTATUS(wstatus) : -1;
    return res;
}

/* ===== HTTP client (system: http) — libcurl, gated ===== */
#ifdef SAFE_ENABLE_HTTP
#include <curl/curl.h>

typedef struct {
    int ok;
    int64_t status;
    char* body;
    SAFEMap* headers;
    char* error;
} SafeHttpResult;

struct safe_curl_buf { char* data; size_t len; };

static size_t safe_curl_write(void* ptr, size_t size, size_t nmemb, void* userdata) {
    size_t total = size * nmemb;
    struct safe_curl_buf* b = (struct safe_curl_buf*)userdata;
    /* Returning a short count aborts the transfer — caps the response and signals an error. */
    if (b->len + total > (size_t)SAFE_CLIENT_MAX_RESPONSE) return 0;
    char* nb = (char*)realloc(b->data, b->len + total + 1);
    if (!nb) return 0;
    b->data = nb;
    memcpy(b->data + b->len, ptr, total);
    b->len += total;
    b->data[b->len] = '\0';
    return total;
}

static size_t safe_curl_header(char* buffer, size_t size, size_t nitems, void* userdata) {
    size_t total = size * nitems;
    SAFEMap* headers = (SAFEMap*)userdata;
    char* colon = (char*)memchr(buffer, ':', total);
    if (colon) {
        size_t nlen = (size_t)(colon - buffer);
        char* name = (char*)malloc(nlen + 1);
        if (!name) return total;
        memcpy(name, buffer, nlen); name[nlen] = '\0';
        char* vstart = colon + 1; size_t vlen = total - nlen - 1;
        while (vlen > 0 && *vstart == ' ') { vstart++; vlen--; }
        while (vlen > 0 && (vstart[vlen-1] == '\r' || vstart[vlen-1] == '\n' || vstart[vlen-1] == ' ')) vlen--;
        char* val = (char*)malloc(vlen + 1);
        if (!val) { free(name); return total; }
        memcpy(val, vstart, vlen); val[vlen] = '\0';
        safe_map_put_str(headers, name, val);
        free(name); free(val);
    }
    return total;
}

static inline SafeHttpResult safe_http_request(const char* method, const char* url, SAFEMap* req_headers, const char* body) {
    SafeHttpResult res = {0, 0, NULL, NULL, NULL};
    if (!safe_check_egress(url)) { res.error = "egress blocked by network policy"; return res; }
    CURL* curl = curl_easy_init();
    if (!curl) { res.error = "curl init failed"; return res; }
    struct safe_curl_buf buf = {NULL, 0};
    SAFEMap* resp_headers = safe_map_new();
    curl_easy_setopt(curl, CURLOPT_URL, url);
    curl_easy_setopt(curl, CURLOPT_CUSTOMREQUEST, method);
    curl_easy_setopt(curl, CURLOPT_WRITEFUNCTION, safe_curl_write);
    curl_easy_setopt(curl, CURLOPT_WRITEDATA, &buf);
    curl_easy_setopt(curl, CURLOPT_HEADERFUNCTION, safe_curl_header);
    curl_easy_setopt(curl, CURLOPT_HEADERDATA, resp_headers);
    /* Do not auto-follow redirects — match the Java HttpClient (which does not) and avoid silently
     * chasing a 30x to an internal/metadata host (SSRF-safer). */
    curl_easy_setopt(curl, CURLOPT_FOLLOWLOCATION, 0L);
    curl_easy_setopt(curl, CURLOPT_TIMEOUT, 30L);
    curl_easy_setopt(curl, CURLOPT_CONNECTTIMEOUT, 10L);
    curl_easy_setopt(curl, CURLOPT_MAXFILESIZE_LARGE, (curl_off_t)SAFE_CLIENT_MAX_RESPONSE);
    struct curl_slist* hlist = NULL;
    if (req_headers) {
        for (SAFEMapEntry* e = req_headers->head; e; e = e->order_next) {
            const char* v = e->value.string_val ? e->value.string_val : "";
            char* line = (char*)malloc(strlen(e->key.string_key) + strlen(v) + 3);
            sprintf(line, "%s: %s", e->key.string_key, v);
            hlist = curl_slist_append(hlist, line);
            free(line);
        }
        curl_easy_setopt(curl, CURLOPT_HTTPHEADER, hlist);
    }
    if (body && body[0]) curl_easy_setopt(curl, CURLOPT_POSTFIELDS, body);
    CURLcode rc = curl_easy_perform(curl);
    if (rc != CURLE_OK) {
        res.error = (char*)curl_easy_strerror(rc);
    } else {
        long code = 0;
        curl_easy_getinfo(curl, CURLINFO_RESPONSE_CODE, &code);
        res.ok = 1;
        res.status = code;
        res.body = buf.data ? buf.data : strdup("");
        res.headers = resp_headers;
    }
    if (hlist) curl_slist_free_all(hlist);
    curl_easy_cleanup(curl);
    return res;
}
#endif /* SAFE_ENABLE_HTTP */

/* ===== HTTP server (http: serve) — raw sockets; TLS gated via OpenSSL ===== */
#ifdef SAFE_ENABLE_TLS
#include <openssl/ssl.h>
#include <openssl/err.h>
static SSL_CTX* safe_tls_ctx = NULL;
#endif

typedef struct {
    char* method;
    char* path;
    SAFEMap* headers;
    char* body;
    int conn;
    void* ssl;
} SafeHttpReq;

static inline ssize_t safe_conn_read(int fd, void* ssl, void* buf, size_t n) {
#ifdef SAFE_ENABLE_TLS
    if (ssl) return SSL_read((SSL*)ssl, buf, (int)n);
#endif
    (void)ssl; return read(fd, buf, n);
}

static inline ssize_t safe_conn_write(int fd, void* ssl, const void* buf, size_t n) {
#ifdef SAFE_ENABLE_TLS
    if (ssl) return SSL_write((SSL*)ssl, buf, (int)n);
#endif
    (void)ssl; return write(fd, buf, n);
}

/* Read one CRLF-terminated line (CRLF stripped), or NULL at EOF. */
/* Read one CRLF line (CRLF stripped). Returns NULL at EOF; on a line longer than
 * SAFE_SERVER_MAX_LINE sets *toolong and returns NULL (the caller answers 431). */
static inline char* safe_http_readline(int fd, void* ssl, int* toolong) {
    *toolong = 0;
    size_t cap = 128, len = 0;
    char* buf = (char*)malloc(cap);
    if (!buf) { *toolong = 1; return NULL; }
    char c;
    while (safe_conn_read(fd, ssl, &c, 1) == 1) {
        if (c == '\n') {
            if (len > 0 && buf[len-1] == '\r') len--;
            buf[len] = '\0';
            return buf;
        }
        if (len + 1 >= cap) {
            if (cap >= (size_t)SAFE_SERVER_MAX_LINE) { free(buf); *toolong = 1; return NULL; }
            cap *= 2;
            char* nb = (char*)realloc(buf, cap);
            if (!nb) { free(buf); *toolong = 1; return NULL; }
            buf = nb;
        }
        buf[len++] = c;
    }
    if (len == 0) { free(buf); return NULL; }
    buf[len] = '\0';
    return buf;
}

static inline const char* safe_http_reason(int64_t status) {
    switch (status) {
        case 200: return "OK";
        case 201: return "Created";
        case 204: return "No Content";
        case 301: return "Moved Permanently";
        case 302: return "Found";
        case 400: return "Bad Request";
        case 401: return "Unauthorized";
        case 403: return "Forbidden";
        case 404: return "Not Found";
        case 413: return "Payload Too Large";
        case 431: return "Request Header Fields Too Large";
        case 500: return "Internal Server Error";
        default:  return "Status";
    }
}

/* Close a connection, releasing any TLS state. */
static inline void safe_http_teardown(int conn, void* ssl) {
#ifdef SAFE_ENABLE_TLS
    if (ssl) { SSL_shutdown((SSL*)ssl); SSL_free((SSL*)ssl); }
#endif
    (void)ssl;
    if (conn >= 0) close(conn);
}

/* Write a bodyless status response (used for 413/431 rejections). */
static inline void safe_http_write_status(int conn, void* ssl, int status) {
    char buf[160];
    int n = snprintf(buf, sizeof(buf),
                     "HTTP/1.1 %d %s\r\nContent-Length: 0\r\nConnection: close\r\n\r\n",
                     status, safe_http_reason(status));
    safe_conn_write(conn, ssl, buf, (size_t)n);
}

/* Abort the server on a malformed request / invalid handler response (surfaced, per policy). */
static inline void safe_http_fail(const char* msg) {
    fprintf(stderr, "http:serve: %s\n", msg);
    exit(1);
}

static inline int safe_http_listen(int port, const char* cert, const char* key) {
    int fd = socket(AF_INET, SOCK_STREAM, 0);
    if (fd < 0) return -1;
    int opt = 1;
    setsockopt(fd, SOL_SOCKET, SO_REUSEADDR, &opt, sizeof(opt));
    struct sockaddr_in addr;
    memset(&addr, 0, sizeof(addr));
    addr.sin_family = AF_INET;
    /* Loopback by default (SAFE_SERVE_BIND overrides) so a guest server is not exposed by default. */
    addr.sin_addr.s_addr = safe_bind_addr();
    addr.sin_port = htons((uint16_t)port);
    if (bind(fd, (struct sockaddr*)&addr, sizeof(addr)) < 0) { close(fd); return -1; }
    if (listen(fd, 16) < 0) { close(fd); return -1; }
    if (cert && cert[0]) {
#ifdef SAFE_ENABLE_TLS
        SSL_library_init();
        SSL_load_error_strings();
        OpenSSL_add_all_algorithms();
        safe_tls_ctx = SSL_CTX_new(TLS_server_method());
        if (!safe_tls_ctx
            /* Confine cert/key to the fs jail (safe_check_path uses one static buffer, so each
             * result is consumed by its SSL_CTX_* call before the next call overwrites it). */
            || SSL_CTX_use_certificate_file(safe_tls_ctx, safe_check_path(cert), SSL_FILETYPE_PEM) <= 0
            || SSL_CTX_use_PrivateKey_file(safe_tls_ctx, safe_check_path(key), SSL_FILETYPE_PEM) <= 0) {
            close(fd); return -1;
        }
#else
        (void)key; close(fd); return -1; /* TLS requested but not compiled in */
#endif
    }
    return fd;
}

/* Accept and parse one request, bounded by the server limits. Return codes:
 *   1  = request parsed, run the handler (out populated)
 *   0  = handled here (4xx written, or dropped) — keep serving, counts as a connection
 *  -1  = malformed request — caller aborts via safe_http_fail
 *  -2  = no connection within the accept poll — re-check the stop predicate, do not count */
static inline int safe_http_accept(int server, SafeHttpReq* out) {
    memset(out, 0, sizeof(*out));
    struct pollfd pfd;
    pfd.fd = server; pfd.events = POLLIN; pfd.revents = 0;
    int pr = poll(&pfd, 1, SAFE_SERVER_ACCEPT_POLL_MS);
    if (pr <= 0) return -2;
    int conn = accept(server, NULL, NULL);
    if (conn < 0) return -2;
    /* per-connection read timeout (slowloris) */
    struct timeval tv;
    tv.tv_sec = SAFE_SERVER_READ_TIMEOUT; tv.tv_usec = 0;
    setsockopt(conn, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));
    void* ssl = NULL;
#ifdef SAFE_ENABLE_TLS
    if (safe_tls_ctx) {
        SSL* s = SSL_new(safe_tls_ctx);
        SSL_set_fd(s, conn);
        if (SSL_accept(s) <= 0) { SSL_free(s); close(conn); return 0; } /* handshake failed: drop */
        ssl = s;
    }
#endif
    out->conn = conn;
    out->ssl = ssl;
    out->headers = safe_map_new();
    out->method = strdup("GET");
    out->path = strdup("/");
    out->body = strdup("");

    int toolong = 0;
    char* line = safe_http_readline(conn, ssl, &toolong);
    if (toolong) { if (line) free(line); safe_http_write_status(conn, ssl, 431); safe_http_teardown(conn, ssl); return 0; }
    if (!line) { safe_http_teardown(conn, ssl); return 0; } /* empty connection / probe: drop */
    char* sp1 = strchr(line, ' ');
    if (!sp1 || sp1 == line) { free(line); safe_http_teardown(conn, ssl); return -1; } /* malformed */
    *sp1 = '\0';
    char* pstart = sp1 + 1;
    char* sp2 = strchr(pstart, ' ');
    if (sp2) *sp2 = '\0';
    if (*pstart == '\0') { free(line); safe_http_teardown(conn, ssl); return -1; }      /* malformed */
    free(out->method); out->method = strdup(line);
    free(out->path); out->path = strdup(pstart);
    free(line);

    long content_length = 0;
    int header_count = 0;
    size_t header_bytes = 0;
    for (;;) {
        line = safe_http_readline(conn, ssl, &toolong);
        if (toolong) { if (line) free(line); safe_http_write_status(conn, ssl, 431); safe_http_teardown(conn, ssl); return 0; }
        if (!line) break;                       /* EOF before blank line — proceed leniently */
        if (line[0] == '\0') { free(line); break; }
        header_count++;
        header_bytes += strlen(line) + 2;
        if (header_count > SAFE_SERVER_MAX_HEADERS || header_bytes > (size_t)SAFE_SERVER_MAX_HEADER_BYTES) {
            free(line); safe_http_write_status(conn, ssl, 431); safe_http_teardown(conn, ssl); return 0;
        }
        char* colon = strchr(line, ':');
        if (colon) {
            *colon = '\0';
            char* val = colon + 1;
            while (*val == ' ') val++;
            safe_map_put_str(out->headers, line, val);
            if (strcasecmp(line, "content-length") == 0) content_length = atol(val);
        }
        free(line);
    }
    if (content_length < 0 || content_length > SAFE_SERVER_MAX_BODY) {
        safe_http_write_status(conn, ssl, 413); safe_http_teardown(conn, ssl); return 0;
    }
    if (content_length > 0) {
        char* body = (char*)malloc((size_t)content_length + 1);
        if (!body) { safe_http_teardown(conn, ssl); return 0; }
        long got = 0;
        while (got < content_length) {
            ssize_t r = safe_conn_read(conn, ssl, body + got, (size_t)(content_length - got));
            if (r <= 0) break;
            got += r;
        }
        body[got] = '\0';
        free(out->body); out->body = body;
    }
    return 1;
}

/* Serialize the handler's response. Returns 0 on success, -1 if a handler-supplied header carries
 * CR/LF/NUL (the caller aborts rather than splitting the response). */
static inline int safe_http_respond(SafeHttpReq* req, int64_t status, const char* body, SAFEMap* headers) {
    if (headers) {
        for (SAFEMapEntry* e = headers->head; e; e = e->order_next) {
            if (!safe_header_clean(e->key.string_key) || !safe_header_clean(e->value.string_val)) {
                safe_http_teardown(req->conn, req->ssl);
                return -1;
            }
        }
    }
    size_t blen = body ? strlen(body) : 0;
    char head[256];
    int hn = snprintf(head, sizeof(head), "HTTP/1.1 %lld %s\r\n", (long long)status, safe_http_reason(status));
    safe_conn_write(req->conn, req->ssl, head, (size_t)hn);
    if (headers) {
        for (SAFEMapEntry* e = headers->head; e; e = e->order_next) {
            const char* v = e->value.string_val ? e->value.string_val : "";
            size_t need = strlen(e->key.string_key) + strlen(v) + 5;
            char* hbuf = (char*)malloc(need);
            if (!hbuf) continue;
            int n = snprintf(hbuf, need, "%s: %s\r\n", e->key.string_key, v);
            safe_conn_write(req->conn, req->ssl, hbuf, (size_t)n);
            free(hbuf);
        }
    }
    char clbuf[96];
    int cn = snprintf(clbuf, sizeof(clbuf), "Content-Length: %zu\r\nConnection: close\r\n\r\n", blen);
    safe_conn_write(req->conn, req->ssl, clbuf, (size_t)cn);
    if (blen) safe_conn_write(req->conn, req->ssl, body, blen);
    safe_http_teardown(req->conn, req->ssl);
    return 0;
}

static inline void safe_http_close(int server) {
    if (server >= 0) close(server);
#ifdef SAFE_ENABLE_TLS
    if (safe_tls_ctx) { SSL_CTX_free(safe_tls_ctx); safe_tls_ctx = NULL; }
#endif
}

#endif /* SAFE_RUNTIME_H */
