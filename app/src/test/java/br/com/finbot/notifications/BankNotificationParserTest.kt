package br.com.finbot.notifications

import br.com.finbot.data.TransactionKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/** Amostras **sintéticas** no estilo das notificações dos bancos (nomes e valores fictícios). */
class BankNotificationParserTest {
    private val zone = ZoneId.of("America/Sao_Paulo")
    private val posted = LocalDateTime.of(2026, 10, 21, 14, 35, 27).atZone(zone).toInstant()

    private fun parse(vararg texts: String) = BankNotificationParser.parse(texts.toList(), posted, zone)

    private fun at(month: Int, day: Int, h: Int, m: Int, year: Int = 2026) = LocalDateTime.of(year, month, day, h, m).atZone(zone).toInstant()

    private fun check(
        event: BankNotificationEvent?,
        type: BankEventType,
        cents: Long,
        who: String,
        occurredAt: Instant = at(10, 21, 14, 35),
    ) {
        requireNotNull(event) { "não reconheceu" }
        assertEquals(type, event.type)
        assertEquals(cents, event.amountCents)
        assertEquals(who, event.counterpart)
        assertEquals(occurredAt, event.occurredAt)
    }

    @Test
    fun `compra no credito em estilos diferentes`() {
        // Estilo Nubank
        check(
            parse("Compra aprovada", "Compra de R$ 45,90 APROVADA em PADARIA SÃO JOSÉ para o cartão com final 1234."),
            BankEventType.PURCHASE_CREDIT, 4_590, "PADARIA SÃO JOSÉ",
        )
        // Estilo Itaú, com data e hora no texto
        check(
            parse("Itaú", "Compra aprovada no seu cartão final 1234 de R$ 1.234,56 em 21/10 às 13:02 em LOJA FICTICIA LTDA."),
            BankEventType.PURCHASE_CREDIT, 123_456, "LOJA FICTICIA LTDA", at(10, 21, 13, 2),
        )
        // Título + texto curto
        check(
            parse("Compra aprovada", "Compra de R$ 9,99 em App de Música"),
            BankEventType.PURCHASE_CREDIT, 999, "App de Música",
        )
        check(
            parse("Compra realizada: R$ 120,00 no Posto Exemplo, cartão final 9876"),
            BankEventType.PURCHASE_CREDIT, 12_000, "Posto Exemplo",
        )
    }

    @Test
    fun `compra no debito`() {
        check(
            parse("Compra no débito", "Compra no débito de R$ 12,00 aprovada em MERCADO BOM PRECO"),
            BankEventType.PURCHASE_DEBIT, 1_200, "MERCADO BOM PRECO",
        )
        check(
            parse("Débito de R$ 75,50 em Farmácia Popular Teste via cartão final 1111"),
            BankEventType.PURCHASE_DEBIT, 7_550, "Farmácia Popular Teste",
        )
    }

    @Test
    fun `pix enviado e recebido`() {
        check(parse("Pix enviado", "Você enviou um Pix de R$ 50,00 para Maria Exemplo."), BankEventType.PIX_SENT, 5_000, "Maria Exemplo")
        check(parse("Pix enviado com sucesso: R$ 1.000,00 para João Teste"), BankEventType.PIX_SENT, 100_000, "João Teste")
        check(parse("Transferência Pix de R$ 30,00 enviada para Ana Fictícia"), BankEventType.PIX_SENT, 3_000, "Ana Fictícia")
        check(
            parse("Pix recebido", "Você recebeu um Pix de R$ 100,00 de Carlos Exemplo"),
            BankEventType.PIX_RECEIVED, 10_000, "Carlos Exemplo",
        )
        check(parse("Pix recebido: R$ 25,50 de Loja Teste"), BankEventType.PIX_RECEIVED, 2_550, "Loja Teste")
        check(parse("Bia Fictícia te enviou R$ 15,00 por Pix"), BankEventType.PIX_RECEIVED, 1_500, "Bia Fictícia")
        assertEquals(TransactionKind.INCOME, parse("Pix recebido: R$ 25,50 de Loja Teste")!!.kind)
        assertEquals("Pix para Maria Exemplo", parse("Você enviou um Pix de R$ 50,00 para Maria Exemplo.")!!.description)
    }

    @Test
    fun `transferencia recebida e pagamento`() {
        check(
            parse("Você recebeu uma transferência TED de R$ 2.500,00 de Empresa Fictícia SA"),
            BankEventType.TRANSFER_RECEIVED, 250_000, "Empresa Fictícia SA",
        )
        check(parse("Você pagou R$ 32,90 para Lanchonete Teste"), BankEventType.PAYMENT_SENT, 3_290, "Lanchonete Teste")
    }

    @Test
    fun `ignora o que nao e lancamento`() {
        assertNull(parse("Compra recusada", "Compra de R$ 45,90 em PADARIA X não aprovada: saldo insuficiente"))
        assertNull(parse("Compra de R$ 45,90 em PADARIA X foi negada"))
        assertNull(parse("Seu código de verificação é 123456"))
        assertNull(parse("Pix agendado de R$ 50,00 para Maria Exemplo em 25/10"))
        assertNull(parse("Estorno de R$ 45,90 em PADARIA X"))
        assertNull(parse("Sua fatura de R$ 1.234,56 fecha amanhã"))
        assertNull(parse("Promoção: ganhe R$ 10,00 de cashback!"))
        assertNull(parse("Você tem uma nova mensagem"))
        assertNull(parse(""))
        assertNull(parse("Compra de R$ 0,00 aprovada em Teste"))
    }

    @Test
    fun `data e hora`() {
        // Sem data no texto: usa a notificação, truncada ao minuto.
        assertEquals(at(10, 21, 14, 35), BankNotificationParser.occurredAt("sem data", posted, zone))
        // Só hora.
        assertEquals(at(10, 21, 9, 15), BankNotificationParser.occurredAt("às 09:15", posted, zone))
        // Data sem ano no futuro = ano anterior.
        assertEquals(at(12, 31, 23, 50, 2025), BankNotificationParser.occurredAt("em 31/12 às 23:50", posted, zone))
        // Nunca depois da notificação.
        assertEquals(at(10, 21, 14, 35), BankNotificationParser.occurredAt("em 21/10 às 18:00", posted, zone))
        assertEquals(at(10, 20, 8, 0), BankNotificationParser.occurredAt("20/10/2026 08h00", posted, zone))
    }
}
