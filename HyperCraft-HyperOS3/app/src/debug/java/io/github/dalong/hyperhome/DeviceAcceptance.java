package io.github.dalong.hyperhome;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Bundle;
import com.dalong.pengpaitool.Access;
import com.dalong.pengpaitool.RouteCatalog;
import com.dalong.pengpaitool.StreamlineActivity;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/** Debug-only checks for the retained tools; never included in release builds. */
public final class DeviceAcceptance extends Instrumentation {
    private Bundle arguments;
    @Override public void onCreate(Bundle input) { super.onCreate(input); arguments=input; start(); }
    @Override public void onStart() {
        Bundle result=new Bundle();
        try {
            HyperHomeApp app=(HyperHomeApp)getTargetContext().getApplicationContext();
            switch(arguments.getString("action","status")) {
                case "status" -> result.putString("data",new JSONObject().put("module",app.status()).toString());
                case "search" -> result.putString("data",search(app).toString());
                case "tools", "tools-auto" -> result.putString("data",tools(app).toString());
                case "tools-root" -> {
                    if(!com.dalong.pengpaitool.DeviceToolAuthorization.prepare(app)) throw new IllegalStateException("Root backend authorization failed");
                    result.putString("data",tools(app).toString());
                }
                case "tools-write" -> result.putString("data",com.dalong.pengpaitool.DeviceToolWrites.run(app,arguments.getString("plan","inventory")));
                case "tools-render" -> result.putString("data",renderTools(app).toString());
                case "tools-process" -> result.putString("data",com.dalong.pengpaitool.DeviceRootLifecycle.run(app));
                default -> throw new IllegalArgumentException("Unknown action");
            }
            finish(Activity.RESULT_OK,result);
        } catch(Throwable error) {
            android.util.Log.e("HyperCraftAcceptance", "Device acceptance failed", error);
            result.putString("error",error.getClass().getSimpleName()+": "+error.getMessage());
            sendStatus(Activity.RESULT_CANCELED,result);
            finish(Activity.RESULT_CANCELED,result);
        }
    }
    @Override public android.app.UiAutomation getUiAutomation() {
        return super.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
    }
    /** Render-only fallback when another installed module owns UiAutomation. No control is changed. */
    private JSONObject renderTools(HyperHomeApp app) throws Exception {
        if (!com.dalong.pengpaitool.DeviceToolAuthorization.prepare(app)) throw new IllegalStateException("Root unavailable");
        if (!com.dalong.pengpaitool.DeviceToolAuthorization.foreground()) throw new IllegalStateException("Foreground launch failed");
        Thread.sleep(500);
        java.util.List<String> routes = new ArrayList<>();
        String requested = arguments.getString("route", "");
        if (!requested.isEmpty()) routes.add(RouteCatalog.validRoute(requested));
        else {
            for (var group : RouteCatalog.groups()) routes.add(group.getRoute());
            for (var entry : RouteCatalog.entries()) routes.add(entry.getRoute());
        }
        JSONArray pages = new JSONArray();
        for (String route : routes) {
            Activity activity = startActivitySync(new android.content.Intent(app, StreamlineActivity.class)
                    .putExtra("route", route).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
            try {
                Thread.sleep(Math.max(500,Math.min(15000,Integer.parseInt(arguments.getString("settleMs","1600")))));
                int[] size = new int[2];
                runOnMainSync(() -> { size[0] = activity.getWindow().getDecorView().getWidth(); size[1] = activity.getWindow().getDecorView().getHeight(); });
                if (size[0] <= 0 || size[1] <= 0) throw new IllegalStateException("Empty page " + route);
                JSONObject page = new JSONObject().put("route", route).put("width",size[0]).put("height",size[1]);
                if (!"wifi".equals(route)) {
                    android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(size[0],size[1],android.graphics.Bitmap.Config.ARGB_8888);
                    java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
                    int[] copied = {-1};
                    runOnMainSync(() -> android.view.PixelCopy.request(activity.getWindow(),bitmap,
                            result -> { copied[0]=result; latch.countDown(); },new android.os.Handler(android.os.Looper.getMainLooper())));
                    if (!latch.await(5, java.util.concurrent.TimeUnit.SECONDS) || copied[0] != android.view.PixelCopy.SUCCESS)
                        throw new IllegalStateException("PixelCopy " + copied[0] + " for " + route);
                    java.io.File file = new java.io.File(app.getExternalFilesDir(null),"render-"+route+".png");
                    try (var output = new java.io.FileOutputStream(file)) { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,output); }
                    bitmap.recycle();page.put("capture",file.getAbsolutePath());
                }
                pages.put(page);
                Bundle progress = new Bundle(); progress.putString("rendered",route); sendStatus(1,progress);
            } finally { runOnMainSync(activity::finish); }
        }
        return new JSONObject().put("check","render-only; no accessibility assertions").put("visited",pages.length()).put("pages",pages);
    }
    /** Navigate real pages; tools-auto checks remembered authorization without pressing a gate. */
    private JSONObject tools(HyperHomeApp app) throws Exception {
        boolean authorized = "tools-root".equals(arguments.getString("action")) || "tools-auto".equals(arguments.getString("action"));
        if (!authorized && (Access.INSTANCE.getRoot() || Access.INSTANCE.getModule()))
            throw new IllegalStateException("Read-only tools acceptance requires a fresh, unauthorized app process");
        String requested = arguments.getString("route", "");
        List<String> routes = new ArrayList<>();
        if (requested.isEmpty()) {
            for (RouteCatalog.Group group : RouteCatalog.groups()) routes.add(group.getRoute());
            for (RouteCatalog.Entry entry : RouteCatalog.entries()) routes.add(entry.getRoute());
        } else {
            if (!requested.equals(RouteCatalog.validRoute(requested)))
                throw new IllegalArgumentException("Unknown catalog route: " + requested);
            routes.add(requested);
        }
        Map<String, Map<String, ?>> original = preferenceValues(app);
        // Establish this application's foreground launch permission using the fixed launcher only.
        try (android.os.ParcelFileDescriptor command = getUiAutomation().executeShellCommand(
                "am start -W -n io.github.dalong.hypercraft/io.github.dalong.hyperhome.MainActivity --ei page 0")) {
            try (java.io.InputStream input = new android.os.ParcelFileDescriptor.AutoCloseInputStream(command)) {
                input.readAllBytes();
            }
        }
        Thread.sleep(400);
        JSONArray pages = new JSONArray();
        int passed = 0;
        for (String route : routes) {
            String title = RouteCatalog.title(route);
            boolean needsRoot = RouteCatalog.requiresRoot(route);
            JSONObject page = new JSONObject().put("route", route).put("title", title).put("requiresRoot", needsRoot);
            Activity activity = null;
            try {
                android.content.Intent intent = new android.content.Intent(app, StreamlineActivity.class)
                        .putExtra(StreamlineActivity.EXTRA_ROUTE, route)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                activity = startActivitySync(intent);
                if (authorized && needsRoot) Thread.sleep(700);
                long until = android.os.SystemClock.elapsedRealtime() + 10000;
                android.view.accessibility.AccessibilityNodeInfo root;
                boolean titlePresent, gatePresent;
                do {
                    root = getUiAutomation().getRootInActiveWindow();
                    titlePresent = visibleToolText(root, title);
                    gatePresent = visibleToolText(root, "授权并准备工具");
                    boolean contentPresent = (!authorized || Access.INSTANCE.getRoot() && Access.INSTANCE.getModule())
                            && (!authorized || !needsRoot || activeTexts().length() > 2);
                    boolean reading = visibleToolText(root, "正在读取…") || visibleToolText(root, "正在读取已保存的网络…");
                    boolean wifiReady = !authorized || !"wifi".equals(route) || findNode(root,"复制",false)!=null || visibleToolText(root,"没有读到保存的网络");
                    if (titlePresent && (!needsRoot || authorized != gatePresent) && contentPresent && !reading && wifiReady) break;
                    Thread.sleep(80);
                } while (android.os.SystemClock.elapsedRealtime() < until);
                boolean unchangedAccess = Access.INSTANCE.getRoot() == authorized && Access.INSTANCE.getModule() == authorized;
                JSONArray visibleTexts = activeTexts();
                boolean loaded = (!authorized || !needsRoot || visibleTexts.length() > 2)
                        && !visibleToolText(root, "正在读取…");
                if (authorized && "wifi".equals(route)) {
                    loaded = findNode(root,"复制",false)!=null || visibleToolText(root,"没有读到保存的网络");
                    page.put("automaticListLoaded",loaded).put("readButtonPresent",visibleToolText(root,"重新读取"));
                }
                boolean ok = titlePresent && (!needsRoot || authorized != gatePresent) && unchangedAccess && loaded;
                page.put("titlePresent", titlePresent).put("authorizationGatePresent", gatePresent)
                        .put("expectedAccessState", unchangedAccess).put("contentLoaded", loaded)
                        .put("status", ok ? "PASS" : "FAIL");
                // Authorized pages can contain saved WLAN credentials. Keep their content on-device.
                if (!authorized) page.put("texts", visibleTexts);
                if (ok) passed++;
                if ((!authorized || !"wifi".equals(route)) && (!requested.isEmpty() || "display".equals(route)
                        || "charge".equals(route) || "mydevice".equals(route) || !ok))
                    { Thread.sleep(250); page.put("capture", captureUi(app, "hypercraft-tools-" + route, root)); }
            } catch (Exception error) {
                page.put("status", "FAIL").put("error", error.getClass().getSimpleName() + ": " + error.getMessage());
            } finally {
                if (activity != null) { Activity opened = activity; runOnMainSync(opened::finish); }
            }
            pages.put(page);
        }
        boolean samePreferences = original.equals(preferenceValues(app));
        boolean expectedAccess = Access.INSTANCE.getRoot() == authorized && Access.INSTANCE.getModule() == authorized;
        JSONObject report = new JSONObject().put("status", passed == routes.size() && samePreferences && expectedAccess ? "PASS" : "FAIL")
                .put("catalogGroups", RouteCatalog.groups().size()).put("catalogEntries", RouteCatalog.entries().size())
                .put("visited", routes.size()).put("passed", passed).put("samePreferenceMaps", samePreferences)
                .put("authorized", authorized).put("expectedAccessState", expectedAccess).put("pages", pages);
        java.io.File reportFile = new java.io.File(app.getExternalFilesDir(null), authorized ? "hypercraft-tools-root-report.json" : "hypercraft-tools-report.json");
        report.put("report", reportFile.getAbsolutePath());
        try (java.io.FileOutputStream output = new java.io.FileOutputStream(reportFile)) {
            output.write(report.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        return report;
    }

    private static boolean visibleToolText(android.view.accessibility.AccessibilityNodeInfo node, String text) {
        if (node == null) return false;
        if (node.isVisibleToUser() && android.text.TextUtils.equals(node.getPackageName(), "io.github.dalong.hypercraft")
                && android.text.TextUtils.equals(node.getText(), text)) return true;
        for (int i = 0; i < node.getChildCount(); i++) if (visibleToolText(node.getChild(i), text)) return true;
        return false;
    }

    /** Drive only this module's search UI; no preference changes or external applications. */
    private JSONObject search(HyperHomeApp app) throws Exception {
        String label = arguments.getString("label", "search");
        if (!label.matches("[a-zA-Z0-9_-]{1,64}")) throw new IllegalArgumentException("Invalid local label");
        // Shell is used only to open this fixed module Activity; Android 17 otherwise blocks
        // activity starts from the instrumentation's background application context.
        try (android.os.ParcelFileDescriptor command = getUiAutomation().executeShellCommand(
                "am start -W -n io.github.dalong.hypercraft/io.github.dalong.hyperhome.MainActivity --ei page 0")) {
            try (java.io.InputStream input = new android.os.ParcelFileDescriptor.AutoCloseInputStream(command)) {
                input.readAllBytes();
            }
        }
        Thread.sleep(500);
        var input = findNode(getUiAutomation().getRootInActiveWindow(), "搜索子模块与功能", false);
        if (input == null) throw new IllegalStateException("Search input missing");
        Bundle text = new Bundle(); text.putCharSequence(android.view.accessibility.AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                arguments.getString("query", ""));
        if (!input.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_SET_TEXT, text))
            throw new IllegalStateException("Search input rejected text");
        Thread.sleep(400);
        JSONObject result = new JSONObject().put("results", activeTexts());
        result.put("before", screenshot(app, label + "-results"));
        String click = arguments.getString("click", "");
        if (!click.isEmpty()) {
            var target = findNode(getUiAutomation().getRootInActiveWindow(), click, true);
            if (target == null) throw new IllegalArgumentException("No exact result " + click);
            while (!target.isClickable() && target.getParent() != null) target = target.getParent();
            if (!target.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
                throw new IllegalStateException("Result was not clickable");
            Thread.sleep(850);
            result.put("destination", activeTexts()).put("after", screenshot(app, label + "-destination"));
        }
        return result;
    }
    private static android.view.accessibility.AccessibilityNodeInfo findNode(
            android.view.accessibility.AccessibilityNodeInfo node, String text, boolean label) {
        if (node == null) return null;
        if ((!label || !node.isEditable())
                && android.text.TextUtils.equals(text, label ? node.getText() : node.getContentDescription())) return node;
        for (int i=0; i<node.getChildCount(); i++) {
            var found = findNode(node.getChild(i), text, label); if (found != null) return found;
        }
        return null;
    }
    private JSONArray activeTexts() {
        JSONArray items = new JSONArray();
        collect(getUiAutomation().getRootInActiveWindow(), items); return items;
    }
    private static void collect(android.view.accessibility.AccessibilityNodeInfo node, JSONArray items) {
        if (node == null) return;
        if (node.getText() != null && node.getText().length() != 0) items.put(node.getText().toString());
        for (int i=0; i<node.getChildCount(); i++) collect(node.getChild(i), items);
    }
    /** Unimplemented capabilities must have no menu, search destination or mutable control. */
    private static Map<String,Map<String,?>> preferenceValues(HyperHomeApp app) {
        return Map.of("mydevice_backup",new java.util.HashMap<>(app.getSharedPreferences("mydevice_backup",0).getAll()));
    }
    private JSONObject captureUi(HyperHomeApp app, String label, android.view.accessibility.AccessibilityNodeInfo root) throws Exception {
        String image = screenshot(app, label);
        java.io.File xml = new java.io.File(app.getExternalFilesDir(null), label + ".xml");
        try (java.io.FileOutputStream stream = new java.io.FileOutputStream(xml)) {
            var serializer = android.util.Xml.newSerializer(); serializer.setOutput(stream, "UTF-8");
            serializer.startDocument("UTF-8", true); serializer.startTag(null, "hierarchy");
            serializeNode(root, serializer); serializer.endTag(null, "hierarchy"); serializer.endDocument();
        }
        return new JSONObject().put("png", image).put("xml", xml.getAbsolutePath())
                .put("elapsedRealtimeMs", android.os.SystemClock.elapsedRealtime());
    }

    private static void serializeNode(android.view.accessibility.AccessibilityNodeInfo node, org.xmlpull.v1.XmlSerializer out) throws Exception {
        if (node == null) return;
        android.graphics.Rect bounds = new android.graphics.Rect(); node.getBoundsInScreen(bounds);
        out.startTag(null, "node");
        out.attribute(null, "text", node.getText() == null ? "" : node.getText().toString());
        out.attribute(null, "content-desc", node.getContentDescription() == null ? "" : node.getContentDescription().toString());
        out.attribute(null, "class", String.valueOf(node.getClassName())); out.attribute(null, "package", String.valueOf(node.getPackageName()));
        out.attribute(null, "enabled", Boolean.toString(node.isEnabled())); out.attribute(null, "clickable", Boolean.toString(node.isClickable()));
        out.attribute(null, "visible-to-user", Boolean.toString(node.isVisibleToUser())); out.attribute(null, "bounds", bounds.toShortString());
        for (int i = 0; i < node.getChildCount(); i++) serializeNode(node.getChild(i), out);
        out.endTag(null, "node");
    }

    private String screenshot(HyperHomeApp app, String label) throws Exception {
        java.io.File file = new java.io.File(app.getExternalFilesDir(null), label + ".png");
        android.graphics.Bitmap bitmap = getUiAutomation().takeScreenshot();
        if (bitmap == null) throw new IllegalStateException("No screenshot");
        try (java.io.FileOutputStream stream = new java.io.FileOutputStream(file)) {
            if (!bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream)) throw new IllegalStateException("Image encoding failed");
        } finally { bitmap.recycle(); }
        return file.getAbsolutePath();
    }

}
