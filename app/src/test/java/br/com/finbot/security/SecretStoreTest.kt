package br.com.finbot.security

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

    private val token = "123456789:AAFakeTokenValue_ForTests-0123456789ab"

    @Test
    fun `segredo e salvo cifrado e lido de volta`() = runTest {
        val file = tmp.newFolder().resolve("secrets.preferences_pb")
        val handle = FileDataStoreHandle(file)
        val store = SecretStore(handle.dataStore, FakeCipher())

        store.put(SecretKind.TELEGRAM_BOT_TOKEN, "  $token  ")

        assertEquals(token, store.get(SecretKind.TELEGRAM_BOT_TOKEN))
        handle.close()
        assertFalse("arquivo não pode conter o segredo em claro", file.readBytes().toString(Charsets.ISO_8859_1).contains(token))
    }

    @Test
    fun `status mostra apenas dica mascarada`() = runTest {
        val handle = FileDataStoreHandle(tmp.newFolder().resolve("s.preferences_pb"))
        val store = SecretStore(handle.dataStore, FakeCipher())
        store.put(SecretKind.TELEGRAM_BOT_TOKEN, token)

        val statuses = store.statuses.first()
        val status = statuses[SecretKind.TELEGRAM_BOT_TOKEN] as SecretStatus.Configured
        assertEquals("•••• 89ab", status.hint)
        assertFalse(status.hint.contains(token.dropLast(4)))
        assertEquals(SecretStatus.Missing, statuses[SecretKind.GEMINI_API_KEY])
        handle.close()
    }

    @Test
    fun `segredo persiste apos reabrir o datastore`() = runTest {
        val file = tmp.newFolder().resolve("p.preferences_pb")
        val first = FileDataStoreHandle(file)
        SecretStore(first.dataStore, FakeCipher()).put(SecretKind.GEMINI_API_KEY, "chave-gemini-de-teste-123456")
        first.close()

        val second = FileDataStoreHandle(file)
        assertEquals("chave-gemini-de-teste-123456", SecretStore(second.dataStore, FakeCipher()).get(SecretKind.GEMINI_API_KEY))
        second.close()
    }

    @Test
    fun `remover apaga o segredo`() = runTest {
        val handle = FileDataStoreHandle(tmp.newFolder().resolve("r.preferences_pb"))
        val store = SecretStore(handle.dataStore, FakeCipher())
        store.put(SecretKind.TELEGRAM_BOT_TOKEN, token)
        store.remove(SecretKind.TELEGRAM_BOT_TOKEN)
        assertNull(store.get(SecretKind.TELEGRAM_BOT_TOKEN))
        assertEquals(SecretStatus.Missing, store.statuses.first()[SecretKind.TELEGRAM_BOT_TOKEN])
        handle.close()
    }

    @Test
    fun `falha ao decifrar vira status ilegivel`() = runTest {
        val cipher = FakeCipher()
        val handle = FileDataStoreHandle(tmp.newFolder().resolve("u.preferences_pb"))
        val store = SecretStore(handle.dataStore, cipher)
        store.put(SecretKind.TELEGRAM_BOT_TOKEN, token)
        cipher.failDecrypt = true

        assertEquals(SecretStatus.Unreadable, store.statuses.first()[SecretKind.TELEGRAM_BOT_TOKEN])
        assertNull(store.get(SecretKind.TELEGRAM_BOT_TOKEN))
        handle.close()
    }

    @Test
    fun `mascara de segredos curtos nao revela caracteres`() {
        assertEquals("••••", SecretMask.hint("curto"))
        assertTrue(SecretMask.hint("x".repeat(40)).endsWith("xxxx"))
    }
}
