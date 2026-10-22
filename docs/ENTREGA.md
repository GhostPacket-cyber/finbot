# Entrega — FinBot 1.0.1

## Checklist
| Item | Situação | Evidência |
|---|---|---|
| APK debug | ✅ | `./gradlew assembleDebug` |
| APK release assinado (chave fora do Git) | ✅ | `./gradlew assembleRelease`; `apksigner verify`: v2 ok, `CN=FinBot` |
| Instalação limpa no aparelho alvo | ✅ | 2026-10-22: 1.0.0 após desinstalar a depuração (primeiro uso correto, listener desativado, sem falhas, abertura a frio 299 ms); atualização para 1.0.1 por cima sem perder dados |
| README (requisitos, instalação, HyperOS, Gemini, permissões) | ✅ | `README.md` |
| Manual do usuário | ✅ | `docs/MANUAL.md` |
| Tela Sobre (versão, licenças, privacidade) | ✅ | Ajustes › Sobre › Privacidade, avisos e licenças |
| Changelog | ✅ | `CHANGELOG.md` |
| Testes automatizados | ✅ | 157 JVM + 128 instrumentados (POCO X7 Pro, duas rodadas); lint sem erros (`docs/TEST_PLAN.md`) |
| Segurança e privacidade documentadas | ✅ | `docs/SECURITY.md` (limites e modelo de ameaças) |
| Problemas conhecidos e plano de evolução | ✅ | Abaixo |

## Validação final no aparelho (aceite da ETAPA 13)
| Cenário | Situação |
|---|---|
| Instalação limpa do release | ✅ 1.0.0 limpa → 1.0.1 por cima |
| Uso completo sem IA (chat, lançamentos, contas, relatórios) | ⏳ (já validado em debug nas etapas 03–07) |
| Com IA: registro por texto e por áudio | ⏳ (validado em debug na ETAPA 04 com a chave do usuário) |
| Importação de extrato | ⏳ (validado em debug na ETAPA 08 com OFX sintético) |
| Relatório | ⏳ |
| Lembrete / aviso | ⏳ (validado em debug nas ETAPAS 06 e 10) |
| Exportar e importar backup | ✅ debug: usuário em 2026-10-22; ⏳ release |

## Avisos registrados
- **Sem Open Finance:** não há conexão com bancos; os dados vêm do usuário, de extratos ou de notificações (opcional).
- **Gemini gratuito** está sujeito a **cota e disponibilidade**; o app sempre oferece o caminho sem IA.
- **O conteúdo enviado ao Gemini sai do aparelho** (texto ou áudio da mensagem; opcionalmente, descrições de extrato e textos de alerta).
- **Lembretes e alertas dependem das restrições de bateria do fabricante** e podem atrasar.

## Problemas conhecidos e limitações
1. A bateria em 24 h e os cenários manuais de resiliência (reinício, economia de bateria, sem internet, troca de fuso, armazenamento cheio, extratos reais) ainda não foram executados (roteiro em TEST_PLAN, ETAPA 12).
2. Os padrões de notificação e o mapeamento de CSV foram testados com **amostras sintéticas**. Textos e formatos reais dos bancos podem exigir ajuste (use "Testar um texto" e os modelos de CSV).
3. Pacotes do catálogo de bancos não confirmados num aparelho: Itaú, Bradesco, C6, Santander, BB, Mercado Pago e PicPay. Confirmados: Nubank, Inter e Caixa.
4. O banco local não tem criptografia própria; a proteção vem do sandbox do Android, da criptografia do aparelho e do bloqueio do app (ADR-020).
5. Trocar a versão de depuração pela release exige desinstalar (assinaturas diferentes). Exporte o backup antes.
6. Desligar a leitura de notificações faz o Android retirar o acesso; ao religar, é preciso conceder de novo.
7. No Android 8 e 9 o desbloqueio usa o diálogo da biblioteca biométrica; não foi testado (aparelho de teste: Android 16).
8. O backup não leva ajustes, a chave da Gemini nem o histórico do chat.
9. O release não usa R8 (sem minificação): APK de ~11 MB. Ligar o R8 exige regras e uma nova rodada de testes.
10. O orçamento é só por categoria e mês (sem orçamento total ou por conta).

## Plano de evolução (sugestões)
1. Executar o roteiro manual de resiliência e medir a bateria em 24 h; ajustar o intervalo do WorkManager se necessário.
2. Calibrar os padrões de notificação e criar modelos de CSV com exemplos reais dos bancos do usuário (sem versionar dados reais).
3. Ligar o R8 com regras e testes no release.
4. Avaliar SQLCipher para o banco ativo, se o app sair do uso pessoal.
5. Widget de saldo e atalho para o registro rápido.
6. Metas de economia e orçamento total do mês.
7. Argon2id no backup (formato já versionado no cabeçalho).
8. Testar em Android 8–12 (emulador) e em outros fabricantes (Samsung, Motorola).
