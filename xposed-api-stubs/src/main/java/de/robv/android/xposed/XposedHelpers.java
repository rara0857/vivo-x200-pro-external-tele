package de.robv.android.xposed;

public final class XposedHelpers {
    public static Class<?> findClass(String name, ClassLoader loader) { return null; }
    public static XC_MethodHook.Unhook findAndHookMethod(String className, ClassLoader loader, String methodName, Object... parameterTypesAndCallback) { return null; }
    public static XC_MethodHook.Unhook findAndHookConstructor(String className, ClassLoader loader, Object... parameterTypesAndCallback) { return null; }
    public static Object newInstance(Class<?> clazz, Object... args) { return null; }
    public static Object callMethod(Object object, String methodName, Object... args) { return null; }
    public static Object callStaticMethod(Class<?> clazz, String methodName, Object... args) { return null; }
    public static Object getObjectField(Object object, String fieldName) { return null; }
    public static void setObjectField(Object object, String fieldName, Object value) { }
    public static void setIntField(Object object, String fieldName, int value) { }
    public static Object getStaticObjectField(Class<?> clazz, String fieldName) { return null; }
}
