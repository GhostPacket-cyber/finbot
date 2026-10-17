# Progresso

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
