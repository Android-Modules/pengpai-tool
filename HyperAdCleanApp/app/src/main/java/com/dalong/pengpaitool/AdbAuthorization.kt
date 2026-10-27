package com.dalong.pengpaitool

object AdbAuthorization {
    suspend fun run(args: String): String {
        val result = Root.exec("apk=\$(pm path com.dalong.pengpaitool | head -n 1); " +
            "CLASSPATH=\"\${apk#package:}\" /system/bin/app_process /system/bin " +
            "com.dalong.pengpaitool.AdbBridge $args")
        return result.out.ifBlank { "ERR|adb|无法读取系统授权接口" }
    }
}
