package com.galeriaia.app.search

import java.time.LocalDate
import java.time.YearMonth

/**
 * Filtro de data. Ou um intervalo [start, end] (inclusivo), ou um mês do ano em qualquer ano.
 */
data class DateFilter(
    val start: LocalDate? = null,
    val end: LocalDate? = null,
    val monthOfYear: Int? = null,
    val label: String,
) {
    fun matches(date: LocalDate): Boolean {
        if (monthOfYear != null) return date.monthValue == monthOfYear
        if (start != null && date.isBefore(start)) return false
        if (end != null && date.isAfter(end)) return false
        return true
    }
}

data class ParsedQuery(
    /** O que sobrou da descrição depois de tirar a parte de data (pode ser vazio). */
    val text: String,
    val date: DateFilter?,
)

/**
 * Extrai datas escritas em português da descrição. Exemplos que entende:
 *  "hoje", "ontem", "últimos 10 dias", "semana passada", "mês passado", "ano passado",
 *  "este ano", "este mês", "de 2022 a 2024", "entre 2022 e 2024", "15/03/2025",
 *  "03/2025", "março de 2025", "dezembro" (qualquer ano), "2024".
 */
object QueryParser {

    private val MONTHS = listOf(
        "janeiro", "fevereiro", "marco", "abril", "maio", "junho",
        "julho", "agosto", "setembro", "outubro", "novembro", "dezembro"
    )
    private val MONTH_LABEL = listOf("jan", "fev", "mar", "abr", "mai", "jun", "jul", "ago", "set", "out", "nov", "dez")
    private val MONTH_ALT = MONTHS.joinToString("|")
    private const val YEAR = "(19[5-9]\\d|20\\d\\d)"
    private const val PREP = "(?:\\b(?:tiradas?|feitas?)\\s+)?(?:\\b(?:em|de|do|da|dos|das|no|na|nos|nas|durante|desde)\\s+)?"

    private val STOPWORDS = setOf(
        "foto", "fotos", "imagem", "imagens", "todas", "todos", "as", "os", "minhas", "meus",
        "tiradas", "tirada", "feitas", "feita", "de", "da", "do", "em", "no", "na", "e", "com"
    )

    private class Rule(val regex: Regex, val build: (MatchResult, LocalDate) -> DateFilter?)

    private fun r(p: String) = Regex(PREP + p)

