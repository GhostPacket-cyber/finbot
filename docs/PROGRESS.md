# Progresso

> **2026-10-17 — Mudança de rumo:** o projeto passa a seguir `Prompt/FINBOT_PROMPT_MESTRE_V2.md` (app isolado com chat interno). Ver ADR-007.

## v2 · ETAPA 01 — Transição para app isolado

**Status:** **concluída.** Build ok, 21/21 testes JVM, lint com 0 erros, 7/7 testes instrumentados e testes manuais verificados no POCO X7 Pro (Android 16, HyperOS 3.0).

### Feito
- **Removido:** token e fluxo do bot de mensagens, IDs autorizados, card de status, `BotController`, `BotState`, canal de notificação e `POST_NOTIFICATIONS` (volta na ETAPA 06). O manifesto ficou **sem nenhuma permissão**.
- **Limpeza de legado** ao abrir o app: apaga o segredo cifrado da v1, os IDs antigos e `finbot_state.preferences_pb`. Os únicos nomes antigos que restam no código são as chaves usadas nessa limpeza.
- **Navegação** com barra inferior: Chat, Lançamentos, Contas, Relatórios e Ajustes. As abas não implementadas mostram "Em breve (ETAPA N)", sem dados fictícios.
- **Onboarding** de primeira execução: boas-vindas, aviso de privacidade, nome opcional e fuso.
- **Ajustes:** perfil (nome e fuso), IA (consentimento e chave Gemini mascarada), diagnóstico e Sobre (versão via `BuildConfig`).
- `DeviceDiagnostics` movido para o pacote `diagnostics`. `versionName` = `0.2.0-v2-etapa01`.

### Comandos e resultados
| Comando | Resultado |
|---|---|
| `gradlew assembleDebug assembleDebugAndroidTest testDebugUnitTest lintDebug` | BUILD SUCCESSFUL, sem warnings do Kotlin |
| testes JVM | **21/21**: ConfigValidator 5, ConfigRepository 4, SecretStore 7, LogSanitizer 5 |
| lint | 0 erros, 1 aviso (`OldTargetApi`, ADR-002) |
| `adb install -r` sobre a v1 (0.1.0 → 0.2.0) | abriu sem crash; a v1 do aparelho não tinha dados gravados, então a limpeza de legado foi validada só nos testes JVM |
| `am instrument` | **7/7**: KeystoreSecretCipher 4, MainScreen 3 |

### Testes manuais (2026-10-17)
| # | Verificação | Resultado |
|---|---|---|
| 1–2 | Onboarding e abas com "Em breve" | ok (relatado pelo usuário e confirmado pela árvore de UI via `uiautomator dump`) |
| 3–5 | Salvar nome, chave e consentimento | Na sessão do usuário, **nada foi gravado**: o log não teve "Ajustes salvos", e o DataStore só tinha o onboarding. Reproduzido via ADB (`input tap`/`input text`): "Ajustes salvos", status `Configurada (•••• efgh)`, diálogo de consentimento e data registrados. Causa da divergência não identificada. Refazer pelo toque na próxima etapa |
| 6 | Persistência após `am force-stop` | ok: nome, fuso, consentimento com data e chave mascarada mantidos |
| 7 | Logcat sem segredos | O FinBot não registrou a chave. Uma ocorrência veio do `adbd` (registro do comando `adb shell input text`, artefato do teste). `finbot_secrets` não contém o valor em claro |
| 8 | FLAG_SECURE | `screencap` sai preto (4 de 400 pixels amostrados não pretos, provavelmente da barra de status) |

### Observações
- O HyperOS registra eventos de tecla injetados (`MiuiInputKeyEventLog`). Isso foge ao controle do app; teclados virtuais normalmente não geram esses eventos.

## Execução no aparelho (2026-10-17)
- Aparelho: POCO X7 Pro (2412DPC0AG), Android 16 (API 36), HyperOS OS3.0.
- Para instalar via ADB no HyperOS foi preciso ativar "Instalar via USB" e desativar a verificação de apps antes de instalar no app Segurança. Antes disso, a instalação falhava com `INSTALL_FAILED_USER_RESTRICTED` (`interruptCode = 7` do scanner `com.miui.global.packageinstaller`).
- `am instrument`: **6/6 testes passaram** (KeystoreSecretCipherTest 4, AdminScreenTest 2), depois da correção do ADR-008. Testes JVM: 30/30.
- Atenção: na primeira tentativa, `connectedDebugAndroidTest` retornou "BUILD SUCCESSFUL" sem executar testes (falha de instalação). Sempre conferir o relatório.
- Testes manuais (salvar a configuração, captura de tela bloqueada, logcat) foram interrompidos pela mudança de rumo e serão refeitos na tela de Ajustes da v2.

