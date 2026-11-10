package com.windy.pyime

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * 全屏搜索页(从输入法工具条的搜索按钮打开,与键盘独立):
 * 搜索剪贴板历史与常用语,支持「全部 / 只在剪贴板历史中 / 只在常用语中」三种范围,
 * 默认范围为「只在常用语中」。
 * 点击任意结果即复制到系统剪贴板,并关闭本页返回原来的应用;
 * 长按常用语结果可编辑/删除,长按剪贴板结果可删除。
 */
class SearchActivity : Activity() {

    private lateinit var ds: DataStore
    private lateinit var queryEdit: EditText
    private lateinit var results: LinearLayout
    private var scope = SCOPE_PHRASE   // 打开搜索页时默认只搜常用语
    private val scopeChips = ArrayList<Pair<Int, TextView>>()   // 范围值 -> 按钮
    private var chosenText: String? = null                      // 选中并复制成功的文字,返回时插回原输入框

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "搜索"
        ds = DataStore(this)
        // 打开搜索页时清掉可能残留的待插入文字(正常流程在返回原输入框时就已被消费)
        PinyinImeService.pendingCommitText = null

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(COL_BG)
        }

        // 顶部:搜索输入框 + 取消
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(16), dp(4))
        }
        queryEdit = EditText(this).apply {
            hint = "搜索历史记录 / 常用语"
            textSize = 16f
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = render()
                override fun afterTextChanged(s: Editable?) {}
            })
        }
        top.addView(queryEdit)
        top.addView(actionButton("取消") { finish() })
        root.addView(top)

        // 搜索范围:全部 / 只在剪贴板历史中 / 只在常用语中
        val scopeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(2), dp(12), dp(6))
        }
        scopeRow.addView(scopeChip("全部", SCOPE_ALL))
        scopeRow.addView(scopeChip("只在剪贴板历史中", SCOPE_HISTORY))
        scopeRow.addView(scopeChip("只在常用语中", SCOPE_PHRASE))
        root.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(scopeRow)
        })

        // 结果列表(占满剩余高度,可滚动)
        results = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(4), dp(6), dp(16))
        }
        root.addView(ScrollView(this).apply {
            isFillViewport = true
            addView(results)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
            )
        })

        setContentView(root)
        queryEdit.requestFocus()
        window.setSoftInputMode(
            WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        )
        render()
    }

    /** 按当前搜索词与范围重建结果:剪贴板历史 + 常用语。 */
    private fun render() {
        val q = queryEdit.text.toString().trim()
        updateScopeChips()
        results.removeAllViews()
        if (q.isEmpty()) {
            val tip = when (scope) {
                SCOPE_PHRASE -> "输入关键词搜索常用语。\n点击结果即可复制到剪贴板,长按可编辑或删除。"
                SCOPE_HISTORY -> "输入关键词搜索剪贴板历史。\n点击结果即可复制到剪贴板,长按可删除。"
                else -> "输入关键词搜索剪贴板历史与常用语。\n点击结果即可复制到剪贴板,长按常用语可编辑或删除。"
            }
            results.addView(hint(tip))
            return
        }
        val terms = listOf(q)
        val clips = if (scope != SCOPE_PHRASE) ds.searchClips(terms) else emptyList()
        val phrases = if (scope != SCOPE_HISTORY) ds.searchPhrases(terms) else emptyList()
        if (clips.isEmpty() && phrases.isEmpty()) {
            results.addView(hint("没有找到与「$q」匹配的内容。"))
            return
        }
        // 常用语带上所属文件夹名称,便于辨识
        val folderNames: Map<String, String> =
            if (phrases.isNotEmpty()) ds.folders().associate { it.uuid to it.name } else emptyMap()

        if (clips.isNotEmpty()) {
            results.addView(groupHeader("剪贴板历史 · ${clips.size}"))
            for (clip in clips) {
                results.addView(resultRow("剪贴板历史", null, clip.content,
                    onLongClick = { showClipActions(clip) }) {
                    ds.touchClip(clip.uuid)   // 置顶到最近
                    copyAndClose(clip.content)
                })
            }
        }
        if (phrases.isNotEmpty()) {
            results.addView(groupHeader("常用语 · ${phrases.size}"))
            for (p in phrases) {
                val folder = p.folderUuid?.let { folderNames[it] }
                val source = if (folder.isNullOrBlank()) "常用语" else "常用语 · $folder"
                results.addView(resultRow(source, p.description, p.content,
                    onLongClick = { showPhraseActions(p) }) {
                    ds.touchPhrase(p.uuid)
                    copyAndClose(p.content)
                })
            }
        }
    }

    /** 复制到系统剪贴板并关闭页面(Android 13+ 系统会自己弹出复制提示,不再额外 Toast)。 */
    private fun copyAndClose(text: String) {
        try {
            val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("PyIME", text))
            if (Build.VERSION.SDK_INT < 33) {
                Toast.makeText(this, "已复制到剪贴板", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Toast.makeText(this, "复制失败", Toast.LENGTH_SHORT).show()
            return
        }
        chosenText = text
        finish()
    }

    /**
     * 本页让出前台(A. 即将回到原应用)之前,把选中的文字交给输入法服务;
     * 原输入框恢复时由 PinyinImeService.onStartInputView 把它 commit 回输入框。
     * 必须放在 onPause 而不是 onDestroy:原页面通常在本页 onDestroy 之前就已恢复并重新唤起输入法。
     */
    override fun onPause() {
        super.onPause()
        chosenText?.let { PinyinImeService.pendingCommitText = it }
    }

    /** 从编辑页返回时重新搜索,让被编辑的内容立刻反映到结果里。 */
    override fun onResume() {
        super.onResume()
        render()
    }

    // ------------------------------------------------------------ 长按操作
    /** 长按常用语结果:编辑 / 删除。 */
    private fun showPhraseActions(p: DataStore.Phrase) {
        AlertDialog.Builder(this)
            .setTitle(oneLine(p.content))
            .setItems(arrayOf("编辑", "删除")) { _, which ->
                when (which) {
                    0 -> editPhrase(p)
                    else -> confirmDeletePhrase(p)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 长按剪贴板结果:只支持删除(剪贴板条目没有编辑入口)。 */
    private fun showClipActions(clip: DataStore.Clip) {
        AlertDialog.Builder(this)
            .setTitle(oneLine(clip.content))
            .setItems(arrayOf("删除")) { _, _ -> confirmDeleteClip(clip) }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 跳到 [PhraseEditActivity] 编辑常用语;返回后由 onResume 重新搜索。 */
    private fun editPhrase(p: DataStore.Phrase) {
        startActivity(Intent(this, PhraseEditActivity::class.java).apply {
            putExtra(PhraseEditActivity.EXTRA_MODE, PhraseEditActivity.MODE_EDIT_PHRASE)
            putExtra(PhraseEditActivity.EXTRA_TITLE, "编辑常用语")
            putExtra(PhraseEditActivity.EXTRA_FOLDER_UUID, p.folderUuid)
            putExtra(PhraseEditActivity.EXTRA_PHRASE_UUID, p.uuid)
            putExtra(PhraseEditActivity.EXTRA_MULTILINE, true)
            putExtra(PhraseEditActivity.EXTRA_INITIAL, p.content)
            putExtra(PhraseEditActivity.EXTRA_INITIAL_DESC, p.description)
        })
    }

    private fun confirmDeletePhrase(p: DataStore.Phrase) {
        AlertDialog.Builder(this)
            .setTitle("删除常用语")
            .setMessage("确定删除「${oneLine(p.content)}」?删除后无法恢复。")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ ->
                ds.deletePhrase(p.uuid)
                render()
            }
            .show()
    }

    private fun confirmDeleteClip(clip: DataStore.Clip) {
        AlertDialog.Builder(this)
            .setTitle("删除剪贴板记录")
            .setMessage("确定删除「${oneLine(clip.content)}」?删除后无法恢复。")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ ->
                ds.deleteClip(clip.uuid)
                render()
            }
            .show()
    }

    /** 把多行文本压成一行并截断,用于对话框标题与提示。 */
    private fun oneLine(s: String): String {
        val flat = s.replace("\r\n", " ").replace('\n', ' ').trim()
        return if (flat.length <= 20) flat else flat.take(20) + "…"
    }

    private fun updateScopeChips() {
        for ((value, tv) in scopeChips) {
            val selected = value == scope
            tv.background = GradientDrawable().apply {
                setColor(if (selected) COL_BLUE else Color.WHITE)
                cornerRadius = dp(18).toFloat()
            }
            tv.setTextColor(if (selected) Color.WHITE else COL_TEXT)
        }
    }

    private fun scopeChip(text: String, value: Int): TextView = TextView(this).apply {
        this.text = text
        textSize = 13f
        gravity = Gravity.CENTER
        setPadding(dp(14), dp(7), dp(14), dp(7))
        isClickable = true
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { setMargins(dp(4), 0, dp(4), 0) }
        setOnClickListener {
            scope = value
            render()
        }
    }.also { scopeChips.add(value to it) }

    private fun resultRow(
        source: String,
        description: String?,
        content: String,
        onLongClick: (() -> Unit)? = null,
        onClick: () -> Unit,
    ): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dp(10).toFloat()
            }
            setPadding(dp(14), dp(10), dp(14), dp(10))
            isClickable = true
            setOnClickListener { onClick() }
            if (onLongClick != null) {
                isLongClickable = true
                setOnLongClickListener { onLongClick(); true }
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(dp(8), dp(4), dp(8), dp(4)) }
        }
        card.addView(TextView(this).apply {
            text = if (description.isNullOrBlank()) source else "$source   $description"
            textSize = 11f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(COL_GRAY)
        })
        card.addView(TextView(this).apply {
            this.text = content
            textSize = 15f
            maxLines = 4
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(COL_TEXT)
            setPadding(0, dp(4), 0, 0)
        })
        return card
    }

    private fun groupHeader(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 12f
        setTextColor(COL_GRAY)
        setPadding(dp(16), dp(10), dp(16), dp(2))
    }

    private fun hint(msg: String): TextView = TextView(this).apply {
        text = msg
        textSize = 14f
        gravity = Gravity.CENTER
        setTextColor(COL_GRAY)
        setPadding(dp(24), dp(48), dp(24), dp(24))
    }

    private fun actionButton(text: String, onClick: () -> Unit): TextView = TextView(this).apply {
        this.text = text
        textSize = 15f
        setTextColor(COL_BLUE)
        gravity = Gravity.CENTER
        setPadding(dp(14), dp(10), dp(4), dp(10))
        isClickable = true
        setOnClickListener { onClick() }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        const val SCOPE_ALL = 0       // 全部(剪贴板历史 + 常用语)
        const val SCOPE_HISTORY = 1   // 只在剪贴板历史中
        const val SCOPE_PHRASE = 2    // 只在常用语中

        private val COL_BG = Color.parseColor("#F2F3F5")
        private val COL_BLUE = Color.parseColor("#4A90D9")
        private val COL_TEXT = Color.parseColor("#202020")
        private val COL_GRAY = Color.parseColor("#808080")
    }
}
