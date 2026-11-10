package io.github.dalong.hyperhome;

import android.app.Application;
import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;

/** The framework connection is used only by the retained About-phone customization. */
public final class HyperHomeApp extends Application {
    private volatile XposedService service;
    private final java.util.Set<Runnable> serviceListeners=new java.util.concurrent.CopyOnWriteArraySet<>();
    @Override public void onCreate() {
        super.onCreate();
        com.dalong.pengpaitool.XpService.init(this);
        XposedServiceHelper.registerListener(new XposedServiceHelper.OnServiceListener() {
            @Override public void onServiceBind(XposedService value) {
                service=value;
                com.dalong.pengpaitool.XpService.accept(value);
                notifyServiceChanged();
            }
            @Override public void onServiceDied(XposedService value) {
                if(service==value) {
                    service=null;
                    com.dalong.pengpaitool.XpService.clear(value);
                    notifyServiceChanged();
                }
            }
        });
    }
    public void addServiceListener(Runnable listener) { serviceListeners.add(listener); }
    public void removeServiceListener(Runnable listener) { serviceListeners.remove(listener); }
    private void notifyServiceChanged() { for(Runnable listener:serviceListeners) listener.run(); }
    public String status() {
        XposedService current=service;
        if(current==null) return "HyperOS 3 · Root 工具独立运行";
        try {
            return "LSPosed API "+current.getApiVersion()+(current.getScope().contains("com.android.settings")
                    ?" · 设备美化已连接":" · 设备美化需勾选系统设置");
        } catch(RuntimeException error) { return "LSPosed 连接暂不可用 · Root 工具独立运行"; }
    }
}
