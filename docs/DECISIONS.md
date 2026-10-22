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

## ADR-013 — Integração Gemini (v2 · ETAPA 04)

- **Endpoint:** `POST /v1beta/models/{modelo}:generateContent` com `"store": false`. A Interactions API foi descartada por guardar interações no servidor por padrão.
- **Sem SDK:** `HttpURLConnection` + `org.json`, sem dependência nova no APK. MockWebServer 3 (5.5.0) e `org.json` (20260814) só nos testes JVM.
- **Formato estruturado:** `generationConfig.responseFormat.text` (`APPLICATION_JSON` + JSON Schema). O JSON recebido passa de novo por `GeminiResponseValidator`; nunca confiamos no schema do servidor.
- **Modelo padrão** `gemini-3.5-flash-lite` (estável, mais barato, com plano gratuito), validado por regex para não injetar caminho na URL.
- **Minimização:** só a mensagem atual (ou o áudio) e os nomes das categorias. Nada de histórico, saldos ou valores anteriores.
- **Ordem:** parser local primeiro. A IA só é usada para o que ele não resolve (desconhecido, valor ausente, vários valores) e só com consentimento e chave.
- **Lote:** vários itens viram `CreateBatch`; os totais são calculados em Kotlin, e a confirmação grava todos ou nenhum (mesma transação Room).
- **Áudio:** AAC/ADTS 16 kHz mono 32 kbps, máx. 60 s, sem conversão. O arquivo fica no cache privado e é apagado logo após a leitura dos bytes.
- **Erros:** 5xx, timeout e rede têm uma nova tentativa (1 s + jitter); 429 não é repetido e informa o `retryDelay` quando vier. Toda mensagem oferece o caminho sem IA.

## ADR-014 — Telas de gestão (v2 · ETAPA 05)

- **Paginação sem Paging 3:** `Flow` do Room com `LIMIT` crescente. É reativo a gravações de qualquer origem (chat, formulário) e dispensa uma dependência.
- **Filtro numa consulta estática** com `(:p IS NULL OR …)`. O texto do usuário só entra como parâmetro LIKE escapado (`SearchPattern`, `ESCAPE '\'`); nunca há SQL montado com texto do usuário.
- **Navegação por estado** dentro da aba (Lista, Revisão, Categorias/contas) com `BackHandler`. A Navigation Compose segue dispensável.
- **Salvar no formulário = confirmação explícita** do lançamento manual. Excluir pede confirmação e oferece desfazer.
- **Mesclar categorias** é a forma de "apagar" uma categoria em uso: tudo migra numa transação e a origem fica arquivada (FK `RESTRICT` impede a exclusão física).

## ADR-015 — Avisos locais com WorkManager, sem alarmes exatos (v2 · ETAPA 06)

- **WorkManager 2.12.0**, periódico de 30 min (`KEEP`), mais a execução ao abrir o app. Alarmes exatos (`SCHEDULE_EXACT_ALARM`) exigem permissão especial e são restritos no Android 14+; para avisos de conta, ±30 min é aceitável.
- **Idempotência por `event_key`** (`bill:<uuid>:<vencimento>:<offset>` e `reminder:<uuid>:<instante>`) em `notification_log`. A chave usa datas de calendário, então trocar de fuso não gera duplicata.
- **Recuperação de 7 dias** após o app ficar parado. Mais antigo que isso é marcado como `SKIPPED`, e as contas atrasadas continuam visíveis na tela.
- **Sem avisos retroativos:** o que foi planejado antes do cadastro é pulado, e de vários avisos da mesma ocorrência vencidos juntos só o mais recente é entregue (achado no aparelho).
- **Meses curtos:** padrão "último dia do mês", configurável por conta. O dia-âncora é sempre o do primeiro vencimento.
- **Janela de ocorrências** [hoje − 62, hoje + 40 dias]: evita gerar dezenas de atrasos para uma conta cadastrada com vencimento antigo.

## ADR-016 — Relatórios e exportação (v2 · ETAPA 07)

