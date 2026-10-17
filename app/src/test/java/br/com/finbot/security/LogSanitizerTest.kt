package br.com.finbot.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogSanitizerTest {

    private val fakeToken = "123456789:AAFakeTokenValue_ForTests-0123456789ab"
    private val fakeGoogleKey = "AIza" + "B".repeat(35)

    @Test
    fun `remove token do telegram em texto e url`() {
        val out = LogSanitizer.sanitize("GET https://api.telegram.org/bot$fakeToken/getUpdates falhou; token=$fakeToken")
        assertFalse(out.contains(fakeToken))
        assertTrue(out.contains("[TOKEN_REMOVIDO]"))
    }

    @Test
    fun `remove chave google e parametros sensiveis`() {
        val out = LogSanitizer.sanitize("url=https://x.googleapis.com/v1/m?key=segredo123&alt=json chave $fakeGoogleKey")
        assertFalse(out.contains("segredo123"))
        assertFalse(out.contains(fakeGoogleKey))
        assertTrue(out.contains("alt=json"))
    }

    @Test
    fun `remove cabecalhos de autenticacao`() {
        val out = LogSanitizer.sanitize("x-goog-api-key: abcdef123 Authorization: Bearer tok.en.value")
        assertFalse(out.contains("abcdef123"))
        assertFalse(out.contains("tok.en.value"))
    }

    @Test
    fun `texto sem segredo permanece igual`() {
        assertEquals("Configuração salva", LogSanitizer.sanitize("Configuração salva"))
    }

    @Test
    fun `texto longo e truncado`() {
        assertTrue(LogSanitizer.sanitize("a".repeat(1000)).length <= 301)
    }
}
