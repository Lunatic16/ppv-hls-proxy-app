package com.example.ppvstreamresolver

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewAssetLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.nio.charset.StandardCharsets
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class StreamResolver(private val context: Context) {

    private val client = OkHttpClient.Builder().build()
    private val mainHandler = Handler(Looper.getMainLooper())

    private val userAgent = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    data class DecryptResult(
        val streamUrl: String,
        val embedPath: String,
        val embedOrigin: String
    )

    private fun encodeFetchBody(path: String): ByteArray {
        val pathBytes = path.toByteArray(StandardCharsets.UTF_8)
        if (pathBytes.size < 128) {
            val body = ByteArray(2 + pathBytes.size)
            body[0] = 0x0a.toByte()
            body[1] = pathBytes.size.toByte()
            System.arraycopy(pathBytes, 0, body, 2, pathBytes.size)
            return body
        } else {
            val lenVarint = mutableListOf<Byte>()
            var temp = pathBytes.size
            while (temp > 0) {
                var byte = (temp and 0x7F).toByte()
                temp = temp ushr 7
                if (temp > 0) {
                    byte = (byte.toInt() or 0x80).toByte()
                }
                lenVarint.add(byte)
            }
            val body = ByteArray(1 + lenVarint.size + pathBytes.size)
            body[0] = 0x0a.toByte()
            for (i in lenVarint.indices) {
                body[1 + i] = lenVarint[i]
            }
            System.arraycopy(pathBytes, 0, body, 1 + lenVarint.size, pathBytes.size)
            return body
        }
    }

    suspend fun resolve(iframeUrl: String): DecryptResult = withContext(Dispatchers.IO) {
        val parsedUrl = java.net.URL(iframeUrl)
        val origin = "${parsedUrl.protocol}://${parsedUrl.authority}"
        val path = parsedUrl.path.removePrefix("/embed/")

        Log.d("StreamResolver", "Handshake with origin: $origin, path: $path")

        // 1. Perform protobuf fetch handshake
        val bodyBytes = encodeFetchBody(path)
        val request = Request.Builder()
            .url("$origin/fetch")
            .post(bodyBytes.toRequestBody("application/octet-stream".toMediaType()))
            .header("Origin", origin)
            .header("Referer", "$origin/embed/$path")
            .header("User-Agent", userAgent)
            .build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            throw IOException("Handshake request failed: ${response.code} ${response.message}")
        }

        val island = response.header("island") ?: throw IOException("Missing island header in handshake response")
        val responseBodyBytes = response.body?.bytes() ?: throw IOException("Empty handshake body response")

        Log.d("StreamResolver", "Handshake success. Island: $island, Body size: ${responseBodyBytes.size}")

        // 2. Run decryption in WebView on Main thread
        val decryptedUrl = runWebViewDecryption(island, responseBodyBytes, path, origin)
        DecryptResult(decryptedUrl, path, origin)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun runWebViewDecryption(
        island: String,
        bodyBytes: ByteArray,
        embedPath: String,
        embedOrigin: String
    ): String = suspendCancellableCoroutine { continuation ->
        mainHandler.post {
            try {
                val webView = WebView(context)
                val bodyBase64 = Base64.encodeToString(bodyBytes, Base64.NO_WRAP)

                val assetLoader = WebViewAssetLoader.Builder()
                    .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
                    .build()

                webView.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView,
                        request: WebResourceRequest
                    ): WebResourceResponse? {
                        return assetLoader.shouldInterceptRequest(request.url)
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        Log.d("StreamResolver", "WebView finished loading page. Triggering decrypt...")
                        // Trigger decrypt function on window
                        val jsCode = "decryptStream('$island', '$bodyBase64', '$embedPath', '$embedOrigin', '')"
                        webView.evaluateJavascript(jsCode, null)
                    }
                }

                webView.settings.javaScriptEnabled = true
                webView.settings.allowFileAccess = false
                webView.settings.allowContentAccess = false

                val bridge = object {
                    @JavascriptInterface
                    fun onDecrypted(url: String) {
                        Log.d("StreamResolver", "Decryption callback success: $url")
                        mainHandler.post {
                            if (continuation.isActive) {
                                continuation.resume(url)
                            }
                            webView.destroy()
                        }
                    }

                    @JavascriptInterface
                    fun onError(error: String) {
                        Log.e("StreamResolver", "Decryption callback failed: $error")
                        mainHandler.post {
                            if (continuation.isActive) {
                                continuation.resumeWithException(Exception(error))
                            }
                            webView.destroy()
                        }
                    }
                }

                webView.addJavascriptInterface(bridge, "KotlinBridge")
                // Load HTML sandboxed page using appassets URL mapping (supports CORS for relative fetch('gasm.wasm'))
                webView.loadUrl("https://appassets.androidplatform.net/assets/decrypt.html")

                continuation.invokeOnCancellation {
                    mainHandler.post {
                        webView.destroy()
                    }
                }
            } catch (e: Exception) {
                if (continuation.isActive) {
                    continuation.resumeWithException(e)
                }
            }
        }
    }
}
