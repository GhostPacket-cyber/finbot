package br.com.finbot.security

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import br.com.finbot.testutil.FakeCipher
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

class SecretStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // Valor sintético; não é uma chave real.
    private val key = "chave-sintetica-de-teste-0123456789wxyz"

    @Test
    fun `segredo e salvo cifrado e lido de volta`() = runTest {
        val file = tmp.newFolder().resolve("secrets.preferences_pb")
        val handle = FileDataStoreHandle(file)
        val store = SecretStore(handle.dataStore, FakeCipher())

        store.put(SecretKind.GEMINI_API_KEY, "  $key  ")

        assertEquals(key, store.get(SecretKind.GEMINI_API_KEY))
        handle.close()
        assertFalse("arquivo não pode conter o segredo em claro", file.readBytes().toString(Charsets.ISO_8859_1).contains(key))
    }

    @Test
    fun `status mostra apenas dica mascarada`() = runTest {
        val handle = FileDataStoreHandle(tmp.newFolder().resolve("s.preferences_pb"))
        val store = SecretStore(handle.dataStore, FakeCipher())
        assertEquals(SecretStatus.Missing, store.statuses.first()[SecretKind.GEMINI_API_KEY])

        store.put(SecretKind.GEMINI_API_KEY, key)

        val status = store.statuses.first()[SecretKind.GEMINI_API_KEY] as SecretStatus.Configured
        assertEquals("•••• wxyz", status.hint)
        assertFalse(status.hint.contains(key.dropLast(4)))
        handle.close()
    }

    @Test
    fun `segredo persiste apos reabrir o datastore`() = runTest {
        val file = tmp.newFolder().resolve("p.preferences_pb")
        val first = FileDataStoreHandle(file)
        SecretStore(first.dataStore, FakeCipher()).put(SecretKind.GEMINI_API_KEY, key)
        first.close()

        val second = FileDataStoreHandle(file)
        assertEquals(key, SecretStore(second.dataStore, FakeCipher()).get(SecretKind.GEMINI_API_KEY))
        second.close()
    }

    @Test
    fun `remover apaga o segredo`() = runTest {
        val handle = FileDataStoreHandle(tmp.newFolder().resolve("r.preferences_pb"))
        val store = SecretStore(handle.dataStore, FakeCipher())
        store.put(SecretKind.GEMINI_API_KEY, key)
        store.remove(SecretKind.GEMINI_API_KEY)
        assertNull(store.get(SecretKind.GEMINI_API_KEY))
        assertEquals(SecretStatus.Missing, store.statuses.first()[SecretKind.GEMINI_API_KEY])
        handle.close()
    }

    @Test
    fun `falha ao decifrar vira status ilegivel`() = runTest {
        val cipher = FakeCipher()
        val handle = FileDataStoreHandle(tmp.newFolder().resolve("u.preferences_pb"))
        val store = SecretStore(handle.dataStore, cipher)
        store.put(SecretKind.GEMINI_API_KEY, key)
        cipher.failDecrypt = true

        assertEquals(SecretStatus.Unreadable, store.statuses.first()[SecretKind.GEMINI_API_KEY])
        assertNull(store.get(SecretKind.GEMINI_API_KEY))
        handle.close()
    }

    @Test
    fun `segredos legados da v1 sao apagados e o atual permanece`() = runTest {
        val handle = FileDataStoreHandle(tmp.newFolder().resolve("l.preferences_pb"))
        val legacyKey = stringPreferencesKey("telegram_bot_token")
        handle.dataStore.edit { it[legacyKey] = "blob-cifrado-antigo" }
        val store = SecretStore(handle.dataStore, FakeCipher())
        store.put(SecretKind.GEMINI_API_KEY, key)

        store.removeLegacy()

        assertNull(handle.dataStore.data.first()[legacyKey])
        assertEquals(key, store.get(SecretKind.GEMINI_API_KEY))
        handle.close()
    }

    @Test
    fun `mascara de segredos curtos nao revela caracteres`() {
        assertEquals("••••", SecretMask.hint("curto"))
        assertTrue(SecretMask.hint("x".repeat(40)).endsWith("xxxx"))
    }
}
