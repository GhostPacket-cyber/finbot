package br.com.finbot.alerts

import br.com.finbot.assistant.CategorySuggester
import br.com.finbot.domain.Money
import br.com.finbot.importing.DedupKeys
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

enum class AlertType { BUDGET, DUPLICATE_CHARGE, SUBSCRIPTION, UNUSUAL_SPENDING, WEEKLY_SUMMARY }

/**
 * Alerta calculado por regra. [key] garante entrega única (`notification_log`).
 * [alsoMarks]: chaves que passam a valer como entregues junto (ex.: 100% do orçamento cobre o aviso de 80%).
 * O [text] já traz todos os números calculados; a IA, se usada, só reescreve o tom.
 */
data class Alert(
    val key: String,
    val type: AlertType,
    val title: String,
    val text: String,
    val alsoMarks: List<String> = emptyList(),
)

/** Despesa considerada pelas regras (ativa e não descartada). */
data class SpendItem(
    val uuid: String,
    val amountCents: Long,
    val description: String,
    val categoryId: Long?,
    val occurredAt: Instant,
    val confirmed: Boolean,
)

/** Regras determinísticas dos alertas (ETAPA 10). Puras: sem banco, sem relógio, sem IA. */
object AlertRules {
    /** Só pares/assinaturas recentes geram alerta: ligar o recurso não dispara avisos sobre o passado. */
    val RECENT: Duration = Duration.ofDays(7)

    /** Diferença mínima para "gasto fora do padrão" (evita alertar R$ 12 contra média de R$ 6). */
    const val MIN_UNUSUAL_EXCESS_CENTS = 2_000L

    /** Resumo semanal atrasado além disso (app sem rodar) é pulado. */
    val WEEKLY_CATCH_UP: Duration = Duration.ofDays(2)

    // ---------- 1. Orçamento ----------

    /** [spent] e [limits] por categoria no mês. Avisa em 80% e 100% (inclusive). */
    fun budget(month: YearMonth, spent: Map<Long, Long>, limits: Map<Long, Long>, names: Map<Long, String>): List<Alert> =
        limits.filter { it.value > 0 }.mapNotNull { (categoryId, limit) ->
            val value = spent[categoryId] ?: 0L
            val name = names[categoryId] ?: "categoria"
            val base = "alert:budget:$categoryId:$month"
            val percent = value * 100 / limit
            when {
                value >= limit -> Alert(
                    key = "$base:100",
                    type = AlertType.BUDGET,
                    title = "Orçamento de $name atingido",
                    text = "Você atingiu o orçamento de $name em ${monthLabel(month)}: ${Money.format(value)} de ${Money.format(limit)} ($percent%).",
                    alsoMarks = listOf("$base:80"),
                )
                value * 100 >= limit * 80 -> Alert(
                    key = "$base:80",
                    type = AlertType.BUDGET,
                    title = "80% do orçamento de $name",
                    text = "Você já usou $percent% do orçamento de $name em ${monthLabel(month)}: ${Money.format(value)} de ${Money.format(limit)}. " +
                        "Restam ${Money.format(limit - value)}.",
                )
                else -> null
            }
        }

    // ---------- 2. Possível cobrança duplicada ----------

    /** Mesmo valor e estabelecimento semelhante em até [windowHours] horas; o mais recente nos últimos 7 dias. */
    fun duplicateCharges(items: List<SpendItem>, windowHours: Int, now: Instant, zone: ZoneId): List<Alert> {
        val window = Duration.ofHours(windowHours.toLong())
        val sorted = items.sortedBy { it.occurredAt }
        val alerts = mutableListOf<Alert>()
        for (i in sorted.indices) {
            for (j in i + 1 until sorted.size) {
                val a = sorted[i]
                val b = sorted[j]
                if (Duration.between(a.occurredAt, b.occurredAt) > window) break
                if (a.amountCents != b.amountCents || !similar(a.description, b.description)) continue
                if (Duration.between(b.occurredAt, now) > RECENT) continue
                val (u1, u2) = listOf(a.uuid, b.uuid).sorted()
                val time = { t: Instant -> t.atZone(zone).let { "%02d/%02d %02d:%02d".format(it.dayOfMonth, it.monthValue, it.hour, it.minute) } }
                alerts += Alert(
                    key = "alert:dup:$u1:$u2",
                    type = AlertType.DUPLICATE_CHARGE,
                    title = "Possível cobrança duplicada",
                    text = "Possível cobrança duplicada: ${Money.format(a.amountCents)} em ${b.description} (${time(a.occurredAt)} e ${time(b.occurredAt)}). " +
                        "Se for engano, confira com o banco ou exclua um dos lançamentos.",
                )
            }
        }
        return alerts
    }

