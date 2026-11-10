package com.dalong.pengpaitool.ui

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** The system pairing service outlives its shell. Own it from before start until cleanup ends. */
internal object PairingSession {
    private val lock = Mutex()

    suspend fun run(stop: suspend () -> Unit, session: suspend () -> Unit) = lock.withLock {
        try {
            session()
        } finally {
            // A replacement page must wait for the previous stop before starting a new service.
            withContext(NonCancellable) { stop() }
        }
    }
}
