# Manual do usuário — FinBot 1.0

O FinBot registra e organiza seus gastos e receitas pelo chat, direto no seu celular. Nada é gravado sem a sua confirmação, e os dados não saem do aparelho, a não ser que você ligue a IA (veja **Gemini**).

## 1. Primeiro uso
1. Abra o app, informe como quer ser chamado (opcional) e confira o fuso horário.
2. As categorias padrão (Mercado, Alimentação, Transporte, Moradia, Saúde, Assinaturas, Educação, Lazer, Salário, Outros) e a conta "Carteira" já vêm criadas.
3. Para usar a IA, veja a seção 3. Sem ela, os comandos e frases curtas funcionam offline.

## 2. Chat sem IA (offline)
Escreva naturalmente ou use comandos. Cada registro aparece num **cartão** com Confirmar, Editar e Cancelar; o cartão vale 15 minutos e só pode ser usado uma vez.

| Você escreve | O que acontece |
|---|---|
| `gastei 45,90 no mercado` | Cartão de despesa R$ 45,90, categoria Mercado, hoje |
| `paguei 120 de luz ontem` | Despesa de ontem, categoria Moradia |
| `recebi 2.500 de salário` | Receita, categoria Salário |
| `comprei 30 na farmácia dia 5` | Despesa no dia 5 deste mês |
| `/gasto 35 almoço` · `/receita 2500 salário` | Atalhos de registro |
| `/saldo` | Saldo calculado (receitas − despesas confirmadas) |
| `/hoje` · `/mes` | Resumo do dia / do mês, com variação e contas pendentes |
| `/gastos` (ou `/gastos 2` para a próxima página) | Últimos lançamentos |
| `/contas` · `/lembretes` | Contas a pagar e lembretes |
| `/desfazer` | Desfaz o último registro feito pelo chat (pede confirmação) |
| `/ajuda` | Lista de exemplos |

Valores aceitos: `45`, `45,9`, `45,90`, `1.234,56`, `R$ 1.234,56`. Datas: `hoje`, `ontem`, `anteontem`, `dia 5`, `05/10`. Se faltar algo (ex.: o valor), o FinBot pergunta.

## 3. Gemini (IA opcional)
- **Ligar:** Ajustes › **Envio ao Gemini** (leia o aviso) e salve a sua **chave da Gemini API** (crie no Google AI Studio). A chave sozinha não basta: o consentimento precisa estar ligado.
- **Frases livres:** "comprei um presente de quarenta reais", "gastei 12 no café e 35,50 no almoço" (até 10 itens por mensagem), "quanto gastei este mês?".
- **Voz:** toque no 🎤, fale até 60 s e toque de novo para enviar. Exemplos: "paguei cinquenta e quatro de Uber hoje"; "recebi trezentos do freela". O áudio é apagado logo após o envio.
- **O que sai do aparelho:** o texto ou o áudio da mensagem atual e os nomes das suas categorias. Nunca o histórico nem o saldo.
- **Cota:** a API gratuita tem limite. Ao estourar, o app avisa e você pode usar os comandos sem IA.
- A IA **só propõe**: tudo vira cartão, e você confirma.

## 4. Lançamentos
- Aba **Lançamentos:** lista por mês com filtros (período, tipo, categoria, conta, origem) e busca. Toque num item para editar ou excluir (com "Desfazer").
- **+ Novo lançamento** para registrar pelo formulário.
- **Categorias:** criar, renomear, arquivar, **mesclar** (move todos os lançamentos) e **Orçamento mensal…** (categorias de despesa).
- **Revisar:** sugestões vindas de extratos e notificações ficam aqui e **não contam no saldo** até você confirmar.

## 5. Contas a pagar e lembretes
- Aba **Contas:** cadastre contas únicas ou mensais, com avisos (ex.: 3 dias antes e no dia, às 9h). Ao pagar, o app cria o lançamento.
- **Lembretes:** avisos livres em data e hora.
- Os avisos podem atrasar com a economia de bateria (seção 10).

## 6. Relatórios
- Aba **Relatórios:** resumo do mês com variação contra o mês anterior, despesas por categoria (rosca), gasto acumulado, evolução de 6 meses e contas. Toque em **Ver tabela** para ver os números.
- **Exportar CSV:** o mês ou tudo, para abrir no Excel ou no Google Planilhas (`;`, vírgula decimal).

## 7. Importar extratos (OFX/CSV)
1. **No banco:** procure no app ou no internet banking a opção de **exportar o extrato** em **OFX** (às vezes "Money" ou "Quicken") ou **CSV**. Um período menor gera um arquivo menor (limite: 5 MB).
2. **No FinBot:** Lançamentos › **Importar** › escolha o arquivo.
3. **OFX:** é lido direto. A conta aparece mascarada (••••1234).
4. **CSV:** confira as colunas sugeridas: data, descrição, valor (ou débito/crédito), formato da data e linhas de cabeçalho e rodapé a pular. **Salve como modelo** com o nome do banco para a próxima vez.
5. Na pré-visualização:
   - itens **já importados** são ignorados (reimportar não duplica);
   - **possíveis duplicatas** de lançamentos feitos à mão vêm desmarcadas;
   - a categoria é sugerida pelo seu histórico e por palavras-chave;
   - opcional: **Sugerir categorias com IA** envia só as descrições.
