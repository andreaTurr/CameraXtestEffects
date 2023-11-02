package com.example.mylibrarycamera

import android.os.Handler
import android.os.SystemClock
import androidx.annotation.RequiresApi
import androidx.camera.core.impl.utils.executor.CameraXExecutors
import androidx.concurrent.futures.CallbackToFutureAdapter
import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.Callable
import java.util.concurrent.Delayed
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.RunnableScheduledFuture
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference

class MyHandlerScheduledExecutorService(private val mHandler: Handler) : AbstractExecutorService(),
    ScheduledExecutorService {
    override fun execute(command: Runnable?) {
        if (!mHandler.post(command!!)) {
            throw createPostFailedException()
        }
    }
    private fun createPostFailedException(): Throwable {
        return RejectedExecutionException("$mHandler is shutting down")
    }
    override fun shutdown() {
        throw UnsupportedOperationException(
            this::class.java.simpleName
                    + " cannot be shut down. Use Looper.quitSafely()."
        )
    }

    override fun shutdownNow(): MutableList<Runnable> {
        throw UnsupportedOperationException(
            this::class.java.simpleName
                    + " cannot be shut down. Use Looper.quitSafely()."
        )
    }

    override fun isShutdown(): Boolean {
        return false
    }

    override fun isTerminated(): Boolean {
        return false
    }

    override fun awaitTermination(p0: Long, p1: TimeUnit?): Boolean {
        throw UnsupportedOperationException(
            this::class.java.simpleName
                    + " cannot be shut down. Use Looper.quitSafely()."
        )
    }

    override fun schedule(
        command: Runnable,
        delay: Long,
        unit: TimeUnit
    ): ScheduledFuture<*>? {
        val wrapper: Callable<Void> = Callable {
            command.run()
            null
        }
        return schedule(wrapper, delay, unit)
    }

    override fun <V : Any?> schedule(
        p0: Callable<V>?,
        p1: Long,
        p2: TimeUnit?
    ): ScheduledFuture<V> {
        TODO("Not yet implemented")
    }

    override fun <V> schedule(
        callable: Callable<V>,
        delay: Long,
        unit: TimeUnit
    ): ScheduledFuture<V> {
        val runAtMillis = SystemClock.uptimeMillis() + TimeUnit.MILLISECONDS.convert(delay, unit)
        val future = HandlerScheduledFuture(
            mHandler, runAtMillis,
            callable
        )
        return if (mHandler.postAtTime(future, runAtMillis)) {
            future
        } else immediateFailedScheduledFuture(createPostFailedException())
    }

    private fun <V> immediateFailedScheduledFuture(cause: Throwable): ScheduledFuture<V> {
        return com.example.mylibrarycamera.ImmediateFuture.ImmediateFailedScheduledFuture(cause)
    }

    override fun scheduleAtFixedRate(
        p0: Runnable?,
        p1: Long,
        p2: Long,
        p3: TimeUnit?
    ): ScheduledFuture<*> {
        throw java.lang.UnsupportedOperationException(
            this::class.java.simpleName
                    + " does not yet support fixed-delay scheduling."
        )
    }


    override fun scheduleWithFixedDelay(
        command: Runnable, initialDelay: Long,
        delay: Long, unit: TimeUnit
    ): ScheduledFuture<*> {
        throw java.lang.UnsupportedOperationException(
            this::class.java.simpleName
                    + " does not yet support fixed-delay scheduling."
        )
    }

    private class HandlerScheduledFuture<V> internal constructor(
        handler: Handler,
        private val mRunAtMillis: Long,
        private val mTask: Callable<V>
    ) :
        RunnableScheduledFuture<V> {
        val mCompleter: AtomicReference<CallbackToFutureAdapter.Completer<V>?> =
            AtomicReference(null)
        private val mDelegate: ListenableFuture<V>

        init {
            mDelegate = CallbackToFutureAdapter.getFuture<V> { completer ->
                DirectExecutor.instance?.let {
                    completer.addCancellationListener(Runnable { // Remove the completer if we're cancelled so the task won't
                        // run.
                        if (mCompleter.getAndSet(null) != null) {
                            handler.removeCallbacks(this@HandlerScheduledFuture)
                        }
                    }, it)
                }
                mCompleter.set(completer)
                "HandlerScheduledFuture-$mTask"
            }
        }

        override fun isPeriodic(): Boolean {
            return false
        }

        override fun getDelay(unit: TimeUnit): Long {
            return unit.convert(
                mRunAtMillis - System.currentTimeMillis(),
                TimeUnit.MILLISECONDS
            )
        }

        override fun compareTo(o: Delayed): Int {
            return getDelay(TimeUnit.MILLISECONDS).compareTo(o.getDelay(TimeUnit.MILLISECONDS))
        }

        override fun run() {
            // If completer is null, it has already run or is cancelled.
            val completer: androidx.concurrent.futures.CallbackToFutureAdapter.Completer<V>? =
                mCompleter.getAndSet(null)
            if (completer != null) {
                try {
                    completer.set(mTask.call())
                } catch (e: Exception) {
                    completer.setException(e)
                }
            }
        }

        override fun cancel(mayInterruptIfRunning: Boolean): Boolean {
            return mDelegate.cancel(mayInterruptIfRunning)
        }

        override fun isCancelled(): Boolean {
            return mDelegate.isCancelled
        }

        override fun isDone(): Boolean {
            return mDelegate.isDone
        }

        @Throws(ExecutionException::class, InterruptedException::class)
        override fun get(): V {
            return mDelegate.get()
        }

        @Throws(
            ExecutionException::class,
            InterruptedException::class,
            TimeoutException::class
        )
        override fun get(timeout: Long, unit: TimeUnit): V {
            return mDelegate[timeout, unit]
        }
    }
    @RequiresApi(21) // TODO(b/200306659): Remove and replace with annotation on package-info.java

    internal class DirectExecutor : Executor {
        override fun execute(command: Runnable) {
            command.run()
        }

        companion object {
            @Volatile
            private var sDirectExecutor: DirectExecutor? = null
            val instance: Executor?
                get() {
                    if (sDirectExecutor != null) {
                        return sDirectExecutor
                    }
                    synchronized(DirectExecutor::class.java) {
                        if (sDirectExecutor == null) {
                            sDirectExecutor = DirectExecutor()
                        }
                    }
                    return sDirectExecutor
                }
        }
    }
}


