package br.com.finbot.security

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import java.security.KeyStore

@RunWith(AndroidJUnit4::class)
class KeystoreSecretCipherTest {

    private val alias = "finbot_test_key"
    private val cipher = KeystoreSecretCipher(alias)
    private val plain = "chave-sintetica-de-teste-0123456789wxyz".toByteArray()

    @After
    fun deleteKey() {
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias)
    }

    @Test
    fun roundTrip() {
        assertArrayEquals(plain, cipher.decrypt(cipher.encrypt(plain)))
    }

    @Test
    fun ivAleatorioGeraBlobsDiferentes() {
        assertFalse(cipher.encrypt(plain).contentEquals(cipher.encrypt(plain)))
    }

    @Test(expected = Exception::class)
    fun blobAdulteradoFalha() {
        val blob = cipher.encrypt(plain)
        blob[blob.size - 1] = (blob.last().toInt() xor 1).toByte()
        cipher.decrypt(blob)
    }

    @Test(expected = Exception::class)
    fun chaveApagadaImpedeLeitura() {
        val blob = cipher.encrypt(plain)
        deleteKey()
        cipher.decrypt(blob)
    }
}
