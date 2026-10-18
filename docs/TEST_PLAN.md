# Plano de testes

Contrato vigente: `Prompt/FINBOT_PROMPT_MESTRE_V2.md`.

## ETAPA 02 (v2) — Room e regras financeiras

### JVM
| Classe | Cobre |
|---|---|
| `MoneyTest` (8) | `R$ 1.234,56`, `45,9`, `1234.56`, `1.234`, negativos, entradas ambíguas rejeitadas, soma exata, formatação, ida e volta, limites |
| `PeriodsTest` (6) | mês em São Paulo, último minuto do mês local × UTC, fevereiro bissexto, virada de ano, dia dependente do fuso (Manaus), intervalo inválido |
| `TransactionRulesTest` (6) | valor, descrição, datas (antes de 2000, futuro > 1 dia), observação |

### Instrumentados (banco real do Android, em memória ou arquivo)
| Classe | Cobre |
|---|---|
| `TransactionRepositoryTest` (8) | saldo; `PENDING_REVIEW`/`DISCARDED` fora do saldo; exclusão lógica com auditoria; edição auditada sem a descrição antiga; deduplicação (inclusive após descarte); categoria de tipo errado ou arquivada; valores inválidos; padrões idempotentes |
| `SummaryRepositoryTest` (4) | virada do mês no fuso; resumo mensal com centavos e ordem por categoria; resumo diário e período vazio; 300 lançamentos com soma exata |
| `BillAndPendingActionTest` (8) | conta não paga ≠ despesa; pagar 2× = 1 lançamento; ocorrência idempotente; desfazer ou excluir reabre a conta; ação pendente de uso único, expirada, revertida em falha e cancelada |
| `PersistenceAndMigrationTest` (2) | fechar e reabrir o arquivo do banco; migração v1 → v2 com `MigrationTestHelper` |

## ETAPA 01 (v2) — Transição para app isolado

### Testes JVM (`app/src/test`) — `gradlew testDebugUnitTest`
| Classe | Cobre |
|---|---|
| `ConfigValidatorTest` (5) | nome opcional (limite, caracteres de controle), chave Gemini opcional, fuso Área/Cidade, agregação de erros |
| `ConfigRepositoryTest` (4) | padrões, onboarding e consentimento persistem ao reabrir o DataStore, remoção do nome, revogação, limpeza das chaves legadas da v1 |
| `SecretStoreTest` (7) | cifragem/decifragem, máscara, persistência, remoção, status "Ilegível", limpeza do segredo legado da v1 |
| `LogSanitizerTest` (5) | chave Google, `x-goog-api-key`, `Bearer`, parâmetros `key=`/`token=`, truncamento |

### Testes instrumentados (`app/src/androidTest`)
Executar com `adb shell am instrument -w -r br.com.finbot.test/androidx.test.runner.AndroidJUnitRunner` depois de instalar os dois APKs. Conferir o resultado: `connectedDebugAndroidTest` já retornou sucesso sem executar testes.

| Classe | Cobre |
|---|---|
| `KeystoreSecretCipherTest` (4) | ida e volta no Keystore real, IV aleatório, blob adulterado, chave apagada |
| `MainScreenTest` (3) | onboarding → abas; placeholders "Em breve (ETAPA N)"; seções de Ajustes; erro de fuso inválido |

### Manuais no aparelho
1. Instalação limpa → o onboarding aparece; preencher o nome → **Começar** → abre a aba Chat com "Em breve (ETAPA 03)".
2. Percorrer as 5 abas; nenhuma mostra dados fictícios.
3. Ajustes: alterar o nome e o fuso → **Salvar ajustes** → mensagem "Ajustes salvos."
4. Ajustes: colar uma chave Gemini → salvar → o campo é limpo e o status mostra `Configurada (•••• xxxx)` → **Remover chave** pede confirmação.
5. Ativar a IA → o diálogo de consentimento aparece → **Concordo** → a data é exibida; desativar → a data some.
6. Forçar a parada do app (`adb shell am force-stop br.com.finbot`) e reabrir → o onboarding não reaparece e os ajustes persistem.
7. `adb logcat` durante os passos 3–6: nenhuma chave aparece (verificação automatizada por regex, só contagem).
8. Captura de tela bloqueada (FLAG_SECURE).
9. Atualização por cima da v1 (`adb install -r`): o app abre sem erro, e o segredo e as chaves legadas são apagados.

## Registro de execuções
Ver `docs/PROGRESS.md`.
