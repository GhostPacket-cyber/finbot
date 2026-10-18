# Segurança — FinBot

Documento vivo; o modelo de ameaças completo entra na ETAPA 11 (v2).

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
- O banco **não é criptografado** em repouso; depende da proteção do sandbox do Android e da criptografia do aparelho. Avaliar na ETAPA 11 (backup exportado será criptografado).
- `audit_log` não guarda descrições antigas nem conteúdo de chat ou áudio, só os campos alterados.

## Backup e exportação
- O backup automático do Android (nuvem e transferência entre aparelhos) está desativado.
- Desinstalar o app ou limpar seus dados apaga tudo. A exportação e importação criptografadas entram na ETAPA 11.

## Tela
- `FLAG_SECURE` em toda a `MainActivity` impede capturas de tela; vira configurável na ETAPA 11.

## Dados enviados a terceiros
- Somente à Gemini API (Google), somente via HTTPS (`usesCleartextTraffic=false`) e somente com consentimento ativo e chave salva, verificados a cada chamada.
- Enviado: a mensagem atual (texto ou áudio) e os nomes das categorias. Nunca o histórico, saldos ou outros lançamentos.
- `"store": false` em toda requisição. No plano gratuito, os termos do Google permitem usar o conteúdo para melhorar os produtos, como o diálogo de consentimento informa.
- A chave vai no cabeçalho `x-goog-api-key`, nunca na URL; os logs registram só o tipo do erro (ex.: "HTTP 429").
- Áudio: arquivo temporário no cache privado, apagado logo após a leitura; nada de áudio fica salvo.
- Prompt injection: o texto do usuário vai marcado como dados; a resposta passa por schema fechado e validação local; a IA nunca grava, só propõe cartões que o usuário confirma.
- Observação: o HyperOS registra no logcat as teclas **injetadas por ADB**. Isso afeta só testes automatizados, não o uso normal com o teclado virtual.
