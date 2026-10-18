package br.com.finbot.ui.chat

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import br.com.finbot.R
import kotlinx.coroutines.delay

/** Estado da entrada por voz na tela de chat. */
class VoiceInputState internal constructor(
    private val recorder: AudioRecorder,
    private val hasPermission: () -> Boolean,
    private val requestPermission: () -> Unit,
) {
    var recording by mutableStateOf(false)
        private set
    var elapsedSeconds by mutableIntStateOf(0)
        internal set
    var showAiNeeded by mutableStateOf(false)
        private set
    internal var aiReady = false

    /** Retorna true se começou a gravar agora. */
    fun requestStart(): Boolean {
        if (recording) return false
        if (!aiReady) {
            showAiNeeded = true
            return false
        }
        if (!hasPermission()) {
            requestPermission() // o usuário toca de novo depois de permitir
            return false
        }
        elapsedSeconds = 0
        recording = recorder.start(onMaxDuration = { onMaxDuration?.invoke() })
        return recording
    }

    internal var onMaxDuration: (() -> Unit)? = null

    fun stop(): Recording? {
        if (!recording) return null
        recording = false
        return recorder.stop()
    }

    fun cancel() {
        if (!recording) return
        recording = false
        recorder.cancel()
    }

    fun dismissAiNeeded() {
        showAiNeeded = false
    }
}

@Composable
fun rememberVoiceInput(aiReady: Boolean, onMaxDuration: (Recording) -> Unit): VoiceInputState {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val state = remember {
        VoiceInputState(
            recorder = AudioRecorder(context.applicationContext),
            hasPermission = {
                ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            },
            requestPermission = { launcher.launch(Manifest.permission.RECORD_AUDIO) },
        )
    }
    val latestOnMax by rememberUpdatedState(onMaxDuration)
    state.aiReady = aiReady
    state.onMaxDuration = { state.stop()?.let(latestOnMax) }

    LaunchedEffect(state.recording) {
        while (state.recording) {
            delay(1_000)
            if (state.recording) state.elapsedSeconds++
        }
    }
    // Sair da tela (ou girar o aparelho) descarta a gravação: nada é enviado sem o usuário pedir.
    DisposableEffect(state) { onDispose { state.cancel() } }
    return state
}

private const val HOLD_THRESHOLD_MS = 600L

/**
 * Segurar para gravar (soltar envia) ou tocar para começar (e depois tocar em Enviar).
 * Com TalkBack, o toque duplo começa a gravação e a barra mostra Enviar/Cancelar.
 */
@Composable
fun MicButton(enabled: Boolean, onStart: () -> Boolean, onHoldRelease: () -> Unit) {
    val currentOnStart by rememberUpdatedState(onStart)
    val currentOnRelease by rememberUpdatedState(onHoldRelease)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(48.dp)
            .semantics {
                role = Role.Button
                contentDescription = "Gravar mensagem de voz"
                onClick { if (enabled) currentOnStart(); true }
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures(onPress = {
                    val pressedAt = System.currentTimeMillis()
                    if (currentOnStart()) {
                        tryAwaitRelease()
                        if (System.currentTimeMillis() - pressedAt >= HOLD_THRESHOLD_MS) currentOnRelease()
                    }
                })
            },
    ) {
        Icon(
            painterResource(R.drawable.ic_mic),
            contentDescription = null,
            tint = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
        )
    }
}

@Composable
fun RecordingBar(seconds: Int, onCancel: () -> Unit, onSend: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.error, modifier = Modifier.size(12.dp)) {}
        Text(
            "Gravando… ${seconds}s de ${AudioRecorder.MAX_SECONDS}s",
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onCancel) { Text("Cancelar") }
        Button(onClick = onSend) { Text("Enviar") }
    }
}
