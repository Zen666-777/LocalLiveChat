package jp.mel.chat.data

import androidx.room.withTransaction
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.yield
import java.text.Normalizer
import java.util.Locale
import kotlin.math.ln
import kotlin.math.sqrt

data class RecalledMessage(val message: MessageEntity, val excerpt: String, val score: Double)
data class RecallTrace(val plotId: String, val sources: List<RecalledMessage>, val noteIds: List<String>, val limited: Boolean)

/** Offline lexical recall. It deliberately does not claim semantic or perfect recall. */
class MemoryRecall(private val database: ChatDatabase) {
    private val dao = database.chatDao()

    suspend fun ensureIndexed(plotId: String) {
        while (true) {
            currentCoroutineContext().ensureActive()
            val batch = dao.unindexedMessages(plotId)
            if (batch.isEmpty()) return
            database.withTransaction {
                dao.indexMessages(batch.map { MessageSearchEntity(it.id, SearchText.index(it.content)) })
            }
            yield()
        }
    }

    suspend fun find(plotId: String, query: String, through: Long = Long.MAX_VALUE, limit: Int = 8): List<RecalledMessage> {
        if (through <= 0 || query.isBlank()) return emptyList()
        val allTerms = SearchText.terms(query)
        val terms = (allTerms.take(24) + allTerms.takeLast(8)).distinctBy { it.encoded }
        if (terms.isEmpty()) return emptyList()
        ensureIndexed(plotId)
        val total = dao.messageCount(plotId, through)
        if (total == 0) return emptyList()
        val frequencies = linkedMapOf<SearchText.Term, Int>()
        for (term in terms) {
            currentCoroutineContext().ensureActive()
            val count = dao.matchingCount(plotId, through, term.encoded)
            if (count > 0) frequencies[term] = count
        }
        // Separate rare-term searches prevent a hundred recent generic hits from
        // hiding an old, uniquely relevant event. SQL parameters never contain raw query syntax.
        val candidates = linkedMapOf<Long, MessageEntity>()
        for ((term, _) in frequencies.entries.sortedWith(compareBy<Map.Entry<SearchText.Term, Int>> { it.value }.thenByDescending { it.key.weight }).take(8)) {
            dao.searchMessages(plotId, through, term.encoded, 32).forEach { candidates[it.id] = it }
        }
        val weights = frequencies.mapValues { (term, count) -> term.weight * (1.0 + ln((total + 1.0) / (count + 1.0))) }
        return candidates.values.map { message ->
            val present = SearchText.terms(message.content).map { it.encoded }.toHashSet()
            val matched = weights.filterKeys { it.encoded in present }
            val score = matched.values.sum() / sqrt(1.0 + message.content.length / 800.0)
            RecalledMessage(message, SearchText.excerpt(message.content, matched.keys.sortedByDescending { weights[it] }.map { it.text }), score)
        }.filter { it.score > 0.0 }
            .sortedWith(compareByDescending<RecalledMessage> { it.score }.thenByDescending { it.message.id })
            .take(limit.coerceIn(1, 30))
    }
}

/** Character bigrams avoid a dependency on an online Japanese tokenizer. */
object SearchText {
    data class Term(val encoded: String, val text: String, val weight: Double)
    private val runs = Regex("[\\p{IsHan}\\p{IsHiragana}\\p{IsKatakana}ー]+|[a-z0-9][a-z0-9_-]*")
    private val stop = setOf("して", "した", "です", "ます", "こと", "する", "ある", "いる", "それ", "これ", "あれ", "から", "ので", "けど", "って", "った", "たい", "ない", "だっ", "っけ", "えて", "教え", "覚え", "前に", "何", "私", "今", "前", "後", "事", "言", "思", "覚", "教", "the", "and", "you", "that", "this", "what", "was", "is", "to")
    fun normalize(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
    private fun encode(text: String): String = buildString {
        append('t')
        val hex = "0123456789abcdef"
        text.toByteArray(Charsets.UTF_8).forEach { byte -> val n = byte.toInt() and 255; append(hex[n ushr 4]); append(hex[n and 15]) }
    }
    fun terms(text: String): List<Term> {
        val result = linkedMapOf<String, Term>()
        fun add(value: String, weight: Double) {
            if (value in stop || value.length > 80) return
            val encoded = encode(value)
            result[encoded] = Term(encoded, value, weight)
        }
        for (match in runs.findAll(normalize(text))) {
            val value = match.value
            if (value.first().code < 128) {
                if (value.length >= 2) add(value, 2.0)
            } else {
                val points = value.codePoints().toArray()
                for (i in points.indices) {
                    if (Character.UnicodeScript.of(points[i]) == Character.UnicodeScript.HAN) add(String(points, i, 1), 0.65)
                    if (i + 1 < points.size) add(String(points, i, 2), 1.0)
                }
            }
        }
        return result.values.toList()
    }
    fun index(text: String): String = terms(text).joinToString(" ") { it.encoded }
    fun related(note: MemoryNoteEntity, query: String): Double {
        val wanted = terms(query).map { it.encoded }.toSet()
        return terms(note.title + " " + note.keywords + " " + note.content).filter { it.encoded in wanted }.sumOf { it.weight }
    }
    fun excerpt(text: String, anchors: List<String>, maxChars: Int = 360): String {
        if (text.length <= maxChars) return text
        // NFKC can change length, so locate normalized anchors on the original only
        // when possible; fall back to the beginning rather than fabricate an offset.
        val position = anchors.firstNotNullOfOrNull { anchor -> text.indexOf(anchor, ignoreCase = true).takeIf { it >= 0 } } ?: 0
        var start = (position - 100).coerceIn(0, (text.length - maxChars).coerceAtLeast(0))
        var end = (start + maxChars).coerceAtMost(text.length)
        if (start > 0 && text[start].isLowSurrogate()) start--
        if (end < text.length && text[end - 1].isHighSurrogate()) end--
        return (if (start > 0) "…" else "") + text.substring(start, end) + if (end < text.length) "…" else ""
    }
}
