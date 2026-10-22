package br.com.finbot.notifications

import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import br.com.finbot.data.TestFinance
import br.com.finbot.data.TransactionKind
import br.com.finbot.data.TransactionSource
import br.com.finbot.data.TransactionStatus
import br.com.finbot.data.repository.ImportRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class BankNotificationIngestorTest {
    private lateinit var f: TestFinance
    private lateinit var ingestor: BankNotificationIngestor
    private val shown = mutableListOf<BankNotificationEvent>()
    private var reads = 0

    private val nubank = "com.nu.production"
    private val posted: Instant get() = f.clock.instant().minusSeconds(60)

    /** Amostra sintética. Conta as leituras do conteúdo para provar que o portão vem antes. */
    private fun content(vararg texts: String): () -> List<String> = {
        reads++
        texts.toList()
    }

    private val compra = arrayOf("Compra aprovada", "Compra de R$ 45,90 APROVADA em PADARIA FICTICIA para o cartão com final 1234.")

    @Before
    fun setUp() = runTest {
        f = TestFinance()
        f.seed()
        val imports = ImportRepository(f.db, f.clock, f.transactions, f.accounts)
        ingestor = BankNotificationIngestor(
            config = { f.config.config.first() },
            zone = { f.zone },
            transactions = f.transactions,
            imports = imports,
            notifier = { _, event -> shown += event },
            ownPackage = "br.com.finbot",
        )
    }

    @After
    fun tearDown() = f.close()

    private fun count(sql: String): Int = f.db.openHelper.readableDatabase.query(sql).use { c -> c.moveToFirst(); c.getInt(0) }

    @Test
    fun desligadoNaoLeNada() = runTest {
        f.config.setBankNotificationPackages(setOf(nubank))
        assertEquals(IngestResult.DISABLED, ingestor.ingest(nubank, posted, content(*compra)))
        assertEquals(0, reads)
        assertEquals(0, count("SELECT COUNT(*) FROM transactions"))
    }

    @Test
    fun appForaDaListaEDescartadoSemLerConteudo() = runTest {
        f.config.setBankNotificationsEnabled(true)
        f.config.setBankNotificationPackages(setOf(nubank, "br.com.finbot"))
        assertEquals(IngestResult.NOT_ALLOWED, ingestor.ingest("com.whatsapp", posted, content(*compra)))
        // O próprio FinBot nunca é lido, mesmo se estiver na lista.
        assertEquals(IngestResult.NOT_ALLOWED, ingestor.ingest("br.com.finbot", posted, content(*compra)))
        assertEquals(0, reads)
        assertEquals(0, count("SELECT COUNT(*) FROM transactions"))
    }

    @Test
    fun ligadoGeraSugestaoParaRevisaoSemDuplicar() = runTest {
        f.config.setBankNotificationsEnabled(true)
        f.config.setBankNotificationPackages(setOf(nubank))

        assertEquals(IngestResult.CREATED, ingestor.ingest(nubank, posted, content(*compra)))
        val t = f.transactions.listPendingReview().single()
        assertEquals(TransactionStatus.PENDING_REVIEW, t.status)
        assertEquals(TransactionSource.BANK_NOTIFICATION, t.source)
        assertEquals(TransactionKind.EXPENSE, t.kind)
        assertEquals(4_590L, t.amountCents)
        assertEquals("PADARIA FICTICIA", t.description)
        assertEquals(f.categoryId("Mercado", TransactionKind.EXPENSE), t.categoryId)
        assertEquals(0L, f.summaries.balanceCents()) // nada conta sem revisão
        assertEquals(1, shown.size)

        // A mesma notificação de novo (o app do banco atualiza/reposta): não duplica nem avisa de novo.
        assertEquals(IngestResult.DUPLICATE, ingestor.ingest(nubank, posted.plusSeconds(20), content(*compra)))
        assertEquals(1, count("SELECT COUNT(*) FROM transactions"))
        assertEquals(1, shown.size)

        // Pix recebido gera outra sugestão (receita).
        assertEquals(
            IngestResult.CREATED,
            ingestor.ingest(nubank, posted, content("Pix recebido", "Você recebeu um Pix de R$ 100,00 de Carlos Exemplo")),
        )
        val pix = f.transactions.listPendingReview().first { it.kind == TransactionKind.INCOME }
        assertEquals("Pix de Carlos Exemplo", pix.description)

        // Descartar e receber de novo: continua sem voltar.
        f.transactions.discard(t.id)
        assertEquals(IngestResult.DUPLICATE, ingestor.ingest(nubank, posted, content(*compra)))
    }

    @Test
    fun mesmaCompraEmOutroMinutoEOutraSugestao() = runTest {
        f.config.setBankNotificationsEnabled(true)
        f.config.setBankNotificationPackages(setOf(nubank))
        assertEquals(IngestResult.CREATED, ingestor.ingest(nubank, posted, content(*compra)))
        assertEquals(IngestResult.CREATED, ingestor.ingest(nubank, posted.minusSeconds(600), content(*compra)))
        assertEquals(2, f.transactions.listPendingReview().size)
    }

    @Test
    fun textoNaoReconhecidoEIgnoradoENadaDoTextoBrutoEGuardado() = runTest {
        f.config.setBankNotificationsEnabled(true)
        f.config.setBankNotificationPackages(setOf(nubank))
        assertEquals(IngestResult.NO_MATCH, ingestor.ingest(nubank, posted, content("Seu código de acesso é 123456")))
        assertEquals(IngestResult.NO_MATCH, ingestor.ingest(nubank, posted, content("Compra recusada", "Compra de R$ 10,00 em X recusada")))
        assertEquals(0, count("SELECT COUNT(*) FROM transactions"))

        ingestor.ingest(nubank, posted, content(*compra))
        // Só os campos extraídos: o restante do texto ("cartão com final 1234") não aparece em lugar nenhum.
        assertEquals(0, count("SELECT COUNT(*) FROM transactions WHERE description LIKE '%final%' OR note IS NOT NULL OR external_reference IS NOT NULL"))
        assertEquals(0, count("SELECT COUNT(*) FROM audit_log WHERE summary_json LIKE '%PADARIA%' OR summary_json LIKE '%1234%'"))
        assertTrue(f.transactions.listPendingReview().single().dedupKey!!.startsWith("notif:"))
    }

    @Test
    fun servicoNasceDesativadoNoManifesto() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val info = context.packageManager.getServiceInfo(
            BankNotificationListener.component(context),
            PackageManager.MATCH_DISABLED_COMPONENTS,
        )
        assertFalse("o serviço deve nascer desativado", info.enabled)
        assertEquals("android.permission.BIND_NOTIFICATION_LISTENER_SERVICE", info.permission)
    }
}
