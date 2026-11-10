package com.dalong.pengpaitool

import android.content.Context
import com.dalong.pengpaitool.ui.PairingSession
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** 固定的 debug-only Root 生命周期验收，只检查并清理本次 fixture 记录的 PID + 启动时间。 */
object DeviceRootLifecycle {
    private data class OwnedPid(val label: String, val pid: Long, val started: Long)

    @JvmStatic fun run(context: Context): String = runBlocking {
        val folder = File(context.cacheDir, "root-lifecycle-${UUID.randomUUID()}").apply { mkdirs() }
        val cases = JSONArray()
        val records = mutableListOf<OwnedPid>()
        suspend fun verify(name: String, block: suspend () -> String) {
            val row = JSONObject().put("name", name)
            try { row.put("details", block()).put("status", "PASS") }
            catch (error: Exception) { row.put("status", "FAIL").put("error", error.toString()) }
            cases.put(row)
        }
        try {
            val identity = Root.exec("id -u", timeoutSeconds = 8)
            check(identity.ok && identity.out.trim() == "0") { "Root probe failed: ${identity.code}: ${identity.err}" }
            verify("normal_exit") {
                val result = Root.exec("printf 'lifecycle-normal\\n'", timeoutSeconds = 5)
                check(result.code == 0 && result.out.trim() == "lifecycle-normal") { "Unexpected normal result: $result" }
                "exit=0; stdout preserved"
            }
            verify("nonzero_exit") {
                val result = Root.exec("printf 'lifecycle-error\\n' >&2; exit 7", timeoutSeconds = 5)
                check(result.code == 7 && result.err.trim() == "lifecycle-error") { "Unexpected nonzero result: $result" }
                "exit=7; stderr preserved"
            }
            verify("normal_exit_cleans_foreground_descendant") {
                val labels = listOf("normal-worker", "normal-child")
                labels.forEach { File(folder, it).writeText("") }
                val command = recorder(folder) + "\nrecord normal-worker \$\$; sleep 30 & record normal-child \$!; exit 7"
                val result = Root.exec(command, timeoutSeconds = 5)
                check(result.code == 7) { "Normal descendant command lost exit code: $result" }
                val owned = labels.map { awaitRecord(folder, it).also(records::add) }
                withTimeout(4000) { while (running(owned).isNotEmpty()) delay(80) }
                "exit=7; worker and non-detached child ended"
            }
            verify("cancel_foreground_descendants") { foreground(folder, "cancel", records, timeout = false, detached = false) }
            verify("timeout_foreground_descendants") { foreground(folder, "timeout", records, timeout = true, detached = false) }
            verify("cancel_preserves_detached_daemon") { foreground(folder, "detach", records, timeout = false, detached = true) }
            verify("normal_exit_preserves_detached_daemon") {
                val prefix = "normal-daemon"
                File(folder, prefix).writeText("")
                val command = "setsid sh -c ${shq(recorder(folder) + "\nrecord $prefix \$\$; exec sleep 30")} </dev/null >/dev/null 2>&1 & " +
                    "while [ ! -s ${shq(File(folder, prefix).absolutePath)} ]; do sleep 0.01; done"
                val result = Root.exec(command, timeoutSeconds = 5)
                check(result.ok) { "Detached start failed: $result" }
                val daemon = awaitRecord(folder, prefix).also { records.add(it) }
                check(running(listOf(daemon)).isNotEmpty()) { "Normal command return killed its detached daemon" }
                cleanup(listOf(daemon))
                check(running(listOf(daemon)).isEmpty()) { "Fixture daemon cleanup failed" }
                "command exited; detached daemon survived and was explicitly cleaned"
            }
            verify("process_observer_positive_control") { coroutineScope {
                val label = "observer-worker"
                File(folder, label).writeText("")
                val task = async { Root.exec(recorder(folder) + "\nrecord $label \$\$; sleep 30", timeoutSeconds = 30) }
                try {
                    val owned = awaitRecord(folder, label).also(records::add)
                    val query = Root.exec("awk '{print \$5}' /proc/${owned.pid}/stat", timeoutSeconds = 5)
                    val group = query.out.trim().toLongOrNull()
                    check(query.ok && group != null) { "Fixture group unavailable" }
                    fun observed(): Boolean {
                        val snapshot = JSONObject(DeviceProcessSnapshot.capture())
                        check(snapshot.getBoolean("ownWrapperObserved"))
                        val rows = snapshot.getJSONArray("residuals")
                        return (0 until rows.length()).any { rows.getJSONObject(it).getLong("group") == group }
                    }
                    check(observed()) { "Active fixture group was invisible to the observer" }
                    task.cancelAndJoin()
                    withTimeout(4000) { while (running(listOf(owned)).isNotEmpty()) delay(80) }
                    check(!observed()) { "Cancelled fixture group remains visible" }
                    "observed active fixture group; cancelled worker and wrapper group disappeared"
                } finally { task.cancelAndJoin() }
            } }
        } catch (error: Exception) {
            cases.put(JSONObject().put("name", "root_setup").put("status", "FAIL").put("error", error.toString()))
        } finally {
            // Includes any records written just before a failed/cancelled assertion.
            folder.listFiles().orEmpty().mapNotNull(::readRecord).forEach { record ->
                if (records.none { it.pid == record.pid && it.started == record.started }) records.add(record)
            }
        }
        var cleanupVerified = false
        verify("fixture_cleanup") {
            cleanup(records)
            val remaining = running(records)
            check(remaining.isEmpty()) { "Fixture processes remain: $remaining" }
            cleanupVerified = true
            "all recorded fixture PID + start-time pairs ended"
        }
        val passed = (0 until cases.length()).count { cases.getJSONObject(it).optString("status") == "PASS" }
        val report = JSONObject().put("status", if (passed == cases.length()) "PASS" else "FAIL")
            .put("passed", passed).put("total", cases.length()).put("cases", cases)
        val target = File(context.getExternalFilesDir(null), "hypercraft-root-lifecycle-report.json")
        report.put("report", target.absolutePath)
        target.writeText(report.toString(2))
        if (cleanupVerified) folder.deleteRecursively()
        report.toString()
    }

