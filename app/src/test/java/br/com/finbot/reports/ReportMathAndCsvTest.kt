package br.com.finbot.reports

import br.com.finbot.data.TransactionKind
import br.com.finbot.data.TransactionSource
import br.com.finbot.data.TransactionStatus
import br.com.finbot.data.dao.TransactionRow
import br.com.finbot.data.entity.TransactionEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

class ReportMathAndCsvTest {

    @Test
    fun `variacao percentual e mes anterior zerado`() {
        assertEquals(25.0, ReportMath.variationPercent(12_500, 10_000)!!, 1e-9)
        assertEquals(-50.0, ReportMath.variationPercent(5_000, 10_000)!!, 1e-9)
        assertNull(ReportMath.variationPercent(5_000, 0))
        assertEquals("+25%", ReportMath.formatPercent(25.0))
        assertEquals("-12,3%", ReportMath.formatPercent(-12.34))
        assertEquals("0%", ReportMath.formatPercent(0.0))
    }

    @Test
    fun `media diaria considera dias transcorridos no mes corrente`() {
        val today = LocalDate.of(2026, 10, 21)
        assertEquals(1_000L, ReportMath.dailyAverage(21_000, YearMonth.of(2026, 10), today))
        assertEquals(1_000L, ReportMath.dailyAverage(30_000, YearMonth.of(2026, 9), today)) // setembro: 30 dias
        assertEquals(0L, ReportMath.dailyAverage(0, YearMonth.of(2026, 10), today))
        assertNull(ReportMath.dailyAverage(100, YearMonth.of(2026, 11), today))
    }

    @Test
    fun `fatias top 5 mais demais preservam o total`() {
        val items = listOf(
            Slice("Moradia", 150_000), Slice("Mercado", 80_000), Slice("Transporte", 30_000), Slice("Lazer", 20_000),
            Slice("Saúde", 15_000), Slice("Educação", 10_000), Slice("Outros", 5_000), Slice("Vazio", 0),
        )
        val slices = ReportMath.topSlices(items)
        assertEquals(listOf("Moradia", "Mercado", "Transporte", "Lazer", "Saúde", ReportMath.OTHERS_LABEL), slices.map { it.label })
        assertEquals(15_000L, slices.last().cents)
        assertEquals(items.sumOf { it.cents }, slices.sumOf { it.cents })
        assertEquals(100.0, ReportMath.shares(slices).sum(), 1e-9)
        // Com 6 itens não faz sentido "Demais" de um só.
        assertEquals(6, ReportMath.topSlices(items.take(6)).size)
        assertEquals(emptyList<Slice>(), ReportMath.topSlices(emptyList()))
        assertEquals(listOf(0.0), ReportMath.shares(listOf(Slice("x", 0))))
    }

    @Test
    fun `serie acumulada`() {
        val points = ReportMath.cumulative(mapOf(1 to 100L, 3 to 50L), 4)
        assertEquals(listOf(100L, 100L, 150L, 150L), points.map { it.cumulativeCents })
        assertEquals(4, points.last().day)
    }

    private fun row(
        kind: TransactionKind, cents: Long, desc: String, category: String?, at: String, note: String? = null,
    ) = TransactionRow(
        TransactionEntity(
            id = 1, uuid = "u", kind = kind, amountCents = cents, description = desc, categoryId = null, accountId = 1,
            occurredAt = Instant.parse(at), createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH,
            source = TransactionSource.MANUAL, status = TransactionStatus.CONFIRMED, note = note,
        ),
        categoryName = category,
        accountName = "Carteira",
    )

    @Test
    fun `csv com bom separador ponto e virgula e decimal com virgula`() {
        val zone = ZoneId.of("America/Sao_Paulo")
        val csv = CsvExporter.export(
            listOf(
                row(TransactionKind.EXPENSE, 123_456, "Mercado", "Mercado", "2026-10-01T02:30:00Z"), // 30/09 local
                row(TransactionKind.INCOME, 5, "Pix; do \"João\"", null, "2026-10-10T15:00:00Z", note = "linha1\nlinha2"),
            ),
            zone,
        )
        assertTrue(csv.startsWith(CsvExporter.BOM))
        val lines = CsvExporter.parse(csv)
        assertEquals(CsvExporter.HEADER, lines[0])
        assertEquals(listOf("30/09/2026", "Despesa", "Mercado", "Mercado", "Carteira", "1234,56", "Manual", ""), lines[1])
        assertEquals(listOf("10/10/2026", "Receita", "Pix; do \"João\"", "Sem categoria", "Carteira", "0,05", "Manual", "linha1\nlinha2"), lines[2])
        assertEquals(3, lines.size)
    }

    @Test
    fun `csv neutraliza formulas`() {
        assertEquals("'=HYPERLINK(\"x\")".let { "\"" + it.replace("\"", "\"\"") + "\"" }, CsvExporter.field("=HYPERLINK(\"x\")"))
        assertEquals("'+55 11", CsvExporter.field("+55 11"))
        assertEquals("'-45", CsvExporter.field("-45"))
        assertEquals("'@soma", CsvExporter.field("@soma"))
        assertEquals("Mercado", CsvExporter.field("Mercado"))
        assertEquals("", CsvExporter.field(""))
        assertEquals("100000000,00", CsvExporter.amount(10_000_000_000))
    }

    @Test
    fun `csv vazio tem so cabecalho`() {
        val lines = CsvExporter.parse(CsvExporter.export(emptyList(), ZoneId.of("UTC")))
        assertEquals(listOf(CsvExporter.HEADER), lines)
    }
}
