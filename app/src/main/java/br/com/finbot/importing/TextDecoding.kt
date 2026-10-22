package br.com.finbot.importing

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * Decodifica extratos em UTF-8, ISO-8859-1 ou Windows-1252 (comuns em bancos brasileiros).
 * Ordem: BOM UTF-8 → cabeçalho OFX (CHARSET/ENCODING) → UTF-8 estrito → Windows-1252.
 */
object TextDecoding {
    const val MAX_BYTES = 5 * 1024 * 1024

    private val UTF8 = Charsets.UTF_8
    private val WINDOWS_1252: Charset = Charset.forName("windows-1252")

    fun decode(bytes: ByteArray): String {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return String(bytes, 3, bytes.size - 3, UTF8)
        }
        declaredCharset(bytes)?.let { return String(bytes, it) }
        return strictUtf8(bytes) ?: String(bytes, WINDOWS_1252)
    }

    /** Lê o cabeçalho OFX (primeiros bytes são ASCII): `CHARSET:1252`, `ENCODING:UTF-8` ou `<?xml ... encoding="...">`. */
    private fun declaredCharset(bytes: ByteArray): Charset? {
        val head = String(bytes, 0, minOf(bytes.size, 512), Charsets.US_ASCII).uppercase()
        Regex("ENCODING=\"([A-Z0-9_-]+)\"").find(head)?.let { return charsetOrNull(it.groupValues[1]) }
        val encoding = Regex("ENCODING:\\s*([A-Z0-9_-]+)").find(head)?.groupValues?.get(1)
        val charset = Regex("CHARSET:\\s*([A-Z0-9_-]+)").find(head)?.groupValues?.get(1)
        return when {
            encoding == "UTF-8" || encoding == "UTF8" -> UTF8
            charset == "1252" -> WINDOWS_1252
            charset == "8859-1" || charset == "ISO-8859-1" -> Charsets.ISO_8859_1
            // OFX 1.x costuma vir "ENCODING:USASCII / CHARSET:NONE" mesmo com acentos em Latin-1: deixa a detecção decidir.
            else -> null
        }
    }

    private fun charsetOrNull(name: String): Charset? = runCatching { Charset.forName(name) }.getOrNull()

    private fun strictUtf8(bytes: ByteArray): String? = try {
        UTF8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: CharacterCodingException) {
        null
    }

    /** OFX ou CSV, pelo conteúdo (a extensão e o tipo MIME do seletor não são confiáveis). */
    fun detectFormat(text: String): StatementFormat {
        val head = text.take(4096).uppercase()
        return if ("OFXHEADER" in head || "<OFX>" in head) StatementFormat.OFX else StatementFormat.CSV
    }
}
