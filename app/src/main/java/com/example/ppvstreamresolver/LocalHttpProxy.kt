package com.example.ppvstreamresolver

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

class LocalHttpProxy(private val port: Int = 3000) {

    private var serverSocket: ServerSocket? = null
    private var isRunning = false
    private val client = OkHttpClient.Builder()
        .followRedirects(true)
        .build()

    private val NON_MEDIA_EXT = Regex("\\.(html|php|js|css|svg)(\\?|$)", RegexOption.IGNORE_CASE)
    private val STREAM_EXT = Regex("\\.(m3u8|ts|m4s|mp4)(\\?|$)", RegexOption.IGNORE_CASE)

    fun start() {
        if (isRunning) return
        isRunning = true
        Thread {
            try {
                serverSocket = ServerSocket(port)
                Log.i("LocalHttpProxy", "Local HTTP Proxy started on port $port")
                while (isRunning) {
                    val socket = serverSocket?.accept() ?: break
                    Thread { handleConnection(socket) }.start()
                }
            } catch (e: Exception) {
                Log.e("LocalHttpProxy", "Error in server socket: ${e.message}")
            }
        }.start()
    }

    fun stop() {
        isRunning = false
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            // Ignore
        }
    }

    private fun handleConnection(socket: Socket) {
        try {
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
            val firstLine = reader.readLine() ?: return
            Log.i("LocalHttpProxy", "Request: $firstLine")

            val parts = firstLine.split(" ")
            if (parts.size >= 2 && parts[0] == "GET") {
                val uriString = parts[1]
                if (uriString.startsWith("/api/hls")) {
                    val uri = URI(uriString)
                    val params = parseQueryParams(uri.rawQuery)
                    val targetUrl = params["url"]
                    val embed = params["embed"]
                    val embedOrigin = params["embedOrigin"]

                    if (targetUrl != null && embed != null && embedOrigin != null) {
                        try {
                            proxyRequest(socket, targetUrl, embed, embedOrigin)
                        } catch (e: Exception) {
                            Log.e("LocalHttpProxy", "Proxy request failed", e)
                            send502(socket, e.message ?: "Proxy error")
                        }
                        return
                    }
                }
            }
            send404(socket)
        } catch (e: Exception) {
            Log.e("LocalHttpProxy", "Connection error: ${e.message}")
            try { socket.close() } catch (ex: Exception) {}
        }
    }

    private fun parseQueryParams(query: String?): Map<String, String> {
        val map = mutableMapOf<String, String>()
        if (query == null) return map
        val pairs = query.split("&")
        for (pair in pairs) {
            val idx = pair.indexOf("=")
            if (idx > 0) {
                val key = URLDecoder.decode(pair.substring(0, idx), "UTF-8")
                val value = URLDecoder.decode(pair.substring(idx + 1), "UTF-8")
                map[key] = value
            }
        }
        return map
    }

    private fun proxyRequest(socket: Socket, targetUrl: String, embed: String, embedOrigin: String) {
        val request = Request.Builder()
            .url(targetUrl)
            .header("Referer", "$embedOrigin/embed/$embed")
            .header("Origin", embedOrigin)
            .header("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                send502(socket, "Upstream returned HTTP ${response.code}")
                return
            }

            val contentType = response.header("Content-Type") ?: ""
            val bodyBytes = response.body?.bytes() ?: ByteArray(0)

            val isPlaylist = contentType.contains("mpegurl", true) ||
                    contentType.contains("m3u8", true) ||
                    targetUrl.contains(".m3u8", true)

            if (isPlaylist) {
                val playlistText = String(bodyBytes, StandardCharsets.UTF_8)
                Log.i("LocalHttpProxy", "Serving rewritten playlist for $targetUrl")
                val rewrittenPlaylist = rewritePlaylist(playlistText, targetUrl, embed, embedOrigin)
                sendResponse(socket, "application/vnd.apple.mpegurl", rewrittenPlaylist.toByteArray(StandardCharsets.UTF_8))
            } else {
                try {
                    val cleanSegment = segmentBody(bodyBytes)
                    Log.i("LocalHttpProxy", "Serving clean segment for $targetUrl")
                    sendResponse(socket, "video/mp2t", cleanSegment)
                } catch (e: Exception) {
                    send502(socket, e.message ?: "Segment error")
                }
            }
        }
    }

    private fun abs(uri: String, base: String): String {
        if (uri.startsWith("http://", true) || uri.startsWith("https://", true)) {
            return uri
        }
        return try {
            val baseUrl = java.net.URL(base)
            java.net.URL(baseUrl, uri).toString()
        } catch (e: Exception) {
            uri
        }
    }

    private fun relayUrl(target: String, embed: String, embedOrigin: String): String {
        val urlEncodedTarget = URLEncoder.encode(target, "UTF-8")
        val urlEncodedEmbed = URLEncoder.encode(embed, "UTF-8")
        val urlEncodedOrigin = URLEncoder.encode(embedOrigin, "UTF-8")
        return "http://127.0.0.1:$port/api/hls?url=$urlEncodedTarget&embed=$urlEncodedEmbed&embedOrigin=$urlEncodedOrigin"
    }

    private fun uriLooksLikeVariant(uri: String): Boolean {
        if (Regex("\\.m3u8(\\?|$)", RegexOption.IGNORE_CASE).containsMatchIn(uri)) return true
        if (!uri.startsWith("http://", true) && !uri.startsWith("https://", true)) {
            return !NON_MEDIA_EXT.containsMatchIn(uri)
        }
        return false
    }

    private fun uriLooksLikeMediaSegment(uri: String): Boolean {
        if (NON_MEDIA_EXT.containsMatchIn(uri)) return false
        if (STREAM_EXT.containsMatchIn(uri)) return true
        if (!uri.startsWith("http://", true) && !uri.startsWith("https://", true)) return true
        return !NON_MEDIA_EXT.containsMatchIn(uri)
    }

    private fun shouldProxyPlaylistUri(absUri: String, playlistUrl: String, embedOrigin: String): Boolean {
        if (!absUri.startsWith("http://", true) && !absUri.startsWith("https://", true)) return false
        try {
            val absUrl = java.net.URL(absUri)
            val embedUrl = java.net.URL(embedOrigin)
            if (absUrl.host.equals(embedUrl.host, true)) return false
        } catch (e: Exception) {
            return false
        }
        if (uriLooksLikeMediaSegment(absUri) || uriLooksLikeVariant(absUri)) return true
        try {
            val absUrl = java.net.URL(absUri)
            val playlist = java.net.URL(playlistUrl)
            return absUrl.protocol.equals(playlist.protocol, true) && absUrl.host.equals(playlist.host, true) && absUrl.port == playlist.port
        } catch (e: Exception) {
            return false
        }
    }

    private fun syncLiveMediaPlaylist(text: String): String {
        if (!text.contains("#EXTINF:") || text.contains("#EXT-X-ENDLIST") || text.contains("#EXT-X-STREAM-INF")) {
            return text
        }

        val lines = text.split("\n")
        val header = mutableListOf<String>()
        data class Entry(val extinf: String, val uri: String, val duration: Double)
        val entries = mutableListOf<Entry>()
        var target = 4.0
        var i = 0

        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()
            if (trimmed.startsWith("#EXT-X-TARGETDURATION:")) {
                target = trimmed.substring(22).toDoubleOrNull() ?: target
            }
            if (trimmed.startsWith("#EXTINF:")) {
                val durationStr = trimmed.substring(8).substringBefore(",")
                val duration = durationStr.toDoubleOrNull() ?: 0.0
                if (i + 1 < lines.size) {
                    val uriLine = lines[i + 1]
                    val uriTrimmed = uriLine.trim()
                    if (uriTrimmed.isNotEmpty() && !uriTrimmed.startsWith("#")) {
                        entries.add(Entry(line, uriLine, duration))
                        i += 2
                        continue
                    }
                }
            }
            if (entries.isEmpty()) {
                header.add(line)
            }
            i += 1
        }

        if (entries.isEmpty()) return text

        val min = target * 0.95
        val kept = if (entries.last().duration < min) entries.dropLast(1) else entries
        if (kept.isEmpty()) return text

        val out = mutableListOf<String>()
        out.addAll(header)
        for (entry in kept) {
            out.add(entry.extinf)
            out.add(entry.uri)
        }
        return out.joinToString("\n")
    }

    private fun rewritePlaylist(text: String, playlistUrl: String, embed: String, embedOrigin: String): String {
        val synced = syncLiveMediaPlaylist(text)
        val lines = synced.split("\n")
        val out = mutableListOf<String>()

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) {
                out.add(line)
                continue
            }
            if (trimmed.startsWith("#")) {
                if (trimmed.startsWith("#EXT-X-MAP:")) {
                    val pattern = Regex("URI=\"([^\"]+)\"")
                    val replaced = pattern.replace(trimmed) { matchResult ->
                        val uri = matchResult.groupValues[1]
                        val href = abs(uri, playlistUrl)
                        if (!shouldProxyPlaylistUri(href, playlistUrl, embedOrigin)) {
                            "URI=\"$uri\""
                        } else {
                            "URI=\"${relayUrl(href, embed, embedOrigin)}\""
                        }
                    }
                    out.add(replaced)
                } else {
                    out.add(line)
                }
                continue
            }
            val href = abs(trimmed, playlistUrl)
            if (!shouldProxyPlaylistUri(href, playlistUrl, embedOrigin)) {
                out.add(line)
                continue
            }
            out.add(relayUrl(href, embed, embedOrigin))
        }
        return out.joinToString("\n")
    }

    private fun tsOff(buf: ByteArray): Int {
        val limit = minOf(buf.size, 65536)
        for (i in 0 until limit) {
            if (buf[i] == 0x47.toByte() && i + 188 < buf.size && buf[i + 188] == 0x47.toByte()) {
                return i
            }
        }
        return -1
    }

    private fun strip(buf: ByteArray): ByteArray {
        if (buf.size < 4 || buf[0] == 0x47.toByte()) return buf
        if (buf[0] == 0x89.toByte() && buf[1] == 0x50.toByte() && buf[2] == 0x4E.toByte() && buf[3] == 0x47.toByte()) {
            val iendOffset = findSequence(buf, byteArrayOf(0x49, 0x45, 0x4E, 0x44))
            if (iendOffset >= 0 && iendOffset + 8 < buf.size) {
                val length = buf.size - (iendOffset + 8)
                val out = ByteArray(length)
                System.arraycopy(buf, iendOffset + 8, out, 0, length)
                return out
            }
        }
        val at = tsOff(buf)
        if (at >= 0) {
            val length = buf.size - at
            val out = ByteArray(length)
            System.arraycopy(buf, at, out, 0, length)
            return out
        }
        return buf
    }

    private fun findSequence(array: ByteArray, sequence: ByteArray): Int {
        for (i in 0..array.size - sequence.size) {
            var found = true
            for (j in sequence.indices) {
                if (array[i + j] != sequence[j]) {
                    found = false
                    break
                }
            }
            if (found) return i
        }
        return -1
    }

    private fun segmentBody(body: ByteArray): ByteArray {
        val out = strip(body)
        if (out.size >= 188 && out[0] == 0x47.toByte()) return out
        throw IOException("Invalid segment payload")
    }

    private fun sendResponse(socket: Socket, contentType: String, body: ByteArray) {
        try {
            val out = socket.getOutputStream()
            val header = "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: $contentType\r\n" +
                    "Content-Length: ${body.size}\r\n" +
                    "Access-Control-Allow-Origin: *\r\n" +
                    "Connection: close\r\n\r\n"
            out.write(header.toByteArray(StandardCharsets.UTF_8))
            out.write(body)
            out.flush()
        } finally {
            try { socket.close() } catch (e: Exception) {}
        }
    }

    private fun send502(socket: Socket, message: String) {
        try {
            val out = socket.getOutputStream()
            val body = message.toByteArray(StandardCharsets.UTF_8)
            val header = "HTTP/1.1 502 Bad Gateway\r\n" +
                    "Content-Type: text/plain\r\n" +
                    "Content-Length: ${body.size}\r\n" +
                    "Access-Control-Allow-Origin: *\r\n" +
                    "Connection: close\r\n\r\n"
            out.write(header.toByteArray(StandardCharsets.UTF_8))
            out.write(body)
            out.flush()
        } finally {
            try { socket.close() } catch (e: Exception) {}
        }
    }

    private fun send404(socket: Socket) {
        try {
            val out = socket.getOutputStream()
            val body = "Not Found".toByteArray(StandardCharsets.UTF_8)
            val header = "HTTP/1.1 404 Not Found\r\n" +
                    "Content-Type: text/plain\r\n" +
                    "Content-Length: ${body.size}\r\n" +
                    "Access-Control-Allow-Origin: *\r\n" +
                    "Connection: close\r\n\r\n"
            out.write(header.toByteArray(StandardCharsets.UTF_8))
            out.write(body)
            out.flush()
        } finally {
            try { socket.close() } catch (e: Exception) {}
        }
    }
}
