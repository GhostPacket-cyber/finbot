# Plano de testes

## ETAPA 01

### Testes JVM (`app/src/test`) — `gradlew testDebugUnitTest`
| Classe | Cobre |
|---|---|
| `ConfigValidatorTest` | formato do token, chave Gemini opcional, IDs (positivos, até 15 dígitos, > 32 bits, distintos), fuso Área/Cidade |
| `LogSanitizerTest` | remoção de token Telegram, chave Google, cabeçalhos e parâmetros sensíveis; truncamento |
| `SecretStoreTest` | cifragem/decifragem, persistência ao reabrir, remoção, máscara, status "Ilegível" |
| `ConfigRepositoryTest` | padrões, persistência após reinício (reabrir o DataStore), revogação de consentimento, limite de 2 usuários |
| `BotStateRepositoryTest` | reconciliação após reinício do processo, contagem de falhas, Parar limpa o erro |

### Testes instrumentados (`app/src/androidTest`) — `gradlew connectedDebugAndroidTest`
| Classe | Cobre |
|---|---|
| `KeystoreSecretCipherTest` | ida e volta no Keystore real, IV aleatório, blob adulterado, chave apagada |
| `AdminScreenTest` | seções da tela, erro de validação ao salvar sem ID (requer app sem configuração prévia) |

### Manuais no aparelho (pendentes até haver dispositivo/emulador)
1. Instalar o APK debug, abrir o app e conceder (ou negar) a permissão de notificações ao tocar em Iniciar.
2. Preencher token, ID(s) e fuso → Salvar → o campo de token é limpo e o status mostra `•••• xxxx`.
3. Forçar a parada do app (Configurações > Apps > FinBot > Forçar parada), reabrir e confirmar que a configuração persiste.
4. `adb logcat -s FinBot` durante os passos 2–3: nenhum token ou chave pode aparecer.
5. Tentar capturar a tela: o sistema deve bloquear (FLAG_SECURE).
6. Remover o token e verificar que Iniciar fica desabilitado.
7. Abrir "Ajustes de bateria" e voltar: o diagnóstico é atualizado.

## Registro de execuções
Ver `docs/PROGRESS.md`.
