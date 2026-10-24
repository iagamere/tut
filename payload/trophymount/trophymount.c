/*
 * trophymount: one-shot helper payload for the PS4 Download Monitor app (EXPERIMENTAL, never run on a console).
 *
 * Goal: a game whose trophies are not synced with PSN keeps its progress only inside
 *   /user/home/<account>/trophy/data/<NPWR>/trophy.img   (an encrypted PFS image)
 * whose key is /user/home/<account>/trophy/data/<NPWR>/sealedkey (sealed with the console's hardware key).
 * This program asks the console itself to open that image the way the open-source tool Apollo (apollo-ps4, GPLv3) does:
 *   1. decrypt sealedkey through /dev/sbl_srv (ioctl 0xC0845302),
 *   2. mount the image with sceFsMountSaveData from libSceFsInternalForVsh.sprx (budget "system"),
 *   3. copy the files it contains to /data/ezremote-client/trophy_export/<account>_<NPWR>/ (plain files the app can read),
 *   4. unmount.
 * Unlike Apollo it does NOT change the process credentials (no kernel writes at all), so it may simply be refused;
 * every step and every error code goes to /data/ezremote_trophy.log and to <export>/_result.txt.
 *
 * Input: empty folders named "<account 8 hex digits>_<NPWR id>" inside /data/ezremote-client/trophy_req (made by the app).
 * Without such a folder it only runs the checks (probe) and mounts nothing.
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdint.h>
#include <stdbool.h>
#include <stdarg.h>
#include <errno.h>
#include <fcntl.h>
#include <unistd.h>
#include <dirent.h>
#include <sys/stat.h>
#include <sys/ioctl.h>
#include <sys/types.h>

extern int sceKernelLoadStartModule(const char *name, size_t argc, const void *argv, unsigned flags, void *opt, int *res);
extern int sceKernelDlsym(int handle, const char *symbol, void **addr);

#define ENC_LEN 0x60
#define DEC_LEN 0x20
#define BASE "/data/ezremote-client"
#define REQ BASE "/trophy_req"
#define EXPORT BASE "/trophy_export"
#define MNT BASE "/tmnt"
#define LOGF "/data/ezremote_trophy.log"

typedef struct { bool readOnly; char *budgetid; } MountOpt;
typedef struct { bool dummy; } UmountOpt;

static int (*fsInitMount)(MountOpt *);
static int (*fsMount)(MountOpt *, const char *vol, const char *mnt, uint8_t key[DEC_LEN]);
static int (*fsInitUmount)(UmountOpt *);
static int (*fsUmount)(UmountOpt *, const char *mnt, int handle, bool ignore);

static FILE *g_res = NULL;

static void lg(const char *fmt, ...) {
    char buf[600]; va_list ap; va_start(ap, fmt); vsnprintf(buf, sizeof buf, fmt, ap); va_end(ap);
    FILE *f = fopen(LOGF, "a"); if (f) { fprintf(f, "%s\n", buf); fclose(f); }
    if (g_res) { fprintf(g_res, "%s\n", buf); fflush(g_res); }
}

static long sys3(long n, long a, long b, long c) {          /* FreeBSD/Orbis syscall: carry flag set on error */
    long r;
    __asm__ volatile("movq %4, %%rax\n\tmovq %1, %%rdi\n\tmovq %2, %%rsi\n\tmovq %3, %%rdx\n\tsyscall\n\tjnc 1f\n\tmovq $-1, %%rax\n1:\n\tmovq %%rax, %0"
                     : "=r"(r) : "r"(a), "r"(b), "r"(c), "r"(n) : "rax", "rdi", "rsi", "rdx", "rcx", "r11", "memory");
    return r;
}

static void mkdir_p(const char *path) {
    char t[512]; snprintf(t, sizeof t, "%s", path);
    for (char *p = t + 1; *p; p++) if (*p == '/') { *p = 0; mkdir(t, 0777); *p = '/'; }
    mkdir(t, 0777);
}

