/*
 * netstub — LD_PRELOAD library that replaces getifaddrs()/freeifaddrs()
 * with an implementation that avoids NETLINK sockets.
 *
 * On Android 11+ inside proot, glibc's getifaddrs() tries to bind() a
 * NETLINK socket. SELinux denies this for untrusted_app UIDs (≥ 10000),
 * returning EACCES. Node.js / libuv calls getifaddrs() and abort()s on
 * failure, killing VS Code (Electron) with SIGABRT.
 *
 * This library reads /proc/net/dev and /proc/net/if_inet6, then uses
 * ioctl() on AF_INET sockets to gather addresses, flags, and netmasks —
 * no NETLINK required.
 *
 * Build:
 *   aarch64-linux-gnu-gcc -shared -fPIC -O2 -o libnetstub.so netstub.c
 *
 * Usage:
 *   LD_PRELOAD=/path/to/libnetstub.so node ...
 */

#define _GNU_SOURCE

#include <errno.h>
#include <ifaddrs.h>
#include <net/if.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/socket.h>
#include <unistd.h>
#include <signal.h>
#include <execinfo.h>
#include <fcntl.h>

/* ---------- hex parser (avoids strtoul → __isoc23_strtoul on glibc 2.38) */

/*
 * Parse a hexadecimal number from `s`, skipping leading whitespace.
 * If `endp` is non-NULL, store a pointer to the first unparsed character.
 * Returns the parsed value (0 if no hex digits found).
 */
static unsigned long parse_hex(const char *s, const char **endp)
{
    unsigned long val = 0;
    const char *p = s;
    while (*p == ' ' || *p == '\t')
        p++;
    int found = 0;
    for (;;) {
        char c = *p;
        if (c >= '0' && c <= '9')      { val = val * 16 + (unsigned long)(c - '0'); }
        else if (c >= 'a' && c <= 'f')  { val = val * 16 + (unsigned long)(c - 'a' + 10); }
        else if (c >= 'A' && c <= 'F')  { val = val * 16 + (unsigned long)(c - 'A' + 10); }
        else break;
        found = 1;
        p++;
    }
    if (endp) *endp = found ? p : s;
    return val;
}

/* ---------- helpers --------------------------------------------------- */

/*
 * Allocate a single block for an ifaddrs entry plus all its sockaddrs and
 * the interface name string.  Layout inside the block:
 *
 *   [ struct ifaddrs ]
 *   [ struct sockaddr_storage ] (ifa_addr)
 *   [ struct sockaddr_storage ] (ifa_netmask)
 *   [ struct sockaddr_storage ] (ifa_broadaddr)
 *   [ char[IFNAMSIZ] ]         (ifa_name)
 *
 * This makes freeifaddrs() trivial — just walk the list and free() each
 * entry.
 */
struct entry_block {
    struct ifaddrs          ifa;
    struct sockaddr_storage addr;
    struct sockaddr_storage netmask;
    struct sockaddr_storage broadaddr;
    char                    name[IFNAMSIZ];
};

static struct ifaddrs *alloc_entry(const char *ifname)
{
    struct entry_block *blk = calloc(1, sizeof(*blk));
    if (!blk)
        return NULL;

    strncpy(blk->name, ifname, IFNAMSIZ - 1);
    blk->name[IFNAMSIZ - 1] = '\0';

    blk->ifa.ifa_name      = blk->name;
    blk->ifa.ifa_addr      = (struct sockaddr *)&blk->addr;
    blk->ifa.ifa_netmask   = (struct sockaddr *)&blk->netmask;
    blk->ifa.ifa_broadaddr = (struct sockaddr *)&blk->broadaddr;
    blk->ifa.ifa_data      = NULL;
    blk->ifa.ifa_next      = NULL;

    return &blk->ifa;
}

/* Prepend an entry to the head of the list.  Returns the new head. */
static struct ifaddrs *prepend(struct ifaddrs *head, struct ifaddrs *entry)
{
    entry->ifa_next = head;
    return entry;
}

/* Fetch interface flags via ioctl.  Returns 0 on success. */
static int get_if_flags(int sock, const char *ifname, unsigned int *flags)
{
    struct ifreq ifr;
    memset(&ifr, 0, sizeof(ifr));
    strncpy(ifr.ifr_name, ifname, IFNAMSIZ - 1);
    if (ioctl(sock, SIOCGIFFLAGS, &ifr) < 0)
        return -1;
    *flags = (unsigned int)ifr.ifr_flags;
    return 0;
}

