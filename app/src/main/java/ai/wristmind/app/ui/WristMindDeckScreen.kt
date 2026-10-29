package ai.wristmind.app.ui

import ai.wristmind.app.agent.PendingWristConfirmation
import ai.wristmind.app.cloud.LiveBurstStatus
import ai.wristmind.app.data.memory.HybridMemoryResult
import ai.wristmind.app.edge.DeviceMemoryTelemetry
import ai.wristmind.app.edge.EdgeModelSpec
import ai.wristmind.app.edge.GalleryRuntimeState
import ai.wristmind.app.edge.ModelCatalog
import ai.wristmind.app.edge.ModelInstallStatus
import ai.wristmind.app.router.SystemOneDecision
import ai.wristmind.app.security.ShieldVerdict
import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview as CameraPreviewUseCase
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

@Composable
fun WristMindRootScreen(viewModel: WristMindViewModel) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val memory by viewModel.memoryTelemetry.collectAsStateWithLifecycle()
    val gallery by viewModel.galleryState.collectAsStateWithLifecycle()
    val decision by viewModel.lastDecision.collectAsStateWithLifecycle()
    val pending by viewModel.pendingConfirmation.collectAsStateWithLifecycle()
    val shieldVerdict by viewModel.lastShieldVerdict.collectAsStateWithLifecycle()
    val liveBurst by viewModel.liveBurstStatus.collectAsStateWithLifecycle()
    val speech by viewModel.speechState.collectAsStateWithLifecycle()

    val context = LocalContext.current
    var hasMicPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }

    val micPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasMicPermission = granted
        if (granted) viewModel.togglePushToTalk()
    }

    val camPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
    }

    WristMindDeckContent(
        uiState = uiState,
        memory = memory,
        gallery = gallery,
        lastDecision = decision,
        pendingConfirmation = pending,
        lastShieldVerdict = shieldVerdict,
        liveBurst = liveBurst,
        isListeningVoice = speech.isListening,
        hasCameraPermission = hasCameraPermission,
        onToggleOnline = viewModel::toggleOnlineMode,
        onQueryChange = viewModel::updateQueryInput,
        onSubmitQuery = { q, withSnap -> viewModel.submitQuery(q, withSnap) },
        onPushToTalk = {
            if (hasMicPermission) viewModel.togglePushToTalk()
            else micPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
        },
        onConfirmSkill = viewModel::confirmPendingSkill,
        onDismissSkill = viewModel::dismissPendingSkill,
        onRequestCameraPermission = { camPermLauncher.launch(Manifest.permission.CAMERA) },
        onCaptureBitmap = { bmp -> viewModel.captureAndIngestBitmap(bmp) },
        onRunSyntheticOcr = viewModel::runSyntheticOcrDemo,
        onToggleLiveBurst = viewModel::toggleLiveBurst,
        onSendLiveFrame = viewModel::sendOneFpsSyntheticOrCameraFrame,
        onSelectModel = viewModel::selectGalleryModel,
        onDownloadModel = viewModel::downloadGalleryModel,
        onUpdateHfToken = viewModel::updateHfToken,
        onCycleResidency = viewModel::cycleResidencyPolicy
    )
}

