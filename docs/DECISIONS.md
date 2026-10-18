# Decisões técnicas (ADRs resumidas)

## ADR-001 — Toolchain e versões (2026-10-17)

Versões consultadas nas páginas oficiais em 2026-10-17:

| Item | Versão | Fonte |
|---|---|---|
| Android Gradle Plugin | 9.4.1 (patch estável indicado pelo lint; 9.4.0 na pesquisa inicial) | developer.android.com/build/releases/gradle-plugin |
| Gradle (wrapper) | 9.6.0 (mínimo e padrão do AGP 9.4) | idem |
| JDK de build | 17+ (o JBR do Android Studio atende) | idem |
| Kotlin | 2.4.21 (08/10/2026) | kotlinlang.org/docs/releases.html |
| Compose BOM | 2026.09.00 (material3 1.4.0) | developer.android.com/develop/ui/compose/bom/bom-mapping |
| core-ktx | 1.19.1 | Jetpack stable channel |
| activity-compose | 1.13.0 | idem |
| lifecycle | 2.11.0 | idem |
| datastore-preferences | 1.2.1 | idem |
| kotlinx-coroutines | 1.11.0 | Maven Central |

- O AGP 9 traz Kotlin embutido (*built-in Kotlin*), por isso o plugin `org.jetbrains.kotlin.android` **não** é aplicado. O plugin do compilador Compose (`org.jetbrains.kotlin.plugin.compose`) usa a mesma versão do Kotlin.
- O `jvmTarget` segue `compileOptions.targetCompatibility` (17).
- Ainda não há kapt. Quando o Room entrar (ETAPA 02), usar KSP, porque kapt é incompatível com o built-in Kotlin.

- Gradle 9.8.1 já existe, mas mantemos o 9.6.0, que é o padrão testado do AGP 9.4.
- SDK instalado com o command-line tools 23.0 em `%LOCALAPPDATA%\Android\Sdk`: `platforms;android-37.0`, `build-tools;36.0.0` e `platform-tools` 37.0.1.

## ADR-002 — SDKs

- **compileSdk 37**: exigido pelo Compose 1.12+ (BOM 2026.09.00). O AGP 9.4 suporta até a API 37.
- **targetSdk 36 (Android 16)**: a página oficial ainda apresenta o Android 17 com builds beta (QPR1/QPR2). Mirar a versão estável evita mudanças de comportamento ainda não consolidadas. Reavaliar na ETAPA 10.
- **minSdk 26 (Android 8.0)**: o aparelho alvo informado roda Android 14+, mas 26 garante `java.time`, `java.util.Base64`, ícones adaptativos e Keystore AES-GCM sem desugaring, e mantém margem caso o aparelho seja trocado.
- Consequências do aparelho com Android 14+: o foreground service exige tipo declarado e permissão específica (avaliar na ETAPA 03); `POST_NOTIFICATIONS` é pedido em tempo de execução (API 33+).

## ADR-003 — Armazenamento da configuração e dos segredos

- **Configuração não secreta** (IDs autorizados, fuso, consentimento de IA e data): Jetpack DataStore Preferences (`finbot_config`). Na ETAPA 02, os valores que precisarem participar de consultas podem migrar para a tabela `settings` do Room.
- **Segredos** (token do Telegram, chave Gemini): AES-256-GCM com chave não exportável no **Android Keystore** (`KeystoreSecretCipher`). O ciphertext em Base64 fica em um DataStore separado (`finbot_secrets`).
- **Não** usamos `androidx.security:security-crypto` (EncryptedSharedPreferences), que está descontinuada.
- Se a chave do Keystore for perdida (limpeza de credenciais, restauração), o status vira "Ilegível" e o administrador informa um novo valor. Não há recuperação silenciosa.
- Backup automático do Android **desativado** (`allowBackup=false` e regras de exclusão). O backup criptografado manual fica para a ETAPA 08.

## ADR-004 — Sem framework de injeção de dependências

Um `AppContainer` manual em `FinBotApp` cria repositórios e serviços, porque o app tem um módulo e poucas dependências. Reavaliar se o grafo crescer.

## ADR-005 — Botão Iniciar na ETAPA 01

O serviço Telegram só existe a partir da ETAPA 03. `NotImplementedBotController` registra o estado **Erro** com a mensagem "Serviço do Telegram ainda não implementado (ETAPA 03)" em vez de simular conexão. Ao abrir o processo, um estado "Conectando/Conectado" persistido é revertido para "Desligado" (`reconcileAfterProcessStart`), para que a tela nunca mostre como ativo um serviço que não está rodando.

## ADR-007 — Mudança de rumo: app isolado em vez de bot Telegram (2026-10-17)

- **Contexto:** o Telegram restringiu a criação do bot para o administrador. Além disso, no aparelho alvo (POCO X7 Pro, HyperOS 3.0) um serviço 24/7 seria frágil.
- **Decisão:** o FinBot vira um app de finanças isolado, com chat interno (texto e áudio), um usuário por instalação, dados só no aparelho, exportação e importação criptografadas, importação OFX/CSV e leitura opcional de notificações de bancos. Contrato: `Prompt/FINBOT_PROMPT_MESTRE_V2.md`.
- **Consequências:** saem o token Telegram, a allowlist de IDs, `BotController`/`BotState` e o foreground service. Ficam Keystore/`SecretStore`, DataStore, `SafeLog`, consentimento de IA, diagnóstico e toda a base de build e testes. A transição está descrita na ETAPA 01 da v2.

## ADR-008 — Versão explícita de kotlinx-coroutines

As libs AndroidX traziam `kotlinx-coroutines-core` 1.9.0, enquanto o `kotlinx-coroutines-test` 1.11.0 dos testes instrumentados exigia 1.11 (`NoSuchMethodError: runBlockingK`). O app declara `kotlinx-coroutines-android` 1.11.0 explicitamente, porque também usa coroutines diretamente.

## ADR-006 — Proteção da tela administrativa

`FLAG_SECURE` na `MainActivity` impede screenshots, gravação de tela e a miniatura em "Recentes" da tela que recebe credenciais. Os campos de segredo usam máscara de senha, teclado sem autocorreção, e são limpos após salvar.