    /** Explicit debug action: cancel after Binder started pairing but before Root returns. */
    @JvmStatic fun runPairingCancellation(context: Context): String = runBlocking {
        val portFile = File(context.cacheDir, "pairing-lifecycle-${UUID.randomUUID()}")
        val report = JSONObject()
        var previousWifi: String? = null
        try {
            val baseline = Root.exec("settings get global adb_wifi_enabled", timeoutSeconds = 5)
            check(baseline.ok && baseline.out.trim() in listOf("0", "1", "null")) { "Cannot read wireless debugging baseline" }
            previousWifi = baseline.out.trim()
            coroutineScope {
                val task = async {
                    PairingSession.run(stop = {
                        val stopped = Mod.result("adb-pair-stop")
                        check(stopped.ok) { "Pairing stop failed" }
                    }) {
                        // Persist only the listener port, never the pairing secret.
                        val result = Root.exec("""
                            result=${'$'}(sh ${Mod.DIR}/adclean.sh adb-pair-start)
                            case "${'$'}result" in
                              OK'|'*) port=${'$'}(printf '%s' "${'$'}result" | cut -d'|' -f4) ;;
                              *) exit 1 ;;
                            esac
                            printf '%s' "${'$'}port" > ${shq(portFile.absolutePath)}
                            unset result
                            sleep 30
                        """.trimIndent(), timeoutSeconds = 35)
                        check(result.ok) { "Pairing fixture start failed" }
                        error("Fixture was not cancelled during its pending startup")
                    }
                }
                try {
                    val port = withTimeout(12000) {
                        var found: Int? = null
                        while (found == null) {
                            found = runCatching { portFile.readText().trim().toIntOrNull()?.takeIf { it in 1..65535 } }.getOrNull()
                            if (found == null) delay(25)
                        }
                        found
                    }
                    val hex = "%04X".format(java.util.Locale.ROOT, port)
                    suspend fun listenerPresent(): Boolean {
                        val result = Root.exec("awk '${'$'}4 == \"0A\" && ${'$'}2 ~ /:$hex${'$'}/ { print \"present\" }' /proc/net/tcp /proc/net/tcp6", timeoutSeconds = 5)
                        check(result.ok) { "Pairing listener query failed" }
                        return result.out.isNotBlank()
                    }
                    check(listenerPresent()) { "Pairing listener was never observed" }
                    check(!task.isCompleted) { "Startup returned before cancellation" }
                    withTimeout(10000) { task.cancelAndJoin() }
                    withTimeout(5000) { while (listenerPresent()) delay(80) }
                    report.put("status", "PASS").put("details", "Pairing listener observed during pending startup, cancelled, then absent")
                } finally { task.cancelAndJoin() }
            }
        } catch (error: Exception) {
            report.put("status", "FAIL").put("error", error.toString())
        } finally {
            withContext(NonCancellable) {
                previousWifi?.let { previous ->
                    val command = if (previous == "null") "settings delete global adb_wifi_enabled" else "settings put global adb_wifi_enabled $previous"
                    val restored = Root.exec(command + "; settings get global adb_wifi_enabled", timeoutSeconds = 5)
                    val ok = restored.ok && restored.out.trim() == previous
                    report.put("wirelessDebuggingRestored", ok)
                    if (!ok) report.put("status", "FAIL")
                }
            }
            portFile.delete()
        }
        val target = File(context.getExternalFilesDir(null), "hypercraft-pairing-lifecycle-report.json")
        report.put("report", target.absolutePath)
        target.writeText(report.toString(2))
        report.toString()
    }

