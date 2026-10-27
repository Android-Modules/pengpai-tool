package com.dalong.pengpaitool;

import android.os.IBinder;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;

/** Runs under app_process as root, using the device's own AIDL implementation. */
public final class AdbBridge {
    private static Object manager;

    private static Object invoke(String name, Class<?>[] types, Object... args) throws Exception {
        Method method = Class.forName("android.debug.IAdbManager").getMethod(name, types);
        return method.invoke(manager, args);
    }

    private static Object devices() throws Exception {
        return invoke("getPairedDevices", new Class<?>[0]);
    }

    private static String field(Object obj, String name) {
        try {
            Field f = obj.getClass().getField(name);
            return String.valueOf(f.get(obj));
        } catch (Exception e) { return "未知"; }
    }

    private static String safe(String value) {
        return value.replace('|', ' ').replace('\n', ' ').replace('\r', ' ');
    }

    private static void printDevice(Object entry) {
        Object device = entry;
        String fingerprint = field(entry, "guid");
        try {
            device = entry.getClass().getField("device").get(entry);
            // keyFingerprint is the Map key (the full public key) on Android 16.
            // PairDevice.guid holds the fingerprint accepted by unpairDevice.
            fingerprint = field(device, "guid");
        } catch (ReflectiveOperationException ignored) {
            // Older Android versions return PairDevice values in a Map.
        }
        System.out.println("DEVICE|" + safe(field(device, "name")) + "|"
                + safe(fingerprint) + "|" + safe(field(device, "connected")));
    }

    private static void list(Object result) {
        if (result instanceof Map) {
            // Map keys can contain public keys: never print them.
            for (Object value : ((Map<?, ?>) result).values()) printDevice(value);
        } else if (result != null && result.getClass().isArray()) {
            for (int i = 0; i < Array.getLength(result); i++) printDevice(Array.get(result, i));
        } else {
            throw new IllegalStateException("Unsupported paired-device response");
        }
    }

    public static void main(String[] args) {
        try {
            IBinder binder = (IBinder) Class.forName("android.os.ServiceManager")
                    .getMethod("getService", String.class).invoke(null, "adb");
            if (binder == null) throw new IllegalStateException("ADB service unavailable");
            manager = Class.forName("android.debug.IAdbManager$Stub")
                    .getMethod("asInterface", IBinder.class).invoke(null, binder);
            if (args.length == 1 && args[0].equals("list")) {
                Object result = devices();
                list(result);
                System.out.println("OK|adb|授权列表已读取");
            } else if (args.length == 2 && args[0].equals("unpair")) {
                if (!args[1].matches("[0-9A-Fa-f:]{16,128}"))
                    throw new IllegalArgumentException("Invalid fingerprint");
                invoke("unpairDevice", new Class<?>[]{String.class}, args[1]);
                System.out.println("OK|adb|系统已接收解除授权请求，请刷新确认设备已移除");
            } else if (args.length == 1 && args[0].equals("clear")) {
                invoke("clearDebuggingKeys", new Class<?>[0]);
                System.out.println("OK|adb|系统已接收撤销全部授权请求，请刷新确认");
            } else throw new IllegalArgumentException("Unknown operation");
        } catch (Throwable e) {
            // Do not print exception messages: vendors may include key material.
            System.out.println("ERR|adb|系统授权接口不可用或操作失败，请使用系统配对页");
            System.exit(1);
        }
    }
}
