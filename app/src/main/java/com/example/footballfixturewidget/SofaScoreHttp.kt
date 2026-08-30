package com.example.footballfixturewidget

import com.google.android.gms.net.CronetProviderInstaller
import com.google.android.gms.tasks.Tasks
import org.chromium.net.CronetEngine
import org.chromium.net.CronetException
import org.chromium.net.UrlRequest
import org.chromium.net.UrlResponseInfo
import org.json.JSONTokener
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * SofaScore transport using Chromium's network stack (Cronet) instead of
 * HttpURLConnection. SofaScore currently returns HTTP 403 to some Android/Java
 * TLS fingerprints even when browser-like headers are added. Cronet uses the
 * Chromium network stack shipped by Google Play services and therefore follows
 * the same HTTP/2/TLS path as Chrome much more closely.
 */
object SofaScoreHttp {
    private const val API_BASE = "https://api.sofascore.com/api/v1"
    private const val WWW_BASE = "https://www.sofascore.com/api/v1"
    private const val UA = "Mozilla/5.0 (Linux; Android 16; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Mobile Safari/537.36"

    private val callbackExecutor = Executors.newCachedThreadPool()
    @Volatile private var cronet: CronetEngine? = null
    private val engineLock = Any()

    fun getAny(urlOrPath: String): Any {
        var last: Throwable? = null
        for (url in candidates(urlOrPath)) {
            try {
                val body = execute(url)
                if (body.isBlank()) throw IllegalStateException("${hostLabel(url)}: 空のレスポンス")
                return JSONTokener(body).nextValue()
            } catch (t: Throwable) {
                last = t
            }
        }
        throw last ?: IllegalStateException("SofaScoreデータを取得できませんでした")
    }

    private fun candidates(input: String): List<String> {
        val path = when {
            input.startsWith("https://api.sofascore.com/api/v1") -> input.removePrefix("https://api.sofascore.com/api/v1")
            input.startsWith("https://www.sofascore.com/api/v1") -> input.removePrefix("https://www.sofascore.com/api/v1")
            input.startsWith("/api/v1/") -> input.removePrefix("/api/v1")
            input.startsWith("/") -> input
            input.startsWith("http://") || input.startsWith("https://") -> return listOf(input)
            else -> "/$input"
        }
        // Official API first. The www path is retained only as a compatibility
        // fallback; unlike v11.7 we do not use unverified third-party mirrors.
        return listOf("$API_BASE$path", "$WWW_BASE$path").distinct()
    }

    private fun execute(url: String): String {
        val engine = engine()
        val done = CountDownLatch(1)
        val output = ByteArrayOutputStream()
        val status = AtomicInteger(-1)
        val failure = AtomicReference<Throwable?>(null)

        val callback = object : UrlRequest.Callback() {
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

            override fun onFailed(request: UrlRequest, info: UrlResponseInfo?, error: CronetException) {
                failure.set(error)
                done.countDown()
            }

            override fun onCanceled(request: UrlRequest, info: UrlResponseInfo?) {
                failure.set(IllegalStateException("通信がキャンセルされました"))
                done.countDown()
            }
        }

        val request = engine.newUrlRequestBuilder(url, callback, callbackExecutor)
            .addHeader("Accept", "application/json,text/plain,*/*")
            .addHeader("Accept-Language", "ja-JP,ja;q=0.9,en-US;q=0.8,en;q=0.7")
            .addHeader("Referer", "https://www.sofascore.com/")
            .addHeader("Origin", "https://www.sofascore.com")
            .addHeader("X-Requested-With", "XMLHttpRequest")
            .addHeader("Cache-Control", "no-cache")
            .build()
        request.start()

        if (!done.await(15, TimeUnit.SECONDS)) {
            request.cancel()
            throw IllegalStateException("${hostLabel(url)}: タイムアウト")
        }
        failure.get()?.let { throw it }
        val code = status.get()
        val body = output.toString(Charsets.UTF_8.name())
        if (code !in 200..299) {
            throw IllegalStateException("${hostLabel(url)}: HTTP $code")
        }
        return body
    }

    private fun engine(): CronetEngine {
        cronet?.let { return it }
        synchronized(engineLock) {
            cronet?.let { return it }
            val context = MatchDayApplication.appContext
            try {
                Tasks.await(CronetProviderInstaller.installProvider(context), 12, TimeUnit.SECONDS)
            } catch (t: Throwable) {
                throw IllegalStateException("Cronetを初期化できません。Google Play開発者サービスを更新してください。", t)
            }
            return CronetEngine.Builder(context)
                .setUserAgent(UA)
                .enableHttp2(true)
                .enableQuic(true)
                .enableBrotli(true)
                .build()
                .also { cronet = it }
        }
    }

    private fun hostLabel(url: String): String = when {
        url.contains("api.sofascore.com") -> "api.sofascore.com(Cronet)"
        url.contains("www.sofascore.com") -> "www.sofascore.com(Cronet)"
        else -> url
    }
}
