package com.example.ppvstreamresolver.ui.player

import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.example.ppvstreamresolver.StreamResolver
import java.net.URLEncoder

@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(
    iframeUrl: String,
    title: String,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var streamUrl by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(true) }

    val resolver = remember { StreamResolver(context) }

    LaunchedEffect(iframeUrl) {
        try {
            val result = resolver.resolve(iframeUrl)
            val proxiedUrl = "http://127.0.0.1:3000/api/hls?url=${URLEncoder.encode(result.streamUrl, "UTF-8")}&embed=${URLEncoder.encode(result.embedPath, "UTF-8")}&embedOrigin=${URLEncoder.encode(result.embedOrigin, "UTF-8")}"
            streamUrl = proxiedUrl
            isLoading = false
        } catch (e: Exception) {
            error = e.message ?: "Failed to resolve stream"
            isLoading = false
        }
    }

    Box(modifier = modifier.fillMaxSize().background(Color.Black)) {
        if (isLoading) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
        } else if (error != null) {
            Text(
                text = error!!,
                color = Color.White,
                modifier = Modifier.align(Alignment.Center)
            )
        } else if (streamUrl != null) {
            VideoPlayer(streamUrl = streamUrl!!)
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
fun VideoPlayer(streamUrl: String) {
    val context = LocalContext.current
    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            val mediaItem = MediaItem.Builder()
                .setUri(streamUrl)
                .setMimeType(MimeTypes.APPLICATION_M3U8)
                .build()
            setMediaItem(mediaItem)
            prepare()
            playWhenReady = true
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            exoPlayer.release()
        }
    }

    AndroidView(
        factory = {
            PlayerView(context).apply {
                player = exoPlayer
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }
        },
        modifier = Modifier.fillMaxSize()
    )
}