@Composable
fun WristMindDeckContent(
    uiState: WristConversationUiState,
    memory: DeviceMemoryTelemetry,
    gallery: GalleryRuntimeState,
    lastDecision: SystemOneDecision?,
    pendingConfirmation: PendingWristConfirmation?,
    lastShieldVerdict: ShieldVerdict?,
    liveBurst: LiveBurstStatus,
    isListeningVoice: Boolean,
    hasCameraPermission: Boolean,
    onToggleOnline: () -> Unit,
    onQueryChange: (String) -> Unit,
    onSubmitQuery: (String?, Boolean) -> Unit,
    onPushToTalk: () -> Unit,
    onConfirmSkill: () -> Unit,
    onDismissSkill: () -> Unit,
    onRequestCameraPermission: () -> Unit,
    onCaptureBitmap: (Bitmap) -> Unit,
    onRunSyntheticOcr: (Boolean) -> Unit,
    onToggleLiveBurst: () -> Unit,
    onSendLiveFrame: (Bitmap?) -> Unit,
    onSelectModel: (EdgeModelSpec) -> Unit,
    onDownloadModel: (EdgeModelSpec) -> Unit,
    onUpdateHfToken: (String) -> Unit,
    onCycleResidency: () -> Unit
) {
    val pagerState = rememberPagerState(pageCount = { 4 })
    val scope = rememberCoroutineScope()
    val tabLabels = listOf("ASK", "SNAP", "LIVE", "MODELS")

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = WristColors.AmoledBlack
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(WristDimens.ScreenPadding),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            TelemetryHudPill(
                memory = memory,
                activeModelShort = gallery.selectedSpec.shortBadge,
                tierBadge = uiState.executedTierBadge,
                sysOneLatencyMs = lastDecision?.latencyMs,
                isOnlineSimulated = uiState.isOnlineMode,
                onToggleOnlineMode = onToggleOnline
            )

            // Large swipeable/tappable 4-card watch navigation strip
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                tabLabels.forEachIndexed { index, title ->
                    val selected = pagerState.currentPage == index
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .background(
                                if (selected) WristColors.EmeraldEdge else WristColors.CardSurface
                            )
                            .clickable { scope.launch { pagerState.animateScrollToPage(index) } },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (selected) Color(0xFF002110) else WristColors.TextMuted,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f)
            ) { page ->
                when (page) {
                    0 -> AskAndVoiceCard(
                        uiState = uiState,
                        lastDecision = lastDecision,
                        pendingConfirmation = pendingConfirmation,
                        isListeningVoice = isListeningVoice,
                        onQueryChange = onQueryChange,
                        onSubmitQuery = onSubmitQuery,
                        onPushToTalk = onPushToTalk,
                        onConfirmSkill = onConfirmSkill,
                        onDismissSkill = onDismissSkill
                    )
                    1 -> SnapOcrShieldCard(
                        uiState = uiState,
                        lastShieldVerdict = lastShieldVerdict,
                        hasCameraPermission = hasCameraPermission,
                        onRequestCameraPermission = onRequestCameraPermission,
                        onCaptureBitmap = onCaptureBitmap,
                        onRunSyntheticOcr = onRunSyntheticOcr,
                        onAskAboutSnapshot = {
                            scope.launch { pagerState.animateScrollToPage(0) }
                            onSubmitQuery("Describe what is in my latest captured snapshot.", true)
                        }
                    )
                    2 -> GeminiLiveBurstCard(
                        liveBurst = liveBurst,
                        onToggleLiveBurst = onToggleLiveBurst,
                        onSendLiveFrame = { onSendLiveFrame(null) }
                    )
                    3 -> EdgeModelGalleryCard(
                        memory = memory,
                        gallery = gallery,
                        hfToken = uiState.hfTokenInput,
                        onUpdateHfToken = onUpdateHfToken,
                        onSelectModel = onSelectModel,
                        onDownloadModel = onDownloadModel,
                        onCycleResidency = onCycleResidency
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AskAndVoiceCard(
    uiState: WristConversationUiState,
    lastDecision: SystemOneDecision?,
    pendingConfirmation: PendingWristConfirmation?,
    isListeningVoice: Boolean,
    onQueryChange: (String) -> Unit,
    onSubmitQuery: (String?, Boolean) -> Unit,
    onPushToTalk: () -> Unit,
    onConfirmSkill: () -> Unit,
    onDismissSkill: () -> Unit
) {
    val quickPrompts = listOf(
        "Parked Car?" to "Where did I park my rental EV and what is the plate?",
        "Hotel Wi-Fi?" to "What is my Grand Hyatt room number and Wi-Fi passcode?",
        "Live HR" to "Measure my heart rate right now",
        "Log Vitals" to "Log my watch vitals and heart rate to memory",
        "5m Timer" to "Start a countdown timer for 5 minutes",
        "Torch" to "Toggle watch flashlight torch",
        "BT Panel" to "Open Bluetooth settings on watch",
        "Nearby Maps" to "Find nearby coffee shops open right now around me"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Huge 80dp Push-to-Talk button tailored to >2" wrist tap ergonomics
        Button(
            onClick = onPushToTalk,
            modifier = Modifier
                .fillMaxWidth()
                .height(WristDimens.PrimaryTouchHeight),
            shape = RoundedCornerShape(WristDimens.CardCornerRadius),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isListeningVoice) WristColors.CoralDanger else WristColors.EmeraldEdge,
                contentColor = Color(0xFF001F0E)
            )
        ) {
            Text(
                text = if (isListeningVoice) "LISTENING... TAP TO STOP" else "PUSH TO TALK (OFFLINE STT)",
                style = MaterialTheme.typography.titleMedium
            )
        }

        // One-tap wrist presets so hackathon demos take zero typing on a 2-inch screen
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            for ((shortLabel, fullPrompt) in quickPrompts) {
                OutlinedButton(
                    onClick = { onSubmitQuery(fullPrompt, false) },
                    modifier = Modifier.heightIn(min = 52.dp),
                    shape = RoundedCornerShape(16.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Text(shortLabel, style = MaterialTheme.typography.labelLarge)
                }
            }
        }

        // Mid-confidence System-1 confirmation card (0.50 <= P_max < 0.80)
        if (pendingConfirmation != null) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background( Color(0xFF2B2105) )
                    .border(2.dp, WristColors.AmberWarn, RoundedCornerShape(18.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Confirm Watch Skill: ${pendingConfirmation.summaryLabel}",
                    style = MaterialTheme.typography.titleMedium,
                    color = WristColors.AmberWarn
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = onConfirmSkill,
                        modifier = Modifier
                            .weight(1f)
                            .height(WristDimens.SecondaryTouchHeight),
                        colors = ButtonDefaults.buttonColors(containerColor = WristColors.EmeraldEdge)
                    ) {
                        Text("CONFIRM", color = Color.Black, style = MaterialTheme.typography.labelLarge)
                    }
                    OutlinedButton(
                        onClick = onDismissSkill,
                        modifier = Modifier
                            .weight(1f)
                            .height(WristDimens.SecondaryTouchHeight)
                    ) {
                        Text("CANCEL", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }

        // Answer & System-1 telemetry card
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(WristDimens.CardCornerRadius))
                .background(WristColors.CardSurface)
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = uiState.executedTierBadge,
                    style = MaterialTheme.typography.labelSmall,
                    color = WristColors.CyanCloud
                )
                if (lastDecision != null) {
                    Text(
                        text = "${lastDecision.routeChoice.selected.displayLabel} P=${"%.2f".format(lastDecision.routeChoice.maxProbability)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = WristColors.EmeraldEdge
                    )
                }
            }

            Text(
                text = uiState.streamingAnswer,
                style = MaterialTheme.typography.bodyLarge,
                color = WristColors.TextPrimary
            )

            if (uiState.citations.isNotEmpty()) {
                CitationBadgesSection(uiState.citations)
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = uiState.queryInput,
                onValueChange = onQueryChange,
                placeholder = { Text("Ask WristMind...", style = MaterialTheme.typography.bodyMedium) },
                modifier = Modifier.weight(1f),
                singleLine = true
            )
            Button(
                onClick = { onSubmitQuery(null, false) },
                modifier = Modifier.height(64.dp),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text("GO", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun CitationBadgesSection(citations: List<HybridMemoryResult>) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = "HYBRID RRF CITATIONS (ObjectBox 256d + AppSearch BM25F):",
            style = MaterialTheme.typography.labelSmall,
            color = WristColors.TextMuted
        )
        for (hit in citations) {
            val cosStr = hit.cosineDistance?.let { "cos=${"%.2f".format(it)}" } ?: "cos=-"
            val lexStr = hit.lexicalRank?.let { "bm25=#$it" } ?: "bm25=-"
            Text(
                text = "• ${hit.chunk.title} [RRF=${"%.3f".format(hit.rrfScore)} | $cosStr | $lexStr]",
                style = MaterialTheme.typography.labelSmall,
                color = WristColors.EmeraldEdge
            )
        }
    }
}

@Composable
private fun SnapOcrShieldCard(
    uiState: WristConversationUiState,
    lastShieldVerdict: ShieldVerdict?,
    hasCameraPermission: Boolean,
    onRequestCameraPermission: () -> Unit,
    onCaptureBitmap: (Bitmap) -> Unit,
    onRunSyntheticOcr: (Boolean) -> Unit,
    onAskAboutSnapshot: () -> Unit
) {
    var previewViewRef by remember { mutableStateOf<PreviewView?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (hasCameraPermission) {
            WatchCameraPreviewBox(
                onPreviewReady = { previewViewRef = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(170.dp)
                    .clip(RoundedCornerShape(18.dp))
            )
            Button(
                onClick = { previewViewRef?.bitmap?.let(onCaptureBitmap) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(WristDimens.PrimaryTouchHeight),
                shape = RoundedCornerShape(WristDimens.CardCornerRadius)
            ) {
                Text("SNAP CAMERA -> OCR -> VAULT", style = MaterialTheme.typography.titleMedium)
            }
        } else {
            OutlinedButton(
                onClick = onRequestCameraPermission,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(WristDimens.SecondaryTouchHeight),
                shape = RoundedCornerShape(WristDimens.CardCornerRadius)
            ) {
                Text("ENABLE WATCH CAMERAX", style = MaterialTheme.typography.titleMedium)
            }
        }

        // Stage-proof synthetic OCR + Prompt-Injection Shield tests
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = { onRunSyntheticOcr(false) },
                modifier = Modifier
                    .weight(1f)
                    .height(WristDimens.SecondaryTouchHeight),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = WristColors.EmeraldEdge)
            ) {
                Text("TEST BENIGN SIGN", color = Color.Black, style = MaterialTheme.typography.labelLarge)
            }
            Button(
                onClick = { onRunSyntheticOcr(true) },
                modifier = Modifier
                    .weight(1f)
                    .height(WristDimens.SecondaryTouchHeight),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = WristColors.CoralDanger)
            ) {
                Text("TEST INJECTION", color = Color.White, style = MaterialTheme.typography.labelLarge)
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(WristColors.CardSurface)
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = "OCR & PROMPT SHIELD VERDICT",
                style = MaterialTheme.typography.labelSmall,
                color = WristColors.AmberWarn
            )
            Text(
                text = uiState.lastOcrStatus,
                style = MaterialTheme.typography.bodyLarge,
                color = WristColors.TextPrimary
            )
            if (lastShieldVerdict != null) {
                Text(
                    text = "Backend: ${lastShieldVerdict.backend} • p_inj=${"%.2f".format(lastShieldVerdict.injectionProbability)} • ${lastShieldVerdict.latencyMs}ms",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (lastShieldVerdict.isSafe) WristColors.EmeraldEdge else WristColors.CoralDanger
                )
            }
        }

        if (uiState.lastCapturedJpeg != null) {
            OutlinedButton(
                onClick = onAskAboutSnapshot,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(WristDimens.SecondaryTouchHeight),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text("ASK LOCAL SMOLVLM ABOUT SNAPSHOT", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

@Composable
private fun WatchCameraPreviewBox(
    onPreviewReady: (PreviewView) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context) }

    DisposableEffect(lifecycleOwner) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        val executor = ContextCompat.getMainExecutor(context)
        cameraProviderFuture.addListener({
            runCatching {
                val provider = cameraProviderFuture.get()
                val preview = CameraPreviewUseCase.Builder().build().also {
                    it.surfaceProvider = previewView.surfaceProvider
                }
                provider.unbindAll()
                val selector = if (provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) {
                    CameraSelector.DEFAULT_BACK_CAMERA
                } else {
                    CameraSelector.DEFAULT_FRONT_CAMERA
                }
                provider.bindToLifecycle(lifecycleOwner, selector, preview)
                onPreviewReady(previewView)
            }
        }, executor)

        onDispose {
            runCatching { cameraProviderFuture.get().unbindAll() }
        }
    }

    AndroidView(
        factory = { previewView },
        modifier = modifier
    )
}

@Composable
private fun GeminiLiveBurstCard(
    liveBurst: LiveBurstStatus,
    onToggleLiveBurst: () -> Unit,
    onSendLiveFrame: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Button(
            onClick = onToggleLiveBurst,
            modifier = Modifier
                .fillMaxWidth()
                .height(WristDimens.PrimaryTouchHeight),
            shape = RoundedCornerShape(WristDimens.CardCornerRadius),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (liveBurst.isStreaming) WristColors.CoralDanger else WristColors.CyanCloud,
                contentColor = Color.Black
            )
        ) {
            Text(
                text = if (liveBurst.isStreaming) "STOP 1FPS GEMINI LIVE" else "START 1FPS GEMINI LIVE BURST",
                style = MaterialTheme.typography.titleMedium
            )
        }

        if (liveBurst.isStreaming) {
            OutlinedButton(
                onClick = onSendLiveFrame,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(WristDimens.SecondaryTouchHeight),
                shape = RoundedCornerShape(16.dp)
            ) {
                Text(
                    text = "SEND 1FPS WATCH FRAME (#${liveBurst.framesSent + 1})",
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(WristDimens.CardCornerRadius))
                .background(WristColors.CardSurface)
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(if (liveBurst.cloudConfigured) WristColors.EmeraldEdge else WristColors.AmberWarn)
                )
                Text(
                    text = if (liveBurst.cloudConfigured) {
                        "Firebase AI Logic + App Check Active"
                    } else {
                        "Offline Demo Mode (awaiting google-services.json)"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = WristColors.CyanCloud
                )
            }

            Text(
                text = liveBurst.transcriptLog,
                style = MaterialTheme.typography.bodyLarge,
                color = WristColors.TextPrimary
            )
        }
    }
}

