package com.example.framescope.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.view.Display
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.WbSunny
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.StayCurrentLandscape
import androidx.compose.material.icons.rounded.StayCurrentPortrait
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.framescope.R
import com.example.framescope.data.AppSettings
import com.example.framescope.data.AudioSourceMode
import com.example.framescope.data.RecordingMode
import com.example.framescope.encoding.EncodingForegroundService
import com.example.framescope.encoding.EncodingTab
import com.example.framescope.encoding.RecordingOrientation
import com.example.framescope.encoding.RecordingSessionState
import com.example.framescope.ui.components.SectionCard
import com.example.framescope.ui.components.SelectableOptionCard
import com.example.framescope.ui.theme.FrameScopeColors
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onOpenSettings: () -> Unit,
    onOpenRecordedVideos: () -> Unit
) {
    val context = LocalContext.current
    val view = LocalView.current
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val sessionState by viewModel.sessionState.collectAsStateWithLifecycle()
    val recordings by viewModel.recordings.collectAsStateWithLifecycle()
    val bitrateError by viewModel.bitrateError.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()

    val displayInfo = remember(context) { readDisplayMetrics(context) }
    val targetDimensions = remember(settings, displayInfo) {
        viewModel.computeTargetDimensions(
            currentSettings = settings,
            screenWidth = displayInfo.widthPx,
            screenHeight = displayInfo.heightPx
        )
    }
    val summaryLine = remember(settings, targetDimensions) {
        viewModel.buildSummaryLine(settings, targetDimensions)
    }

    val isRecording = sessionState is RecordingSessionState.Recording
    val isStopping = sessionState is RecordingSessionState.Stopping
    val controlsEnabled = !isRecording && !isStopping

    DisposableEffect(settings.keepScreenAwake) {
        view.keepScreenOn = settings.keepScreenAwake
        onDispose { view.keepScreenOn = false }
    }

    val projectionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val outputPath = viewModel.createOutputFilePath()
            val startIntent = Intent(context, EncodingForegroundService::class.java).apply {
                action = EncodingForegroundService.ACTION_START
                putExtra(EncodingForegroundService.EXTRA_RESULT_CODE, result.resultCode)
                putExtra(EncodingForegroundService.EXTRA_RESULT_DATA, result.data)
                putExtra(EncodingForegroundService.EXTRA_TAB, EncodingTab.H265.name)
                putExtra(EncodingForegroundService.EXTRA_WIDTH, targetDimensions.width)
                putExtra(EncodingForegroundService.EXTRA_HEIGHT, targetDimensions.height)
                putExtra(EncodingForegroundService.EXTRA_DENSITY, displayInfo.densityDpi)
                putExtra(EncodingForegroundService.EXTRA_FRAME_RATE, displayInfo.refreshRateHz)
                putExtra(EncodingForegroundService.EXTRA_BITRATE, settings.effectiveBitrateBps)
                putExtra(EncodingForegroundService.EXTRA_OUTPUT_PATH, outputPath)
                putExtra(EncodingForegroundService.EXTRA_ORIENTATION, settings.orientation.name)
                putExtra(EncodingForegroundService.EXTRA_AUDIO_SOURCE, settings.audioSource.name)
                putExtra(EncodingForegroundService.EXTRA_SUMMARY, summaryLine)
                putExtra(EncodingForegroundService.EXTRA_SHOW_BORDER, false)
                putExtra(EncodingForegroundService.EXTRA_IS_REGION, false)
            }
            runCatching {
                ContextCompat.startForegroundService(context, startIntent)
            }.onFailure { err ->
                coroutineScope.launch {
                    snackbarHostState.showSnackbar(
                        err.message ?: "Unable to start recording service."
                    )
                }
            }
        } else {
            coroutineScope.launch {
                snackbarHostState.showSnackbar("Screen capture permission was cancelled.")
            }
        }
    }

    val launchMediaProjectionPrompt = remember(context, projectionLauncher) {
        {
            val manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                as MediaProjectionManager
            projectionLauncher.launch(manager.createScreenCaptureIntent())
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { grantResults ->
        val audioGranted = grantResults[Manifest.permission.RECORD_AUDIO] == true ||
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED

        if (audioGranted) {
            launchMediaProjectionPrompt()
        } else {
            coroutineScope.launch {
                snackbarHostState.showSnackbar(
                    "Audio permission is required for AAC audio capture."
                )
            }
        }
    }

    fun onRecordButtonClicked() {
        if (isRecording) {
            val stopIntent = Intent(context, EncodingForegroundService::class.java).apply {
                action = EncodingForegroundService.ACTION_STOP
            }
            runCatching {
                context.startService(stopIntent)
            }.onFailure { err ->
                coroutineScope.launch {
                    snackbarHostState.showSnackbar(
                        err.message ?: "Unable to stop recording service."
                    )
                }
            }
            return
        }
        if (isStopping) return

        val missingPermissions = buildList {
            if (ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.RECORD_AUDIO
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                add(Manifest.permission.RECORD_AUDIO)
            }
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (missingPermissions.isNotEmpty()) {
            permissionLauncher.launch(missingPermissions.toTypedArray())
        } else {
            launchMediaProjectionPrompt()
        }
    }

    LaunchedEffect(Unit) {
        viewModel.refreshRecordings()
    }

    if (sessionState is RecordingSessionState.Error) {
        val errorMessage = (sessionState as RecordingSessionState.Error).message
        AlertDialog(
            onDismissRequest = { viewModel.clearRecordingError() },
            containerColor = FrameScopeColors.CardSurface,
            title = { Text("Recording Error", color = FrameScopeColors.TextPrimary) },
            text = { Text(errorMessage, color = FrameScopeColors.TextSecondary) },
            confirmButton = {
                TextButton(onClick = { viewModel.clearRecordingError() }) {
                    Text("OK", color = FrameScopeColors.SelectionBlue)
                }
            }
        )
    }

    Scaffold(
        containerColor = FrameScopeColors.Background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        snackbarHost = {
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.navigationBarsPadding()
            )
        },
        topBar = {
            HomeTopBar(
                recordingsCount = recordings.size,
                keepScreenAwake = settings.keepScreenAwake,
                onToggleKeepAwake = {
                    viewModel.toggleKeepScreenAwake()
                    coroutineScope.launch {
                        val msg = if (!settings.keepScreenAwake) {
                            "Keep screen awake enabled"
                        } else {
                            "Keep screen awake disabled"
                        }
                        snackbarHostState.showSnackbar(msg)
                    }
                },
                onOpenRecordedVideos = onOpenRecordedVideos,
                onOpenSettings = onOpenSettings
            )
        }
    ) { innerPadding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .navigationBarsPadding()
        ) {
            val isWideLayout = maxWidth >= 720.dp
            val scrollState = rememberScrollState()

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = if (isWideLayout) 1040.dp else 600.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    HeroRecordingCard(
                        sessionState = sessionState,
                        summaryLine = summaryLine,
                        onRecordClick = ::onRecordButtonClicked
                    )

                    if (isWideLayout) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(14.dp)
                        ) {
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(14.dp)
                            ) {
                                RecordingModeSection(
                                    enabled = controlsEnabled,
                                    onSelectFullScreen = {
                                        viewModel.setRecordingMode(RecordingMode.FULL_SCREEN)
                                    }
                                )
                                OrientationSection(
                                    selected = settings.orientation,
                                    enabled = controlsEnabled,
                                    onSelect = viewModel::setOrientation
                                )
                            }
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(14.dp)
                            ) {
                                AudioSourceSection(
                                    selected = settings.audioSource,
                                    enabled = controlsEnabled,
                                    onSelect = viewModel::setAudioSource
                                )
                                VideoBitrateSection(
                                    selectedMbps = settings.bitrateMbps,
                                    customInput = settings.customBitrateInput,
                                    bitrateError = bitrateError,
                                    enabled = controlsEnabled,
                                    onSelectPreset = viewModel::selectPresetBitrate,
                                    onCustomChanged = viewModel::updateCustomBitrate
                                )
                            }
                        }
                    } else {
                        RecordingModeSection(
                            enabled = controlsEnabled,
                            onSelectFullScreen = {
                                viewModel.setRecordingMode(RecordingMode.FULL_SCREEN)
                            }
                        )
                        OrientationSection(
                            selected = settings.orientation,
                            enabled = controlsEnabled,
                            onSelect = viewModel::setOrientation
                        )
                        AudioSourceSection(
                            selected = settings.audioSource,
                            enabled = controlsEnabled,
                            onSelect = viewModel::setAudioSource
                        )
                        VideoBitrateSection(
                            selectedMbps = settings.bitrateMbps,
                            customInput = settings.customBitrateInput,
                            bitrateError = bitrateError,
                            enabled = controlsEnabled,
                            onSelectPreset = viewModel::selectPresetBitrate,
                            onCustomChanged = viewModel::updateCustomBitrate
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                }
            }
        }
    }
}