6. **Importar** manda tudo para **Revisar**. Confirme lá.
7. Errou? Em Importar › Importações anteriores › **Desfazer lote** remove só aquele lote.

Dicas por formato:
- **OFX** é o mais confiável (cada lançamento tem identificador do banco).
- **CSV com "R$ -1.234,56"**, valores com `D`/`C` ou entre parênteses são entendidos.
- **CSV em que as despesas aparecem positivas:** ligue "Despesas aparecem positivas (inverter sinal)".

## 8. Notificações de banco (opcional)
1. Ajustes › **Configurar notificações de banco** › ligue **Ler notificações de banco**.
2. **Conceder acesso no Android** e ative o FinBot. No Android 13+, se a opção aparecer bloqueada ("configuração restrita"): Informações do app › ⋮ › **Permitir configurações restritas**.
3. Marque só os apps de banco que você usa (aparecem apenas os instalados).
4. Compras, Pix e transferências viram **sugestões em Revisar**, com o aviso "Nova sugestão: R$ 45,90 em PADARIA — Revisar".

Bom saber:
- O texto das notificações não é guardado e nada sai do aparelho.
- Códigos de verificação ficam ocultos para o app (Android 15+).
- **Desligar a chave** retira também o acesso no Android: para religar, conceda de novo.
- **Testar um texto:** cole o texto de uma notificação para ver o que seria extraído (não grava nada). Se o banco mudar o texto, a notificação passa a ser ignorada.

## 9. Alertas
Em Ajustes › **Alertas** (cada um liga e desliga):
- **Orçamento por categoria:** aviso em 80% e 100% do limite mensal.
- **Possível cobrança duplicada:** mesmo valor e estabelecimento em até N horas.
- **Nova assinatura recorrente:** mesmo valor e estabelecimento em meses seguidos; sugere cadastrar como conta.
- **Gasto fora do padrão:** categoria acima da média dos 3 meses anteriores + limiar.
- **Resumo semanal** no dia e hora escolhidos.

Os alertas chegam como mensagem no chat (e notificação, se permitido), uma vez cada. "Texto mais amigável com IA" é opcional e só reescreve o texto; os números são conferidos.

## 10. Backup, trocar de aparelho e segurança
- **Exportar:** Ajustes › **Backup e segurança** › Exportar backup.
  1. Escolha uma senha de 10+ caracteres.
  2. Salve o arquivo `.finbot` onde quiser (Drive, computador…).
  3. **Sem a senha, o arquivo não abre.**
- **Importar:** escolha o arquivo e digite a senha.
  - **Mesclar:** junta com os dados daqui, sem duplicar nem sobrescrever.
  - **Substituir tudo:** para trocar de aparelho; pede confirmação dupla.
- **Lembrete de backup:** a cada 30 dias (configurável).
- O backup **não** leva a chave da Gemini, os ajustes nem o histórico do chat.
- **Bloquear o app:** biometria ou o bloqueio de tela do aparelho, ao abrir e após N minutos fora.
- **Proteger a tela:** impede prints e esconde o app em Recentes.
- **Apagar todos os dados:** digite APAGAR. Isso apaga tudo, inclusive a chave da Gemini.

## 11. Resolução de problemas
| Problema | O que fazer |
|---|---|
| "Falta a chave…" ou a IA não responde | Confira em Ajustes se **Envio ao Gemini** está ligado **e** se a chave foi salva |
| "Limite de uso da Gemini API" | Cota gratuita esgotada: espere o tempo indicado ou use os comandos sem IA |
| "A chave foi recusada" | Gere outra no Google AI Studio e substitua em Ajustes |
| O áudio não é entendido | Fale mais perto do microfone, em ambiente silencioso, ou digite |
| Aviso de conta não chegou / atrasou | Bateria **Sem restrições** e **Inicialização automática** (Xiaomi); abrir o app confere os avisos pendentes |
| Notificações de banco não viram sugestão | Confira a chave, o acesso no Android e o banco marcado; use **Testar um texto** |
| CSV não reconhecido | Ajuste as colunas, o separador e o formato da data; pule as linhas de cabeçalho e rodapé; salve como modelo |
| "Senha incorreta ou arquivo alterado" | Senha errada, ou o arquivo foi modificado ou corrompido. Nada foi alterado no app |
| O app não instala por cima | A versão instalada tem outra assinatura: exporte o backup, desinstale, instale e importe |
| Esqueci a senha do backup | Não há recuperação (é a proteção). Exporte de novo, com outra senha, a partir de um aparelho que tenha os dados |
