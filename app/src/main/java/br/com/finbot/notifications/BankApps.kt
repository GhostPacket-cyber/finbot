package br.com.finbot.notifications

/** App de banco conhecido. O pacote também precisa constar em `<queries>` no manifesto para ser listado. */
data class BankApp(val packageName: String, val name: String)

object BankApps {
    /**
     * Catálogo inicial (ETAPA 09). A tela mostra só os que estão instalados.
     * Bancos mudam pacote e textos sem aviso: ver limitações em docs/PROGRESS.md.
     */
    val CATALOG: List<BankApp> = listOf(
        BankApp("com.nu.production", "Nubank"),
        BankApp("com.itau", "Itaú"),
        BankApp("com.bradesco", "Bradesco"),
        BankApp("br.com.intermedium", "Inter"),
        BankApp("com.c6bank.app", "C6 Bank"),
        BankApp("com.santander.app", "Santander"),
        BankApp("br.com.gabba.Caixa", "Caixa"),
        BankApp("br.com.bb.android", "Banco do Brasil"),
        BankApp("com.mercadopago.wallet", "Mercado Pago"),
        BankApp("com.picpay", "PicPay"),
    )

    /** Só em builds de depuração: notificações postadas por `adb shell cmd notification post`, para testar no aparelho. */
    const val ADB_TEST_PACKAGE = "com.android.shell"

    fun nameOf(packageName: String): String =
        CATALOG.firstOrNull { it.packageName == packageName }?.name
            ?: if (packageName == ADB_TEST_PACKAGE) "Teste via ADB" else packageName
}