@Composable
private fun HomeTopBar(
    recordingsCount: Int,
    keepScreenAwake: Boolean,
    onToggleKeepAwake: () -> Unit,
    onOpenRecordedVideos: () -> Unit,
    onOpenSettings: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(FrameScopeColors.Background)
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(FrameScopeColors.SpecRedContainer)
                .border(1.dp, FrameScopeColors.SpecRedBorder, RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Rounded.Videocam,
                contentDescription = null,
                tint = FrameScopeColors.RecordRed,
                modifier = Modifier.size(20.dp)
            )
        }

        Spacer(modifier = Modifier.width(10.dp))

        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.titleLarge,
            color = FrameScopeColors.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )

        IconButton(
            onClick = onToggleKeepAwake,
            modifier = Modifier.testTag("keep_awake_button")
        ) {
            Icon(
                imageVector = if (keepScreenAwake) Icons.Rounded.WbSunny else Icons.Outlined.WbSunny,
                contentDescription = "Toggle keep screen awake",
                tint = if (keepScreenAwake) FrameScopeColors.SelectionBlue else FrameScopeColors.TextSecondary
            )
        }

        Box(contentAlignment = Alignment.TopEnd) {
            IconButton(
                onClick = onOpenRecordedVideos,
                modifier = Modifier.testTag("topbar_videos_button")
            ) {
                Icon(
                    imageVector = Icons.Rounded.VideoLibrary,
                    contentDescription = "Open recorded videos",
                    tint = FrameScopeColors.TextSecondary
                )
            }
            if (recordingsCount > 0) {
                Box(
                    modifier = Modifier
                        .offset(x = (-4).dp, y = 4.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(FrameScopeColors.RecordRed)
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = recordingsCount.toString(),
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        ),
                        color = Color.White
                    )
                }
            }
        }

        IconButton(
            onClick = onOpenSettings,
            modifier = Modifier.testTag("encoding_settings_button")
        ) {
            Icon(
                imageVector = Icons.Rounded.Settings,
                contentDescription = "Encoding Settings",
                tint = FrameScopeColors.TextSecondary
            )
        }
    }
}