- **Gráficos em Canvas do Compose**, sem biblioteca: rosca, barras e linha, cada um com `contentDescription` contendo os números e uma alternativa em tabela.
- **Previsto ≠ realizado:** só lançamentos CONFIRMED entram nas despesas. Contas pendentes aparecem à parte como previsão; contas pagas contam uma vez, pelo lançamento do pagamento.
- **Agregação por mês no fuso do usuário** com intervalos `[início, fim)` calculados em Kotlin (uma consulta por mês). Agrupar UTC no SQLite erraria a virada de mês.
- **CSV pt-BR** (BOM, `;`, decimal com vírgula) com **proteção contra injeção de fórmula**, já que descrições podem vir de extratos (ETAPA 08). A gravação é via SAF, sem permissão ampla de armazenamento.
- **Formulários com carga assíncrona** só aplicam os valores salvos se o usuário ainda não editou.

## ADR-006 — Proteção da tela administrativa

`FLAG_SECURE` na `MainActivity` impede screenshots, gravação de tela e a miniatura em "Recentes" da tela que recebe credenciais. Os campos de segredo usam máscara de senha, teclado sem autocorreção, e são limpos após salvar.

## ADR-017 — Importação de extratos (v2 · ETAPA 08)

- **Parsers próprios e tolerantes**, sem biblioteca. O OFX 1.x real costuma ser SGML malformado (tags sem fechamento, `CHARSET:NONE` com acentos em Latin-1). A codificação segue esta ordem: BOM → declarada → UTF-8 estrito → Windows-1252 (superconjunto prático do ISO-8859-1).
- **Chaves de dedup em hash** (SHA-256) no índice único já existente em `dedup_key`. O número da conta não fica no banco em claro, só mascarado em `import_batches.masked_account`. No CSV, a "ocorrência no dia" diferencia dois lançamentos idênticos e continua estável ao reimportar.
- **Itens excluídos ou descartados continuam bloqueando a chave.** Descartar é uma decisão do usuário e não deve voltar. **Desfazer lote** é a exceção: libera as chaves de propósito.
- **Lote atômico:** o lote e os itens são gravados numa única transação. Se nada for novo, o lote não é criado.
- **Data ao meio-dia local** para a data do extrato (sem horário confiável). Isso evita trocar de dia por fuso. No dia de hoje, a data nunca passa de "agora".
- **Possível duplicata de lançamento manual** (sem chave, mesmo tipo e valor, ±2 dias) vem desmarcada. Comparar descrições de banco e de usuário gera falsos negativos demais.
- **Categoria:** o histórico do usuário vence as palavras-chave. A IA é só por botão e envia apenas tipo e descrição; a resposta passa por schema fechado e validação local.
- **Schema v4** só com `ALTER TABLE ADD COLUMN` e uma tabela nova. `import_batch_id` não tem FK, para não recriar a tabela `transactions`; a integridade fica no repositório.

## ADR-018 — Notificações de banco (v2 · ETAPA 09)

- **Componente desativado no manifesto** e ativado só pela chave do app. É uma garantia mais forte que um `if` no código: com o recurso desligado, o sistema não entrega notificação alguma, mesmo que o acesso tenha sido concedido antes.
- **Portão antes da leitura:** o ingestor recebe o conteúdo como função e só a chama depois de conferir chave e pacote. Os testes contam as leituras.
- **Padrões em regex, sem IA.** O que não casa é ignorado. Mandar à IA texto de notificação bancária seria um vazamento desnecessário.
- **Rejeitar é mais seguro que aceitar:** termos como recusada, estorno, código, agendado e fatura descartam a notificação inteira.
- **Dedup por minuto**, como pede o prompt. A repostagem da mesma notificação (comum nos apps de banco) não duplica.
- **Lista de pacotes via `<queries>`** em vez de `QUERY_ALL_PACKAGES`: menor superfície e aceito pela Play.
- **Aviso privado na tela bloqueada:** valor e estabelecimento só aparecem com o aparelho desbloqueado.
- A opção **"Teste via ADB"** existe só em `BuildConfig.DEBUG`, para validar o caminho real do serviço sem notificações de bancos de verdade.

## ADR-019 — Alertas inteligentes (v2 · ETAPA 10)