/* Build a /128-style IPv6 prefix mask from a prefix length (0-128). */
static void make_ipv6_mask(int prefix_len, struct sockaddr_in6 *mask)
{
    memset(mask, 0, sizeof(*mask));
    mask->sin6_family = AF_INET6;
    unsigned char *p = mask->sin6_addr.s6_addr;
    int full_bytes = prefix_len / 8;
    int remaining  = prefix_len % 8;
    int i;
    for (i = 0; i < full_bytes && i < 16; i++)
        p[i] = 0xFF;
    if (i < 16 && remaining)
        p[i] = (unsigned char)(0xFF << (8 - remaining));
}

/* ---------- /proc/net/dev — interface enumeration ---------------------- */

/* Maximum interfaces we'll handle. */
#define MAX_INTERFACES 64

/*
 * Parse /proc/net/dev and fill `names` with up to `max` interface names.
 * Returns the number found.
 */
static int enumerate_interfaces(char names[][IFNAMSIZ], int max)
{
    FILE *fp = fopen("/proc/net/dev", "r");
    if (!fp)
        return 0;

    char line[512];
    int count = 0;

    /* Skip two header lines. */
    if (!fgets(line, sizeof(line), fp)) { fclose(fp); return 0; }
    if (!fgets(line, sizeof(line), fp)) { fclose(fp); return 0; }

    while (fgets(line, sizeof(line), fp) && count < max) {
        /* Format: "  ifname: <stats...>" */
        char *colon = strchr(line, ':');
        if (!colon)
            continue;
        *colon = '\0';

        /* Trim leading whitespace. */
        char *start = line;
        while (*start == ' ' || *start == '\t')
            start++;

        if (*start == '\0')
            continue;

        strncpy(names[count], start, IFNAMSIZ - 1);
        names[count][IFNAMSIZ - 1] = '\0';
        count++;
    }

    fclose(fp);
    return count;
}

/* ---------- IPv4 entries ----------------------------------------------- */

/*
 * For each interface, try SIOCGIFADDR.  If the interface has an IPv4
 * address, create an ifaddrs entry with addr, netmask, broadcast, and
 * flags.
 */
static struct ifaddrs *collect_ipv4(int sock,
                                    char names[][IFNAMSIZ], int count,
                                    struct ifaddrs *head)
{
    for (int i = 0; i < count; i++) {
        struct ifreq ifr;
        memset(&ifr, 0, sizeof(ifr));
        strncpy(ifr.ifr_name, names[i], IFNAMSIZ - 1);

        /* Must have an IPv4 address. */
        if (ioctl(sock, SIOCGIFADDR, &ifr) < 0)
            continue;

        struct ifaddrs *ifa = alloc_entry(names[i]);
        if (!ifa)
            continue;

        /* Address. */
        memcpy(ifa->ifa_addr, &ifr.ifr_addr, sizeof(struct sockaddr_in));

        /* Flags. */
        unsigned int flags = 0;
        if (get_if_flags(sock, names[i], &flags) == 0)
            ifa->ifa_flags = flags;

        /* Netmask. */
        memset(&ifr, 0, sizeof(ifr));
        strncpy(ifr.ifr_name, names[i], IFNAMSIZ - 1);
        if (ioctl(sock, SIOCGIFNETMASK, &ifr) == 0)
            memcpy(ifa->ifa_netmask, &ifr.ifr_addr, sizeof(struct sockaddr_in));

        /* Broadcast address — only for non-loopback, non-point-to-point. */
        if (!(flags & IFF_LOOPBACK) && !(flags & IFF_POINTOPOINT)) {
            memset(&ifr, 0, sizeof(ifr));
            strncpy(ifr.ifr_name, names[i], IFNAMSIZ - 1);
            if (ioctl(sock, SIOCGIFBRDADDR, &ifr) == 0)
                memcpy(ifa->ifa_broadaddr, &ifr.ifr_addr,
                       sizeof(struct sockaddr_in));
        }

        head = prepend(head, ifa);
    }
    return head;
}

