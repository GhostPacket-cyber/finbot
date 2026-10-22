# Plano de testes

Contrato vigente: `Prompt/FINBOT_PROMPT_MESTRE_V2.md`.

## ETAPA 11 (v2) — Segurança, backup e importação

### JVM
| Classe | Cobre |
|---|---|
| `BackupCryptoTest` (8) | criptografia, adulteração, limites, manifesto, política de bloqueio, lembrete de backup |

### Instrumentados
| Classe | Cobre |
|---|---|
| `BackupServiceTest` (7) | A → B reproduz os totais; WAL; senha errada e adulteração sem efeito; manifesto forjado e versão nova recusados; mesclar 2× sem duplicar; migração do v3; apagar tudo |
| `SecurityScreensTest` (2) | tela de bloqueio sem dados; senha curta recusada |

### Manuais no aparelho (o usuário digita senha e biometria; não usar ADB para senhas, porque o HyperOS registra as teclas injetadas)
1. Ajustes › **Backup e segurança** › **Bloquear o app** → autenticar → "Bloquear de novo após: voltar ao app" → ir à tela inicial e voltar → biometria pedida; cancelar → continua bloqueado; "Desbloquear" → entra.
2. **Proteger a tela** desligado → print funciona; ligado → print bloqueado.
3. **Exportar backup** com uma senha → salvar em Downloads → "Último backup" atualizado.
4. **Importar backup** do mesmo arquivo com a senha **errada** → "Senha incorreta ou arquivo alterado." e nada muda.
5. Importar com a senha certa → a prévia mostra "0 novo(s)" (os dados já existem) → **Mesclar** → nada duplica.
6. Opcional (troca de aparelho): em outro aparelho ou após **Apagar tudo**, importar → **Substituir** (digitar SUBSTITUIR) → conferir o saldo e as contagens.
7. **Apagar todos os dados** só com um backup feito antes (digitar APAGAR) → o app volta ao primeiro uso.

## ETAPA 10 (v2) — Alertas inteligentes

### JVM
| Classe | Cobre |
|---|---|
| `AlertRulesTest` (7) | bordas de cada regra (orçamento, duplicada, assinatura, fora do padrão, resumo semanal) |
| `AlertRewriterTest` (3) | IA só reescreve: números idênticos ou texto original |

### Instrumentados
| Classe | Cobre |
|---|---|
| `AlertProcessorTest` (8) | entrega única por `event_key`, chat + notificação opcional, ajustes respeitados, reescrita inválida descartada |
| `AlertsScreenTest` (2) | tela de alertas acessível; orçamento no menu da categoria |

### Manuais sugeridos
1. Categorias › Lazer › ⋮ › **Orçamento mensal…** → R$ 10 → registrar R$ 9 em Lazer → abrir o app → alerta de 80% no chat e notificação. Reabrir → não repete.
2. Registrar R$ 2 a mais → alerta de 100%.
3. Ajustes › **Alertas** → desligar "Orçamento por categoria" → nada novo.
4. Resumo semanal: escolher o dia de hoje e a hora atual cheia → abrir o app → resumo no chat.
5. Com consentimento e chave: ligar "Texto mais amigável com IA" → conferir que os números do alerta são os mesmos.

## ETAPA 09 (v2) — Notificações de banco

### JVM
| Classe | Cobre |
|---|---|
| `BankNotificationParserTest` (6) | padrões de compra, Pix, TED e pagamento com amostras sintéticas; rejeições; data e hora |

### Instrumentados
| Classe | Cobre |
|---|---|
| `BankNotificationIngestorTest` (6) | portão antes da leitura; sugestão em revisão; dedup; sem texto bruto; serviço desativado no manifesto |
| `BankNotificationsScreenTest` (1) | tela explicativa; teste de texto sem gravar; abrir não liga |

### Ponta a ponta no aparelho (build de depuração)
1. Ajustes › **Configurar notificações de banco** → ligar **Ler notificações de banco**.
2. **Conceder acesso no Android** → ativar o FinBot (o usuário faz; é um ajuste de segurança).
3. Marcar **Teste via ADB** na lista.
4. Postar notificações sintéticas:
   ```bash
   adb shell cmd notification post -t "Compra aprovada" teste1 "Compra de R\$ 45,90 APROVADA em PADARIA TESTE para o cartão com final 1234."
   ```
   O resultado esperado: aparece o aviso "Nova sugestão: R$ 45,90 em PADARIA TESTE — Revisar", e o item fica em Lançamentos › revisar.
