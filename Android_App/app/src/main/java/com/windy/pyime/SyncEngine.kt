package com.windy.pyime

import org.json.JSONArray
import org.json.JSONObject

/**
 * 增量双向同步的核心逻辑(不含网络/UI)。
 *
 * 比较规则:按 uuid 匹配本地与云端(删除一律为硬删除,行不存在即「该端没有」)。
 *  - 仅一端有  -> 差异项,归有数据的那一端(补齐到另一端)
 *  - 两端都有  -> 视为一致;内容若被编辑(updated_at 不同)按较新方传播
 *  - 两端都没有 -> 无差异
 *
 * 删除的传播由比较面板里长按「删除该端数据」显式完成(本地直接删行、云端发删除请求),
 * 而非靠时间戳自动覆盖。
 *
 * 执行同步时,对每个(未被用户排除的)差异项:
 *  - winner 来自云端 -> 写入本地
 *  - winner 来自本地 -> 加入待上传列表
 * 被排除的差异项两端都不动(不参与本次同步)。剪贴板不参与比较/同步。
 */
class SyncEngine(private val ds: DataStore) {

    enum class Kind { CLIP, FOLDER, PHRASE }
    enum class Origin { LOCAL, REMOTE }

    /** 一条待确认的差异。 */
    class Diff(
        val kind: Kind,
        val uuid: String,
        val label: String,    // 列表主文本
        val detail: String,   // 来源说明:仅本地/仅云端/本地较新/云端较新(+已删)
        val winner: JSONObject,
        val origin: Origin,
    )

    /** 计算差异项(仅文件夹与常用语;剪贴板不参与比较/同步)。
     *  文件夹按「名字」比较,常用语按「所在文件夹名 + 文本内容」比较(均不看 uuid)。 */
    fun computeDiffs(remote: SyncClient.RemoteData): List<Diff> {
        val localFolders = ds.exportFolders()
        val out = ArrayList<Diff>()
        out += diffFoldersByName(localFolders, remote.folders)
        out += diffPhrasesByPath(
            ds.exportPhrases(), remote.phrases,
            uuidToName(localFolders), uuidToName(remote.folders)
        )
        return out
    }

