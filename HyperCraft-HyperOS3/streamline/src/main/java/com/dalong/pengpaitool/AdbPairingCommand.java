package com.dalong.pengpaitool;

import android.os.Process;

/** Explicit root app_process entry: use named platform methods, never firmware Binder numbers. */
public final class AdbPairingCommand {
    private AdbPairingCommand() {}

    public static void main(String[] args) {
        try {
            if (Process.myUid() != 0) throw new SecurityException("Root required");
            boolean stop = args.length == 1 && args[0].equals("stop");
            boolean start = args.length == 2 && args[0].equals("start") && args[1].matches("[0-9]{6}");
            boolean port = args.length == 1 && args[0].equals("port");
            if (!stop && !start && !port) throw new IllegalArgumentException("Invalid pairing operation");
            Object binder = Class.forName("android.os.ServiceManager").getMethod("getService", String.class).invoke(null, "adb");
            Object manager = Class.forName("android.debug.IAdbManager$Stub")
                    .getMethod("asInterface", Class.forName("android.os.IBinder")).invoke(null, binder);
            if (manager == null) throw new IllegalStateException("Adb service unavailable");
            Class<?> api = Class.forName("android.debug.IAdbManager");
            if (stop) api.getMethod("disablePairing").invoke(manager);
            else if (start) api.getMethod("enablePairingByQrCode", String.class, String.class).invoke(manager, "HyperCraft", args[1]);
            else {
                Object value = api.getMethod("getAdbWirelessPort").invoke(manager);
                if (!(value instanceof Integer number) || number < 1 || number > 65535)
                    throw new IllegalStateException("Wireless port unavailable");
                System.out.println(value);
            }
        } catch (Exception failure) {
            // Never print the pairing code or any Binder payload.
            System.err.println("Pairing control failed: " + failure.getClass().getSimpleName());
            System.exit(1);
        }
    }
}
