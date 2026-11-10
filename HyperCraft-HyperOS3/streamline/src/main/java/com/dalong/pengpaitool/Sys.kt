package com.dalong.pengpaitool

/** 直接读写系统设置项和属性（不需要开机保持的简单开关） */
object Sys {
    private val NS = setOf("system", "secure", "global")

    suspend fun get(ns: String, key: String): String {
        require(ns in NS)
        return Root.exec("settings get $ns ${shq(key)}").out.trim()
    }

    suspend fun put(ns: String, key: String, value: String) {
        require(ns in NS)
        Root.exec("settings put $ns ${shq(key)} ${shq(value)}")
    }

    suspend fun delete(ns: String, key: String) {
        require(ns in NS)
        Root.exec("settings delete $ns ${shq(key)}")
    }

    suspend fun prop(key: String): String = Root.exec("getprop ${shq(key)}").out.trim()

    suspend fun setProp(key: String, value: String) { Root.exec("setprop ${shq(key)} ${shq(value)}") }

    suspend fun read(path: String): String = Root.exec("cat ${shq(path)} 2>/dev/null").out.trim()
}