@Composable
private fun EdgeModelGalleryCard(
    memory: DeviceMemoryTelemetry,
    gallery: GalleryRuntimeState,
    hfToken: String,
    onUpdateHfToken: (String) -> Unit,
    onSelectModel: (EdgeModelSpec) -> Unit,
    onDownloadModel: (EdgeModelSpec) -> Unit,
    onCycleResidency: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // 4GB RAM Residency Controller card
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(WristColors.CardSurfaceElevated)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = "4GB LPDDR RESIDENCY: ${memory.residencyMode.badge}",
                style = MaterialTheme.typography.titleMedium,
                color = WristColors.EmeraldEdge
            )
            Text(
                text = "Physical: ${memory.physicalTotalMb}MB • Free: ${memory.memAvailableMb}MB • App PSS: ${memory.appPssMb}MB",
                style = MaterialTheme.typography.labelSmall,
                color = WristColors.TextMuted
            )
            OutlinedButton(
                onClick = onCycleResidency,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text("TOGGLE CO-RESIDENT / AUTO-SWAP", style = MaterialTheme.typography.labelLarge)
            }
        }

        OutlinedTextField(
            value = hfToken,
            onValueChange = onUpdateHfToken,
            placeholder = { Text("Optional HuggingFace Token (for gated Gemma)", style = MaterialTheme.typography.bodyMedium) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        val statuses = gallery.statuses.ifEmpty {
            ModelCatalog.ALL_MODELS.map { ModelInstallStatus(it, null, false) }
        }

        for (item in statuses) {
            val isSelected = gallery.selectedSpec.id == item.spec.id
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(WristColors.CardSurface)
                    .border(
                        width = if (isSelected) 2.dp else 1.dp,
                        color = if (isSelected) WristColors.EmeraldEdge else WristColors.CardSurfaceElevated,
                        shape = RoundedCornerShape(18.dp)
                    )
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = "${item.spec.displayName} (${item.spec.approxDiskMb} MB)",
                    style = MaterialTheme.typography.titleMedium,
                    color = WristColors.TextPrimary
                )
                Text(
                    text = item.spec.summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = WristColors.TextMuted
                )

                if (item.downloadProgress != null) {
                    Spacer(Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = { item.downloadProgress },
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (item.installedFile != null) {
                        Button(
                            onClick = { onSelectModel(item.spec) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(60.dp),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Text(
                                text = if (item.isLoaded) "ACTIVE IN RAM" else "ACTIVATE ON WRIST",
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
                    } else {
                        OutlinedButton(
                            onClick = { onDownloadModel(item.spec) },
                            modifier = Modifier
                                .weight(1f)
                                .height(60.dp),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Text("PULL HF", style = MaterialTheme.typography.labelLarge)
                        }
                        Button(
                            onClick = { onSelectModel(item.spec) },
                            modifier = Modifier
                                .weight(1f)
                                .height(60.dp),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Text(
                                text = if (isSelected) "SELECTED" else "SELECT",
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Calibrated preview matching the user's rectangular >2" Full Android 15 watch (`Smallest Width = 426dp`).
 */
@Preview(
    name = "WristMind sw426dp Rectangular Watch",
    device = "spec:width=426dp,height=490dp,dpi=180",
    showBackground = true
)
@Composable
fun WristMindSw426WatchPreview() {
    WristMicroTheme {
        WristMindDeckContent(
            uiState = WristConversationUiState(
                executedTierBadge = "LOCAL (Qwen3-0.6B)",
                streamingAnswer = "Parked rental EV (plate 8KZM419) on Level P3, Pillar Green-14 near the North elevator."
            ),
            memory = DeviceMemoryTelemetry(
                physicalTotalMb = 4096L,
                memAvailableMb = 1320L,
                appPssMb = 685L
            ),
            gallery = GalleryRuntimeState(),
            lastDecision = null,
            pendingConfirmation = null,
            lastShieldVerdict = null,
            liveBurst = LiveBurstStatus(),
            isListeningVoice = false,
            hasCameraPermission = false,
            onToggleOnline = {},
            onQueryChange = {},
            onSubmitQuery = { _, _ -> },
            onPushToTalk = {},
            onConfirmSkill = {},
            onDismissSkill = {},
            onRequestCameraPermission = {},
            onCaptureBitmap = {},
            onRunSyntheticOcr = {},
            onToggleLiveBurst = {},
            onSendLiveFrame = {},
            onSelectModel = {},
            onDownloadModel = {},
            onUpdateHfToken = {},
            onCycleResidency = {}
        )
    }
}