@Composable
private fun HeroRecordingCard(
    sessionState: RecordingSessionState,
    summaryLine: String,
    onRecordClick: () -> Unit
) {
    val isRecording = sessionState is RecordingSessionState.Recording
    val isStopping = sessionState is RecordingSessionState.Stopping

    val elapsedSeconds = (sessionState as? RecordingSessionState.Recording)?.elapsedSeconds ?: 0L
    val activeSummary = (sessionState as? RecordingSessionState.Recording)?.summaryText ?: summaryLine

    val infiniteTransition = rememberInfiniteTransition(label = "recording_pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 700),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_alpha"
    )

    val statusText = when (sessionState) {
        is RecordingSessionState.Idle,
        is RecordingSessionState.Error -> stringResource(R.string.ready_to_record)
        is RecordingSessionState.Recording -> stringResource(R.string.recording_active)
        is RecordingSessionState.Stopping -> stringResource(R.string.recording_stopping)
    }

    val statusDotColor = when (sessionState) {
        is RecordingSessionState.Recording -> FrameScopeColors.RecordRed.copy(alpha = pulseAlpha)
        is RecordingSessionState.Stopping -> FrameScopeColors.SelectionBlue
        else -> FrameScopeColors.ReadyGreen
    }

    val statusPillBg = when (sessionState) {
        is RecordingSessionState.Recording -> FrameScopeColors.SpecRedContainer
        is RecordingSessionState.Stopping -> FrameScopeColors.SelectionBlueTint
        else -> FrameScopeColors.ReadyGreenBg
    }

    val statusTextColor = when (sessionState) {
        is RecordingSessionState.Recording -> FrameScopeColors.SpecRedText
        is RecordingSessionState.Stopping -> FrameScopeColors.SelectionBlue
        else -> FrameScopeColors.ReadyGreen
    }

    val cardBorder = if (isRecording &&
        (sessionState as RecordingSessionState.Recording).let { it.isRegionMode && it.showRegionBorder }
    ) {
        BorderStroke(2.dp, FrameScopeColors.RecordRed.copy(alpha = pulseAlpha))
    } else {
        BorderStroke(1.dp, FrameScopeColors.BorderSubtle)
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = FrameScopeColors.CardSurface,
        border = cardBorder
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Surface(
                shape = RoundedCornerShape(50),
                color = statusPillBg
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(statusDotColor)
                    )
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp
                        ),
                        color = statusTextColor
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            Text(
                text = formatTimer(elapsedSeconds),
                style = MaterialTheme.typography.headlineLarge.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 40.sp,
                    lineHeight = 46.sp,
                    letterSpacing = 2.sp,
                    fontWeight = FontWeight.Bold
                ),
                color = FrameScopeColors.TextPrimary,
                modifier = Modifier.testTag("recording_timer_text")
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = activeSummary,
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp
                ),
                color = FrameScopeColors.TextSecondary
            )

            Spacer(modifier = Modifier.height(18.dp))

            Button(
                onClick = onRecordClick,
                enabled = !isStopping,
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isRecording) FrameScopeColors.RecordRedDark else FrameScopeColors.RecordRed,
                    contentColor = Color.White,
                    disabledContainerColor = FrameScopeColors.ElevatedSurface
                ),
                contentPadding = PaddingValues(vertical = 15.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .minimumInteractiveComponentSize()
                    .testTag("record_action_button")
            ) {
                if (isRecording) {
                    Icon(
                        imageVector = Icons.Rounded.Stop,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .clip(CircleShape)
                            .background(Color.White)
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = if (isRecording) {
                        stringResource(R.string.action_stop)
                    } else {
                        stringResource(R.string.action_record)
                    },
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold)
                )
            }
        }
    }
}

