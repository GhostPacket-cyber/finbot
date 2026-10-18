package br.com.finbot.config

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigValidatorTest {

    @Test
    fun `formulario padrao e valido`() {
        assertFalse(ConfigValidator.validate(ConfigForm()).hasErrors)
    }

    @Test
    fun `nome e opcional e limitado`() {
        assertNull(ConfigValidator.displayNameError(""))
        assertNull(ConfigValidator.displayNameError("Luís Felipe"))
        assertNull(ConfigValidator.displayNameError("a".repeat(ConfigValidator.MAX_DISPLAY_NAME)))
        assertNotNull(ConfigValidator.displayNameError("a".repeat(ConfigValidator.MAX_DISPLAY_NAME + 1)))
        assertNotNull(ConfigValidator.displayNameError("nome\u0000oculto"))
    }

    @Test
    fun `chave gemini e opcional mas validada quando informada`() {
        assertNull(ConfigValidator.geminiKeyError(""))
        assertNotNull(ConfigValidator.geminiKeyError("curta"))
        assertNotNull(ConfigValidator.geminiKeyError("chave com espacos 1234567890"))
        assertNull(ConfigValidator.geminiKeyError("x".repeat(39)))
    }

    @Test
    fun `fuso horario`() {
        assertNull(ConfigValidator.timezoneError("America/Sao_Paulo"))
        assertNull(ConfigValidator.timezoneError("America/Manaus"))
        assertNotNull(ConfigValidator.timezoneError(""))
        assertNotNull(ConfigValidator.timezoneError("Marte/Olympus"))
        assertNotNull(ConfigValidator.timezoneError("-03:00"))
        assertNotNull(ConfigValidator.timezoneError("UTC"))
    }

    @Test
    fun `validate agrega erros de cada campo`() {
        val errors = ConfigValidator.validate(ConfigForm(displayName = "a".repeat(50), timezone = "x", geminiKey = "curta"))
        assertNotNull(errors.displayName)
        assertNotNull(errors.timezone)
        assertNotNull(errors.geminiKey)
        assertTrue(errors.hasErrors)
    }
}
