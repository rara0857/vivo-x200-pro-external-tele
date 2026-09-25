#define _GNU_SOURCE
#include "frida-core.h"

#include <dirent.h>
#include <errno.h>
#include <signal.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>

#define PROVIDER "vendor.vivo.hardware.camera3rd.provider@1.0-service"
#define CAMERA "com.android.camera"
#define STATE "/data/user/0/com.android.camera/files/exttele_ois_state"
#define VAF "/vendor/lib64/libvivo.vaf.system.so"
#define META "/vendor/lib64/libvivo.algo.metadata.so"
#define VAF_SHA "cf9116660b5ea5be7cbb297be7dddb3334a486c1e886d752bc72cb8d4f8edaf8"
#define META_SHA "2bc7dbc8e88bad452e60b6ed594310fb72ebb2100ff7f6740a8b7a1d3fac5154"

static volatile sig_atomic_t stopping;
static void handle_signal(int sig) { (void)sig; stopping = 1; }

static uint64_t uptime_ms(void) {
    struct timespec time;
    clock_gettime(CLOCK_BOOTTIME, &time);
    return (uint64_t)time.tv_sec * 1000 + time.tv_nsec / 1000000;
}

static int has_cmdline(pid_t pid, const char *name) {
    char path[64], line[256] = {0};
    snprintf(path, sizeof(path), "/proc/%d/cmdline", pid);
    FILE *file = fopen(path, "rb");
    if (!file) return 0;
    size_t count = fread(line, 1, sizeof(line) - 1, file);
    fclose(file);
    if (!count) return 0;
    const char *base = strrchr(line, '/');
    return !strcmp(base ? base + 1 : line, name);
}

static pid_t find_provider(void) {
    DIR *dir = opendir("/proc");
    if (!dir) return 0;
    struct dirent *entry;
    pid_t found = 0;
    while ((entry = readdir(dir))) {
        if (entry->d_name[0] < '1' || entry->d_name[0] > '9') continue;
        pid_t pid = (pid_t)atoi(entry->d_name);
        if (has_cmdline(pid, PROVIDER)) { found = pid; break; }
    }
    closedir(dir);
    return found;
}

static int watermark_enabled(void) {
    FILE *file = fopen(STATE, "r");
    if (!file) return 0;
    long pid = 0;
    unsigned long long updated = 0;
    int ois = -1, watermark = -1;
    int fields = fscanf(file, "%ld %llu %d %d", &pid, &updated, &ois, &watermark);
    fclose(file);
    uint64_t now = uptime_ms();
    return fields == 4 && pid > 0 && pid < 10000000 &&
           updated <= now && now - updated <= 1500 &&
           ois >= 0 && ois <= 3 && watermark == 1 &&
           has_cmdline((pid_t)pid, CAMERA);
}

static int hash_matches(const char *path, const char *expected) {
    char command[256], digest[65] = {0};
    snprintf(command, sizeof(command), "/system/bin/sha256sum %s", path);
    FILE *process = popen(command, "r");
    if (!process) return 0;
    int good = fscanf(process, "%64s", digest) == 1 && !strcmp(digest, expected);
    int status = pclose(process);
    return good && status == 0;
}

static char *read_script(const char *path) {
    FILE *file = fopen(path, "rb");
    if (!file) return NULL;
    if (fseek(file, 0, SEEK_END)) { fclose(file); return NULL; }
    long size = ftell(file);
    if (size < 1 || size > 65536 || fseek(file, 0, SEEK_SET)) {
        fclose(file); return NULL;
    }
    char *script = calloc((size_t)size + 1, 1);
    if (!script || fread(script, 1, (size_t)size, file) != (size_t)size) {
        free(script); fclose(file); return NULL;
    }
    fclose(file);
    return script;
}

static void on_message(FridaScript *script, const gchar *message,
                       GBytes *data, gpointer user_data) {
    (void)script; (void)data; (void)user_data;
    fprintf(stderr, "hook %s\n", message);
}

static void clear_hook(FridaScript **script, FridaSession **session) {
    if (*script) {
        frida_script_post(*script,
                          "{\"type\":\"state\",\"payload\":{\"enabled\":false}}",
                          NULL);
        frida_script_unload_sync(*script, NULL, NULL);
        frida_unref(*script);
        *script = NULL;
    }
    if (*session) {
        if (!frida_session_is_detached(*session))
            frida_session_detach_sync(*session, NULL, NULL);
        frida_unref(*session);
        *session = NULL;
    }
}

