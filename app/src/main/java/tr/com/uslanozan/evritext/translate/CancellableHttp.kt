package tr.com.uslanozan.evritext.translate

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume

/** An OkHttp request whose underlying network call stops when its coroutine is cancelled. */
internal suspend fun OkHttpClient.await(request: Request): Response =
    suspendCancellableCoroutine { continuation ->
        val call = newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, error: IOException) {
                continuation.resumeWith(Result.failure(error))
            }

            override fun onResponse(call: Call, response: Response) {
                continuation.resume(response) { _, undelivered, _ -> undelivered.close() }
            }
        })
    }
