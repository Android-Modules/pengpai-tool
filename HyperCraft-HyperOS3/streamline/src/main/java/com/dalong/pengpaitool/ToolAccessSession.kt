package com.dalong.pengpaitool

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal enum class ToolAccessState { IDLE, NO_ROOT, NO_MODULE, OK }

/** A remembered grant permits a check, never substitutes for checking the current permission. */
internal class ToolAccessCoordinator(
    private val previouslyGranted: () -> Boolean,
    private val rememberGrant: () -> Unit,
    private val checkRoot: suspend () -> Boolean,
    private val prepareBackend: suspend () -> Boolean,
    private val now: () -> Long,
    initiallyPrepared: Boolean = false,
) {
    private val lock = Mutex()
    private var prepared = initiallyPrepared
    private var result: ToolAccessState? = null
    private var checkedAt = 0L
    @Volatile private var revision = 0L

    suspend fun ensure(manual: Boolean): ToolAccessState {
        val observed = revision
        return lock.withLock {
            val previous = result
            if (previous != null && (observed != revision || !manual && now() - checkedAt < RECHECK_MS)) return@withLock previous
            if (!manual && !previouslyGranted()) return@withLock ToolAccessState.IDLE
            val root = checkRoot()
            val next = if (!root) {
                prepared = false
                ToolAccessState.NO_ROOT
            } else {
                rememberGrant()
                if (!prepared) prepared = prepareBackend()
                if (prepared) ToolAccessState.OK else ToolAccessState.NO_MODULE
            }
            result = next
            checkedAt = now()
            revision++
            next
        }
    }

    companion object { const val RECHECK_MS = 60_000L }
}

internal object ToolAccessSession {
    private const val GRANTED = "previously_granted"
    private var coordinator: ToolAccessCoordinator? = null

    private fun preferences(context: Context) = context.getSharedPreferences("tool_access", Context.MODE_PRIVATE)

    private fun hasGrantHistory(context: Context): Boolean = preferences(context).getBoolean(GRANTED, false) ||
        context.createDeviceProtectedStorageContext().getSharedPreferences("runtime", Context.MODE_PRIVATE).getBoolean("ready", false)

    fun shouldRestore(context: Context) = Access.root || hasGrantHistory(context)

    /** Call only after a successful current Root probe, including the debug authorization fixture. */
    @JvmStatic fun rememberAuthorized(context: Context) { preferences(context).edit().putBoolean(GRANTED, true).apply() }

    private fun coordinator(context: Context): ToolAccessCoordinator = synchronized(this) {
        coordinator ?: run {
            val application = context.applicationContext
            ToolAccessCoordinator(
                previouslyGranted = { Access.root || hasGrantHistory(application) },
                rememberGrant = { rememberAuthorized(application) },
                checkRoot = { Root.granted() },
                prepareBackend = { BackendRuntime.prepare(application) },
                now = SystemClock::elapsedRealtime,
                initiallyPrepared = Access.module,
            ).also { coordinator = it }
        }
    }

    suspend fun ensure(context: Context, manual: Boolean): ToolAccessState = coordinator(context).ensure(manual).also {
        Access.root = it == ToolAccessState.OK || it == ToolAccessState.NO_MODULE
        Access.module = it == ToolAccessState.OK
    }
}
