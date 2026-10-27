package com.dalong.pengpaitool

/**
 * 模块脚本 adclean.sh 的封装。需要开机保持/可恢复的操作都走脚本，
 * 这样 App 不运行时模块也能在开机后重新应用。
 */
object Mod {
    const val DIR = "/data/adb/modules/hyperadclean"
    private const val SCRIPT = "$DIR/adclean.sh"
    val PKG_RE = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$")

    /** 一条结果：OK/ERR/SKIP + 对象 + 消息 */
    data class Result(val kind: String, val target: String, val msg: String) {
        val ok get() = kind == "OK"
    }

    suspend fun installed(): Boolean = Root.exec("[ -f $SCRIPT ] && [ ! -f $DIR/disable ] && [ ! -f $DIR/remove ]").ok

    suspend fun rows(args: String): List<List<String>> =
        Root.exec("sh $SCRIPT $args").lines.map { it.split('|') }

    suspend fun raw(args: String): String = Root.exec("sh $SCRIPT $args").out

    suspend fun results(args: String): List<Result> =
        rows(args).map { Result(it.getOrElse(0) { "" }, it.getOrElse(1) { "" }, it.getOrElse(2) { "" }) }

    suspend fun result(args: String): Result =
        results(args).firstOrNull() ?: Result("ERR", "", "没有输出，确认模块已启用")

    suspend fun info(): Map<String, String> =
        rows("info").associate { it[0] to it.drop(1).joinToString("|") }

    data class PkgState(val st: String, val mode: String, val ts: Long)

    suspend fun status(pkgs: List<String>): Map<String, PkgState> =
        rows("status " + pkgs.filter { PKG_RE.matches(it) }.joinToString(" ")).associate {
            it[0] to PkgState(it.getOrElse(1) { "x" }, it.getOrElse(2) { "" }, it.getOrElse(3) { "" }.toLongOrNull() ?: 0)
        }
}
