package local.pd2405.exttele.prototype;

import android.content.Context;
import android.graphics.Rect;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;

import java.util.Map;
import java.util.WeakHashMap;

import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.XposedBridge;

/**
 * Experimental preview-only crop stabilization. No camera request, still image,
 * OIS, EIS or vendor metadata is changed. It is intentionally bounded and can
 * be turned off while the virtual tele UI remains open.
 */
final class PreviewGyroStabilizer implements SensorEventListener {
    private static final int HISTORY = 256;
    private static final long MAX_FRAME_AGE_NS = 300_000_000L;
    private static final double MAX_SAMPLE_DT = 0.10;
    private final long[] times = new long[HISTORY];
    private final double[] errorsX = new double[HISTORY];
    private final double[] errorsY = new double[HISTORY];
    private final Map<Object, Rect> originalRects = new WeakHashMap<>();
    private final Map<Object, Boolean> originalViewportMissing = new WeakHashMap<>();
    private final Map<Object, Rect> lastAppliedRects = new WeakHashMap<>();
    private SensorManager sensors;
    private HandlerThread sensorThread;
    private int count;
    private int next;
    private long lastSensorNs;
    private double angleX;
    private double angleY;
    private double slowX;
    private double slowY;
    private volatile int trialLevel;
    private volatile boolean running;
    private volatile String lastApplyStatus = "not-called";
    private float focalMm = 85f * 2.35f;
    private long lastFrameNs;
    private double filteredX, filteredY;
    private int appliedFrames;

    synchronized void setFocalMm(float value) {
        if (Float.isNaN(value) || Float.isInfinite(value)) return;
        focalMm = Math.max(85f, Math.min(5400f, value));
    }

    synchronized boolean start(Context context) {
        if (running) return true;
        sensors = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        if (sensors == null) return false;
        Sensor gyro = sensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
        if (gyro == null) return false;
        sensorThread = new HandlerThread("PD2405-preview-gyro");
        sensorThread.start();
        if (!sensors.registerListener(this, gyro, SensorManager.SENSOR_DELAY_GAME,
                new Handler(sensorThread.getLooper()))) {
            sensorThread.quitSafely();
            sensorThread = null;
            return false;
        }
        count = 0;
        next = 0;
        lastSensorNs = 0;
        angleX = angleY = slowX = slowY = 0;
        lastFrameNs = 0;
        filteredX = filteredY = 0;
        appliedFrames = 0;
        running = true;
        return true;
    }

    synchronized void stop() {
        running = false;
        trialLevel = 0;
        if (sensors != null) sensors.unregisterListener(this);
        if (sensorThread != null) sensorThread.quitSafely();
        sensorThread = null;
        for (Map.Entry<Object, Rect> entry : originalRects.entrySet()) {
            try { restoreViewport(entry.getKey(), entry.getValue()); }
            catch (Throwable ignored) { /* input may already have been destroyed */ }
        }
        originalRects.clear();
        originalViewportMissing.clear();
        lastAppliedRects.clear();
    }

    synchronized int cycleTrialLevel() {
        trialLevel = (trialLevel + 1) % 3;
        lastFrameNs = 0;
        filteredX = filteredY = 0;
        return trialLevel;
    }

    int level() { return trialLevel; }

    synchronized String frameStatus(long frameNs) {
        long nowNs = SystemClock.elapsedRealtimeNanos();
        long gyroNs = count == 0 ? 0 : times[(next - 1 + HISTORY) % HISTORY];
        return "level=" + trialLevel + " running=" + running
                + " focalMm=" + focalMm
                + " samples=" + count
                + " apply=" + lastApplyStatus
                + " frameAgeMs=" + (frameNs <= 0 ? "none"
                : String.valueOf((nowNs - frameNs) / 1_000_000L))
                + " gyroFrameDeltaMs=" + (frameNs <= 0 || gyroNs <= 0 ? "none"
                : String.valueOf((gyroNs - frameNs) / 1_000_000L));
    }

    @Override
    public synchronized void onSensorChanged(SensorEvent event) {
        if (!running || event.sensor.getType() != Sensor.TYPE_GYROSCOPE) return;
        long time = event.timestamp;
        if (lastSensorNs == 0) {
            lastSensorNs = time;
            return;
        }
        double dt = (time - lastSensorNs) * 1e-9;
        lastSensorNs = time;
        if (dt <= 0 || dt > MAX_SAMPLE_DT) {
            slowX = angleX;
            slowY = angleY;
            count = next = 0;
            return;
        }
        angleX += event.values[0] * dt;
        angleY += event.values[1] * dt;
        // Follow intentional pans faster instead of pinning the crop to an edge.
        double speed = Math.hypot(event.values[0], event.values[1]);
        double tau = 0.28 - 0.22 * Math.min(1.0, speed / 0.35);
        double alpha = 1.0 - Math.exp(-dt / tau);
        slowX += alpha * (angleX - slowX);
        slowY += alpha * (angleY - slowY);
        times[next] = time;
        errorsX[next] = angleX - slowX;
        errorsY[next] = angleY - slowY;
        next = (next + 1) % HISTORY;
        if (count < HISTORY) count++;
    }

    @Override public void onAccuracyChanged(Sensor sensor, int accuracy) { }

