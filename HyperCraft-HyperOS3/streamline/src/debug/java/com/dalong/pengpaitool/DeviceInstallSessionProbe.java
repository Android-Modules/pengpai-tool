package com.dalong.pengpaitool;

import android.os.IBinder;

/** Read-only root app_process query for the explicit debug split-install lifecycle fixture. */
public final class DeviceInstallSessionProbe {
    public static void main(String[] args) {
        try {
            if (args.length != 1 || !args[0].matches("[1-9][0-9]{0,9}"))
                throw new IllegalArgumentException("Invalid session ID");
            int id = Integer.parseInt(args[0]);
            IBinder binder = (IBinder) Class.forName("android.os.ServiceManager")
                    .getMethod("getService", String.class).invoke(null, "package");
            Object packages = Class.forName("android.content.pm.IPackageManager$Stub")
                    .getMethod("asInterface", IBinder.class).invoke(null, binder);
            Object installer = Class.forName("android.content.pm.IPackageManager")
                    .getMethod("getPackageInstaller").invoke(packages);
            Object session = Class.forName("android.content.pm.IPackageInstaller")
                    .getMethod("getSessionInfo", int.class).invoke(installer, id);
            System.out.println(session == null ? "ABSENT" : "PRESENT");
        } catch (Throwable error) {
            System.err.println("Session query failed: " + error.getClass().getSimpleName());
            System.exit(1);
        }
    }
}
