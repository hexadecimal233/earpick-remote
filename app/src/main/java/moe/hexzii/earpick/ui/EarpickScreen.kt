package moe.hexzii.earpick.ui

import android.Manifest
import android.app.Activity
import android.annotation.SuppressLint
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Flip
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import moe.hexzii.earpick.R
import moe.hexzii.earpick.link.LinkPhase
import moe.hexzii.earpick.link.LinkState

@Composable
fun EarpickRoute(viewModel: EarpickViewModel = viewModel(factory = EarpickViewModel.Factory)) {
    val state by viewModel.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val view = LocalView.current
    val darkTheme = isSystemInDarkTheme()
    val activity = context.findActivity()
    val snackbarHostState = remember { SnackbarHostState() }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var pendingViaWifi by remember { mutableStateOf<Boolean?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val viaWifi = pendingViaWifi
        pendingViaWifi = null
        if (granted) {
            if (viaWifi != null) viewModel.connect(viaWifi)
        } else {
            viewModel.onLocalNetworkPermissionDenied()
        }
    }

    val requestConnect: (Boolean) -> Unit = { viaDeviceWifi ->
        if (needsLocalNetworkPermission(context)) {
            pendingViaWifi = viaDeviceWifi
            permissionLauncher.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
        } else {
            viewModel.connect(viaDeviceWifi)
        }
    }

    val snapshotSavedMessage = stringResource(R.string.snapshot_saved)
    val snapshotFailedMessage = stringResource(R.string.snapshot_failed)

    LaunchedEffect(viewModel, snapshotSavedMessage, snapshotFailedMessage) {
        viewModel.events.collect { event ->
            snackbarHostState.showSnackbar(
                when (event) {
                    UiEvent.SnapshotSaved -> snapshotSavedMessage
                    UiEvent.SnapshotFailed -> snapshotFailedMessage
                },
            )
        }
    }

    DisposableEffect(state.isViewing) {
        // Keep the screen awake only while a camera session is on screen.
        view.keepScreenOn = state.isViewing
        onDispose { view.keepScreenOn = false }
    }

    DisposableEffect(state.isViewing, darkTheme, activity) {
        val window = activity?.window
        if (window != null) {
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            val darkIcons = !state.isViewing && !darkTheme
            controller.isAppearanceLightStatusBars = darkIcons
            controller.isAppearanceLightNavigationBars = darkIcons
        }
        onDispose {}
    }

    Scaffold(
        snackbarHost = {
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.navigationBarsPadding(),
            )
        },
        containerColor = if (state.isViewing) Color.Black else MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { contentPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding),
        ) {
            if (state.isViewing) {
                ViewerPanel(
                    state = state,
                    onToggleLed = viewModel::toggleLed,
                    onToggleMirror = viewModel::toggleMirror,
                    onSnapshot = viewModel::saveSnapshot,
                    onDisconnect = viewModel::disconnect,
                    onOpenSettings = { settingsOpen = true },
                )
            } else {
                ConnectPanel(
                    state = state,
                    onConnect = requestConnect,
                    onCancel = viewModel::disconnect,
                    onRetry = viewModel::retry,
                    onOpenSettings = { settingsOpen = true },
                    onOpenAppSettings = { context.openAppSettings() },
                )
            }
        }
    }

    if (settingsOpen) {
        SettingsSheet(
            settings = state.settings,
            onSave = {
                viewModel.updateSettings(it)
                settingsOpen = false
            },
            onDismiss = { settingsOpen = false },
            onOpenAppSettings = { context.openAppSettings() },
        )
    }
}

@SuppressLint("InlinedApi")
private fun needsLocalNetworkPermission(context: Context): Boolean =
    Build.VERSION.SDK_INT >= 37 && !hasLocalNetworkPermission(context)