@Composable
private fun RecordingModeSection(
    enabled: Boolean,
    onSelectFullScreen: () -> Unit
) {
    SectionCard(title = stringResource(R.string.section_recording_mode)) {
        SelectableOptionCard(
            title = stringResource(R.string.mode_full_screen),
            subtitle = stringResource(R.string.mode_full_screen_desc),
            icon = Icons.Rounded.Fullscreen,
            selected = true,
            enabled = enabled,
            testTag = "mode_full_screen_card",
            onClick = onSelectFullScreen,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun OrientationSection(
    selected: RecordingOrientation,
    enabled: Boolean,
    onSelect: (RecordingOrientation) -> Unit
) {
    SectionCard(title = stringResource(R.string.section_orientation)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            SelectableOptionCard(
                title = stringResource(R.string.orientation_portrait),
                subtitle = stringResource(R.string.orientation_portrait_desc),
                icon = Icons.Rounded.StayCurrentPortrait,
                selected = selected == RecordingOrientation.PORTRAIT,
                enabled = enabled,
                testTag = "orientation_portrait_card",
                onClick = { onSelect(RecordingOrientation.PORTRAIT) },
                modifier = Modifier.weight(1f)
            )
            SelectableOptionCard(
                title = stringResource(R.string.orientation_landscape),
                subtitle = stringResource(R.string.orientation_landscape_desc),
                icon = Icons.Rounded.StayCurrentLandscape,
                selected = selected == RecordingOrientation.LANDSCAPE,
                enabled = enabled,
                testTag = "orientation_landscape_card",
                onClick = { onSelect(RecordingOrientation.LANDSCAPE) },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun AudioSourceSection(
    selected: AudioSourceMode,
    enabled: Boolean,
    onSelect: (AudioSourceMode) -> Unit
) {
    SectionCard(title = stringResource(R.string.section_audio_source)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            SelectableOptionCard(
                title = stringResource(R.string.audio_internal),
                subtitle = stringResource(R.string.audio_internal_desc),
                icon = Icons.Rounded.GraphicEq,
                selected = selected == AudioSourceMode.INTERNAL_AUDIO,
                enabled = enabled,
                testTag = "audio_internal_card",
                onClick = { onSelect(AudioSourceMode.INTERNAL_AUDIO) },
                modifier = Modifier.weight(1f)
            )
            SelectableOptionCard(
                title = stringResource(R.string.audio_mic),
                subtitle = stringResource(R.string.audio_mic_desc),
                icon = Icons.Rounded.Mic,
                selected = selected == AudioSourceMode.MICROPHONE,
                enabled = enabled,
                testTag = "audio_mic_card",
                onClick = { onSelect(AudioSourceMode.MICROPHONE) },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun VideoBitrateSection(
    selectedMbps: Int,
    customInput: String,
    bitrateError: String?,
    enabled: Boolean,
    onSelectPreset: (Int) -> Unit,
    onCustomChanged: (String) -> Unit
) {
    SectionCard(
        title = stringResource(R.string.section_video_bitrate),
        trailing = {
            Text(
                text = "${selectedMbps} Mbps",
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                color = FrameScopeColors.SelectionBlue
            )
        }
    ) {
        val rows = AppSettings.PRESET_BITRATES_MBPS.chunked(2)
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            rows.forEach { pair ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    pair.forEach { mbps ->
                        val isPresetActive = customInput.isEmpty() && selectedMbps == mbps
                        BitrateChip(
                            mbps = mbps,
                            selected = isPresetActive,
                            enabled = enabled,
                            onClick = { onSelectPreset(mbps) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(2.dp))

            OutlinedTextField(
                value = customInput,
                onValueChange = onCustomChanged,
                enabled = enabled,
                singleLine = true,
                isError = bitrateError != null,
                placeholder = {
                    Text(
                        text = stringResource(R.string.custom_bitrate_hint),
                        color = FrameScopeColors.TextMuted
                    )
                },
                supportingText = bitrateError?.let { err ->
                    { Text(err, color = FrameScopeColors.SpecRedText) }
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                shape = RoundedCornerShape(12.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = FrameScopeColors.SelectionBlue,
                    unfocusedBorderColor = FrameScopeColors.BorderSubtle,
                    focusedContainerColor = FrameScopeColors.InnerSurface,
                    unfocusedContainerColor = FrameScopeColors.InnerSurface,
                    focusedTextColor = FrameScopeColors.TextPrimary,
                    unfocusedTextColor = FrameScopeColors.TextPrimary
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("custom_bitrate_input")
            )
        }
    }
}

@Composable
private fun BitrateChip(
    mbps: Int,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(12.dp)
    Surface(
        modifier = modifier
            .minimumInteractiveComponentSize()
            .clip(shape)
            .clickable(enabled = enabled, onClick = onClick)
            .testTag("bitrate_chip_$mbps"),
        shape = shape,
        color = if (selected) FrameScopeColors.SelectionBlueTint else FrameScopeColors.InnerSurface,
        border = BorderStroke(
            width = if (selected) 1.5.dp else 1.dp,
            color = if (selected) FrameScopeColors.SelectionBlue else FrameScopeColors.BorderSubtle
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "$mbps Mbps",
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium
                ),
                color = if (selected) FrameScopeColors.SelectionBlue else FrameScopeColors.TextPrimary
            )
        }
    }
}

private data class DeviceDisplayInfo(
    val widthPx: Int,
    val heightPx: Int,
    val densityDpi: Int,
    val refreshRateHz: Int
)

private fun readDisplayMetrics(context: Context): DeviceDisplayInfo {
    val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    val bounds = windowManager.maximumWindowMetrics.bounds
    val densityDpi = context.resources.displayMetrics.densityDpi
    val displayManager = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
    val refreshRate = displayManager?.getDisplay(Display.DEFAULT_DISPLAY)?.refreshRate
        ?.toInt()
        ?.coerceIn(30, 120)
        ?: 60
    return DeviceDisplayInfo(
        widthPx = bounds.width().coerceAtLeast(720),
        heightPx = bounds.height().coerceAtLeast(1280),
        densityDpi = densityDpi.coerceAtLeast(160),
        refreshRateHz = refreshRate
    )
}

private fun formatTimer(totalSeconds: Long): String {
    val hrs = totalSeconds / 3600
    val mins = (totalSeconds % 3600) / 60
    val secs = totalSeconds % 60
    return String.format("%02d:%02d:%02d", hrs, mins, secs)
}