5. Repetir o mesmo comando no mesmo minuto → nenhuma sugestão nova.
6. `adb shell cmd notification post teste2 "Seu código é 123456"` → nada.
7. Desligar a chave → postar de novo → nada (o serviço é desativado).
8. `adb logcat -s FinBot` → nenhum valor, estabelecimento ou texto.
9. Opcional: uma compra real com Nubank, Inter ou Caixa marcado. Se não virar sugestão, colar o texto em **Testar um texto** (sem dados sensíveis) para ajustar o padrão.

## ETAPA 08 (v2) — Importação OFX/CSV

### JVM
| Classe | Cobre |
|---|---|
| `StatementParsingTest` (15) | OFX SGML latin1/XML UTF-8, codificação, CSV `;`/`,`, `R$ -1.234,56`, débito/crédito, cabeçalho/rodapé, chaves de dedup |
| `AiCategorizerTest` (5) | só descrições saem do aparelho; validação da resposta; sem consentimento/chave não chama; retry |

### Instrumentados
| Classe | Cobre |
|---|---|
| `ImportRepositoryTest` (9) | reimportar sem duplicar; acentos latin1; `R$ -1.234,56`; desfazer só o lote; gravação atômica; duplicata manual; categorias; data futura; modelos |
| `PersistenceAndMigrationTest` (+1) | v3 → v4 e cadeia v1 → v4 |
| `ImportScreenTest` (2) | navegação; desfazer lote pela tela |

Os arquivos sintéticos ficam em `app/src/test/resources/importing/` e entram no APK de teste como assets.

### Manuais sugeridos
1. Exportar um extrato OFX real no app do banco → Lançamentos → **Importar** → conferir acentos, conta mascarada e valores → Importar → Revisão → Confirmar.
2. Importar o mesmo arquivo de novo → "0 novo(s)".
3. CSV do banco → conferir o mapeamento → **Salvar** como modelo → reabrir outro CSV do mesmo banco e aplicar o modelo.
4. Registrar à mão um gasto com o mesmo valor de um item do extrato → importar → o item aparece como possível duplicata, desmarcado.
5. **Sugerir categorias com IA** (com consentimento e chave) → conferir as sugestões "(IA)".
6. **Desfazer lote** → os itens somem de Lançamentos e da Revisão.
7. Arquivo maior que 5 MB → mensagem de limite.

## ETAPA 03 (v2) — Chat sem IA

### JVM
| Classe | Cobre |
|---|---|
| `CommandParserTest` (9) | comandos com barra; frases do prompt; formatos de valor; datas relativas e absolutas; "dia 5" não vira valor; ambiguidade → pergunta; consultas; não reconhecido e prompt injection; sugestão de categoria |

### Instrumentados
| Classe | Cobre |
|---|---|
| `ChatAssistantTest` (12) | nada gravado antes de confirmar; confirmação única; cancelar; expirado; editar (uma vez); edição inválida não consome o cartão; consultas com números do banco; "Ver mais"; `/desfazer` com confirmação; ambíguo ou desconhecido sem cartão; histórico apagado sem afetar lançamentos; payload ilegível nunca executa |
| `ChatScreenTest` (3) | UI real: digitar → cartão → Confirmar → "✓ Confirmado" sem botão; chip Saldo; frase sem valor pede esclarecimento |
| `PersistenceAndMigrationTest` (+2) | v2 → v3 preserva o histórico; cadeia v1 → v3 |

Pré-requisito no aparelho: tela desbloqueada ("Permanecer ativo" ajuda).

### Manuais sugeridos
1. "gastei 45,90 no mercado" → cartão → **Editar** (trocar categoria) → Confirmar → resposta com saldo.
2. "/gastos" com mais de 10 lançamentos → **Ver mais**.
3. Fechar e reabrir o app → histórico e status dos cartões mantidos.
4. Modo avião → tudo funciona.
5. TalkBack: rótulos "Você"/"FinBot" e botões anunciados.

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
