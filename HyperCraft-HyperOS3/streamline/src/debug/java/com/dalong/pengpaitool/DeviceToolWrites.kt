package com.dalong.pengpaitool

import android.content.Context
import kotlinx.coroutines.runBlocking
import java.io.File

/** Fixed debug-only device probes, never packaged in a release build. */
object DeviceToolWrites {
    @JvmStatic fun run(context: Context, plan: String): String = runBlocking {
        require(plan in setOf("legacy", "inventory", "final", "recovery", "settings", "services", "packages", "charge", "cycle", "model", "model-baseline", "cpu", "system", "hold-stage", "hold-check", "hold-cleanup", "hold-final"))
        check(Root.granted()) { "Root authorization unavailable" }
        if (plan == "legacy") {
            val result = BackendRuntime.deactivateLegacy(context.applicationContext)
            return@runBlocking "exit=${result.code}\n${result.out}\n${result.err}"
        }
        check(BackendRuntime.prepare(context.applicationContext)) { BackendRuntime.error ?: "Backend preparation failed" }
        val script = File(context.createDeviceProtectedStorageContext().filesDir, "device-write-$plan.sh")
        val common = File(script.parentFile, "device-write-common.sh")
        context.assets.open("device-probes/common.sh").use { input -> common.outputStream().use(input::copyTo) }
        context.assets.open("device-probes/$plan.sh").use { input -> script.outputStream().use(input::copyTo) }
        val result = Root.exec("sh ${shq(script.absolutePath)}", timeoutSeconds = 300)
        val report = "exit=${result.code}\n${result.out}\n${result.err}"
        File(context.getExternalFilesDir(null), "hypercraft-write-$plan.txt").writeText(report)
        report
    }
}
