package de.robv.android.xposed;

public final class XposedBridge {
    public static void log(String message) {}
    public static void log(Throwable throwable) {}
    public static XC_MethodHook.Unhook hookMethod(java.lang.reflect.Member method, XC_MethodHook callback) { return null; }
}
