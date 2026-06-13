package com.windy.pyime

import java.io.File

/**
 * 拼音词库 + 候选算法,等价移植自 PC 版 pyime.py 的 Dict 类。
 * 分词、模糊音、简拼、选词调权(bump)、调权写回文件,逻辑与 Python 版保持一致。
 *
 * 构造参数 raw 是 yaml 文件的完整文本(rime 格式:yaml 头 + "..." 分界 + 制表符分隔的词条体)。
 */
class PinyinDict(raw: String) {

    /** 一个词条(词 + 权重)。weight 可变,选词调权时就地修改;table 与 abbr 共享同一实例,改一处两边同步。 */
    class WordWeight(val word: String, var weight: Int)

    /** 返回给 UI 的候选:词 + 消耗的音节数。 */
    data class Candidate(val word: String, val nseg: Int)

    private val table = HashMap<String, MutableList<WordWeight>>()   // "ni hao" -> 按权重降序
    private val syllables = HashSet<String>()                        // 所有合法音节
    private val fuzzy = HashMap<String, List<String>>()              // 音节 -> 等价音节(不含自身)
    private val abbr = HashMap<String, MutableList<WordWeight>>()    // 声母串 -> 候选(共享 table 的实例)
    private var maxsyl = 1
    private val initSubs = ArrayList<Pair<String, String>>()         // 声母替换对

    init {
        // ---- 解析词条体 ----
        val idx = raw.indexOf("\n...")
        val body = if (idx >= 0) raw.substring(idx + 4) else raw
        for (line in body.split("\n")) {
            val parts = line.split("\t")
            if (parts.size < 2 || parts[0].isEmpty() || parts[0].startsWith("#")) continue
            val word = parts[0]
            val py = parts[1].trim()
            if (py.isEmpty()) continue
            val weight = if (parts.size > 2) parts[2].trim().toIntOrNull() ?: 0 else 0
            table.getOrPut(py) { ArrayList() }.add(WordWeight(word, weight))
            for (s in py.split(" ")) syllables.add(s)
        }
        for (v in table.values) v.sortByDescending { it.weight }
        maxsyl = syllables.maxOfOrNull { it.length } ?: 1

        // ---- 模糊音:声母替换对 + 韵母替换对,构建每个音节的等价闭包 ----
        val finalSubs = ArrayList<Pair<String, String>>()
        for ((a, b) in FUZZY_PAIRS) {
            for ((x, y) in listOf(a to b, b to a)) {
                if (x in INITIALS_1 || x in INITIALS_2) initSubs.add(x to y)
                else finalSubs.add(x to y)
            }
        }
        for (s in syllables) {
            val group = HashSet<String>().apply { add(s) }
            val todo = ArrayDeque<String>().apply { add(s) }
            while (todo.isNotEmpty()) {
                val cur = todo.removeLast()
                val vs = ArrayList<String>()
                for ((x, y) in initSubs) if (initialOf(cur) == x) vs.add(y + cur.substring(x.length))
                for ((x, y) in finalSubs) if (cur.endsWith(x)) vs.add(cur.substring(0, cur.length - x.length) + y)
                for (v in vs) if (v in syllables && v !in group) { group.add(v); todo.add(v) }
            }
            if (group.size > 1) fuzzy[s] = (group - s).sorted()
        }

        // ---- 简拼索引:每音节取首字母,按权重降序、截断 ----
        if (ENABLE_SHOUPIN) {
            for ((py, words) in table) {
                val sylls = py.split(" ")
                if (sylls.size < 2) continue   // 单字简拼太歧义,不收
                val key = sylls.joinToString("") { it[0].toString() }
                abbr.getOrPut(key) { ArrayList() }.addAll(words)   // 共享 WordWeight 实例
            }
            for (v in abbr.values) {
                v.sortByDescending { it.weight }
                if (v.size > MAX_SHOUPIN_PER_KEY) v.subList(MAX_SHOUPIN_PER_KEY, v.size).clear()
            }
        }
    }