    // ---------- 3. Nova assinatura recorrente ----------

    /** Bill já cadastrada: título e valor. */
    data class KnownBill(val title: String, val amountCents: Long)

    /**
     * Mesmo valor e estabelecimento em meses consecutivos (só confirmados). A ocorrência mais recente
     * precisa ser dos últimos 7 dias. Ignora o que já parece estar cadastrado como conta.
     */
    fun subscriptions(items: List<SpendItem>, bills: List<KnownBill>, now: Instant, zone: ZoneId): List<Alert> {
        val groups = items.filter { it.confirmed }.groupBy { merchantKey(it.description) to it.amountCents }
        return groups.mapNotNull { (key, group) ->
            val (merchant, amount) = key
            if (merchant.isEmpty()) return@mapNotNull null
            val latest = group.maxBy { it.occurredAt }
            if (Duration.between(latest.occurredAt, now) > RECENT) return@mapNotNull null
            val months = group.map { YearMonth.from(it.occurredAt.atZone(zone)) }.toSet()
            val latestMonth = YearMonth.from(latest.occurredAt.atZone(zone))
            if (latestMonth.minusMonths(1) !in months) return@mapNotNull null
            if (bills.any { it.amountCents == amount && similar(it.title, latest.description) } ||
                bills.any { merchantKey(it.title) == merchant }
            ) {
                return@mapNotNull null
            }
            Alert(
                key = "alert:sub:" + DedupKeys.sha256("$merchant|$amount").take(24),
                type = AlertType.SUBSCRIPTION,
                title = "Nova assinatura recorrente?",
                text = "${latest.description} cobrou ${Money.format(amount)} em ${monthLabel(latestMonth.minusMonths(1))} e em ${monthLabel(latestMonth)}. " +
                    "Parece uma assinatura: cadastre como conta em Contas › + Conta para receber lembretes.",
            )
        }
    }

    // ---------- 4. Gasto fora do padrão ----------

    /**
     * Gasto do mês na categoria acima da média dos 3 meses anteriores + [thresholdPercent]%.
     * A média inclui meses zerados, mas exige gasto em pelo menos 2 dos 3 meses (sem histórico, sem alerta).
     */
    fun unusualSpending(
        month: YearMonth,
        byCategory: Map<Long, Map<YearMonth, Long>>,
        names: Map<Long, String>,
        thresholdPercent: Int,
    ): List<Alert> = byCategory.mapNotNull { (categoryId, perMonth) ->
        val current = perMonth[month] ?: 0L
        val previous = (1..3).map { perMonth[month.minusMonths(it.toLong())] ?: 0L }
        if (previous.count { it > 0 } < 2) return@mapNotNull null
        val sum = previous.sum()
        // current > (sum / 3) × (100 + limiar) / 100, sem divisão inteira.
        if (current * 300 <= sum * (100 + thresholdPercent)) return@mapNotNull null
        val average = sum / 3
        if (current - average < MIN_UNUSUAL_EXCESS_CENTS) return@mapNotNull null
        val name = names[categoryId] ?: "uma categoria"
        val percent = if (average > 0) (current - average) * 100 / average else 0
        Alert(
            key = "alert:unusual:$categoryId:$month",
            type = AlertType.UNUSUAL_SPENDING,
            title = "Gasto acima do normal em $name",
            text = "Gasto fora do padrão em $name: ${Money.format(current)} em ${monthLabel(month)}, $percent% acima da média dos 3 meses anteriores (${Money.format(average)}).",
        )
    }