@RequiresApi(37)
private fun hasLocalNetworkPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACCESS_LOCAL_NETWORK,
    ) == PackageManager.PERMISSION_GRANTED

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun Context.openAppSettings() {
    startActivity(
        Intent(
            AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", packageName, null),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}

@Composable
private fun ConnectPanel(
    state: UiState,
    onConnect: (Boolean) -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenAppSettings: () -> Unit,
) {
    val scroll = rememberScrollState()
    Box(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing),
        contentAlignment = Alignment.Center,
    ) {
        IconButton(
            onClick = onOpenSettings,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(8.dp),
        ) {
            Icon(Icons.Outlined.Settings, contentDescription = stringResource(R.string.settings))
        }

        Column(
            modifier = Modifier
                .verticalScroll(scroll)
                .padding(horizontal = 28.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(76.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Outlined.PhotoCamera,
                        contentDescription = null,
                        modifier = Modifier.size(36.dp),
                    )
                }
            }

            Spacer(Modifier.height(20.dp))
            Text(
                text = stringResource(R.string.connect_title),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.connect_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(28.dp))

            if (state.phase == UiPhase.ConnectingWifi || state.phase == UiPhase.Starting) {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            strokeWidth = 2.dp,
                        )
                        Text(
                            text = stringResource(
                                if (state.phase == UiPhase.ConnectingWifi) {
                                    R.string.connect_waiting_wifi
                                } else {
                                    R.string.connect_preparing
                                },
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = onCancel) {
                            Text(stringResource(R.string.connect_cancel))
                        }
                    }
                }
            } else {
                Button(
                    onClick = { onConnect(true) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                ) {
                    Icon(Icons.Outlined.Wifi, contentDescription = null)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = stringResource(R.string.connect_primary),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = { onConnect(false) }) {
                    Text(stringResource(R.string.connect_manual))
                }
            }

            state.error?.let { error ->
                Spacer(Modifier.height(20.dp))
                ErrorCard(
                    error = error,
                    onOpenAppSettings = onOpenAppSettings,
                    onOpenSettings = onOpenSettings,
                    onRetry = onRetry,
                )
            }

            Spacer(Modifier.height(28.dp))

            if (Build.VERSION.SDK_INT >= 37) {
                Text(
                    text = stringResource(R.string.connect_permission_note),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(16.dp))
            }

            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Icon(
                        Icons.Outlined.Lock,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = stringResource(R.string.security_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun ViewerPanel(
    state: UiState,
    onToggleLed: () -> Unit,
    onToggleMirror: () -> Unit,
    onSnapshot: () -> Unit,
    onDisconnect: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        val landscape = maxWidth > maxHeight

        ZoomableFrame(
            frame = state.frame,
            modifier = Modifier.fillMaxSize(),
        )

        if (state.frame == null) {
            WaitingLayer(state)
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(
                    WindowInsets.safeDrawing.only(
                        WindowInsetsSides.Top + WindowInsetsSides.Horizontal,
                    ),
                )
                .padding(horizontal = 8.dp, vertical = 6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onDisconnect) {
                    Icon(
                        Icons.Outlined.Close,
                        contentDescription = stringResource(R.string.control_disconnect),
                        tint = Color.White,
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = state.link.deviceName
                            ?: stringResource(R.string.streaming_device_fallback),
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = stringResource(
                            if (state.phase == UiPhase.Streaming) {
                                R.string.status_live
                            } else if (state.phase == UiPhase.Error) {
                                R.string.error_title
                            } else {
                                R.string.status_waiting
                            },
                        ),
                        color = Color.White.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                IconButton(onClick = onOpenSettings) {
                    Icon(
                        Icons.Outlined.Settings,
                        contentDescription = stringResource(R.string.settings),
                        tint = Color.White,
                    )
                }
            }

            StatsRow(state)
        }

        val safeEdges = WindowInsets.safeDrawing.only(
            WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom,
        )
        val controlModifier = if (landscape) {
            Modifier
                .align(Alignment.CenterEnd)
                .windowInsetsPadding(safeEdges)
                .padding(end = 12.dp)
        } else {
            Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(safeEdges)
                .padding(bottom = 20.dp)
        }

        ControlBar(
            landscape = landscape,
            savingSnapshot = state.savingSnapshot,
            ledOn = state.link.ledOn,
            mirrorOn = state.link.mirror,
            enabled = state.phase != UiPhase.Error,
            onToggleLed = onToggleLed,
            onToggleMirror = onToggleMirror,
            onSnapshot = onSnapshot,
            modifier = controlModifier,
        )

        if (!landscape && state.frame != null && state.phase == UiPhase.Streaming) {
            Text(
                text = stringResource(R.string.zoom_hint),
                color = Color.White.copy(alpha = 0.55f),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .windowInsetsPadding(safeEdges)
                    .padding(bottom = 4.dp),
            )
        }
    }
}

@Composable
private fun WaitingLayer(state: UiState) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(color = Color.White, strokeWidth = 3.dp)
        Spacer(Modifier.height(20.dp))
        Text(
            text = stringResource(R.string.waiting_title),
            color = Color.White,
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = stringResource(R.string.waiting_body),
            color = Color.White.copy(alpha = 0.65f),
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(14.dp))
        if (state.link.packetsReceived > 0 && state.link.videoPacketsReceived == 0L) {
            Text(
                text = stringResource(R.string.waiting_no_video_hint),
                color = Color(0xFFFFC857),
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(8.dp))
        }
        Text(
            text = stringResource(
                R.string.diag_format,
                state.link.packetsReceived,
                state.link.videoPacketsReceived,
                state.link.framesReceived,
                state.link.bufferedBytes,
                state.decodeFailures,
            ),
            color = Color.White.copy(alpha = 0.45f),
            style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.Center,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StatsRow(state: UiState) {
    val link = state.link
    FlowRow(
        modifier = Modifier.padding(start = 56.dp, end = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (link.phase == LinkPhase.Streaming) {
            StatPill(stringResource(R.string.chip_fps, link.fps))
            StatPill(stringResource(R.string.chip_kbps, link.kbps))
        } else if (state.frame != null) {
            StatPill(stringResource(R.string.waiting_reconnecting))
        }
        link.batteryAdc?.let { adc ->
            StatPill(
                text = stringResource(R.string.chip_adc, adc),
                description = stringResource(R.string.chip_adc_description),
            )
        }
    }
}

@Composable
private fun StatPill(text: String, description: String? = null) {
    Surface(
        shape = RoundedCornerShape(50),
        color = Color.Black.copy(alpha = 0.42f),
        contentColor = Color.White,
    ) {
        Text(
            text = text,
            modifier = Modifier
                .padding(horizontal = 10.dp, vertical = 4.dp)
                .semanticsDescription(description),
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

private fun Modifier.semanticsDescription(description: String?): Modifier =
    if (description == null) {
        this
    } else {
        this.semantics { contentDescription = description }
    }

@Composable
private fun ControlBar(
    landscape: Boolean,
    savingSnapshot: Boolean,
    ledOn: Boolean,
    mirrorOn: Boolean,
    enabled: Boolean,
    onToggleLed: () -> Unit,
    onToggleMirror: () -> Unit,
    onSnapshot: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = Color.Black.copy(alpha = 0.55f),
        contentColor = Color.White,
        modifier = modifier,
    ) {
        if (landscape) {
            Column(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                ControlButton(Icons.Outlined.Lightbulb, stringResource(R.string.control_led), selected = ledOn, enabled = enabled, onClick = onToggleLed)
                ControlButton(Icons.Outlined.Flip, stringResource(R.string.control_mirror), selected = mirrorOn, enabled = enabled, onClick = onToggleMirror)
                ControlButton(Icons.Outlined.PhotoCamera, stringResource(R.string.control_snapshot), enabled = enabled && !savingSnapshot, onClick = onSnapshot)
            }
        } else {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ControlButton(Icons.Outlined.Lightbulb, stringResource(R.string.control_led), selected = ledOn, enabled = enabled, onClick = onToggleLed)
                ControlButton(Icons.Outlined.Flip, stringResource(R.string.control_mirror), selected = mirrorOn, enabled = enabled, onClick = onToggleMirror)
                ControlButton(Icons.Outlined.PhotoCamera, stringResource(R.string.control_snapshot), enabled = enabled && !savingSnapshot, onClick = onSnapshot)
            }
        }
    }
}

@Composable
private fun ControlButton(
    icon: ImageVector,
    label: String,
    selected: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        if (selected) {
            FilledIconButton(
                onClick = onClick,
                enabled = enabled,
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = Color(0xFFFFC857),
                    contentColor = Color(0xFF241900),
                ),
            ) {
                Icon(icon, contentDescription = label)
            }
        } else {
            FilledTonalIconButton(
                onClick = onClick,
                enabled = enabled,
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = Color.White.copy(alpha = 0.12f),
                    contentColor = Color.White,
                    disabledContainerColor = Color.White.copy(alpha = 0.06f),
                    disabledContentColor = Color.White.copy(alpha = 0.4f),
                ),
            ) {
                Icon(icon, contentDescription = label)
            }
        }
        Spacer(Modifier.height(3.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = if (enabled) 0.8f else 0.4f),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ErrorCard(
    error: UiError,
    onOpenAppSettings: () -> Unit,
    onOpenSettings: () -> Unit,
    onRetry: () -> Unit,
    onDismiss: (() -> Unit)? = null,
) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 6.dp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.ErrorOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = stringResource(R.string.error_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(Modifier.height(10.dp))
            Text(
                text = errorMessage(error),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (error == UiError.PermissionDenied) {
                    Button(onClick = onOpenAppSettings) {
                        Text(stringResource(R.string.action_open_settings))
                    }
                } else {
                    Button(onClick = onRetry) {
                        Text(stringResource(R.string.control_retry))
                    }
                }
                if (error == UiError.WifiUnavailable) {
                    TextButton(onClick = onOpenSettings) {
                        Text(stringResource(R.string.settings))
                    }
                }
                if (onDismiss != null) {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.control_disconnect))
                    }
                }
            }
        }
    }
}

@Composable
private fun errorMessage(error: UiError): String = when (error) {
    UiError.PermissionDenied -> stringResource(R.string.error_permission_body)
    UiError.WifiUnavailable -> stringResource(R.string.error_wifi_unavailable)
    UiError.Network -> stringResource(R.string.error_network)
    UiError.WifiLost -> stringResource(R.string.error_wifi_lost)
    is UiError.Unknown -> stringResource(R.string.error_unknown, error.detail)
}

@Composable
private fun ZoomableFrame(frame: ImageBitmap?, modifier: Modifier = Modifier) {
    if (frame == null) return

    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }

    Box(
        modifier = modifier
            .clipToBounds()
            .onSizeChanged { size = it }
            .pointerInput(Unit) {
                detectTapGestures(
                    onDoubleTap = {
                        scale = 1f
                        offset = Offset.Zero
                    },
                )
            }
            .pointerInput(Unit) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    val newScale = (scale * zoom).coerceIn(1f, 6f)
                    val center = Offset(size.width / 2f, size.height / 2f)
                    val centroidOffset = centroid - center
                    val transformed =
                        (offset - centroidOffset) * (newScale / scale) + centroidOffset + pan
                    offset = clampOffset(transformed, newScale, size, frame)
                    scale = newScale
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        androidx.compose.foundation.Image(
            bitmap = frame,
            contentDescription = stringResource(R.string.preview_description),
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                },
            contentScale = ContentScale.Fit,
        )
    }
}

private fun clampOffset(
    offset: Offset,
    scale: Float,
    size: IntSize,
    frame: ImageBitmap,
): Offset {
    if (size.width == 0 || size.height == 0) return Offset.Zero

    val imageAspect = frame.width.toFloat() / frame.height
    val boxAspect = size.width.toFloat() / size.height
    val contentWidth: Float
    val contentHeight: Float
    if (imageAspect > boxAspect) {
        contentWidth = size.width.toFloat()
        contentHeight = contentWidth / imageAspect
    } else {
        contentHeight = size.height.toFloat()
        contentWidth = contentHeight * imageAspect
    }
    val maxX = contentWidth * (scale - 1f) / 2f
    val maxY = contentHeight * (scale - 1f) / 2f
    return Offset(
        x = offset.x.coerceIn(-maxX, maxX),
        y = offset.y.coerceIn(-maxY, maxY),
    )
}

@Preview(showBackground = true, widthDp = 400, heightDp = 800)
@Composable
private fun ConnectPanelPreview() {
    EarpickTheme {
        ConnectPanel(
            state = UiState(),
            onConnect = {},
            onCancel = {},
            onRetry = {},
            onOpenSettings = {},
            onOpenAppSettings = {},
        )
    }
}

@Preview(showBackground = true, widthDp = 800, heightDp = 400)
@Composable
private fun ViewerPanelPreview() {
    EarpickTheme {
        ViewerPanel(
            state = UiState(
                phase = UiPhase.Streaming,
                link = LinkState(
                    phase = LinkPhase.Streaming,
                    deviceName = "zdeer earpick-3593",
                    batteryAdc = 73,
                    width = 640,
                    height = 480,
                    fps = 20,
                    kbps = 390,
                ),
                frame = ImageBitmap(640, 480),
            ),
            onToggleLed = {},
            onToggleMirror = {},
            onSnapshot = {},
            onDisconnect = {},
            onOpenSettings = {},
        )
    }
}