    private val rules: List<Rule> = listOf(
        Rule(r("\\bhoje\\b")) { _, t -> DateFilter(t, t, label = "hoje") },
        Rule(r("\\bontem\\b")) { _, t -> t.minusDays(1).let { DateFilter(it, it, label = "ontem") } },
        Rule(r("\\b(?:nos\\s+|nas\\s+)?ultim[oa]s\\s+(\\d{1,4})\\s+dias\\b")) { m, t ->
            val n = m.groupValues[1].toLong()
            DateFilter(t.minusDays(n - 1), t, label = "últimos $n dias")
        },
        Rule(r("\\b(?:nos\\s+|nas\\s+)?ultim[oa]s\\s+(\\d{1,3})\\s+(?:meses|mes)\\b")) { m, t ->
            val n = m.groupValues[1].toLong()
            DateFilter(t.minusMonths(n), t, label = "últimos $n meses")
        },
        Rule(r("\\bsemana\\s+passada\\b")) { _, t ->
            val thisMonday = t.minusDays((t.dayOfWeek.value - 1).toLong())
            DateFilter(thisMonday.minusWeeks(1), thisMonday.minusDays(1), label = "semana passada")
        },
        Rule(r("\\bmes\\s+passado\\b")) { _, t ->
            val ym = YearMonth.from(t).minusMonths(1)
            DateFilter(ym.atDay(1), ym.atEndOfMonth(), label = "mês passado")
        },
        Rule(r("\\bano\\s+passado\\b")) { _, t ->
            val y = t.year - 1
            DateFilter(LocalDate.of(y, 1, 1), LocalDate.of(y, 12, 31), label = "$y")
        },
        Rule(r("\\b(?:neste|nesse|este|esse)\\s+mes\\b")) { _, t ->
            DateFilter(t.withDayOfMonth(1), t, label = "este mês")
        },
        Rule(r("\\b(?:neste|nesse|este|esse)\\s+ano\\b")) { _, t ->
            DateFilter(LocalDate.of(t.year, 1, 1), t, label = "${t.year}")
        },
        Rule(r("\\b(?:de|entre)\\s+$YEAR\\s+(?:a|e|ate)\\s+$YEAR\\b")) { m, _ ->
            val a = m.groupValues[1].toInt()
            val b = m.groupValues[2].toInt()
            val (lo, hi) = if (a <= b) a to b else b to a
            DateFilter(LocalDate.of(lo, 1, 1), LocalDate.of(hi, 12, 31), label = "$lo a $hi")
        },
        Rule(r("\\b(\\d{1,2})/(\\d{1,2})/(\\d{4})\\b")) { m, _ ->
            runCatching {
                val d = LocalDate.of(m.groupValues[3].toInt(), m.groupValues[2].toInt(), m.groupValues[1].toInt())
                DateFilter(d, d, label = "%02d/%02d/%d".format(d.dayOfMonth, d.monthValue, d.year))
            }.getOrNull()
        },
        Rule(r("\\b(\\d{1,2})/(\\d{4})\\b")) { m, _ ->
            val month = m.groupValues[1].toInt()
            if (month !in 1..12) null else {
                val ym = YearMonth.of(m.groupValues[2].toInt(), month)
                DateFilter(ym.atDay(1), ym.atEndOfMonth(), label = "${MONTH_LABEL[month - 1]}/${ym.year}")
            }
        },
        Rule(r("\\b($MONTH_ALT)(?:\\s+(?:de|do))?\\s+$YEAR\\b")) { m, _ ->
            val month = MONTHS.indexOf(m.groupValues[1]) + 1
            val ym = YearMonth.of(m.groupValues[2].toInt(), month)
            DateFilter(ym.atDay(1), ym.atEndOfMonth(), label = "${MONTH_LABEL[month - 1]}/${ym.year}")
        },
        Rule(r("\\b($MONTH_ALT)\\b")) { m, _ ->
            val month = MONTHS.indexOf(m.groupValues[1]) + 1
            DateFilter(monthOfYear = month, label = "${MONTHS[month - 1].replace("marco", "março")} (qualquer ano)")
        },
        Rule(r("\\b$YEAR\\b")) { m, _ ->
            val y = m.groupValues[1].toInt()
            DateFilter(LocalDate.of(y, 1, 1), LocalDate.of(y, 12, 31), label = "$y")
        },
    )

    /** Tira acentos mantendo o mesmo comprimento da string (para os índices baterem). */
    private fun normalize(s: String): String {
        val sb = StringBuilder(s.length)
        for (c in s.lowercase()) {
            sb.append(
                when (c) {
                    'á', 'à', 'â', 'ã', 'ä' -> 'a'
                    'é', 'è', 'ê', 'ë' -> 'e'
                    'í', 'ì', 'î', 'ï' -> 'i'
                    'ó', 'ò', 'ô', 'õ', 'ö' -> 'o'
                    'ú', 'ù', 'û', 'ü' -> 'u'
                    'ç' -> 'c'
                    else -> c
                }
            )
        }
        return sb.toString()
    }

    fun parse(input: String, today: LocalDate = LocalDate.now()): ParsedQuery {
        // lowercase() pode mudar o tamanho em casos raros; nesse caso trabalhamos só com a versão minúscula
        val original = if (input.lowercase().length == input.length) input else input.lowercase()
        val norm = normalize(original)

        var filter: DateFilter? = null
        var text = original
        for (rule in rules) {
            val m = rule.regex.find(norm) ?: continue
            val f = rule.build(m, today) ?: continue
            filter = f
            text = original.removeRange(m.range)
            break
        }

        text = text.replace(Regex("\\s+"), " ").trim()
        // remove preposições soltas no começo/fim ("praia em" -> "praia")
        val dangling = Regex("(?i)(^|\\s)(em|de|do|da|no|na|nos|nas|entre|durante|desde|e)$")
        while (true) {
            val m = dangling.find(text) ?: break
            text = text.substring(0, m.range.first).trim()
        }
        val leading = Regex("(?i)^(em|de|do|da|no|na|nos|nas|e)\\s+")
        while (true) {
            val m = leading.find(text) ?: break
            text = text.substring(m.range.last + 1).trim()
        }
        text = text.trim(' ', ',', '.', '-')

        val words = normalize(text).split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.isEmpty() || words.all { it in STOPWORDS }) text = ""

        return ParsedQuery(text, filter)
    }
}