## ETAPA 01 — Bootstrap Android e tela administrativa

**Status:** build, testes JVM e lint **passando**. Testes instrumentados compilam, mas **não foram executados** (nenhum aparelho ou emulador conectado). Testes manuais pendentes.

### Ambiente (2026-10-17)
- Windows 11 Pro, 31,9 GB de RAM.
- Android Studio 2026.2.1 em `E:\Android`; JBR OpenJDK 25.0.3 usado como `JAVA_HOME`.
- Android SDK em `%LOCALAPPDATA%\Android\Sdk`: platform 37.0, build-tools 36.0.0, platform-tools 37.0.1, cmdline-tools 23.0.
- Git 2.55.0.
- Nenhum dispositivo no `adb devices`.
- Particularidade do ambiente do agente: o JDK 25 falhou ao criar o socket Unix interno no `%TEMP%` ("Unable to establish loopback connection"). Contorno usado **só na sessão do agente**: `JAVA_TOOL_OPTIONS=-Djdk.net.unixdomain.tmpdir=E:\gtmp`. Não deve ser necessário no Android Studio.

### Implementado
- Projeto `:app` com Gradle Kotlin DSL, version catalog, AGP 9.4.1 (built-in Kotlin), Kotlin 2.4.21, Compose BOM 2026.09.00 e Gradle wrapper 9.6.0 (SHA-256 verificado).
- Tela administrativa (`ui/AdminScreen.kt`):
  - status (Desligado/Conectando/Conectado/Erro), última atividade, falhas e último erro;
  - Iniciar/Parar;
  - token e chave com máscara, dica dos 4 últimos caracteres, substituir e remover com confirmação;
  - 1 ou 2 IDs e fuso;
  - consentimento de IA com data;
  - diagnóstico de armazenamento, Android, fabricante, bateria e notificações.
- Segredos cifrados com AES-256-GCM no Android Keystore; configuração e estado em DataStore. Backup automático desativado.
- Canal de notificação. `POST_NOTIFICATIONS` pedido em tempo de execução. Nenhuma outra permissão.
- `SafeLog`/`LogSanitizer`; `FLAG_SECURE`.
- Iniciar registra "Serviço do Telegram ainda não implementado (ETAPA 03)", sem simular conexão.

### Comandos executados e resultados reais
| Comando | Resultado |
|---|---|
| `gradlew clean assembleDebug testDebugUnitTest lintDebug` | BUILD SUCCESSFUL (2m04s) |
| testes JVM | **30/30 passaram**: ConfigValidator 12, SecretStore 6, LogSanitizer 5, ConfigRepository 4, BotStateRepository 3 |
| lint | 0 erros, 1 aviso (`OldTargetApi`: targetSdk 36, decisão do ADR-002) |
| `gradlew assembleDebugAndroidTest` | BUILD SUCCESSFUL, sem warnings |
| APK | `app/build/outputs/apk/debug/app-debug.apk` (12,7 MB) |

Correções feitas durante a verificação: escape `C\:` no `local.properties` (erro de lint); AGP 9.4.0 → 9.4.1; ícone de notificação ainda sem uso removido (volta na ETAPA 03); `createAndroidComposeRule` depreciado trocado pela API `v2`.

### Pendências para fechar o aceite
- [ ] `gradlew connectedDebugAndroidTest` (KeystoreSecretCipherTest, AdminScreenTest): requer o celular por USB ou um emulador.
- [ ] Testes manuais 1–7 de `docs/TEST_PLAN.md`: abrir, salvar, forçar parada, reabrir, conferir persistência e `adb logcat -s FinBot` sem segredos.

### Riscos e observações
- Keystore e persistência real só foram validados com cifra falsa e DataStore em JVM. A validação no aparelho está pendente.
- `AdminScreenTest.salvarSemIdMostraErro` pressupõe instalação limpa.
