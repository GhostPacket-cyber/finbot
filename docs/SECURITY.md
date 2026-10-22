# Segurança — FinBot

Documento vivo. O modelo de ameaças está no fim (ETAPA 11, v2).

## Segredos
- A chave da Gemini API (opcional) é informada **somente no aparelho**, em Ajustes. Nunca entra no código-fonte, no Git, no APK nem em logs.
- Fica cifrada com AES-256-GCM, com chave não exportável no Android Keystore (`security/SecretCipher.kt`), dentro do diretório privado do app.
- A UI mostra no máximo os 4 últimos caracteres (`•••• abcd`) e permite substituir ou remover.
- **Limitação:** chaves guardadas no cliente não são invioláveis. Root, malware com privilégios ou depuração no aparelho podem obtê-las. Se houver suspeita de vazamento, revogue a chave no Google AI Studio e cadastre outra.
- Atualizar a partir da v1 apaga automaticamente o segredo e os IDs da versão anterior (`FinBotApp.removeLegacyData`).

## Logs
- `SafeLog` registra apenas eventos técnicos. Para exceções, registra só o tipo, nunca a mensagem.
- `LogSanitizer` remove como rede de segurança: chaves `AIza…`, `x-goog-api-key`, `Bearer` e parâmetros `key=`/`token=`.
- Proibido registrar conteúdo de mensagens, áudios, valores financeiros ou o nome do usuário.

## Permissões
- `INTERNET` (Gemini, ETAPA 04) e `RECORD_AUDIO` (voz, pedida no primeiro uso). Cada permissão entra só na etapa que a usa.

## Banco de dados
- `databases/finbot.db` (SQLite/Room) fica no diretório privado do app e é excluído de backup em nuvem e de transferência entre aparelhos (`data_extraction_rules.xml`, `backup_rules.xml`).
- O banco **não é criptografado** em repouso: depende do sandbox do Android e da criptografia do aparelho (decisão em ADR-020). O backup exportado é criptografado com a senha do usuário.
- `audit_log` não guarda descrições antigas nem conteúdo de chat ou áudio, só os campos alterados.

## Importação de extratos
- O arquivo vem do seletor do sistema (SAF), com acesso só ao arquivo escolhido e limite de 5 MB lido em blocos. O texto fica apenas em memória.
- O número da conta é exibido e guardado só mascarado (`••••1234`); as chaves de dedup são hashes SHA-256. O nome do arquivo vira hash, nunca é gravado nem registrado em log.
- Logs da importação: só o tamanho em bytes e as contagens.
- Descrições vindas de extratos são tratadas como dados: nunca viram SQL (parâmetros do Room), e a exportação CSV neutraliza fórmulas (ADR-016).
- O botão "Sugerir categorias com IA" envia à Gemini API **só o tipo (despesa/receita) e a descrição** dos itens sem categoria, além dos nomes das categorias. Valores, datas, conta e nome do arquivo nunca saem do aparelho.

## Notificações de banco
- O recurso é desligado por padrão. O serviço (`BankNotificationListener`) nasce desativado no manifesto e só é ativado pela chave do app; o acesso é concedido pelo usuário nas configurações do Android e pode ser revogado ali ou pela chave. Verificado no HyperOS 3.0: desligar a chave desativa o componente e o Android também retira o acesso.
- Notificações de apps fora da lista escolhida são descartadas antes de qualquer leitura do conteúdo. O próprio FinBot nunca é lido.
- O texto bruto não é guardado nem registrado: o banco recebe só valor, estabelecimento ou contraparte, data e uma chave de dedup em hash. Os logs não têm conteúdo.
- Nada é enviado à IA ou para fora do aparelho.
- O aviso de sugestão não mostra valor nem estabelecimento na tela bloqueada.
- Android 15+: códigos de verificação ficam ocultos para listeners de terceiros (o FinBot não pede `RECEIVE_SENSITIVE_NOTIFICATIONS`).

