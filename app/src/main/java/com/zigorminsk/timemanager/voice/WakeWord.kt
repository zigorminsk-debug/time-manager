package com.zigorminsk.timemanager.voice

/**
 * Распознавание фразы-обращения («Слушай, Тайм») в потоке речи.
 *
 * Системное распознавание почти никогда не выдаёт текст идеально: «Слушай, Тайм»
 * приходит как «слушай тайм», «слушай таймер», «слушай, тай м» или «слyшай тайм».
 * Поэтому сравнение идёт по словам с допуском на 1–2 опечатки, а не по точному
 * совпадению строк.
 *
 * Модуль не зависит от Android и проверяется обычными JUnit-тестами.
 */
object WakeWord {

    /** Фраза по умолчанию. */
    const val DEFAULT = "слушай тайм"

    /** Варианты, которые приложение понимает всегда, даже если фраза изменена. */
    private val BUILT_IN = listOf(
        "слушай тайм",
        "слушай таймер",
        "слушай, тайм",
        "эй тайм",
        "окей тайм",
        "привет тайм",
        "тайм слушай",
        "слушай помощник",
        "эй помощник",
    )

    /** Короткие слова, которыми можно прервать ассистента. */
    private val STOP_WORDS = listOf(
        "стоп", "хватит", "замолчи", "тихо", "отмена", "пока",
        "до свидания", "выключись", "заткнись", "хватит слушать",
    )

    data class Match(
        /** Найдена ли фраза-обращение. */
        val found: Boolean,
        /** Текст без фразы-обращения — это и есть команда. */
        val command: String,
        /** Какая именно фраза сработала. */
        val phrase: String = "",
    )

    /** Все варианты обращения: пользовательский + встроенные. */
    fun aliases(phrase: String = DEFAULT): List<String> {
        val custom = normalize(phrase)
        val list = mutableListOf<String>()
        if (custom.isNotBlank()) {
            list += custom
            // «Слушай, Тайм» → «эй тайм» и «привет тайм» тоже удобно
            val parts = custom.split(" ").filter { it.isNotBlank() }
            if (parts.size == 2 && parts[0] == "слушай") {
                list += "эй ${parts[1]}"
                list += "окей ${parts[1]}"
                list += "${parts[1]} слушай"
            }
        }
        BUILT_IN.forEach { list += normalize(it) }
        return list.filter { it.isNotBlank() }.distinct()
    }

    /** Есть ли в тексте обращение. */
    fun contains(text: String, phrase: String = DEFAULT): Boolean = split(text, phrase).found

    /** Отделить обращение от команды: «слушай тайм, что на сегодня» → «что на сегодня». */
    fun split(text: String, phrase: String = DEFAULT): Match {
        val source = text ?: return Match(false, "", "")
        val spans = tokenSpans(source)
        if (spans.isEmpty()) return Match(false, source.trim(), "")

        val words = spans.map { normalize(it.text) }
        for (alias in aliases(phrase)) {
            val target = alias.split(" ").filter { it.isNotBlank() }
            if (target.isEmpty()) continue
            var start = 0
            while (start + target.size <= words.size) {
                if (matchesAt(words, start, target) || matchesAtLoose(words, start, target)) {
                    val remove = BooleanArray(source.length)
                    for (k in start until start + target.size) {
                        for (i in spans[k].start until spans[k].end) remove[i] = true
                    }
                    val sb = StringBuilder(source.length)
                    source.forEachIndexed { index, ch -> if (!remove[index]) sb.append(ch) }
                    // После вырезания фразы остаются запятые и пробелы — снимаем их
                    // по кругу, пока не останется чистый текст команды.
                    val command = sb.toString()
                        .replace(Regex("\\s+"), " ")
                        .replace(Regex("^[\\s,.;:!?—–-]+"), "")
                        .replace(Regex("[\\s,.;:!?—–-]+$"), "")
                        .trim()
                    return Match(true, command, alias)
                }
                start++
            }
        }
        return Match(false, source.trim(), "")
    }

    /**
     * Короткая команда-остановка: «стоп», «хватит», «пока», «замолчи».
     * Не срабатывает на «покажи задачи» и «стоп-кран в задаче».
     */
    fun isStop(text: String): Boolean {
        val n = normalize(text)
        if (n.isBlank()) return false
        return STOP_WORDS.any { word ->
            if (n == word) return true
            if (!n.startsWith("$word ")) return false
            // «замолчи, пожалуйста», «стоп уже» — ещё остановка;
            // «стоп-кран купить» — уже команда, а не остановка.
            val rest = n.removePrefix("$word ").split(" ").filter { it.isNotBlank() }
            rest.isNotEmpty() && rest.all { it in POLITE_WORDS }
        }
    }

    private val POLITE_WORDS = setOf("пожалуйста", "уже", "давай", "ну", "все", "всё")

    // ------------------------------------------------------------------ внутреннее

    private fun matchesAt(words: List<String>, start: Int, target: List<String>): Boolean =
        target.indices.all { i -> fuzzy(words[start + i], target[i]) }

    /**
     * Дополнительный, более мягкий вариант: разрешает одно «лишнее» слово внутри
     * фразы («слушай, э, тайм»). Проверяется только вторая половина фразы.
     */
    private fun matchesAtLoose(words: List<String>, start: Int, target: List<String>): Boolean {
        if (target.size < 2) return false
        if (start + target.size + 1 > words.size) return false
        if (!fuzzy(words[start], target.first())) return false
        val last = start + target.size
        if (!fuzzy(words[last], target.last())) return false
        return target.drop(1).dropLast(1).all { part ->
            (start + 1..last - 1).any { idx -> fuzzy(words[idx], part) }
        }
    }

    /** Одно слово с допуском на опечатку. */
    private fun fuzzy(actual: String, expected: String): Boolean {
        if (actual == expected) return true
        if (expected.length >= 4 && actual.length >= 4 && actual.startsWith(expected.take(4))) {
            // «тайм» / «таймер» / «тайма» — общий корень
            return levenshtein(actual, expected) <= 2
        }
        if (expected.length <= 3) return actual == expected
        return levenshtein(actual, expected) <= 1
    }

    private fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)
        for (i in 1..a.length) {
            current[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(
                    current[j - 1] + 1,
                    previous[j] + 1,
                    previous[j - 1] + cost,
                )
            }
            val swap = previous
            previous = current
            current = swap
        }
        return previous[b.length]
    }

    private data class Span(val start: Int, val end: Int, val text: String)

    private fun tokenSpans(text: String): List<Span> {
        val spans = mutableListOf<Span>()
        var index = 0
        while (index < text.length) {
            if (text[index].isLetterOrDigit()) {
                val start = index
                while (index < text.length && (text[index].isLetterOrDigit() || text[index] == '\'' || text[index] == '’')) {
                    index++
                }
                spans += Span(start, index, text.substring(start, index))
            } else {
                index++
            }
        }
        return spans
    }

    /** Нижний регистр, ё→е, только буквы/цифры, один пробел между словами. */
    fun normalize(text: String): String {
        val sb = StringBuilder(text.length)
        var space = true
        for (raw in text.lowercase()) {
            val ch = if (raw == 'ё' || raw == 'Ё') 'е' else raw
            if (ch.isLetterOrDigit()) {
                sb.append(ch)
                space = false
            } else if (!space) {
                sb.append(' ')
                space = true
            }
        }
        return sb.toString().trim()
    }
}
