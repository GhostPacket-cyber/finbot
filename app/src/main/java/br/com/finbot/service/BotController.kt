package br.com.finbot.service

import br.com.finbot.security.SafeLog

interface BotController {
    suspend fun start()
    suspend fun stop()
}

/**
 * ETAPA 01: ainda não há serviço nem cliente Telegram. Iniciar registra um erro explícito em vez de
 * simular conexão; a implementação real (foreground service + long polling) entra na ETAPA 03.
 */
class NotImplementedBotController(private val stateRepository: BotStateRepository) : BotController {

    override suspend fun start() {
        SafeLog.i("Iniciar solicitado; serviço Telegram ainda não implementado")
        stateRepository.recordError("Serviço do Telegram ainda não implementado (ETAPA 03).")
    }

    override suspend fun stop() {
        SafeLog.i("Parar solicitado")
        stateRepository.setStatus(BotStatus.DESLIGADO)
    }
}
