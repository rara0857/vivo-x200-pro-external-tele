#define _GNU_SOURCE
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <math.h>
#include <sched.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/stat.h>
#include <time.h>
#include <unistd.h>

/* PD2405-only, opt-in OIS gain trial. Defaults to monitor-only. */
#define STATE "/data/user/0/com.android.camera/files/exttele_ois_state"
#define HAL_LIB "/vendor/lib64/mt6991/libcam.hal3a.oisdrv.so"
#define POINTER_MASK UINT64_C(0x00ffffffffffffff)
#define GAIN 2.35f
#define POLL_USEC 33000
#define AF_CHANGE_THRESHOLD 500
static volatile sig_atomic_t stop_requested;
static void on_signal(int sig) { (void)sig; stop_requested = 1; }

typedef struct { pid_t pid; uint64_t ms; int active; } Signal;
typedef struct { pid_t pid; float x, y; int32_t af; } Baseline;

/* Trial bounds are the user's measured bare-phone AF endpoints, not donor calibration. */
static float gain_factor(int mode, int32_t af) {
    if (mode == 1) return GAIN;
    if (mode == 3) return 0.0f;
    if (mode != 2 || af <= 0 || af > 65535) return 0.0f;
    int32_t bounded = af < 2175 ? 2175 : af > 14602 ? 14602 : af;
    return (2309.5f + 0.0423f * bounded) / 1000.0f;
}

static int af_changed(int32_t previous, int32_t current) {
    return llabs((int64_t)current - previous) > AF_CHANGE_THRESHOLD;
}

static uint64_t uptime_ms(void) {
    struct timespec now;
    clock_gettime(CLOCK_BOOTTIME, &now);
    return (uint64_t)now.tv_sec * 1000 + now.tv_nsec / 1000000;
}

static int join_init_mount_namespace(void) {
    struct stat init_ns, self_ns;
    if (stat("/proc/1/ns/mnt", &init_ns) || stat("/proc/self/ns/mnt", &self_ns))
        return 0;
    if (init_ns.st_ino == self_ns.st_ino) return 1;
    int fd = open("/proc/1/ns/mnt", O_RDONLY | O_CLOEXEC);
    if (fd < 0) return 0;
    int ok = setns(fd, CLONE_NEWNS) == 0;
    close(fd);
    if (ok) fprintf(stderr, "Joined init mount namespace after runtime restart\n");
    return ok;
}

static int command_name(pid_t pid, const char *expected) {
    char path[64], name[128] = {0};
    snprintf(path, sizeof(path), "/proc/%d/cmdline", pid);
    int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return 0;
    ssize_t n = read(fd, name, sizeof(name) - 1);
    close(fd);
    return n > 0 && !strcmp(name, expected);
}

static pid_t find_hal(void) {
    DIR *proc = opendir("/proc");
    if (!proc) return 0;
    struct dirent *e;
    pid_t found = 0;
    while ((e = readdir(proc))) {
        if (e->d_name[0] < '1' || e->d_name[0] > '9') continue;
        pid_t pid = (pid_t)atoi(e->d_name);
        if (command_name(pid, "/vendor/bin/hw/camerahalserver") ||
            command_name(pid, "camerahalserver")) { found = pid; break; }
    }
    closedir(proc);
    return found;
}

static int read_signal(Signal *out) {
    FILE *file = fopen(STATE, "r");
    if (!file) return 0;
    long pid; unsigned long long ms; int active;
    int valid = fscanf(file, "%ld %llu %d", &pid, &ms, &active) == 3;
    fclose(file);
    if (!valid || pid <= 0 || pid > 10000000 || active < 0 || active > 3) return 0;
    uint64_t now = uptime_ms();
    if (ms > now || now - ms > 1500 || !command_name((pid_t)pid, "com.android.camera")) return 0;
    out->pid = (pid_t)pid; out->ms = ms; out->active = active;
    return 1;
}

static int at(int fd, uintptr_t ptr, void *out, size_t n) {
    return pread(fd, out, n, (off_t)(ptr & POINTER_MASK)) == (ssize_t)n;
}

static int gain_pair_valid(float x, float y, int allow_zero) {
    if (!isfinite(x) || !isfinite(y)) return 0;
    if (allow_zero && x == 0.0f && y == 0.0f) return 1;
    return x >= 0.1f && x <= 20.0f && y >= 0.1f && y <= 20.0f;
}

