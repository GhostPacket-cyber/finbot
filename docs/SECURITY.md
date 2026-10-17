# Segurança — FinBot

Documento vivo; o modelo de ameaças completo entra na ETAPA 08.

## Segredos
- O token do bot Telegram e a chave Gemini são informados **somente no aparelho**, pela tela administrativa. Nunca entram no código-fonte, no Git, no APK nem em logs.
- Ficam cifrados com AES-256-GCM, com chave não exportável no Android Keystore (`security/SecretCipher.kt`), dentro do diretório privado do app.
- A UI mostra no máximo os 4 últimos caracteres (`•••• abcd`) e permite substituir ou remover.
- **Limitação:** chaves guardadas no cliente não são invioláveis. Root, malware com privilégios ou depuração no aparelho podem obtê-las. Se houver suspeita de vazamento, revogue o token no @BotFather (`/revoke`) e a chave no Google AI Studio, e cadastre novos valores.

## Logs
- `SafeLog` registra apenas eventos técnicos. Para exceções, registra só o tipo, nunca a mensagem.
- `LogSanitizer` remove como rede de segurança: tokens do Telegram (inclusive em URLs `bot<token>`), chaves `AIza…`, `x-goog-api-key`, `Bearer` e parâmetros `key=`/`token=`.
- Proibido registrar conteúdo de mensagens, áudios, valores financeiros ou nomes de usuário.

## Backup e exportação
- O backup automático do Android (nuvem e transferência entre aparelhos) está desativado.
- Desinstalar o app ou limpar seus dados apaga tudo. O backup manual criptografado entra na ETAPA 08.

## Tela
- `FLAG_SECURE` impede capturas de tela da área administrativa.

## Dados enviados a terceiros
- Telegram e Gemini processam mensagens em servidores externos: arquitetura local-first não é offline.
- O envio ao Gemini exige consentimento explícito registrado na tela (só a opção e a data são armazenadas).
