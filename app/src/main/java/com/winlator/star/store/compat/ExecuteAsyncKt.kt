@file:JvmName("ExecuteAsyncKt")

/**
 * Compatibility shim for okhttp3.coroutines.executeAsync.
 *
 * The okhttp-coroutines artifact was compiled against Kotlin 2.x / coroutines 1.9+
 * which introduced CancellableContinuation.resume(value, onCancellation: Function3).
 * The base APK ships an older coroutines that doesn't have this overload →
 * NoSuchMethodError at runtime.
 *
 * This shim uses suspendCoroutine (kotlin-stdlib, always present) which gives a
 * plain Continuation<T> — no CancellableContinuation, no coroutines-library version
 * dependency, no Kotlin IR optimizer issues with mismatched metadata versions.
 *
 * okhttp-coroutines.jar must be excluded from the runtime DEX bundle; this
 * class (compiled into the Kotlin Steam DEX) is the only provider at runtime.
 */
package okhttp3.coroutines

import kotlin.coroutines.suspendCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import java.io.IOException

@Suppress("RedundantSuspendModifier")
suspend fun Call.executeAsync(): Response = suspendCoroutine { cont ->
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) {
            cont.resumeWith(Result.success(response))
        }
        override fun onFailure(call: Call, e: IOException) {
            cont.resumeWith(Result.failure(e))
        }
    })
}