/* ---------- IPv6 entries ----------------------------------------------- */

/*
 * Parse /proc/net/if_inet6 (one line per IPv6 address).
 *
 * Format per line:
 *   <32-hex-chars> <index> <prefix_len> <scope> <flags> <ifname>
 *
 * Example:
 *   fe800000000000000000000000000001 03 40 20 80 eth0
 */
static struct ifaddrs *collect_ipv6(int sock, struct ifaddrs *head)
{
    FILE *fp = fopen("/proc/net/if_inet6", "r");
    if (!fp)
        return head;

    char line[256];
    while (fgets(line, sizeof(line), fp)) {
        char hex_addr[33];
        unsigned long idx, prefix_len, scope, flags;
        char ifname[IFNAMSIZ];

        /*
         * Parse /proc/net/if_inet6 manually (no sscanf) to avoid
         * __isoc23_sscanf which requires GLIBC_2.38.
         *
         * Format: "<32 hex chars> <idx> <prefix> <scope> <flags> <name>\n"
         */
        const char *p = line;

        /* Skip leading whitespace. */
        while (*p == ' ' || *p == '\t') p++;

        /* Field 1: 32 hex characters for the IPv6 address. */
        int hlen = 0;
        while (hlen < 32 && ((*p >= '0' && *p <= '9') ||
                             (*p >= 'a' && *p <= 'f') ||
                             (*p >= 'A' && *p <= 'F'))) {
            hex_addr[hlen++] = *p++;
        }
        if (hlen != 32) continue;
        hex_addr[32] = '\0';

        /* Fields 2-5: four hex integers separated by whitespace. */
        const char *endp;

        if (*p != ' ' && *p != '\t') continue;
        idx = parse_hex(p, &endp);
        if (endp == p) continue;
        p = endp;

        if (*p != ' ' && *p != '\t') continue;
        prefix_len = parse_hex(p, &endp);
        if (endp == p) continue;
        p = endp;

        if (*p != ' ' && *p != '\t') continue;
        scope = parse_hex(p, &endp);
        if (endp == p) continue;
        p = endp;

        if (*p != ' ' && *p != '\t') continue;
        flags = parse_hex(p, &endp);
        if (endp == p) continue;
        p = endp;

        /* Field 6: interface name. */
        while (*p == ' ' || *p == '\t') p++;
        int nlen = 0;
        while (nlen < IFNAMSIZ - 1 && *p != '\0' && *p != '\n' &&
               *p != ' ' && *p != '\t') {
            ifname[nlen++] = *p++;
        }
        if (nlen == 0) continue;
        ifname[nlen] = '\0';

        struct ifaddrs *ifa = alloc_entry(ifname);
        if (!ifa)
            continue;

        /* Parse the 32-hex-char address into in6_addr. */
        struct sockaddr_in6 *sa6 = (struct sockaddr_in6 *)ifa->ifa_addr;
        sa6->sin6_family = AF_INET6;
        sa6->sin6_scope_id = (scope == 0x20) ? (unsigned int)idx : 0;
        for (int i = 0; i < 16; i++) {
            char hex_byte[3] = { hex_addr[i * 2], hex_addr[i * 2 + 1], '\0' };
            sa6->sin6_addr.s6_addr[i] = (unsigned char)parse_hex(hex_byte, NULL);
        }

        /* Netmask from prefix length. */
        make_ipv6_mask(prefix_len,
                       (struct sockaddr_in6 *)ifa->ifa_netmask);

        /* Interface flags. */
        unsigned int if_flags = 0;
        if (get_if_flags(sock, ifname, &if_flags) == 0)
            ifa->ifa_flags = if_flags;

        /* No broadcast for IPv6. */
        ifa->ifa_broadaddr = NULL;

        head = prepend(head, ifa);
    }

    fclose(fp);
    return head;
}

/* ---------- public API ------------------------------------------------ */

