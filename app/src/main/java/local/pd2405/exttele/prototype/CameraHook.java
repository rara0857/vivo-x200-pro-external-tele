package local.pd2405.exttele.prototype;

import android.app.Application;
import android.app.Activity;
import android.os.Bundle;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.SurfaceTexture;
import android.os.SystemClock;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.util.Range;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.Toast;

import java.util.List;
import java.util.Map;
import java.util.Arrays;
import java.util.HashSet;
import java.util.WeakHashMap;
import java.lang.ref.WeakReference;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Reversible app-layer external-tele UI experiment using stock camera zoom.
 * The opt-in OIS signal is consumed by a separate PD2405-only root controller;
 * C sends the stock Camera preview OIS-off request; this hook does not issue
 * native OIS commands or replace camera/vendor files.
 */
public final class CameraHook implements IXposedHookLoadPackage {
    private static final String CAMERA = "com.android.camera";
    private static final String VERIFIED_APK_VERSION = "13.0.47.271";
    private static final String EXTERNAL_PHOTO = "external_tele_photo";
    private final AtomicBoolean initialized = new AtomicBoolean(false);
    private final AtomicBoolean virtualActive = new AtomicBoolean(false);
    private final AtomicBoolean videoVirtualActive = new AtomicBoolean(false);
    private final AtomicBoolean proStockTeleActive = new AtomicBoolean(false);
    private final AtomicBoolean highResolutionTeleActive = new AtomicBoolean(false);
    private final AtomicBoolean splitVideoStockTeleActive = new AtomicBoolean(false);
    private final AtomicBoolean nativeZoomRequested = new AtomicBoolean(false);
    private final AtomicInteger oisGainMode = new AtomicInteger(0);
    private volatile WeakReference<Object> stockOisAddition = new WeakReference<>(null);
    private volatile Object stockOisKey;
    private volatile Boolean stockOisEnabled;
    private final AtomicBoolean stockOisForcedOff = new AtomicBoolean(false);
    private final AtomicBoolean nativeZoomShown = new AtomicBoolean(false);
    private final AtomicInteger selectedFocalStep = new AtomicInteger(0);
    private final ThreadLocal<Boolean> nativeZoomUiScope = new ThreadLocal<>();
    private final AtomicBoolean previewRotationObserved = new AtomicBoolean(false);
    private final AtomicBoolean pipRotationObserved = new AtomicBoolean(false);
    private final AtomicBoolean pipGateObserved = new AtomicBoolean(false);
    private final AtomicBoolean captureRotationObserved = new AtomicBoolean(false);
    private final AtomicInteger watermarkCaptureDiagnostics = new AtomicInteger(0);
    private final AtomicInteger stabilizationProbeCount = new AtomicInteger(0);
    private final AtomicBoolean previewCropObserved = new AtomicBoolean(false);
    private final AtomicBoolean previewTimestampDiagnosed = new AtomicBoolean(false);
    private final AtomicBoolean focalConversionObserved = new AtomicBoolean(false);
    private final AtomicBoolean zoomFloorGuardObserved = new AtomicBoolean(false);
    private final AtomicBoolean splitZoomConfigObserved = new AtomicBoolean(false);
    private final AtomicInteger splitRoutingDiagnostics = new AtomicInteger(0);
    private final AtomicInteger splitRulerDiagnostics = new AtomicInteger(0);
    private final AtomicBoolean inverseFocalConversionObserved = new AtomicBoolean(false);
    private final AtomicBoolean rulerRepaintSkipObserved = new AtomicBoolean(false);
    private final AtomicInteger previewCropFrameChecks = new AtomicInteger(0);
    private final AtomicBoolean macroChangeAttempted = new AtomicBoolean(false);
    private final AtomicInteger macroRecoveryTries = new AtomicInteger(0);
    private final AtomicInteger proTeleSwitchTries = new AtomicInteger(0);
    private final AtomicInteger videoTeleSwitchTries = new AtomicInteger(0);
    private final AtomicInteger videoZoomConfigGeneration = new AtomicInteger(0);
    private final AtomicInteger videoZoomRefreshAttemptedGeneration = new AtomicInteger(-1);
    private final AtomicInteger highResolutionProbeCount = new AtomicInteger(0);
    private final AtomicInteger highResolutionUiProbeCount = new AtomicInteger(0);
    private final AtomicInteger highResolutionUiGeneration = new AtomicInteger(0);
    private final AtomicInteger photoMacroZoomGeneration = new AtomicInteger(0);
    private volatile Boolean activePhotoMacro;
    private volatile String activeVideoFormat;
    private volatile int activeVideoRoute;
    private volatile boolean activeVideo15xLimited;
    private final AtomicBoolean twoPointTeleClickPending = new AtomicBoolean(false);
    private final PreviewGyroStabilizer previewStabilizer = new PreviewGyroStabilizer();
    private final Map<Object, Integer> previewInputRotations = new WeakHashMap<>();
    private final Map<Object, Object> nativeZoomConfigs = new WeakHashMap<>();
    private final Map<Object, Boolean> equivalentTagAdjusted = new WeakHashMap<>();
    private final ExternalTelePanel panel = new ExternalTelePanel();
    private volatile Object photoUi;
    private volatile Object videoUi;
    private volatile WeakReference<Object> zoomController = new WeakReference<>(null);
    private volatile WeakReference<Object> pipAddition = new WeakReference<>(null);
    private volatile NativeOisSignal nativeOisSignal;
    private volatile boolean cameraResumed;
    private static final long WATERMARK_CAPTURE_GRACE_MS = 30_000L;
    private volatile long lastExternalStillCaptureAt;
    private volatile long watermarkPausedUntil;
    private volatile float splitVideoRulerGlobalZoom = Float.NaN;
    private volatile long splitVideoRulerAt;
    private volatile float splitVideoButtonGlobalZoom = Float.NaN;
    private volatile long splitVideoButtonAt;
    private volatile WeakReference<View> twoPointSideButton = new WeakReference<>(null);
    private volatile WeakReference<View> highResolutionEntry = new WeakReference<>(null);
    private volatile WeakReference<ImageView> highResolutionSideIcon =
            new WeakReference<>(null);
    private final AtomicInteger highResolutionTeleRequestGeneration =
            new AtomicInteger(0);
    private final Map<View, Integer> highResolutionOriginalButtonVisibility =
            new WeakHashMap<>();
    private final AtomicInteger recordingRulerRepairLogs = new AtomicInteger(0);

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam load) {
        if (!CAMERA.equals(load.packageName) || !CAMERA.equals(load.processName)) return;
        XposedHelpers.findAndHookMethod(Application.class.getName(), null,
                "attach", Context.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (!initialized.compareAndSet(false, true)) return;
                        Context context = (Context) param.args[0];
                        try {
                            nativeOisSignal = new NativeOisSignal(context,
                                    () -> virtualActive.get()
                                            && (nativeZoomRequested.get()
                                                || proStockTeleActive.get()
                                                || highResolutionTeleActive.get()
                                                || splitVideoStockTeleActive.get())
                                            && cameraResumed
                                            && (videoVirtualActive.get()
                                                ? videoUi != null && isVideo(videoUi)
                                                : photoUi != null && isPhoto(photoUi))
                                            && isLiveTeleZoom() ?
                                                    (oisGainMode.get() == 3 ? 0 : oisGainMode.get())
                                                    : 0,
                                    () -> (virtualActive.get()
                                            && !videoVirtualActive.get()
                                            && (nativeZoomRequested.get()
                                                || proStockTeleActive.get()
                                                || highResolutionTeleActive.get())
                                            && cameraResumed
                                            && photoUi != null && isPhoto(photoUi)
                                            && isLiveTeleZoom())
                                            || (!cameraResumed && SystemClock.elapsedRealtime()
                                                < watermarkPausedUntil));
                            ((Application) param.thisObject).registerActivityLifecycleCallbacks(
                                    new Application.ActivityLifecycleCallbacks() {
                                        @Override public void onActivityCreated(Activity a, Bundle b) {}
                                        @Override public void onActivityStarted(Activity a) {}
                                        @Override public void onActivityResumed(Activity a) {
                                             if (!"com.android.camera.CameraActivity".equals(
                                                     a.getClass().getName())) return;
                                             cameraResumed = true;
                                             watermarkPausedUntil = 0;
                                             if (nativeOisSignal != null) nativeOisSignal.refresh();
                                             if (isExternalOisCActive()) submitStockOis(false);
                                             else if (oisGainMode.get() != 3) restoreStockOis();
                                             a.getWindow().getDecorView().postDelayed(() -> {
                                                 if (isExternalOisCActive()) submitStockOis(false);
                                             }, 700);
                                            int generation = highResolutionUiGeneration.incrementAndGet();
                                            a.getWindow().getDecorView().post(
                                                    () -> refreshHighResolutionEntry(a, generation));
                                        }
                                        @Override public void onActivityPaused(Activity a) {
                                             if (!"com.android.camera.CameraActivity".equals(
                                                     a.getClass().getName())) return;
                                             cameraResumed = false;
                                             restoreStockOis();
                                             long now = SystemClock.elapsedRealtime();
                                             long shotAt = lastExternalStillCaptureAt;
                                             watermarkPausedUntil = shotAt > 0 && now >= shotAt
                                                     && now - shotAt < WATERMARK_CAPTURE_GRACE_MS
                                                     ? shotAt + WATERMARK_CAPTURE_GRACE_MS : 0;
                                             if (watermarkPausedUntil > now) {
                                                 XposedBridge.log("PD2405ExtTele: watermark post-capture grace "
                                                         + (watermarkPausedUntil - now) + "ms");
                                             }
                                             highResolutionUiGeneration.incrementAndGet();
                                            if (nativeOisSignal != null) nativeOisSignal.refresh();
                                        }
                                        @Override public void onActivityStopped(Activity a) {}
                                        @Override public void onActivitySaveInstanceState(
                                                Activity a, Bundle b) {}
                                        @Override public void onActivityDestroyed(Activity a) {}
                                    });
                            String version = context.getPackageManager()
                                    .getPackageInfo(CAMERA, 0).versionName;
                            if (!VERIFIED_APK_VERSION.equals(version)) {
                                XposedBridge.log("PD2405ExtTele: unsupported camera version " + version);
                                return;
                            }
                            ClassLoader loader = context.getClassLoader();
            installZoomCapture(loader);
            installVirtualZoomFloor(loader);
                            installPreviewRenderRotation(loader);
                            installPipRenderRotation(loader);
                            installExternalPipGate(loader);
                            installStillCaptureRotation(loader);
                            installVideoSnapshotRotation(loader);
                            installStillEquivalentFocalLength(loader);
                            installVideoOrientationHint(loader);
                            installNativeFocalZoomUi(loader);
                            installVideoZoomConfigRefresh(loader);
                            installMoreTileClickGuard(loader);
                            installModeIndicatorClickGuard(loader);
                            installMoreTileGate(loader);
                            installButton(loader);
                            installSceneryButton(loader);
                            installStageButtons(loader);
                            installHighResolutionButton(loader);
                            installOisSideButtonBinding(loader);
                            installStockOisRequestHook(loader);
                            installVideoButton(loader);
                            installAdvancedButtons(loader);
                            installStillModeDiagnostics(loader);
                            installStageAndHighResolutionDiagnostics(loader);
                            installTwoPointStillButton(loader,
                                    "com.android.camera.motiondeblur.ui.moduleui.MotionDeblurModuleUI",
                                    "motion_deblur");
                            installTwoPointStillButton(loader,
                                    "com.android.camera.portrait.ui.moduleui.PortraitModuleUI",
                                    "portrait");
                            XposedBridge.log("PD2405ExtTele: virtual-tele UI hooks installed");
                        } catch (PackageManager.NameNotFoundException error) {
                            XposedBridge.log(error);
                        } catch (Throwable error) {
                            XposedBridge.log(error);
                        }
                    }
                });
    }

    private void installZoomCapture(ClassLoader loader) {
        Class<?> uiInterface = XposedHelpers.findClass(
                "com.android.camera.ui.core.UiInnerController", loader);
        XposedHelpers.findAndHookConstructor(
                "com.android.camera.ui.commonui.zoomui.view.ZoomUIController", loader,
                uiInterface, ViewGroup.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        zoomController = new WeakReference<>(param.thisObject);
                        XposedBridge.log("PD2405ExtTele: stock zoom controller constructed");
                    }
                });
        XposedHelpers.findAndHookMethod(
                "com.android.camera.ui.commonui.zoomui.view.ZoomUIController", loader,
                "initZoomConfig", boolean.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        zoomController = new WeakReference<>(param.thisObject);
                    }
                });
    }

    /** Stock pinch still uses the ordinary SAT range. Reject an under-floor
     *  ZoomMessage before updateZoom can submit a camera-type switch. */
    private void installVirtualZoomFloor(ClassLoader loader) {
        Class<?> zoomMessage = XposedHelpers.findClass(
                "com.android.camera.ui.commonui.zoomui.EntityData.ZoomMessage", loader);
        XposedHelpers.findAndHookMethod(
                "com.android.camera.ui.commonui.zoomui.base.BaseZoomModelImpl", loader,
                "updateZoom", zoomMessage, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        if (!virtualActive.get() || (!nativeZoomRequested.get()
                                && !proStockTeleActive.get()
                                && !splitVideoStockTeleActive.get())
                                || isTwoPointStill()
                                || param.args[0] == null) return;
                        try {
                            Object message = param.args[0];
                            String zoomParam = (String) XposedHelpers.getObjectField(
                                    message, "zoomParam");
                            if (zoomParam == null) return;
                            float requested = Float.parseFloat(zoomParam);
                            float scale = 1f;
                            Object controller = zoomController.get();
                            if (controller != null) {
                                Object zoomContext = XposedHelpers.getObjectField(
                                        controller, "mContext");
                                Object currentScale = XposedHelpers.callMethod(
                                        zoomContext, "getZoomScale");
                                if (currentScale instanceof Number) {
                                    scale = ((Number) currentScale).floatValue();
                                }
                            }
                            String nextCameraType = (String) XposedHelpers.getObjectField(
                                    message, "cameraType");
                            float targetScale = scale;
                            if (controller != null && nextCameraType != null) {
                                Object zoomContext = XposedHelpers.getObjectField(
                                        controller, "mContext");
                                Object zoomConfig = XposedHelpers.callMethod(
                                        zoomContext, "getZoomConfig");
                                Map<?, ?> scales = (Map<?, ?>) XposedHelpers.getObjectField(
                                        zoomConfig, "cameraTypeZoomScale");
                                Object destinationScale = scales.get(nextCameraType);
                                if (destinationScale instanceof Number
                                        && ((Number) destinationScale).floatValue() > 0f) {
                                    targetScale = ((Number) destinationScale).floatValue();
                                }
                            }
                            Object source = XposedHelpers.getObjectField(message,
                                    "sourceType");
                            if ((splitVideoStockTeleActive.get() || proStockTeleActive.get())
                                    && SystemClock.uptimeMillis() - splitVideoButtonAt <= 1000
                                    && Float.isFinite(splitVideoButtonGlobalZoom)) {
                                float target = splitVideoButtonGlobalZoom;
                                splitVideoButtonGlobalZoom = Float.NaN;
                                XposedHelpers.setObjectField(message, "cameraType",
                                        "Tele3P5x");
                                XposedHelpers.setObjectField(message, "zoomParam",
                                        Float.toString(target / 3.7f));
                                XposedHelpers.setObjectField(message, "title", null);
                                XposedBridge.log("PD2405ExtTele: stock tele button "
                                        + target + "x global -> Tele3P5x "
                                        + (target / 3.7f) + "x native");
                                return;
                            }
                            if (splitVideoStockTeleActive.get()
                                    && splitRoutingDiagnostics.getAndIncrement() < 25) {
                                XposedBridge.log("PD2405ExtTele: split zoom route source="
                                        + source + " type=" + nextCameraType
                                        + " zoom=" + zoomParam
                                        + " scale=" + targetScale);
                            }
                            if ((splitVideoStockTeleActive.get() || proStockTeleActive.get())
                                    && "ZoomScrollRuler".equals(String.valueOf(source))) {
                                float globalZoom = SystemClock.uptimeMillis()
                                        - splitVideoRulerAt <= 1000
                                        && Float.isFinite(splitVideoRulerGlobalZoom)
                                        ? splitVideoRulerGlobalZoom
                                        : requested * targetScale;
                                if (!Float.isFinite(globalZoom)
                                        || globalZoom < 3.7f - 0.0001f) {
                                    param.setResult(null);
                                    return;
                                }
                                float teleZoom = Math.min(20f, globalZoom) / 3.7f;
                                XposedHelpers.setObjectField(message, "cameraType",
                                        "Tele3P5x");
                                XposedHelpers.setObjectField(message, "zoomParam",
                                        Float.toString(teleZoom));
                                XposedHelpers.setObjectField(message, "title", null);
                                if (splitRulerDiagnostics.getAndIncrement() < 30) {
                                    XposedBridge.log("PD2405ExtTele: stock tele ruler "
                                            + zoomParam + "x type=" + nextCameraType
                                            + " scale=" + targetScale + " -> Tele3P5x "
                                            + teleZoom + "x native");
                                }
                                return;
                            }
                            boolean leaveStockTele = (proStockTeleActive.get()
                                    || splitVideoStockTeleActive.get())
                                    && nextCameraType != null
                                    && !"Tele3P5x".equals(nextCameraType);
                            if (!Float.isFinite(requested) || !Float.isFinite(targetScale)
                                    || requested * targetScale < 3.7f - 0.0001f
                                    || leaveStockTele) {
                                param.setResult(null);
                                if (zoomFloorGuardObserved.compareAndSet(false, true)) {
                                    XposedBridge.log("PD2405ExtTele: blocked under-200mm zoom "
                                            + zoomParam + "x scale=" + scale
                                            + " targetScale=" + targetScale
                                            + "; camera type switch suppressed");
                                }
                            }
                        } catch (NumberFormatException error) {
                            // Stock may introduce a nonnumeric request in a future APK.
                            // Keep the stock behavior rather than rewriting an unknown format.
                        } catch (Throwable error) {
                            XposedBridge.log("PD2405ExtTele: zoom floor guard skipped: " + error);
                        }
                    }
                });
    }

    private void installPreviewRenderRotation(ClassLoader loader) {
        XposedHelpers.findAndHookMethod(
                "com.android.camera.glrender.core.actor.PreviewRenderActor", loader,
                "render", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        if (!virtualActive.get()) return;
                        try {
                            Object request = XposedHelpers.getObjectField(
                                    param.thisObject, "mRenderRequest");
                            if (request == null) return;
                            Object input = XposedHelpers.callMethod(request, "getInput");
                            if (input == null) return;
                            synchronized (previewInputRotations) {
                                Integer original = previewInputRotations.get(input);
                                if (original == null) {
                                    original = (Integer) XposedHelpers.callMethod(
                                            input, "getRotationClockwise");
                                    previewInputRotations.put(input, original);
                                }
                                int target = virtualActive.get()
                                        ? (original + 180) % 360 : original;
                                int current = (Integer) XposedHelpers.callMethod(
                                        input, "getRotationClockwise");
                                if (current != target) {
                                    XposedHelpers.callMethod(input, "setRotationClockwise", target);
                                }
                                if (previewStabilizer.level() != 0) {
                                    try {
                                        Object controller = zoomController.get();
                                        Object zoom = XposedHelpers.getObjectField(controller, "mContext");
                                        float ratio = ((Number) XposedHelpers.callMethod(zoom,
                                                "getCurrentValue")).floatValue();
                                        previewStabilizer.setFocalMm(ratio * (85f / 3.7f) * 2.35f);
                                    } catch (Throwable ignored) { /* keep last valid focal */ }
                                    long frameNs = getPreviewFrameTimestamp(param.thisObject, input);
                                    boolean cropped = previewStabilizer.apply(input, frameNs);
                                    if (cropped && previewCropObserved.compareAndSet(false, true)) {
                                        XposedBridge.log("PD2405ExtTele: preview gyro crop active "
                                                + previewStabilizer.frameStatus(frameNs));
                                    }
                                    if (previewCropFrameChecks.incrementAndGet() == 60) {
                                        XposedBridge.log("PD2405ExtTele: preview gyro 60-frame check "
                                                + previewStabilizer.frameStatus(frameNs)
                                                + " cropped=" + previewCropObserved.get());
                                    }
                                }
                                if (virtualActive.get()
                                        && previewRotationObserved.compareAndSet(false, true)) {
                                    XposedBridge.log("PD2405ExtTele: preview render input "
                                            + input.getClass().getSimpleName() + " rotation "
                                            + original + " -> " + target);
                                }
                            }
                        } catch (Throwable error) {
                            XposedBridge.log(error);
                        }
                    }
                });
    }

    private long getPreviewFrameTimestamp(Object actor, Object input) {
        try {
            if (input.getClass().getName().endsWith("EglimageDataInput")) {
                long timestamp = (Long) XposedHelpers.callMethod(input, "getTimeStamp");
                if (timestamp > 0) return timestamp;
            }
            Object core = XposedHelpers.getObjectField(actor, "mRenderEngineCore");
            Object holder = XposedHelpers.callMethod(core, "getPreviewStreamHolder");
            Object texture = XposedHelpers.callMethod(holder, "getSurfaceTexture");
            if (texture instanceof SurfaceTexture) {
                long timestamp = ((SurfaceTexture) texture).getTimestamp();
                if (timestamp <= 0 && previewTimestampDiagnosed.compareAndSet(false, true)) {
                    XposedBridge.log("PD2405ExtTele: EGL and SurfaceTexture timestamps are zero;"
                            + " using approximate render clock"
                            + " holder=" + holder.getClass().getName());
                }
                return timestamp > 0 ? timestamp : SystemClock.elapsedRealtimeNanos();
            }
            if (previewTimestampDiagnosed.compareAndSet(false, true)) {
                XposedBridge.log("PD2405ExtTele: preview texture unavailable"
                        + " type=" + (texture == null ? "null" : texture.getClass().getName()));
            }
        } catch (Throwable error) {
            if (previewTimestampDiagnosed.compareAndSet(false, true)) {
                XposedBridge.log("PD2405ExtTele: preview timestamp lookup failed: " + error);
            }
        }
        return 0L;
    }

    /** The high-zoom finder uses a separate YUV render path, not PreviewRenderActor.render(). */
    private void installPipRenderRotation(ClassLoader loader) {
        Class<?> yuvInput = XposedHelpers.findClass("com.vivo.viengine.data.input.YUVDataInput", loader);
        XposedHelpers.findAndHookMethod(
                "com.android.camera.glrender.core.request.PreviewRenderRequestBuilder", loader,
                "getSatPipPreviewRenderRequest", yuvInput, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        if (!virtualActive.get() || photoUi == null || !isPhoto(photoUi)) return;
                        try {
                            Object input = param.args[0];
                            if (input == null) return;
                            int original = (Integer) XposedHelpers.callMethod(input,
                                    "getRotationClockwise");
                            int target = (original + 180) % 360;
                            XposedHelpers.callMethod(input, "setRotationClockwise", target);
                            if (pipRotationObserved.compareAndSet(false, true)) {
                                XposedBridge.log("PD2405ExtTele: high-zoom finder rotation "
                                        + original + " -> " + target);
                            }
                        } catch (Throwable error) {
                            XposedBridge.log("PD2405ExtTele: finder rotation skipped: " + error);
                        }
                    }
                });
    }

    /** Match the v1.43 virtual Photo finder gate; stock Photo retains its own gate. */
    private void installExternalPipGate(ClassLoader loader) {
        XposedHelpers.findAndHookMethod(
                "com.android.camera.addition.pip.PipAddition", loader,
                "getSatPipGate", new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        pipAddition = new WeakReference<>(param.thisObject);
                        if (!virtualActive.get() || !nativeZoomRequested.get()
                                || photoUi == null || !isPhoto(photoUi)) return;
                        float gate = 9.2f;
                        param.setResult(gate);
                        if (pipGateObserved.compareAndSet(false, true)) {
                            XposedBridge.log("PD2405ExtTele: virtual Photo PIP gate "
                                    + gate + "x native (~500 mm nominal)");
                        }
                    }
                });
    }

    /**
     * PreviewRenderActor only changes display pixels. PhotoBaseContext builds the
     * per-shot CaptureParam before it is submitted to VCamera/VIF. Its prepared
     * callback later reads JPEG_ORIENTATION from the completed outer request, so
     * change the request here, not the gallery's EXIF after saving.
     */
    private void installStillCaptureRotation(ClassLoader loader) {
        XposedHelpers.findAndHookMethod(
                "com.android.camera.module.photobase.PhotoBaseContext", loader,
                "createCaptureParam", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (!virtualActive.get() || photoUi == null || !isPhoto(photoUi)) return;
                         Object captureParam = param.getResult();
                         if (captureParam == null) return;
                         if (cameraResumed && !videoVirtualActive.get()
                                 && isLiveTeleZoom()) {
                             lastExternalStillCaptureAt = SystemClock.elapsedRealtime();
                         }
                        try {
                            Integer original = (Integer) XposedHelpers.callMethod(
                                    captureParam, "get", CaptureRequest.JPEG_ORIENTATION);
                            if (original == null) {
                                Object rotationAddition = XposedHelpers.getObjectField(
                                        param.thisObject, "mImageRotationAddition");
                                original = (Integer) XposedHelpers.callMethod(
                                        rotationAddition, "getImageRotation");
                            }
                            if (original == null) return;
                            int target = (original + 180) % 360;
                            XposedHelpers.callMethod(captureParam, "set",
                                    CaptureRequest.JPEG_ORIENTATION, Integer.valueOf(target));
                            if (captureRotationObserved.compareAndSet(false, true)) {
                                XposedBridge.log("PD2405ExtTele: still JPEG_ORIENTATION "
                                        + original + " -> " + target);
                            }
                            logWatermarkCaptureParam(captureParam, loader, original, target);
                            alignBorderWatermark(captureParam, loader);
                        } catch (Throwable error) {
                            XposedBridge.log("PD2405ExtTele: still rotation skipped: " + error);
                        }
                    }
                });
    }

    /** Video snapshots use a separate capture request and storage bundle. */
    private void installVideoSnapshotRotation(ClassLoader loader) {
        String state = "com.android.camera.module.videobase.state."
                + "StateRecordingCapturing_VideoBase";
        XposedHelpers.findAndHookMethod(state, loader, "createCaptureParam",
                new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        if (!isVirtualVideoActive()) return;
                        Object captureParam = param.getResult();
                        if (captureParam == null) return;
                        try {
                            Integer rotation = (Integer) XposedHelpers.callMethod(
                                    captureParam, "get", CaptureRequest.JPEG_ORIENTATION);
                            if (rotation == null) {
                                Object context = XposedHelpers.callMethod(param.thisObject,
                                        "context");
                                Object addition = XposedHelpers.getObjectField(context,
                                        "mImageRotationAddition");
                                rotation = (Integer) XposedHelpers.callMethod(addition,
                                        "getImageRotation");
                            }
                            if (rotation == null) return;
                            int target = (rotation + 180) % 360;
                            XposedHelpers.callMethod(captureParam, "set",
                                    CaptureRequest.JPEG_ORIENTATION, Integer.valueOf(target));
                            XposedBridge.log("PD2405ExtTele: video snapshot JPEG rotation "
                                    + rotation + " -> " + target);
                        } catch (Throwable error) {
                            XposedBridge.log("PD2405ExtTele: video snapshot request skipped: "
                                    + error);
                        }
                    }
                });
        Class<?> builder = XposedHelpers.findClass(
                "com.android.camera.storage_ext.photo.bundle."
                        + "SnapShotPhotoStorageBundle$Builder", loader);
        XposedHelpers.findAndHookMethod(state, loader, "setDataToBundleBuilder",
                builder, long.class, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        if (!isVirtualVideoActive()) return;
                        Object result = param.getResult();
                        if (result == null) return;
                        try {
                            Object context = XposedHelpers.callMethod(param.thisObject,
                                    "context");
                            Object addition = XposedHelpers.getObjectField(context,
                                    "mImageRotationAddition");
                            int rotation = ((Number) XposedHelpers.callMethod(addition,
                                    "getImageRotation")).intValue();
                            int target = (rotation + 180) % 360;
                            XposedHelpers.callMethod(result, "setOrientation", target);
                            XposedBridge.log("PD2405ExtTele: video snapshot bundle rotation "
                                    + rotation + " -> " + target);
                        } catch (Throwable error) {
                            XposedBridge.log("PD2405ExtTele: video snapshot bundle skipped: "
                                    + error);
                        }
                    }
                });
    }

    private boolean isVirtualVideoActive() {
        return virtualActive.get() && videoVirtualActive.get()
                && videoUi != null && isVideo(videoUi);
    }

    /** Video container orientation is separate from the Photo JPEG request. */
    private void installVideoOrientationHint(ClassLoader loader) {
        XposedHelpers.findAndHookMethod(
                "com.android.camera.module.videobase.VideoBaseContext", loader,
                "getOrientationHint", new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        if (!virtualActive.get() || !videoVirtualActive.get()
                                || videoUi == null || !isVideo(videoUi)) return;
                        Object original = param.getResult();
                        if (!(original instanceof Integer)) return;
                        int value = (Integer) original;
                        if (value != 0 && value != 90 && value != 180 && value != 270) return;
                        int target = (value + 180) % 360;
                        param.setResult(target);
                        XposedBridge.log("PD2405ExtTele: video orientation hint "
                                + value + " -> " + target);
                    }
                });
    }

    /** Bounded scalar-only probe: no photo pixels, location, watermark text or file paths. */
    private void logWatermarkCaptureParam(Object captureParam, ClassLoader loader,
                                          int original, int target) {
        if (watermarkCaptureDiagnostics.getAndIncrement() >= 8) return;
        try {
            Class<?> capability = XposedHelpers.findClass(
                    "com.android.vcamera.mode.manager.VModeCapability", loader);
            Object key = XposedHelpers.getStaticObjectField(
                    capability, "VIVO_CAPTURE_WATERMARK");
            Object wm = XposedHelpers.callMethod(captureParam, "get", key);
            Object one = XposedHelpers.callMethod(captureParam, "oneWMCapture");
            if (wm == null) {
                XposedBridge.log("PD2405ExtTele: watermark probe absent; jpeg "
                        + original + "->" + target);
                return;
            }
            Integer[] frame = (Integer[]) XposedHelpers.callMethod(wm,
                    "getWatermarkZeissSize");
            Object uiZoom = XposedHelpers.callMethod(wm, "getUiZoomRatio");
            Object baseZoom = XposedHelpers.callMethod(wm, "getSensorBasicZoom");
            Object param3a = XposedHelpers.callMethod(wm, "getWatermark3aParam");
            String oneInfo = one == null ? "null" :
                    "border=" + XposedHelpers.callMethod(one, "isBorderWMOn")
                    + " imageOrie=" + XposedHelpers.callMethod(one, "getImageOrientation")
                    + " deviceOrie=" + XposedHelpers.callMethod(one, "getDeviceOrientation")
                    + " focus=" + XposedHelpers.callMethod(one, "getFocusLength");
            XposedBridge.log("PD2405ExtTele: watermark probe jpeg=" + original
                    + "->" + target + " one=" + oneInfo
                    + " uiZoom=" + uiZoom + " baseZoom=" + baseZoom
                    + " param3a=" + param3a + " frame=" + Arrays.toString(frame));
        } catch (Throwable error) {
            XposedBridge.log("PD2405ExtTele: watermark probe skipped: " + error);
        }
    }

    /** Match the native border/frame orientation to the already-rotated JPEG only. */
    private void alignBorderWatermark(Object captureParam, ClassLoader loader) {
        try {
            Class<?> capability = XposedHelpers.findClass(
                    "com.android.vcamera.mode.manager.VModeCapability", loader);
            Object key = XposedHelpers.getStaticObjectField(
                    capability, "VIVO_CAPTURE_WATERMARK");
            Object wm = XposedHelpers.callMethod(captureParam, "get", key);
            Object one = XposedHelpers.callMethod(captureParam, "oneWMCapture");
            if (wm == null || one == null
                    || !Boolean.TRUE.equals(XposedHelpers.callMethod(one, "isBorderWMOn"))) {
                return;
            }
            Integer[] original = (Integer[]) XposedHelpers.callMethod(wm,
                    "getWatermarkZeissSize");
            if (original == null || (original.length != 16 && original.length != 31)) return;
            int imageOrientation = (Integer) XposedHelpers.callMethod(one,
                    "getImageOrientation");
            if (imageOrientation < 0 || imageOrientation > 270
                    || imageOrientation % 90 != 0) return;
            int last = original.length - 1;
            if (original[last] == null || original[last] != imageOrientation) return;
            int corrected = (imageOrientation + 180) % 360;
            Integer[] frame = original.clone();
            frame[last] = corrected;
            XposedHelpers.callMethod(wm, "setSize", (Object) frame);
            XposedHelpers.setIntField(one, "imageOrientation", corrected);
            XposedBridge.log("PD2405ExtTele: border orientation "
                    + imageOrientation + " -> " + corrected
                    + " (frame and capture context; plain text unchanged)");
        } catch (Throwable error) {
            XposedBridge.log("PD2405ExtTele: border orientation skipped: " + error);
        }
    }

    /** Use the same vendor OIS preview key that stock Professional Photo uses.
     *  The live submit handles a toggle without rebuilding the camera session;
     *  the preview-param hook also covers later session rebuilds. */
    private void installStockOisRequestHook(ClassLoader loader) {
        Class<?> cameraParam = XposedHelpers.findClass(
                "com.android.camera.utils.cameradevice.param.CameraParam", loader);
        Class<?> previewParam = XposedHelpers.findClass(
                "com.android.camera.cameradevice.api.param.PreviewParam", loader);
        Class<?> capability = XposedHelpers.findClass(
                "com.android.vcamera.mode.manager.VModeCapability", loader);
        stockOisKey = XposedHelpers.getStaticObjectField(capability, "VIVO_CONTROL_OIS");
        XposedHelpers.findAndHookMethod("com.android.camera.addition.ois.OisAddition",
                loader, "configPreviewParam", cameraParam, previewParam,
                new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            Object key = stockOisKey;
                            Object value = XposedHelpers.callMethod(param.args[1], "get", key);
                            if (!(value instanceof Boolean)) return;
                            stockOisAddition = new WeakReference<>(param.thisObject);
                            stockOisEnabled = (Boolean) value;
                            if (!isExternalOisCActive()) return;
                            XposedHelpers.callMethod(param.args[1], "set", key, Boolean.FALSE);
                            stockOisForcedOff.set(true);
                            XposedBridge.log("PD2405ExtTele: C preview OIS OFF"
                                    + " stock=" + value);
                        } catch (Throwable error) {
                            XposedBridge.log("PD2405ExtTele: C preview OIS skipped: " + error);
                        }
                    }
                });
    }

    private boolean isExternalOisCActive() {
        return oisGainMode.get() == 3 && virtualActive.get() && cameraResumed
                && (nativeZoomRequested.get() || proStockTeleActive.get()
                    || highResolutionTeleActive.get() || splitVideoStockTeleActive.get())
                && (videoVirtualActive.get()
                    ? videoUi != null && isVideo(videoUi)
                    : photoUi != null && isPhoto(photoUi))
                && isLiveTeleZoom();
    }

    private boolean submitStockOis(boolean enabled) {
        Object addition = stockOisAddition.get();
        Object key = stockOisKey;
        if (addition == null || key == null || stockOisEnabled == null) return false;
        try {
            Object module = XposedHelpers.getObjectField(addition, "mModuleController");
            Object camera = XposedHelpers.callMethod(module, "getCameraController");
            XposedHelpers.callMethod(camera, "submitParam", key, Boolean.valueOf(enabled));
            stockOisForcedOff.set(!enabled);
            XposedBridge.log("PD2405ExtTele: C live OIS "
                    + (enabled ? "ON" : "OFF"));
            return true;
        } catch (Throwable error) {
            XposedBridge.log("PD2405ExtTele: C live OIS submit failed: " + error);
            return false;
        }
    }

    private void restoreStockOis() {
        if (!stockOisForcedOff.get()) return;
        Boolean stock = stockOisEnabled;
        if (stock != null && submitStockOis(stock.booleanValue())) {
            stockOisForcedOff.set(false);
        }
    }

    /** Read-only frame metadata probe for the ordinary Photo/SAT path. Null
     *  result values mean unavailable; they must not be interpreted as off. */
    private void installStabilizationProbe(ClassLoader loader) {
        Class<?> cameraParam = XposedHelpers.findClass(
                "com.android.camera.utils.cameradevice.param.CameraParam", loader);
        Class<?> resultKeys = XposedHelpers.findClass(
                "com.android.vcamera.result.VivoCaptureResultKey", loader);
        final Object satKey = XposedHelpers.getStaticObjectField(resultKeys,
                "VIVO_SAT_ACTIVE_CAMERA_TYPE");
        XposedHelpers.findAndHookMethod(
                "com.android.camera.addition.watermark.WatermarkAddition", loader,
                "onMetadataCallback", cameraParam, CaptureResult.class,
                new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        if (!virtualActive.get() || photoUi == null || !isPhoto(photoUi)) return;
                        int n = stabilizationProbeCount.getAndIncrement();
                        if (n >= 5000 || (n >= 10 && n % 75 != 0)) return;
                        try {
                            CaptureResult result = (CaptureResult) param.args[1];
                            if (result == null) return;
                            Object sat = XposedHelpers.callMethod(result, "get", satKey);
                            XposedBridge.log("PD2405ExtTele: stab frame " + n
                                    + " sat=" + sat
                                    + " physical=" + result.get(
                                            CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID)
                                    + " ois=" + result.get(
                                            CaptureResult.LENS_OPTICAL_STABILIZATION_MODE)
                                    + " videoEis=" + result.get(
                                            CaptureResult.CONTROL_VIDEO_STABILIZATION_MODE)
                                    + " lensFocal=" + result.get(
                                            CaptureResult.LENS_FOCAL_LENGTH)
                                    + " zoom=" + result.get(
                                            CaptureResult.CONTROL_ZOOM_RATIO));
                        } catch (Throwable error) {
                            XposedBridge.log("PD2405ExtTele: stab probe skipped: " + error);
                        }
                    }
                });
    }

    /** Only the 35 mm equivalent EXIF value represents the converter's nominal FOV. */
    private void installStillEquivalentFocalLength(ClassLoader loader) {
        Class<?> exifClass = XposedHelpers.findClass(
                "com.android.camera.storage_ext.util.exifs.ExifInterface", loader);
        int equivalentTag = (Integer) XposedHelpers.getStaticObjectField(
                exifClass, "TAG_FOCAL_LENGTH_IN_35_MM_FILE");
        int digitalZoomTag = (Integer) XposedHelpers.getStaticObjectField(
                exifClass, "TAG_DIGITAL_ZOOM_RATIO");
        XposedHelpers.findAndHookMethod(
                "com.android.camera.storage_ext.util.exifs.ExifWriter", loader,
                "exitUpdateExif", new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        boolean virtualStill = virtualActive.get() && photoUi != null
                                && isPhoto(photoUi);
                        boolean virtualVideoSnapshot = isVirtualVideoActive();
                        if (!virtualStill && !virtualVideoSnapshot) return;
                        synchronized (equivalentTagAdjusted) {
                            if (Boolean.TRUE.equals(equivalentTagAdjusted.get(param.thisObject))) return;
                        }
                        try {
                            Object exif = XposedHelpers.getObjectField(param.thisObject, "mExif");
                            Object oldTag = XposedHelpers.callMethod(exif, "getTag", equivalentTag);
                            if (oldTag == null) return;
                            int original = (Integer) XposedHelpers.callMethod(oldTag,
                                    "getValueAsInt", 0);
                            if (original <= 0 || original > 10000) return;
                            int corrected = nominalStillFocal(exif, digitalZoomTag, original);
                            Object newTag = XposedHelpers.callMethod(exif, "buildTag",
                                    equivalentTag, Short.valueOf((short) corrected));
                            if (newTag == null) return;
                            XposedHelpers.callMethod(exif, "setTag", newTag);
                            synchronized (equivalentTagAdjusted) {
                                equivalentTagAdjusted.put(param.thisObject, true);
                            }
                            XposedBridge.log("PD2405ExtTele: "
                                    + (virtualVideoSnapshot ? "video snapshot" : "photo")
                                    + " 35mm EXIF " + original
                                    + " -> " + corrected + " mm (nominal 2.35x)");
                        } catch (Throwable error) {
                            XposedBridge.log("PD2405ExtTele: 35mm EXIF correction skipped: "
                                    + error);
                        }
                    }
                });
    }

    /** Use the JPEG's own zoom tag for continuous positions, not the current UI
     *  zoom, which may already belong to the next shot in a burst. */
    private static int nominalStillFocal(Object exif, int digitalZoomTag, int stockMm) {
        switch (stockMm) {
            case 135: return 320;
            case 170: return 400;
            case 230: return 540;
            case 340: return 800;
            case 1362: return 3200;
            default: break;
        }
        try {
            Object tag = XposedHelpers.callMethod(exif, "getTag", digitalZoomTag);
            if (tag != null) {
                Object rational = XposedHelpers.callMethod(tag, "getValueAsRational", 0L);
                long numerator = ((Number) XposedHelpers.callMethod(
                        rational, "getNumerator")).longValue();
                long denominator = ((Number) XposedHelpers.callMethod(
                        rational, "getDenominator")).longValue();
                if (denominator > 0) {
                    double zoom = (double) numerator / denominator;
                    double expectedStock = 85.0 * zoom / 3.7;
                    // A non-tele, stale or malformed tag must not relabel this JPEG.
                    if (zoom >= 3.7 && zoom <= 100.0
                            && Math.abs(stockMm - expectedStock)
                                    <= Math.max(2.0, expectedStock * 0.005)) {
                        return (int) Math.round(200.0 * zoom / 3.7);
                    }
                }
            }
        } catch (Throwable ignored) {
            // Keep the previously verified nominal conversion as a fallback.
        }
        return Math.round(stockMm * 2.35f);
    }

    /** Adjust the per-capture watermark zoom only, leaving normal zoom settings alone. */
    private void installStillWatermarkFocalLength(ClassLoader loader) {
        Class<?> watermarkParam = XposedHelpers.findClass(
                "com.android.vcamera.parameter.WaterMarkParameter", loader);
        Class<?> watermarkJson = XposedHelpers.findClass(
                "com.android.camera.featureconfig.configuration.watermark.WatermarkJSON", loader);
        XposedHelpers.findAndHookMethod(
                "com.android.camera.addition.watermark.core.WMParamConfigurator", loader,
                "config3AParam", watermarkParam, watermarkJson, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        if (!virtualActive.get() || photoUi == null || !isPhoto(photoUi)) return;
                        try {
                            Object value = XposedHelpers.callMethod(param.args[0],
                                    "getUiZoomRatio");
                            if (!(value instanceof Float)) return;
                            float original = (Float) value;
                            if (original <= 0f || original > 100f) return;
                            float adjusted = original * 2.35f;
                            XposedHelpers.callMethod(param.args[0], "setUiZoomRatio",
                                    Float.valueOf(adjusted));
                            XposedBridge.log("PD2405ExtTele: photo watermark zoom "
                                    + original + " -> " + adjusted + " (nominal 2.35x)");
                        } catch (Throwable error) {
                            XposedBridge.log("PD2405ExtTele: watermark correction skipped: "
                                    + error);
                        }
                    }
                });
    }

    /** Use stock, orientation-aware ZoomCircleButtons rather than a decor overlay. */
    private void installNativeFocalZoomUi(ClassLoader loader) {
        Class<?> settingManagerClass = XposedHelpers.findClass(
                "com.android.camera.setting.api.ISettingManager", loader);
        // Stock ruler truncates its floating focal value when drawing the
        // large label. The button and saved photo round the same value.
        XposedHelpers.findAndHookMethod(
                "com.android.camera.ui.commonui.zoomui.widget.ZoomCircleRuler", loader,
                "drawCurrentZoom", Canvas.class, float.class, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        if (!virtualActive.get() || !nativeZoomRequested.get()
                                || videoVirtualActive.get()) return;
                        int style = ((Number) XposedHelpers.getObjectField(
                                param.thisObject, "mCurrentZoomShowStyle")).intValue();
                        if (style != 2) return;
                        float focal = ((Number) param.args[1]).floatValue();
                        if (Float.isFinite(focal) && focal >= 200f && focal <= 5400f)
                            param.args[1] = (float) Math.round(focal);
                    }
                });
        XposedHelpers.findAndHookMethod(
                "com.android.camera.ui.commonui.zoomui.ZoomUtil", loader,
                "getZoomShowStyle", settingManagerClass, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        if (virtualActive.get() && nativeZoomRequested.get()) {
                            param.setResult(2); // stock focal-length ruler style
                        }
                    }
                });
        XposedHelpers.findAndHookMethod(
                "com.android.camera.ui.commonui.zoomui.ZoomUtil", loader,
                "zoomRatioConvertToFocalLength", float.class, boolean.class,
                settingManagerClass, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        if (!virtualActive.get() || !nativeZoomRequested.get()) return;
                        Object value = param.getResult();
                        if (!(value instanceof Number)) return;
                        float stockMm = ((Number) value).floatValue();
                        if (stockMm <= 0 || stockMm > 3000) return;
                        float uiZoom = ((Number) param.args[0]).floatValue();
                        boolean photoMacro = isPhotoMacroActive();
                        float maxZoom = isStageVideo() ? 30f
                                : videoVirtualActive.get()
                                ? isVideo15xLimited() ? 14.8f : 20f
                                : photoMacro ? 20f : isAdvancedPhoto() ? 10f : 100f;
                        if (uiZoom < 3.7f || uiZoom > maxZoom + 0.05f) return;
                        float equivalentMm = isStageVideo() && uiZoom >= 29.95f
                                ? 1620f : (videoVirtualActive.get() || photoMacro)
                                && uiZoom >= 19.95f
                                && !isStageVideo() ? 1080f
                                : photoMacro && Math.abs(uiZoom - 10f) < 0.01f
                                ? 540f : isAdvancedPhoto() && uiZoom >= 9.95f
                                ? 540f : 200f * Math.min(uiZoom, 99.9f) / 3.7f;
                        param.setResult(equivalentMm);
                        if (focalConversionObserved.compareAndSet(false, true)) {
                            XposedBridge.log("PD2405ExtTele: stock ruler focal "
                                    + stockMm + " at " + uiZoom + "x -> "
                                    + equivalentMm + " mm (nominal)");
                        }
                    }
                });
        // The ruler's four labelled nodes are also its min/max and drag geometry.
        // Do not rewrite ZoomUtil's global inverse: it is used outside the ruler.
        XposedHelpers.findAndHookMethod(
                "com.android.camera.ui.commonui.zoomui.base.BaseZoomModelImpl", loader,
                "initZoomList", Range.class, float.class, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        if (!virtualActive.get() || !nativeZoomRequested.get()
                                || !videoVirtualActive.get()
                                || !isVideo15xLimited()) return;
                        Range<?> range = (Range<?>) param.args[0];
                        float scale = ((Number) param.args[1]).floatValue();
                        float nativeMax = 14.8f / scale;
                        float originalMax = ((Number) range.getUpper()).floatValue();
                        if (nativeMax >= originalMax) return;
                        float nativeMin = ((Number) range.getLower()).floatValue();
                        param.args[0] = new Range<>(nativeMin, nativeMax);
                    }
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        if (!virtualActive.get() || !nativeZoomRequested.get()
                                || isTwoPointStill()) return;
                        Object modelContext = XposedHelpers.getObjectField(
                                param.thisObject, "mContext");
                        boolean video = videoVirtualActive.get();
                        boolean limitedVideo = video && isVideo15xLimited();
                        boolean photoMacro = isPhotoMacroActive();
                        boolean proPhoto = isAdvancedPhoto();
                        XposedHelpers.callMethod(modelContext, "setZoomRulerList", video
                                ? isStageVideo()
                                ? Arrays.asList(200f, 400f, 600f, 800f, 1620f)
                                : limitedVideo
                                ? Arrays.asList(200f, 400f, 600f, 800f)
                                : Arrays.asList(200f, 400f, 600f, 800f, 1080f)
                                : photoMacro ? Arrays.asList(200f, 400f, 540f, 800f, 1080f)
                                : proPhoto ? Arrays.asList(200f, 400f, 540f)
                                : Arrays.asList(200f, 400f, 800f, 1600f, 3200f, 5400f));
                        XposedHelpers.callMethod(modelContext, "setRealZoomRulerList", video
                                ? isStageVideo()
                                ? Arrays.asList(3.7f, 7.4f, 11.1f, 14.8f, 30f)
                                : limitedVideo
                                ? Arrays.asList(3.7f, 7.4f, 11.1f, 14.8f)
                                : Arrays.asList(3.7f, 7.4f, 11.1f, 14.8f, 20f)
                                : photoMacro ? Arrays.asList(3.7f, 7.4f, 10f, 14.8f, 20f)
                                : proPhoto ? Arrays.asList(3.7f, 7.4f, 10f)
                                : Arrays.asList(3.7f, 7.4f, 14.8f, 29.6f, 59.2f, 99.9f));
                        XposedHelpers.callMethod(modelContext, "setSpecialZoomRulerList",
                                new HashSet<Float>());
                        XposedBridge.log("PD2405ExtTele: ruler nodes " + (video
                                ? isStageVideo()
                                ? "200/400/600/800/1620 mm (stage video)"
                                : limitedVideo
                                ? "200/400/600/800 mm (15x video ceiling)"
                                : "200/400/600/800/1080 mm (video)"
                                : photoMacro ? "200/400/540/800/1080 mm (photo macro)"
                                : proPhoto ? "200/400/540 mm (pro photo)"
                                : "200/400/800/1600/3200/5400 mm"));
                    }
                });
        XposedHelpers.findAndHookMethod(
                "com.android.camera.ui.commonui.zoomui.widget.ZoomCircleRuler", loader,
                "initZoomFocalBoldGradationList", new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        if (!virtualActive.get() || !nativeZoomRequested.get()
                                || isTwoPointStill()) return;
                        XposedHelpers.setObjectField(param.thisObject,
                                "mBoldGradationList", videoVirtualActive.get()
                                        ? isStageVideo()
                                        ? Arrays.asList(200f, 400f, 600f, 800f, 1620f)
                                        : isVideo15xLimited()
                                        ? Arrays.asList(200f, 400f, 600f, 800f)
                                        : Arrays.asList(200f, 400f, 600f, 800f, 1080f)
                                        : isPhotoMacroActive()
                                        ? Arrays.asList(200f, 400f, 540f, 800f, 1080f)
                                        : isAdvancedPhoto() ? Arrays.asList(200f, 400f, 540f)
                                        : Arrays.asList(200f, 400f, 800f, 1600f, 3200f, 5400f));
                    }
                });
        XposedHelpers.findAndHookMethod(
                "com.android.camera.ui.commonui.zoomui.widget.ZoomCircleRuler", loader,
                "updateZoomUIForRecording", boolean.class, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        if (Boolean.TRUE.equals(param.args[0]))
                            repair4K120RecordingRuler(param.thisObject);
                    }
                });
        Class<?> rulerResponse = XposedHelpers.findClass(
                "com.android.camera.ui.commonui.zoomui.EntityData.response.RulerResponseInfo",
                loader);
        for (String method : new String[]{"initUIParams", "updateUIParams"}) {
            XposedHelpers.findAndHookMethod(
                    "com.android.camera.ui.commonui.zoomui.widget.ZoomCircleRuler",
                    loader, method, rulerResponse, new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            repair4K120RecordingRuler(param.thisObject);
                        }
                    });
        }
        XposedHelpers.findAndHookMethod(
                "com.android.camera.ui.commonui.zoomui.widget.ZoomCircleRuler", loader,
                "outputCurrentZoom", float.class, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        if (!virtualActive.get() || !nativeZoomRequested.get()
                                || isTwoPointStill()) return;
                        float equivalentMm = ((Number) param.args[0]).floatValue();
                        float maxMm = isStageVideo() ? 1620f
                                : videoVirtualActive.get()
                                ? isVideo15xLimited() ? 800.1f : 1080f
                                : isPhotoMacroActive() ? 1080f
                                : isAdvancedPhoto() ? 540f : 5400f;
                        if (equivalentMm < 200f || equivalentMm > maxMm) return;
                        float stockZoom = isStageVideo() && equivalentMm >= 1619f
                                ? 30f : videoVirtualActive.get()
                                && isVideo15xLimited() && equivalentMm >= 799.5f
                                ? 14.8f : (videoVirtualActive.get()
                                || isPhotoMacroActive()) && equivalentMm >= 1079f
                                && !isStageVideo() ? 20f
                                : isAdvancedPhoto() && equivalentMm >= 539f
                                ? 10f : 3.7f * equivalentMm / 200f;
                        if (splitVideoStockTeleActive.get() || proStockTeleActive.get()) {
                            splitVideoRulerGlobalZoom = stockZoom;
                            splitVideoRulerAt = SystemClock.uptimeMillis();
                        }
                        param.setResult(stockZoom);
                        if (inverseFocalConversionObserved.compareAndSet(false, true)) {
                            XposedBridge.log("PD2405ExtTele: ruler direct "
                                    + equivalentMm + " mm -> " + stockZoom + "x");
                        }
                    }
                });
        XposedHelpers.findAndHookMethod(
                "com.android.camera.ui.commonui.zoomui.view.ZoomUIController", loader,
                "initZoomConfig", boolean.class, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        if (nativeZoomRequested.get()) nativeZoomUiScope.set(true);
                    }
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        nativeZoomUiScope.remove();
                        restorePhotoTeleAfterMacroChange();
                    }
                });
        XposedHelpers.findAndHookMethod(
                "com.android.camera.setting.CameraTypeZoomManager", loader,
                "getZoomConfig", new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        if (!nativeZoomRequested.get()
                                || !Boolean.TRUE.equals(nativeZoomUiScope.get())) return;
                        Object original = param.getResult();
                        if (original == null) return;
                        try {
                            Object replacement;
                            synchronized (nativeZoomConfigs) {
                                replacement = nativeZoomConfigs.get(original);
                                if (replacement == null) {
                                    replacement = makeFocalZoomConfig(original, loader);
                                    if (replacement != null) nativeZoomConfigs.put(original, replacement);
                                }
                            }
                            if (replacement != null) param.setResult(replacement);
                        } catch (Throwable error) {
                            XposedBridge.log("PD2405ExtTele: native zoom config rejected: " + error);
                        }
                    }
                });
        XposedHelpers.findAndHookMethod(
                "com.android.camera.ui.commonui.zoomui.widget.circlegroup.ZoomCircleGroupManager",
                loader, "isNeedShowFocalLen", new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        if (nativeZoomRequested.get()
                                || highResolutionTeleActive.get()) param.setResult(true);
                    }
                });
        XposedHelpers.findAndHookMethod(
                "com.android.camera.ui.commonui.zoomui.widget.circlegroup.ZoomCircleGroupManager",
                loader, "getCheckedButtonIndex", new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        if (nativeZoomRequested.get() && !isNativeRulerVisible()) {
                            param.setResult(selectedFocalStep.get());
                        }
                    }
                });
        Class<?> circleResponse = XposedHelpers.findClass(
                "com.android.camera.ui.commonui.zoomui.EntityData.response.CircleResponseInfo",
                loader);
        Class<?> zoomMessage = XposedHelpers.findClass(
                "com.android.camera.ui.commonui.zoomui.EntityData.ZoomMessage",
                loader);
        XposedHelpers.findAndHookMethod(
                "com.android.camera.ui.commonui.zoomui.base.BaseZoomModelImpl",
                loader, "updateZoom", zoomMessage, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        if (!highResolutionTeleActive.get()) return;
                        Object source = XposedHelpers.getObjectField(param.args[0],
                                "sourceType");
                        String name = String.valueOf(source);
                        if ("ZoomGestureDetector".equals(name)
                                || "ZoomScrollRuler".equals(name)
                                || "ZoomRockerView".equals(name)) {
                            param.setResult(null);
                        }
                    }
                });
        XposedHelpers.findAndHookMethod(
                "com.android.camera.ui.commonui.zoomui.view.ZoomUIController",
                loader, "showOrHideZoomRuler", boolean.class, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        if (highResolutionTeleActive.get()
                                && Boolean.TRUE.equals(param.args[0]))
                            param.setResult(null);
                    }
                });
        XposedHelpers.findAndHookMethod(
                "com.android.camera.ui.commonui.zoomui.widget.circlegroup.ZoomCircleGroupManager",
                loader, "updateUIParams", circleResponse, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        repaintNativeFocalSelection(param.thisObject);
                        repaintHighResolutionFocalButton(param.thisObject);
                    }
                });
        XposedHelpers.findAndHookMethod(
                "com.android.camera.ui.commonui.zoomui.widget.circlegroup.ZoomCircleGroupManager",
                loader, "initUIParams", circleResponse, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        repaintNativeFocalSelection(param.thisObject);
                        repaintHighResolutionFocalButton(param.thisObject);
                    }
                });
        // Entering recording rebuilds the button with the stock 85-mm string.
        // Repaint from the current zoom after that transition completes.
        XposedHelpers.findAndHookMethod(
                "com.android.camera.ui.commonui.zoomui.widget.circlegroup.ZoomCircleGroupManager",
                loader, "updateButtonInRecoding", boolean.class, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        if (!virtualActive.get() || !videoVirtualActive.get()
                                || !nativeZoomRequested.get()) return;
                        Object manager = param.thisObject;
                        repaintNativeFocalSelection(manager);
                        Object group = XposedHelpers.getObjectField(manager, "mZoomCircleGroup");
                        if (group instanceof View) {
                            ((View) group).postDelayed(() -> {
                                if (virtualActive.get() && videoVirtualActive.get()
                                        && nativeZoomRequested.get()) {
                                    repaintNativeFocalSelection(manager);
                                }
                            }, 120);
                        }
                        XposedBridge.log("PD2405ExtTele: refreshed video focal label"
                                + " after recording UI rebuild");
                    }
                });
        // The stock group is re-shown after the wheel closes, but may not
        // receive another CircleResponseInfo. Refresh after its 200 ms anim.
        XposedHelpers.findAndHookMethod(
                "com.android.camera.ui.commonui.zoomui.widget.circlegroup.ZoomCircleGroupManager",
                loader, "showWithAnim", new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        if (!virtualActive.get() || !nativeZoomRequested.get()) return;
                        Object group = XposedHelpers.getObjectField(
                                param.thisObject, "mZoomCircleGroup");
                        if (!(group instanceof View)) return;
                        Object manager = param.thisObject;
                        ((View) group).postDelayed(() -> {
                            if (virtualActive.get() && nativeZoomRequested.get()) {
                                repaintNativeFocalSelection(manager);
                                XposedBridge.log("PD2405ExtTele: refreshed focal rail after ruler close");
                            }
                        }, 240);
                    }
                });
        XposedHelpers.findAndHookMethod(
                "com.android.camera.ui.commonui.zoomui.widget.circlegroup.ZoomCircleButton",
                loader, "setZoomShowStyle", int.class, int.class, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        if (nativeZoomRequested.get()
                                || highResolutionTeleActive.get()) param.args[0] = 2;
                    }
                });
        // A synthetic zoom point must not enter the stock camera-type switch.
        // Route its tap through the already-working ordinary Photo zoom request.
        XposedHelpers.findAndHookMethod(
                "com.android.camera.ui.commonui.zoomui.widget.circlegroup.ZoomCircleButton",
                loader, "onClick", View.class, new XC_MethodHook() {
                     @Override protected void beforeHookedMethod(MethodHookParam param) {
                         if (!virtualActive.get()) return;
                         if (highResolutionTeleActive.get()) {
                             param.setResult(null);
                             return;
                         }
                         if (splitVideoStockTeleActive.get()
                                 && videoVirtualActive.get()) {
                             try {
                                 Object point = XposedHelpers.callMethod(param.thisObject,
                                         "getZoomPoint");
                                 String title = point == null ? "" : String.valueOf(
                                         XposedHelpers.getObjectField(point, "title"));
                                 String[] ratios = {"3.7", "7.4", "11.1", "14.8"};
                                 for (int i = 0; i < ratios.length; i++) {
                                     if (!ratios[i].equals(title)) continue;
                                     param.setResult(null);
                                     selectedFocalStep.set(i);
                                     XposedHelpers.callMethod(param.thisObject,
                                             "setChecked", true, false, true, false);
                                     applyZoom(((View) param.thisObject).getContext(), i);
                                     return;
                                 }
                             } catch (Throwable error) {
                                 XposedBridge.log("PD2405ExtTele: split video button skipped: "
                                         + error);
                             }
                             param.setResult(null);
                             return;
                         }
                         if (proStockTeleActive.get() && isAdvancedPhoto()) {
                             try {
                                 Object point = XposedHelpers.callMethod(param.thisObject,
                                         "getZoomPoint");
                                 String title = point == null ? "" : String.valueOf(
                                         XposedHelpers.getObjectField(point, "title"));
                                 String[] ratios = {"3.7", "10"};
                                 for (int i = 0; i < ratios.length; i++) {
                                     if (!ratios[i].equals(title)) continue;
                                     param.setResult(null);
                                     selectedFocalStep.set(i);
                                     XposedHelpers.callMethod(param.thisObject,
                                             "setChecked", true, false, true, false);
                                     applyZoom(((View) param.thisObject).getContext(), i);
                                     return;
                                 }
                             } catch (Throwable error) {
                                 XposedBridge.log("PD2405ExtTele: pro focal button"
                                         + " skipped: " + error);
                             }
                             param.setResult(null);
                             return;
                         }
                         if (!nativeZoomRequested.get()) return;
                         if (isStageVideo() || "stage_photo".equals(currentPhotoModule())) {
                             // Stage keeps five stock circle views even when our four-point
                             // config is installed. Its view index can therefore disagree with
                             // the attached ZoomPoint. Route by the label actually tapped.
                             param.setResult(null);
                             try {
                                 Object labelView = XposedHelpers.getObjectField(
                                         param.thisObject, "mUncheckedView");
                                 String label = labelView instanceof android.widget.TextView
                                         ? ((android.widget.TextView) labelView).getText()
                                                 .toString().trim() : "";
                                 String[] labels = isStageVideo()
                                         ? new String[]{"200", "400", "600", "800"}
                                         : new String[]{"200", "400", "800", "1600"};
                                 Object point = XposedHelpers.callMethod(param.thisObject,
                                         "getZoomPoint");
                                 for (int i = 0; i < labels.length; i++) {
                                     if (!labels[i].equals(label)) continue;
                                     selectedFocalStep.set(i);
                                     applyZoom(((View) param.thisObject).getContext(), i);
                                     XposedBridge.log("PD2405ExtTele: stage focal tap label="
                                             + label + " point=" + point + " step=" + i);
                                     return;
                                 }
                                 XposedBridge.log("PD2405ExtTele: stage focal tap ignored label="
                                         + label + " point=" + point);
                             } catch (Throwable error) {
                                 XposedBridge.log("PD2405ExtTele: stage focal tap blocked: "
                                         + error);
                             }
                             return;
                         }
                         // Professional Photo has a real, separately selected Tele3P5x
                         // camera. Its stock button dispatch carries the raw 1x/2x
                         // lens-relative zoom and keeps that camera selected.
                         if (isAdvancedPhoto() || isTwoPointStill()) return;
                         try {
                            Object point = XposedHelpers.callMethod(param.thisObject,
                                    "getZoomPoint");
                            if (point == null) return;
                            String title = (String) XposedHelpers.getObjectField(point, "title");
                            String[] ratios = videoVirtualActive.get()
                                    ? new String[]{"3.7", "7.4", "11.1", "14.8"}
                                    : isAdvancedPhoto() ? new String[]{"3.7", "7.4"}
                                    : isPhotoMacroActive()
                                    ? new String[]{"3.7", "7.4", "10"}
                                    : new String[]{"3.7", "7.4", "14.8", "29.6"};
                            for (int i = 0; i < ratios.length; i++) {
                                if (!ratios[i].equals(title)) continue;
                                param.setResult(null);
                                selectedFocalStep.set(i);
                                XposedHelpers.callMethod(param.thisObject, "setChecked",
                                        true, false, true, false);
                                applyZoom(((View) param.thisObject).getContext(), i);
                                XposedBridge.log("PD2405ExtTele: native focal tap " + title
                                        + " via stock Photo zoom, no camera-type switch");
                                return;
                            }
                        } catch (Throwable error) {
                            param.setResult(null);
                            XposedBridge.log("PD2405ExtTele: native focal tap blocked: " + error);
                        }
                    }
                });
    }

    /** A video quality or FPS change replaces the stock zoom config in place. */
    private void installVideoZoomConfigRefresh(ClassLoader loader) {
        XposedHelpers.findAndHookMethod(
                "com.android.camera.setting.CameraTypeZoomManager", loader,
                "createZoomConfig", new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        if (!isVirtualVideoActive() || !nativeZoomShown.get()) return;
                        Object ui = videoUi;
                        if (ui == null) return;
                        try {
                            Object settings = XposedHelpers.callMethod(ui,
                                    "getSettingsManager");
                            if (XposedHelpers.callMethod(settings,
                                    "getZoomManager") != param.thisObject) return;
                            Object config = XposedHelpers.getObjectField(param.thisObject,
                                    "mCurZoomConfig");
                            int route = videoZoomRoute(config);
                            String format = videoFormatKey(settings, loader);
                            boolean limited = isVideo15xLimited();
                            if (route == 0 || (route == activeVideoRoute
                                    && format.equals(activeVideoFormat)
                                    && limited == activeVideo15xLimited)) return;
                            Activity activity = ExternalTelePanel.findActivity(
                                    (Context) XposedHelpers.callMethod(ui, "getContext"));
                            if (activity == null) return;
                            int generation = videoZoomConfigGeneration.incrementAndGet();
                            long scheduledAt = SystemClock.uptimeMillis();
                            View decor = activity.getWindow().getDecorView();
                            Runnable refresh = () -> refreshVideoZoomAfterConfig(settings,
                                    param.thisObject, loader, activity, generation,
                                    scheduledAt);
                            // The stock camera is still reopening at 350 ms on
                            // SAT/4K60; use a common 500-ms first attempt and
                            // retry at 600 ms only if the config is not ready.
                            decor.postDelayed(refresh, 500);
                            decor.postDelayed(refresh, 600);
                        } catch (Throwable error) {
                            XposedBridge.log("PD2405ExtTele: video zoom config watch"
                                    + " skipped: " + error);
                        }
                    }
                });
    }

    private void refreshVideoZoomAfterConfig(Object settings, Object manager,
                                             ClassLoader loader, Activity activity,
                                             int generation, long scheduledAt) {
        if (generation != videoZoomConfigGeneration.get()
                || generation == videoZoomRefreshAttemptedGeneration.get()
                || !isVirtualVideoActive() || !nativeZoomShown.get()) return;
        try {
            Class<?> keys = XposedHelpers.findClass(
                    "com.android.camera.featureconfig.configuration."
                            + "database.ISettingKeys", loader);
            Object recordingKey = XposedHelpers.getStaticObjectField(
                    keys, "KEY_VIDEO_RECORDING_SWITCH");
            Object recording = XposedHelpers.callMethod(settings,
                    "getSettingValueFromKey", recordingKey, (Object) new Class[0]);
            if ("1".equals(String.valueOf(recording))) return;
            Object currentConfig = XposedHelpers.getObjectField(manager,
                    "mCurZoomConfig");
            int currentRoute = videoZoomRoute(currentConfig);
            String currentFormat = videoFormatKey(settings, loader);
            boolean currentLimited = isVideo15xLimited();
            if (currentRoute == 0 || (currentRoute == activeVideoRoute
                    && currentFormat.equals(activeVideoFormat)
                    && currentLimited == activeVideo15xLimited)) return;
            Object controller = zoomController.get();
            if (controller == null) return;
            videoZoomRefreshAttemptedGeneration.set(generation);
            nativeZoomShown.set(false);
            nativeZoomRequested.set(false);
            splitVideoStockTeleActive.set(false);
            videoTeleSwitchTries.set(0);
            synchronized (nativeZoomConfigs) {
                nativeZoomConfigs.clear();
            }
            XposedHelpers.callMethod(controller, "initZoomConfig", true);
            XposedBridge.log("PD2405ExtTele: rebuilding video focal"
                    + " rail after quality/FPS change at "
                    + (SystemClock.uptimeMillis() - scheduledAt) + "ms"
                    + ": " + activeVideoFormat + "/" + activeVideoRoute
                    + "/" + activeVideo15xLimited + " -> " + currentFormat
                    + "/" + currentRoute + "/" + currentLimited);
            showVirtualVideoPanel(activity);
        } catch (Throwable error) {
            XposedBridge.log("PD2405ExtTele: video rail refresh skipped: " + error);
        }
    }

    private int videoZoomRoute(Object config) {
        if (config == null) return 0;
        Object raw = XposedHelpers.getObjectField(config, "cameraTypeZoomRange");
        if (!(raw instanceof Map)) return 0;
        Map<?, ?> ranges = (Map<?, ?>) raw;
        return ranges.containsKey("SAT") ? 1
                : ranges.containsKey("Tele3P5x") ? 2 : 0;
    }

    private String videoFormatKey(Object settings, ClassLoader loader) {
        Class<?> keys = XposedHelpers.findClass(
                "com.android.camera.featureconfig.configuration.database.ISettingKeys", loader);
        Object qualityKey = XposedHelpers.getStaticObjectField(keys,
                "KEY_VIDEO_QUALITY_BACK_VALUE");
        Object fpsKey = XposedHelpers.getStaticObjectField(keys,
                "KEY_VIDEO_FRAME_RATE");
        return String.valueOf(XposedHelpers.callMethod(settings,
                "getSettingValueFromKey", qualityKey, (Object) new Class[0]))
                + "/" + String.valueOf(XposedHelpers.callMethod(settings,
                "getSettingValueFromKey", fpsKey, (Object) new Class[0]));
    }

    private void repaintNativeFocalSelection(Object manager) {
        if (!virtualActive.get() || !nativeZoomRequested.get()) return;
        float uiZoom;
        try {
            Object zoomContext = XposedHelpers.getObjectField(manager, "mZoomContext");
            uiZoom = ((Number) XposedHelpers.callMethod(
                    zoomContext, "getCurrentValue")).floatValue();
            if (uiZoom < 3.65f || uiZoom > 100.05f) return;
        } catch (Throwable error) {
            XposedBridge.log("PD2405ExtTele: focal rail zoom unavailable: " + error);
            return;
        }
        boolean video = videoVirtualActive.get();
        boolean photoMacro = isPhotoMacroActive();
        boolean proPhoto = isAdvancedPhoto();
        boolean snapshot = "motion_deblur".equals(currentPhotoModule());
        boolean portrait = "portrait".equals(currentPhotoModule());
        boolean twoPoint = snapshot || portrait;
        float globalZoom = uiZoom;
        if (((video || photoMacro) && globalZoom >
                (isStageVideo() ? 30.05f : 20.05f))
                || (proPhoto && uiZoom > 10.05f)) return;
        int selected = twoPoint ? (uiZoom >= (portrait ? 5.85f : 9.95f) ? 1 : 0)
                : video
                ? (globalZoom >= 14.77f ? 3 : globalZoom >= 11.07f ? 2
                        : globalZoom >= 7.37f ? 1 : 0)
                : proPhoto ? (uiZoom >= 9.95f ? 1 : 0)
                : photoMacro ? (uiZoom >= 9.95f ? 2
                        : uiZoom >= 7.37f ? 1 : 0)
                : (uiZoom >= 29.57f ? 3 : uiZoom >= 14.77f ? 2
                        : uiZoom >= 7.37f ? 1 : 0);
        selectedFocalStep.set(selected);
        if (isNativeRulerVisible()) {
            if (rulerRepaintSkipObserved.compareAndSet(false, true)) {
                XposedBridge.log("PD2405ExtTele: suppress fixed-button repaint while ruler open");
            }
            return;
        }
        try {
            Object[] buttons = (Object[]) XposedHelpers.getObjectField(
                    manager, "mZoomCircleButtons");
            if (buttons == null || buttons.length < (twoPoint || proPhoto ? 2
                    : photoMacro ? 3 : 4)) return;
            String[] labels = snapshot ? new String[]{"200", "540"}
                    : portrait ? new String[]{"200", "320"}
                    : video
                    ? new String[]{"200", "400", "600", "800"}
                    : proPhoto ? new String[]{"200", "540"}
                    : photoMacro ? new String[]{"200", "400", "540"}
                    : new String[]{"200", "400", "800", "1600"};
            for (int i = 0; i < labels.length; i++) {
                if (i == selected || buttons[i] == null) continue;
                XposedHelpers.callMethod(buttons[i], "setChecked", false,
                        false, false, false);
                Object text = XposedHelpers.getObjectField(buttons[i], "mUncheckedView");
                if (text instanceof android.widget.TextView) {
                    ((android.widget.TextView) text).setText(labels[i]);
                }
            }
            if (buttons[selected] != null) {
                XposedHelpers.callMethod(buttons[selected], "setChecked", true,
                        false, false, false);
                int mm = snapshot ? (selected == 0 ? 200 : 540)
                        : portrait ? (selected == 0 ? 200 : 320)
                        : isStageVideo() && globalZoom >= 29.95f ? 1620
                        : (video || photoMacro) && globalZoom >= 19.95f
                        && !isStageVideo() ? 1080
                        : photoMacro && Math.abs(uiZoom - 10f) < 0.01f ? 540
                        : proPhoto && uiZoom >= 9.95f ? 540
                        : Math.round(200f * Math.min(globalZoom, 99.9f) / 3.7f);
                XposedHelpers.callMethod(buttons[selected], "showFocalLenText", mm + "\nmm");
            }
        } catch (Throwable error) {
            XposedBridge.log("PD2405ExtTele: focal selection repaint skipped: " + error);
        }
    }

    private void repaintHighResolutionFocalButton(Object manager) {
        if (!highResolutionTeleActive.get() || !isHighResolutionActive()) return;
        try {
            Object[] buttons = (Object[]) XposedHelpers.getObjectField(
                    manager, "mZoomCircleButtons");
            if (buttons == null) return;
            int lastTeleIndex = -1;
            for (int i = 0; i < buttons.length; i++) {
                Object button = buttons[i];
                if (!(button instanceof View)) continue;
                Object point = XposedHelpers.callMethod(button, "getZoomPoint");
                if (point != null && "3.7".equals(String.valueOf(
                        XposedHelpers.getObjectField(point, "title")))) {
                    lastTeleIndex = i;
                }
            }
            if (lastTeleIndex < 0) return;
            Object group = XposedHelpers.getObjectField(manager,
                    "mZoomCircleGroup");
            if (group instanceof ViewGroup) {
                ViewGroup views = (ViewGroup) group;
                for (int i = 0; i < views.getChildCount(); i++) {
                    View child = views.getChildAt(i);
                    if (!child.getClass().getName().endsWith(
                            "ZoomCircleGroupBackground")) continue;
                    if (!highResolutionOriginalButtonVisibility.containsKey(child))
                        highResolutionOriginalButtonVisibility.put(child,
                                child.getVisibility());
                    child.setVisibility(View.GONE);
                }
            }
            for (int i = 0; i < buttons.length; i++) {
                Object button = buttons[i];
                if (!(button instanceof View)) continue;
                View buttonView = (View) button;
                if (!highResolutionOriginalButtonVisibility.containsKey(buttonView))
                    highResolutionOriginalButtonVisibility.put(buttonView,
                            buttonView.getVisibility());
                boolean tele = i == lastTeleIndex;
                ((View) button).setVisibility(tele ? View.VISIBLE : View.GONE);
                if (tele) {
                    XposedHelpers.callMethod(button, "setChecked", true,
                            false, false, false);
                    XposedHelpers.callMethod(button, "showFocalLenText", "200\nmm");
                }
            }
        } catch (Throwable error) {
            XposedBridge.log("PD2405ExtTele: high-resolution focal button skipped: "
                    + error);
        }
    }

    private void refreshHighResolutionFocalButton() {
        Object controller = zoomController.get();
        if (controller == null) return;
        try {
            Object manager = XposedHelpers.getObjectField(controller,
                    "mZoomCircleGroupManager");
            if (manager != null) repaintHighResolutionFocalButton(manager);
        } catch (Throwable error) {
            XposedBridge.log("PD2405ExtTele: high-resolution circle unavailable: "
                    + error);
        }
    }

    private void restoreHighResolutionFocalButtons() {
        Object controller = zoomController.get();
        if (controller == null) return;
        try {
            for (Map.Entry<View, Integer> entry
                    : highResolutionOriginalButtonVisibility.entrySet()) {
                entry.getKey().setVisibility(entry.getValue());
            }
            highResolutionOriginalButtonVisibility.clear();
            XposedHelpers.callMethod(controller, "initZoomConfig", true);
        } catch (Throwable error) {
            XposedBridge.log("PD2405ExtTele: high-resolution circle restore skipped: "
                    + error);
        }
    }

    private boolean isNativeRulerVisible() {
        Object controller = zoomController.get();
        if (controller == null) return false;
        try {
            Object rulerManager = XposedHelpers.getObjectField(controller, "mZoomRulerManager");
            if (rulerManager == null) return false;
            Object circle = XposedHelpers.getObjectField(rulerManager, "mZoomCircleRuler");
            if (circle != null && Boolean.TRUE.equals(XposedHelpers.callMethod(circle, "isShow"))) {
                return true;
            }
            Object scroll = XposedHelpers.getObjectField(rulerManager, "mZoomScrollRuler");
            return scroll != null && Boolean.TRUE.equals(XposedHelpers.callMethod(scroll, "isShow"));
        } catch (Throwable ignored) {
            return false;
        }
    }

    private Object makeFocalZoomConfig(Object original, ClassLoader loader) {
        Map<?, ?> ranges = (Map<?, ?>) XposedHelpers.getObjectField(
                original, "cameraTypeZoomRange");
        Map<?, ?> scales = (Map<?, ?>) XposedHelpers.getObjectField(
                original, "cameraTypeZoomScale");
        boolean proPhoto = isAdvancedPhoto();
        boolean splitVideo = splitVideoStockTeleActive.get()
                && videoVirtualActive.get();
        boolean photoMacro = isPhotoMacroActive();
        String stillMode = currentPhotoModule();
        boolean snapshot = "motion_deblur".equals(stillMode);
        boolean portrait = "portrait".equals(stillMode);
        String zoomType = portrait ? "BokehTele3"
                : (proPhoto || snapshot || splitVideo) ? "Tele3P5x" : "SAT";
        Object stockRange = ranges.get(zoomType);
        Object stockScale = scales.get(zoomType);
        if (!(stockRange instanceof Range) || !(stockScale instanceof Number)) {
            if (splitZoomConfigObserved.compareAndSet(false, true)) {
                XposedBridge.log("PD2405ExtTele: zoom config lacks " + zoomType + " for "
                        + (videoVirtualActive.get() ? "video" : isAdvancedPhoto()
                        ? "pro photo" : "photo") + "; ranges=" + ranges
                        + " scales=" + scales + " default="
                        + XposedHelpers.getObjectField(original, "defaultZoomPoint"));
            }
            return null;
        }
        Range<?> range = (Range<?>) stockRange;
        float zoomScale = ((Number) stockScale).floatValue();
        float requiredMax = isStageVideo() ? 30f
                : videoVirtualActive.get() || photoMacro ? 20f
                : proPhoto || snapshot
                ? 10f : portrait ? 5.869565f
                : ("scenery".equals(stillMode)
                || "stage_photo".equals(stillMode)) ? 99.9f : 29.6f;
        float requiredMin = portrait ? 3.695652f : 3.7f;
        if (zoomScale <= 0f || ((Number) range.getLower()).floatValue() * zoomScale
                > requiredMin + 0.01f || ((Number) range.getUpper()).floatValue() * zoomScale
                < requiredMax - 0.01f) {
            XposedBridge.log("PD2405ExtTele: " + zoomType + " range " + range
                    + " scale=" + zoomScale + " cannot cover 3.7–"
                    + requiredMax + "x");
            return null;
        }
        Class<?> configClass = XposedHelpers.findClass(
                "com.android.camera.setting.api.ZoomConfig", loader);
        Class<?> listClass = XposedHelpers.findClass(
                "com.android.camera.setting.api.ZoomConfig$ZoomPointList", loader);
        Class<?> pointClass = XposedHelpers.findClass(
                "com.android.camera.setting.api.ZoomConfig$CameraTypeZoomPoint", loader);
        Object replacement = XposedHelpers.newInstance(configClass);
        Object points = XposedHelpers.newInstance(listClass);
        if (splitVideo || proPhoto) {
            // Keep the Master->Tele gate in the model without displaying a
            // fifth circle button. A Tele-only list leaves GestureDetectorModel
            // with no adjacent camera gate and crashes on pinch.
            Class<?> placeClass = XposedHelpers.findClass(
                    "com.android.camera.setting.api.ZoomConfig$CameraTypeZoomPoint$Place",
                    loader);
            Object none = XposedHelpers.getStaticObjectField(placeClass, "NONE");
            Object anchor = XposedHelpers.newInstance(pointClass,
                    none, "1", "1.0", "Master");
            XposedHelpers.callMethod(points, "add", anchor);
        }
        String[] ratios = snapshot ? new String[]{"3.7", "10"}
                : portrait ? new String[]{"3.7", "5.9"}
                : videoVirtualActive.get()
                ? new String[]{"3.7", "7.4", "11.1", "14.8"}
                : proPhoto ? new String[]{"3.7", "10"}
                : photoMacro ? new String[]{"3.7", "7.4", "10"}
                : new String[]{"3.7", "7.4", "14.8", "29.6"};
        float[] focal = snapshot ? new float[]{200f, 540f}
                : portrait ? new float[]{200f, 320f}
                : videoVirtualActive.get()
                ? new float[]{200f, 400f, 600f, 800f}
                : proPhoto ? new float[]{200f, 540f}
                : photoMacro ? new float[]{200f, 400f, 540f}
                : new float[]{200f, 400f, 800f, 1600f};
        for (int i = 0; i < ratios.length; i++) {
            String nativeRatio = splitVideo
                    ? Float.toString(Float.parseFloat(ratios[i]) / zoomScale)
                    : snapshot ? (i == 0 ? "1.0" : "2.7027028")
                    : portrait ? (i == 0 ? "3.6956522" : "5.869565")
                    : proPhoto ? Float.toString(Float.parseFloat(ratios[i]) / zoomScale)
                    : ratios[i];
            Object point = XposedHelpers.newInstance(pointClass,
                    ratios[i], nativeRatio,
                    zoomType, focal[i]);
            XposedHelpers.callMethod(points, "add", point);
        }
        XposedHelpers.setObjectField(replacement, "cameraTypeZoomRange", ranges);
        XposedHelpers.setObjectField(replacement, "cameraTypeZoomScale", scales);
        XposedHelpers.setObjectField(replacement, "zoomPointList", points);
        XposedHelpers.setObjectField(replacement, "defaultZoomPoint", "3.7");
        XposedHelpers.setObjectField(replacement, "zoomShortcutPointList",
                XposedHelpers.getObjectField(original, "zoomShortcutPointList"));
        return replacement;
    }

    private void installMoreTileClickGuard(ClassLoader loader) {
        XposedHelpers.findAndHookMethod(
                "com.android.camera.ui.commonui.moreui.MoreModuleUI$6", loader,
                "onItemClicked", View.class, int.class, boolean.class, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            Object moreUi = XposedHelpers.getObjectField(param.thisObject, "this$0");
                            Object rawList = XposedHelpers.getObjectField(moreUi, "mSubModulesList");
                            if (!(rawList instanceof List)) return;
                            List<?> modes = (List<?>) rawList;
                            int index = (Integer) param.args[1];
                            if (index < 0 || index >= modes.size()) return;
                            View view = (View) param.args[0];
                            Context context = view.getContext();
                            int titleId = context.getResources().getIdentifier(
                                    "mode_externel_tele", "string", CAMERA);
                            if (titleId == 0 || !context.getString(titleId).contentEquals(
                                    String.valueOf(modes.get(index)))) return;
                            // Consume the stock click before showSubModule can enter the absent mode.
                            param.setResult(true);
                            Object ui = XposedHelpers.getObjectField(moreUi, "mUIController");
                            virtualActive.set(true);
                            if (isPhoto(ui)) {
                                XposedHelpers.callMethod(moreUi, "hideMoreModuleUI", false, false);
                                view.postDelayed(() -> showVirtualPanel(context), 200);
                            } else {
                                int photoTitle = context.getResources().getIdentifier(
                                        "mode_camera", "string", CAMERA);
                                if (photoTitle == 0) throw new IllegalStateException("mode_camera missing");
                                Class<?> commands = XposedHelpers.findClass(
                                        "com.android.camera.constant.ui.UICommands$UICommand",
                                        context.getClassLoader());
                                Object switchCommand = XposedHelpers.getStaticObjectField(
                                        commands, "CMD_SWITCH_TO_MODULE");
                                XposedHelpers.callMethod(ui, "sendUICommand", switchCommand,
                                        (Object) new Object[]{context.getString(photoTitle)});
                            }
                            XposedBridge.log("PD2405ExtTele: More item requested virtual photo UI");
                        } catch (Throwable error) {
                            XposedBridge.log(error);
                        }
                    }
                });
    }

    private void installMoreTileGate(ClassLoader loader) {
        XposedHelpers.findAndHookMethod(
                "com.android.camera.featureconfig.FeatureConfig_meat_PD2405", loader,
                "isSupportModule", boolean.class, String.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (Boolean.FALSE.equals(param.args[0])
                                && EXTERNAL_PHOTO.equals(param.args[1])) {
                            param.setResult(true);
                        }
                    }
                });
    }

    private void installModeIndicatorClickGuard(ClassLoader loader) {
        XC_MethodHook guard = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    int index = (Integer) param.args[0];
                    View indicator = (View) param.thisObject;
                    Object rawList = XposedHelpers.getObjectField(
                            indicator, "modulesTextListForLogical");
                    if (!(rawList instanceof List)) return;
                    List<?> modes = (List<?>) rawList;
                    if (index < 0 || index >= modes.size()) return;
                    Context context = indicator.getContext();
                    int titleId = context.getResources().getIdentifier(
                            "mode_externel_tele", "string", CAMERA);
                    if (titleId == 0 || !context.getString(titleId).contentEquals(
                            String.valueOf(modes.get(index)))) return;
                    param.setResult(null);
                    virtualActive.set(true);
                    if (photoUi != null && isPhoto(photoUi)) {
                        indicator.post(() -> showVirtualPanel(context));
                    } else {
                        int photoTitle = context.getResources().getIdentifier(
                                "mode_camera", "string", CAMERA);
                        String photoName = photoTitle == 0 ? "拍照" : context.getString(photoTitle);
                        int photoIndex = modes.indexOf(photoName);
                        if (photoIndex < 0) {
                            virtualActive.set(false);
                            indicator.post(() -> Toast.makeText(context,
                                    "請先切回拍照模式", Toast.LENGTH_SHORT).show());
                        } else {
                            XposedHelpers.callMethod(indicator, "animateToIndicator", photoIndex);
                        }
                    }
                    XposedBridge.log("PD2405ExtTele: mode bar requested virtual photo UI");
                } catch (Throwable error) {
                    XposedBridge.log(error);
                }
            }
        };
        XposedHelpers.findAndHookMethod(
                "com.android.camera.ui.commonui.ModeIndicatorView", loader,
                "animateToIndicator", int.class, guard);
        XposedHelpers.findAndHookMethod(
                "com.android.camera.ui.commonui.ModeIndicatorView", loader,
                "snapToTab", int.class, boolean.class, guard);
    }

    private void installButton(ClassLoader loader) {
        XposedHelpers.findAndHookMethod(
                "com.android.camera.photo.ui.moduleui.PhotoModuleUI", loader,
                "onModuleEnter", boolean.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            Object ui = XposedHelpers.getObjectField(
                                    param.thisObject, "mUIController");
                            photoUi = ui;
                            Object settings = XposedHelpers.callMethod(ui, "getSettingsManager");
                            Object cameraId = XposedHelpers.callMethod(settings, "getCurrentCameraId");
                            if (Boolean.TRUE.equals(XposedHelpers.callMethod(cameraId, "isFront"))) return;
                            Class<?> managerClass = XposedHelpers.findClass(
                                    "com.android.camera.ui.commonui.sidebuttonui.TopSideButtonManager",
                                    loader);
                            Object manager = XposedHelpers.callStaticMethod(managerClass, "instance");
                            XposedHelpers.callMethod(manager, "init", ui);
                            Context context = (Context) XposedHelpers.callMethod(ui, "getContext");
                            int icon = context.getResources().getIdentifier(
                                    "more_mode_external_tele", "drawable", CAMERA);
                            int title = context.getResources().getIdentifier(
                                    "mode_externel_tele", "string", CAMERA);
                            if (icon == 0 || title == 0) {
                                XposedBridge.log("PD2405ExtTele: expected stock resources missing");
                                return;
                            }
                            Class<?> itemClass = XposedHelpers.findClass(
                                    "com.android.camera.ui.commonui.sidebuttonui."
                                            + "TopSideButtonManager$TopSideListItemInfo", loader);
                            Object item = XposedHelpers.newInstance(itemClass, icon, title, 11);
                            XposedHelpers.callMethod(item, "setOnClickListener",
                                    (View.OnClickListener) view -> {
                                        installOisLongPress(view);
                                        if (virtualActive.get()) {
                                            closeVirtualPanel();
                                        } else {
                                            virtualActive.set(true);
                                            showVirtualPanel(context);
                                        }
                                        int imageId = view.getResources().getIdentifier(
                                                "top_side_item_image", "id", CAMERA);
                                        View iconView = imageId == 0 ? null : view.findViewById(imageId);
                                        if (iconView instanceof ImageView) {
                                            if (virtualActive.get()) {
                                                ((ImageView) iconView).setColorFilter(0xFFFFC645);
                                            } else {
                                                ((ImageView) iconView).clearColorFilter();
                                            }
                                        }
                                        XposedBridge.log("PD2405ExtTele: virtual UI side button toggled");
                                    });
                            XposedHelpers.callMethod(manager, "addButtonItem", item, 1);
                            XposedBridge.log("PD2405ExtTele: display-only button added");
                            if (virtualActive.get()) {
                                Activity activity = ExternalTelePanel.findActivity(context);
                                if (activity != null) activity.getWindow().getDecorView().postDelayed(
                                        () -> showVirtualPanel(context), 300);
                            }
                        } catch (Throwable error) {
                            XposedBridge.log(error);
                        }
                    }
                });
        XposedHelpers.findAndHookMethod(
                "com.android.camera.photo.ui.moduleui.PhotoModuleUI", loader,
                "onModuleExit", boolean.class, Object[].class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        photoUi = null;
                        closeVirtualPanel();
                    }
                });
    }

    private void installSceneryButton(ClassLoader loader) {
        String name = "com.android.camera.scenery.ui.moduleui.SceneryModuleUI";
        XposedHelpers.findAndHookMethod(name, loader, "onModuleEnter", boolean.class,
                new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            Object ui = XposedHelpers.getObjectField(param.thisObject,
                                    "mUIController");
                            Object settings = XposedHelpers.callMethod(ui,
                                    "getSettingsManager");
                            Object module = XposedHelpers.callMethod(settings,
                                    "getCurrentModuleId");
                            if (!"scenery".equals(XposedHelpers.callMethod(module,
                                    "getModuleId"))) return;
                            photoUi = ui;
                            Object cameraId = XposedHelpers.callMethod(settings,
                                    "getCurrentCameraId");
                            if (Boolean.TRUE.equals(XposedHelpers.callMethod(cameraId,
                                    "isFront"))) return;
                            Context context = (Context) XposedHelpers.callMethod(ui,
                                    "getContext");
                            int icon = context.getResources().getIdentifier(
                                    "more_mode_external_tele", "drawable", CAMERA);
                            int title = context.getResources().getIdentifier(
                                    "mode_externel_tele", "string", CAMERA);
                            if (icon == 0 || title == 0) return;
                            Class<?> managerClass = XposedHelpers.findClass(
                                    "com.android.camera.ui.commonui.sidebuttonui."
                                            + "TopSideButtonManager", loader);
                            Object manager = XposedHelpers.callStaticMethod(managerClass,
                                    "instance");
                            Class<?> itemClass = XposedHelpers.findClass(
                                    "com.android.camera.ui.commonui.sidebuttonui."
                                            + "TopSideButtonManager$TopSideListItemInfo", loader);
                            Object item = XposedHelpers.newInstance(itemClass,
                                    icon, title, 11);
                            XposedHelpers.callMethod(item, "setOnClickListener",
                                    (View.OnClickListener) view -> {
                                        installOisLongPress(view);
                                        if (photoUi != ui || !"scenery".equals(
                                                currentPhotoModule())) return;
                                        if (virtualActive.get()) closeVirtualPanel();
                                        else {
                                            virtualActive.set(true);
                                            showVirtualPanel(context);
                                        }
                                        int imageId = view.getResources().getIdentifier(
                                                "top_side_item_image", "id", CAMERA);
                                        View iconView = imageId == 0 ? null
                                                : view.findViewById(imageId);
                                        if (iconView instanceof ImageView) {
                                            if (virtualActive.get()) {
                                                ((ImageView) iconView).setColorFilter(0xFFFFC645);
                                            } else {
                                                ((ImageView) iconView).clearColorFilter();
                                            }
                                        }
                                    });
                            XposedHelpers.callMethod(manager, "addButtonItem", item, 1);
                            XposedBridge.log("PD2405ExtTele: scenery entry added");
                        } catch (Throwable error) {
                            XposedBridge.log("PD2405ExtTele: scenery entry skipped: " + error);
                        }
                    }
                });
        XposedHelpers.findAndHookMethod(name, loader, "onModuleExit",
                boolean.class, Object[].class, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        Object ui = XposedHelpers.getObjectField(param.thisObject,
                                "mUIController");
                        if (ui != photoUi) return;
                        closeVirtualPanel();
                        photoUi = null;
                    }
                });
    }

    private void installStageButtons(ClassLoader loader) {
        XposedHelpers.findAndHookMethod(
                "com.android.camera.stagePhoto.ui.moduleui.StagePhotoModuleUI",
                loader, "onModuleEnter", boolean.class, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            Object ui = XposedHelpers.getObjectField(
                                    param.thisObject, "mUIController");
                            Object settings = XposedHelpers.callMethod(ui,
                                    "getSettingsManager");
                            Object module = XposedHelpers.callMethod(settings,
                                    "getCurrentModuleId");
                            if (!"stage_photo".equals(XposedHelpers.callMethod(
                                    module, "getModuleId"))) return;
                            photoUi = ui;
                            addAdvancedButton(ui, loader, false);
                            XposedBridge.log("PD2405ExtTele: stage photo entry added");
                        } catch (Throwable error) {
                            XposedBridge.log("PD2405ExtTele: stage photo entry failed: "
                                    + error);
                        }
                    }
                });
        XposedHelpers.findAndHookMethod(
                "com.android.camera.stagevideo.ui.moduleui.StageVideoModuleUI",
                loader, "onModuleEnter", boolean.class, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            Object ui = XposedHelpers.getObjectField(
                                    param.thisObject, "mUIController");
                            Object settings = XposedHelpers.callMethod(ui,
                                    "getSettingsManager");
                            Object module = XposedHelpers.callMethod(settings,
                                    "getCurrentModuleId");
                            if (!"stage_video".equals(XposedHelpers.callMethod(
                                    module, "getModuleId"))) return;
                            videoUi = ui;
                            addAdvancedButton(ui, loader, true);
                            XposedBridge.log("PD2405ExtTele: stage video entry added");
                        } catch (Throwable error) {
                            XposedBridge.log("PD2405ExtTele: stage video entry failed: "
                                    + error);
                        }
                    }
                });
        String[] stageClasses = {
                "com.android.camera.stagePhoto.ui.moduleui.StagePhotoModuleUI",
                "com.android.camera.stagevideo.ui.moduleui.StageVideoModuleUI"};
        for (String className : stageClasses) {
            XposedHelpers.findAndHookMethod(className, loader, "onModuleExit",
                    boolean.class, Object[].class, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            Object ui = XposedHelpers.getObjectField(
                                    param.thisObject, "mUIController");
                            if (ui == photoUi) {
                                closeVirtualPanel();
                                photoUi = null;
                            }
                            if (ui == videoUi) {
                                closeVirtualPanel();
                                videoUi = null;
                            }
                        }
                    });
        }
    }

    private void installHighResolutionButton(ClassLoader loader) {
        String name = "com.android.camera.remosaic.ui.moduleui.RemosaicModuleUI";
        XposedHelpers.findAndHookMethod(name, loader, "onModuleEnter",
                boolean.class, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            Object ui = XposedHelpers.getObjectField(param.thisObject,
                                    "mUIController");
                            photoUi = ui;
                            Object settings = XposedHelpers.callMethod(ui,
                                    "getSettingsManager");
                            Object module = XposedHelpers.callMethod(settings,
                                    "getCurrentModuleId");
                            if (!"remosaic".equals(XposedHelpers.callMethod(module,
                                    "getModuleId"))) return;
                            Context context = (Context) XposedHelpers.callMethod(ui,
                                    "getContext");
                            int icon = context.getResources().getIdentifier(
                                    "more_mode_external_tele", "drawable", CAMERA);
                            int title = context.getResources().getIdentifier(
                                    "mode_externel_tele", "string", CAMERA);
                            Class<?> managerClass = XposedHelpers.findClass(
                                    "com.android.camera.ui.commonui.sidebuttonui."
                                            + "TopSideButtonManager", loader);
                            Object manager = XposedHelpers.callStaticMethod(managerClass,
                                    "instance");
                            XposedHelpers.callMethod(manager, "init", ui);
                            Class<?> itemClass = XposedHelpers.findClass(
                                    "com.android.camera.ui.commonui.sidebuttonui."
                                            + "TopSideButtonManager$TopSideListItemInfo",
                                    loader);
                            Object item = XposedHelpers.newInstance(itemClass,
                                    icon, title, 11);
                            XposedHelpers.callMethod(item, "setOnClickListener",
                                    (View.OnClickListener) view -> {
                                        installOisLongPress(view);
                                        if (!isHighResolutionActive()) return;
                                        if (highResolutionTeleActive.get())
                                            closeVirtualPanel();
                                        else activateHighResolutionTele();
                                        int imageId = view.getResources().getIdentifier(
                                                "top_side_item_image", "id", CAMERA);
                                        View image = imageId == 0 ? null
                                                : view.findViewById(imageId);
                                        if (image instanceof ImageView) {
                                            highResolutionSideIcon = new WeakReference<>(
                                                    (ImageView) image);
                                            updateHighResolutionSideIcon();
                                        }
                                    });
                            XposedHelpers.callMethod(manager, "addButtonItem", item, 1);
                            XposedBridge.log("PD2405ExtTele: high-resolution stock side entry added");
                        } catch (Throwable error) {
                            XposedBridge.log("PD2405ExtTele: high-resolution side entry failed: "
                                    + error);
                        }
                    }
                });
        XposedHelpers.findAndHookMethod(name, loader, "onModuleExit",
                boolean.class, Object[].class, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        Object ui = XposedHelpers.getObjectField(param.thisObject,
                                "mUIController");
                        if (photoUi != ui) return;
                        closeVirtualPanel();
                        photoUi = null;
                    }
                });
    }

    private void installVideoButton(ClassLoader loader) {
        XposedHelpers.findAndHookMethod(
                "com.android.camera.normalvideo.ui.moduleui.NormalVideoModuleUI", loader,
                "onModuleEnter", boolean.class, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            Object ui = XposedHelpers.getObjectField(
                                    param.thisObject, "mUIController");
                            videoUi = ui;
                            if (!isVideo(ui)) return;
                            Object settings = XposedHelpers.callMethod(ui, "getSettingsManager");
                            Object cameraId = XposedHelpers.callMethod(settings,
                                    "getCurrentCameraId");
                            if (Boolean.TRUE.equals(XposedHelpers.callMethod(cameraId, "isFront")))
                                return;
                            Context context = (Context) XposedHelpers.callMethod(ui, "getContext");
                            int icon = context.getResources().getIdentifier(
                                    "more_mode_external_tele", "drawable", CAMERA);
                            int title = context.getResources().getIdentifier(
                                    "mode_externel_tele", "string", CAMERA);
                            if (icon == 0 || title == 0) return;
                            Class<?> managerClass = XposedHelpers.findClass(
                                    "com.android.camera.ui.commonui.sidebuttonui.TopSideButtonManager",
                                    loader);
                            Object manager = XposedHelpers.callStaticMethod(managerClass,
                                    "instance");
                            Class<?> itemClass = XposedHelpers.findClass(
                                    "com.android.camera.ui.commonui.sidebuttonui."
                                            + "TopSideButtonManager$TopSideListItemInfo", loader);
                            Object item = XposedHelpers.newInstance(itemClass, icon, title, 11);
                            XposedHelpers.callMethod(item, "setOnClickListener",
                                    (View.OnClickListener) view -> {
                                        installOisLongPress(view);
                                        if (!isVideo(videoUi)) return;
                                        try {
                                            Class<?> keys = XposedHelpers.findClass(
                                                    "com.android.camera.featureconfig.configuration."
                                                            + "database.ISettingKeys", loader);
                                            Object key = XposedHelpers.getStaticObjectField(keys,
                                                    "KEY_VIDEO_RECORDING_SWITCH");
                                            Object currentSettings = XposedHelpers.callMethod(
                                                    videoUi, "getSettingsManager");
                                            Object state = XposedHelpers.callMethod(currentSettings,
                                                    "getSettingValueFromKey", key,
                                                    (Object) new Class[0]);
                                            if (state == null) {
                                                XposedBridge.log("PD2405ExtTele: recording state null");
                                                return;
                                            }
                                            if ("1".equals(String.valueOf(state))) {
                                                Toast.makeText(view.getContext(),
                                                        "請先停止錄影再切換長焦增距",
                                                        Toast.LENGTH_SHORT).show();
                                                return;
                                            }
                                        } catch (Throwable error) {
                                            XposedBridge.log("PD2405ExtTele: recording state "
                                                    + "not resolved; refusing toggle: " + error);
                                            return;
                                        }
                                        if (videoVirtualActive.get()) {
                                            closeVirtualPanel();
                                        } else {
                                            videoVirtualActive.set(true);
                                            virtualActive.set(true);
                                            showVirtualVideoPanel(view.getContext());
                                        }
                                        int imageId = view.getResources().getIdentifier(
                                                "top_side_item_image", "id", CAMERA);
                                        View iconView = imageId == 0 ? null
                                                : view.findViewById(imageId);
                                        if (iconView instanceof ImageView) {
                                            if (videoVirtualActive.get()) {
                                                ((ImageView) iconView).setColorFilter(0xFFFFC645);
                                            } else {
                                                ((ImageView) iconView).clearColorFilter();
                                            }
                                        }
                                    });
                            XposedHelpers.callMethod(manager, "addButtonItem", item, 1);
                            XposedBridge.log("PD2405ExtTele: video trial button added");
                        } catch (Throwable error) {
                            XposedBridge.log("PD2405ExtTele: video button skipped: " + error);
                        }
                    }
                });
        XposedHelpers.findAndHookMethod(
                "com.android.camera.normalvideo.ui.moduleui.NormalVideoModuleUI", loader,
                "onModuleExit", boolean.class, Object[].class, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        if (videoVirtualActive.get()) closeVirtualPanel();
                        videoUi = null;
                    }
                });
    }

    private void installAdvancedButtons(ClassLoader loader) {
        // Keep the stock Pro tele camera graph; only the external-tele entry is added.
        XposedHelpers.findAndHookMethod(
                "com.android.camera.advanced.ui.moduleui.AdvancedModuleUI", loader,
                "onModuleEnter", boolean.class, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            Object ui = XposedHelpers.getObjectField(param.thisObject,
                                    "mUIController");
                            Object settings = XposedHelpers.callMethod(ui, "getSettingsManager");
                            Object module = XposedHelpers.callMethod(settings,
                                    "getCurrentModuleId");
                            if (!"advanced".equals(XposedHelpers.callMethod(module,
                                    "getModuleId"))) return;
                            photoUi = ui;
                            addAdvancedButton(ui, loader, false);
                        } catch (Throwable error) {
                            XposedBridge.log("PD2405ExtTele: pro photo entry skipped: " + error);
                        }
                    }
                });
        XposedHelpers.findAndHookMethod(
                "com.android.camera.videoadvanced.ui.moduleui.VideoAdvancedModuleUI", loader,
                "onModuleEnter", boolean.class, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            Object ui = XposedHelpers.getObjectField(param.thisObject,
                                    "mUIController");
                            if (!isVideo(ui)) return;
                            videoUi = ui;
                            addAdvancedButton(ui, loader, true);
                        } catch (Throwable error) {
                            XposedBridge.log("PD2405ExtTele: pro video entry skipped: " + error);
                        }
                    }
                });
        XposedHelpers.findAndHookMethod(
                "com.android.camera.advanced.ui.moduleui.AdvancedModuleUI", loader,
                "onModuleExit", boolean.class, Object[].class, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        Object ui = XposedHelpers.getObjectField(param.thisObject,
                                "mUIController");
                        if (ui == photoUi) {
                            closeVirtualPanel();
                            photoUi = null;
                        }
                        if (ui == videoUi) {
                            closeVirtualPanel();
                            videoUi = null;
                        }
                    }
                });
    }

    /** Read-only, per-mode zoom evidence for the requested Snapshot and Portrait paths. */
    private void installStillModeDiagnostics(ClassLoader loader) {
        String[] classes = {
                "com.android.camera.motiondeblur.ui.moduleui.MotionDeblurModuleUI",
                "com.android.camera.portrait.ui.moduleui.PortraitModuleUI"};
        for (String name : classes) {
            XposedHelpers.findAndHookMethod(name, loader, "onModuleEnter", boolean.class,
                    new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Object ui = XposedHelpers.getObjectField(param.thisObject,
                                        "mUIController");
                                Object settings = XposedHelpers.callMethod(ui, "getSettingsManager");
                                Object module = XposedHelpers.callMethod(settings,
                                        "getCurrentModuleId");
                                Object cameraId = XposedHelpers.callMethod(settings,
                                        "getCurrentCameraId");
                                Object manager = XposedHelpers.callMethod(settings,
                                        "getZoomManager");
                                Object config = XposedHelpers.getObjectField(manager,
                                        "mCurZoomConfig");
                                String mode = String.valueOf(XposedHelpers.callMethod(module,
                                        "getModuleId"));
                                XposedBridge.log("PD2405ExtTele: still-mode probe " + mode
                                        + " cameraType=" + XposedHelpers.getObjectField(
                                        cameraId, "mCameraType"));
                                if (config != null) {
                                    XposedBridge.log("PD2405ExtTele: still-mode zoom " + mode
                                            + " ranges=" + XposedHelpers.getObjectField(
                                            config, "cameraTypeZoomRange") + " scales="
                                            + XposedHelpers.getObjectField(config,
                                            "cameraTypeZoomScale") + " default="
                                            + XposedHelpers.getObjectField(config,
                                            "defaultZoomPoint") + " points="
                                            + XposedHelpers.getObjectField(config,
                                            "zoomPointList"));
                                }
                            } catch (Throwable error) {
                                XposedBridge.log("PD2405ExtTele: still-mode probe " + name
                                        + " failed: " + error);
                            }
                        }
                    });
        }
    }

    private void installStageAndHighResolutionDiagnostics(ClassLoader loader) {
        String[] classes = {
                "com.android.camera.stagePhoto.ui.moduleui.StagePhotoModuleUI",
                "com.android.camera.stagevideo.ui.moduleui.StageVideoModuleUI"};
        for (String name : classes) {
            XposedHelpers.findAndHookMethod(name, loader, "onModuleEnter",
                    boolean.class, new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                Object ui = XposedHelpers.getObjectField(
                                        param.thisObject, "mUIController");
                                logStageZoomState(ui, "entry");
                            } catch (Throwable error) {
                                XposedBridge.log("PD2405ExtTele: stage probe "
                                        + name + " failed: " + error);
                            }
                        }
                    });
        }
        XposedHelpers.findAndHookMethod(
                "com.android.camera.ui.moduleui.additionui.highresolution."
                        + "HighResolutionAdditionUI", loader,
                "updateAdditionUI", Object[].class, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        if (highResolutionUiProbeCount.getAndIncrement() >= 20) return;
                        try {
                            Object settings = XposedHelpers.getObjectField(
                                    param.thisObject, "mSettingManager");
                            Object module = XposedHelpers.callMethod(settings,
                                    "getCurrentModuleId");
                            Object cameraId = XposedHelpers.callMethod(settings,
                                    "getCurrentCameraId");
                            Object manager = XposedHelpers.callMethod(settings,
                                    "getZoomManager");
                            Object config = XposedHelpers.getObjectField(manager,
                                    "mCurZoomConfig");
                            Object highResolution = XposedHelpers.callMethod(settings,
                                    "getSettingBooleanValueWithCheck",
                                    "pref_portrait_high_resolution");
                            XposedBridge.log("PD2405ExtTele: high-resolution UI mode="
                                    + XposedHelpers.callMethod(module, "getModuleId")
                                    + " active=" + highResolution
                                    + " camera=" + XposedHelpers.getObjectField(cameraId,
                                    "mCameraType") + " ranges=" + (config == null ? null
                                    : XposedHelpers.getObjectField(config,
                                    "cameraTypeZoomRange")) + " scales="
                                    + (config == null ? null : XposedHelpers.getObjectField(
                                    config, "cameraTypeZoomScale")));
                        } catch (Throwable error) {
                            XposedBridge.log("PD2405ExtTele: high-resolution UI probe "
                                    + "failed: " + error);
                        }
                    }
                });
        XposedHelpers.findAndHookMethod(
                "com.android.camera.setting.CameraTypeZoomManager", loader,
                "createZoomConfig", new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        if (highResolutionProbeCount.get() >= 12 || photoUi == null) return;
                        try {
                            Object settings = XposedHelpers.callMethod(photoUi,
                                    "getSettingsManager");
                            if (XposedHelpers.callMethod(settings,
                                    "getZoomManager") != param.thisObject) return;
                            if (!isHighResolutionActive()) return;
                            highResolutionProbeCount.incrementAndGet();
                            logStageZoomState(photoUi, "high-resolution config");
                        } catch (Throwable error) {
                            XposedBridge.log("PD2405ExtTele: high-resolution probe failed: "
                                    + error);
                        }
                    }
                });
    }

    private boolean isHighResolutionActive() {
        try {
            Object controller = zoomController.get();
            if (controller == null) return false;
            Object settings = XposedHelpers.getObjectField(controller,
                    "mSettingManager");
            Object module = XposedHelpers.callMethod(settings,
                    "getCurrentModuleId");
            if (!"remosaic".equals(XposedHelpers.callMethod(module,
                    "getModuleId"))) return false;
            Object ui = XposedHelpers.getObjectField(controller,
                    "mUIController");
            if (ui == null) return false;
            photoUi = ui;
            return true;
        } catch (Throwable error) {
            XposedBridge.log("PD2405ExtTele: high-resolution mode check failed: "
                    + error);
            return false;
        }
    }

    private void refreshHighResolutionEntry(Activity activity, int generation) {
        if (generation != highResolutionUiGeneration.get() || !cameraResumed) return;
        try {
            boolean highResolution = isHighResolutionActive();
            View entry = highResolutionEntry.get();
            if (highResolution) {
                if (highResolutionTeleActive.get())
                    refreshHighResolutionFocalButton();
                if (virtualActive.get() && nativeZoomRequested.get()) {
                    closeVirtualPanel();
                    activateHighResolutionTele();
                }
            } else {
                if (highResolutionTeleActive.get()) closeVirtualPanel();
                if (entry != null && entry.getParent() instanceof ViewGroup) {
                    ((ViewGroup) entry.getParent()).removeView(entry);
                }
                highResolutionEntry = new WeakReference<>(null);
            }
        } catch (Throwable error) {
            XposedBridge.log("PD2405ExtTele: high-resolution entry refresh skipped: "
                    + error);
        }
        activity.getWindow().getDecorView().postDelayed(
                () -> refreshHighResolutionEntry(activity, generation), 350);
    }

    private View createHighResolutionEntry(Activity activity) {
        int size = Math.round(42f * activity.getResources()
                .getDisplayMetrics().density);
        int margin = Math.round(18f * activity.getResources()
                .getDisplayMetrics().density);
        android.widget.LinearLayout entry = new android.widget.LinearLayout(activity);
        entry.setOrientation(android.widget.LinearLayout.VERTICAL);
        entry.setGravity(android.view.Gravity.CENTER);
        entry.setPadding(margin / 2, margin / 2, margin / 2, margin / 2);
        entry.setBackgroundColor(0x99000000);
        ImageView icon = new ImageView(activity);
        int drawable = activity.getResources().getIdentifier(
                "more_mode_external_tele", "drawable", CAMERA);
        if (drawable != 0) icon.setImageResource(drawable);
        entry.addView(icon, new android.widget.LinearLayout.LayoutParams(size, size));
        android.widget.TextView focal = new android.widget.TextView(activity);
        focal.setText("200 mm");
        focal.setTextColor(0xFFFFC645);
        focal.setTextSize(12f);
        focal.setGravity(android.view.Gravity.CENTER);
        entry.addView(focal);
        entry.setContentDescription("長焦增距，固定 200 mm");
        entry.setClickable(true);
        entry.setOnClickListener(view -> {
            if (!isHighResolutionActive()) return;
            if (highResolutionTeleActive.get()) closeVirtualPanel();
            else activateHighResolutionTele();
            updateHighResolutionEntry(view);
        });
        installOisLongPress(entry);
        android.widget.FrameLayout decor = (android.widget.FrameLayout)
                activity.getWindow().getDecorView();
        android.widget.FrameLayout.LayoutParams layout =
                new android.widget.FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        android.view.Gravity.TOP | android.view.Gravity.END);
        layout.topMargin = Math.round(130f * activity.getResources()
                .getDisplayMetrics().density);
        layout.rightMargin = margin;
        decor.addView(entry, layout);
        return entry;
    }

    private void updateHighResolutionEntry(View entry) {
        if (!(entry instanceof android.widget.LinearLayout)) return;
        android.widget.LinearLayout group = (android.widget.LinearLayout) entry;
        ImageView icon = (ImageView) group.getChildAt(0);
        android.widget.TextView focal = (android.widget.TextView) group.getChildAt(1);
        if (highResolutionTeleActive.get()) icon.setColorFilter(0xFFFFC645);
        else icon.clearColorFilter();
        focal.setVisibility(highResolutionTeleActive.get() ? View.VISIBLE : View.GONE);
    }

    private String currentPhotoCameraType() {
        try {
            Object settings = XposedHelpers.callMethod(photoUi,
                    "getSettingsManager");
            Object cameraId = XposedHelpers.callMethod(settings,
                    "getCurrentCameraId");
            return String.valueOf(XposedHelpers.getObjectField(cameraId,
                    "mCameraType"));
        } catch (Throwable error) {
            return "unknown";
        }
    }

    private void activateHighResolutionTele() {
        if (!isHighResolutionActive()) return;
        int generation = highResolutionTeleRequestGeneration.incrementAndGet();
        activateHighResolutionTele(generation, 0);
    }

    private void activateHighResolutionTele(int generation, int attempt) {
        if (generation != highResolutionTeleRequestGeneration.get()
                || !isHighResolutionActive()) return;
        if (!"Tele3P5x".equals(currentPhotoCameraType())) {
            if (attempt >= 4) {
                XposedBridge.log("PD2405ExtTele: high-resolution tele switch timed out"
                        + " camera=" + currentPhotoCameraType());
                return;
            }
            Object controller = zoomController.get();
            if (controller == null) return;
            Activity activity = ExternalTelePanel.findActivity((Context)
                    XposedHelpers.callMethod(photoUi, "getContext"));
            if (activity == null) return;
            if (attempt == 0) {
                try {
                    XposedHelpers.callMethod(controller, "onZoomForOuter",
                            "3.7", false);
                    XposedBridge.log("PD2405ExtTele: high-resolution requested stock tele");
                } catch (Throwable error) {
                    XposedBridge.log("PD2405ExtTele: high-resolution tele request failed: "
                            + error);
                    return;
                }
            }
            activity.getWindow().getDecorView().postDelayed(
                    () -> activateHighResolutionTele(generation, attempt + 1), 250);
            return;
        }
        virtualActive.set(true);
        videoVirtualActive.set(false);
        highResolutionTeleActive.set(true);
        updateHighResolutionSideIcon();
        refreshHighResolutionFocalButton();
        previewRotationObserved.set(false);
        captureRotationObserved.set(false);
        if (nativeOisSignal != null) nativeOisSignal.refresh();
        XposedBridge.log("PD2405ExtTele: high-resolution fixed 200-mm tele active");
    }

    private void updateHighResolutionSideIcon() {
        ImageView icon = highResolutionSideIcon.get();
        if (icon == null) return;
        if (highResolutionTeleActive.get()) icon.setColorFilter(0xFFFFC645);
        else icon.clearColorFilter();
    }

    private void logStageZoomState(Object ui, String event) {
        Object settings = XposedHelpers.callMethod(ui, "getSettingsManager");
        Object module = XposedHelpers.callMethod(settings, "getCurrentModuleId");
        Object cameraId = XposedHelpers.callMethod(settings, "getCurrentCameraId");
        Object manager = XposedHelpers.callMethod(settings, "getZoomManager");
        Object config = XposedHelpers.getObjectField(manager, "mCurZoomConfig");
        XposedBridge.log("PD2405ExtTele: stage probe " + event + " mode="
                + XposedHelpers.callMethod(module, "getModuleId")
                + " camera=" + XposedHelpers.getObjectField(cameraId, "mCameraType")
                + " ranges=" + (config == null ? null : XposedHelpers.getObjectField(
                config, "cameraTypeZoomRange"))
                + " scales=" + (config == null ? null : XposedHelpers.getObjectField(
                config, "cameraTypeZoomScale"))
                + " default=" + (config == null ? null : XposedHelpers.getObjectField(
                config, "defaultZoomPoint"))
                + " points=" + (config == null ? null : XposedHelpers.getObjectField(
                config, "zoomPointList")));
    }

    private String currentPhotoModule() {
        if (photoUi == null) return "";
        try {
            Object settings = XposedHelpers.callMethod(photoUi, "getSettingsManager");
            Object module = XposedHelpers.callMethod(settings, "getCurrentModuleId");
            return String.valueOf(XposedHelpers.callMethod(module, "getModuleId"));
        } catch (Throwable ignored) { return ""; }
    }

    private boolean isTwoPointStill() {
        String mode = currentPhotoModule();
        return "motion_deblur".equals(mode) || "portrait".equals(mode);
    }

    private void installTwoPointStillButton(ClassLoader loader, String className,
                                            String expectedMode) {
        XposedHelpers.findAndHookMethod(className, loader, "onModuleEnter", boolean.class,
                new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            Object ui = XposedHelpers.getObjectField(param.thisObject,
                                    "mUIController");
                            Object settings = XposedHelpers.callMethod(ui,
                                    "getSettingsManager");
                            Object module = XposedHelpers.callMethod(settings,
                                    "getCurrentModuleId");
                            if (!expectedMode.equals(XposedHelpers.callMethod(module,
                                    "getModuleId"))) return;
                            Object cameraId = XposedHelpers.callMethod(settings,
                                    "getCurrentCameraId");
                            if (Boolean.TRUE.equals(XposedHelpers.callMethod(cameraId,
                                    "isFront"))) return;
                            photoUi = ui;
                            Context context = (Context) XposedHelpers.callMethod(ui,
                                    "getContext");
                            int icon = context.getResources().getIdentifier(
                                    "more_mode_external_tele", "drawable", CAMERA);
                            int title = context.getResources().getIdentifier(
                                    "mode_externel_tele", "string", CAMERA);
                            if (icon == 0 || title == 0) return;
                            Class<?> managerClass = XposedHelpers.findClass(
                                    "com.android.camera.ui.commonui.sidebuttonui."
                                            + "TopSideButtonManager", loader);
                            Object manager = XposedHelpers.callStaticMethod(managerClass,
                                    "instance");
                            XposedHelpers.callMethod(manager, "init", ui);
                            Class<?> itemClass = XposedHelpers.findClass(
                                    "com.android.camera.ui.commonui.sidebuttonui."
                                            + "TopSideButtonManager$TopSideListItemInfo", loader);
                            Object item = XposedHelpers.newInstance(itemClass, icon, title, 11);
                            XposedHelpers.callMethod(item, "setOnClickListener",
                                    (View.OnClickListener) view -> {
                                        installOisLongPress(view);
                                        if (photoUi != ui || !expectedMode.equals(
                                                currentPhotoModule())) return;
                                        twoPointSideButton = new WeakReference<>(view);
                                        if (virtualActive.get()) closeVirtualPanel();
                                        else {
                                            virtualActive.set(true);
                                            showVirtualPanel(context);
                                        }
                                    });
                            XposedHelpers.callMethod(manager, "addButtonItem", item, 1);
                            XposedBridge.log("PD2405ExtTele: two-point still entry added "
                                    + expectedMode);
                        } catch (Throwable error) {
                            XposedBridge.log("PD2405ExtTele: still entry skipped "
                                    + expectedMode + " " + error);
                        }
                    }
                });
        XposedHelpers.findAndHookMethod(className, loader, "onModuleExit",
                boolean.class, Object[].class, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        Object ui = XposedHelpers.getObjectField(param.thisObject,
                                "mUIController");
                        if (ui == photoUi) {
                            closeVirtualPanel();
                            photoUi = null;
                        }
                    }
                });
    }

    private void addAdvancedButton(Object ui, ClassLoader loader, boolean video) {
        Object settings = XposedHelpers.callMethod(ui, "getSettingsManager");
        Object cameraId = XposedHelpers.callMethod(settings, "getCurrentCameraId");
        if (Boolean.TRUE.equals(XposedHelpers.callMethod(cameraId, "isFront"))) return;
        Context context = (Context) XposedHelpers.callMethod(ui, "getContext");
        int icon = context.getResources().getIdentifier(
                "more_mode_external_tele", "drawable", CAMERA);
        int title = context.getResources().getIdentifier(
                "mode_externel_tele", "string", CAMERA);
        if (icon == 0 || title == 0) return;
        Class<?> managerClass = XposedHelpers.findClass(
                "com.android.camera.ui.commonui.sidebuttonui.TopSideButtonManager", loader);
        Object manager = XposedHelpers.callStaticMethod(managerClass, "instance");
        if (!video) XposedHelpers.callMethod(manager, "init", ui);
        Class<?> itemClass = XposedHelpers.findClass(
                "com.android.camera.ui.commonui.sidebuttonui."
                        + "TopSideButtonManager$TopSideListItemInfo", loader);
        Object item = XposedHelpers.newInstance(itemClass, icon, title, 11);
        XposedHelpers.callMethod(item, "setOnClickListener", (View.OnClickListener) view -> {
            if (video) {
                if (videoUi != ui || !isVideo(ui)) return;
                try {
                    Class<?> keys = XposedHelpers.findClass(
                            "com.android.camera.featureconfig.configuration.database.ISettingKeys",
                            loader);
                    Object key = XposedHelpers.getStaticObjectField(keys,
                            "KEY_VIDEO_RECORDING_SWITCH");
                    Object state = XposedHelpers.callMethod(settings,
                            "getSettingValueFromKey", key, (Object) new Class[0]);
                    if (state == null || "1".equals(String.valueOf(state))) {
                        Toast.makeText(context, "請先停止錄影再切換長焦增距",
                                Toast.LENGTH_SHORT).show();
                        return;
                    }
                } catch (Throwable error) {
                    XposedBridge.log("PD2405ExtTele: pro recording state unavailable: " + error);
                    return;
                }
            } else if (photoUi != ui || !isPhoto(ui)) {
                return;
            }
            if (virtualActive.get()) {
                closeVirtualPanel();
            } else {
                if (video) videoVirtualActive.set(true);
                virtualActive.set(true);
                if (video) showVirtualVideoPanel(context);
                else showVirtualPanel(context);
            }
            int imageId = view.getResources().getIdentifier(
                    "top_side_item_image", "id", CAMERA);
            View iconView = imageId == 0 ? null : view.findViewById(imageId);
            if (iconView instanceof ImageView) {
                if (virtualActive.get()) ((ImageView) iconView).setColorFilter(0xFFFFC645);
                else ((ImageView) iconView).clearColorFilter();
            }
        });
        XposedHelpers.callMethod(manager, "addButtonItem", item, video ? 2 : 1);
        XposedBridge.log("PD2405ExtTele: pro " + (video ? "video" : "photo")
                + " trial button added");
    }

    private boolean isPhoto(Object ui) {
        try {
            Object settings = XposedHelpers.callMethod(ui, "getSettingsManager");
            Object module = XposedHelpers.callMethod(settings, "getCurrentModuleId");
            String id = String.valueOf(XposedHelpers.callMethod(module, "getModuleId"));
            return "photo".equals(id) || "advanced".equals(id)
                    || "scenery".equals(id)
                    || "stage_photo".equals(id) || "remosaic".equals(id)
                    || "motion_deblur".equals(id) || "portrait".equals(id);
        } catch (Throwable error) {
            XposedBridge.log(error);
            return false;
        }
    }

    private boolean isLiveTeleZoom() {
        try {
            if (highResolutionTeleActive.get()) {
                return "Tele3P5x".equals(currentPhotoCameraType());
            }
            Object controller = zoomController.get();
            if (controller == null) return false;
            Object context = XposedHelpers.getObjectField(controller, "mContext");
            float zoom = ((Number) XposedHelpers.callMethod(context,
                    "getCurrentValue")).floatValue();
            if ((proStockTeleActive.get() && isAdvancedPhoto())
                    || (splitVideoStockTeleActive.get() && videoVirtualActive.get())) {
                Object ui = videoVirtualActive.get() ? videoUi : photoUi;
                Object settings = XposedHelpers.callMethod(ui,
                        "getSettingsManager");
                Object cameraId = XposedHelpers.callMethod(settings,
                        "getCurrentCameraId");
                return "Tele3P5x".equals(XposedHelpers.getObjectField(cameraId,
                        "mCameraType"));
            }
            return Float.isFinite(zoom) && zoom >= 3.65f;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void installOisLongPress(View view) {
        view.setOnLongClickListener(held -> {
            if (!virtualActive.get() || (!nativeZoomRequested.get()
                    && !proStockTeleActive.get()
                    && !highResolutionTeleActive.get()
                    && !splitVideoStockTeleActive.get())
                    || !cameraResumed || !isLiveTeleZoom()) {
                Toast.makeText(held.getContext(), "請先進入長焦增距模式",
                        Toast.LENGTH_SHORT).show();
                return true;
            }
            int previousMode = oisGainMode.get();
            int mode = (previousMode + 1) % 4;
            if (mode == 3 && (stockOisKey == null || stockOisAddition.get() == null
                    || stockOisEnabled == null)) {
                Toast.makeText(held.getContext(), "OIS 開關尚未就緒",
                        Toast.LENGTH_SHORT).show();
                return true;
            }
            oisGainMode.set(mode);
            if (mode == 3) {
                if (!submitStockOis(false)) {
                    oisGainMode.set(previousMode);
                    Toast.makeText(held.getContext(), "OIS 關閉失敗，維持原模式",
                            Toast.LENGTH_SHORT).show();
                    return true;
                }
            } else if (previousMode == 3) {
                restoreStockOis();
            }
            if (nativeOisSignal != null) nativeOisSignal.refresh();
            Toast.makeText(held.getContext(), mode == 1 ? "增距OIS A：固定2.35倍"
                    : mode == 2 ? "增距OIS B：AF公式浮動"
                    : mode == 3 ? "增距OIS C：關閉防抖"
                    : "增距OIS：關閉（原廠值）", Toast.LENGTH_SHORT).show();
            XposedBridge.log("PD2405ExtTele: OIS trial mode=" + mode);
            return true;
        });
    }

    /** RecyclerView can replace the logo View after a zoom or module change. */
    private void installOisSideButtonBinding(ClassLoader loader) {
        Class<?> holderClass = XposedHelpers.findClass(
                "com.android.camera.ui.base.MyViewHolder", loader);
        Class<?> itemClass = XposedHelpers.findClass(
                "com.android.camera.ui.commonui.sidebuttonui."
                        + "TopSideButtonManager$TopSideListItemInfo", loader);
        XposedHelpers.findAndHookMethod(
                "com.android.camera.ui.commonui.sidebuttonui."
                        + "TopSideButtonManager$SideListAdapter", loader,
                "convert", holderClass, itemClass, int.class, new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                try {
                    if (param.args.length < 2 || param.args[1] == null) return;
                    Object item = param.args[1];
                    if (((Number) XposedHelpers.callMethod(item, "getType")).intValue() != 11)
                        return;
                    Object holder = param.args[0];
                    View view = (View) XposedHelpers.getObjectField(holder, "itemView");
                    int logo = view.getResources().getIdentifier(
                            "more_mode_external_tele", "drawable", CAMERA);
                    int image = ((Number) XposedHelpers.callMethod(item,
                            "getItemImgId")).intValue();
                    if (logo != 0 && image == logo) installOisLongPress(view);
                } catch (Throwable error) {
                    XposedBridge.log("PD2405ExtTele: OIS logo binding skipped: " + error);
                }
            }
        });
    }

    private boolean isVideo(Object ui) {
        try {
            Object settings = XposedHelpers.callMethod(ui, "getSettingsManager");
            Object module = XposedHelpers.callMethod(settings, "getCurrentModuleId");
            String id = String.valueOf(XposedHelpers.callMethod(module, "getModuleId"));
            return "video".equals(id) || "video_advanced".equals(id)
                    || "stage_video".equals(id);
        } catch (Throwable error) {
            XposedBridge.log(error);
            return false;
        }
    }

    private boolean isStageVideo() {
        if (!videoVirtualActive.get() || videoUi == null) return false;
        try {
            Object settings = XposedHelpers.callMethod(videoUi,
                    "getSettingsManager");
            Object module = XposedHelpers.callMethod(settings,
                    "getCurrentModuleId");
            return "stage_video".equals(XposedHelpers.callMethod(
                    module, "getModuleId"));
        } catch (Throwable error) {
            return false;
        }
    }

    private boolean isVideo15xLimited() {
        if (!videoVirtualActive.get() || videoUi == null || isStageVideo())
            return false;
        try {
            Object settings = XposedHelpers.callMethod(videoUi,
                    "getSettingsManager");
            ClassLoader loader = videoUi.getClass().getClassLoader();
            if ("8/120".equals(videoFormatKey(settings, loader))) return true;
            Class<?> keys = XposedHelpers.findClass(
                    "com.android.camera.featureconfig.configuration.database.ISettingKeys",
                    loader);
            Object stableKey = XposedHelpers.getStaticObjectField(keys,
                    "KEY_VIDEO_STABLE");
            if (Boolean.TRUE.equals(XposedHelpers.callMethod(settings,
                    "isSupportSetting", stableKey))) {
                String stable = String.valueOf(XposedHelpers.callMethod(settings,
                        "getSettingValueFromKey", stableKey,
                        (Object) new Class[0]));
                if ("1".equals(stable) || "3".equals(stable)) return true;
            }
            for (String name : new String[] {"KEY_VIDEO_SUPER_EIS",
                    "KEY_VIDEO_SUPER_EIS_PRO", "KEY_VIDEO_SUPER_EIS_HORIZON"}) {
                Object key = XposedHelpers.getStaticObjectField(keys, name);
                if (!Boolean.TRUE.equals(XposedHelpers.callMethod(settings,
                        "isSupportSetting", key))) continue;
                Object value = XposedHelpers.callMethod(settings,
                        "getSettingValueFromKey", key, (Object) new Class[0]);
                if ("1".equals(String.valueOf(value))
                        || Boolean.TRUE.equals(value)) return true;
            }
            return false;
        } catch (Throwable error) {
            return false;
        }
    }

    private void repair4K120RecordingRuler(Object ruler) {
        if (!virtualActive.get() || !nativeZoomRequested.get()
                || !isVideo15xLimited()) return;
        try {
            if (!Boolean.TRUE.equals(XposedHelpers.getObjectField(
                    ruler, "mIsRecording"))) return;
            float minimum = ((Number) XposedHelpers.getObjectField(
                    ruler, "miniZoom")).floatValue();
            float maximum = ((Number) XposedHelpers.getObjectField(
                    ruler, "maxZoom")).floatValue();
            if (minimum < 190f || minimum > 210f
                    || maximum < 790f || maximum > 810f) return;
            float previousMaximum = ((Number) XposedHelpers.getObjectField(
                    ruler, "maxZoomInRecording")).floatValue();
            XposedHelpers.setObjectField(ruler, "miniZoomInRecording", minimum);
            XposedHelpers.setObjectField(ruler, "maxZoomInRecording", maximum);
            float minimumAngle = ((Number) XposedHelpers.callMethod(
                    ruler, "zoomToAngle", minimum)).floatValue();
            float maximumAngle = ((Number) XposedHelpers.callMethod(
                    ruler, "zoomToAngle", maximum)).floatValue();
            XposedHelpers.setObjectField(ruler,
                    "minAngleInRecording", minimumAngle);
            XposedHelpers.setObjectField(ruler,
                    "maxAngleInRecording", maximumAngle);
            if (Math.abs(previousMaximum - maximum) > 1f
                    && recordingRulerRepairLogs.getAndIncrement() < 5)
                XposedBridge.log("PD2405ExtTele: 4K120 recording ruler corrected "
                        + previousMaximum + " -> " + maximum + " mm");
        } catch (Throwable error) {
            XposedBridge.log("PD2405ExtTele: recording ruler repair skipped: "
                    + error);
        }
    }

    private boolean isAdvancedPhoto() {
        if (!virtualActive.get() || videoVirtualActive.get() || photoUi == null) return false;
        try {
            Object settings = XposedHelpers.callMethod(photoUi, "getSettingsManager");
            Object module = XposedHelpers.callMethod(settings, "getCurrentModuleId");
            return "advanced".equals(XposedHelpers.callMethod(module, "getModuleId"));
        } catch (Throwable error) {
            return false;
        }
    }

    private boolean isPhotoMacroActive() {
        if (!virtualActive.get() || videoVirtualActive.get() || photoUi == null
                || !"photo".equals(currentPhotoModule())) return false;
        try {
            Object settings = XposedHelpers.callMethod(photoUi, "getSettingsManager");
            Class<?> keys = XposedHelpers.findClass(
                    "com.android.camera.featureconfig.configuration.database.ISettingKeys",
                    photoUi.getClass().getClassLoader());
            Object key = XposedHelpers.getStaticObjectField(keys,
                    "KEY_FOCUS_MICRO_FEATURE");
            return Boolean.TRUE.equals(XposedHelpers.callMethod(settings,
                    "getSettingBooleanValueWithCheck", key));
        } catch (Throwable error) {
            return false;
        }
    }

    /** Accept stock 1080p/2.8K/4K formats; the zoom route is checked separately. */
    private boolean isSafeVideoSetting(Object settings, ClassLoader loader) {
        try {
            if (isStageVideo()) return true;
            Class<?> keys = XposedHelpers.findClass(
                    "com.android.camera.featureconfig.configuration.database.ISettingKeys", loader);
            Object quality = XposedHelpers.getStaticObjectField(keys,
                    "KEY_VIDEO_QUALITY_BACK_VALUE");
            Object fps = XposedHelpers.getStaticObjectField(keys, "KEY_VIDEO_FRAME_RATE");
            String qualityValue = String.valueOf(XposedHelpers.callMethod(settings,
                    "getSettingValueFromKey", quality, (Object) new Class[0]));
            String fpsValue = String.valueOf(XposedHelpers.callMethod(settings,
                    "getSettingValueFromKey", fps, (Object) new Class[0]));
            XposedBridge.log("PD2405ExtTele: video settings quality=" + qualityValue
                    + " fps=" + fpsValue);
            int frameRate = Integer.parseInt(fpsValue);
            return ("6".equals(qualityValue) || "7".equals(qualityValue)
                    || "8".equals(qualityValue))
                    && frameRate >= 24 && frameRate <= 120;
        } catch (Throwable error) {
            XposedBridge.log("PD2405ExtTele: video settings unavailable: " + error);
            return false;
        }
    }

    private void showVirtualVideoPanel(Context context) {
        if (!virtualActive.get() || !videoVirtualActive.get()
                || videoUi == null || !isVideo(videoUi)) return;
        try {
            Object settings = XposedHelpers.callMethod(videoUi, "getSettingsManager");
            Object cameraId = XposedHelpers.callMethod(settings, "getCurrentCameraId");
            if (Boolean.TRUE.equals(XposedHelpers.callMethod(cameraId, "isFront"))
                    || !isSafeVideoSetting(settings, context.getClassLoader())) {
                closeVirtualPanel();
                Toast.makeText(context, "僅支援後鏡頭 1080p／4K 的可用長焦規格",
                        Toast.LENGTH_SHORT).show();
                return;
            }
            if (nativeZoomShown.get()) return;
            Object controller = zoomController.get();
            if (controller == null) throw new IllegalStateException("zoom controller missing");
            Object manager = XposedHelpers.callMethod(settings, "getZoomManager");
            Object original = XposedHelpers.getObjectField(manager, "mCurZoomConfig");
            if (original == null) throw new IllegalStateException("stock video zoom missing");
            if (makeFocalZoomConfig(original, context.getClassLoader()) == null) {
                activateSplitVideoTele(context, settings, original);
                return;
            }
            nativeZoomRequested.set(true);
            selectedFocalStep.set(0);
            XposedHelpers.callMethod(controller, "initZoomConfig", true);
            nativeZoomShown.set(true);
            activeVideoFormat = videoFormatKey(settings, context.getClassLoader());
            activeVideoRoute = 1;
            activeVideo15xLimited = isVideo15xLimited();
            panel.hide();
            previewRotationObserved.set(false);
            focalConversionObserved.set(false);
            inverseFocalConversionObserved.set(false);
            applyZoom(context, 0);
            XposedBridge.log("PD2405ExtTele: stock video focal rail activated");
        } catch (Throwable error) {
            XposedBridge.log("PD2405ExtTele: video zoom unavailable: " + error);
            closeVirtualPanel();
            Toast.makeText(context, "此錄影設定無法安全啟用長焦增距",
                    Toast.LENGTH_SHORT).show();
        }
    }

    private void activateSplitVideoTele(Context context, Object settings,
                                        Object original) {
        try {
            Map<?, ?> ranges = (Map<?, ?>) XposedHelpers.getObjectField(original,
                    "cameraTypeZoomRange");
            Map<?, ?> scales = (Map<?, ?>) XposedHelpers.getObjectField(original,
                    "cameraTypeZoomScale");
            Object rangeValue = ranges.get("Tele3P5x");
            Object scaleValue = scales.get("Tele3P5x");
            if (!(rangeValue instanceof Range) || !(scaleValue instanceof Number)) {
                throw new IllegalStateException("stock Tele3P5x route unavailable");
            }
            Range<?> range = (Range<?>) rangeValue;
            float scale = ((Number) scaleValue).floatValue();
            if (scale < 3.65f
                    || ((Number) range.getLower()).floatValue() > 1.01f
                    || ((Number) range.getUpper()).floatValue() * scale < 20f) {
                throw new IllegalStateException("stock Tele3P5x range unusable: "
                        + range + " scale=" + scale);
            }
            Object currentId = XposedHelpers.callMethod(settings,
                    "getCurrentCameraId");
            String cameraType = String.valueOf(XposedHelpers.getObjectField(
                    currentId, "mCameraType"));
            if (!"Tele3P5x".equals(cameraType)) {
                int attempt = videoTeleSwitchTries.incrementAndGet();
                if (attempt > 9) throw new IllegalStateException(
                        "stock video tele switch failed from " + cameraType);
                // A camera reopen can take over a second at 4K120. Repeated
                // clicks every 400 ms may cancel the switch, so poll first.
                if (attempt == 1 || attempt == 7) {
                    Object controller = zoomController.get();
                    if (controller == null) throw new IllegalStateException(
                            "zoom controller missing");
                    Object group = XposedHelpers.getObjectField(controller,
                            "mZoomCircleGroupManager");
                    Object[] buttons = (Object[]) XposedHelpers.getObjectField(group,
                            "mZoomCircleButtons");
                    View teleButton = null;
                    if (buttons != null) for (Object button : buttons) {
                        if (!(button instanceof View)) continue;
                        Object point = XposedHelpers.callMethod(button, "getZoomPoint");
                        if (point != null && "Tele3P5x".equals(
                                XposedHelpers.getObjectField(point, "cameraType"))) {
                            teleButton = (View) button;
                            break;
                        }
                    }
                    if (teleButton == null || !teleButton.isEnabled()) {
                        throw new IllegalStateException("stock video tele button unavailable");
                    }
                    boolean clicked = teleButton.performClick();
                    XposedBridge.log("PD2405ExtTele: clicked stock video Tele3P5x"
                            + " from " + cameraType + " attempt=" + attempt
                            + " accepted=" + clicked);
                } else {
                    XposedBridge.log("PD2405ExtTele: waiting for stock video Tele3P5x"
                            + " attempt=" + attempt + " current=" + cameraType);
                }
                Activity activity = ExternalTelePanel.findActivity(context);
                if (activity == null) throw new IllegalStateException(
                        "camera Activity unavailable");
                activity.getWindow().getDecorView().postDelayed(
                        () -> showVirtualVideoPanel(context), 350);
                return;
            }
            videoTeleSwitchTries.set(0);
            splitVideoStockTeleActive.set(true);
            Object controller = zoomController.get();
            if (controller == null) throw new IllegalStateException(
                    "zoom controller missing after stock tele switch");
            synchronized (nativeZoomConfigs) { nativeZoomConfigs.clear(); }
            if (makeFocalZoomConfig(original, context.getClassLoader()) == null) {
                throw new IllegalStateException("Tele3P5x focal rail unavailable");
            }
            nativeZoomRequested.set(true);
            selectedFocalStep.set(0);
            XposedHelpers.callMethod(controller, "initZoomConfig", true);
            nativeZoomShown.set(true);
            activeVideoFormat = videoFormatKey(settings, context.getClassLoader());
            activeVideoRoute = 2;
            activeVideo15xLimited = isVideo15xLimited();
            panel.hide();
            previewRotationObserved.set(false);
            focalConversionObserved.set(false);
            inverseFocalConversionObserved.set(false);
            if (nativeOisSignal != null) nativeOisSignal.refresh();
            XposedBridge.log("PD2405ExtTele: split video external tele active"
                    + " with 200/400/600/800-mm focal rail and 1080-mm ruler");
        } catch (Throwable error) {
            XposedBridge.log("PD2405ExtTele: split video tele unavailable: " + error);
            closeVirtualPanel();
            Toast.makeText(context, "此錄影設定的原廠長焦切換未就緒",
                    Toast.LENGTH_SHORT).show();
        }
    }

    private void showVirtualPanel(Context context) {
        if (!virtualActive.get() || photoUi == null || !isPhoto(photoUi)) return;
        Object settings;
        try {
            settings = XposedHelpers.callMethod(photoUi, "getSettingsManager");
            Object cameraId = XposedHelpers.callMethod(settings, "getCurrentCameraId");
            if (Boolean.TRUE.equals(XposedHelpers.callMethod(cameraId, "isFront"))) return;
        } catch (Throwable error) {
            XposedBridge.log(error);
            return;
        }
        Activity activity = ExternalTelePanel.findActivity(context);
        if (activity == null) {
            XposedBridge.log("PD2405ExtTele: camera Activity unavailable for panel");
            return;
        }
        if (isHighResolutionActive()) {
            activateHighResolutionTele();
            View entry = highResolutionEntry.get();
            if (entry != null) updateHighResolutionEntry(entry);
            return;
        }
        if (isTwoPointStill()) {
            if (!selectTwoPointStillTele(context, activity)) return;
        }
        if (isAdvancedPhoto()) {
            try {
                Object currentId = XposedHelpers.callMethod(settings, "getCurrentCameraId");
                String cameraType = String.valueOf(XposedHelpers.getObjectField(
                        currentId, "mCameraType"));
                if (!"Tele3P5x".equals(cameraType)) {
                    int attempt = proTeleSwitchTries.incrementAndGet();
                    if (attempt > 3) {
                        closeVirtualPanel();
                        Toast.makeText(context, "專業模式無法切換到原廠長焦鏡頭",
                                Toast.LENGTH_SHORT).show();
                        XposedBridge.log("PD2405ExtTele: pro tele switch failed; last="
                                + cameraType);
                        return;
                    }
                    Object controller = zoomController.get();
                    if (controller == null) throw new IllegalStateException("zoom controller missing");
                    Object group = XposedHelpers.getObjectField(
                            controller, "mZoomCircleGroupManager");
                    Object[] buttons = (Object[]) XposedHelpers.getObjectField(
                            group, "mZoomCircleButtons");
                    View teleButton = null;
                    if (buttons != null) for (Object button : buttons) {
                        if (!(button instanceof View)) continue;
                        Object point = XposedHelpers.callMethod(button, "getZoomPoint");
                        if (point != null && "Tele3P5x".equals(
                                XposedHelpers.getObjectField(point, "cameraType"))) {
                            teleButton = (View) button;
                            break;
                        }
                    }
                    if (teleButton == null || !teleButton.isEnabled()) {
                        throw new IllegalStateException("stock pro tele button unavailable");
                    }
                    boolean clicked = teleButton.performClick();
                    XposedBridge.log("PD2405ExtTele: pro photo clicked stock Tele3P5x"
                            + " from " + cameraType + " attempt=" + attempt
                            + " accepted=" + clicked);
                    activity.getWindow().getDecorView().postDelayed(
                            () -> showVirtualPanel(context), 400);
                    return;
                }
                proTeleSwitchTries.set(0);
                XposedBridge.log("PD2405ExtTele: pro photo stock Tele3P5x selected");
                Object controller = zoomController.get();
                if (controller == null) throw new IllegalStateException(
                        "pro zoom controller missing");
                Object zoomManager = XposedHelpers.callMethod(settings,
                        "getZoomManager");
                Object original = XposedHelpers.getObjectField(zoomManager,
                        "mCurZoomConfig");
                if (original == null || makeFocalZoomConfig(original,
                        context.getClassLoader()) == null) {
                    throw new IllegalStateException("pro Tele3P5x 3.7-10x range missing");
                }
                proStockTeleActive.set(true);
                nativeZoomRequested.set(true);
                selectedFocalStep.set(0);
                XposedHelpers.callMethod(controller, "initZoomConfig", true);
                nativeZoomShown.set(true);
                panel.hide();
                previewRotationObserved.set(false);
                captureRotationObserved.set(false);
                if (nativeOisSignal != null) nativeOisSignal.refresh();
                XposedBridge.log("PD2405ExtTele: Pro photo external tele active"
                        + " with 200/540-mm focal rail");
                return;
            } catch (Throwable error) {
                closeVirtualPanel();
                XposedBridge.log("PD2405ExtTele: pro tele switch unavailable: " + error);
                Toast.makeText(context, "無法確認專業模式長焦鏡頭",
                        Toast.LENGTH_SHORT).show();
                return;
            }
        }
        if (!isAdvancedPhoto() && !isTwoPointStill()
                && !isPhotoMacroActive()) try {
            Class<?> keys = XposedHelpers.findClass(
                    "com.android.camera.featureconfig.configuration.database.ISettingKeys",
                    context.getClassLoader());
            Object macroKey = XposedHelpers.getStaticObjectField(keys, "KEY_FOCUS_MICRO_FEATURE");
            if (Boolean.TRUE.equals(XposedHelpers.callMethod(settings,
                    "getSettingBooleanValueWithCheck", macroKey))) {
                if (macroChangeAttempted.compareAndSet(false, true)) {
                    macroRecoveryTries.set(6);
                    XposedHelpers.callMethod(settings, "changeSetting", macroKey, "0");
                    XposedBridge.log("PD2405ExtTele: turned off stock super macro before tele");
                }
                if (macroRecoveryTries.get() <= 0) {
                    closeVirtualPanel();
                    Toast.makeText(context, "請先關閉超微距再開長焦增距",
                            Toast.LENGTH_SHORT).show();
                    return;
                }
                activity.getWindow().getDecorView().postDelayed(
                        () -> retryAfterMacro(context), 250);
                return;
            }
        } catch (Throwable error) {
            XposedBridge.log("PD2405ExtTele: macro state not safely resolved: " + error);
            closeVirtualPanel();
            Toast.makeText(context, "請先關閉超微距再開長焦增距", Toast.LENGTH_SHORT).show();
            return;
        }
        if (nativeZoomShown.get()) return;
        Object controller = zoomController.get();
        if (controller != null) {
            try {
                Object manager = XposedHelpers.callMethod(settings, "getZoomManager");
                Object original = XposedHelpers.getObjectField(manager, "mCurZoomConfig");
                if (original != null && makeFocalZoomConfig(original,
                        context.getClassLoader()) != null) {
                    nativeZoomRequested.set(true);
                    activePhotoMacro = "photo".equals(currentPhotoModule())
                            ? isPhotoMacroActive() : null;
                    selectedFocalStep.set(0);
                    XposedHelpers.callMethod(controller, "initZoomConfig", true);
                    nativeZoomShown.set(true);
                    if (isTwoPointStill()) setTwoPointSideButtonSelected(true);
                    panel.hide();
                    previewRotationObserved.set(false);
                    pipRotationObserved.set(false);
                    captureRotationObserved.set(false);
                    if (!isTwoPointStill()) applyZoom(context, 0);
                    if (nativeOisSignal != null) nativeOisSignal.refresh();
                    macroRecoveryTries.set(0);
                    macroChangeAttempted.set(false);
                    XposedBridge.log("PD2405ExtTele: stock focal zoom rail activated");
                    return;
                }
            } catch (Throwable error) {
                nativeZoomRequested.set(false);
                nativeZoomShown.set(false);
                XposedBridge.log("PD2405ExtTele: stock zoom rail unavailable: " + error);
                try { XposedHelpers.callMethod(controller, "initZoomConfig", true); }
                catch (Throwable restoreError) { XposedBridge.log(restoreError); }
            }
        }
        if (macroRecoveryTries.get() > 0) {
            activity.getWindow().getDecorView().postDelayed(
                    () -> retryAfterMacro(context), 250);
            return;
        }
        // The legacy overlay collides with the stock mode bar (especially
        // with super macro's 20x cap). Never present it as a fallback.
        closeVirtualPanel();
        Toast.makeText(context, "長焦變焦尚未就緒，請稍後重試", Toast.LENGTH_SHORT).show();
    }

    private boolean selectTwoPointStillTele(Context context, Activity activity) {
        try {
            Object settings = XposedHelpers.callMethod(photoUi, "getSettingsManager");
            Object currentId = XposedHelpers.callMethod(settings, "getCurrentCameraId");
            String currentType = String.valueOf(XposedHelpers.getObjectField(
                    currentId, "mCameraType"));
            Object controller = zoomController.get();
            if (controller == null) throw new IllegalStateException("zoom controller missing");
            Object zoomContext = XposedHelpers.getObjectField(controller, "mContext");
            float value = ((Number) XposedHelpers.callMethod(zoomContext,
                    "getCurrentValue")).floatValue();
            String expectedType = "portrait".equals(currentPhotoModule())
                    ? "BokehTele3" : "Tele3P5x";
            if (twoPointTeleClickPending.get()) {
                if (!expectedType.equals(currentType) || value < 3.65f || value > 3.75f) {
                    throw new IllegalStateException("stock 3.7x tele not selected: "
                            + currentType + " zoom=" + value);
                }
                twoPointTeleClickPending.set(false);
                XposedBridge.log("PD2405ExtTele: verified still tele "
                        + currentPhotoModule() + " type=" + currentType
                        + " zoom=" + value);
                return true;
            }
            Object group = XposedHelpers.getObjectField(controller,
                    "mZoomCircleGroupManager");
            Object[] buttons = (Object[]) XposedHelpers.getObjectField(group,
                    "mZoomCircleButtons");
            View teleButton = null;
            if (buttons != null) for (Object button : buttons) {
                if (!(button instanceof View)) continue;
                Object point = XposedHelpers.callMethod(button, "getZoomPoint");
                if (point != null && "3.7".equals(XposedHelpers.getObjectField(point,
                        "title")) && expectedType.equals(XposedHelpers.getObjectField(
                        point, "cameraType"))) {
                    teleButton = (View) button;
                    break;
                }
            }
            if (teleButton == null || !teleButton.isEnabled()) {
                throw new IllegalStateException("stock 3.7x point unavailable");
            }
            twoPointTeleClickPending.set(true);
            boolean accepted = teleButton.performClick();
            XposedBridge.log("PD2405ExtTele: clicked stock still 3.7x "
                    + currentPhotoModule() + " accepted=" + accepted);
            if (!accepted) throw new IllegalStateException("stock click rejected");
            activity.getWindow().getDecorView().postDelayed(
                    () -> showVirtualPanel(context), 500);
            return false;
        } catch (Throwable error) {
            XposedBridge.log("PD2405ExtTele: two-point tele unavailable: " + error);
            closeVirtualPanel();
            Toast.makeText(context, "此模式無法安全切到長焦鏡頭",
                    Toast.LENGTH_SHORT).show();
            return false;
        }
    }

    private void setTwoPointSideButtonSelected(boolean selected) {
        View button = twoPointSideButton.get();
        if (button == null) return;
        int imageId = button.getResources().getIdentifier(
                "top_side_item_image", "id", CAMERA);
        View icon = imageId == 0 ? null : button.findViewById(imageId);
        if (icon instanceof ImageView) {
            if (selected) ((ImageView) icon).setColorFilter(0xFFFFC645);
            else ((ImageView) icon).clearColorFilter();
        }
    }

    private void retryAfterMacro(Context context) {
        if (!virtualActive.get()) return;
        if (macroRecoveryTries.decrementAndGet() < 0) {
            closeVirtualPanel();
            Toast.makeText(context, "請先關閉超微距再開長焦增距",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        showVirtualPanel(context);
    }

    private void applyZoom(Context context, int step) {
        if (!virtualActive.get()) return;
        if (videoVirtualActive.get()) {
            if (videoUi == null || !isVideo(videoUi)) return;
        } else if (photoUi == null || !isPhoto(photoUi)) return;
        String[] ratios = videoVirtualActive.get()
                ? new String[]{"3.7", "7.4", "11.1", "14.8"}
                : isAdvancedPhoto() ? new String[]{"3.7", "10"}
                : isPhotoMacroActive() ? new String[]{"3.7", "7.4", "10"}
                : new String[]{"3.7", "7.4", "14.8", "29.6"};
        if (step < 0 || step >= ratios.length) return;
        Object controller = zoomController.get();
        if (controller == null) {
            Toast.makeText(context, "相機變焦尚未就緒", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            if (!Boolean.TRUE.equals(XposedHelpers.getObjectField(
                    controller, "mIsInflateFinish"))) {
                Toast.makeText(context, "相機變焦尚未就緒", Toast.LENGTH_SHORT).show();
                return;
            }
            if ((splitVideoStockTeleActive.get() && videoVirtualActive.get())
                    || (proStockTeleActive.get() && isAdvancedPhoto())) {
                splitVideoButtonGlobalZoom = Float.parseFloat(ratios[step]);
                splitVideoButtonAt = SystemClock.uptimeMillis();
            }
            XposedHelpers.callMethod(controller, "onZoomForOuter", ratios[step], false);
            if (!nativeZoomShown.get()) panel.select(step);
            XposedBridge.log("PD2405ExtTele: stock zoom requested " + ratios[step] + "x");
        } catch (Throwable error) {
            XposedBridge.log(error);
            Toast.makeText(context, "變焦失敗，已保留原廠相機", Toast.LENGTH_SHORT).show();
        }
    }

    private void restorePhotoTeleAfterMacroChange() {
        if (!virtualActive.get() || videoVirtualActive.get()
                || !nativeZoomRequested.get() || !nativeZoomShown.get()
                || !"photo".equals(currentPhotoModule())) return;
        boolean macro = isPhotoMacroActive();
        Boolean previous = activePhotoMacro;
        if (previous == null) {
            activePhotoMacro = macro;
            return;
        }
        if (previous == macro) return;
        activePhotoMacro = macro;
        int generation = photoMacroZoomGeneration.incrementAndGet();
        try {
            Object ui = photoUi;
            Activity activity = ExternalTelePanel.findActivity(
                    (Context) XposedHelpers.callMethod(ui, "getContext"));
            if (activity == null) return;
            View decor = activity.getWindow().getDecorView();
            decor.postDelayed(() -> restorePhotoTeleZoom(generation, macro), 250);
            decor.postDelayed(() -> restorePhotoTeleZoom(generation, macro), 700);
            XposedBridge.log("PD2405ExtTele: photo macro " + previous + " -> "
                    + macro + "; checking tele zoom after stock rebuild");
        } catch (Throwable error) {
            XposedBridge.log("PD2405ExtTele: photo macro zoom watch skipped: " + error);
        }
    }

    private void restorePhotoTeleZoom(int generation, boolean macro) {
        if (generation != photoMacroZoomGeneration.get() || !virtualActive.get()
                || videoVirtualActive.get() || !nativeZoomRequested.get()
                || !nativeZoomShown.get() || !cameraResumed
                || !"photo".equals(currentPhotoModule())
                || isPhotoMacroActive() != macro) return;
        try {
            Object controller = zoomController.get();
            if (controller == null || !Boolean.TRUE.equals(XposedHelpers.getObjectField(
                    controller, "mIsInflateFinish"))) return;
            Object zoomContext = XposedHelpers.getObjectField(controller, "mContext");
            float current = ((Number) XposedHelpers.callMethod(
                    zoomContext, "getCurrentValue")).floatValue();
            if (!Float.isFinite(current) || current >= 3.65f) return;
            applyZoom((Context) XposedHelpers.callMethod(photoUi, "getContext"), 0);
            XposedBridge.log("PD2405ExtTele: photo macro transition restored tele"
                    + " from " + current + "x");
        } catch (Throwable error) {
            XposedBridge.log("PD2405ExtTele: photo macro tele recovery skipped: " + error);
        }
    }

    private void closeVirtualPanel() {
        highResolutionTeleRequestGeneration.incrementAndGet();
        boolean wasHighResolutionTele = highResolutionTeleActive.get();
        highResolutionTeleActive.set(false);
        updateHighResolutionSideIcon();
        if (wasHighResolutionTele) restoreHighResolutionFocalButtons();
        photoMacroZoomGeneration.incrementAndGet();
        activePhotoMacro = null;
        setTwoPointSideButtonSelected(false);
        restoreStockOis();
        oisGainMode.set(0);
        virtualActive.set(false);
        if (nativeOisSignal != null) nativeOisSignal.refresh();
        macroRecoveryTries.set(0);
        proTeleSwitchTries.set(0);
        proStockTeleActive.set(false);
        videoTeleSwitchTries.set(0);
        videoZoomConfigGeneration.incrementAndGet();
        activeVideoFormat = null;
        activeVideoRoute = 0;
        activeVideo15xLimited = false;
        splitVideoStockTeleActive.set(false);
        splitRoutingDiagnostics.set(0);
        splitRulerDiagnostics.set(0);
        splitVideoRulerGlobalZoom = Float.NaN;
        splitVideoRulerAt = 0L;
        splitVideoButtonGlobalZoom = Float.NaN;
        splitVideoButtonAt = 0L;
        twoPointTeleClickPending.set(false);
        macroChangeAttempted.set(false);
        previewStabilizer.stop();
        boolean restoreZoom = nativeZoomRequested.getAndSet(false);
        nativeZoomShown.set(false);
        selectedFocalStep.set(0);
        focalConversionObserved.set(false);
        inverseFocalConversionObserved.set(false);
        splitZoomConfigObserved.set(false);
        rulerRepaintSkipObserved.set(false);
        pipGateObserved.set(false);
        panel.hide();
        if (restoreZoom) {
            Object controller = zoomController.get();
            if (controller != null) {
                try { XposedHelpers.callMethod(controller, "initZoomConfig", true); }
                catch (Throwable error) { XposedBridge.log(error); }
            }
        }
        String exitingPhotoModule = currentPhotoModule();
        if ("photo".equals(exitingPhotoModule)
                || "scenery".equals(exitingPhotoModule)
                || "stage_photo".equals(exitingPhotoModule)) {
            Object addition = pipAddition.get();
            if (addition != null) {
                try {
                    float zoom = ((Number) XposedHelpers.callMethod(
                            addition, "getZoomValue")).floatValue();
                    float gate = ((Number) XposedHelpers.callMethod(
                            addition, "getSatPipGate")).floatValue();
                    boolean wasClosed = Boolean.TRUE.equals(XposedHelpers.callMethod(
                            addition, "checkBeenClosed"));
                    XposedHelpers.callMethod(addition, "addOrRemovePipSurfaceIfNeed",
                            zoom >= gate && !wasClosed, false);
                    XposedBridge.log("PD2405ExtTele: restored stock PIP gate "
                            + gate + "x at " + zoom + "x");
                } catch (Throwable error) {
                    XposedBridge.log("PD2405ExtTele: PIP restore skipped: " + error);
                }
            }
        }
        synchronized (previewInputRotations) {
            for (Map.Entry<Object, Integer> entry : previewInputRotations.entrySet()) {
                try {
                    XposedHelpers.callMethod(entry.getKey(), "setRotationClockwise",
                            entry.getValue());
                } catch (Throwable error) {
                    XposedBridge.log(error);
                }
            }
        }
        videoVirtualActive.set(false);
    }

}
