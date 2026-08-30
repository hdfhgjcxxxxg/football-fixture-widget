package com.example.footballfixturewidget

import android.annotation.SuppressLint
import android.content.Context
import android.net.http.HttpEngine
import android.net.http.HttpException
import android.net.http.UrlRequest
import android.net.http.UrlResponseInfo
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Android 14+ platform Chromium HTTP stack. Kept in its own class so older
 * Android versions never need to load android.net.http classes.
 */
@SuppressLint("NewApi")
object PlatformHttpEngineClient {
    private const val UA = "Mozilla/5.0 (Linux; Android 16; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Mobile Safari/537.36"

    @Volatile private var engine: HttpEngine? = null
    private val engineLock = Any()
    private val callbackExecutor: ExecutorService = Executors.newCachedThreadPool()

    fun get(context: Context, url: String): String {
        val done = CountDownLatch(1)
        val output = ByteArrayOutputStream()
        val status = AtomicInteger(-1)
        val failure = AtomicReference<Throwable?>(null)

        val callback = object : UrlRequest.Callback {
            override fun onRedirectReceived(request: UrlRequest, info: UrlResponseInfo, newLocationUrl: String) {
                request.followRedirect()
            }

            override fun onResponseStarted(request: UrlRequest, info: UrlResponseInfo) {
                status.set(info.httpStatusCode)
                request.read(ByteBuffer.allocateDirect(64 * 1024))
            }

            override fun onReadCompleted(request: UrlRequest, info: UrlResponseInfo, byteBuffer: ByteBuffer) {
                byteBuffer.flip()
                val bytes = ByteArray(byteBuffer.remaining())
                byteBuffer.get(bytes)
                output.write(bytes)
                byteBuffer.clear()
                request.read(byteBuffer)
            }

            override fun onSucceeded(request: UrlRequest, info: UrlResponseInfo) {
                done.countDown()
            }

            override fun onFailed(request: UrlRequest, info: UrlResponseInfo?, error: HttpException) {
                failure.set(error)
                done.countDown()
            }

            override fun onCanceled(request: UrlRequest, info: UrlResponseInfo?) {
                failure.set(IllegalStateException("通信がキャンセルされました"))
                done.countDown()
            }
        }

        val request = engine(context).newUrlRequestBuilder(url, callbackExecutor, callback)
            .addHeader("Accept", "application/json,text/plain,*/*")
            .addHeader("Accept-Language", "ja-JP,ja;q=0.9,en-US;q=0.8,en;q=0.7")
            .addHeader("Referer", "https://www.sofascore.com/")
            .addHeader("Origin", "https://www.sofascore.com")
            .addHeader("X-Requested-With", "XMLHttpRequest")
            .addHeader("Sec-Fetch-Dest", "empty")
            .addHeader("Sec-Fetch-Mode", "cors")
            .addHeader("Sec-Fetch-Site", "same-site")
            .addHeader("Cache-Control", "no-cache")
            .build()
        request.start()

        if (!done.await(18, TimeUnit.SECONDS)) {
            request.cancel()
            throw IllegalStateException("HttpEngine: タイムアウト")
        }
        failure.get()?.let { throw it }
        val code = status.get()
        val body = output.toString(Charsets.UTF_8.name())
        if (code !in 200..299) throw IllegalStateException("HttpEngine: HTTP $code")
        return body
    }

    private fun engine(context: Context): HttpEngine {
        engine?.let { return it }
        synchronized(engineLock) {
            engine?.let { return it }
            return HttpEngine.Builder(context.applicationContext)
                .setUserAgent(UA)
                .setEnableHttp2(true)
                .setEnableQuic(true)
                .setEnableBrotli(true)
                .build()
                .also { engine = it }
        }
    }
}
