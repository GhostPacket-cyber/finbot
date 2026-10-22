package br.com.finbot.importing

import br.com.finbot.data.TransactionKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class StatementParsingTest {

    private fun bytes(name: String): ByteArray =
        requireNotNull(javaClass.classLoader!!.getResourceAsStream("importing/$name")) { "recurso $name" }.use { it.readBytes() }

    private fun text(name: String) = TextDecoding.decode(bytes(name))

    // ---------- OFX ----------

    @Test
    fun `OFX SGML em ISO-8859-1 com acentos e tags sem fechamento`() {
        val raw = bytes("ofx_sgml_latin1.ofx")
        assertTrue("arquivo deve ser latin1 de verdade", raw.contains(0xC3.toByte()))
        val decoded = TextDecoding.decode(raw)
        assertEquals(StatementFormat.OFX, TextDecoding.detectFormat(decoded))

        val st = OfxParser.parse(decoded)
        assertEquals(emptyList<RowError>(), st.errors)
        assertEquals(3, st.items.size)
        assertEquals("99887766-5", st.accountId)
        assertEquals("0999", st.bankId)
        assertEquals("••••66-5", Masking.account(st.accountId))

        val (padaria, salario, farmacia) = st.items
        assertEquals("PADARIA SÃO JOSÉ", padaria.description)
        assertEquals(TransactionKind.EXPENSE, padaria.kind)
        assertEquals(2390L, padaria.amountCents)
        assertEquals(LocalDate.of(2026, 10, 2), padaria.date)
        assertEquals("TESTE0001", padaria.externalReference)

        assertEquals("SALÁRIO EMPRESA FICTÍCIA", salario.description)
        assertEquals(TransactionKind.INCOME, salario.kind)
        assertEquals(450_000L, salario.amountCents)

        assertEquals("FARMÁCIA AÇAÍ · Compra cartão débito", farmacia.description)
        assertEquals(123_456L, farmacia.amountCents)
    }

    @Test
    fun `OFX latin1 sem charset declarado cai para Windows-1252`() {
        val raw = String(bytes("ofx_sgml_latin1.ofx"), Charsets.ISO_8859_1)
            .replace("CHARSET:8859-1", "CHARSET:NONE").toByteArray(Charsets.ISO_8859_1)
        val st = OfxParser.parse(TextDecoding.decode(raw))
        assertEquals("PADARIA SÃO JOSÉ", st.items.first().description)
    }

    @Test
    fun `OFX 2 em XML UTF-8 com entidades`() {
        val decoded = text("ofx_xml_utf8.ofx")
        assertEquals(StatementFormat.OFX, TextDecoding.detectFormat(decoded))
        val st = OfxParser.parse(decoded)
        assertEquals(emptyList<RowError>(), st.errors)
        assertEquals(2, st.items.size)
        assertEquals("Supermercado Pão & Cia", st.items[0].description)
        assertEquals(8990L, st.items[0].amountCents)
        assertEquals(15_000L, st.items[1].amountCents)
        assertEquals("Pix recebido", st.items[1].description)
        assertEquals("11223344", st.accountId)
    }

    @Test
    fun `OFX chave de dedup e estavel e depende da conta`() {
        val a = OfxParser.parse(text("ofx_sgml_latin1.ofx"))
        val b = OfxParser.parse(text("ofx_sgml_latin1.ofx"))
        assertEquals(a.items.map { it.dedupKey }, b.items.map { it.dedupKey })
        assertEquals(3, a.items.map { it.dedupKey }.toSet().size)
        assertNotEquals(DedupKeys.ofx("111", "F1"), DedupKeys.ofx("222", "F1"))
        assertTrue(a.items.all { it.dedupKey.startsWith("ofx:") && "99887766" !in it.dedupKey })
    }

    @Test
    fun `OFX linhas invalidas viram erros e o resto continua`() {
        val ofx = """
            <OFX><BANKTRANLIST>
            <STMTTRN><DTPOSTED>20261001<TRNAMT>-10.00<FITID>A
            <STMTTRN><DTPOSTED>20261341<TRNAMT>-10.00<FITID>B
            <STMTTRN><DTPOSTED>20261001<TRNAMT>abc<FITID>C
            <STMTTRN><DTPOSTED>20261001<TRNAMT>-5.00
            <STMTTRN><DTPOSTED>20261001<TRNAMT>0.00<FITID>E
            </BANKTRANLIST></OFX>
        """.trimIndent()
        val st = OfxParser.parse(ofx)
        assertEquals(1, st.items.size)
        assertEquals("Lançamento", st.items[0].description)
        assertEquals(listOf(2, 3, 4, 5), st.errors.map { it.line })
    }

    @Test
    fun `OFX valor`() {
        assertEquals(-123_456L, OfxParser.parseAmount("-1234.56"))
        assertEquals(10_000L, OfxParser.parseAmount("100"))
        assertEquals(10_000L, OfxParser.parseAmount("+100.0"))
        assertEquals(-990L, OfxParser.parseAmount("-9,90"))
        assertNull(OfxParser.parseAmount("1.234.56"))
        assertNull(OfxParser.parseAmount("1.235"))
        assertNull(OfxParser.parseAmount(""))
    }

    // ---------- Codificação ----------

    @Test
    fun `decodifica UTF-8 com BOM, UTF-8 sem BOM e Windows-1252`() {
        val s = "Ação;Café"
        assertEquals(s, TextDecoding.decode(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + s.toByteArray()))
        assertEquals(s, TextDecoding.decode(s.toByteArray(Charsets.UTF_8)))
        assertEquals(s, TextDecoding.decode(s.toByteArray(charset("windows-1252"))))
        assertEquals(StatementFormat.CSV, TextDecoding.detectFormat(s))
    }

    // ---------- CSV ----------

    @Test
    fun `CSV com ponto e virgula, R$ -1234,56, cabecalho, rodape e Windows-1252`() {
        val decoded = text("csv_pontovirgula_1252.csv")
        val mapping = requireNotNull(CsvImporter.suggestMapping(decoded))
        assertEquals(';', mapping.separator)
        assertEquals(3, mapping.skipLines)
        assertEquals(0, mapping.dateColumn)
        assertEquals(1, mapping.descriptionColumn)
        assertEquals(2, mapping.amountColumn)
        assertEquals(CsvDateFormat.DMY, mapping.dateFormat)

        val st = CsvImporter.parse(decoded, mapping)
        assertEquals(emptyList<RowError>(), st.errors)
        assertEquals(listOf("Aluguel outubro", "Pix recebido João", "Mercado São Jorge"), st.items.map { it.description })
        val aluguel = st.items[0]
        assertEquals(TransactionKind.EXPENSE, aluguel.kind)
        assertEquals(123_456L, aluguel.amountCents)
        assertEquals(LocalDate.of(2026, 10, 3), aluguel.date)
        assertEquals(TransactionKind.INCOME, st.items[1].kind)
        assertEquals(50_000L, st.items[1].amountCents)
        assertEquals(8730L, st.items[2].amountCents)
    }

    @Test
    fun `CSV com virgula, ponto decimal, data ISO e aspas`() {
        val decoded = text("csv_virgula_ponto.csv")
        val mapping = requireNotNull(CsvImporter.suggestMapping(decoded))
        assertEquals(',', mapping.separator)
        assertEquals(CsvDateFormat.ISO, mapping.dateFormat)
        val st = CsvImporter.parse(decoded, mapping)
        assertEquals(4, st.items.size)
        assertEquals("Uber, viagem", st.items[2].description)
        assertEquals(2340L, st.items[2].amountCents)
        assertEquals(30_000L, st.items[3].amountCents)
        assertEquals(TransactionKind.INCOME, st.items[3].kind)
    }

    @Test
    fun `CSV itens identicos no mesmo dia tem chaves distintas e estaveis`() {
        val decoded = text("csv_virgula_ponto.csv")
        val mapping = CsvImporter.suggestMapping(decoded)!!
        val a = CsvImporter.parse(decoded, mapping).items.map { it.dedupKey }
        val b = CsvImporter.parse(decoded, mapping).items.map { it.dedupKey }
        assertEquals(a, b)
        assertEquals(4, a.toSet().size)
        assertTrue(a.all { it.startsWith("csv:") })
        // Descrição normalizada: caixa e acento não mudam a chave.
        assertEquals(
            DedupKeys.csv(LocalDate.of(2026, 10, 1), TransactionKind.EXPENSE, 1250, "Café da manhã", 0),
            DedupKeys.csv(LocalDate.of(2026, 10, 1), TransactionKind.EXPENSE, 1250, "CAFE DA MANHA", 0),
        )
    }

    @Test
    fun `CSV com colunas de debito e credito`() {
        val decoded = text("csv_debito_credito.csv")
        val mapping = requireNotNull(CsvImporter.suggestMapping(decoded))
        assertNull(mapping.amountColumn)
        assertEquals(2, mapping.debitColumn)
        assertEquals(3, mapping.creditColumn)
        val st = CsvImporter.parse(decoded, mapping)
        assertEquals(emptyList<RowError>(), st.errors)
        assertEquals(listOf(TransactionKind.EXPENSE, TransactionKind.INCOME, TransactionKind.EXPENSE), st.items.map { it.kind })
        assertEquals(listOf(15_000L, 4_500L, 1_290L), st.items.map { it.amountCents })
        assertEquals("Conta de luz", st.items[0].description)
    }

    @Test
    fun `CSV mapeamento manual, rodape, inversao de sinal e erros por linha`() {
        val csv = "x;y;z\n01/10/26;Loja;10,00\n32/10/26;Loja;5,00\n02/10/26;Loja;abc\nTOTAL;;15,00\n"
        val m = CsvMapping(';', skipLines = 1, dateColumn = 0, descriptionColumn = 1, amountColumn = 2,
            dateFormat = CsvDateFormat.DMY_SHORT, invertSign = true)
        val st = CsvImporter.parse(csv, m)
        assertEquals(1, st.items.size)
        assertEquals(TransactionKind.EXPENSE, st.items[0].kind)
        assertEquals(LocalDate.of(2026, 10, 1), st.items[0].date)
        assertEquals(listOf(3, 4, 5), st.errors.map { it.line })

        val semRodape = CsvImporter.parse(csv, m.copy(skipFooterLines = 1))
        assertEquals(listOf(3, 4), semRodape.errors.map { it.line })
    }

    @Test
    fun `valores de CSV no formato dos bancos`() {
        assertEquals(-123_456L, CsvImporter.parseAmount("R$ -1.234,56"))
        assertEquals(-123_456L, CsvImporter.parseAmount("-R$ 1.234,56"))
        assertEquals(-123_456L, CsvImporter.parseAmount("(1.234,56)"))
        assertEquals(-123_456L, CsvImporter.parseAmount("1.234,56 D"))
        assertEquals(123_456L, CsvImporter.parseAmount("1.234,56 C"))
        assertEquals(-1_250L, CsvImporter.parseAmount("-12.50"))
        assertNull(CsvImporter.parseAmount(""))
        assertNull(CsvImporter.parseAmount("R$ abc"))
    }

    @Test
    fun `leitor CSV respeita aspas duplas escapadas e quebra de linha entre aspas`() {
        val rows = CsvReader.read("a;\"b;\"\"c\"\"\";\"linha\nnova\"\r\n1;2;3", ';')
        assertEquals(listOf(listOf("a", "b;\"c\"", "linha\nnova"), listOf("1", "2", "3")), rows)
        assertEquals(';', CsvReader.detectSeparator("a;b;c\n1;\"2,5\";3"))
        assertEquals(',', CsvReader.detectSeparator("a,b,c\n1,2,3"))
    }

    @Test
    fun `cabecalho nao reconhecido nao sugere mapeamento`() {
        assertNull(CsvImporter.suggestMapping("foo;bar\n1;2"))
    }
}
