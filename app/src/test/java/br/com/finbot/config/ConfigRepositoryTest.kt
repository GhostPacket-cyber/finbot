package br.com.finbot.config

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
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
        assertEquals("", config.displayName)
        assertEquals("America/Sao_Paulo", config.timezone)
        assertFalse(config.onboardingCompleted)
        assertFalse(config.aiConsentGranted)
        handle.close()
    }

    @Test
    fun `onboarding e consentimento persistem apos reinicio`() = runTest {
        val file = tmp.newFolder().resolve("c.preferences_pb")
        val consentAt = Instant.parse("2026-10-17T12:00:00Z")
        val first = FileDataStoreHandle(file)
        ConfigRepository(first.dataStore).apply {
            completeOnboarding("  Luís  ", "America/Manaus")
            setAiConsent(true, consentAt)
        }
        first.close()

        val second = FileDataStoreHandle(file)
        val config = ConfigRepository(second.dataStore).config.first()
        assertEquals("Luís", config.displayName)
        assertEquals("America/Manaus", config.timezone)
        assertTrue(config.onboardingCompleted)
        assertTrue(config.aiConsentGranted)
        assertEquals(consentAt, config.aiConsentAt)
        second.close()
    }

    @Test
    fun `nome vazio remove o nome e revogar limpa a data`() = runTest {
        val handle = FileDataStoreHandle(tmp.newFolder().resolve("c.preferences_pb"))
        val repo = ConfigRepository(handle.dataStore)
        repo.saveProfile("Ana", "America/Sao_Paulo")
        repo.setAiConsent(true, Instant.EPOCH)
        repo.saveProfile("   ", "America/Sao_Paulo")
        repo.setAiConsent(false, Instant.EPOCH)

        val config = repo.config.first()
        assertEquals("", config.displayName)
        assertFalse(config.aiConsentGranted)
        assertNull(config.aiConsentAt)
        handle.close()
    }

    @Test
    fun `chaves legadas da v1 sao removidas sem afetar as atuais`() = runTest {
        val handle = FileDataStoreHandle(tmp.newFolder().resolve("c.preferences_pb"))
        val legacy = longPreferencesKey("authorized_user_1_id")
        handle.dataStore.edit { it[legacy] = 111L }
        val repo = ConfigRepository(handle.dataStore)
        repo.saveProfile("Ana", "America/Recife")

        repo.removeLegacyKeys()

        val prefs = handle.dataStore.data.first()
        assertNull(prefs[legacy])
        assertEquals("America/Recife", repo.config.first().timezone)
        handle.close()
    }
}
