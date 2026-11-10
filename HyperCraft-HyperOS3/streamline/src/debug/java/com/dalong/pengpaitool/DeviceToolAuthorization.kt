package com.dalong.pengpaitool

import android.content.Context
import kotlinx.coroutines.runBlocking

/** Debug instrumentation only; called explicitly after the user authorized device testing. */
object DeviceToolAuthorization {
    @JvmStatic fun foreground(): Boolean = runBlocking {
        Root.exec("am start -W -n io.github.dalong.hypercraft/io.github.dalong.hyperhome.MainActivity --ei page 0").ok
    }
    @JvmStatic fun prepare(context: Context): Boolean = runBlocking {
        Access.root = Root.granted()
        Access.module = Access.root && BackendRuntime.prepare(context.applicationContext)
        if (Access.root) ToolAccessSession.rememberAuthorized(context.applicationContext)
        Access.module
    }
}
