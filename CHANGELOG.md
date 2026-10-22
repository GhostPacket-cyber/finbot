# Changelog

Versões internas por etapa do prompt mestre v2. Datas no formato aaaa-mm-dd.

## 1.0.2 — 2026-10-22
- **Correção:** o rótulo "Lançamentos" da barra inferior quebrava em duas linhas em telas de ~400 dp. Achado nas capturas do README.
- Capturas do README geradas a partir do app real na JVM (Robolectric + Roborazzi), com dados fictícios (`ScreenshotsTest`, só roda com `FINBOT_SCREENSHOTS=1`).
- Licença MIT; README para o GitHub.

## 1.0.1 — 2026-10-22
- Textos desatualizados corrigidos, achados na instalação limpa do release: o primeiro uso dizia que o backup criptografado "estará disponível em uma próxima versão", e o chat dizia que contas e lembretes "chegam em breve" (já existem na aba Contas).

## 1.0.0 — 2026-10-22 (ETAPA 13)
- Primeira versão de entrega: **APK release assinado**, com a chave fora do repositório (`keystore.properties`, ignorado pelo Git).
- Tela **Sobre**: versão, política de privacidade local, avisos (sem Open Finance, cota da Gemini, dados enviados à IA, bateria) e licenças de código aberto.
- README de instalação (inclusive Xiaomi/HyperOS), manual do usuário, checklist de entrega, problemas conhecidos e plano de evolução.

## 0.13.0 — 2026-10-22 (ETAPA 12)
- Testes de ponta a ponta: Gemini por HTTP falso (válida, inválida, campos extras, 429, 500, timeout), entradas hostis, WorkManager com `WorkManagerTestInitHelper`, permissões e performance com 10 mil lançamentos.
- **Correção:** aspas no meio de um campo CSV eram tratadas como início de citação.
- WorkManager com inicialização sob demanda.

## 0.12.0 — 2026-10-22 (ETAPA 11)
- Bloqueio do app (biometria ou credencial) e proteção de tela configurável.
- Backup **`.finbot`** criptografado (AES-256-GCM + PBKDF2): exportar, importar (substituir ou mesclar sem duplicar), lembrete de backup.
- Apagar todos os dados (inclui a chave do Keystore); limpeza de temporários; modelo de ameaças.

## 0.11.0 — 2026-10-22 (ETAPA 10)
- Alertas por regras: orçamento (80%/100%), possível cobrança duplicada, nova assinatura, gasto fora do padrão e resumo semanal, no chat e por notificação, cada um uma única vez.
- Orçamento mensal por categoria; reescrita opcional pela IA, conferindo os números.

## 0.10.0 — 2026-10-22 (ETAPA 09)
- Leitura opcional de notificações de banco (desligada por padrão; só apps escolhidos), com sugestões para revisão e dedup por minuto.
- Chaves de ajuste com rótulo acessível.

## 0.9.0 — 2026-10-22 (ETAPA 08)
- Importação de extratos OFX (SGML/XML, UTF-8/ISO-8859-1/Windows-1252) e CSV (mapeamento de colunas, modelos por banco).
- Dedup, possíveis duplicatas, categorias sugeridas, lote atômico em revisão e desfazer lote.

## 0.8.0 — 2026-10-21 (ETAPA 07)
- Relatórios mensais com gráficos nativos acessíveis e exportação CSV (pt-BR, proteção contra fórmulas).

## 0.7.0 — 2026-10-18 (ETAPA 06)
- Contas a pagar com recorrência mensal, lembretes e avisos locais (WorkManager, sem duplicar).

## 0.6.0 — 2026-10-18 (ETAPA 05)
- Telas de lançamentos (filtros, busca, edição, desfazer), revisão em lote, categorias e contas.

## 0.5.0 — 2026-10-18 (ETAPA 04)
- Gemini opcional para texto e áudio, com consentimento, schema fechado e validação local.

## 0.4.0 — 2026-10-18 (ETAPA 03)
- Chat interno com comandos e frases em pt-BR sem IA; cartões de confirmação de uso único.

## 0.3.0 — 2026-10-17 (ETAPA 02)
- Banco Room/SQLite com regras financeiras (centavos em Long, fuso do usuário, UUIDs, auditoria).

## 0.2.0 — 2026-10-17 (ETAPA 01 v2)
- Mudança de rumo: de bot do Telegram para app isolado, com onboarding e navegação por abas.
