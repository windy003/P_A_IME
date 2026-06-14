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

    /** 计算差异项(仅文件夹与常用语;剪贴板不参与比较/同步)。 */
    fun computeDiffs(remote: SyncClient.RemoteData): List<Diff> {
        val out = ArrayList<Diff>()
        out += diffTable(Kind.FOLDER, ds.exportFolders(), remote.folders) { "文件夹:" + it.optString("name") }
        out += diffTable(Kind.PHRASE, ds.exportPhrases(), remote.phrases) { "常用语:" + it.optString("content") }
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

    private inline fun diffTable(
        kind: Kind, localArr: JSONArray, remoteArr: JSONArray, label: (JSONObject) -> String
    ): List<Diff> {
        val local = indexByUuid(localArr)
        val remote = indexByUuid(remoteArr)
        val out = ArrayList<Diff>()
        val allUuids = LinkedHashSet<String>().apply { addAll(local.keys); addAll(remote.keys) }
        for (uuid in allUuids) {
            val l = local[uuid]
            val r = remote[uuid]
            when {
                // 仅本地有 -> 归本地,待补到云端
                l != null && r == null ->
                    out.add(Diff(kind, uuid, label(l), "仅本地", l, Origin.LOCAL))
                // 仅云端有 -> 归云端,待写入本地
                r != null && l == null ->
                    out.add(Diff(kind, uuid, label(r), "仅云端", r, Origin.REMOTE))
                // 两端都有:内容若被编辑(updated_at 不同)按较新方传播
                l != null && r != null -> {
                    val lt = l.optLong("updated_at"); val rt = r.optLong("updated_at")
                    if (lt != rt) {
                        val winner = if (lt > rt) l else r
                        val origin = if (lt > rt) Origin.LOCAL else Origin.REMOTE
                        val who = if (lt > rt) "本地较新" else "云端较新"
                        out.add(Diff(kind, uuid, label(winner), who, winner, origin))
                    }
                }
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
