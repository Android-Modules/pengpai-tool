

# Xposed 入口由框架按 java_init.list 里的类名反射加载，hook 侧代码运行在「设置」进程
-keep class com.dalong.pengpaitool.xp.** { *; }
-dontwarn io.github.libxposed.api.**
-keep class com.dalong.pengpaitool.AdbBridge { public static void main(java.lang.String[]); }