    /** folder_uuid -> 文件夹名,合并本地与云端两份文件夹表(供树状面板显示文件夹名)。 */
    fun folderNames(remote: SyncClient.RemoteData): Map<String, String> {
        val m = HashMap<String, String>()
        fun add(arr: JSONArray) {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val u = o.optString("uuid")
                if (u.isNotEmpty()) m[u] = o.optString("name")
            }
        }
        add(ds.exportFolders())
        add(remote.folders)
        return m
    }

    /** 文件夹数组 -> (uuid -> 名称)。 */
    private fun uuidToName(arr: JSONArray): HashMap<String, String> {
        val m = HashMap<String, String>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val u = o.optString("uuid")
            if (u.isNotEmpty()) m[u] = o.optString("name")
        }
        return m
    }

    /** 文件夹数组 -> (名称 -> uuid);同名取第一个。 */
    private fun nameToUuid(arr: JSONArray): HashMap<String, String> {
        val m = HashMap<String, String>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val n = o.optString("name"); val u = o.optString("uuid")
            if (n.isNotEmpty() && u.isNotEmpty()) m.putIfAbsent(n, u)
        }
        return m
    }

    /** 文件夹按「名字」比较(而非 uuid):同名即视为同一个文件夹;仅一端有 -> 补到另一端。 */
    private fun diffFoldersByName(localArr: JSONArray, remoteArr: JSONArray): List<Diff> {
        fun index(arr: JSONArray): LinkedHashMap<String, JSONObject> {
            val m = LinkedHashMap<String, JSONObject>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val n = o.optString("name")
                if (n.isNotEmpty()) m.putIfAbsent(n, o)
            }
            return m
        }
        val local = index(localArr); val remote = index(remoteArr)
        val out = ArrayList<Diff>()
        val allNames = LinkedHashSet<String>().apply { addAll(local.keys); addAll(remote.keys) }
        for (n in allNames) {
            val l = local[n]; val r = remote[n]
            when {
                l != null && r == null ->
                    out.add(Diff(Kind.FOLDER, l.optString("uuid"), "文件夹:$n", "仅本地", l, Origin.LOCAL))
                r != null && l == null ->
                    out.add(Diff(Kind.FOLDER, r.optString("uuid"), "文件夹:$n", "仅云端", r, Origin.REMOTE))
                // 同名 -> 视为同一文件夹,无差异
            }
        }
        return out
    }

    /**
     * 常用语按「路径(所在文件夹的名字)+ 文本内容」比较,而非各自的 uuid:
     * 两端在同名文件夹下有相同内容即视为同一条(不再因 uuid 不同而重复同步);
     * 仅一端有 -> 补到另一端。注意路径用文件夹「名字」而非 folder_uuid,
     * 这样即使两端同名文件夹的 uuid 不同,也能正确匹配。
     * 注意:因内容是身份的一部分,「编辑某条文字」会被视为「旧条目消失、新条目出现」,
     * 而非内容更新(旧条目需在比较面板里手动删除)。
     */
    private fun diffPhrasesByPath(
        localArr: JSONArray, remoteArr: JSONArray,
        localNames: Map<String, String>, remoteNames: Map<String, String>
    ): List<Diff> {
        fun keyOf(o: JSONObject, names: Map<String, String>): String {
            val fu = if (o.isNull("folder_uuid")) "" else o.optString("folder_uuid")
            val folderName = names[fu] ?: ""          // 把 folder_uuid 翻译成文件夹名
            return folderName + "	" + o.optString("content")   // 文件夹名(路径) + 内容
        }
        fun index(arr: JSONArray, names: Map<String, String>): LinkedHashMap<String, JSONObject> {
            val m = LinkedHashMap<String, JSONObject>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                m.putIfAbsent(keyOf(o, names), o)   // 同一端已有重复(同路径同内容)只保留第一条
            }
            return m
        }
        val local = index(localArr, localNames)
        val remote = index(remoteArr, remoteNames)
        val out = ArrayList<Diff>()
        val allKeys = LinkedHashSet<String>().apply { addAll(local.keys); addAll(remote.keys) }
        for (k in allKeys) {
            val l = local[k]; val r = remote[k]
            when {
                l != null && r == null ->
                    out.add(Diff(Kind.PHRASE, l.optString("uuid"), "常用语:" + l.optString("content"), "仅本地", l, Origin.LOCAL))
                r != null && l == null ->
                    out.add(Diff(Kind.PHRASE, r.optString("uuid"), "常用语:" + r.optString("content"), "仅云端", r, Origin.REMOTE))
                // 两端同路径同内容 -> 一致,无差异
            }
        }
        return out
    }

    private fun indexByUuid(arr: JSONArray): Map<String, JSONObject> {
        val m = HashMap<String, JSONObject>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            m[o.optString("uuid")] = o
        }
        return m
    }

    private fun clipLabel(o: JSONObject): String {
        val t = o.optString("content").replace("\n", " ")
        return if (t.length > 40) t.substring(0, 40) + "…" else t
    }

    /** 应用结果:需要上传到云端的数据(仅文件夹/常用语)。 */
    class Result(
        val pushFolder: JSONArray,
        val pushPhrase: JSONArray,
    )

    /**
     * 执行同步:云端 winner 写本地,本地 winner 收集待上传(仅文件夹/常用语,剪贴板不参与)。
     * 文件夹先于常用语写入(保证归属文件夹已存在)。
     */
    fun apply(diffs: List<Diff>): Result {
        val pushFolder = JSONArray()
        val pushPhrase = JSONArray()

        // 先写本地的文件夹,再写常用语
        val ordered = diffs.sortedBy { if (it.kind == Kind.FOLDER) 0 else 1 }
        for (d in ordered) {
            if (d.origin == Origin.REMOTE) {
                when (d.kind) {
                    Kind.FOLDER -> ds.applyFolderRow(d.winner)
                    Kind.PHRASE -> ds.applyPhraseRow(d.winner)
                    Kind.CLIP -> {}   // 剪贴板不同步
                }
            } else {
                when (d.kind) {
                    Kind.FOLDER -> pushFolder.put(d.winner)
                    Kind.PHRASE -> pushPhrase.put(d.winner)
                    Kind.CLIP -> {}   // 剪贴板不同步
                }
            }
        }

        return Result(pushFolder, pushPhrase)
    }
}