- **Regras puras em Kotlin** (`AlertRules`), testáveis sem banco. O processador só busca os dados e entrega. A IA nunca calcula.
- **Entrega única pela `event_key` em `notification_log`** (a mesma tabela dos avisos de contas, com prefixo `alert:`). O registro e a mensagem do chat são gravados na mesma transação. As chaves embutem o período (mês, semana) ou os itens (UUIDs), então o alerta de outro mês é outro alerta.
- **Chat como canal principal**, a notificação é opcional: o alerta fica no histórico mesmo com as notificações do sistema bloqueadas.
- **Sem retroativos:** só eventos recentes (7 dias) ou do mês corrente; o resumo semanal atrasado mais de 2 dias é pulado. Ativar ou atualizar o app não dispara avisos antigos.
- **Orçamento vigente = o definido mais recentemente até o mês** (`budgets` já tinha `month`). Remover grava limite 0 a partir do mês, preservando o histórico de meses anteriores.
- **Reescrita por IA verificada:** a resposta precisa ter o mesmo multiconjunto de números do original. É simples, local e impede que a IA "corrija" um valor.
- **Execução junto do `ReminderWorker`** (30 min) em vez de outro job, para não criar mais um agendamento periódico.

## ADR-020 — Backup `.finbot`, mesclagem e bloqueio (v2 · ETAPA 11)

- **Retrato do banco:** copiar o arquivo e o `-wal` dentro de uma transação do Room (nenhuma outra escrita é confirmada nesse intervalo) e depois aplicar o WAL na cópia (`wal_checkpoint` + `journal_mode=DELETE`). Funciona do Android 8 em diante, enquanto o `VACUUM INTO` exige SQLite 3.27 (Android 11+).
- **Formato:** `FINBOTX1` + cabeçalho JSON (KDF e parâmetros) + AES-256-GCM. O cabeçalho é AAD: trocar parâmetros invalida a tag. Senha errada e adulteração dão o mesmo erro, de propósito.
- **KDF:** PBKDF2-HMAC-SHA256 com 600 mil iterações (OWASP 2023), nativo do Android. Argon2id seria preferível, mas exigiria uma biblioteca nativa. O parâmetro fica no cabeçalho, para subir no futuro sem quebrar arquivos antigos. Limites de 100 mil a 5 milhões evitam DoS por arquivo malicioso.
- **Importar nunca mexe no banco atual antes de tudo conferido.** A cópia recebida é validada (tag, SHA-256, `integrity_check`, versão, totais) e aberta pelo Room com as migrações. Só então se substitui ou mescla, numa transação.
- **Substituir copia linhas, não troca o arquivo:** trocar o arquivo com o app aberto quebraria o Room e os Flows; a cópia em transação mantém as telas reativas e é atômica.
- **Mesclar: o que existe neste aparelho prevalece.** União por `uuid`; categorias e contas de mesmo nome são unificadas; mesma `dedup_key` = mesmo lançamento; nada local é sobrescrito. A prévia executa a mesma mesclagem dentro de uma transação e desfaz, então mostra exatamente o que será gravado.
- **Fora do backup:** a chave da Gemini (segredo do aparelho), os ajustes e o histórico do chat. Fora da mesclagem também: as ações pendentes, o registro de notificações e a auditoria.
- **Banco ativo sem SQLCipher:** depende do sandbox, da criptografia do Android e do bloqueio do app. O SQLCipher traria uma biblioteca nativa grande e a gestão de mais uma chave. Reavaliar se o app sair do uso pessoal.
- **Bloqueio:** `BIOMETRIC_WEAK | DEVICE_CREDENTIAL` (aceito do Android 8 ao 16). O estado fica por processo: um processo novo começa bloqueado. Se o aparelho perder o bloqueio de tela, o app não tranca o usuário fora (não há como autenticar).

## ADR-021 — Infraestrutura de testes (v2 · ETAPA 12)

- **WorkManager sob demanda** (`Configuration.Provider`, inicializador do androidx.startup removido). Isso permite que o `FinBotTestRunner` instale o WorkManager de teste antes do `Application.onCreate`, como pede o `WorkManagerTestInitHelper`. Em produção o comportamento é o mesmo: o `onCreate` agenda e inicializa.
- **Os testes de interface rodam no app real do aparelho** e por isso não podem depender dos ajustes do usuário. O bloqueio do app é desviado só em memória pelo runner (`AppLock.testBypass`), sem alterar a configuração salva. Os testes que mudam ajustes restauram o valor anterior.
- **Performance medida no aparelho de teste**, com números registrados em TEST_PLAN e limites largos nos testes, só contra regressões grosseiras.
