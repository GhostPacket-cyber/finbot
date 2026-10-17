package br.com.finbot.service

import br.com.finbot.testutil.FileDataStoreHandle
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BotStateRepositoryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `estado conectado nao sobrevive a reinicio do processo`() = runTest {
        val handle = FileDataStoreHandle(tmp.newFolder().resolve("s.preferences_pb"))
        val repo = BotStateRepository(handle.dataStore)
        repo.setStatus(BotStatus.CONECTADO)
        repo.reconcileAfterProcessStart()
        assertEquals(BotStatus.DESLIGADO, repo.state.first().status)
        handle.close()
    }

    @Test
    fun `erro permanece apos reinicio e conta falhas`() = runTest {
        val handle = FileDataStoreHandle(tmp.newFolder().resolve("s.preferences_pb"))
        val repo = BotStateRepository(handle.dataStore)
        repo.recordError("falha 1")
        repo.recordError("falha 2")
        repo.reconcileAfterProcessStart()
        val state = repo.state.first()
        assertEquals(BotStatus.ERRO, state.status)
        assertEquals("falha 2", state.lastError)
        assertEquals(2, state.failureCount)
        handle.close()
    }

    @Test
    fun `parar limpa ultimo erro`() = runTest {
        val handle = FileDataStoreHandle(tmp.newFolder().resolve("s.preferences_pb"))
        val repo = BotStateRepository(handle.dataStore)
        val controller = NotImplementedBotController(repo)
        controller.start()
        assertEquals(BotStatus.ERRO, repo.state.first().status)
        controller.stop()
        val state = repo.state.first()
        assertEquals(BotStatus.DESLIGADO, state.status)
        assertNull(state.lastError)
        handle.close()
    }
}
