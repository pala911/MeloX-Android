package com.lladlam.melox.core.autosync

import java.text.Normalizer
import java.util.Locale
import kotlin.math.roundToLong

/**
 * Text side of the English AutoSync contract, mirroring MeloX-AutoSync
 * `src/autosync/lyrics/normalize.py::normalize_en` and
 * `src/autosync/models/wav2vec2_ctc.py::_align_words`.
 *
 * The tokenizer behaviour was verified against the real
 * `Wav2Vec2CTCTokenizer` for `facebook/wav2vec2-base-960h`:
 * space -> `|` (id 4), unknown character -> `<unk>` (id 3), blank = 0.
 */
internal object AutoSyncText {

    private val NON_KEEP = Regex("[^A-Za-z0-9' ]+")
    private val WHITESPACE = Regex("\\s+")
    private val WORD = Regex("[A-Za-z0-9']+")

    private val SPECIAL_TOKENS = setOf("<pad>", "<s>", "</s>", "<unk>", "|", " ")

    /** NFKC, straighten curly apostrophes, drop unsupported punctuation, upper-case. */
    fun normalizeEn(text: String): String {
        var value = Normalizer.normalize(text, Normalizer.Form.NFKC)
        value = value.replace('\u2019', '\'').replace('\u2018', '\'')
        value = NON_KEEP.replace(value, " ")
        value = WHITESPACE.replace(value, " ").trim()
        return value.uppercase(Locale.ROOT)
    }

    /**
     * Chinese/Japanese normalisation, mirroring
     * `src/autosync/lyrics/normalize.py::normalize_cjk`: NFKC, then keep only
     * CJK, kana and digits. Latin is dropped **by design** of the pack — which
     * is why [ZhText.isAlignableForChinese] refuses lines that lean on it.
     */
    fun normalizeZh(text: String): String {
        val value = Normalizer.normalize(text, Normalizer.Form.NFKC)
        return CJK_KEEP_DROP.replace(value, "")
    }

    private val CJK_KEEP_DROP = Regex(
        "[^\\u3400-\\u4dbf\\u4e00-\\u9fff\\uf900-\\ufaff" +
            "\\u3041-\\u309f\\u30a0-\\u30ff\\uff10-\\uff19\\d]+",
    )

    fun tokenIds(normalized: String, manifest: AutoSyncManifest): List<Int> {
        val lookup = HashMap<String, Int>(manifest.tokens.size * 2)
        manifest.tokens.forEachIndexed { index, token -> lookup.putIfAbsent(token, index) }
        val delimiter = manifest.wordDelimiterId.takeIf { it >= 0 }
            ?: lookup["|"]
            ?: 4
        return normalized.map { character ->
            if (character == ' ') delimiter else lookup[character.toString()] ?: manifest.unkId
        }
    }

    /**
     * Groups character spans into display words exactly like the reference
     * engine: a `|`/space closes the current word, the remaining special tokens
     * are skipped *without* closing it, and the original surface words are used
     * when their count matches (otherwise the normalised words are).
     *
     * @return word units in window-relative milliseconds.
     */
    fun groupWords(
        originalText: String,
        normalized: String,
        tokenIds: List<Int>,
        spans: List<CtcForcedAligner.Span?>,
        manifest: AutoSyncManifest,
        frameHopSeconds: Double,
    ): List<AutoSyncUnit> {
        val groups = ArrayList<MutableList<CtcForcedAligner.Span>>()
        var current = ArrayList<CtcForcedAligner.Span>()
        for (index in tokenIds.indices) {
            val token = manifest.tokens.getOrNull(tokenIds[index]) ?: continue
            if (token == "|" || token == " ") {
                if (current.isNotEmpty()) {
                    groups.add(current)
                    current = ArrayList()
                }
                continue
            }
            if (token in SPECIAL_TOKENS) continue
            val span = spans.getOrNull(index) ?: continue
            current.add(span)
        }
        if (current.isNotEmpty()) groups.add(current)
        if (groups.isEmpty()) return emptyList()

        val originalWords = WORD.findAll(originalText).map { it.value }.toList()
        val normalizedWords = normalized.split(' ').filter { it.isNotEmpty() }
        val display = if (originalWords.size == groups.size) originalWords else normalizedWords

        return groups.mapIndexed { index, group ->
            val startSeconds = group.first().startFrame * frameHopSeconds
            // `end_frame` is inclusive; the +1 keeps the reference engine's end.
            val endSeconds = (group.last().endFrame + 1) * frameHopSeconds
            AutoSyncUnit(
                text = display.getOrNull(index).orEmpty(),
                startMs = (startSeconds * 1000.0).roundToLong(),
                endMs = (endSeconds * 1000.0).roundToLong(),
            )
        }
    }

    /**
     * Picks an implemented engine. Kana-bearing Japanese lyrics stay line-timed
     * until the Japanese G2P path is implemented.
     */
    fun detectLanguage(lines: List<String>): String? {
        var latin = 0
        var cjk = 0
        var kana = 0
        for (line in lines) {
            for (character in line) {
                val code = character.code
                when {
                    character in 'A'..'Z' || character in 'a'..'z' -> latin++
                    code in 0x3040..0x30FF -> { cjk++; kana++ }
                    code in 0x4E00..0x9FFF -> cjk++
                }
            }
        }
        // A stray English chorus inside a Chinese song must not switch engines.
        if (latin >= 16 && latin >= cjk * 2) return "en"
        if (kana > 0) return null
        // Chinese packs align characters; Latin-heavy lines are refused per line
        // by ZhText.isAlignableForChinese rather than by the document language.
        if (cjk >= 8 && cjk > latin) return "zh"
        return null
    }

    /**
     * Lines that are credits or instrumental markers are never aligned: the
     * reference engine classifies them out, and force-aligning them would attach
     * confident-looking timing to text nobody sings.
     */
    fun isAlignable(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.length < 2) return false
        if (trimmed.all { !it.isLetterOrDigit() }) return false
        val lower = trimmed.lowercase(Locale.ROOT)
        if (CREDIT_MARKERS.any { lower.contains(it) }) return false
        if (INSTRUMENTAL_MARKERS.any { lower.contains(it) }) return false
        return true
    }

    private val CREDIT_MARKERS = listOf(
        "作词", "作詞", "作曲", "编曲", "編曲", "制作人", "監製", "监制", "混音", "母带", "母帶",
        "录音", "錄音", "吉他", "贝斯", "貝斯", "鼓 ", "弦乐", "和声", "和聲", "出品", "发行", "發行",
        "produced by", "written by", "composed by", "arranged by", "lyrics by", "music by",
        "mixed by", "mastered by", "recording by", "op:", "sp:", "op：", "sp：",
    )

    private val INSTRUMENTAL_MARKERS = listOf(
        "间奏", "間奏", "前奏", "尾奏", "纯音乐", "純音樂", "instrumental", "[music]", "（music）",
    )
}
