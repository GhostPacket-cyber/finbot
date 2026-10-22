package br.com.finbot

import android.app.Application
import android.util.Log
import androidx.test.runner.AndroidJUnitRunner
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper

/**
 * Instala o WorkManager de teste antes do Application.onCreate (que agenda os avisos).
 * Assim os testes nunca mexem no agendamento real do aparelho.
 */
class FinBotTestRunner : AndroidJUnitRunner() {
    override fun callApplicationOnCreate(app: Application) {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            app,
            Configuration.Builder().setMinimumLoggingLevel(Log.DEBUG).setExecutor(SynchronousExecutor()).build(),
        )
        super.callApplicationOnCreate(app)
        // O bloqueio do app configurado pelo usuário não pode travar os testes de interface (SecurityScreensTest o reativa).
        (app as FinBotApp).container.appLock.testBypass = true
    }
}
