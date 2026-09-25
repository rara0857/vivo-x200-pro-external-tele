package local.pd2405.exttele.prototype;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;

/** Short-lived app-to-root state signal. It never issues an OIS command. */
final class NativeOisSignal {
    static final String FILE_NAME = "exttele_ois_state";
    private static final long HEARTBEAT_MS = 500;
    private final File state;
    private final File pending;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final IntSupplier requestedMode;
    private final BooleanSupplier watermarkActive;
    private boolean running;

    NativeOisSignal(Context context, IntSupplier requestedMode,
                    BooleanSupplier watermarkActive) {
        state = new File(context.getFilesDir(), FILE_NAME);
        pending = new File(context.getFilesDir(), FILE_NAME + ".tmp");
        this.requestedMode = requestedMode;
        this.watermarkActive = watermarkActive;
        publish(0, false);
    }

    void refresh() {
        int mode = requestedMode.getAsInt();
        boolean watermark = watermarkActive.getAsBoolean();
        if (mode == 0 && !watermark) {
            running = false;
            handler.removeCallbacks(tick);
            publish(0, false);
        } else {
            publish(mode, watermark);
            if (!running) {
                running = true;
                handler.postDelayed(tick, HEARTBEAT_MS);
            }
        }
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!running) return;
            int mode = requestedMode.getAsInt();
            boolean watermark = watermarkActive.getAsBoolean();
            if (mode == 0 && !watermark) {
                running = false;
                publish(0, false);
                return;
            }
            publish(mode, watermark);
            handler.postDelayed(this, HEARTBEAT_MS);
        }
    };

    private void publish(int mode, boolean watermark) {
        String line = Process.myPid() + " " + SystemClock.elapsedRealtime()
                + " " + mode + " " + (watermark ? 1 : 0) + "\n";
        byte[] bytes = line.getBytes(StandardCharsets.US_ASCII);
        try (FileOutputStream stream = new FileOutputStream(pending, false)) {
            stream.write(bytes);
            stream.flush();
            if (!pending.renameTo(state)) {
                throw new IllegalStateException("cannot publish OIS state");
            }
        } catch (Throwable ignored) {
            // A missing signal fails closed in the root controller.
        }
    }
}