    /** 取音节(或不完整音节)的声母,无声母返回空串。 */
    private fun initialOf(s: String): String {
        if (s.length >= 2 && s.substring(0, 2) in INITIALS_2) return s.substring(0, 2)
        return if (s.isNotEmpty() && s[0].toString() in INITIALS_1) s[0].toString() else ""
    }

    /** 把字母串贪心切分成音节列表;切不动时退化为单字母段。 */
    fun segment(buf: String): List<String> {
        val segs = ArrayList<String>()
        var i = 0
        val n = buf.length
        while (i < n) {
            if (buf[i] == '\'') { i++; continue }
            var matched = false
            val maxL = minOf(maxsyl, n - i)
            for (L in maxL downTo 1) {
                val s = buf.substring(i, i + L)
                if (s in syllables) { segs.add(s); i += L; matched = true; break }
            }
            if (!matched) { segs.add(buf[i].toString()); i++ }
        }
        return segs
    }

    /** 完整音节列表 sub 的所有模糊音组合键,第一个固定是原拼音。 */
    fun fuzzyKeys(sub: List<String>): List<String> {
        var combos = listOf("")
        for (s in sub) {
            val vs = listOf(s) + (fuzzy[s] ?: emptyList())
            val next = ArrayList<String>(combos.size * vs.size)
            for (c in combos) for (v in vs) next.add(if (c.isEmpty()) v else "$c $v")
            combos = if (next.size > MAX_FUZZY_KEYS) next.subList(0, MAX_FUZZY_KEYS) else next
        }
        return combos
    }

    /**
     * 返回 (候选列表, 分段);整串匹配时精确与模糊拼音的候选合并,统一按权重排序
     * (同权重精确在前),再逐级缩短;只匹配完整音节,不做前缀补全。最后按消耗音节数降序稳定排序。
     */
    fun candidates(buf: String): Pair<List<Candidate>, List<String>> {
        val segs = segment(buf)
        if (segs.isEmpty()) return Pair(emptyList(), emptyList())
        val out = ArrayList<Candidate>()
        val seen = HashSet<String>()
        fun add(word: String, nseg: Int) {
            if (word !in seen && out.size < MAX_CANDS) { seen.add(word); out.add(Candidate(word, nseg)) }
        }

        for (n in segs.size downTo 1) {
            val sub = segs.subList(0, n)
            if (sub.all { it in syllables }) {
                // 精确 + 模糊全部候选,统一按权重降序、同权重精确(键序号小)在前
                val pool = ArrayList<Triple<String, Int, Int>>()
                for ((ki, k) in fuzzyKeys(sub).withIndex()) {
                    table[k]?.forEach { pool.add(Triple(it.word, it.weight, ki)) }
                }
                pool.sortWith(compareByDescending<Triple<String, Int, Int>> { it.second }.thenBy { it.third })
                for (t in pool) add(t.first, n)
            }
        }

        // 简拼:整串全是允许的声母字母时,按声母串补充候选(消耗整个缓冲区)
        val letters = buf.replace("'", "")
        if (abbr.isNotEmpty() && letters.length >= 2 && letters.all { it in ABBR_LETTERS }) {
            abbr[letters]?.forEach { add(it.word, segs.size) }
        }
        // 消耗输入更多的候选排前(如 nbn:吃满 3 字母的"能不能"应在只占 1 个的"嗯"前),稳定排序
        val sorted = out.sortedByDescending { it.nseg }
        return Pair(sorted, segs)
    }

