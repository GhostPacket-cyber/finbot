package br.com.finbot.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogSanitizerTest {

    private val fakeGoogleKey = "AIza" + "B".repeat(35)

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
    fun `remove parametro token`() {
        val out = LogSanitizer.sanitize("GET /arquivo?token=abc123segredo&x=1")
        assertFalse(out.contains("abc123segredo"))
        assertTrue(out.contains("x=1"))
    }

    @Test
    fun `texto sem segredo permanece igual`() {
        assertEquals("Ajustes salvos", LogSanitizer.sanitize("Ajustes salvos"))
    }

    @Test
    fun `texto longo e truncado`() {
        assertTrue(LogSanitizer.sanitize("a".repeat(1000)).length <= 301)
    }
}