    // ---------- 5. Resumo semanal ----------

    /** O horário de resumo mais recente que já passou (dia da semana + hora, no fuso do usuário). */
    fun lastWeeklySlot(now: Instant, zone: ZoneId, day: DayOfWeek, time: LocalTime): Instant {
        val local = now.atZone(zone)
        var slot = local.toLocalDate().with(TemporalAdjusters.previousOrSame(day)).atTime(time).atZone(zone)
        if (slot.toInstant() > now) slot = slot.minusWeeks(1)
        return slot.toInstant()
    }

    /** Chave do resumo da semana, ou null se o horário passou há mais de 2 dias (app parado). */
    fun weeklyKey(now: Instant, zone: ZoneId, day: DayOfWeek, time: LocalTime): String? {
        val slot = lastWeeklySlot(now, zone, day, time)
        if (Duration.between(slot, now) > WEEKLY_CATCH_UP) return null
        return "alert:weekly:${slot.atZone(zone).toLocalDate()}"
    }

    data class WeeklyFacts(
        val start: LocalDate,
        val end: LocalDate,
        val incomeCents: Long,
        val expenseCents: Long,
        val topCategory: String?,
        val topCategoryCents: Long,
        val pendingReview: Int,
        val billsNext7Days: Int,
        val billsNext7DaysCents: Long,
    )

    fun weeklySummary(key: String, f: WeeklyFacts): Alert {
        val parts = mutableListOf(
            "Resumo da semana (${f.start.dayOfMonth.pad()}/${f.start.monthValue.pad()} a ${f.end.dayOfMonth.pad()}/${f.end.monthValue.pad()}): " +
                "despesas ${Money.format(f.expenseCents)}, receitas ${Money.format(f.incomeCents)}.",
        )
        if (f.topCategory != null && f.topCategoryCents > 0) parts += "Maior gasto: ${f.topCategory} (${Money.format(f.topCategoryCents)})."
        if (f.billsNext7Days > 0) parts += "Contas nos próximos 7 dias: ${f.billsNext7Days} (${Money.format(f.billsNext7DaysCents)})."
        if (f.pendingReview > 0) parts += "${f.pendingReview} sugestão(ões) esperando revisão."
        return Alert(key, AlertType.WEEKLY_SUMMARY, "Resumo da semana", parts.joinToString(" "))
    }

    // ---------- Apoio ----------

    private val NOISE = setOf(
        "compra", "pagamento", "pag", "pix", "deb", "debito", "credito", "cartao", "visa", "master", "elo", "ltda", "sa", "me", "eireli",
        "com", "br", "www", "do", "da", "de", "dos", "das", "em", "no", "na", "para",
    )

    /** Palavras relevantes da descrição (sem números, códigos e termos genéricos de extrato). */
    fun tokens(text: String): Set<String> =
        CategorySuggester.normalize(text).split(' ').filter { it.length >= 3 && it.none(Char::isDigit) && it !in NOISE }.toSet()

    fun merchantKey(text: String): String = tokens(text).sorted().joinToString(" ")

    /** Estabelecimentos semelhantes: mesmas palavras relevantes, uma contida na outra, ou sobreposição acima de 50%. */
    fun similar(a: String, b: String): Boolean {
        val ta = tokens(a)
        val tb = tokens(b)
        if (ta.isEmpty() || tb.isEmpty()) return CategorySuggester.normalize(a) == CategorySuggester.normalize(b) && a.isNotBlank()
        if (ta == tb || ta.containsAll(tb) || tb.containsAll(ta)) return true
        val inter = (ta intersect tb).size.toDouble()
        return inter / (ta union tb).size > 0.5
    }

    private val MONTHS = listOf("janeiro", "fevereiro", "março", "abril", "maio", "junho", "julho", "agosto", "setembro", "outubro", "novembro", "dezembro")

    fun monthLabel(month: YearMonth): String = "${MONTHS[month.monthValue - 1]}/${month.year}"

    private fun Int.pad() = toString().padStart(2, '0')
}
