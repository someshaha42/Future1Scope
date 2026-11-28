package com.example.framescope.ui

import android.media.AudioManager
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.framescope.ui.theme.FrameScopeColors
import java.io.File

@Composable
fun VideoPlayerScreen(
    filePath: String,
    onBack: () -> Unit
) {
    val file = remember(filePath) { File(filePath) }
    val fileName = file.name
    val lifecycleOwner = LocalLifecycleOwner.current

    var isLoading by remember(filePath) { mutableStateOf(true) }
    var isCompleted by remember(filePath) { mutableStateOf(false) }
    var playbackError by remember(filePath) {
        mutableStateOf(
            if (!file.exists() || file.length() <= 0L) {
                "Recording file is missing or empty."
            } else {
                null
            }
        )
    }
    var videoViewRef by remember { mutableStateOf<VideoView?>(null) }

    DisposableEffect(lifecycleOwner, videoViewRef) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) {
                runCatching {
                    if (videoViewRef?.isPlaying == true) {
                        videoViewRef?.pause()
                    }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    DisposableEffect(filePath) {
        onDispose {
            runCatching { videoViewRef?.stopPlayback() }
            videoViewRef = null
        }
    }

    Scaffold(
        containerColor = Color(0xFF070A12),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0x990D1424))
                    .statusBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.testTag("player_back_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = "Back",
                        tint = Color.White
                    )
                }
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = fileName,
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .navigationBarsPadding()
                .background(FrameScopeColors.Background),
            contentAlignment = Alignment.Center
        ) {
            if (playbackError != null) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.ErrorOutline,
                        contentDescription = null,
                        tint = FrameScopeColors.SpecRedText,
                        modifier = Modifier.size(40.dp)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = playbackError ?: "Unable to play video.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = FrameScopeColors.TextSecondary
                    )
                }
            } else {
                AndroidView(
                    factory = { ctx ->
                        FrameLayout(ctx).apply {
                            val container = this
                            val vv = VideoView(ctx).apply {
                                val controller = MediaController(ctx)
                                controller.setAnchorView(container)
                                setMediaController(controller)
                                setAudioFocusRequest(AudioManager.AUDIOFOCUS_GAIN)
                                setVideoPath(filePath)
                                setOnPreparedListener { mp ->
                                    isLoading = false
                                    isCompleted = false
                                    mp.isLooping = false
                                    start()
                                }
                                setOnCompletionListener {
                                    isCompleted = true
                                }
                                setOnErrorListener { _, what, extra ->
                                    isLoading = false
                                    playbackError = "Playback failed (code $what/$extra)."
                                    true
                                }
                            }
                            videoViewRef = vv
                            addView(
                                vv,
                                FrameLayout.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    Gravity.CENTER
                                )
                            )
                        }
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("native_video_player")
                )

                if (isLoading) {
                    CircularProgressIndicator(
                        color = FrameScopeColors.SelectionBlue
                    )
                } else if (isCompleted) {
                    FilledTonalButton(
                        onClick = {
                            isCompleted = false
                            videoViewRef?.seekTo(0)
                            videoViewRef?.start()
                        },
                        modifier = Modifier.testTag("player_replay_button")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Replay,
                            contentDescription = "Replay"
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Replay")
                    }
                }
            }
        }
    }
}
