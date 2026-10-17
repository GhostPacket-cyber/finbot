# FinBot

Bot Telegram de finanças pessoais hospedado em um celular Android, para no máximo dois usuários autorizados. Os dados ficam em SQLite local, no próprio aparelho.

> Estado atual: **ETAPA 01** — APK com tela administrativa e diagnóstico. Ainda não conversa com o Telegram (ETAPA 03).

## Requisitos de desenvolvimento
- Android Studio recente (o JDK de build é o JBR embutido; o AGP 9.4 exige JDK 17+).
- Android SDK Platform **37** e Build-Tools 36 (instale pelo SDK Manager se o Studio não instalar sozinho).
- Aparelho com Android 8.0+ (alvo: Android 14+).

## Instalar pelo Android Studio
1. Instale o Android Studio (`winget install Google.AndroidStudio`), abra-o uma vez e conclua o assistente, aceitando as licenças do SDK.
2. *File > Open* → pasta `FinBot`. Aguarde o *Gradle Sync*.
3. No celular: *Configurações > Sobre o telefone* → toque 7 vezes em "Número da versão" para habilitar as Opções do desenvolvedor → ative **Depuração USB**.
4. Conecte o celular por USB, autorize o computador e clique em **Run ▶** com o módulo `app` selecionado.

Pela linha de comando (com o `JAVA_HOME` apontando para o JBR do Studio):
```bash
./gradlew assembleDebug
```
O APK fica em `app/build/outputs/apk/debug/app-debug.apk`.

## Identificar a versão do Android e as restrições de bateria
- **Versão:** *Configurações > Sobre o telefone > Informações do software*, ou o cartão "Diagnóstico do aparelho" do próprio app (mostra versão, API e fabricante).
- **Otimização de bateria:** o cartão de diagnóstico informa se o FinBot está restrito. O botão "Ajustes de bateria" abre a tela do sistema; escolha "Sem restrições" para o FinBot.
- Fabricantes como Samsung, Xiaomi, Motorola e outros têm gerenciadores próprios ("Apps em suspensão", "Inicialização automática"). Consulte https://dontkillmyapp.com para o seu modelo.
- Mesmo com esses ajustes, **não há garantia de execução 24/7**.

## Documentação
- `docs/DECISIONS.md`: versões e decisões técnicas.
- `docs/SECURITY.md`: segredos, logs e backup.
- `docs/TEST_PLAN.md`: testes automatizados e manuais.
- `docs/PROGRESS.md`: andamento por etapa.
