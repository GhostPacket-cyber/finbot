package br.com.finbot.config

import br.com.finbot.testutil.FileDataStoreHandle
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Instant

class ConfigRepositoryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `padroes antes de configurar`() = runTest {
        val handle = FileDataStoreHandle(tmp.newFolder().resolve("c.preferences_pb"))
        val config = ConfigRepository(handle.dataStore).config.first()
        assertEquals(emptyList<Long>(), config.authorizedUserIds)
        assertEquals("America/Sao_Paulo", config.timezone)
        assertFalse(config.aiConsentGranted)
        handle.close()
    }

    @Test
    fun `configuracao persiste apos reinicio`() = runTest {
        val file = tmp.newFolder().resolve("c.preferences_pb")
        val consentAt = Instant.parse("2026-10-17T12:00:00Z")
        val first = FileDataStoreHandle(file)
        ConfigRepository(first.dataStore).apply {
            saveUsersAndTimezone(listOf(111L, 7_000_000_000L), "America/Manaus")
            setAiConsent(true, consentAt)
        }
        first.close()

        val second = FileDataStoreHandle(file)
        val config = ConfigRepository(second.dataStore).config.first()
        assertEquals(listOf(111L, 7_000_000_000L), config.authorizedUserIds)
        assertEquals("America/Manaus", config.timezone)
        assertTrue(config.aiConsentGranted)
        assertEquals(consentAt, config.aiConsentAt)
        second.close()
    }

    @Test
    fun `remover segundo usuario e revogar consentimento`() = runTest {
        val handle = FileDataStoreHandle(tmp.newFolder().resolve("c.preferences_pb"))
        val repo = ConfigRepository(handle.dataStore)
        repo.saveUsersAndTimezone(listOf(1L, 2L), "America/Sao_Paulo")
        repo.setAiConsent(true, Instant.EPOCH)
        repo.saveUsersAndTimezone(listOf(1L), "America/Sao_Paulo")
        repo.setAiConsent(false, Instant.EPOCH)

        val config = repo.config.first()
        assertEquals(listOf(1L), config.authorizedUserIds)
        assertFalse(config.aiConsentGranted)
        assertNull(config.aiConsentAt)
        handle.close()
    }

    @Test(expected = IllegalArgumentException::class)
    fun `mais de dois usuarios e rejeitado`() = runTest {
        val handle = FileDataStoreHandle(tmp.newFolder().resolve("c.preferences_pb"))
        try {
            ConfigRepository(handle.dataStore).saveUsersAndTimezone(listOf(1L, 2L, 3L), "America/Sao_Paulo")
        } finally {
            handle.close()
        }
    }
}
