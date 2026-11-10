package com.dalong.pengpaitool.xp

/** Own only these callbacks, including protection against a late delivery after cancellation. */
internal class ScopedCallbacks(
    private val enqueue: (Runnable, Long) -> Boolean,
    private val dequeue: (Runnable) -> Boolean,
    private val active: () -> Boolean,
) {
    private val pending = mutableSetOf<Runnable>()

    fun post(delay: Long, action: () -> Unit) {
        if (!active()) return
        lateinit var task: Runnable
        task = Runnable { if (pending.remove(task) && active()) action() }
        pending += task
        if (!enqueue(task, delay)) pending.remove(task)
    }

    fun clear() {
        pending.forEach { dequeue(it) }
        pending.clear()
    }
}