/* Apollo makes these three device nodes usable before mounting; we only try when opening them fails. */
static void fix_node(const char *path) {
    int fd = open(path, O_RDWR);
    if (fd >= 0) { close(fd); lg("%s: opens", path); return; }
    int e = errno; struct stat st; memset(&st, 0, sizeof st);
    if (stat(path, &st) != 0) { lg("%s: cannot open (errno %d) and stat fails (errno %d)", path, e, errno); return; }
    long r = sys3(14, (long)path, (long)(S_IFCHR | 0777), (long)st.st_dev);
    lg("%s: cannot open (errno %d); mknod returned %ld", path, e, r);
    fd = open(path, O_RDWR);
    lg("%s: open after mknod %s (errno %d)", path, fd >= 0 ? "OK" : "FAILED", fd >= 0 ? 0 : errno);
    if (fd >= 0) close(fd);
}

static int decrypt_key(const char *keyPath, uint8_t out[DEC_LEN]) {
    uint8_t enc[ENC_LEN];
    int fd = open(keyPath, O_RDONLY);
    if (fd < 0) { lg("open %s failed (errno %d)", keyPath, errno); return -1; }
    int n = (int)read(fd, enc, ENC_LEN); close(fd);
    if (n != ENC_LEN) { lg("%s: read %d of %d bytes", keyPath, n, ENC_LEN); return -2; }
    uint8_t data[ENC_LEN + DEC_LEN]; memset(data, 0, sizeof data); memcpy(data, enc, ENC_LEN);
    int s = open("/dev/sbl_srv", O_RDWR);
    if (s < 0) { lg("open /dev/sbl_srv failed (errno %d)", errno); return -3; }
    int r = ioctl(s, 0xc0845302, data); int e = errno; close(s);
    if (r == -1) { lg("ioctl decrypt sealedkey failed (errno %d)", e); return -4; }
    memcpy(out, data + ENC_LEN, DEC_LEN);
    return 0;
}

static int copy_file(const char *src, const char *dst) {
    int in = open(src, O_RDONLY); if (in < 0) return -1;
    int out = open(dst, O_WRONLY | O_CREAT | O_TRUNC, 0666); if (out < 0) { close(in); return -2; }
    static char buf[65536]; long total = 0; int n;
    while ((n = (int)read(in, buf, sizeof buf)) > 0) { if (write(out, buf, n) != n) { close(in); close(out); return -3; } total += n; if (total > (32L << 20)) break; }
    close(in); close(out); return 0;
}

static int copy_tree(const char *src, const char *dst, int depth, int *files) {
    DIR *d = opendir(src); if (!d) { lg("opendir %s failed (errno %d)", src, errno); return -1; }
    mkdir_p(dst);
    struct dirent *e;
    while ((e = readdir(d)) != NULL) {
        if (!strcmp(e->d_name, ".") || !strcmp(e->d_name, "..")) continue;
        char s[600], t[600]; snprintf(s, sizeof s, "%s/%s", src, e->d_name); snprintf(t, sizeof t, "%s/%s", dst, e->d_name);
        struct stat st; if (stat(s, &st) != 0) { lg("stat %s failed", s); continue; }
        if (S_ISDIR(st.st_mode)) { if (depth < 4) copy_tree(s, t, depth + 1, files); }
        else { int r = copy_file(s, t); lg("  %s (%lld bytes): %s", s, (long long)st.st_size, r == 0 ? "copied" : "COPY FAILED"); if (r == 0) (*files)++; }
    }
    closedir(d); return 0;
}

