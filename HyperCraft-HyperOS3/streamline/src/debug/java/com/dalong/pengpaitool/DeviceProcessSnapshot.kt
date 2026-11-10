package com.dalong.pengpaitool

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

/** Read-only debug diagnostics. Never kills a process or changes a backend feature. */
object DeviceProcessSnapshot {
    @JvmStatic fun capture(): String = runBlocking {
        val readbacks = ManagedDaemonPolicy.settings.joinToString("\n") { setting ->
            "read_setting ${shq(setting.id)} ${shq(setting.path)} ${shq(setting.key)}"
        }
        val result = Root.exec("""
            group=${'$'}(awk '{print ${'$'}5}' /proc/${'$'}${'$'}/stat)
            printf 'SELF|%s\n' "${'$'}group"
            read_setting() {
              if [ ! -r "${'$'}2" ]; then state=unreadable; value=''
              elif value=${'$'}(awk -v wanted="${'$'}3" '
                index(${'$'}0,wanted "=")==1 { count++; value=substr(${'$'}0,length(wanted)+2) }
                END { if (count==1 && length(value)<128) print value; else exit 1 }
              ' "${'$'}2"); then state=read
              else state=invalid; value=''; fi
              value=${'$'}(printf '%s' "${'$'}value" | tr '|\r\n' '   ')
              printf 'CONFIG|%s|%s|%s\n' "${'$'}1" "${'$'}state" "${'$'}value"
            }
            $readbacks
            ps -A -o PID,PPID,PGID,USER | while read -r pid parent group user; do
              case "${'$'}user" in root|0)
                # Kernel threads have no userspace command line or owned session.
                [ "${'$'}group" -gt 0 ] 2>/dev/null || continue
                command=${'$'}(/system/bin/tr '\000\n\r' '   ' < /proc/${'$'}pid/cmdline 2>/dev/null)
                printf 'ROOT|%s|%s|%s|%s\n' "${'$'}pid" "${'$'}parent" "${'$'}group" "${'$'}command"
                ;;
              esac
            done
            for host in io.github.dalong.hypercraft com.android.settings; do
              for pid in ${'$'}(pidof "${'$'}host"); do
                started=${'$'}(awk '{print ${'$'}22}' /proc/${'$'}pid/stat 2>/dev/null)
                rss=${'$'}(awk '${'$'}1 == "VmRSS:" {print ${'$'}2}' /proc/${'$'}pid/status 2>/dev/null)
                pss=${'$'}(awk '${'$'}1 == "Pss:" {print ${'$'}2; exit}' /proc/${'$'}pid/smaps_rollup 2>/dev/null)
                cpu=${'$'}(awk '{print ${'$'}14+${'$'}15}' /proc/${'$'}pid/stat 2>/dev/null)
                threads=${'$'}(awk '${'$'}1 == "Threads:" {print ${'$'}2}' /proc/${'$'}pid/status 2>/dev/null)
                printf 'HOST|%s|%s|%s|%s|%s|%s|%s\n' "${'$'}host" "${'$'}pid" "${'$'}started" "${'$'}rss" "${'$'}pss" "${'$'}cpu" "${'$'}threads"
              done
            done
        """.trimIndent(), timeoutSeconds = 45)
        check(result.ok && !result.truncated) { "Process snapshot failed: ${result.code}: ${result.err}" }
        val self = result.lines.firstOrNull { it.startsWith("SELF|") }?.substringAfter('|')?.toLongOrNull()
        check(self != null) { "Query process group unavailable" }
        val residuals = JSONArray()
        val managedDaemons = JSONArray()
        val hosts = JSONArray()
        val config = result.lines.filter { it.startsWith("CONFIG|") }.associate { line ->
            val fields = line.split('|', limit = 4)
            check(fields.size == 4) { "Invalid daemon configuration row" }
            fields[1] to ManagedDaemonPolicy.Readback(fields[2], fields[3])
        }
        val configEvidence = JSONArray()
        ManagedDaemonPolicy.settings.forEach { setting ->
            val value = config[setting.id]
            configEvidence.put(JSONObject().put("id", setting.id).put("path", setting.path).put("key", setting.key)
                .put("status", value?.status ?: "missing").put("value", value?.value ?: JSONObject.NULL))
        }
        val processes = result.lines.filter { it.startsWith("ROOT|") }.mapNotNull { line ->
            val fields = line.split('|', limit = 5)
            if (fields.size != 5) null else {
                val pid = fields[1].toLongOrNull()
                val parent = fields[2].toLongOrNull()
                val group = fields[3].toLongOrNull()
                if (pid == null || parent == null || group == null) null
                else ManagedDaemonPolicy.Process(pid, parent, group, fields[4])
            }
        }
        val managed = ManagedDaemonPolicy.classify(processes.filter { it.group != self }, config)
        var ownWrapperSeen = false
        for (line in result.lines) {
            if (line.startsWith("HOST|")) {
                val fields = line.split('|')
                check(fields.size == 8) { "Invalid host row" }
                val row = JSONObject().put("name", fields[1])
                for ((index, name) in listOf(2 to "pid", 3 to "started", 4 to "rssKiB", 5 to "pssKiB", 6 to "cpuTicks", 7 to "threads")) {
                    row.put(name, fields[index].toLongOrNull() ?: JSONObject.NULL)
                }
                hosts.put(row)
                continue
            }
            if (!line.startsWith("ROOT|")) continue
            val fields = line.split('|', limit = 5)
            if (fields.size != 5 || fields[1].toLongOrNull() == null) continue
            val command = fields[4]
            val foreground = command.contains("exec 4<&0") && command.contains("kill -KILL")
            if (fields[3].toLongOrNull() == self) {
                if (foreground) ownWrapperSeen = true
                continue
            }
            val backend = command.contains("/data/adb/hypercraft/") || command.contains("/data/user_de/0/io.github.dalong.hypercraft/")
            val daemon = managed[fields[1].toLong()]
            if (!foreground && daemon != null) {
                managedDaemons.put(JSONObject().put("pid", fields[1].toLong()).put("ppid", fields[2].toLong())
                    .put("group", fields[3].toLong()).put("kind", daemon).put("command", command.trim()))
            } else if (foreground || backend) residuals.put(JSONObject().put("pid", fields[1].toLong())
                .put("ppid", fields[2].toLong()).put("group", fields[3].toLong())
                .put("kind", if (foreground) "foreground-wrapper" else "backend"))
        }
        check(ownWrapperSeen) { "Root wrapper arguments are not observable; residual count cannot be trusted" }
        JSONObject().put("residuals", residuals).put("managedDaemons", managedDaemons)
            .put("daemonConfiguration", configEvidence).put("hosts", hosts).put("ownWrapperObserved", true).toString()
    }
}
