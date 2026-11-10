package com.dalong.pengpaitool.ui

import com.dalong.pengpaitool.Sys

/** Sys writes return no result, so verify the stored value before reporting success. */
internal suspend fun verifySystemWrite(value: String, write: suspend () -> Unit, read: suspend () -> String): Boolean {
    write()
    return read() == value
}

internal suspend fun writeSetting(ns: String, key: String, value: String) =
    verifySystemWrite(value, { Sys.put(ns, key, value) }, { Sys.get(ns, key) })

internal suspend fun writeProperty(key: String, value: String) =
    verifySystemWrite(value, { Sys.setProp(key, value) }, { Sys.prop(key) })
