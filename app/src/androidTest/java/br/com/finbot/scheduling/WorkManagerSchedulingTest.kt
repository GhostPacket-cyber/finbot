package br.com.finbot.scheduling

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/**
 * ETAPA 12 — WorkManager com WorkManagerTestInitHelper (instalado pelo FinBotTestRunner).
 * Confere o agendamento periódico idempotente e a execução do worker.
 */
@RunWith(AndroidJUnit4::class)
class WorkManagerSchedulingTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val workManager get() = WorkManager.getInstance(context)

    private fun infos(): List<WorkInfo> = workManager.getWorkInfosForUniqueWork(ReminderScheduler.UNIQUE_NAME).get()

    @Test
    fun agendamentoPeriodicoEIdempotenteERoda() {
        // O FinBotApp.onCreate já agendou; agendar de novo (como a cada abertura) não duplica.
        ReminderScheduler.schedule(context)
        ReminderScheduler.schedule(context)
        val info = infos().single()
        assertEquals(WorkInfo.State.ENQUEUED, info.state)
        assertEquals(TimeUnit.MINUTES.toMillis(ReminderScheduler.INTERVAL_MINUTES), info.periodicityInfo!!.repeatIntervalMillis)

        // Simula o intervalo cumprido: o worker roda (executor síncrono) e volta a ficar agendado.
        WorkManagerTestInitHelper.getTestDriver(context)!!.setPeriodDelayMet(info.id)
        // CoroutineWorker roda fora do executor síncrono: espera terminar e voltar a ENQUEUED.
        var after = infos().single()
        val deadline = System.currentTimeMillis() + 15_000
        while (after.state != WorkInfo.State.ENQUEUED && System.currentTimeMillis() < deadline) {
            Thread.sleep(100)
            after = infos().single()
        }
        assertEquals(info.id, after.id)
        assertEquals(WorkInfo.State.ENQUEUED, after.state)
        // Rodou: o próximo horário passa a contar a partir de agora (antes contava da abertura do app).
        assertTrue("próximo ${after.nextScheduleTimeMillis} > ${info.nextScheduleTimeMillis}", after.nextScheduleTimeMillis > info.nextScheduleTimeMillis)
    }

    @Test
    fun workerProcessaAvisosEAlertasComSucesso() = runBlocking {
        val worker = TestListenableWorkerBuilder<ReminderWorker>(context).build()
        assertEquals(ListenableWorker.Result.success(), worker.doWork())
    }
}