static int attach_hook(FridaDevice *device, pid_t pid, const char *source,
                       FridaScript **script_out, FridaSession **session_out) {
    GError *error = NULL;
    FridaSession *session = frida_device_attach_sync(device, (guint)pid,
                                                     NULL, NULL, &error);
    if (!session) goto failed;
    FridaScriptOptions *options = frida_script_options_new();
    frida_script_options_set_name(options, "pd2405-watermark-focal");
    frida_script_options_set_runtime(options, FRIDA_SCRIPT_RUNTIME_QJS);
    FridaScript *script = frida_session_create_script_sync(session, source,
                                                            options, NULL, &error);
    g_clear_object(&options);
    if (!script) { *session_out = session; goto failed; }
    g_signal_connect(script, "message", G_CALLBACK(on_message), NULL);
    frida_script_load_sync(script, NULL, &error);
    if (error) {
        *script_out = script; *session_out = session;
        goto failed;
    }
    *script_out = script;
    *session_out = session;
    fprintf(stderr, "attached to provider pid=%d\n", pid);
    return 1;
failed:
    fprintf(stderr, "attach failed pid=%d: %s\n", pid,
            error ? error->message : "unknown error");
    if (error) g_error_free(error);
    clear_hook(script_out, session_out);
    return 0;
}

int main(int argc, char **argv) {
    if (argc != 2 && !(argc == 3 && !strcmp(argv[1], "--probe-hook"))) {
        fprintf(stderr, "usage: %s <watermark_hook.js> | --probe | --probe-hook <watermark_hook.js>\n", argv[0]);
        return 2;
    }
    if (!hash_matches(VAF, VAF_SHA) || !hash_matches(META, META_SHA)) {
        fputs("refusing: live vendor camera library hash mismatch\n", stderr);
        return 1;
    }
    int probe = !strcmp(argv[1], "--probe") || !strcmp(argv[1], "--probe-hook");
    char *source = !strcmp(argv[1], "--probe") ?
        strdup("send({kind:'probe-ready',pid:Process.id});") :
        read_script(argc == 3 ? argv[2] : argv[1]);
    if (!source) { fputs("refusing: hook script missing/too large\n", stderr); return 1; }
    signal(SIGINT, handle_signal);
    signal(SIGTERM, handle_signal);
    frida_init();
    FridaDeviceManager *manager = frida_device_manager_new();
    GError *error = NULL;
    FridaDeviceList *devices = frida_device_manager_enumerate_devices_sync(
        manager, NULL, &error);
    if (!devices) {
        fprintf(stderr, "device enumeration failed: %s\n", error->message);
        g_error_free(error); free(source); return 1;
    }
    FridaDevice *device = NULL;
    for (int i = 0; i < frida_device_list_size(devices); i++) {
        FridaDevice *candidate = frida_device_list_get(devices, i);
        if (frida_device_get_dtype(candidate) == FRIDA_DEVICE_TYPE_LOCAL)
            device = g_object_ref(candidate);
        g_object_unref(candidate);
    }
    frida_unref(devices);
    if (!device) { fputs("local device unavailable\n", stderr); free(source); return 1; }
    FridaScript *script = NULL;
    FridaSession *session = NULL;
    pid_t attached_pid = 0;
    uint64_t last_attempt = 0;
    if (probe) {
        pid_t pid = find_provider();
        int ok = pid && attach_hook(device, pid, source, &script, &session);
        for (int i = 0; ok && i < 20; i++) {
            while (g_main_context_pending(NULL)) g_main_context_iteration(NULL, FALSE);
            usleep(100000);
        }
        clear_hook(&script, &session);
        frida_unref(device);
        frida_device_manager_close_sync(manager, NULL, NULL);
        frida_unref(manager);
        free(source);
        return ok ? 0 : 1;
    }
    fprintf(stderr, "PD2405 watermark controller started (signal gated)\n");
    while (!stopping) {
        while (g_main_context_pending(NULL)) g_main_context_iteration(NULL, FALSE);
        int enabled = watermark_enabled();
        pid_t current = enabled ? find_provider() : 0;
        if (script && (current != attached_pid || !session ||
                       frida_session_is_detached(session))) {
            clear_hook(&script, &session);
            attached_pid = 0;
            fprintf(stderr, "detached (inactive or provider changed)\n");
        }
        if (enabled && current && !script && uptime_ms() - last_attempt >= 2000) {
            last_attempt = uptime_ms();
            if (hash_matches(VAF, VAF_SHA) && hash_matches(META, META_SHA) &&
                attach_hook(device, current, source, &script, &session))
                attached_pid = current;
        }
        if (script) frida_script_post(script,
            "{\"type\":\"state\",\"payload\":{\"enabled\":true}}", NULL);
        usleep(250000);
    }
    clear_hook(&script, &session);
    frida_unref(device);
    frida_device_manager_close_sync(manager, NULL, NULL);
    frida_unref(manager);
    free(source);
    fprintf(stderr, "PD2405 watermark controller stopped\n");
    return 0;
}
