package br.com.finbot.backup

import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Senha errada **ou** arquivo adulterado: o GCM não distingue (e não deve). */
class BackupAuthException : Exception("Senha incorreta ou arquivo alterado.")

/** Não é um arquivo .finbot válido (formato, versão ou parâmetros fora dos limites). */
class BackupFormatException(message: String) : Exception(message)

/**
 * Formato `.finbot` (ETAPA 11):
 * ```
 * "FINBOTX1" | u32 tamanho do cabeçalho | cabeçalho JSON (KDF, sal, nonce) | AES-256-GCM(conteúdo)
 * ```
 * O cabeçalho entra como AAD: alterar parâmetros também invalida a tag.
 * Chave: PBKDF2-HMAC-SHA256 com sal aleatório de 16 bytes e [ITERATIONS] iterações (OWASP 2023).
 * Conteúdo: u32 tamanho do manifesto | manifesto JSON | banco SQLite.
 */
object BackupCrypto {
    private val MAGIC = "FINBOTX1".toByteArray(Charsets.US_ASCII)
    const val ITERATIONS = 600_000
    private const val MIN_ITERATIONS = 100_000
    private const val MAX_ITERATIONS = 5_000_000
    private const val MAX_HEADER = 4_096
    const val MIN_PASSWORD = 10
    private const val SALT_BYTES = 16
    private const val NONCE_BYTES = 12
    private const val TAG_BITS = 128
    private val random = SecureRandom()

    fun encrypt(password: CharArray, manifest: String, db: ByteArray, iterations: Int = ITERATIONS): ByteArray {
        require(password.size >= MIN_PASSWORD) { "A senha precisa ter pelo menos $MIN_PASSWORD caracteres." }
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val header = JSONObject()
            .put("v", 1)
            .put("kdf", "PBKDF2-HMAC-SHA256")
            .put("iter", iterations)
            .put("salt", b64(salt))
            .put("cipher", "AES-256-GCM")
            .put("nonce", b64(nonce))
            .toString().toByteArray(Charsets.UTF_8)
        val prefix = MAGIC + ByteBuffer.allocate(4).putInt(header.size).array() + header

        val manifestBytes = manifest.toByteArray(Charsets.UTF_8)
        val plain = ByteBuffer.allocate(4 + manifestBytes.size + db.size).putInt(manifestBytes.size).put(manifestBytes).put(db).array()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, deriveKey(password, salt, iterations), GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(prefix)
        val sealed = cipher.doFinal(plain)
        plain.fill(0)
        return ByteArrayOutputStream(prefix.size + sealed.size).apply { write(prefix); write(sealed) }.toByteArray()
    }

    data class Opened(val manifest: String, val db: ByteArray)

    fun decrypt(password: CharArray, file: ByteArray): Opened {
        if (file.size < MAGIC.size + 4 || !file.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) {
            throw BackupFormatException("Não é um backup do FinBot (.finbot).")
        }
        val headerLen = ByteBuffer.wrap(file, MAGIC.size, 4).int
        if (headerLen <= 0 || headerLen > MAX_HEADER || MAGIC.size + 4 + headerLen >= file.size) throw BackupFormatException("Cabeçalho inválido.")
        val prefixEnd = MAGIC.size + 4 + headerLen
        val header = try {
            JSONObject(String(file, MAGIC.size + 4, headerLen, Charsets.UTF_8))
        } catch (_: JSONException) {
            throw BackupFormatException("Cabeçalho inválido.")
        }
        if (header.optInt("v") != 1 || header.optString("kdf") != "PBKDF2-HMAC-SHA256" || header.optString("cipher") != "AES-256-GCM") {
            throw BackupFormatException("Versão do backup não suportada.")
        }
        val iterations = header.optInt("iter")
        // Limites evitam um arquivo malicioso que trave o app com milhões de iterações (ou um fraco demais).
        if (iterations !in MIN_ITERATIONS..MAX_ITERATIONS) throw BackupFormatException("Parâmetros de chave fora dos limites.")
        val salt = unb64(header.optString("salt"))
        val nonce = unb64(header.optString("nonce"))
        if (salt.size != SALT_BYTES || nonce.size != NONCE_BYTES) throw BackupFormatException("Parâmetros de chave inválidos.")

        val plain = try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, deriveKey(password, salt, iterations), GCMParameterSpec(TAG_BITS, nonce))
            cipher.updateAAD(file, 0, prefixEnd)
            cipher.doFinal(file, prefixEnd, file.size - prefixEnd)
        } catch (_: GeneralSecurityException) {
            throw BackupAuthException()
        }
        val buffer = ByteBuffer.wrap(plain)
        val manifestLen = buffer.int
        if (manifestLen <= 0 || manifestLen > plain.size - 4) throw BackupFormatException("Conteúdo inválido.")
        val manifest = String(plain, 4, manifestLen, Charsets.UTF_8)
        val db = plain.copyOfRange(4 + manifestLen, plain.size)
        plain.fill(0)
        return Opened(manifest, db)
    }

    private fun deriveKey(password: CharArray, salt: ByteArray, iterations: Int): SecretKeySpec {
        val spec = PBEKeySpec(password, salt, iterations, 256)
        try {
            val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
            return SecretKeySpec(bytes, "AES")
        } finally {
            spec.clearPassword()
        }
    }

    private fun b64(bytes: ByteArray) = Base64.getEncoder().encodeToString(bytes)

    private fun unb64(text: String): ByteArray = try {
        Base64.getDecoder().decode(text)
    } catch (_: IllegalArgumentException) {
        ByteArray(0)
    }
}
