package moe.hexzii.earpick.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import moe.hexzii.earpick.BuildConfig
import moe.hexzii.earpick.EarpickApp
import moe.hexzii.earpick.capture.SnapshotStore
import moe.hexzii.earpick.data.AppSettings
import moe.hexzii.earpick.data.Settings
import moe.hexzii.earpick.link.AndroidUdpTransport
import moe.hexzii.earpick.link.EarpickSession
import moe.hexzii.earpick.link.LinkPhase
import moe.hexzii.earpick.link.LinkState
import moe.hexzii.earpick.protocol.EarpickProtocol
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class UiPhase {
    Idle,
    Starting,
    WaitingDevice,
    Streaming,
    Error,
}

sealed interface UiError {
    data object PermissionDenied : UiError
    data object Network : UiError
    data class Unknown(val detail: String) : UiError
}

sealed interface UiEvent {
    data object SnapshotSaved : UiEvent
    data object SnapshotFailed : UiEvent
}

data class UiState(
    val phase: UiPhase = UiPhase.Idle,
    val link: LinkState = LinkState(),
    val frame: ImageBitmap? = null,
    val error: UiError? = null,
    val settings: Settings = Settings(),
    val savingSnapshot: Boolean = false,
    val decodeFailures: Int = 0,
) {
    val isViewing: Boolean
        get() = phase == UiPhase.WaitingDevice || phase == UiPhase.Streaming
}

class EarpickViewModel(
    private val settingsStore: AppSettings,
    private val snapshots: SnapshotStore,
) : ViewModel() {
    private val _ui = MutableStateFlow(UiState())
    val ui = _ui.asStateFlow()

    private val _events = Channel<UiEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private var currentSettings = Settings()
    private var connectJob: Job? = null
    private var connectAttempt = 0
    private var session: EarpickSession? = null
    private var sessionScope: CoroutineScope? = null
    private val lastJpeg = AtomicReference<ByteArray?>(null)

    init {
        viewModelScope.launch {
            settingsStore.settings.collect { value ->
                currentSettings = value
                _ui.update { it.copy(settings = value) }
            }
        }
    }

    fun connect() {
        if (session != null || connectJob?.isActive == true) return
        val attempt = ++connectAttempt
        _ui.update {
            it.copy(
                phase = UiPhase.Starting,
                frame = null,
                error = null,
                decodeFailures = 0,
            )
        }

        connectJob = viewModelScope.launch {
            try {
                startSession(attempt)
            } catch (e: CancellationException) {
                throw e
            } catch (_: SecurityException) {
                failConnect(attempt, UiError.PermissionDenied)
            } catch (_: IOException) {
                failConnect(attempt, UiError.Network)
            } catch (e: Exception) {
                failConnect(attempt, UiError.Unknown(e.message ?: e.javaClass.simpleName))
            }
        }
    }

    fun retry() {
        disconnectInternal()
        connect()
    }

    fun onHostStopped() {
        if (session != null) disconnect()
    }

    fun disconnect() {
        disconnectInternal()
        _ui.update {
            it.copy(
                phase = UiPhase.Idle,
                link = LinkState(),
                frame = null,
                error = null,
                savingSnapshot = false,
                decodeFailures = 0,
            )
        }
    }

    fun onLocalNetworkPermissionDenied() {
        _ui.update { it.copy(phase = UiPhase.Idle, error = UiError.PermissionDenied) }
    }

    fun toggleLed() {
        session?.setLed(!_ui.value.link.ledOn)
    }

    fun toggleMirror() {
        session?.toggleMirror()
    }

    fun saveSnapshot() {
        val jpeg = lastJpeg.get() ?: return
        viewModelScope.launch {
            _ui.update { it.copy(savingSnapshot = true) }
            val event = try {
                snapshots.save(jpeg)
                UiEvent.SnapshotSaved
            } catch (_: Exception) {
                UiEvent.SnapshotFailed
            }
            _ui.update { it.copy(savingSnapshot = false) }
            _events.send(event)
        }
    }

    fun updateSettings(next: Settings) {
        viewModelScope.launch { settingsStore.update { next } }
    }

    private suspend fun startSession(attempt: Int) {
        if (attempt != connectAttempt) return

        val (host, port) = resolveEndpoint()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineName("earpick-session"))
        // Socket creation is IO; never do it on the main thread.
        val newSession = withContext(Dispatchers.IO) {
            val address = InetAddress.getByName(host)
            EarpickSession(AndroidUdpTransport(address, port), scope)
        }

        if (attempt != connectAttempt) {
            newSession.close()
            scope.cancel()
            return
        }

        session = newSession
        sessionScope = scope
        connectJob = null

        scope.launch {
            newSession.state.collect { link ->
                if (link.phase == LinkPhase.Failed) {
                    viewModelScope.launch {
                        if (session === newSession) {
                            disconnectInternal()
                            _ui.update { state ->
                                state.copy(
                                    phase = UiPhase.Error,
                                    link = link,
                                    error = UiError.Network,
                                )
                            }
                        }
                    }
                    return@collect
                }
                _ui.update { state ->
                    state.copy(
                        link = link,
                        phase = when (link.phase) {
                            LinkPhase.WaitingForDevice -> UiPhase.WaitingDevice
                            LinkPhase.Streaming -> UiPhase.Streaming
                            LinkPhase.Failed -> UiPhase.Error
                        },
                    )
                }
            }
        }

        scope.launch(Dispatchers.Default) {
            newSession.frames.collect { jpeg ->
                lastJpeg.set(jpeg)
                val bitmap = decodeJpeg(jpeg)
                if (bitmap == null) {
                    _ui.update { it.copy(decodeFailures = it.decodeFailures + 1) }
                } else {
                    _ui.update { it.copy(frame = bitmap.asImageBitmap()) }
                }
            }
        }

        newSession.start()
        _ui.update { it.copy(phase = UiPhase.WaitingDevice, error = null) }
    }

    private fun failConnect(attempt: Int, error: UiError) {
        if (attempt != connectAttempt) return
        connectJob = null
        _ui.update { it.copy(phase = UiPhase.Error, error = error) }
    }

    private fun disconnectInternal() {
        connectAttempt++
        connectJob?.cancel()
        connectJob = null
        session?.close()
        session = null
        sessionScope?.cancel()
        sessionScope = null
        lastJpeg.set(null)
    }

    private fun resolveEndpoint(): Pair<String, Int> {
        val settings = currentSettings
        return if (BuildConfig.DEBUG && settings.devTargetEnabled) {
            settings.devHost.trim().ifBlank { EarpickProtocol.DEFAULT_HOST } to
                settings.devPort.coerceIn(1, 65535)
        } else {
            EarpickProtocol.DEFAULT_HOST to EarpickProtocol.DEFAULT_PORT
        }
    }

    private fun decodeJpeg(jpeg: ByteArray): Bitmap? = runCatching {
        val options = BitmapFactory.Options().apply {
            // Opaque camera frames: half the allocation and bandwidth of ARGB_8888.
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, options)
    }.getOrNull()

    override fun onCleared() {
        disconnectInternal()
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY] as EarpickApp
                EarpickViewModel(
                    settingsStore = app.container.settings,
                    snapshots = app.container.snapshots,
                )
            }
        }
    }
}