static int read_baseline(pid_t pid, Baseline *out, int allow_zero) {
    char path[64], line[1024];
    snprintf(path, sizeof(path), "/proc/%d/maps", pid);
    FILE *maps = fopen(path, "r");
    if (!maps) return 0;
    uintptr_t base = 0;
    while (fgets(line, sizeof(line), maps)) {
        uintptr_t start; unsigned long offset;
        if (strstr(line, HAL_LIB) &&
            sscanf(line, "%lx-%*x %*4s %lx", &start, &offset) == 2 &&
            offset == 0) { base = start; break; }
    }
    fclose(maps);
    if (!base) return 0;
    snprintf(path, sizeof(path), "/proc/%d/mem", pid);
    int mem = open(path, O_RDONLY | O_CLOEXEC);
    if (mem < 0) return 0;
    uintptr_t driver = 0, ois = 0;
    char name[9] = {0};
    uint16_t transport = 0;
    uint32_t sensor = 0;
    int32_t index = -1;
    int32_t af = -1;
    float x = 0, y = 0;
    int ok = at(mem, base + 0x5c048 + 3 * 0x10, &driver, 8) && driver &&
             at(mem, driver + 0x88, &ois, 8) && ois &&
             at(mem, driver + 0x208, name, 8) &&
             at(mem, driver + 0x1c0, &transport, 2) &&
             at(mem, driver + 0xb70, &sensor, 4) &&
             at(mem, driver + 0x2c, &index, 4) &&
             at(mem, driver + 0x74, &af, 4) &&
             at(mem, ois + 0x30, &x, 4) && at(mem, ois + 0x34, &y, 4);
    close(mem);
    if (!ok || strcmp(name, "prism") || index != 3 || transport != 1 ||
        sensor != 0x11 || !gain_pair_valid(x, y, allow_zero)) return 0;
    *out = (Baseline){pid, x, y, af};
    return 1;
}

static int hf_command(int fd, int axis, float gain) {
    struct { uint32_t sensor; uint8_t command[64]; } msg = {0};
    msg.sensor = 0x55;
    msg.command[0] = 0x20;
    msg.command[1] = 8;
    msg.command[2] = 8;
    memcpy(msg.command + 4, &axis, 4);
    memcpy(msg.command + 8, &gain, 4);
    return ioctl(fd, 0xc0446107UL, &msg);
}

static int write_pair(float x, float y) {
    int fd = open("/dev/hf_manager", O_RDWR | O_CLOEXEC);
    if (fd < 0) return 0;
    uint8_t info[8] = {0x55};
    int ready = ioctl(fd, 0xc0086101UL, info) == 0 && info[4] == 1;
    int x_ok = ready && hf_command(fd, 26, x) >= 0;
    int y_ok = ready && hf_command(fd, 27, y) >= 0;
    close(fd);
    return x_ok && y_ok;
}

