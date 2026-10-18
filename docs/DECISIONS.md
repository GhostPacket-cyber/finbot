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

## ADR-009 — Navegação por estado e ícones próprios (v2 · ETAPA 01)

- A barra inferior com 5 abas é controlada por `rememberSaveable`, sem Navigation Compose. Ainda não há rotas com argumentos nem *deep links*; reavaliar na ETAPA 05.
- Os ícones das abas são vetores XML próprios, para evitar `material-icons-extended`, que é pesada.
- `buildConfig = true` para exibir a versão em "Sobre".
- Permissões só entram na etapa que as usa: INTERNET e RECORD_AUDIO na 04, POST_NOTIFICATIONS na 06.

## ADR-010 — Room 2.8.5 com KSP 2.3.12 e kotlinx-serialization 1.8.1 (v2 · ETAPA 02)

- **Room 2.8.5** (estável, set/2026) com o plugin `androidx.room`, que exporta os schemas em `app/schemas/`. Os arquivos são versionados e o plugin os injeta nos testes instrumentados para o `MigrationTestHelper`.
- **KSP 2.3.12** (`com.google.devtools.ksp`), com correções para o built-in Kotlin do AGP 9 e o Kotlin 2.4. O kapt é incompatível com o built-in Kotlin.
- **kotlinx-serialization 1.8.1** via BOM: o `room-testing` precisa de 1.8.1, mas as libs AndroidX traziam 1.7.3 ao APK do app, e o APK de teste é forçado à versão do app. Resultado: `AbstractMethodError` em `FieldBundle$$serializer` no teste de migração. O lint aponta 1.11.0; ficamos na mínima exigida pelo Room, para não subir dependência sem necessidade.

## ADR-011 — Modelo de dados e regras financeiras (v2 · ETAPA 02)

- **Usuário único por instalação:** sem coluna `user_id`. Todas as entidades exportáveis têm `uuid` (TEXT UNIQUE), para a mesclagem da ETAPA 11.
- **Dinheiro** em centavos `Long`, sempre positivo; o sinal vem de `kind` (INCOME/EXPENSE). O limite por lançamento é R$ 10.000.000,00. `Money.parseCents` aceita formatos brasileiros e rejeita os ambíguos (ex.: `12,345`, `1,234.56`).
- **Datas:** `Instant` em epoch millis (UTC) no banco. Períodos do usuário (dia, mês) são convertidos com o fuso dele em intervalos semiabertos `[início, fim)` (`domain/Periods.kt`). Datas de calendário (vencimentos) ficam em ISO `yyyy-MM-dd`.
- **Saldo** = receitas CONFIRMED − despesas CONFIRMED, excluindo `deleted_at` não nulo, `PENDING_REVIEW` e `DISCARDED`. É rotulado como "saldo calculado", não bancário.
- **Exclusão lógica** (`deleted_at`) com auditoria. `audit_log.summary_json` guarda só os campos alterados (ex.: `4500→5000`) e nunca a descrição antiga.
- **Contas a pagar** não são despesas. `markPaid` cria **um** lançamento (`source = BILL`), e o índice único em `bill_occurrences.linked_transaction_id` impede dupla contagem. Chamadas repetidas são idempotentes. Excluir o lançamento reabre a ocorrência.
- **Deduplicação:** índice único em `transactions.dedup_key`; os NULLs não colidem no SQLite. Itens descartados continuam bloqueando a reimportação.
- **Ações pendentes:** a transição `PENDING → CONFIRMED/CANCELLED` é feita por um `UPDATE ... WHERE status='PENDING' AND expires_at > now`, o que garante uso único. A confirmação executa a escrita na mesma transação; se a escrita falha, tudo é revertido e a ação continua pendente.
- **Categorias** são arquivadas, não apagadas (FK RESTRICT). As padrão são criadas na primeira execução, de forma idempotente.
- **Migrações explícitas**, nunca destrutivas. v1 → v2 adiciona `transactions.note` (observação opcional).

## ADR-012 — Chat determinístico e cartões de confirmação (v2 · ETAPA 03)

- **Sem IA nesta etapa:** `CommandParser` é uma função pura testada em JVM. Na ambiguidade, responde com uma pergunta e nunca adivinha. Instruções embutidas no texto são tratadas como texto.
- **Toda escrita passa por cartão:** a mensagem gera uma `pending_action` (TTL 15 min) e um cartão. Confirmar ou Editar grava na mesma transação da baixa da ação (`PendingActionRepository.confirm`). Repetir não grava de novo, e se a gravação falha, nada muda e o cartão continua pendente.
- `/desfazer` também é uma escrita: gera o cartão "Excluir lançamento?".
- **Data do lançamento:** hoje usa o instante atual; datas passadas usam meio-dia local, para evitar viradas de dia por fuso.
- **"Ver mais" persistente:** botões de resposta rápida gravados em `chat_messages` (schema v3) para sobreviver ao reinício.
- **Retenção:** ações pendentes ficam 30 dias, para o histórico mostrar Confirmado/Cancelado/Expirado; depois são apagadas e a mensagem vira texto simples.
- **Fila na UI:** as operações do ViewModel passam por um `Mutex`, e os botões ficam desabilitados enquanto há uma em andamento.

## ADR-006 — Proteção da tela administrativa

`FLAG_SECURE` na `MainActivity` impede screenshots, gravação de tela e a miniatura em "Recentes" da tela que recebe credenciais. Os campos de segredo usam máscara de senha, teclado sem autocorreção, e são limpos após salvar.
