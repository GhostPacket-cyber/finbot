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
- Os avisos de contas (às 9h) e os lembretes são conferidos pelo WorkManager a cada ~30 min e sempre que o app é aberto. Eles **podem atrasar**: o Android adia tarefas em segundo plano (Doze) e fabricantes como Xiaomi, Samsung e Motorola têm economias de bateria mais agressivas.
- Para reduzir atrasos: em Ajustes → "Ajustes de bateria", escolha **Sem restrições** para o FinBot. No HyperOS/MIUI, ative também **Inicialização automática** em *Configurações → Apps → Gerenciar apps → FinBot*. Guia por modelo: https://dontkillmyapp.com.
- Avisos que deveriam ter chegado com o app parado são entregues ao voltar, com a nota "aviso com atraso de …". Com mais de 7 dias de atraso são pulados, mas a conta continua em "A pagar".
- A permissão de notificações é pedida ao salvar a primeira conta ou lembrete. Sem ela, nada é notificado, e o app registra a falha.

## Documentação
- `docs/DECISIONS.md`: versões e decisões técnicas.
- `docs/SECURITY.md`: segredos, logs, permissões e backup.
- `docs/TEST_PLAN.md`: testes automatizados e manuais.
- `docs/PROGRESS.md`: andamento por etapa.
