# FinBot

App Android de finanças pessoais com assistente por chat (texto e voz). Cada pessoa usa no próprio celular: os dados ficam só no aparelho, num SQLite local, sem conta online, servidor ou nuvem.

**Versão 1.0.0** (`versionCode` 100). Contrato do projeto: `../Prompt/FINBOT_PROMPT_MESTRE_V2.md`. Manual do usuário: [docs/MANUAL.md](docs/MANUAL.md). Entrega: [docs/ENTREGA.md](docs/ENTREGA.md).

## O que faz
- **Chat:** "gastei 45,90 no mercado", "recebi 2.500 de salário", `/saldo`, `/mes`. Nada é gravado sem você tocar em **Confirmar**.
- **IA opcional (Gemini):** frases livres, vários gastos numa mensagem e áudio. Exige consentimento e a sua chave.
- Lançamentos com filtros, categorias, contas, revisão de sugestões.
- Contas a pagar e lembretes com aviso local.
- Relatórios com gráficos e exportação CSV.
- Importação de extratos **OFX/CSV**.
- Leitura **opcional** de notificações de banco.
- **Alertas:** orçamento, cobrança duplicada, assinaturas, gasto fora do padrão e resumo semanal.
- **Segurança:** bloqueio por biometria e **backup criptografado** (`.finbot`) para trocar de aparelho.

## Avisos importantes
- **Sem Open Finance:** o FinBot não se conecta a banco nenhum. Os dados entram pelo chat, por extratos ou pelas notificações (opcional).
- **Gemini:** é opcional. A API gratuita tem **cota** e pode ficar indisponível. Quando a IA está ligada, **o conteúdo da mensagem (texto ou áudio) sai do aparelho para o Google**. Sem ela, tudo funciona offline com os comandos.
- **Lembretes podem atrasar:** dependem do WorkManager e das restrições de bateria do fabricante (Xiaomi, Samsung, Motorola…).
- **Faça backups:** desinstalar o app, limpar os dados ou perder o celular apaga tudo o que não foi exportado.

## Requisitos
- **Para usar:** Android 8.0 ou superior. O aparelho de teste é o POCO X7 Pro com Android 16 e HyperOS 3.0.
- **Para compilar:**
  - Android Studio recente (o JDK de build é o JBR embutido; o AGP 9.4 exige JDK 17+);
  - Android SDK Platform **37** e Build-Tools 36.

## Instalar o APK no celular

### Opção 1 — APK pronto
1. Copie `FinBot-1.0.0.apk` para o celular (cabo, Drive etc.) e toque nele.
2. Permita **instalar apps desconhecidos** para o app que abriu o arquivo (Arquivos, Chrome…).
3. No Xiaomi/POCO/Redmi, a **verificação de segurança** pode analisar o APK antes de instalar. Aceite ou, se bloquear, desative-a temporariamente em *Segurança → ⚙ → Verificação de segurança*.

> Trocar a versão de depuração pela release exige **desinstalar** antes, porque as assinaturas são diferentes. Isso apaga os dados: **exporte um backup antes** (Ajustes › Backup e segurança) e importe depois.

### Opção 2 — pelo computador (USB)
1. No celular, ative as **Opções do desenvolvedor** (toque 7 vezes em "Versão do SO" no HyperOS) e ative **Depuração USB**.
2. **Xiaomi/HyperOS:** ative também **Instalar via USB** (exige conta Mi e, às vezes, dados móveis) e **Depuração USB (Configurações de segurança)**. Se falhar com `INSTALL_FAILED_USER_RESTRICTED`, desative a verificação de apps antes de instalar em *Segurança → ⚙ → Verificação de segurança*.
3. Instale:
   ```bash
   adb install FinBot-1.0.0.apk
   ```

## Compilar
```bash
./gradlew assembleDebug
```
O APK de depuração sai em `app/build/outputs/apk/debug/app-debug.apk`.

**Release assinado:**
1. Copie `keystore.properties.example` para `keystore.properties`, que é ignorado pelo Git, e aponte para uma chave **fora do repositório**.
2. Rode:
   ```bash
   ./gradlew assembleRelease
   ```
3. O APK sai em `app/build/outputs/apk/release/app-release.apk`. Sem `keystore.properties`, o release não é assinado.
4. **Guarde a chave e a senha**: sem elas não há como publicar atualizações que instalem por cima.

**Testes:**
```bash
./gradlew testDebugUnitTest lintDebug
```
Os testes instrumentados rodam no aparelho com o runner próprio:
```bash
adb shell am instrument -w br.com.finbot.test/br.com.finbot.FinBotTestRunner
```

## Chave da Gemini (opcional)
1. Acesse o **Google AI Studio** (aistudio.google.com), entre com sua conta Google e crie uma **API key**.
2. No FinBot: Ajustes › **Envio ao Gemini** (leia e aceite o consentimento) e salve a chave.
3. A chave fica cifrada no Android Keystore deste aparelho, nunca aparece inteira e não entra no backup. Se vazar, revogue no AI Studio e cadastre outra.

## Permissões
| Permissão | Para quê | Quando é pedida |
|---|---|---|
| Internet | Só para a Gemini, com o consentimento ligado | Não é pedida (normal) |
| Microfone | Mensagens de voz | No primeiro toque no 🎤 |
| Notificações | Avisos de contas, lembretes, alertas e sugestões | Ao salvar a primeira conta ou lembrete |
| Acesso a notificações | Ler notificações de banco (opcional) | Você concede em Ajustes do Android; desligado por padrão |
| Biometria | Bloqueio do app (opcional) | Ao ligar o bloqueio |

O FinBot **não** pede SMS, contatos, localização, câmera, armazenamento nem alarmes exatos. Os arquivos (extratos, backup, CSV) passam pelo seletor do sistema, com acesso só ao arquivo escolhido.

## Bateria e lembretes
- Os avisos são conferidos a cada ~30 min pelo WorkManager e sempre que o app é aberto. O Android (Doze) e os fabricantes podem atrasá-los.
- **Xiaomi/HyperOS:** *Configurações → Apps → Gerenciar apps → FinBot* → ative **Inicialização automática** e, em **Economia de bateria**, escolha **Sem restrições**. Guia por modelo: https://dontkillmyapp.com.
- Avisos atrasados chegam com a nota "aviso com atraso de …". Com mais de 7 dias de atraso são pulados, mas a conta continua em "A pagar".

## Documentação
- [docs/MANUAL.md](docs/MANUAL.md): manual do usuário.
- [docs/ENTREGA.md](docs/ENTREGA.md): checklist de entrega, problemas conhecidos e plano de evolução.
- [CHANGELOG.md](CHANGELOG.md): mudanças por versão.
- `docs/SECURITY.md`: privacidade, limites e modelo de ameaças.
- `docs/DECISIONS.md`: decisões técnicas (ADRs).
- `docs/TEST_PLAN.md`: testes e evidências.
- `docs/PROGRESS.md`: andamento por etapa.
