package br.com.finbot.diagnostics

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.os.StatFs
import br.com.finbot.security.SafeLog
import java.io.File
import java.io.IOException

data class StorageHealth(val ok: Boolean, val freeBytes: Long, val message: String)

data class DeviceInfo(
    val androidVersion: String,
    val sdkInt: Int,
    val manufacturer: String,
    val model: String,
    val ignoringBatteryOptimizations: Boolean,
)

class DeviceDiagnostics(private val context: Context) {

    /** Escreve, lê e apaga um arquivo de prova no diretório privado do app. Executar fora da thread principal. */
    fun checkStorage(): StorageHealth {
        val dir = context.filesDir
        val freeBytes = StatFs(dir.absolutePath).availableBytes
        return try {
            val probe = File(dir, PROBE_NAME)
            probe.writeText(PROBE_CONTENT)
            val readBack = probe.readText()
            probe.delete()
            when {
                readBack != PROBE_CONTENT -> StorageHealth(false, freeBytes, "Falha na verificação de leitura.")
                freeBytes < LOW_SPACE_BYTES -> StorageHealth(false, freeBytes, "Pouco espaço livre no aparelho.")
                else -> StorageHealth(true, freeBytes, "Armazenamento privado OK.")
            }
        } catch (e: IOException) {
            SafeLog.w("Falha na verificação de armazenamento", e)
            StorageHealth(false, freeBytes, "Não foi possível gravar no armazenamento privado.")
        }
    }

    fun deviceInfo(): DeviceInfo {
        val power = context.getSystemService(PowerManager::class.java)
        return DeviceInfo(
            androidVersion = Build.VERSION.RELEASE,
            sdkInt = Build.VERSION.SDK_INT,
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            ignoringBatteryOptimizations = power.isIgnoringBatteryOptimizations(context.packageName),
        )
    }

    private companion object {
        const val PROBE_NAME = ".storage_probe"
        const val PROBE_CONTENT = "finbot-ok"
        const val LOW_SPACE_BYTES = 100L * 1024 * 1024
    }
}
