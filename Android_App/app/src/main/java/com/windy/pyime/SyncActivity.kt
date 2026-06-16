package com.windy.pyime

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.os.Bundle
import android.os.Environment
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File

/**
 * 同步页(账号体系版):输入法窗口里无法登录/比较,所以放在独立 Activity。
 *
 * 流程:
 *  1. 未登录(无 Worker 地址或 token)-> 登录表单(用户名+密码),底部有「注册新账号(管理员)」入口。
 *  2. 已登录 -> 自动 pull 该账号云端数据并与本地比较,列出差异项(可长按排除某项)。
 *  3. 点「增量双向同步」-> 应用未排除的差异(云端→本地写入、本地→收集上传),裁剪剪贴板,push 上传。
 *
 * 切换到不同账号登录时,先清空本机同步数据(避免把上个账号的数据推到新账号)。
 */
class SyncActivity : Activity() {

    private lateinit var ds: DataStore
    private val ui = android.os.Handler(android.os.Looper.getMainLooper())

    private val diffs = ArrayList<SyncEngine.Diff>()   // 当前比较出的差异(树状面板的数据源)
    private val folderNames = HashMap<String, String>() // folder_uuid -> 文件夹名(两端合并)
    private val collapsed = HashSet<String>()           // 已折叠的树节点 key(不在集合里即展开)
    private var rootContainer: LinearLayout? = null

    // 词库同步:每个来源(GitHub / 我的服务器)各自暂存比较后下载到的新内容,供「下载并替换」使用
    private class DictSource { var pendingBytes: ByteArray? = null }
    private val githubSource = DictSource()
    private val serverSource = DictSource()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "云同步"
        ds = DataStore(this)

        rootContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }
        setContentView(ScrollView(this).apply { addView(rootContainer) })

        if (session() == null) showLogin() else startCompare()
    }

    // ---------------------------------------------------------------- 会话
    private fun prefs() = getSharedPreferences(PinyinImeService.PREFS, MODE_PRIVATE)

    /** 返回 (url, token);token 为空视为未登录。url 固定为预置的 Worker 地址。 */
    private fun session(): Pair<String, String>? {
        val url = DEFAULT_SYNC_URL.trim()
        val token = prefs().getString(KEY_SYNC_TOKEN, "")?.trim().orEmpty()
        return if (url.isNotEmpty() && token.isNotEmpty()) url to token else null
    }

    // ---------------------------------------------------------------- 登录
    private fun showLogin() {
        val box = rootContainer ?: return
        box.removeAllViews()
        box.addView(titleText("登录同步"))
        box.addView(bodyText("用管理员分配给你的账号登录。地址为你部署的 Worker 网址。"))

        val userEdit = EditText(this).apply {
            hint = "用户名"
            inputType = InputType.TYPE_CLASS_TEXT
            setText(prefs().getString(KEY_SYNC_USER, ""))
            layoutParams = mw()
        }
        val pwdEdit = EditText(this).apply {
            hint = "密码"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            layoutParams = mw()
        }
        box.addView(userEdit); box.addView(pwdEdit)

        box.addView(Button(this).apply {
            text = "登录"
            layoutParams = mw()
            setOnClickListener {
                val url = DEFAULT_SYNC_URL.trim()
                val user = userEdit.text.toString().trim()
                val pwd = pwdEdit.text.toString()
                if (user.isEmpty() || pwd.isEmpty()) {
                    toast("请填写用户名和密码"); return@setOnClickListener
                }
                isEnabled = false; text = "登录中…"
                Thread {
                    val result = try {
                        val token = SyncClient(url).login(user, pwd)
                        Result.success(token)
                    } catch (e: Exception) {
                        Result.failure(e)
                    }
                    ui.post {
                        result.onSuccess { token ->
                            onLoginSuccess(user, token)
                        }.onFailure { e ->
                            isEnabled = true; text = "登录"
                            toast("登录失败:${e.message}")
                        }
                    }
                }.start()
            }
        })

        box.addView(spacerV(dp(16)))
        box.addView(Button(this).apply {
            text = "注册新账号(管理员)"
            layoutParams = mw()
            setOnClickListener { showRegister() }
        })

        // 词库同步不依赖账号登录,登录页也提供入口
        addDictSyncCard(box)
    }

    /** 登录成功:换了不同账号则先确认清空本机数据,再保存会话并进入比较。 */
    private fun onLoginSuccess(user: String, token: String) {
        val prevUser = prefs().getString(KEY_SYNC_USER, "").orEmpty()
        fun save() {
            prefs().edit()
                .putString(KEY_SYNC_USER, user)
                .putString(KEY_SYNC_TOKEN, token)
                .apply()
            startCompare()
        }
        if (prevUser.isNotEmpty() && prevUser != user) {
            AlertDialog.Builder(this)
                .setTitle("切换账号")
                .setMessage("检测到换了账号($prevUser → $user)。\n继续将清空本机的剪贴板/常用语,改用「$user」的云端数据。是否继续?")
                .setNegativeButton("取消", null)
                .setPositiveButton("继续") { _, _ ->
                    ds.clearSyncTables(); toast("已清空本机数据"); save()
                }
                .show()
        } else {
            save()
        }
    }

    private fun showRegister() {
        val box = rootContainer ?: return
        box.removeAllViews()
        box.addView(titleText("注册新账号"))
        box.addView(bodyText("仅管理员可注册:需填写部署 Worker 时设置的管理员密钥(ADMIN_KEY)。"))

        val adminEdit = EditText(this).apply {
            hint = "管理员密钥(ADMIN_KEY)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            layoutParams = mw()
        }
        val userEdit = EditText(this).apply {
            hint = "新账号用户名"
            inputType = InputType.TYPE_CLASS_TEXT
            layoutParams = mw()
        }
        val pwdEdit = EditText(this).apply {
            hint = "新账号密码"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            layoutParams = mw()
        }
        box.addView(adminEdit); box.addView(userEdit); box.addView(pwdEdit)

        box.addView(Button(this).apply {
            text = "注册"
            layoutParams = mw()
            setOnClickListener {
                val url = DEFAULT_SYNC_URL.trim()
                val admin = adminEdit.text.toString().trim()
                val user = userEdit.text.toString().trim()
                val pwd = pwdEdit.text.toString()
                if (admin.isEmpty() || user.isEmpty() || pwd.isEmpty()) {
                    toast("请填写全部字段"); return@setOnClickListener
                }
                isEnabled = false; text = "注册中…"
                Thread {
                    val err = try { SyncClient(url).register(admin, user, pwd) }
                    catch (e: Exception) { e.message ?: "注册失败" }
                    ui.post {
                        isEnabled = true; text = "注册"
                        if (err == null) {
                            // 注册成功:回登录页,预填好地址和用户名
                            prefs().edit().putString(KEY_SYNC_USER, user).apply()
                            toast("注册成功,请用新账号登录")
                            showLogin()
                        } else {
                            toast("注册失败:$err")
                        }
                    }
                }.start()
            }
        })
        box.addView(spacerV(dp(8)))
        box.addView(Button(this).apply {
            text = "返回登录"; layoutParams = mw(); setOnClickListener { showLogin() }
        })
    }

    private fun logout() {
        prefs().edit().remove(KEY_SYNC_TOKEN).apply()   // 保留用户名方便下次登录
        diffs.clear()
        showLogin()
    }

    // ---------------------------------------------------------------- 比较
    private fun startCompare() {
        val (url, token) = session() ?: run { showLogin(); return }
        val box = rootContainer ?: return
        box.removeAllViews()
        box.addView(titleText("正在比较两端数据…"))

        Thread {
            try {
                val remote = SyncClient(url, token).pull()
                val engine = SyncEngine(ds)
                val list = engine.computeDiffs(remote)
                val names = engine.folderNames(remote)
                ui.post {
                    diffs.clear(); diffs.addAll(list)
                    folderNames.clear(); folderNames.putAll(names)
                    collapsed.clear()
                    renderCompare()
                }
            } catch (e: Exception) {
                ui.post {
                    box.removeAllViews()
                    box.addView(titleText("比较失败"))
                    box.addView(bodyText(e.message ?: "网络或服务器错误"))
                    box.addView(Button(this).apply {
                        text = "重试"; layoutParams = mw(); setOnClickListener { startCompare() }
                    })
                    box.addView(Button(this).apply {
                        text = "重新登录"; layoutParams = mw(); setOnClickListener { logout() }
                    })
                }
            }
        }.start()
    }

    private fun renderCompare() {
        val box = rootContainer ?: return
        box.removeAllViews()

        val user = prefs().getString(KEY_SYNC_USER, "").orEmpty()
        box.addView(titleText("账号「$user」· 两端差异(${diffs.size})"))
        box.addView(bodyText(
            "树状展示两端各自独有的部分:端 → 文件夹 → 条目。\n" +
            "点节点折叠/展开;长按文件夹或条目可「删除该端数据」(本地端删本机,云端端删云端)。\n" +
            "点「增量双向同步」会把剩下的差异补齐到另一端。"
        ))

        if (diffs.isEmpty()) {
            box.addView(bodyText("两端已一致,无需同步。"))
        } else {
            box.addView(endNode(SyncEngine.Origin.LOCAL, "📱 本地"))
            box.addView(endNode(SyncEngine.Origin.REMOTE, "☁ 云端"))
        }

        box.addView(spacerV(dp(12)))
        box.addView(Button(this).apply {
            text = "增量双向同步"
            layoutParams = mw()
            isEnabled = diffs.isNotEmpty()
            setOnClickListener { runSync() }
        })
        box.addView(Button(this).apply {
            text = "重新比较"; layoutParams = mw(); setOnClickListener { startCompare() }
        })
        box.addView(Button(this).apply {
            text = "退出登录"; layoutParams = mw(); setOnClickListener { logout() }
        })

        // 词库文件同步(独立于账号数据同步)
        addDictSyncCard(box)
    }

    // ---------------------------------------------------------------- 树:端(第 0 级)
    private fun endNode(origin: SyncEngine.Origin, label: String): View {
        val box = vbox()
        val mine = diffs.filter { it.origin == origin }
        val key = "end:$origin"
        box.addView(treeRow(0, key, "$label(${mine.size})", expandable = mine.isNotEmpty()))
        if (mine.isNotEmpty() && key !in collapsed) {
            // 按文件夹归组:FOLDER 差异用自身 uuid,PHRASE 差异用 folder_uuid(null 归到「未分类」)
            val folderDiffs = LinkedHashMap<String, SyncEngine.Diff>()
            val phraseGroups = LinkedHashMap<String?, MutableList<SyncEngine.Diff>>()
            for (d in mine) when (d.kind) {
                SyncEngine.Kind.FOLDER -> folderDiffs[d.uuid] = d
                SyncEngine.Kind.PHRASE -> {
                    val fk = if (d.winner.isNull("folder_uuid")) null else d.winner.optString("folder_uuid")
                    phraseGroups.getOrPut(fk) { ArrayList() }.add(d)
                }
                SyncEngine.Kind.CLIP -> {}
            }
            val keys = LinkedHashSet<String?>().apply { addAll(folderDiffs.keys); addAll(phraseGroups.keys) }
            for (fk in keys) {
                box.addView(folderNode(origin, fk, folderDiffs[fk], phraseGroups[fk] ?: emptyList()))
            }
        }
        return box
    }

    // ---------------------------------------------------------------- 树:文件夹(第 1 级)
    private fun folderNode(
        origin: SyncEngine.Origin,
        folderUuid: String?,
        folderDiff: SyncEngine.Diff?,
        phrases: List<SyncEngine.Diff>,
    ): View {
        val box = vbox()
        val name = when {
            folderUuid == null -> "未分类"
            else -> folderNames[folderUuid] ?: "(未知文件夹)"
        }
        val key = "folder:$origin:$folderUuid"
        val tag = if (folderDiff != null) " · 新文件夹" else ""
        val expandable = phrases.isNotEmpty()
        // 长按文件夹:删掉该文件夹差异 + 其下所有条目差异
        val toDelete = (listOfNotNull(folderDiff) + phrases)
        box.addView(treeRow(1, key, "📁 $name(${phrases.size})$tag", expandable) {
            confirmDelete(origin, toDelete, "文件夹「$name」及其 ${phrases.size} 个条目")
        })
        if (expandable && key !in collapsed) {
            for (p in phrases) box.addView(entryNode(origin, p))
        }
        return box
    }

    // ---------------------------------------------------------------- 树:条目(第 2 级)
    private fun entryNode(origin: SyncEngine.Origin, d: SyncEngine.Diff): View {
        val raw = d.winner.optString("content").replace("\n", " ")
        val text = if (raw.length > 40) raw.take(40) + "…" else raw
        return treeRow(2, "entry:${d.uuid}", "📄 $text", expandable = false) {
            confirmDelete(origin, listOf(d), "条目「$text」")
        }
    }

    /** 长按删除前的二次确认;确认后真正删除该端数据。 */
    private fun confirmDelete(origin: SyncEngine.Origin, items: List<SyncEngine.Diff>, what: String) {
        if (items.isEmpty()) return
        val where = if (origin == SyncEngine.Origin.LOCAL) "本机" else "云端"
        AlertDialog.Builder(this)
            .setTitle("删除$where 数据")
            .setMessage("将从$where 删除 $what。此操作会写入${if (origin == SyncEngine.Origin.LOCAL) "本地数据库" else "云端"},不可撤销。确定?")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ -> deleteFromEnd(origin, items) }
            .show()
    }

    /** 真正删除:本地端直接删行;云端端发删除请求(按 uuid 硬删除)。完成后从树移除。 */
    private fun deleteFromEnd(origin: SyncEngine.Origin, items: List<SyncEngine.Diff>) {
        toast("删除中…")
        Thread {
            try {
                if (origin == SyncEngine.Origin.LOCAL) {
                    for (d in items) when (d.kind) {
                        SyncEngine.Kind.FOLDER -> ds.deleteFolder(d.uuid)
                        SyncEngine.Kind.PHRASE -> ds.deletePhrase(d.uuid)
                        SyncEngine.Kind.CLIP -> {}
                    }
                } else {
                    val (url, token) = session() ?: throw RuntimeException("未登录")
                    val delFolders = org.json.JSONArray()
                    val delPhrases = org.json.JSONArray()
                    for (d in items) when (d.kind) {
                        SyncEngine.Kind.FOLDER -> delFolders.put(d.uuid)
                        SyncEngine.Kind.PHRASE -> delPhrases.put(d.uuid)
                        SyncEngine.Kind.CLIP -> {}
                    }
                    SyncClient(url, token).pushDelete(delFolders, delPhrases)
                }
                ui.post {
                    diffs.removeAll(items.toSet())
                    toast("已删除")
                    renderCompare()
                }
            } catch (e: Exception) {
                ui.post { toast("删除失败:${e.message}") }
            }
        }.start()
    }

    /** 一个可折叠的树节点行:缩进 + 三角 + 文本;可点折叠,可长按删除。 */
    private fun treeRow(
        level: Int,
        key: String,
        label: String,
        expandable: Boolean,
        onLongDelete: (() -> Unit)? = null,
    ): View {
        val expanded = key !in collapsed
        val arrow = if (!expandable) "　" else if (expanded) "▾ " else "▸ "
        return TextView(this).apply {
            text = arrow + label
            textSize = if (level == 0) 17f else 15f
            setTextColor(if (level == 0) Color.parseColor("#1A1A1A") else Color.parseColor("#404040"))
            setPadding(dp(8 + level * 20), dp(11), dp(12), dp(11))
            maxLines = 2
            layoutParams = (mw() as LinearLayout.LayoutParams).apply { topMargin = dp(3) }
            setBackgroundColor(
                when (level) {
                    0 -> Color.parseColor("#DCE7FF")
                    1 -> Color.parseColor("#EEF1F6")
                    else -> Color.parseColor("#F7F8FA")
                }
            )
            if (expandable) setOnClickListener {
                if (key in collapsed) collapsed.remove(key) else collapsed.add(key)
                renderCompare()
            }
            if (onLongDelete != null) setOnLongClickListener {
                onLongDelete()   // 直接进确认对话框(带「删除/取消」两个按钮)
                true
            }
        }
    }

    private fun vbox() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    // ---------------------------------------------------------------- 执行同步
    private fun runSync() {
        val (url, token) = session() ?: run { showLogin(); return }
        val snapshot = diffs.toList()
        toast("同步中…")
        Thread {
            try {
                val result = SyncEngine(ds).apply(snapshot)
                SyncClient(url, token).push(result.pushFolder, result.pushPhrase)
                ui.post {
                    toast("同步完成:上传 ${
                        result.pushFolder.length() + result.pushPhrase.length()
                    } 项")
                    startCompare()   // 重新比较,正常应显示已一致
                }
            } catch (e: Exception) {
                ui.post { toast("同步失败:${e.message}") }
            }
        }.start()
    }

    // ---------------------------------------------------------------- 词库文件同步
    /** 本地词库文件路径,必须与 PinyinImeService.dictFile 一致(IME 从这里读取)。 */
    private fun dictFile() = File(Environment.getExternalStorageDirectory(), "1/pinyin_simp.dict.yaml")

    /** 把用户填写的服务器地址规整成完整 URL:补 http://、默认端口 5001、补词库路径。空输入返回空串。 */
    private fun buildServerUrl(input: String): String {
        var s = input.trim().removePrefix("http://").removePrefix("https://").trimEnd('/')
        if (s.isEmpty()) return ""
        val hostPort = s.substringBefore('/')              // 去掉用户可能粘进来的路径
        val host = if (hostPort.contains(':')) hostPort else "$hostPort:5001"
        return "http://$host/pinyin_simp.dict.yaml"
    }

    /** 在 box 末尾加两张词库同步卡片:我的服务器 + GitHub。两者都用内容哈希判断更新。 */
    private fun addDictSyncCard(box: LinearLayout) {
        // —— 我的服务器 ——
        box.addView(spacerV(dp(20)))
        box.addView(titleText("🖥 我的服务器同步"))
        box.addView(bodyText(
            "从你自己的服务器(http.server)拉取词库。\n" +
            "填局域网 IP 或 Tailscale IP(默认端口 5001),按内容哈希判断是否有更新。"
        ))
        val ipEdit = EditText(this).apply {
            hint = "服务器 IP,如 192.168.2.56"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setText(prefs().getString(KEY_SERVER_IP, ""))   // 记住上次填的 IP
            layoutParams = mw()
        }
        box.addView(ipEdit)
        addDictSourceUI(box, serverSource, "比较(检查服务器是否有更新)") {
            val url = buildServerUrl(ipEdit.text.toString())
            if (url.isNotEmpty()) {
                prefs().edit().putString(KEY_SERVER_IP, ipEdit.text.toString().trim()).apply()
            }
            url
        }

        // —— GitHub ——
        box.addView(spacerV(dp(20)))
        box.addView(titleText("📖 词库文件同步(GitHub)"))
        box.addView(bodyText(
            "从 GitHub 拉取最新词库 pinyin_simp.dict.yaml。\n" +
            "点「比较」下载并按内容哈希(SHA-256)判断是否有更新;有更新时点「下载并替换」会先删除本地旧文件再写入。"
        ))
        addDictSourceUI(box, githubSource, "比较(检查 GitHub 是否有更新)") { DictSync.GITHUB_URL }
    }

    /**
     * 渲染一个词库来源的「状态行 + 比较按钮 + 下载并替换按钮」。
     * @param urlProvider 点比较时调用,返回要下载的完整 URL;返回空串表示地址无效,直接提示。
     */
    private fun addDictSourceUI(
        box: LinearLayout,
        source: DictSource,
        compareLabel: String,
        urlProvider: () -> String,
    ) {
        val status = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.parseColor("#505050"))
            setPadding(0, dp(4), 0, dp(4))
            text = if (source.pendingBytes != null) "有新版本,可下载替换" else "尚未比较"
        }
        val downloadBtn = Button(this).apply {
            text = "下载并替换"
            layoutParams = mw()
            visibility = if (source.pendingBytes != null) View.VISIBLE else View.GONE
        }
        val compareBtn = Button(this).apply {
            text = compareLabel
            layoutParams = mw()
        }

        compareBtn.setOnClickListener {
            val url = urlProvider()
            if (url.isEmpty()) { toast("请先填写服务器地址"); return@setOnClickListener }
            compareBtn.isEnabled = false
            status.setTextColor(Color.parseColor("#505050"))
            status.text = "比较中…"
            Thread {
                try {
                    val f = dictFile()
                    val localBytes = if (f.exists()) f.readBytes() else null
                    val r = DictSync.check(url, localBytes)
                    ui.post {
                        compareBtn.isEnabled = true
                        if (r.changed) {
                            source.pendingBytes = r.bytes
                            val kb = r.bytes.size / 1024
                            status.setTextColor(Color.parseColor("#B05000"))
                            status.text = if (localBytes != null)
                                "内容不一致,有新版本(约 ${kb}KB),可下载替换"
                            else
                                "本地暂无词库,远端约 ${kb}KB,可下载"
                            downloadBtn.visibility = View.VISIBLE
                        } else {
                            source.pendingBytes = null
                            status.setTextColor(Color.parseColor("#1A6E1A"))
                            status.text = "内容一致,已是最新"
                            downloadBtn.visibility = View.GONE
                        }
                    }
                } catch (e: Exception) {
                    ui.post {
                        compareBtn.isEnabled = true
                        status.setTextColor(Color.parseColor("#C0392B"))
                        status.text = "比较失败:${e.message}"
                    }
                }
            }.start()
        }

        downloadBtn.setOnClickListener {
            val bytes = source.pendingBytes ?: run { toast("请先比较"); return@setOnClickListener }
            downloadBtn.isEnabled = false
            status.setTextColor(Color.parseColor("#505050"))
            status.text = "下载中…"
            Thread {
                try {
                    val f = dictFile()
                    if (f.exists()) f.delete()      // 先删本地旧文件,避免残留/重复
                    f.parentFile?.mkdirs()
                    f.writeBytes(bytes)
                    val kb = bytes.size / 1024
                    source.pendingBytes = null
                    ui.post {
                        downloadBtn.isEnabled = true
                        downloadBtn.visibility = View.GONE
                        status.setTextColor(Color.parseColor("#1A6E1A"))
                        status.text = "已下载并替换(约 ${kb}KB)。切换/重启输入法后生效。"
                    }
                } catch (e: Exception) {
                    ui.post {
                        downloadBtn.isEnabled = true
                        status.setTextColor(Color.parseColor("#C0392B"))
                        status.text = "下载失败:${e.message}"
                    }
                }
            }.start()
        }

        box.addView(status)
        box.addView(compareBtn)
        box.addView(downloadBtn)
    }

    // ---------------------------------------------------------------- UI helpers
    private fun titleText(t: String) = TextView(this).apply {
        text = t; textSize = 20f; setTextColor(Color.parseColor("#202020"))
        setPadding(0, 0, 0, dp(8))
    }

    private fun bodyText(t: String) = TextView(this).apply {
        text = t; textSize = 14f; setTextColor(Color.parseColor("#505050"))
        setPadding(0, dp(4), 0, dp(8))
    }

    private fun spacerV(h: Int) = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h)
    }

    private fun mw() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(8) }

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        // 预置的 Worker 地址:填上你部署的 workers.dev 网址(如 "https://pyime.xxx.workers.dev")。
        // 登录/注册统一使用该地址,app 内不再提供「Worker 地址」输入框。
        const val DEFAULT_SYNC_URL = "https://pyime.mybrowser.workers.dev"

        const val KEY_SYNC_USER = "sync_user"
        const val KEY_SYNC_TOKEN = "sync_token"
        const val KEY_SERVER_IP = "dict_server_ip"   // 「我的服务器同步」上次填的 IP
    }
}