__attribute__((visibility("default")))
int getifaddrs(struct ifaddrs **ifap)
{
    if (!ifap) {
        errno = EINVAL;
        return -1;
    }
    *ifap = NULL;

    /* We need a socket for ioctl. */
    int sock = socket(AF_INET, SOCK_DGRAM, 0);
    if (sock < 0)
        return -1;

    /* 1. Enumerate interfaces from /proc/net/dev. */
    char names[MAX_INTERFACES][IFNAMSIZ];
    int count = enumerate_interfaces(names, MAX_INTERFACES);

    struct ifaddrs *head = NULL;

    /* 2. IPv4 entries. */
    head = collect_ipv4(sock, names, count, head);

    /* 3. IPv6 entries. */
    head = collect_ipv6(sock, head);

    close(sock);

    *ifap = head;
    return 0;
}

__attribute__((visibility("default")))
void freeifaddrs(struct ifaddrs *ifa)
{
    while (ifa) {
        struct ifaddrs *next = ifa->ifa_next;
        free(ifa);  /* single-block allocation — one free per entry */
        ifa = next;
    }
}

/* ====================================================================== */
/* SIGABRT crash handler — captures backtraces when Electron/Node crashes  */
/* inside proot where strace is unavailable (proot uses ptrace).           */
/*                                                                         */
/* ALL I/O in the signal handler uses only async-signal-safe functions:     */
/*   write(), open(), read(), close(), raise(), signal()                   */
/* No fprintf, printf, malloc, free, sprintf, snprintf, strlen, sscanf.    */
/* ====================================================================== */

/*
 * Async-signal-safe write helpers.  Return values are intentionally ignored
 * — there's nothing useful we can do if stderr writes fail during a crash.
 */
static inline void safe_write(int fd, const void *buf, size_t count)
{
    ssize_t __attribute__((unused)) r = write(fd, buf, count);
}

#define STATIC_WRITE(fd, literal) \
    safe_write((fd), (literal), sizeof(literal) - 1)

/*
 * Async-signal-safe integer-to-string for signal numbers (small positive
 * integers).  Writes decimal digits into a stack buffer and returns a
 * pointer to the first digit.  `len_out` receives the number of digits.
 */
static const char *sig_to_str(int sig, char *buf, int buflen, int *len_out)
{
    unsigned int val = (unsigned int)(sig < 0 ? -sig : sig);
    int pos = buflen - 1;
    buf[pos] = '\0';
    if (val == 0) {
        buf[--pos] = '0';
    } else {
        while (val > 0 && pos > 0) {
            buf[--pos] = '0' + (char)(val % 10);
            val /= 10;
        }
    }
    *len_out = (buflen - 1) - pos;
    return &buf[pos];
}

/*
 * Dump a proc file to stderr using only async-signal-safe I/O.
 * Reads up to `max_lines` lines (or the whole file if fewer).
 */
static void dump_proc_file(const char *path,
                           const char *header, int header_len,
                           int max_lines)
{
    safe_write(STDERR_FILENO, header, (size_t)header_len);

    int fd = open(path, O_RDONLY);
    if (fd < 0) {
        STATIC_WRITE(STDERR_FILENO, "  (could not open)\n");
        return;
    }

    char buf[4096];
    int lines = 0;
    ssize_t n;
    while ((n = read(fd, buf, sizeof(buf))) > 0) {
        if (max_lines <= 0) {
            /* No line limit — dump everything. */
            safe_write(STDERR_FILENO, buf, (size_t)n);
        } else {
            /* Write up to max_lines lines. */
            ssize_t i;
            for (i = 0; i < n; i++) {
                if (buf[i] == '\n') {
                    lines++;
                    if (lines >= max_lines) {
                        /* Write up to and including this newline, then stop. */
                        safe_write(STDERR_FILENO, buf, (size_t)(i + 1));
                        STATIC_WRITE(STDERR_FILENO,
                                     "  ... (truncated)\n");
                        close(fd);
                        return;
                    }
                }
            }
            /* Haven't hit the limit yet — write the whole chunk. */
            safe_write(STDERR_FILENO, buf, (size_t)n);
        }
    }

    close(fd);
}

/*
 * The actual signal handler.  Async-signal-safe throughout.
 */
