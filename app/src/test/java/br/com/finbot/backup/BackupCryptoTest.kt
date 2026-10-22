package br.com.finbot.backup

import br.com.finbot.alerts.AlertRules
import br.com.finbot.security.AppLock
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.time.Duration
import java.time.Instant

class BackupCryptoTest {
    private val password = "senha-de-teste-123".toCharArray()
    private val manifest = """{"format":1}"""
    private val db = ByteArray(5_000) { (it % 251).toByte() }

    /** Iterações no mínimo aceito, para o teste ser rápido. */
    private fun sealed() = BackupCrypto.encrypt(password, manifest, db, iterations = 100_000)

    @Test
    fun `ida e volta preserva manifesto e banco`() {
        val file = sealed()
        assertTrue(String(file, 0, 8, Charsets.US_ASCII) == "FINBOTX1")
        val opened = BackupCrypto.decrypt(password, file)
        assertEquals(manifest, opened.manifest)
        assertArrayEquals(db, opened.db)
        // Sal e nonce aleatórios: duas exportações iguais geram arquivos diferentes.
        assertFalse(sealed().contentEquals(file))
    }

    @Test
    fun `senha errada falha`() {
        assertThrows(BackupAuthException::class.java) { BackupCrypto.decrypt("outra-senha-qualquer".toCharArray(), sealed()) }
    }

    @Test
    fun `arquivo adulterado e rejeitado no conteudo ou no cabecalho`() {
        val file = sealed()
        val body = file.copyOf().also { it[it.size - 100] = (it[it.size - 100] + 1).toByte() }
        assertThrows(BackupAuthException::class.java) { BackupCrypto.decrypt(password, body) }
        val tag = file.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
        assertThrows(BackupAuthException::class.java) { BackupCrypto.decrypt(password, tag) }
        // Trocar o nonce no cabecalho (AAD) também invalida.
        val headerLen = ByteBuffer.wrap(file, 8, 4).int
        val header = String(file, 12, headerLen, Charsets.UTF_8)
        val nonce = Regex(""""nonce":"([^"]+)"""").find(header)!!.groupValues[1]
        val otherNonce = java.util.Base64.getEncoder().encodeToString(ByteArray(12) { 7 })
        val forged = header.replace(nonce, otherNonce).toByteArray(Charsets.UTF_8)
        assertEquals(headerLen, forged.size)
        val withForgedHeader = file.copyOf().also { forged.copyInto(it, 12) }
        assertThrows(BackupAuthException::class.java) { BackupCrypto.decrypt(password, withForgedHeader) }
    }

    @Test
    fun `formato invalido e parametros fora dos limites`() {
        assertThrows(BackupFormatException::class.java) { BackupCrypto.decrypt(password, "nao e backup".toByteArray()) }
        assertThrows(BackupFormatException::class.java) { BackupCrypto.decrypt(password, ByteArray(3)) }
        val file = sealed()
        val headerLen = ByteBuffer.wrap(file, 8, 4).int
        val header = String(file, 12, headerLen, Charsets.UTF_8)
        // Iterações absurdas (ataque de negação de serviço) são recusadas antes de derivar a chave.
        val huge = header.replace("\"iter\":100000", "\"iter\":999999").toByteArray()
        assertEquals(headerLen, huge.size)
        assertThrows(BackupAuthException::class.java) { BackupCrypto.decrypt(password, file.copyOf().also { huge.copyInto(it, 12) }) }
        val tooMany = header.replace("\"iter\":100000", "\"iter\":9999999").toByteArray()
        val rebuilt = ByteBuffer.allocate(8 + 4 + tooMany.size + file.size - 12 - headerLen)
            .put(file, 0, 8).putInt(tooMany.size).put(tooMany).put(file, 12 + headerLen, file.size - 12 - headerLen).array()
        assertThrows(BackupFormatException::class.java) { BackupCrypto.decrypt(password, rebuilt) }
    }

    @Test
    fun `senha curta nao exporta`() {
        assertThrows(IllegalArgumentException::class.java) { BackupCrypto.encrypt("curta".toCharArray(), manifest, db) }
    }

    @Test
    fun `manifesto ida e volta`() {
        val m = BackupManifest(
            schemaVersion = 4,
            createdAtEpochMs = 1_700_000_000_000,
            appVersion = "x",
            sha256 = "ab",
            tables = mapOf("transactions" to TableStats(3, 1_500), "accounts" to TableStats(1)),
            balanceCents = -500,
        )
        val back = BackupManifest.fromJson(m.toJson())
        assertEquals(m, back)
        assertTrue(back.sameTotals(StatsSnapshot(m.tables, -500)))
        assertFalse(back.sameTotals(StatsSnapshot(m.tables, -501)))
    }

    @Test
    fun `politica do bloqueio do app`() {
        val t0 = Instant.parse("2026-10-22T12:00:00Z")
        assertFalse(AppLock.shouldLock(false, 1, false, null, t0)) // desligado
        assertTrue(AppLock.shouldLock(true, 1, false, null, t0)) // abertura a frio
        assertFalse(AppLock.shouldLock(true, 1, true, null, t0)) // já desbloqueado, sem ir ao fundo
        assertFalse(AppLock.shouldLock(true, 1, true, t0, t0.plusSeconds(59))) // 59 s fora
        assertTrue(AppLock.shouldLock(true, 1, true, t0, t0.plusSeconds(60))) // 1 min exato
        assertTrue(AppLock.shouldLock(true, 0, true, t0, t0)) // "sempre ao voltar"
        val lock = AppLock()
        lock.onForeground(true, 1, t0)
        assertTrue(lock.locked.value)
        lock.unlock()
        lock.onBackground(t0.plusSeconds(10))
        lock.onForeground(true, 1, t0.plusSeconds(30))
        assertFalse(lock.locked.value)
        lock.onBackground(t0.plusSeconds(40))
        lock.onForeground(true, 1, t0.plusSeconds(100))
        assertTrue(lock.locked.value)
    }

    @Test
    fun `lembrete de backup`() {
        val now = Instant.parse("2026-10-22T12:00:00Z")
        assertNull(AlertRules.backupReminder(null, null, 30, now)) // sem dados
        assertNull(AlertRules.backupReminder(now.minus(Duration.ofDays(29)), null, 30, now))
        assertNull(AlertRules.backupReminder(now.minus(Duration.ofDays(40)), null, 0, now)) // desligado
        val first = AlertRules.backupReminder(null, now.minus(Duration.ofDays(30)), 30, now)
        assertNotNull(first)
        first!!
        assertTrue(first.text.contains("ainda não exportou"))
        val last = now.minus(Duration.ofDays(65))
        val a = AlertRules.backupReminder(last, null, 30, now)!!
        assertTrue(a.text.contains("65 dias"))
        // Mesma chave dentro do mesmo período; muda no período seguinte.
        assertEquals(a.key, AlertRules.backupReminder(last, null, 30, now.plus(Duration.ofDays(20)))!!.key)
        assertFalse(a.key == AlertRules.backupReminder(last, null, 30, now.plus(Duration.ofDays(30)))!!.key)
    }
}