static void do_one(const char *acct, const char *npwr) {
    char exp[300], mnt[300], key[300], vol[300], resp[340];
    snprintf(exp, sizeof exp, EXPORT "/%s_%s", acct, npwr); mkdir_p(exp);
    snprintf(resp, sizeof resp, "%s/_result.txt", exp);
    g_res = fopen(resp, "w");
    lg("== %s %s", acct, npwr);
    snprintf(key, sizeof key, "/user/home/%s/trophy/data/%s/sealedkey", acct, npwr);
    snprintf(vol, sizeof vol, "/user/home/%s/trophy/data/%s/trophy.img", acct, npwr);
    snprintf(mnt, sizeof mnt, MNT "/%s", npwr); mkdir_p(mnt);
    uint8_t dk[DEC_LEN]; int r = decrypt_key(key, dk);
    if (r != 0) { lg("RESULT: FAILED at step decrypt-sealedkey (code %d)", r); goto done; }
    lg("sealedkey decrypted");
    if (!fsMount || !fsInitMount) { lg("RESULT: FAILED: sceFsMountSaveData not available"); goto done; }
    MountOpt mo; memset(&mo, 0, sizeof mo); fsInitMount(&mo); mo.budgetid = "system";
    r = fsMount(&mo, vol, mnt, dk);
    memset(dk, 0, sizeof dk);
    if (r < 0) { lg("RESULT: FAILED at step mount (sceFsMountSaveData returned 0x%X)", r); goto done; }
    lg("mounted at %s", mnt);
    int files = 0; copy_tree(mnt, exp, 0, &files);
    lg("copied %d files", files);
    UmountOpt uo; memset(&uo, 0, sizeof uo); if (fsInitUmount) fsInitUmount(&uo);
    int u = fsUmount ? fsUmount(&uo, mnt, 0, 0) : -1;
    lg("unmount returned 0x%X", u);
    lg("RESULT: OK (%d files)", files);
done:
    rmdir(mnt);
    if (g_res) { fclose(g_res); g_res = NULL; }
}

int main(void) {
    mkdir_p(BASE); mkdir_p(REQ); mkdir_p(EXPORT);
    FILE *f = fopen(LOGF, "w"); if (f) fclose(f);
    lg("trophymount start; uid %d euid %d", (int)getuid(), (int)geteuid());
    FILE *j = fopen("/user/.jailbreak", "w"); lg("write test in /user: %s", j ? "allowed" : "refused"); if (j) { fclose(j); remove("/user/.jailbreak"); }
    fix_node("/dev/sbl_srv"); fix_node("/dev/pfsctldev"); fix_node("/dev/lvdctl");
    int h = sceKernelLoadStartModule("/system/priv/lib/libSceFsInternalForVsh.sprx", 0, NULL, 0, NULL, NULL);
    lg("load libSceFsInternalForVsh.sprx: %d", h);
    if (h >= 0) {
        sceKernelDlsym(h, "sceFsInitMountSaveDataOpt", (void **)&fsInitMount);
        sceKernelDlsym(h, "sceFsMountSaveData", (void **)&fsMount);
        sceKernelDlsym(h, "sceFsInitUmountSaveDataOpt", (void **)&fsInitUmount);
        sceKernelDlsym(h, "sceFsUmountSaveData", (void **)&fsUmount);
        lg("symbols: initMount %s, mount %s, initUmount %s, umount %s", fsInitMount ? "yes" : "NO", fsMount ? "yes" : "NO", fsInitUmount ? "yes" : "NO", fsUmount ? "yes" : "NO");
    }
    int n = 0;
    DIR *d = opendir(REQ);
    if (d) {
        struct dirent *e;
        while ((e = readdir(d)) != NULL) {
            char acct[16], npwr[32];
            if (strlen(e->d_name) < 10 || e->d_name[8] != '_' || strncmp(e->d_name + 9, "NPWR", 4) != 0) continue;
            if (strlen(e->d_name) > 9 + 20) continue;
            memcpy(acct, e->d_name, 8); acct[8] = 0; snprintf(npwr, sizeof npwr, "%s", e->d_name + 9);
            for (char *p = acct; *p; p++) if (!((*p >= '0' && *p <= '9') || (*p >= 'a' && *p <= 'f'))) goto skip;
            for (char *p = npwr; *p; p++) if (!((*p >= '0' && *p <= '9') || (*p >= 'A' && *p <= 'Z') || *p == '_')) goto skip;
            do_one(acct, npwr); n++;
            { char rq[400]; snprintf(rq, sizeof rq, REQ "/%s", e->d_name); rmdir(rq); }
            skip: ;
        }
        closedir(d);
    }
    lg("finished; %d request(s)", n);
    FILE *dn = fopen(BASE "/trophy_helper_done.txt", "w"); if (dn) { fprintf(dn, "done %d\n", n); fclose(dn); }
    return 0;
}