    /**
     * 选词调权:把选中词的权重提为同拼音候选池里的最大权重 + 1(内存立即生效)。
     * 返回需写回文件的 (拼音键, 新权重);已是唯一最高或找不到时返回 null。
     */
    fun bump(word: String, sub: List<String>): Pair<String, Int>? {
        val sylls = sub.filter { it != "'" }
        if (sylls.isEmpty()) return null
        var target: String? = null
        var pool: List<WordWeight>? = null

        if (sylls.all { it in syllables }) {
            val keys = fuzzyKeys(sylls)   // 第一个是原拼音,优先归到精确键
            for (k in keys) {
                if (table[k]?.any { it.word == word } == true) { target = k; break }
            }
            if (target != null) {
                val p = ArrayList<WordWeight>()
                for (k in keys) table[k]?.let { p.addAll(it) }
                pool = p
            }
        }
        if (target == null) {   // 简拼选词:反查该词真正的拼音键
            val letters = sylls.joinToString("")
            val ab = abbr[letters]
            if (ab != null && ab.any { it.word == word }) {
                pool = ab
                for ((k, v) in table) {
                    val ss = k.split(" ")
                    if (ss.size == letters.length &&
                        ss.indices.all { ss[it][0] == letters[it] } &&
                        v.any { it.word == word }
                    ) { target = k; break }
                }
            }
        }
        val t = target ?: return null
        val p = pool ?: return null
        val mx = p.maxOf { it.weight }
        val cur = table[t]!!.first { it.word == word }.weight
        if (cur == mx && p.count { it.weight == mx } == 1) return null   // 已是唯一最高
        val new = mx + 1
        setWeight(t, word, new)
        return t to new
    }

    private fun setWeight(key: String, word: String, weight: Int) {
        val lst = table[key] ?: return
        lst.firstOrNull { it.word == word }?.weight = weight   // 共享实例,abbr 同步更新
        lst.sortByDescending { it.weight }
        val sylls = key.split(" ")
        if (sylls.size >= 2) abbr[sylls.joinToString("") { it[0].toString() }]?.sortByDescending { it.weight }
    }

    companion object {
        // ---- 配置(与 pyime.py 顶部一致) ----
        const val MAX_CANDS = 60
        const val MAX_PINYIN = 30
        const val MAX_FUZZY_KEYS = 24
        const val ENABLE_SHOUPIN = true
        const val MAX_SHOUPIN_PER_KEY = 80

        // 模糊音:每对双向等价;清空则关闭
        val FUZZY_PAIRS = listOf(
            "z" to "zh", "c" to "ch", "s" to "sh",   // 平翘舌
            "en" to "eng", "in" to "ing",
            "l" to "r", "on" to "ong"
        )

        val INITIALS_2 = setOf("zh", "ch", "sh")
        // 单字符声母,存成单字符字符串集合(与 Python frozenset 行为一致,便于和 String 比较)
        val INITIALS_1 = "bpmfdtnlgkhjqxrzcsyw".map { it.toString() }.toHashSet()
        // 简拼允许的字母(Char 集合):声母 + 零声母音节首字母 a/e/o(否则 安卓=az、欧盟=om 查不到)
        val ABBR_LETTERS = ("bpmfdtnlgkhjqxrzcsyw" + "aeo").toHashSet()

        /** 从文件加载词库。 */
        fun load(file: File): PinyinDict = PinyinDict(file.readText(Charsets.UTF_8))

        /**
         * 把词库文件里 word+key 那行的权重列改为 weight,覆盖写回(单文件、小改动,直接覆盖即可)。
         * 找不到对应行返回 false。线程安全由调用方(单线程写队列)保证。
         */
        fun updateDictFile(file: File, word: String, key: String, weight: Int): Boolean {
            val lines = file.readText(Charsets.UTF_8).split("\n").toMutableList()
            var body = false
            var done = false
            for (i in lines.indices) {
                val line = lines[i]
                if (!body) { if (line.trim() == "...") body = true; continue }
                val parts = line.split("\t").toMutableList()
                if (parts.size >= 2 && parts[0] == word && parts[1].trim() == key) {
                    if (parts.size >= 3) parts[2] = weight.toString() else parts.add(weight.toString())
                    lines[i] = parts.joinToString("\t")
                    done = true
                    break
                }
            }
            if (!done) return false
            file.writeText(lines.joinToString("\n"), Charsets.UTF_8)
            return true
        }
    }
}
