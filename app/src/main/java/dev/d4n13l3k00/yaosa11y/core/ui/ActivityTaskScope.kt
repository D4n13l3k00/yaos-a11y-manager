package dev.d4n13l3k00.yaosa11y.core.ui

import android.app.Activity
import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class ActivityTaskScope(private val activity: Activity) : Closeable {
    private val executor = Executors.newSingleThreadExecutor()
    private val closed = AtomicBoolean(false)

    fun execute(work: () -> Unit) {
        if (closed.get()) return
        executor.execute {
            if (!closed.get()) work()
        }
    }

    fun post(action: () -> Unit) {
        if (closed.get()) return
        activity.runOnUiThread {
            if (!closed.get() && !activity.isFinishing && !activity.isDestroyed) action()
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) executor.shutdownNow()
    }
}

fun Activity.postIfAlive(action: () -> Unit) {
    runOnUiThread {
        if (!isFinishing && !isDestroyed) action()
    }
}