## Alertas
- Calculados no aparelho por regras; nada é enviado para fora, exceto com a opção "Texto mais amigável com IA" ligada (desligada por padrão; exige consentimento e chave). Nesse caso, o Gemini recebe só o texto do alerta (valores e nomes de categoria ou estabelecimento), sem histórico nem outros lançamentos.
- A notificação de alerta é privada na tela bloqueada ("Novo alerta do FinBot").
- Os logs registram só a quantidade de alertas entregues.

## Backup (.finbot)
- O backup automático do Android (nuvem e transferência entre aparelhos) continua desativado: o único backup é o que o usuário exporta.
- **Criptografia:** AES-256-GCM com chave derivada da senha por PBKDF2-HMAC-SHA256 (600 000 iterações, sal aleatório de 16 bytes, nonce de 12 bytes). O cabeçalho entra como AAD. A senha tem no mínimo 10 caracteres, não é guardada e é apagada da memória (`CharArray`) após o uso.
- **Integridade:** além da tag do GCM, a importação confere o SHA-256 do banco, o `PRAGMA integrity_check`, a versão do schema (mais nova é recusada) e as contagens, somas e saldo do manifesto. O Room ainda valida o hash de identidade do schema.
- **Arquivo malicioso:** limite de 50 MB, cabeçalho de até 4 KB e iterações entre 100 mil e 5 milhões (evita travar o app). O banco recebido é aberto como cópia separada; o banco atual só muda depois de tudo conferido, e numa única transação.
- **Conteúdo:** lançamentos, categorias, contas, contas a pagar, lembretes, orçamentos, importações e modelos de CSV. **Não** leva a chave da Gemini, os ajustes nem o histórico do chat. Mesclar também não traz o chat, as ações pendentes, o registro de notificações nem a auditoria.
- Lembrete opcional (padrão: 30 dias) e aviso fixo de que desinstalar, limpar os dados ou perder o aparelho apaga o que não foi exportado.
- Temporários (cópia do banco, arquivo de importação, áudios esquecidos) ficam no cache privado e são apagados ao fim da operação ou ao abrir o app (mais de 1 hora).

## Bloqueio do app e tela
- Bloqueio opcional com `BiometricPrompt` (biometria ou PIN, padrão ou senha do aparelho; `BIOMETRIC_WEAK or DEVICE_CREDENTIAL`). Ele vale ao abrir e após N minutos em segundo plano (0 = sempre); rotação de tela não conta. Bloqueado, nenhuma tela com dados é composta.
- Ligar o bloqueio exige autenticar na hora. Sem bloqueio de tela no Android, o recurso não liga (e, se o bloqueio do aparelho for removido depois, o app não prende o usuário fora).
- `FLAG_SECURE` (padrão ligado, configurável): impede capturas e gravação de tela e a miniatura em Recentes.

## Apagar tudo
- Ajustes › Backup e segurança › **Apagar todos os dados**, com confirmação forte (digitar APAGAR). Apaga o banco inteiro (inclusive o chat), as preferências, os segredos, a **chave do Keystore**, as notificações e os temporários, e desativa a leitura de notificações. O app volta ao primeiro uso.

## Dados enviados a terceiros
- Somente à Gemini API (Google), somente via HTTPS (`usesCleartextTraffic=false`) e somente com consentimento ativo e chave salva, verificados a cada chamada.
- Enviado: a mensagem atual (texto ou áudio) e os nomes das categorias. Nunca o histórico, saldos ou outros lançamentos.
- `"store": false` em toda requisição. No plano gratuito, os termos do Google permitem usar o conteúdo para melhorar os produtos, como o diálogo de consentimento informa.
- A chave vai no cabeçalho `x-goog-api-key`, nunca na URL; os logs registram só o tipo do erro (ex.: "HTTP 429").
- Áudio: arquivo temporário no cache privado, apagado logo após a leitura; nada de áudio fica salvo.
- Prompt injection: o texto do usuário vai marcado como dados; a resposta passa por schema fechado e validação local; a IA nunca grava, só propõe cartões que o usuário confirma.
- Observação: o HyperOS registra no logcat as teclas **injetadas por ADB**. Isso afeta só testes automatizados, não o uso normal com o teclado virtual.

