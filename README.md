# FinBot

App Android de finanças pessoais com assistente por chat (texto e, futuramente, áudio). Cada pessoa usa no próprio celular, e os dados ficam só no aparelho, em SQLite local, sem conta online nem nuvem.

> Estado atual: **v2 · ETAPA 01** — onboarding, navegação com abas e Ajustes. O chat chega na ETAPA 03. Contrato: `../Prompt/FINBOT_PROMPT_MESTRE_V2.md`.

## Requisitos de desenvolvimento
- Android Studio recente (o JDK de build é o JBR embutido; o AGP 9.4 exige JDK 17+).
- Android SDK Platform **37** e Build-Tools 36.
- Aparelho com Android 8.0+ (alvo testado: Android 16).

## Instalar no celular
1. Abra a pasta `FinBot` no Android Studio e aguarde o *Gradle Sync*.
2. No celular, ative as **Opções do desenvolvedor** (toque 7 vezes em "Número da versão" ou, no HyperOS, em "Versão do SO") e ative **Depuração USB**.
3. Conecte por USB, autorize o computador e clique em **Run ▶**.

Pela linha de comando:
```bash
./gradlew assembleDebug
```
O APK fica em `app/build/outputs/apk/debug/app-debug.apk`.

### Xiaomi / POCO / Redmi (HyperOS, MIUI)
- Ative também **Instalar via USB** (exige conta Mi e, às vezes, dados móveis) e **Depuração USB (Configurações de segurança)**.
- Se a instalação falhar com `INSTALL_FAILED_USER_RESTRICTED`, desative a verificação de apps antes de instalar em *Segurança → ⚙ → Verificação de segurança*. Religue depois, se quiser.

## Bateria e lembretes
- O cartão "Diagnóstico do aparelho" em Ajustes mostra a versão do Android e se o FinBot sofre otimização de bateria. O botão "Ajustes de bateria" abre a tela do sistema.
- Os lembretes (ETAPA 06) podem atrasar com a otimização ativa. Consulte https://dontkillmyapp.com para o seu modelo.

## Documentação
- `docs/DECISIONS.md`: versões e decisões técnicas.
- `docs/SECURITY.md`: segredos, logs, permissões e backup.
- `docs/TEST_PLAN.md`: testes automatizados e manuais.
- `docs/PROGRESS.md`: andamento por etapa.