    /** Called only for the main OES preview input, after stock builder set its viewport. */
    synchronized boolean apply(Object input, long frameNs) {
        if (!running || input == null) {
            lastApplyStatus = "not-running-or-null-input";
            return false;
        }
        Object value = XposedHelpers.callMethod(input, "getReadViewport");
        if (value != null && !(value instanceof Rect)) {
            lastApplyStatus = "viewport-" + value.getClass().getName();
            return false;
        }
        Rect current = (Rect) value;
        Rect last = lastAppliedRects.get(input);
        boolean stockViewportMissing = current == null;
        Rect base;
        if (last != null && last.equals(current)) {
            base = originalRects.get(input);
            stockViewportMissing = Boolean.TRUE.equals(originalViewportMissing.get(input));
        } else if (current == null) {
            int width = (Integer) XposedHelpers.callMethod(input, "getWidth");
            int height = (Integer) XposedHelpers.callMethod(input, "getHeight");
            base = new Rect(0, 0, width, height);
        } else {
            base = new Rect(current);
        }
        if (base == null || base.width() < 320 || base.height() < 240) {
            lastApplyStatus = "viewport-small-" + base;
            return false;
        }
        originalRects.put(input, new Rect(base));
        originalViewportMissing.put(input, stockViewportMissing);

        if (trialLevel == 0 || frameNs <= 0 || count == 0
                || Math.abs(SystemClock.elapsedRealtimeNanos() - frameNs) > MAX_FRAME_AGE_NS) {
            restoreViewport(input, base);
            lastAppliedRects.remove(input);
            lastApplyStatus = "clock-or-samples-invalid";
            return false;
        }
        int sample = (next - 1 + HISTORY) % HISTORY;
        int newest = sample;
        int oldest = (next - count + HISTORY) % HISTORY;
        if (frameNs < times[oldest] || frameNs > times[newest] + 30_000_000L) {
            restoreViewport(input, base);
            lastAppliedRects.remove(input);
            lastFrameNs = 0;
            lastApplyStatus = "frame-outside-gyro-history";
            return false;
        }
        for (int i = 0; i < count; i++) {
            int candidate = (next - 1 - i + HISTORY * 2) % HISTORY;
            if (times[candidate] <= frameNs) { sample = candidate; break; }
        }
        if (Math.abs(times[sample] - frameNs) > MAX_FRAME_AGE_NS) {
            restoreViewport(input, base);
            lastAppliedRects.remove(input);
            lastApplyStatus = "gyro-frame-gap";
            return false;
        }

        double errorX = errorsX[sample], errorY = errorsY[sample];
        if (sample != newest) {
            int after = (sample + 1) % HISTORY;
            long span = times[after] - times[sample];
            if (span > 0) {
                double mix = Math.max(0, Math.min(1, (double)(frameNs - times[sample]) / span));
                errorX += (errorsX[after] - errorX) * mix;
                errorY += (errorsY[after] - errorY) * mix;
            }
        }
        int marginX = Math.max(4, Math.round(base.width() * 0.06f));
        int marginY = Math.max(4, Math.round(base.height() * 0.06f));
        // Both signs are offered as A/B because OIS residual and 180-degree
        // render rotation prevent inferring the correct axis sign from static code.
        double gain = trialLevel == 1 ? 0.25 : -0.25;
        // Nominal focal mapping, not a calibrated OIS-residual model.
        double focalPx = base.width() * focalMm / 36.0 * gain;
        double displayX = -errorY * focalPx;
        double displayY = errorX * focalPx;
        int rotation = (Integer) XposedHelpers.callMethod(input, "getRotationClockwise");
        double inputX;
        double inputY;
        switch ((rotation % 360 + 360) % 360) {
            case 90: inputX = -displayY; inputY = displayX; break;
            case 180: inputX = -displayX; inputY = -displayY; break;
            case 270: inputX = displayY; inputY = -displayX; break;
            default: inputX = displayX; inputY = displayY; break;
        }
        double targetX = marginX * Math.tanh(inputX / marginX);
        double targetY = marginY * Math.tanh(inputY / marginY);
        double frameDt = lastFrameNs == 0 ? 0 : (frameNs - lastFrameNs) * 1e-9;
        if (lastFrameNs == 0 || frameDt < 0 || frameDt > 0.3) {
            filteredX = filteredY = 0;
        } else {
            double blend = 1.0 - Math.exp(-frameDt / 0.018);
            filteredX += blend * (targetX - filteredX);
            filteredY += blend * (targetY - filteredY);
        }
        lastFrameNs = frameNs;
        int shiftX = clamp((int) Math.round(filteredX), -marginX, marginX);
        int shiftY = clamp((int) Math.round(filteredY), -marginY, marginY);
        Rect crop = new Rect(base.left + marginX + shiftX,
                base.top + marginY + shiftY,
                base.right - marginX + shiftX,
                base.bottom - marginY + shiftY);
        if (!base.contains(crop)) {
            lastApplyStatus = "crop-outside-" + base + "-" + crop;
            return false;
        }
        XposedHelpers.callMethod(input, "setReadViewport", crop);
        lastAppliedRects.put(input, crop);
        lastApplyStatus = "crop-" + crop;
        if (++appliedFrames % 30 == 0) {
            XposedBridge.log("PD2405ExtTele: gyro-data level=" + trialLevel
                    + " focalMm=" + String.format(java.util.Locale.US, "%.1f", focalMm)
                    + " errDeg=" + String.format(java.util.Locale.US, "%.4f,%.4f",
                    Math.toDegrees(errorX), Math.toDegrees(errorY))
                    + " targetPx=" + String.format(java.util.Locale.US, "%.1f,%.1f", targetX, targetY)
                    + " appliedPx=" + shiftX + "," + shiftY
                    + " marginPx=" + marginX + "," + marginY
                    + " crop=" + crop);
        }
        return true;
    }

    private static int clamp(int value, int lower, int upper) {
        return Math.max(lower, Math.min(value, upper));
    }

    private void restoreViewport(Object input, Rect base) {
        if (Boolean.TRUE.equals(originalViewportMissing.get(input))) {
            XposedHelpers.setObjectField(input, "readViewport", null);
        } else {
            XposedHelpers.callMethod(input, "setReadViewport", base);
        }
    }
}