    /** Creates and abandons an empty real install session; never writes or commits an APK. */
    @JvmStatic fun runSplitCancellation(context: Context): String = runBlocking {
        val folder = File(context.cacheDir, "split-lifecycle-${UUID.randomUUID()}").apply { mkdirs() }
        val sessionFile = File(folder, "session")
        val pending = File(folder, "pending")
        val abandon = File(folder, "abandon")
        val payload = File(folder, "fixture.apk").apply { writeText("not an APK; consumed only by fixture stub") }
        val report = JSONObject()
        var cleanupVerified = false
        fun recordedSession(): Int? = runCatching { sessionFile.readText().trim().toIntOrNull()?.takeIf { it > 0 } }.getOrNull()
        suspend fun present(id: Int): Boolean {
            val result = Root.exec("CLASSPATH=${shq(context.applicationInfo.sourceDir)} /system/bin/app_process /system/bin com.dalong.pengpaitool.DeviceInstallSessionProbe $id", timeoutSeconds = 10)
            check(result.ok && result.out.trim() in listOf("PRESENT", "ABSENT")) { "Install session query failed: ${result.code}: ${result.err}" }
            return result.out.trim() == "PRESENT"
        }
        try {
            val source = context.assets.open("backend/adclean.sh").bufferedReader().use { it.readText() }
            val start = source.indexOf("restore_split_apks() (\n")
            check(start >= 0) { "Production split restore function missing" }
            val end = source.indexOf("\n)", start)
            check(end > start) { "Production split restore function incomplete" }
            val productionFunction = source.substring(start, end + 2)
            val fixture = productionFunction + "\n" + """
                pm() {
                  case "${'$'}1" in
                    install-create)
                      output=${'$'}(command pm "${'$'}@" 2>&1); code=${'$'}?
                      id=${'$'}(printf '%s\n' "${'$'}output" | sed -n 's/^Success: created install session \[\([0-9][0-9]*\)\]${'$'}/\1/p')
                      [ -z "${'$'}id" ] || printf '%s' "${'$'}id" > ${shq(sessionFile.absolutePath)}
                      printf '%s\n' "${'$'}output"
                      return "${'$'}code" ;;
                    install-write)
                      cat >/dev/null
                      printf pending > ${shq(pending.absolutePath)}
                      sleep 30
                      return 1 ;;
                    install-commit)
                      echo 'Fixture refuses every commit' >&2
                      return 1 ;;
                    install-abandon)
                      printf started > ${shq(abandon.absolutePath)}
                      command pm "${'$'}@"; code=${'$'}?
                      printf 'returned:%s' "${'$'}code" > ${shq(abandon.absolutePath)}
                      return "${'$'}code" ;;
                    *) return 1 ;;
                  esac
                }
                restore_split_apks ${shq(payload.absolutePath)}
            """.trimIndent()
            coroutineScope {
                val task = async { Root.exec(fixture, timeoutSeconds = 40) }
                try {
                    withTimeout(15000) {
                        while (!pending.exists()) {
                            check(!task.isCompleted) { "Split install fixture exited before cancellation" }
                            delay(25)
                        }
                    }
                    val id = checkNotNull(recordedSession()) { "Session ID not recorded" }
                    report.put("session", id)
                    check(present(id)) { "Created session was not visible before cancellation" }
                    withTimeout(5000) { task.cancelAndJoin() }
                    withTimeout(8000) { while (present(id)) delay(80) }
                    report.put("status", "PASS").put("details", "Real empty install session existed before cancellation and disappeared after production trap; no APK write or commit")
                } finally { task.cancelAndJoin() }
            }
        } catch (error: Exception) {
            report.put("status", "FAIL").put("error", error.toString())
        } finally {
            report.put("trapAbandon", runCatching { abandon.readText() }.getOrDefault("not called"))
            withContext(NonCancellable) {
                val id = recordedSession()
                try {
                    if (id != null) {
                        // Failure cleanup is separately recorded and cannot turn the probe into PASS.
                        Root.exec("pm install-abandon $id", timeoutSeconds = 10)
                        withTimeout(8000) { while (present(id)) delay(80) }
                    }
                    cleanupVerified = true
                } catch (error: Exception) {
                    report.put("status", "FAIL").put("cleanupError", error.toString())
                }
            }
            report.put("cleanupVerified", cleanupVerified)
        }
        val target = File(context.getExternalFilesDir(null), "hypercraft-split-lifecycle-report.json")
        report.put("report", target.absolutePath)
        target.writeText(report.toString(2))
        if (cleanupVerified) folder.deleteRecursively()
        report.toString()
    }

