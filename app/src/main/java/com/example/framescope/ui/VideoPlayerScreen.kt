package com.example.framescope.ui

import android.net.Uri
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.compose.PlayerSurface
import androidx.media3.ui.compose.SURFACE_TYPE_TEXTURE_VIEW
import com.example.framescope.ui.theme.FrameScopeColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.roundToLong

@OptIn(UnstableApi::class)
@Composable
fun VideoPlayerScreen(
    filePath: String,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val file = remember(filePath) { File(filePath) }
    val fileName = file.name
    val lifecycleOwner = LocalLifecycleOwner.current

    var isLoading by remember(filePath) { mutableStateOf(true) }
    var isCompleted by remember(filePath) { mutableStateOf(false) }
    var isPlaying by remember(filePath) { mutableStateOf(true) }
    var durationMs by remember(filePath) { mutableLongStateOf(0L) }
    var currentPosMs by remember(filePath) { mutableLongStateOf(0L) }
    var isScrubbing by remember(filePath) { mutableStateOf(false) }
    var videoAspectRatio by remember(filePath) { mutableFloatStateOf(16f / 9f) }
    var playbackError by remember(filePath) {
        mutableStateOf(
            if (!file.exists() || file.length() <= 0L) {
                "Recording file is missing or empty."
            } else {
                null
            }
        )
    }

    val exoPlayer = remember(filePath) {
        if (file.exists() && file.length() > 0L) {
            runCatching {
                val renderersFactory = DefaultRenderersFactory(context).apply {
                    setEnableDecoderFallback(true)
                }
                ExoPlayer.Builder(context, renderersFactory).build().apply {
                    repeatMode = Player.REPEAT_MODE_OFF
                    setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
                    prepare()
                    playWhenReady = true
                }
            }.onFailure { err ->
                isLoading = false
                playbackError = err.message ?: "Unable to initialize player."
            }.getOrNull()
        } else {
            null
        }
    }

    DisposableEffect(exoPlayer) {
        val player = exoPlayer
        val listener = if (player != null) {
            object : Player.Listener {
                override fun onVideoSizeChanged(videoSize: VideoSize) {
                    if (videoSize.width > 0 && videoSize.height > 0) {
                        val par = videoSize.pixelWidthHeightRatio
                            .takeIf { it.isFinite() && it > 0f } ?: 1f
                        videoAspectRatio = ((videoSize.width * par) / videoSize.height.toFloat())
                            .coerceIn(0.2f, 5f)
                    }
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    when (playbackState) {
                        Player.STATE_READY -> {
                            isLoading = false
                            val dur = player.duration
                            if (dur != C.TIME_UNSET && dur > 0L) {
                                durationMs = dur
                            }
                        }

                        Player.STATE_ENDED -> {
                            isLoading = false
                            isCompleted = true
                            isPlaying = false
                            currentPosMs = durationMs
                        }

                        else -> Unit
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    isLoading = false
                    isPlaying = false
                    playbackError = error.localizedMessage ?: "Playback failed (${error.errorCodeName})."
                }
            }.also { player.addListener(it) }
        } else {
            null
        }

        onDispose {
            if (player != null) {
                if (listener != null) {
                    player.removeListener(listener)
                }
                runCatching { player.release() }
            }
        }
    }

    DisposableEffect(lifecycleOwner, exoPlayer) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) {
                isPlaying = false
                exoPlayer?.playWhenReady = false
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(exoPlayer, isPlaying) {
        exoPlayer?.playWhenReady = isPlaying
    }

    LaunchedEffect(exoPlayer, isPlaying, isScrubbing) {
        val player = exoPlayer ?: return@LaunchedEffect
        if (!isPlaying || isScrubbing) return@LaunchedEffect
        while (isActive) {
            currentPosMs = player.currentPosition.coerceAtLeast(0L)
            delay(100L)
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
                if (exoPlayer != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) {
                                if (isCompleted) {
                                    isCompleted = false
                                    exoPlayer.seekTo(0L)
                                    isPlaying = true
                                } else {
                                    isPlaying = !isPlaying
                                }
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        PlayerSurface(
                            player = exoPlayer,
                            surfaceType = SURFACE_TYPE_TEXTURE_VIEW,
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(videoAspectRatio)
                                .testTag("native_video_player")
                        )
                    }
                }

                if (isLoading) {
                    CircularProgressIndicator(
                        color = FrameScopeColors.SelectionBlue
                    )
                } else if (isCompleted) {
                    FilledTonalButton(
                        onClick = {
                            isCompleted = false
                            exoPlayer?.seekTo(0L)
                            isPlaying = true
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

                if (durationMs > 0L) {
                    Row(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .background(Color(0xA6070A12))
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        IconButton(
                            onClick = {
                                if (isCompleted) {
                                    isCompleted = false
                                    exoPlayer?.seekTo(0L)
                                    isPlaying = true
                                } else {
                                    isPlaying = !isPlaying
                                }
                            }
                        ) {
                            Icon(
                                imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                contentDescription = if (isPlaying) "Pause" else "Play",
                                tint = Color.White
                            )
                        }
                        Slider(
                            value = currentPosMs.coerceIn(0L, durationMs).toFloat(),
                            onValueChange = { value ->
                                isScrubbing = true
                                isCompleted = false
                                val target = value.roundToLong().coerceIn(0L, durationMs)
                                currentPosMs = target
                                exoPlayer?.seekTo(target)
                            },
                            onValueChangeFinished = {
                                isScrubbing = false
                            },
                            valueRange = 0f..durationMs.toFloat(),
                            colors = SliderDefaults.colors(
                                thumbColor = FrameScopeColors.SelectionBlue,
                                activeTrackColor = FrameScopeColors.SelectionBlue
                            ),
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = formatDurationLabel(currentPosMs) + " / " + formatDurationLabel(durationMs),
                            style = MaterialTheme.typography.labelSmall.copy(
                                fontFamily = FontFamily.Default,
                                fontFeatureSettings = "tnum"
                            ),
                            color = Color.White
                        )
                    }
                }
            }
        }
    }
}

private fun formatDurationLabel(ms: Long): String {
    val totalSec = (ms / 1000L).coerceAtLeast(0L)
    val mins = totalSec / 60L
    val secs = totalSec % 60L
    return String.format(Locale.US, "%02d:%02d", mins, secs)
}
