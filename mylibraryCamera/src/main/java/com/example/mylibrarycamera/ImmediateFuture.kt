package com.example.mylibrarycamera

import android.util.Log
import androidx.annotation.RequiresApi
import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.Delayed
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/*
* Copyright 2019 The Android Open Source Project
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
*      http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/

/**
 * An implementation of [ListenableFuture] which immediately contains a result.
 *
 *
 * This implementation is based off of the Guava ImmediateSuccessfulFuture class.
 * @param <V> The type of the value stored in the future.
</V> */
@RequiresApi(21) // TODO(b/200306659): Remove and replace with annotation on package-info.java

internal abstract class ImmediateFuture<V> :
    ListenableFuture<V> {
    override fun addListener(listener: Runnable, executor: Executor) {
        checkNotNull(listener)
        checkNotNull(executor)
        try {
            executor.execute(listener)
        } catch (e: RuntimeException) {
            // ListenableFuture does not throw runtime exceptions, so swallow the exception and
            // log it here.
            Log.e(
                TAG,
                "Experienced RuntimeException while attempting to notify " + listener
                        + " on Executor " + executor, e
            )
        }
    }

    /**
     * Ensures that an object reference passed as a parameter to the calling
     * method is not null.
     *
     * @param reference an object reference
     * @return the non-null reference that was validated
     * @throws NullPointerException if `reference` is null
     */
    open fun <T> checkNotNull(reference: T?): T {
        if (reference == null) {
            throw NullPointerException()
        }
        return reference
    }
    override fun cancel(mayInterruptIfRunning: Boolean): Boolean {
        return false
    }

    override fun isCancelled(): Boolean {
        return false
    }

    override fun isDone(): Boolean {
        return true
    }

    @Throws(ExecutionException::class)
    abstract override fun get(): V?

    @Throws(ExecutionException::class)
    override fun get(timeout: Long, unit: TimeUnit): V? {
        checkNotNull(unit)
        return get()
    }

    internal class ImmediateSuccessfulFuture<V>(private val mValue: V?) :
        com.example.mylibrarycamera.ImmediateFuture<V>() {
        override fun get(): V? {
            return mValue
        }

        override fun toString(): String {
            // Behaviour analogous to AbstractResolvableFuture#toString().
            return super.toString() + "[status=SUCCESS, result=[" + mValue + "]]"
        }

        companion object {
            val NULL_FUTURE: com.example.mylibrarycamera.ImmediateFuture<Any> = ImmediateSuccessfulFuture(null)
        }
    }

    internal open class ImmediateFailedFuture<V>(private val mCause: Throwable) :
        com.example.mylibrarycamera.ImmediateFuture<V>() {
        @Throws(ExecutionException::class)
        override fun get(): V? {
            throw ExecutionException(mCause)
        }

        override fun toString(): String {
            // Behaviour analogous to AbstractResolvableFuture#toString().
            return super.toString() + "[status=FAILURE, cause=[" + mCause + "]]"
        }
    }

    internal class ImmediateFailedScheduledFuture<V>(cause: Throwable) :
        ImmediateFailedFuture<V>(cause),
        ScheduledFuture<V> {
        override fun getDelay(timeUnit: TimeUnit): Long {
            return 0
        }

        override fun compareTo(delayed: Delayed): Int {
            return -1
        }
    }

    companion object {
        private const val TAG = "ImmediateFuture"

        /**
         * Returns a future that contains a null value.
         *
         *
         * This should be used any time a null value is needed as it uses a static ListenableFuture
         * that contains null, and thus will not allocate.
         */
        fun <V> nullFuture(): com.example.mylibrarycamera.ImmediateFuture<Any> {
            return ImmediateSuccessfulFuture.Companion.NULL_FUTURE
        }
    }
}