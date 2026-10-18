package br.com.finbot.ui.chat

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import br.com.finbot.security.SafeLog
import java.io.File
import java.io.IOException

/** Gravação finalizada, pronta para envio. O arquivo fica no cache privado e deve ser apagado após o uso. */
data class Recording(val file: File, val mimeType: String, val durationSeconds: Int)

/**
 * Grava voz em AAC (container ADTS, `audio/aac`), formato aceito pela Gemini API sem conversão.
 * Mono, 16 kHz, ~32 kbps: 60 s ≈ 240 KB. Limite de duração aplicado pelo próprio MediaRecorder.
 */
class AudioRecorder(private val context: Context) {
    private var recorder: MediaRecorder? = null
    private var file: File? = null
    private var startedAtMs = 0L

    val isRecording: Boolean get() = recorder != null

    /** @param onMaxDuration chamado (na thread principal) quando o limite é atingido; a gravação já foi encerrada. */
    fun start(onMaxDuration: () -> Unit): Boolean {
        if (recorder != null) return false
        val dir = File(context.cacheDir, "audio").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() } // sobras de execuções interrompidas
        val output = File(dir, "voz-${System.currentTimeMillis()}.aac")
        val mr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
        return try {
            mr.setAudioSource(MediaRecorder.AudioSource.MIC)
            mr.setOutputFormat(MediaRecorder.OutputFormat.AAC_ADTS)
            mr.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            mr.setAudioChannels(1)
            mr.setAudioSamplingRate(16_000)
            mr.setAudioEncodingBitRate(32_000)
            mr.setMaxDuration(MAX_SECONDS * 1000)
            mr.setOutputFile(output.absolutePath)
            mr.setOnInfoListener { _, what, _ ->
                if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) onMaxDuration()
            }
            mr.prepare()
            mr.start()
            recorder = mr
            file = output
            startedAtMs = System.currentTimeMillis()
            true
        } catch (e: IOException) {
            fail(mr, output, e)
        } catch (e: RuntimeException) {
            fail(mr, output, e)
        }
    }

    /** Encerra e devolve a gravação; null se curta demais ou se falhou (o arquivo já é apagado). */
    fun stop(): Recording? {
        val mr = recorder ?: return null
        val output = file
        recorder = null
        file = null
        val seconds = ((System.currentTimeMillis() - startedAtMs) / 1000).toInt().coerceAtMost(MAX_SECONDS)
        val ok = try {
            mr.stop()
            true
        } catch (e: RuntimeException) {
            // stop() falha se nada foi capturado (toque muito curto).
            SafeLog.w("Gravação sem áudio válido", e)
            false
        } finally {
            mr.release()
        }
        if (!ok || output == null || seconds < MIN_SECONDS || output.length() == 0L) {
            output?.delete()
            return null
        }
        return Recording(output, MIME_TYPE, seconds.coerceAtLeast(1))
    }

    /** Descarta a gravação em andamento sem enviar. */
    fun cancel() {
        stop()?.file?.delete()
    }

    private fun fail(mr: MediaRecorder, output: File, e: Exception): Boolean {
        SafeLog.w("Falha ao iniciar gravação", e)
        mr.release()
        output.delete()
        return false
    }

    companion object {
        const val MAX_SECONDS = 60
        const val MIN_SECONDS = 1
        const val MIME_TYPE = "audio/aac"
    }
}