    private fun recorder(folder: File) = """
        record() {
          started=${'$'}(awk '{print ${'$'}22}' /proc/${'$'}2/stat 2>/dev/null)
          [ -n "${'$'}started" ] && printf '%s %s\n' "${'$'}2" "${'$'}started" > ${shq(folder.absolutePath)}/${'$'}1
        }
    """.trimIndent()

    private suspend fun foreground(folder: File, prefix: String, all: MutableList<OwnedPid>, timeout: Boolean, detached: Boolean): String = coroutineScope {
        // Android does not guarantee /proc/<pid>/task/<pid>/children visibility. Observe the
        // fixture worker + its explicit child directly instead of guessing the wrapper monitor.
        val labels = listOf("$prefix-worker", "$prefix-child") +
            if (detached) listOf("$prefix-daemon") else emptyList()
        labels.forEach { File(folder, it).writeText("") }
        val daemon = if (detached) "setsid sh -c ${shq(recorder(folder) + "\nrecord $prefix-daemon \$\$; exec sleep 30")} </dev/null >/dev/null 2>&1 &" else ""
        val command = recorder(folder) + """

            record $prefix-worker ${'$'}${'$'}
            sleep 30 &
            record $prefix-child ${'$'}!
            $daemon
            wait
        """.trimIndent()
        val task = async { Root.exec(command, timeoutSeconds = if (timeout) 2 else 30) }
        val owned = mutableListOf<OwnedPid>()
        try {
            labels.forEach { label -> awaitRecord(folder, label).also { owned.add(it); all.add(it) } }
            if (timeout) {
                val result = withTimeout(5000) { task.await() }
                check(!result.ok && result.err.contains("超时")) { "Expected timeout: $result" }
            } else {
                check(!task.isCompleted) { "Foreground command exited before cancellation" }
                withTimeout(3000) { task.cancelAndJoin() }
            }
            val foreground = owned.filterNot { it.label.endsWith("-daemon") }
            withTimeout(4000) { while (running(foreground).isNotEmpty()) delay(80) }
            if (detached) {
                val background = owned.filter { it.label.endsWith("-daemon") }
                check(running(background).size == 1) { "Cancellation killed an explicitly detached daemon" }
            }
            "${foreground.size} foreground processes ended; detached=${if (detached) "preserved" else "none"}"
        } finally {
            task.cancelAndJoin()
            cleanup(owned)
        }
    }

    private fun readRecord(file: File): OwnedPid? {
        val tokens = runCatching { file.readText().trim().split(' ') }.getOrNull() ?: return null
        if (tokens.size != 2) return null
        val pid = tokens[0].toLongOrNull()?.takeIf { it > 1 } ?: return null
        val started = tokens[1].toLongOrNull()?.takeIf { it > 0 } ?: return null
        return OwnedPid(file.name, pid, started)
    }

    private suspend fun awaitRecord(folder: File, name: String): OwnedPid = withTimeout(2500) {
        var found: OwnedPid? = null
        while (found == null) {
            found = readRecord(File(folder, name))
            if (found == null) delay(10)
        }
        found
    }

    private suspend fun running(records: List<OwnedPid>): List<String> {
        if (records.isEmpty()) return emptyList()
        val command = records.joinToString("\n") { r ->
            // Labels are generated internally, never supplied by an Intent or user input.
            require(r.label.matches(Regex("[a-z-]+")))
            "awk -v expected=${r.started} '\$22 == expected && \$3 != \"Z\" { print \"${r.label}\" }' /proc/${r.pid}/stat 2>/dev/null"
        } + "\nprintf '__root_query_complete__\\n'"
        val result = Root.exec(command, timeoutSeconds = 5)
        check(result.ok && result.lines.lastOrNull() == "__root_query_complete__") { "Root process query failed: $result" }
        return result.lines.dropLast(1)
    }

    private suspend fun cleanup(records: List<OwnedPid>) {
        if (records.isEmpty()) return
        fun signals(signal: String) = records.distinctBy { it.pid to it.started }.joinToString("\n") { r ->
            "[ \"\$(awk '{print \$22}' /proc/${r.pid}/stat 2>/dev/null)\" = ${r.started} ] && kill -$signal ${r.pid} 2>/dev/null; :"
        }
        Root.exec(signals("TERM"), timeoutSeconds = 5)
        delay(250)
        Root.exec(signals("KILL"), timeoutSeconds = 5)
    }
}
