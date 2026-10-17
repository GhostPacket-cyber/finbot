package br.com.finbot.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfigValidatorTest {

    // Valor sintético com o formato do @BotFather; não é um token real.
    private val fakeToken = "123456789:AAFakeTokenValue_ForTests-0123456789ab"

    private fun validForm() = ConfigForm(
        telegramToken = fakeToken,
        user1Id = "111111111",
        user2Id = "222222222",
        timezone = "America/Sao_Paulo",
    )

    @Test
    fun `formulario valido nao tem erros`() {
        assertFalse(ConfigValidator.validate(validForm(), hasStoredToken = false).hasErrors)
    }

    @Test
    fun `token obrigatorio quando nao ha token salvo`() {
        val errors = ConfigValidator.validate(validForm().copy(telegramToken = ""), hasStoredToken = false)
        assertNotNull(errors.telegramToken)
    }

    @Test
    fun `token vazio mantem o salvo`() {
        val errors = ConfigValidator.validate(validForm().copy(telegramToken = ""), hasStoredToken = true)
        assertNull(errors.telegramToken)
    }

    @Test
    fun `token com formato invalido e rejeitado`() {
        listOf("abc", "123:curto", "123456789-AAFakeTokenValue_ForTests-0123456789ab", "12345:com espaço no meio do token xxxxx")
            .forEach { assertNotNull(it, ConfigValidator.telegramTokenError(it, hasStoredToken = false)) }
    }

    @Test
    fun `chave gemini e opcional mas validada quando informada`() {
        assertNull(ConfigValidator.geminiKeyError(""))
        assertNotNull(ConfigValidator.geminiKeyError("curta"))
        assertNotNull(ConfigValidator.geminiKeyError("chave com espacos 1234567890"))
        assertNull(ConfigValidator.geminiKeyError("x".repeat(39)))
    }

    @Test
    fun `primeiro usuario obrigatorio e segundo opcional`() {
        assertNotNull(ConfigValidator.validate(validForm().copy(user1Id = ""), false).user1Id)
        assertNull(ConfigValidator.validate(validForm().copy(user2Id = ""), false).user2Id)
    }

    @Test
    fun `ids invalidos sao rejeitados`() {
        listOf("0", "-5", "12a", "1234567890123456", "1.5").forEach {
            assertNotNull(it, ConfigValidator.userIdError(it, required = true))
        }
    }

    @Test
    fun `ids iguais sao rejeitados`() {
        val errors = ConfigValidator.validate(validForm().copy(user2Id = "111111111"), false)
        assertNotNull(errors.user2Id)
    }

    @Test
    fun `ids autorizados preservam ordem e ignoram vazio`() {
        assertEquals(listOf(111111111L, 222222222L), ConfigValidator.authorizedIds(validForm()))
        assertEquals(listOf(111111111L), ConfigValidator.authorizedIds(validForm().copy(user2Id = "")))
    }

    @Test
    fun `ids acima de 32 bits sao aceitos`() {
        assertEquals(7_000_000_000L, ConfigValidator.parseUserId("7000000000"))
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
    fun `errors hasErrors reflete qualquer campo`() {
        assertTrue(ConfigErrors(timezone = "x").hasErrors)
        assertFalse(ConfigErrors().hasErrors)
    }
}