## Limites
| O quê | Limite |
|---|---|
| Áudio | 60 s, AAC 16 kHz; apagado após a leitura |
| Itens por mensagem (IA) | 10 lançamentos; categorização por IA: 60 descrições |
| Extrato OFX/CSV | 5 MB, lido em blocos |
| Backup `.finbot` | 50 MB; cabeçalho até 4 KB; iterações 100 mil a 5 milhões |
| Texto de notificação analisado | 1 000 caracteres |
| Gemini | conexão 10 s, leitura 45 s; 1 nova tentativa só em falha transitória (nunca em 429) |
| Temporários | apagados ao fim da operação ou ao abrir o app (mais de 1 hora) |
| Descrição / observação | 120 / 500 caracteres |

## Modelo de ameaças (ETAPA 11)
| Ameaça | Mitigação | Risco que resta |
|---|---|---|
| **Aparelho perdido ou roubado** (desbloqueado ou com a tela desbloqueada por alguém) | Bloqueio do app com biometria ou credencial; `FLAG_SECURE`; notificações privadas na tela bloqueada; "Apagar tudo" | Com o bloqueio do app desligado, quem tem o celular desbloqueado vê tudo. O banco não é criptografado além da criptografia do Android |
| **APK extraído / engenharia reversa** | Nenhum segredo no APK; a chave da Gemini é do usuário e fica no Keystore; não há servidor do FinBot | A lógica é visível; nada sensível depende de segredo no código |
| **Aparelho com root ou malware privilegiado** | Keystore (chave não exportável), backup automático desligado, logs sem conteúdo | Root lê o banco e pode usar a chave do Keystore enquanto o app roda. Não há defesa completa no cliente |
| **Chave Gemini vazada** | Fica só no aparelho, cifrada; vai em cabeçalho, nunca na URL nem em logs; não entra no backup | Revogar no Google AI Studio e cadastrar outra (documentado) |
| **Prompt injection — texto e áudio** | Entrada marcada como dados; schema JSON fechado; validação local (valores, datas, categorias); a IA só propõe cartões que o usuário confirma | Uma interpretação errada pode virar cartão, mas nunca é gravada sem confirmação |
| **Prompt injection — extrato** | Parsers determinísticos; descrições são só dados; IA de categoria só devolve categorias existentes; tudo entra como PENDING_REVIEW | Descrições estranhas aparecem na revisão como texto |
| **Prompt injection — notificação** | Só apps escolhidos; regex sem IA; texto bruto não é guardado; sugestão em revisão | Um app permitido que poste notificações forjadas pode gerar sugestões, que precisam ser confirmadas |
| **Arquivo de importação malicioso** (OFX/CSV/.finbot) | Limites de tamanho; leitura tolerante sem execução; SQL só com parâmetros; neutralização de fórmulas na exportação CSV; `.finbot` autenticado (GCM), conferido (hash, integridade, versão, totais) e aplicado em transação | Um `.finbot` válido com a senha certa é aceito como verdadeiro: o usuário precisa confiar em quem enviou |
| **Perda de dados** | Lembrete de backup; aviso fixo; exportação criptografada; importação que nunca altera nada se falhar; mesclar não sobrescreve | Sem backup exportado, desinstalar ou perder o aparelho apaga tudo (não há nuvem por decisão de privacidade) |
| **Senha do backup fraca ou esquecida** | Mínimo de 10 caracteres; PBKDF2 com 600 mil iterações | Senha esquecida = backup irrecuperável (avisado na tela). Senhas curtas e comuns ainda podem ser quebradas por força bruta offline |
