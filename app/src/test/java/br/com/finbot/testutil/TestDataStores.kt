package br.com.finbot.testutil

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import br.com.finbot.security.SecretCipher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import java.io.File

/** DataStore em arquivo temporário; [close] simula o encerramento do processo. */
class FileDataStoreHandle(private val file: File) {
    private val job = Job()
    val dataStore: DataStore<Preferences> =
        PreferenceDataStoreFactory.create(scope = CoroutineScope(Dispatchers.IO + job)) { file }

    suspend fun close() = job.cancelAndJoin()
}

/** Cifra reversível só para testes JVM (o Android Keystore não existe fora do aparelho). */
class FakeCipher : SecretCipher {
    var failDecrypt = false

    override fun encrypt(plain: ByteArray): ByteArray = byteArrayOf(MARK) + plain.map { (it.toInt() xor 0x5A).toByte() }

    override fun decrypt(blob: ByteArray): ByteArray {
        check(!failDecrypt && blob.firstOrNull() == MARK) { "falha simulada" }
        return blob.drop(1).map { (it.toInt() xor 0x5A).toByte() }.toByteArray()
    }

    private companion object {
        const val MARK: Byte = 7
    }
}