int main(int argc, char **argv) {
    if (argc == 2 && !strcmp(argv[1], "--self-test")) {
        int ok = fabsf(gain_factor(1, 2175) - 2.35f) < 0.00001f &&
                 fabsf(gain_factor(1, 14602) - 2.35f) < 0.00001f &&
                 fabsf(gain_factor(2, 2175) - 2.4015025f) < 0.00001f &&
                 fabsf(gain_factor(2, 14602) - 2.9271646f) < 0.00001f &&
                 gain_factor(2, 1) == gain_factor(2, 2175) &&
                 gain_factor(2, 65535) == gain_factor(2, 14602) &&
                 gain_factor(2, 0) == 0 && gain_factor(2, -1) == 0 &&
                 gain_factor(2, 65536) == 0 && gain_factor(0, 2175) == 0 &&
                 gain_factor(3, 2175) == 0 && gain_factor(3, 14602) == 0 &&
                 gain_pair_valid(0.0f, 0.0f, 1) &&
                 !gain_pair_valid(0.0f, 0.0f, 0) &&
                 !gain_pair_valid(0.0f, 1.0f, 1) &&
                 !af_changed(5000, 5000) &&
                 !af_changed(5000, 5500) &&
                 af_changed(5000, 5501) &&
                 af_changed(5500, 4999);
        printf("AF_FACTOR_TEST=%s fixed=%.7f infinity=%.7f near=%.7f\n",
               ok ? "PASS" : "FAIL", gain_factor(1, 2175),
               gain_factor(2, 2175), gain_factor(2, 14602));
        return ok ? 0 : 1;
    }
    if (argc == 2 && !strcmp(argv[1], "--diagnose")) {
        Signal state = {0};
        int fresh = read_signal(&state);
        pid_t hal = find_hal();
        Baseline base = {0};
        int baseline_ok = hal && read_baseline(hal, &base, 0);
        fprintf(stderr, "signal=%d camera_pid=%d age_ms=%llu mode=%d hal_pid=%d baseline=%d x=%.6f y=%.6f af=%d factor=%.7f\n",
                fresh, state.pid,
                fresh ? (unsigned long long)(uptime_ms() - state.ms) : 0ULL,
                state.active, hal, baseline_ok, base.x, base.y, base.af,
                gain_factor(state.active, base.af));
        return 0;
    }
    int apply = argc == 2 && !strcmp(argv[1], "--apply");
    if (argc != 1 && !apply) { fputs("usage: ois_gain_controller [--apply]\n", stderr); return 2; }
    signal(SIGINT, on_signal); signal(SIGTERM, on_signal);
    int engaged = 0;
    int last_gate = -1;
    unsigned sent_count = 0;
    Baseline previous = {0};
    uint64_t last_log = 0;
    int previous_mode = 0;
    fprintf(stderr, "OIS controller v1.8 %s, poll=33ms AF_delta>500 modes=0/off,1/fixed,2/AF-formula,3/zero\n", apply ? "APPLY" : "MONITOR");
    while (!stop_requested) {
        int namespace_ok = join_init_mount_namespace();
        Signal state = {0};
        int fresh = namespace_ok && read_signal(&state);
        pid_t hal = (engaged || (fresh && state.active)) ? find_hal() : 0;
        Baseline current = {0};
        int keeping_zero = engaged && previous_mode == 3 && state.active == 3 &&
                           hal == previous.pid;
        int baseline_ok = fresh && state.active && hal &&
                          read_baseline(hal, &current, keeping_zero);
        if (baseline_ok && keeping_zero && current.x == 0.0f && current.y == 0.0f)
            current = previous;
        float factor = baseline_ok ? gain_factor(state.active, current.af) : 0;
        int wanted = baseline_ok && (state.active == 3 || factor > 0);
        int gate = !namespace_ok ? 1 : !fresh ? 2 : !state.active ? 3 :
                   !hal ? 4 : !baseline_ok ? 5 : !wanted ? 6 : 0;
        if (gate != last_gate) {
            fprintf(stderr, "gate=%d camera_pid=%d hal_pid=%d\n", gate, state.pid, hal);
            last_gate = gate;
        }
        uint64_t now = uptime_ms();
        int changed = !engaged || previous.pid != hal ||
                      previous_mode != state.active ||
                      (state.active == 2 && af_changed(previous.af, current.af)) ||
                      fabsf(previous.x - current.x) > 0.0001f ||
                      fabsf(previous.y - current.y) > 0.0001f;
        if (wanted && changed) {
            if (!apply || write_pair(current.x * factor, current.y * factor)) {
                if (!engaged || previous_mode != state.active || now - last_log >= 1000) {
                    fprintf(stderr,
                        "active mode=%d af=%d factor=%.7f pid=%d base=%.6f/%.6f target=%.6f/%.6f%s\n",
                        state.active, current.af, factor, hal, current.x, current.y,
                        current.x * factor, current.y * factor,
                        apply ? " sent" : " dry-run");
                    last_log = now;
                }
                ++sent_count;
                engaged = 1; previous = current;
                previous_mode = state.active;
            } else {
                fprintf(stderr, "OIS gain command failed; attempting baseline restore\n");
                write_pair(current.x, current.y);
                engaged = 0;
            }
        } else if (!wanted && engaged) {
            if (apply && hal == previous.pid) {
                Baseline restore = previous;
                read_baseline(hal, &restore, 0);
                int ok = write_pair(restore.x, restore.y);
                fprintf(stderr, "inactive restore %.6f/%.6f %s after %u writes\n",
                        restore.x, restore.y, ok ? "sent" : "FAILED", sent_count);
            } else fprintf(stderr, "inactive: no active HAL write to restore\n");
            engaged = 0;
            sent_count = 0;
        }
        usleep(POLL_USEC);
    }
    if (apply && engaged && previous.pid == find_hal()) {
        Baseline restore = previous;
        read_baseline(previous.pid, &restore, 0);
        int ok = write_pair(restore.x, restore.y);
        fprintf(stderr, "shutdown restore %.6f/%.6f %s after %u writes\n",
                restore.x, restore.y, ok ? "sent" : "FAILED", sent_count);
        return ok ? 0 : 1;
    }
    return 0;
}
