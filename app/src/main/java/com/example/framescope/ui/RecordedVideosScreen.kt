package com.example.framescope.ui

import android.content.Intent
import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SaveAlt
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.framescope.R
import com.example.framescope.data.RecordedVideo
import com.example.framescope.ui.components.AeroGlassBackground
import com.example.framescope.ui.components.NeumorphicGlassCard
import com.example.framescope.ui.components.NeumorphicIconButton
import com.example.framescope.ui.components.neumorphicShadow
import com.example.framescope.ui.theme.FrameScopeColors
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun RecordedVideosScreen(
    viewModel: HomeViewModel,
    onBack: () -> Unit,
    onPlayVideo: (String) -> Unit
) {
    val context = LocalContext.current
    val recordings by viewModel.recordings.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    var pendingDelete by remember { mutableStateOf<RecordedVideo?>(null) }

    LaunchedEffect(Unit) {
        viewModel.refreshRecordings()
    }

    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            containerColor = FrameScopeColors.BackgroundSecondary,
            title = { Text("Delete Recording?", color = FrameScopeColors.TextPrimary) },
            text = {
                Text(
                    text = "Remove ${target.fileName} from FrameScope storage?",
                    color = FrameScopeColors.TextSecondary
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteVideo(target.filePath)
                        pendingDelete = null
                    }
                ) {
                    Text("Delete", color = FrameScopeColors.RecordRed)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text("Cancel", color = FrameScopeColors.TextSecondary)
                }
            }
        )
    }

    AeroGlassBackground {
        Scaffold(
            containerColor = Color.Transparent,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            snackbarHost = {
                SnackbarHost(
                    hostState = snackbarHostState,
                    modifier = Modifier.navigationBarsPadding()
                )
            },
            topBar = {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    NeumorphicIconButton(
                        onClick = onBack,
                        modifier = Modifier.testTag("videos_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "Back",
                            tint = FrameScopeColors.TextPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(14.dp))
                    Text(
                        text = "${stringResource(R.string.recorded_videos_title)} (${recordings.size})",
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        color = FrameScopeColors.TextPrimary
                    )
                }
            }
        ) { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .navigationBarsPadding(),
                contentAlignment = Alignment.TopCenter
            ) {
                if (recordings.isEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(28.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        NeumorphicGlassCard(
                            modifier = Modifier.widthIn(max = 360.dp),
                            cornerRadius = 26.dp,
                            elevation = 7.dp
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 24.dp, vertical = 32.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(64.dp)
                                        .neumorphicShadow(
                                            cornerRadius = 20.dp,
                                            elevation = 4.dp,
                                            lightColor = FrameScopeColors.NeuShadowLight,
                                            darkColor = FrameScopeColors.NeuShadowDark
                                        )
                                        .clip(RoundedCornerShape(20.dp))
                                        .background(FrameScopeColors.InnerSurface)
                                        .border(1.dp, FrameScopeColors.GlassRimTop, RoundedCornerShape(20.dp)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.VideoLibrary,
                                        contentDescription = null,
                                        tint = FrameScopeColors.SelectionBlue,
                                        modifier = Modifier.size(30.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.height(18.dp))
                                Text(
                                    text = "No Recorded Videos Yet",
                                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                                    color = FrameScopeColors.TextPrimary,
                                    textAlign = TextAlign.Center
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "Tap Record on the home screen to capture an MP4 recording.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = FrameScopeColors.TextSecondary,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .widthIn(max = 760.dp)
                            .padding(horizontal = 18.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        items(recordings, key = { it.filePath }) { video ->
                            RecordedVideoItemCard(
                                video = video,
                                onPlay = { onPlayVideo(video.filePath) },
                                onShare = {
                                    runCatching {
                                        val file = File(video.filePath)
                                        require(file.exists() && file.length() > 0L) {
                                            "Recording file is missing."
                                        }
                                        val uri = FileProvider.getUriForFile(
                                            context,
                                            "${context.packageName}.fileprovider",
                                            file
                                        )
                                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                                            type = "video/mp4"
                                            putExtra(Intent.EXTRA_STREAM, uri)
                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        }
                                        context.startActivity(
                                            Intent.createChooser(shareIntent, "Share Recording")
                                        )
                                    }.onFailure {
                                        coroutineScope.launch {
                                            snackbarHostState.showSnackbar("Unable to share recording")
                                        }
                                    }
                                },
                                onExport = {
                                    coroutineScope.launch {
                                        val ok = viewModel.exportVideoToGallery(video.filePath)
                                        snackbarHostState.showSnackbar(
                                            if (ok) "Saved copy to Movies/FrameScope"
                                            else "Could not export video"
                                        )
                                    }
                                },
                                onDelete = { pendingDelete = video }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RecordedVideoItemCard(
    video: RecordedVideo,
    onPlay: () -> Unit,
    onShare: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit
) {
    NeumorphicGlassCard(
        modifier = Modifier
            .clickable(onClick = onPlay)
            .testTag("recorded_video_card"),
        cornerRadius = 22.dp,
        elevation = 6.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .neumorphicShadow(
                            cornerRadius = 15.dp,
                            elevation = 4.dp,
                            lightColor = FrameScopeColors.NeuShadowLight,
                            darkColor = FrameScopeColors.NeuShadowDark
                        )
                        .clip(RoundedCornerShape(15.dp))
                        .background(
                            brush = Brush.linearGradient(
                                listOf(FrameScopeColors.SelectionBlueLight, FrameScopeColors.SelectionBlue)
                            )
                        )
                        .border(1.dp, Color.White.copy(alpha = 0.45f), RoundedCornerShape(15.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.PlayArrow,
                        contentDescription = "Play video",
                        tint = Color.White,
                        modifier = Modifier.size(26.dp)
                    )
                }

                Spacer(modifier = Modifier.width(14.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = video.fileName,
                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                        color = FrameScopeColors.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        text = buildMetadataSummary(video),
                        style = MaterialTheme.typography.bodyMedium,
                        color = FrameScopeColors.TextSecondary
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically
            ) {
                NeumorphicIconButton(onClick = onExport, size = 40.dp) {
                    Icon(
                        imageVector = Icons.Rounded.SaveAlt,
                        contentDescription = "Save to Movies gallery",
                        tint = FrameScopeColors.TextSecondary,
                        modifier = Modifier.size(18.dp)
                    )
                }
                NeumorphicIconButton(onClick = onShare, size = 40.dp) {
                    Icon(
                        imageVector = Icons.Rounded.Share,
                        contentDescription = "Share recording",
                        tint = FrameScopeColors.SelectionBlue,
                        modifier = Modifier.size(18.dp)
                    )
                }
                NeumorphicIconButton(onClick = onDelete, size = 40.dp) {
                    Icon(
                        imageVector = Icons.Rounded.Delete,
                        contentDescription = "Delete recording",
                        tint = FrameScopeColors.SpecRedText,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

private fun buildMetadataSummary(video: RecordedVideo): String {
    val locale = Locale.getDefault()
    val seconds = (video.durationMs / 1000L).coerceAtLeast(0L)
    val duration = String.format(locale, "%02d:%02d", seconds / 60, seconds % 60)
    val res = if (video.width > 0 && video.height > 0) "${video.width}×${video.height}" else "MP4"
    val sizeMb = String.format(locale, "%.1f MB", video.sizeBytes / (1024.0 * 1024.0))
    val pattern = DateFormat.getBestDateTimePattern(locale, "MMMdHm")
    val date = SimpleDateFormat(pattern, locale).format(Date(video.createdAtMs))
    return "$duration • $res • $sizeMb • $date"
}