static void crash_handler(int sig)
{
    /* --- Header --- */
    STATIC_WRITE(STDERR_FILENO,
                 "\n=== NETSTUB CRASH HANDLER: signal ");
    {
        char numbuf[16];
        int numlen;
        const char *numstr = sig_to_str(sig, numbuf, sizeof(numbuf), &numlen);
        safe_write(STDERR_FILENO, numstr, (size_t)numlen);
    }
    STATIC_WRITE(STDERR_FILENO, " caught ===\n");

    /* --- Backtrace --- */
    STATIC_WRITE(STDERR_FILENO, "\n--- backtrace ---\n");
    {
        void *frames[64];
        int nframes = backtrace(frames, 64);
        if (nframes > 0) {
            backtrace_symbols_fd(frames, nframes, STDERR_FILENO);
        } else {
            STATIC_WRITE(STDERR_FILENO, "  (no frames captured)\n");
        }
    }

    /* --- /proc/self/maps --- */
    {
        static const char maps_path[] = "/proc/self/maps";
        static const char maps_hdr[]  = "\n--- /proc/self/maps (first 100 lines) ---\n";
        dump_proc_file(maps_path,
                       maps_hdr, sizeof(maps_hdr) - 1,
                       100);
    }

    /* --- /proc/self/status --- */
    {
        static const char status_path[] = "/proc/self/status";
        static const char status_hdr[]  = "\n--- /proc/self/status ---\n";
        dump_proc_file(status_path,
                       status_hdr, sizeof(status_hdr) - 1,
                       0 /* no line limit */);
    }

    /* --- Footer --- */
    STATIC_WRITE(STDERR_FILENO, "\n=== END CRASH HANDLER ===\n");

    /* Re-raise with default handler so the process still terminates with the
     * correct signal and exit status. */
    signal(sig, SIG_DFL);
    raise(sig);
}

/*
 * Detect Electron/Chromium child processes that inherit LD_PRELOAD but
 * should not have our signal handlers installed.
 *
 * Old-style Node.js fork children set ELECTRON_RUN_AS_NODE=1.
 * Newer Electron UtilityProcess children use Chromium's --type= arg
 * (--type=utility, --type=renderer, --type=gpu-process, --type=zygote).
 * The main browser process does NOT have --type=.
 */
static int is_electron_child(void)
{
    static int cached = -1;
    if (cached != -1)
        return cached;

    /* Check ELECTRON_RUN_AS_NODE first (old-style Node children) */
    if (getenv("ELECTRON_RUN_AS_NODE")) {
        cached = 1;
        return 1;
    }

    /* Check /proc/self/cmdline for --type= (Chromium child processes).
     * cmdline args are NUL-separated; search each arg for the prefix. */
    cached = 0;
    int fd = open("/proc/self/cmdline", O_RDONLY);
    if (fd >= 0) {
        char buf[4096];
        ssize_t n = read(fd, buf, sizeof(buf) - 1);
        close(fd);
        if (n > 0) {
            buf[n] = '\0';
            for (ssize_t i = 0; i < n; i++) {
                if (buf[i] == '\0')
                    continue;
                if (strncmp(&buf[i], "--type=", 7) == 0) {
                    cached = 1;
                    break;
                }
                /* Skip to next NUL */
                while (i < n && buf[i] != '\0')
                    i++;
            }
        }
    }

    return cached;
}

/*
 * Library constructor — runs when libnetstub.so is loaded (via LD_PRELOAD).
 * Installs crash signal handlers (SIGABRT, SIGTRAP, SIGSEGV).  SA_RESETHAND
 * makes each one-shot: if the handler itself re-raises, the default fires.
 */
__attribute__((constructor))
static void install_crash_handler(void)
{
    /* Skip signal handlers for Electron child processes (extensionHost,
     * fileWatcher, shared-process, gpu-process, zygote).  They inherit
     * LD_PRELOAD but our SIGSEGV handler conflicts with Chromium's
     * Breakpad/Crashpad and causes exit code 11 crashes. */
    if (is_electron_child())
        return;

    struct sigaction sa;

    /* Zero the struct using a loop — memset is safe but let's be explicit
     * about not pulling in anything unexpected. */
    char *p = (char *)&sa;
    for (unsigned long i = 0; i < sizeof(sa); i++)
        p[i] = 0;

    sa.sa_handler = crash_handler;
    sa.sa_flags   = SA_RESETHAND;  /* one-shot: reverts to SIG_DFL after first delivery */
    sigemptyset(&sa.sa_mask);

    sigaction(SIGABRT, &sa, NULL);
    sigaction(SIGTRAP, &sa, NULL);
    sigaction(SIGSEGV, &sa, NULL);
}
