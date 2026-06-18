package com.windy.pyime

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.ClipboardManager
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.inputmethod.ExtractedTextRequest
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.vision.digitalink.DigitalInkRecognition
import com.google.mlkit.vision.digitalink.DigitalInkRecognitionModel
import com.google.mlkit.vision.digitalink.DigitalInkRecognitionModelIdentifier
import com.google.mlkit.vision.digitalink.DigitalInkRecognizer
import com.google.mlkit.vision.digitalink.DigitalInkRecognizerOptions
import com.google.mlkit.vision.digitalink.Ink
import com.google.mlkit.vision.digitalink.RecognitionResult
import java.io.File
import java.util.concurrent.Executors

/**
 * 拼音输入法服务。自绘 QWERTY 软键盘 + 候选栏,逻辑参照 PC 版 Engine:
 * 点字母 -> 累积拼音 -> 出候选 -> 点候选/空格上屏并调权(写回词库文件)。
 * 词库固定从 /storage/emulated/0/pinyin_simp.dict.yaml 读取(用户手动放置)。
 */
class PinyinImeService : InputMethodService() {

    private val dictFile: File
        get() = File(Environment.getExternalStorageDirectory(), "1/pinyin_simp.dict.yaml")

    @Volatile private var dict: PinyinDict? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val writeExec = Executors.newSingleThreadExecutor()

    // 组词状态
    private var buf = ""
    private var cands: List<PinyinDict.Candidate> = emptyList()
    private var segs: List<String> = emptyList()
    private var cnMode = true

    // UI 引用(随 onCreateInputView 重建)
    private var pinyinPreview: TextView? = null
    private var candidatesScroll: HorizontalScrollView? = null
    private var candidatesContainer: LinearLayout? = null
    private var toolbarRow: LinearLayout? = null   // 常驻工具条容器(顶栏 + 展开面板)
    private var toolbarTopRow: LinearLayout? = null     // 顶部一行:展开按钮 + 前若干个按钮
    private var toolbarExtraPanel: LinearLayout? = null // 展开面板:其余按钮 / 排序编辑
    private var toolbarExpanded = false                 // 展开面板是否打开
    private var toolbarEditing = false                  // 是否处于按钮排序编辑模式
    private var toolOrder: MutableList<String> = mutableListOf()  // 工具按钮顺序(存按钮 id)
    private var modeKey: TextView? = null

    private var rowHeightDp = DEFAULT_ROW_HEIGHT   // 当前键盘行高(可在设置页调节)

    private var shiftState = SHIFT_OFF             // 英文大小写:关 / 单次大写 / 大写锁定
    private var shiftKey: TextView? = null
    private val letterKeys = ArrayList<Pair<Char, TextView>>()  // 字母键引用,大小写变化时刷新

    // 剪贴板/常用语
    private var dataStore: DataStore? = null
    private var keyboardView: View? = null         // 键盘整体
    private var panelView: View? = null            // 工具面板(剪贴板/常用语)
    private var panelContent: LinearLayout? = null // 工具面板内容区(Tab 切换时重建)
    private var currentFolder: DataStore.Folder? = null  // 常用语当前进入的文件夹(null=最近)

    // 跳转到编辑页(PhraseEditActivity)/同步页(SyncActivity)后,返回时自动重开工具面板
    private var pendingReopenPanel = false
    private var pendingReopenFolderUuid: String? = null
    private var pendingReopenTab = TAB_PHRASE   // 返回后要重开的 Tab(剪贴板/常用语)

    private var symbolView: View? = null           // 数字符号页

    // 手写输入(Google ML Kit Digital Ink,首次需联网下载中文模型,之后离线识别)
    private var handwritingView: View? = null       // 手写面板整体
    private var handwritingPad: HandwritingPad? = null  // 画板
    private var hwCandidates: LinearLayout? = null  // 手写候选行
    private var hwStatus: TextView? = null          // 状态提示(下载/识别/出错)
    private var recognizer: DigitalInkRecognizer? = null
    private var modelReady = false                  // 中文手写模型是否已就绪
    private val recognizeRunnable = Runnable { runRecognition() }  // 停笔后防抖触发识别

    // 光标操作面板
    private var cursorView: View? = null           // 光标操作面板(方向键 + 选择/剪切/复制/粘贴)
    private var selectKey: TextView? = null        // 中间「选择」键引用,切换选择模式时刷新外观
    private var selecting = false                  // 选择模式:开时移动光标会扩展选区
    private var curSelStart = 0                    // 当前编辑框选区起点(由 onUpdateSelection 维护)
    private var curSelEnd = 0                      // 当前编辑框选区终点
    private var selAnchor = 0                      // 选择模式锚点:固定不动的一端

    private fun prefs() = getSharedPreferences(PREFS, MODE_PRIVATE)

    private var builtNight = false   // 上次构建输入视图时的夜间状态(用于检测系统切换后重建)

    /** 当前是否处于系统夜间模式。 */
    private fun isNight(): Boolean =
        (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    // 随夜间模式切换的调色板(强调蓝/红/灰提示色在明暗下通用,不随之变化)
    private fun colPanelBg() = if (isNight()) 0xFF202124.toInt() else 0xFFECEFF1.toInt()
    private fun colSurface() = if (isNight()) 0xFF3C4043.toInt() else Color.WHITE
    private fun colText() = if (isNight()) 0xFFE8EAED.toInt() else 0xFF202020.toInt()

    private val ROW1 = "qwertyuiop"
    private val ROW2 = "asdfghjkl"
    private val ROW3 = "zxcvbnm"

    /** 字母键上滑可输入的符号:长按字母向上滑动即输入对应符号。 */
    private val swipeSymbols = mapOf(
        'a' to "!",
        's' to "@",
        'q' to "'",
        'w' to "\"",
        'e' to ":",
        'r' to "/",
        't' to "\\",
        'u' to "?",
        'o' to "-",
        'p' to "_",
    )

    override fun onCreate() {
        super.onCreate()
        dataStore = DataStore(this)
        loadDictAsync()
    }

    private fun loadDictAsync() {
        Thread {
            val d = try {
                if (dictFile.exists()) PinyinDict.load(dictFile) else null
            } catch (e: Exception) {
                null
            }
            dict = d
            mainHandler.post {
                updatePreview()
                if (buf.isNotEmpty()) refresh()
            }
        }.start()
    }

    // ---------------------------------------------------------------- UI 构建
    override fun onCreateInputView(): View = buildInputView()

    /** 键盘唤起时:若设置页改过高度则重建键盘;复位到键盘视图;采集系统剪贴板。 */
    override fun onStartInputView(info: android.view.inputmethod.EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        if (prefs().getInt(KEY_ROW_HEIGHT, DEFAULT_ROW_HEIGHT) != rowHeightDp
            || isNight() != builtNight) {   // 高度改过 或 系统夜间模式切换过 -> 重建
            setInputView(buildInputView())
        }
        // 区分当前输入框是自己的编辑页,还是外部 app 的输入框
        val isOwnEditor = info?.packageName == packageName
        if (pendingReopenPanel && !isOwnEditor) {
            // 从编辑页/同步页返回到外部输入框:重开之前所在的工具面板(并定位到原文件夹)
            pendingReopenPanel = false
            toolTab = pendingReopenTab
            currentFolder = pendingReopenFolderUuid?.let { uuid ->
                dataStore?.folders()?.find { it.uuid == uuid }
            }
            openToolPanel()
        } else {
            // 普通唤起,或刚跳进自己的编辑页:都显示拼音字母主键盘,便于直接输入
            // (进编辑页时保留 pendingReopenPanel,等返回外部 app 再重开面板)
            closeToolPanel()
        }
        captureClipboard()
    }

    /** 读取系统剪贴板当前内容,与最新一条不同则存入历史顶端(输入法已获焦,允许读取)。 */
    private fun captureClipboard() {
        try {
            val cm = getSystemService(CLIPBOARD_SERVICE) as? ClipboardManager ?: return
            val clip = cm.primaryClip ?: return
            if (clip.itemCount == 0) return
            val text = clip.getItemAt(0)?.coerceToText(this)?.toString()?.trim()
            if (!text.isNullOrEmpty()) {
                val ds = dataStore
                writeExec.execute { ds?.upsertClipTop(text) }
            }
        } catch (e: Exception) { /* 读剪贴板失败不影响输入 */ }
    }

    private fun buildInputView(): View {
        rowHeightDp = prefs().getInt(KEY_ROW_HEIGHT, DEFAULT_ROW_HEIGHT)
        builtNight = isNight()
        letterKeys.clear()
        currentFolder = null

        // FrameLayout 根容器:键盘与工具面板叠放,通过显隐切换
        val root = FrameLayout(this).apply {
            setBackgroundColor(colPanelBg())
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        val kb = buildKeyboardView()
        val panel = buildToolPanel().apply { visibility = View.GONE }
        val cursor = buildCursorPanel().apply { visibility = View.GONE }
        val symbol = buildSymbolView().apply { visibility = View.GONE }
        val handwriting = buildHandwritingPanel().apply { visibility = View.GONE }
        keyboardView = kb
        panelView = panel
        cursorView = cursor
        symbolView = symbol
        handwritingView = handwriting
        root.addView(kb)
        root.addView(panel)
        root.addView(cursor)
        root.addView(symbol)
        root.addView(handwriting)
        return root
    }

    /** 构建键盘整体(拼音预览行 + 候选行 + 三行字母 + 功能行)。 */
    private fun buildKeyboardView(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(colPanelBg())
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        // 拼音预览行(固定高度,无输入时 GONE 不占位)
        pinyinPreview = TextView(this).apply {
            textSize = 14f
            setTextColor(Color.parseColor("#4A90D9"))
            setPadding(dp(12), 0, dp(12), 0)
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(32)
            )
            visibility = View.GONE
        }
        root.addView(pinyinPreview)

        // 候选词横向滚动栏(固定高度 + 垂直居中,空时 INVISIBLE 占位)
        candidatesContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        candidatesScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(candidatesContainer)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(50)
            )
            visibility = View.GONE
        }
        root.addView(candidatesScroll)

        // 常驻工具条:高度 = 预览(32)+候选(50),与有输入时总高一致,切换时键盘不跳动
        toolbarRow = buildToolbar()
        root.addView(toolbarRow)

        // 键盘:三行字母 + 功能行
        root.addView(letterRow(ROW1))
        root.addView(letterRow(ROW2, sideSpacer = 0.5f))
        root.addView(row3())
        root.addView(functionRow())

        updatePreview()
        return root
    }

    /** 工具按钮定义:id 用于持久化顺序,icon 是显示图标,label 是排序编辑时的文字说明。 */
    private data class ToolDef(val id: String, val icon: String, val label: String)

    /** 全部可用的工具按钮(顺序仅作为首次使用时的默认排序)。 */
    private val toolDefs = listOf(
        ToolDef("clip", "📋", "剪贴板"),
        ToolDef("phrase", "📝", "常用语"),
        ToolDef("paste", "⎘", "粘贴最近"),
        ToolDef("cursor", "✥", "光标"),
        ToolDef("hide", "⌄", "收起键盘"),
        ToolDef("handwriting", "✍", "手写"),
        ToolDef("sync", "☁", "同步"),
    )

    /** 执行某个工具按钮的动作。 */
    private fun runToolAction(id: String) {
        when (id) {
            "clip" -> { toolTab = TAB_CLIP; currentFolder = null; openToolPanel() }
            "phrase" -> { toolTab = TAB_PHRASE; currentFolder = null; openToolPanel() }
            "paste" -> pasteRecentClip()
            "cursor" -> openCursorPanel()
            "hide" -> onHide()
            "handwriting" -> openHandwriting()
            "sync" -> openSync()
        }
    }

    /** 从设置读取按钮顺序;补齐新增按钮、剔除已失效的 id。 */
    private fun loadToolOrder() {
        val saved = prefs().getString(KEY_TOOL_ORDER, null)
            ?.split(",")?.filter { it.isNotBlank() } ?: emptyList()
        val validIds = toolDefs.map { it.id }
        val order = saved.filter { it in validIds }.toMutableList()
        for (id in validIds) if (id !in order) order.add(id)   // 补齐(含将来新增)
        toolOrder = order
    }

    private fun saveToolOrder() {
        prefs().edit().putString(KEY_TOOL_ORDER, toolOrder.joinToString(",")).apply()
    }

    /** 粘贴最近一条剪贴板历史。 */
    private fun pasteRecentClip() {
        if (buf.isNotEmpty()) { commitText(buf.replace("'", "")); clearBuf() }
        val clip = dataStore?.recentClips(1)?.firstOrNull()
        if (clip == null) {
            Toast.makeText(this, "剪贴板暂无记录", Toast.LENGTH_SHORT).show()
            return
        }
        commitText(clip.content)
        dataStore?.touchClip(clip.uuid)   // 置顶到最近
    }

    /**
     * 常驻工具条:无拼音输入时显示。顶栏放展开按钮 + 前 TOOL_TOP_COUNT 个按钮,
     * 其余按钮收进展开面板;展开后可点「编辑排序」长按拖动调整全部按钮顺序。
     */
    private fun buildToolbar(): LinearLayout {
        loadToolOrder()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        toolbarTopRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(82)   // = 预览32 + 候选50,保证不跳动
            )
        }
        toolbarExtraPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(colPanelBg())
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        container.addView(toolbarTopRow)
        container.addView(toolbarExtraPanel)
        renderToolbar()
        return container
    }

    /** 根据当前顺序与展开/编辑状态重建整个工具条。 */
    private fun renderToolbar() {
        renderToolbarTop()
        val extra = toolbarExtraPanel ?: return
        extra.removeAllViews()
        extra.visibility = if (toolbarExpanded) View.VISIBLE else View.GONE
        if (!toolbarExpanded) { toolbarEditing = false; return }
        if (toolbarEditing) renderToolbarEditList(extra) else renderToolbarExtra(extra)
    }

    /** 只刷新顶栏(展开按钮 + 前若干按钮),用于拖动排序时的实时预览。 */
    private fun renderToolbarTop() {
        val top = toolbarTopRow ?: return
        top.removeAllViews()
        top.addView(toolbarButton(if (toolbarExpanded) "▲" else "☰") { toggleToolbarExpanded() })
        // 顶栏从右到左对应排序列表第 1、2…个:下拉按钮居最左,动作按钮反序排列
        for (id in toolOrder.take(TOOL_TOP_COUNT).reversed()) top.addView(toolbarButton(toolIcon(id)) {
            if (toolbarExpanded) collapseToolbar(); runToolAction(id)
        })
    }

    /** 展开面板(非编辑):编辑入口 + 其余按钮。 */
    private fun renderToolbarExtra(extra: LinearLayout) {
        val opRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        opRow.addView(textButton("✎ 编辑排序") { toolbarEditing = true; renderToolbar() })
        extra.addView(opRow)

        val panelIds = toolOrder.drop(TOOL_TOP_COUNT)
        if (panelIds.isEmpty()) {
            extra.addView(emptyHint("按钮都在顶栏。点「编辑排序」可把按钮挪到这里。"))
            return
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        for (id in panelIds) row.addView(toolbarButton(toolIcon(id)) {
            collapseToolbar(); runToolAction(id)
        })
        repeat((TOOL_TOP_COUNT - panelIds.size).coerceAtLeast(0)) {
            row.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(0, dp(50), 1f) })
        }
        extra.addView(row)
    }

    /** 展开面板(编辑模式):全部按钮竖排,长按拖动排序;前 TOOL_TOP_COUNT 个进顶栏。 */
    private fun renderToolbarEditList(extra: LinearLayout) {
        extra.addView(emptyHint("长按拖动调整顺序。前 $TOOL_TOP_COUNT 个显示在顶栏,其余在此展开面板。").apply {
            setPadding(dp(12), dp(8), dp(12), dp(8))
        })
        val opRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        opRow.addView(textButton("✓ 完成") { toolbarEditing = false; renderToolbar() })
        extra.addView(opRow)

        val listContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        extra.addView(listContainer)
        for (id in toolOrder) listContainer.addView(toolEditRow(id, listContainer))
    }

    /** 排序编辑中的一行:图标 + 名称 + 拖动手柄;row.tag = id。 */
    @SuppressLint("ClickableViewAccessibility")
    private fun toolEditRow(id: String, container: LinearLayout): View {
        val def = toolDefs.first { it.id == id }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                setColor(colSurface())
                cornerRadius = dp(6).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(dp(6), dp(3), dp(6), dp(3)) }
            tag = id
        }
        row.addView(TextView(this).apply {
            text = "${def.icon}  ${def.label}"
            textSize = 14f
            setTextColor(colText())
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        val handle = TextView(this).apply {
            text = "≡"
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#9AA0A6"))
            setPadding(dp(12), dp(10), dp(14), dp(10))
        }
        handle.setOnTouchListener(makeDragTouch(row, container) { persistToolOrder(it) })
        row.addView(handle)
        return row
    }

    /** 按编辑列表当前顺序(row.tag = id)写回按钮顺序,并实时刷新顶栏。 */
    private fun persistToolOrder(container: LinearLayout) {
        val ids = (0 until container.childCount).mapNotNull { container.getChildAt(it).tag as? String }
        if (ids.isEmpty()) return
        toolOrder = ids.toMutableList()
        saveToolOrder()
        renderToolbarTop()
    }

    private fun toolIcon(id: String) = toolDefs.first { it.id == id }.icon

    private fun toggleToolbarExpanded() {
        toolbarExpanded = !toolbarExpanded
        if (!toolbarExpanded) toolbarEditing = false
        renderToolbar()
    }

    /** 收起展开面板(点按钮执行动作前调用,回到键盘时保持简洁)。 */
    private fun collapseToolbar() {
        toolbarExpanded = false
        toolbarEditing = false
        renderToolbar()
    }

    private fun toolbarButton(text: String, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(colText())
            background = GradientDrawable().apply {
                setColor(colSurface())
                cornerRadius = dp(6).toFloat()
            }
            isClickable = true
            layoutParams = LinearLayout.LayoutParams(0, dp(50), 1f).apply {
                setMargins(dp(6), dp(6), dp(6), dp(6))
            }
            setOnClickListener { onClick() }
        }
    }

    private fun letterRow(letters: String, sideSpacer: Float = 0f): LinearLayout {
        val row = newRow()
        if (sideSpacer > 0) row.addView(spacer(sideSpacer))
        for (c in letters) row.addView(addLetterKey(c))
        if (sideSpacer > 0) row.addView(spacer(sideSpacer))
        return row
    }

    private fun row3(): LinearLayout {
        val row = newRow()
        val sk = makeKey(shiftSymbol(), 1.5f) { onShift() }
        shiftKey = sk
        row.addView(sk)
        for (c in ROW3) row.addView(addLetterKey(c))
        row.addView(makeKey("⌫", 1.5f) { onBackspace() })
        return row
    }

    /**
     * 创建一个字母键并登记引用,文字按当前大小写状态显示。
     * 若该字母配置了上滑符号([swipeSymbols]),键的右上角显示小提示,
     * 并支持「向上滑动输入符号、普通点击输入字母」。
     */
    private fun addLetterKey(c: Char): View {
        val sym = swipeSymbols[c]
        val label = TextView(this).apply {
            text = displayChar(c).toString()
            gravity = Gravity.CENTER
            textSize = 18f
            setTextColor(colText())
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        letterKeys.add(c to label)

        val container = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                setColor(colSurface())
                cornerRadius = dp(6).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(0, dp(rowHeightDp), 1f).apply {
                setMargins(dp(2), dp(3), dp(2), dp(3))
            }
            addView(label)
        }

        if (sym == null) {
            container.isClickable = true
            container.setOnClickListener { appendLetter(c) }
        } else {
            // 右上角小符号提示
            container.addView(TextView(this).apply {
                text = sym
                textSize = 10f
                setTextColor(Color.parseColor("#9AA0A6"))
                gravity = Gravity.TOP or Gravity.END
                setPadding(0, dp(2), dp(4), 0)
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
            })
            container.setOnTouchListener(makeSwipeTouch(c, sym))
        }
        return container
    }

    /** 处理「点击输入字母 / 上滑输入符号」的触摸监听。 */
    @SuppressLint("ClickableViewAccessibility")
    private fun makeSwipeTouch(c: Char, sym: String) = object : View.OnTouchListener {
        var startY = 0f
        var startX = 0f
        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { startY = e.rawY; startX = e.rawX; return true }
                MotionEvent.ACTION_UP -> {
                    val up = startY - e.rawY              // 向上滑动距离
                    val dx = Math.abs(e.rawX - startX)
                    if (up > dp(20) && up > dx) onSwipeSymbol(sym) else appendLetter(c)
                    return true
                }
            }
            return false
        }
    }

    /** 上滑输入符号:先把未完成拼音上屏,再输出符号(与标点处理一致)。 */
    private fun onSwipeSymbol(sym: String) {
        if (buf.isNotEmpty()) { commitText(buf.replace("'", "")); clearBuf() }
        commitText(sym)
    }

    /** 字母键应显示的字符:键面恒大写(中文模式拼音仍按小写输入,
     *  英文模式实际大小写由 Shift 状态另行决定,见 appendLetter)。 */
    private fun displayChar(c: Char): Char = c.uppercaseChar()

    private fun shiftSymbol(): String = when (shiftState) {
        SHIFT_ONCE -> "⇪"   // 单次大写:箭头下方带横线
        SHIFT_LOCK -> "⬆"   // 大写锁定:实心箭头
        else -> "⇧"          // 小写:空心箭头
    }

    private fun onShift() {
        shiftState = (shiftState + 1) % 3   // 关 → 单次 → 锁定 → 关
        updateLetterCaps()
    }

    /** Shift 或模式变化后刷新所有字母键和 Shift 键的显示。 */
    private fun updateLetterCaps() {
        for ((c, tv) in letterKeys) tv.text = displayChar(c).toString()
        shiftKey?.text = shiftSymbol()
    }

    private fun functionRow(): LinearLayout {
        val row = newRow()
        // 剪贴板、光标、收起入口都在常驻工具条
        modeKey = makeKey(if (cnMode) "中" else "EN", 1.4f) { toggleMode() }
        row.addView(makeKey("1", 1.4f) { openSymbolView() })  // 数字键盘(九宫格)入口
        row.addView(modeKey)
        row.addView(makeKey(",", 1f) { onPunct("，", ",") })
        row.addView(makeKey("空格", 3f) { onSpace() })
        row.addView(makeKey(".", 1f) { onPunct("。", ".") })
        row.addView(makeKey("⏎", 1.6f) { onEnter() })
        return row
    }

    private fun newRow() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun spacer(weight: Float) = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(0, dp(rowHeightDp), weight)
    }

    private fun makeKey(text: String, weight: Float, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            this.text = text
            gravity = Gravity.CENTER
            textSize = 18f
            setTextColor(colText())
            background = GradientDrawable().apply {
                setColor(colSurface())
                cornerRadius = dp(6).toFloat()
            }
            isClickable = true
            layoutParams = LinearLayout.LayoutParams(0, dp(rowHeightDp), weight).apply {
                setMargins(dp(2), dp(3), dp(2), dp(3))
            }
            setOnClickListener { onClick() }
        }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    // ---------------------------------------------------------------- 状态机
    private fun appendLetter(c: Char) {
        if (!cnMode) {   // 英文模式:按 Shift 状态决定大小写,单次大写上屏后自动复位
            val ch = if (shiftState != SHIFT_OFF) c.uppercaseChar() else c
            commitText(ch.toString())
            if (shiftState == SHIFT_ONCE) { shiftState = SHIFT_OFF; updateLetterCaps() }
            return
        }
        if (buf.length < PinyinDict.MAX_PINYIN) { buf += c; refresh() }
    }

    private fun refresh() {
        if (buf.isEmpty()) { clearBuf(); return }
        val d = dict
        if (d != null) {
            val (c, s) = d.candidates(buf)
            cands = c; segs = s
        } else {
            cands = emptyList(); segs = buf.map { it.toString() }
        }
        updatePreview()
    }

    private fun clearBuf() {
        buf = ""; cands = emptyList(); segs = emptyList()
        updatePreview()
    }

    private fun choose(index: Int) {
        if (index >= cands.size) return
        val cand = cands[index]
        commitText(cand.word)
        val consumed = segs.subList(0, cand.nseg).toList()
        val d = dict
        if (d != null) {
            try {
                val upd = d.bump(cand.word, consumed)
                if (upd != null) writeBack(cand.word, upd.first, upd.second)
            } catch (e: Exception) { /* 调权失败不影响上屏 */ }
        }
        // 去掉已消耗音节对应的字母,余下继续组词
        var b = buf
        for (s in consumed) {
            b = b.trimStart('\'')
            if (b.startsWith(s)) b = b.substring(s.length)
        }
        buf = b.trimStart('\'')
        if (buf.isNotEmpty()) refresh() else clearBuf()
    }

    private fun onBackspace() {
        if (buf.isNotEmpty()) { buf = buf.dropLast(1); refresh() }
        else currentInputConnection?.deleteSurroundingText(1, 0)
    }

    private fun onSpace() {
        if (cnMode && buf.isNotEmpty() && cands.isNotEmpty()) choose(0)
        else commitText(" ")
    }

    private fun onEnter() {
        if (buf.isNotEmpty()) { commitText(buf.replace("'", "")); clearBuf(); return }
        // 无拼音时:优先触发输入框声明的编辑器动作(地址栏「前往」、搜索框「搜索」等);
        // 没有具体动作或被禁用时才退回发普通 Enter(换行/确认)
        val ic = currentInputConnection
        val ei = currentInputEditorInfo
        if (ic != null && ei != null) {
            val opts = ei.imeOptions
            val action = opts and android.view.inputmethod.EditorInfo.IME_MASK_ACTION
            val noEnterAction =
                (opts and android.view.inputmethod.EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0
            if (!noEnterAction &&
                action != android.view.inputmethod.EditorInfo.IME_ACTION_NONE &&
                action != android.view.inputmethod.EditorInfo.IME_ACTION_UNSPECIFIED) {
                ic.performEditorAction(action)
                return
            }
        }
        sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
    }

    private fun onPunct(cn: String, en: String) {
        // 组词中按标点:先把当前拼音字母上屏,再发标点(简化:不在标点路径做部分选词)
        if (buf.isNotEmpty()) { commitText(buf.replace("'", "")); clearBuf() }
        // 逗号、句号无论中英文模式都输出英文版本
        commitText(en)
    }

    /** 收回输入法面板:丢弃未完成的拼音,隐藏键盘。 */
    private fun onHide() {
        if (buf.isNotEmpty()) clearBuf()
        requestHideSelf(0)
    }

    // ---------------------------------------------------------------- 工具面板
    /** 工具面板顶部 Tab 当前选中:剪贴板 / 常用语。 */
    private var toolTab = TAB_CLIP
    private var clipTabKey: TextView? = null    // 顶部「剪贴板」Tab,选中时背景高亮
    private var phraseTabKey: TextView? = null  // 顶部「常用语」Tab,选中时背景高亮

    /** 构建工具面板:顶部 Tab + 内容区(内容区随 Tab/操作动态重建)。 */
    private fun buildToolPanel(): View {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(colPanelBg())
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        // 顶部 Tab 行:剪贴板 / 常用语 / 返回键盘
        val tabRow = newRow()
        clipTabKey = makeKey("剪贴板", 2f) { toolTab = TAB_CLIP; currentFolder = null; renderPanel() }
        phraseTabKey = makeKey("常用语", 2f) { toolTab = TAB_PHRASE; currentFolder = null; renderPanel() }
        tabRow.addView(clipTabKey)
        tabRow.addView(phraseTabKey)
        tabRow.addView(makeKey("☁", 1.4f) {
            // 从工具面板进同步页:记下当前 Tab/文件夹,返回外部输入框时自动重开本面板
            pendingReopenPanel = true
            pendingReopenTab = toolTab
            pendingReopenFolderUuid = currentFolder?.uuid
            openSync()
        })
        tabRow.addView(makeKey("⌨", 1.4f) { closeToolPanel() })
        panel.addView(tabRow)

        // 内容区:固定高度的可滚动列表(高度与键盘大致相当,避免面板忽高忽低)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        panelContent = content
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            addView(content)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(rowHeightDp * 4 + 24)
            )
        }
        panel.addView(scroll)
        return panel
    }

    private fun openToolPanel() {
        if (buf.isNotEmpty()) { commitText(buf.replace("'", "")); clearBuf() }
        renderPanel()
        keyboardView?.visibility = View.GONE
        panelView?.visibility = View.VISIBLE
    }

    private fun closeToolPanel() {
        panelView?.visibility = View.GONE
        cursorView?.visibility = View.GONE
        symbolView?.visibility = View.GONE
        keyboardView?.visibility = View.VISIBLE
    }

    /** 根据当前 Tab 重新填充内容区,并刷新 Tab 高亮。 */
    private fun renderPanel() {
        updateTabHighlight()
        val content = panelContent ?: return
        content.removeAllViews()
        when (toolTab) {
            TAB_CLIP -> renderClipboard(content)
            else -> renderPhrase(content)
        }
    }

    /** 选中的 Tab 背景设为蓝色,未选中为白色。 */
    private fun updateTabHighlight() {
        setTabBg(clipTabKey, toolTab == TAB_CLIP)
        setTabBg(phraseTabKey, toolTab == TAB_PHRASE)
    }

    private fun setTabBg(tv: TextView?, selected: Boolean) {
        tv?.background = GradientDrawable().apply {
            setColor(if (selected) Color.parseColor("#4A90D9") else colSurface())
            cornerRadius = dp(6).toFloat()
        }
    }

    // ---- 剪贴板面板 ----
    private fun renderClipboard(content: LinearLayout) {
        val clips = dataStore?.recentClips() ?: emptyList()
        if (clips.isEmpty()) {
            content.addView(emptyHint("剪贴板暂无记录。复制文字后再唤起键盘即可自动收录。"))
            return
        }
        for (clip in clips) {
            content.addView(listItem(clip.content, onClick = {
                commitText(clip.content)
                dataStore?.touchClip(clip.uuid)   // 置顶到最近
                closeToolPanel()
            }, onDelete = {
                dataStore?.deleteClip(clip.uuid)
                renderPanel()
            }))
        }
    }

    // ---- 常用语面板 ----
    private fun renderPhrase(content: LinearLayout) {
        val ds = dataStore ?: return
        // 子 Tab 行:最近 / 各文件夹 / 新建文件夹
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        val barScroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(bar)
        }
        bar.addView(chip("最近", selected = currentFolder == null) {
            currentFolder = null; renderPanel()
        })
        // 文件夹 chip 放进独立子容器,长按可在其中左右拖动排序
        val foldersContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        for (f in ds.folders()) foldersContainer.addView(folderChip(f, foldersContainer))
        bar.addView(foldersContainer)
        bar.addView(chip("+ 文件夹", selected = false) { promptNewFolder() })
        content.addView(barScroll)

        val folder = currentFolder
        if (folder == null) {
            // 「最近」:最近点选过的常用语
            val recents = ds.recentPhrases()
            if (recents.isEmpty()) {
                content.addView(emptyHint("还没有最近使用的常用语。\n进入文件夹新建条目并点选后,会出现在这里。"))
                return
            }
            // 「最近」按使用时间排序,不支持拖动;仍可长按删除
            for (p in recents) content.addView(phraseItem(p, container = null, draggable = false))
        } else {
            // 某个文件夹:条目列表 + 新建条目 + 删除文件夹
            val actionRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(dp(8), dp(2), dp(8), dp(2))
            }
            actionRow.addView(textButton("+ 新建条目") { promptNewPhrase(folder) })
            actionRow.addView(textButton("重命名") { promptRenameFolder(folder) })
            actionRow.addView(textButton("删除该文件夹") { confirmDeleteFolder(folder) })
            content.addView(actionRow)

            val items = ds.phrasesIn(folder.uuid)
            if (items.isEmpty()) {
                content.addView(emptyHint("「${folder.name}」还没有条目,点上方「+ 新建条目」添加。"))
                return
            }
            // 条目放进独立子容器,拖动排序时只在此容器内重排,不受上方 Tab/操作行干扰
            val itemsContainer = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
            }
            content.addView(itemsContainer)
            for (p in items) itemsContainer.addView(phraseItem(p, itemsContainer, draggable = true))
        }
    }

    // ---- 新建(跳转编辑页)/ 删除 ----
    /** 新建文件夹:跳到编辑页输入名称(输入法窗口内无法打字)。 */
    private fun promptNewFolder() =
        launchTextInput(PhraseEditActivity.MODE_FOLDER, "新建文件夹", null, multiline = false)

    /** 在指定文件夹下新建常用语:跳到编辑页输入内容。 */
    private fun promptNewPhrase(folder: DataStore.Folder) =
        launchTextInput(
            PhraseEditActivity.MODE_PHRASE, "在「${folder.name}」中新建常用语",
            folder.uuid, multiline = true
        )

    /** 重命名文件夹:跳到编辑页,预填当前名称。 */
    private fun promptRenameFolder(folder: DataStore.Folder) =
        launchTextInput(
            PhraseEditActivity.MODE_RENAME_FOLDER, "重命名文件夹",
            folder.uuid, multiline = false, initial = folder.name
        )

    /** 编辑常用语:跳到编辑页,预填当前内容;返回后定位回该条目所在文件夹。 */
    private fun promptEditPhrase(p: DataStore.Phrase) =
        launchTextInput(
            PhraseEditActivity.MODE_EDIT_PHRASE, "编辑常用语",
            folderUuid = p.folderUuid, multiline = true,
            initial = p.content, phraseUuid = p.uuid
        )

    /**
     * 跳转到 [PhraseEditActivity] 编辑文字。记下返回后要重开的文件夹,
     * 保存返回时由 onStartInputView 重新打开常用语面板。
     */
    private fun launchTextInput(
        mode: String, title: String, folderUuid: String?,
        multiline: Boolean, initial: String? = null, phraseUuid: String? = null
    ) {
        pendingReopenPanel = true
        pendingReopenFolderUuid = folderUuid
        pendingReopenTab = TAB_PHRASE
        val intent = android.content.Intent(this, PhraseEditActivity::class.java).apply {
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(PhraseEditActivity.EXTRA_MODE, mode)
            putExtra(PhraseEditActivity.EXTRA_TITLE, title)
            putExtra(PhraseEditActivity.EXTRA_FOLDER_UUID, folderUuid)
            putExtra(PhraseEditActivity.EXTRA_PHRASE_UUID, phraseUuid)
            putExtra(PhraseEditActivity.EXTRA_MULTILINE, multiline)
            putExtra(PhraseEditActivity.EXTRA_INITIAL, initial)
        }
        startActivity(intent)
    }

    private fun confirmDeleteFolder(folder: DataStore.Folder) {
        val dlg = AlertDialog.Builder(this)
            .setTitle("删除文件夹")
            .setMessage("确定删除「${folder.name}」及其中所有条目?")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除") { _, _ ->
                dataStore?.deleteFolder(folder.uuid); currentFolder = null; renderPanel()
            }
            .create()
        showOverIme(dlg)
    }

    /**
     * 让对话框能显示在输入法窗口之上。
     * 关键:必须用「当前输入视图所在窗口」的 windowToken 作为附着 token,
     * 而不是 window.attributes.token(后者在输入法窗口里无效,show() 会抛 BadTokenException)。
     * token、type 必须在 show() 之前写回 attributes。
     */
    private fun showOverIme(dlg: AlertDialog) {
        val token = (keyboardView ?: panelView ?: cursorView ?: symbolView)?.windowToken
            ?: window?.window?.decorView?.windowToken
        val w = dlg.window
        if (token != null && w != null) {
            val lp = w.attributes
            lp.token = token
            lp.type = android.view.WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG
            w.attributes = lp
            w.addFlags(android.view.WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
        }
        dlg.show()
    }

    // ---- 面板内的小部件 ----
    /** 一行列表项:左侧文本(可点击上屏),右侧 ✕ 删除按钮默认隐藏,长按文本后显示/收起。 */
    private fun listItem(text: String, onClick: () -> Unit, onDelete: () -> Unit): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                setColor(colSurface())
                cornerRadius = dp(6).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(dp(6), dp(3), dp(6), dp(3)) }
        }
        // ✕ 删除按钮:默认隐藏,长按文本后出现
        val delBtn = TextView(this).apply {
            this.text = "✕"
            textSize = 16f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#9AA0A6"))
            setPadding(dp(16), dp(12), dp(16), dp(12))
            isClickable = true
            visibility = View.GONE
            setOnClickListener { onDelete() }
        }
        row.addView(TextView(this).apply {
            this.text = text
            textSize = 12f
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(colText())
            setPadding(dp(12), dp(12), dp(12), dp(12))
            isClickable = true
            isLongClickable = true
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { onClick() }
            setOnLongClickListener {
                // 长按切换:显示/隐藏删除按钮
                delBtn.visibility =
                    if (delBtn.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                true
            }
        })
        row.addView(delBtn)
        return row
    }

    /**
     * 常用语条目行:文本点击上屏、长按弹出/收起删除按钮;
     * draggable 时右侧带「≡」手柄,按住可在 [container] 内拖动排序。
     * row.tag 记 uuid,拖动结束据此持久化顺序。
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun phraseItem(p: DataStore.Phrase, container: LinearLayout?, draggable: Boolean): View {
        val ds = dataStore
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                setColor(colSurface())
                cornerRadius = dp(6).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(dp(6), dp(3), dp(6), dp(3)) }
            tag = p.uuid
        }

        // 操作按钮:默认隐藏,长按文本后出现(编辑 / 删除)
        val editBtn = TextView(this).apply {
            text = "编辑"
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#4A90D9"))
                cornerRadius = dp(6).toFloat()
            }
            setPadding(dp(14), dp(8), dp(14), dp(8))
            isClickable = true
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, dp(4), dp(6), dp(4)) }
            setOnClickListener { promptEditPhrase(p) }
        }

        val delBtn = TextView(this).apply {
            text = "删除"
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#E53935"))
                cornerRadius = dp(6).toFloat()
            }
            setPadding(dp(14), dp(8), dp(14), dp(8))
            isClickable = true
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(0, dp(4), dp(6), dp(4)) }
            setOnClickListener { ds?.deletePhrase(p.uuid); renderPanel() }
        }

        val textView = TextView(this).apply {
            text = p.content
            textSize = 12f
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            setTextColor(colText())
            setPadding(dp(10), dp(6), dp(10), dp(6))
            isClickable = true
            isLongClickable = true
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { commitText(p.content); ds?.touchPhrase(p.uuid); closeToolPanel() }
            setOnLongClickListener {
                // 长按切换:同时显示/隐藏 编辑、删除 按钮
                val show = if (delBtn.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                editBtn.visibility = show
                delBtn.visibility = show
                true
            }
        }

        row.addView(textView)
        row.addView(editBtn)
        row.addView(delBtn)

        if (draggable && container != null) {
            val handle = TextView(this).apply {
                text = "≡"
                textSize = 14f
                gravity = Gravity.CENTER
                setTextColor(Color.parseColor("#9AA0A6"))
                setPadding(dp(12), dp(6), dp(14), dp(6))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            handle.setOnTouchListener(makeDragTouch(row, container))
            row.addView(handle)
        }
        return row
    }

    /**
     * 拖动手柄的触摸逻辑(带松手前预览):
     * 被拖行用 translationY 跟着手指浮动(translationZ 提到其他行之上,不改 child 顺序);
     * 其他行根据当前目标位置用带动画的 translationY 上下让位;松手时才真正重排并清除位移、持久化。
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun makeDragTouch(
        row: View,
        container: LinearLayout,
        onDrop: (LinearLayout) -> Unit = { persistPhraseOrder(it) }
    ) = object : View.OnTouchListener {
        var startRawY = 0f
        var origIndex = -1
        var target = -1
        var rowH = 0

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    // 让外层 ScrollView 不拦截后续移动事件(会向上传播到祖先)
                    (v.parent as? ViewGroup)?.requestDisallowInterceptTouchEvent(true)
                    startRawY = e.rawY
                    origIndex = container.indexOfChild(row)
                    target = origIndex
                    rowH = if (row.height > 0) row.height else dp(48)
                    row.translationZ = dp(8).toFloat()   // 浮到其他行之上(不改变 child 顺序)
                    row.alpha = 0.9f
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (origIndex < 0) return true
                    row.translationY = e.rawY - startRawY     // 跟手浮动
                    // 用各行原始布局中点判断目标插入位置(getTop 不含 translationY,稳定)
                    val loc = IntArray(2)
                    container.getLocationOnScreen(loc)
                    val touchY = e.rawY - loc[1]
                    var t = container.childCount - 1
                    for (i in 0 until container.childCount) {
                        val c = container.getChildAt(i)
                        if (touchY < c.top + c.height / 2f) { t = i; break }
                    }
                    if (t != target) {                        // 目标变化时,其他行带动画让位
                        target = t
                        for (i in 0 until container.childCount) {
                            if (i == origIndex) continue
                            val ty = when {
                                origIndex < target && i in (origIndex + 1)..target -> -rowH.toFloat()
                                origIndex > target && i in target until origIndex -> rowH.toFloat()
                                else -> 0f
                            }
                            container.getChildAt(i).animate().translationY(ty).setDuration(120).start()
                        }
                    }
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (origIndex < 0) return true
                    val finalTarget = target
                    origIndex = -1
                    row.alpha = 1f
                    row.translationZ = 0f
                    // 重排会移除正在接收触摸的行,必须延到事件派发结束后执行,否则
                    // removeView 触发 ACTION_CANCEL 再次进入本回调,造成无限递归崩溃。
                    container.post {
                        row.translationY = 0f
                        for (i in 0 until container.childCount) {
                            val c = container.getChildAt(i)
                            c.animate().cancel()
                            c.translationY = 0f
                        }
                        val cur = container.indexOfChild(row)
                        if (cur >= 0 && finalTarget != cur && finalTarget in 0 until container.childCount) {
                            container.removeViewAt(cur)
                            container.addView(row, finalTarget.coerceIn(0, container.childCount))
                        }
                        onDrop(container)
                    }
                    return true
                }
            }
            return false
        }
    }

    /** 按容器中当前条目顺序(row.tag = uuid)写回排序。 */
    private fun persistPhraseOrder(container: LinearLayout) {
        val uuids = (0 until container.childCount).mapNotNull { container.getChildAt(it).tag as? String }
        if (uuids.isNotEmpty()) dataStore?.reorderPhrases(uuids)
    }

    /** 常用语子 Tab 的小标签。 */
    private fun chip(text: String, selected: Boolean, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(if (selected) Color.WHITE else colText())
            background = GradientDrawable().apply {
                setColor(if (selected) Color.parseColor("#4A90D9") else colSurface())
                cornerRadius = dp(14).toFloat()
            }
            setPadding(dp(12), dp(5), dp(12), dp(5))
            isClickable = true
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(dp(4), 0, dp(4), 0) }
            setOnClickListener { onClick() }
        }
    }

    /**
     * 可拖动排序的文件夹标签:点击进入该文件夹,长按后左右拖动可在 [container] 内排序。
     * tag 记 uuid,拖动结束后据此持久化顺序。
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun folderChip(folder: DataStore.Folder, container: LinearLayout): TextView {
        val selected = currentFolder?.uuid == folder.uuid
        val tv = TextView(this).apply {
            text = folder.name
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(if (selected) Color.WHITE else colText())
            background = GradientDrawable().apply {
                setColor(if (selected) Color.parseColor("#4A90D9") else colSurface())
                cornerRadius = dp(14).toFloat()
            }
            setPadding(dp(12), dp(5), dp(12), dp(5))
            isClickable = true
            isLongClickable = true
            tag = folder.uuid
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(dp(4), 0, dp(4), 0) }
        }
        tv.setOnTouchListener(makeFolderDragTouch(tv, container, folder))
        return tv
    }

    /**
     * 文件夹标签的触摸逻辑:长按进入拖动,横向跟手浮动 + 其他标签让位(松手前预览);
     * 松手后重排并持久化。未触发长按时:轻点=进入该文件夹,横滑=交还 ScrollView 滚动。
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun makeFolderDragTouch(chip: View, container: LinearLayout, folder: DataStore.Folder) =
        object : View.OnTouchListener {
            val slop = ViewConfiguration.get(this@PinyinImeService).scaledTouchSlop
            var startRawX = 0f
            var dragging = false
            var origIndex = -1
            var target = -1
            var chipW = 0
            val longPress = Runnable {
                dragging = true
                origIndex = container.indexOfChild(chip)
                target = origIndex
                chipW = chip.width
                (chip.parent as? ViewGroup)?.requestDisallowInterceptTouchEvent(true)
                chip.alpha = 0.85f
                chip.translationZ = dp(8).toFloat()
            }

            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        startRawX = e.rawX
                        dragging = false
                        mainHandler.postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        if (!dragging) {
                            // 长按未触发就移动:视为滚动,取消长按并交还 ScrollView
                            if (Math.abs(e.rawX - startRawX) > slop) {
                                mainHandler.removeCallbacks(longPress)
                                (chip.parent as? ViewGroup)?.requestDisallowInterceptTouchEvent(false)
                            }
                            return false
                        }
                        chip.translationX = e.rawX - startRawX     // 横向跟手
                        val loc = IntArray(2)
                        container.getLocationOnScreen(loc)
                        val touchX = e.rawX - loc[0]
                        var t = container.childCount - 1
                        for (i in 0 until container.childCount) {
                            val c = container.getChildAt(i)
                            if (touchX < c.left + c.width / 2f) { t = i; break }
                        }
                        if (t != target) {                          // 目标变化时,其他标签带动画让位
                            target = t
                            for (i in 0 until container.childCount) {
                                if (i == origIndex) continue
                                val tx = when {
                                    origIndex < target && i in (origIndex + 1)..target -> -chipW.toFloat()
                                    origIndex > target && i in target until origIndex -> chipW.toFloat()
                                    else -> 0f
                                }
                                container.getChildAt(i).animate().translationX(tx).setDuration(120).start()
                            }
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        mainHandler.removeCallbacks(longPress)
                        if (!dragging) {
                            // 没进入拖动:轻点(未明显移动)= 进入该文件夹
                            if (e.actionMasked == MotionEvent.ACTION_UP &&
                                Math.abs(e.rawX - startRawX) <= slop) {
                                currentFolder = folder; renderPanel()
                            }
                            return true
                        }
                        val finalTarget = target
                        dragging = false
                        chip.alpha = 1f
                        chip.translationZ = 0f
                        // 重排延到事件派发结束后执行,避免 removeView 触发 CANCEL 递归
                        container.post {
                            chip.translationX = 0f
                            for (i in 0 until container.childCount) {
                                val c = container.getChildAt(i)
                                c.animate().cancel()
                                c.translationX = 0f
                            }
                            val cur = container.indexOfChild(chip)
                            if (cur >= 0 && finalTarget != cur && finalTarget in 0 until container.childCount) {
                                container.removeViewAt(cur)
                                container.addView(chip, finalTarget.coerceIn(0, container.childCount))
                            }
                            persistFolderOrder(container)
                        }
                        return true
                    }
                }
                return false
            }
        }

    /** 按容器中当前文件夹顺序(chip.tag = uuid)写回排序。 */
    private fun persistFolderOrder(container: LinearLayout) {
        val uuids = (0 until container.childCount).mapNotNull { container.getChildAt(it).tag as? String }
        if (uuids.isNotEmpty()) dataStore?.reorderFolders(uuids)
    }

    private fun textButton(text: String, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 12f
            setTextColor(Color.parseColor("#4A90D9"))
            setPadding(dp(6), dp(6), dp(12), dp(6))
            isClickable = true
            setOnClickListener { onClick() }
        }
    }

    private fun emptyHint(msg: String): TextView {
        return TextView(this).apply {
            text = msg
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#808080"))
            setPadding(dp(24), dp(32), dp(24), dp(32))
        }
    }

    // ---------------------------------------------------------------- 数字键盘(九宫格)
    /**
     * 构建九宫格数字键盘:左侧 1-9 + 0 排成九宫格,右侧一列功能键(退格/返回/空格/回车)。
     * 此页不参与拼音组词,数字键直接上屏。
     */
    private fun buildSymbolView(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(colPanelBg())
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        val r1 = newRow()
        for (n in listOf("1", "2", "3")) r1.addView(makeKey(n, 1f) { onSymbolInput(n) })
        r1.addView(makeKey("返回", 1f) { closeSymbolView() })
        root.addView(r1)

        val r2 = newRow()
        for (n in listOf("4", "5", "6")) r2.addView(makeKey(n, 1f) { onSymbolInput(n) })
        r2.addView(makeKey("⌫", 1f) { onBackspace() })
        root.addView(r2)

        val r3 = newRow()
        for (n in listOf("7", "8", "9")) r3.addView(makeKey(n, 1f) { onSymbolInput(n) })
        r3.addView(makeKey("空格", 1f) { commitText(" ") })
        root.addView(r3)

        val r4 = newRow()
        r4.addView(makeKey("0", 2f) { onSymbolInput("0") })   // 0 横跨两列
        r4.addView(makeKey(".", 1f) { onSymbolInput(".") })   // 小数点
        r4.addView(makeKey("⏎", 1f) { onEnter() })
        root.addView(r4)
        return root
    }

    /** 数字键:直接上屏对应字符(进入本页时已清空拼音缓冲)。 */
    private fun onSymbolInput(s: String) {
        commitText(s)
    }

    private fun openSymbolView() {
        if (buf.isNotEmpty()) { commitText(buf.replace("'", "")); clearBuf() }
        keyboardView?.visibility = View.GONE
        panelView?.visibility = View.GONE
        cursorView?.visibility = View.GONE
        symbolView?.visibility = View.VISIBLE
    }

    private fun closeSymbolView() {
        symbolView?.visibility = View.GONE
        keyboardView?.visibility = View.VISIBLE
    }

    // ---------------------------------------------------------------- 手写输入
    /**
     * 手写面板:候选行 + 状态提示 + 画板 + 功能行。
     * 识别用 Google ML Kit Digital Ink(中文 zh-Hani 模型),首次需联网下载,之后离线可用。
     */
    private fun buildHandwritingPanel(): View {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(colPanelBg())
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        // 候选行(横向滚动)
        val cand = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        hwCandidates = cand
        panel.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(cand)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(46)
            )
        })

        // 状态提示(下载/识别/出错)
        hwStatus = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.parseColor("#9AA0A6"))
            setPadding(dp(12), 0, dp(12), dp(2))
        }
        panel.addView(hwStatus)

        // 画板
        val pad = HandwritingPad(this).apply {
            background = GradientDrawable().apply {
                setColor(colSurface())
                cornerRadius = dp(6).toFloat()
            }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(180)
            ).apply { setMargins(dp(6), dp(2), dp(6), dp(4)) }
        }
        handwritingPad = pad
        panel.addView(pad)

        // 功能行
        val row = newRow()
        row.addView(makeKey("清除", 1f) { clearHandwriting() })
        row.addView(makeKey("⌫", 1f) { onBackspace() })
        row.addView(makeKey("空格", 2f) { commitText(" ") })
        row.addView(makeKey("⏎", 1f) { onEnter() })
        row.addView(makeKey("拼音", 1.4f) { closeHandwriting() })
        panel.addView(row)
        return panel
    }

    /** 画板:捕捉触摸笔迹,边画边收集成 ML Kit 的 Ink;停笔后由外层防抖触发识别。 */
    @SuppressLint("ViewConstructor")
    private inner class HandwritingPad(context: Context) : View(context) {
        private val paint = Paint().apply {
            color = colText()
            isAntiAlias = true
            style = Paint.Style.STROKE
            strokeWidth = dp(3).toFloat()
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private val committedPaths = ArrayList<Path>()
        private var livePath: Path? = null
        private var inkBuilder = Ink.builder()
        private var strokeBuilder: Ink.Stroke.Builder? = null

        fun isEmpty() = committedPaths.isEmpty() && livePath == null

        fun clearPad() {
            committedPaths.clear()
            livePath = null
            inkBuilder = Ink.builder()
            strokeBuilder = null
            invalidate()
        }

        fun currentInk(): Ink = inkBuilder.build()

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean {
            val x = event.x; val y = event.y; val t = System.currentTimeMillis()
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    cancelPendingRecognition()
                    livePath = Path().apply { moveTo(x, y) }
                    strokeBuilder = Ink.Stroke.builder().apply { addPoint(Ink.Point.create(x, y, t)) }
                    invalidate()
                }
                MotionEvent.ACTION_MOVE -> {
                    livePath?.lineTo(x, y)
                    strokeBuilder?.addPoint(Ink.Point.create(x, y, t))
                    invalidate()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    livePath?.let { committedPaths.add(it) }
                    livePath = null
                    strokeBuilder?.let { inkBuilder.addStroke(it.build()) }
                    strokeBuilder = null
                    invalidate()
                    scheduleRecognition()
                }
            }
            return true
        }

        override fun onDraw(canvas: Canvas) {
            for (p in committedPaths) canvas.drawPath(p, paint)
            livePath?.let { canvas.drawPath(it, paint) }
        }
    }

    private fun setHwStatus(s: String) { hwStatus?.text = s }

    private fun scheduleRecognition() {
        mainHandler.removeCallbacks(recognizeRunnable)
        mainHandler.postDelayed(recognizeRunnable, 600)   // 停笔 600ms 后识别
    }

    private fun cancelPendingRecognition() {
        mainHandler.removeCallbacks(recognizeRunnable)
    }

    /** 确保中文手写模型就绪(必要时下载),就绪后回调 onReady。 */
    private fun ensureRecognizer(onReady: () -> Unit) {
        if (modelReady && recognizer != null) { onReady(); return }
        try {
            val modelId = DigitalInkRecognitionModelIdentifier.fromLanguageTag("zh-Hani")
            if (modelId == null) { setHwStatus("此设备不支持中文手写模型"); return }
            val model = DigitalInkRecognitionModel.builder(modelId).build()
            val rec = DigitalInkRecognition.getClient(
                DigitalInkRecognizerOptions.builder(model).build()
            )
            val manager = RemoteModelManager.getInstance()
            manager.isModelDownloaded(model).addOnSuccessListener { downloaded ->
                if (downloaded) {
                    recognizer = rec; modelReady = true; setHwStatus(""); onReady()
                } else {
                    setHwStatus("首次使用:正在下载中文手写模型(需联网)…")
                    manager.download(model, DownloadConditions.Builder().build())
                        .addOnSuccessListener {
                            recognizer = rec; modelReady = true; setHwStatus(""); onReady()
                        }
                        .addOnFailureListener { e ->
                            setHwStatus("模型下载失败(请联网后重开手写):${e.message}")
                        }
                }
            }.addOnFailureListener { e ->
                setHwStatus("手写模型检查失败:${e.message}")
            }
        } catch (e: Exception) {
            setHwStatus("手写初始化失败:${e.message}")
        }
    }

    private fun runRecognition() {
        val pad = handwritingPad ?: return
        if (pad.isEmpty()) return
        ensureRecognizer {
            val rec = recognizer ?: return@ensureRecognizer
            try {
                rec.recognize(pad.currentInk())
                    .addOnSuccessListener { result -> showHwCandidates(result) }
                    .addOnFailureListener { e -> setHwStatus("识别失败:${e.message}") }
            } catch (e: Exception) {
                setHwStatus("识别异常:${e.message}")
            }
        }
    }

    private fun showHwCandidates(result: RecognitionResult) {
        val cont = hwCandidates ?: return
        cont.removeAllViews()
        for (c in result.candidates.take(12)) cont.addView(hwCandView(c.text))
    }

    /** 手写候选项:点击上屏并清空画板,方便继续写下一个字。 */
    private fun hwCandView(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 20f
        setTextColor(colText())
        gravity = Gravity.CENTER
        setPadding(dp(14), dp(4), dp(14), dp(4))
        isClickable = true
        setOnClickListener {
            commitText(text)
            handwritingPad?.clearPad()
            hwCandidates?.removeAllViews()
        }
    }

    private fun clearHandwriting() {
        handwritingPad?.clearPad()
        hwCandidates?.removeAllViews()
        setHwStatus("")
    }

    private fun openHandwriting() {
        if (buf.isNotEmpty()) { commitText(buf.replace("'", "")); clearBuf() }
        keyboardView?.visibility = View.GONE
        panelView?.visibility = View.GONE
        cursorView?.visibility = View.GONE
        symbolView?.visibility = View.GONE
        handwritingView?.visibility = View.VISIBLE
        clearHandwriting()
        ensureRecognizer { }   // 预热;若未下载会提示并开始下载
    }

    private fun closeHandwriting() {
        cancelPendingRecognition()
        handwritingView?.visibility = View.GONE
        keyboardView?.visibility = View.VISIBLE
    }

    // ---------------------------------------------------------------- 光标操作面板
    /**
     * 构建光标操作面板:十字方向键 + 中间「选择」键(开启后移动方向键会扩展选区)。
     * 编辑动作放在十字的四个斜对角上(全选/剪切/复制/粘贴),不再拥挤在顶部;
     * 清除、返回键盘放在最底一行。
     */
    private fun buildCursorPanel(): View {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(colPanelBg())
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        // 第一行:左上角 全选 / ↑ / 右上角 清除
        val row1 = newRow()
        row1.addView(makeKey("全选", 1f) { onSelectAll() })
        row1.addView(makeKey("↑", 1f) { moveCursor(KeyEvent.KEYCODE_DPAD_UP) })
        row1.addView(makeKey("清除", 1f) { onClear() })
        panel.addView(row1)

        // 第二行:← / 选择 / →
        val row2 = newRow()
        row2.addView(makeKey("←", 1f) { moveCursor(KeyEvent.KEYCODE_DPAD_LEFT) })
        val sk = makeKey("选择", 1f) { toggleSelecting() }
        selectKey = sk
        row2.addView(sk)
        row2.addView(makeKey("→", 1f) { moveCursor(KeyEvent.KEYCODE_DPAD_RIGHT) })
        panel.addView(row2)

        // 第三行:左下角 复制 / ↓ / 右下角 剪切
        val row3 = newRow()
        row3.addView(makeKey("复制", 1f) { onClipAction(android.R.id.copy) })
        row3.addView(makeKey("↓", 1f) { moveCursor(KeyEvent.KEYCODE_DPAD_DOWN) })
        row3.addView(makeKey("剪切", 1f) { onClipAction(android.R.id.cut) })
        panel.addView(row3)

        // 最底一行:粘贴 / 返回键盘
        val row4 = newRow()
        row4.addView(makeKey("粘贴", 1f) { onClipAction(android.R.id.paste) })
        row4.addView(makeKey("⌨", 1f) { closeCursorPanel() })
        panel.addView(row4)

        return panel
    }

    private fun openCursorPanel() {
        if (buf.isNotEmpty()) { commitText(buf.replace("'", "")); clearBuf() }
        selecting = false
        updateSelectKey()
        keyboardView?.visibility = View.GONE
        panelView?.visibility = View.GONE
        cursorView?.visibility = View.VISIBLE
    }

    private fun closeCursorPanel() {
        cursorView?.visibility = View.GONE
        keyboardView?.visibility = View.VISIBLE
    }

    /** 打开云同步页(登录/比较/同步都在独立 Activity,输入法窗口里无法操作)。 */
    private fun openSync() {
        if (buf.isNotEmpty()) { commitText(buf.replace("'", "")); clearBuf() }
        val intent = android.content.Intent(this, SyncActivity::class.java)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
    }

    /**
     * 移动光标。选择模式下左右键直接用 setSelection 精确扩展选区(锚点固定,
     * 移动另一端),比发带 Shift 的按键事件更可靠;上下键(跨行)仍用按键事件兜底。
     */
    private fun moveCursor(keyCode: Int) {
        val ic = currentInputConnection ?: return
        if (selecting && (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)) {
            // 与锚点不同的那一端是「移动端」;选区折叠时两端都等于锚点
            val movingEnd = if (curSelEnd != selAnchor) curSelEnd else curSelStart
            val delta = if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1
            val newEnd = (movingEnd + delta).coerceAtLeast(0)
            ic.setSelection(minOf(selAnchor, newEnd), maxOf(selAnchor, newEnd))
            return
        }
        // 选择模式下上/下键:按行扩展选区(尽量保留列位置)。成功则返回,
        // 取不到整段文本时(部分编辑框不支持)再走下面的按键事件兜底。
        if (selecting && (keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_DPAD_DOWN)) {
            if (selectByLine(ic, keyCode == KeyEvent.KEYCODE_DPAD_DOWN)) return
        }
        val meta = if (selecting) KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON else 0
        val now = System.currentTimeMillis()
        ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, meta))
        ic.sendKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0, meta))
    }

    /**
     * 选择模式下按行扩展选区:把「移动端」上移/下移一行(列位置尽量保持),
     * 再以锚点为固定端重设选区。返回是否成功(取不到整段文本时返回 false 交给兜底)。
     */
    private fun selectByLine(ic: android.view.inputmethod.InputConnection, down: Boolean): Boolean {
        val et = ic.getExtractedText(ExtractedTextRequest(), 0) ?: return false
        val text = et.text?.toString() ?: return false
        val base = et.startOffset.coerceAtLeast(0)            // 整段文本在原文中的起始偏移
        val movingEnd = if (curSelEnd != selAnchor) curSelEnd else curSelStart
        val pos = (movingEnd - base).coerceIn(0, text.length) // 移动端在整段文本中的下标
        val lineStart = text.lastIndexOf('\n', pos - 1).let { if (it < 0) 0 else it + 1 }
        val column = pos - lineStart
        val target: Int = if (down) {
            val lineEnd = text.indexOf('\n', pos).let { if (it < 0) text.length else it }
            if (lineEnd >= text.length) text.length            // 已是最后一行:选到末尾
            else {
                val nextStart = lineEnd + 1
                val nextEnd = text.indexOf('\n', nextStart).let { if (it < 0) text.length else it }
                minOf(nextStart + column, nextEnd)
            }
        } else {
            if (lineStart == 0) 0                              // 已是第一行:选到开头
            else {
                val prevEnd = lineStart - 1                     // 上一行末尾的换行符位置
                val prevStart = text.lastIndexOf('\n', prevEnd - 1).let { if (it < 0) 0 else it + 1 }
                minOf(prevStart + column, prevEnd)
            }
        }
        val g = target + base
        ic.setSelection(minOf(selAnchor, g), maxOf(selAnchor, g))
        return true
    }

    private fun toggleSelecting() {
        selecting = !selecting
        // 开启选择模式时,把当前光标位置定为锚点(后续移动以此端为固定端)
        if (selecting) selAnchor = curSelStart
        updateSelectKey()
    }

    /** 刷新「选择」键外观:选择模式下高亮显示。 */
    private fun updateSelectKey() {
        val k = selectKey ?: return
        k.text = if (selecting) "选择中" else "选择"
        k.background = GradientDrawable().apply {
            setColor(if (selecting) Color.parseColor("#4A90D9") else colSurface())
            cornerRadius = dp(6).toFloat()
        }
        k.setTextColor(if (selecting) Color.WHITE else colText())
    }

    private fun onSelectAll() {
        currentInputConnection?.performContextMenuAction(android.R.id.selectAll)
        selecting = true
        updateSelectKey()
    }

    /** 执行剪切/复制/粘贴,完成后退出选择模式。 */
    private fun onClipAction(action: Int) {
        currentInputConnection?.performContextMenuAction(action)
        selecting = false
        updateSelectKey()
    }

    /** 清除:有选区时用空串替换选中内容即删除(配合「全选」可清空输入框);完成后退出选择模式。 */
    private fun onClear() {
        currentInputConnection?.commitText("", 1)
        selecting = false
        updateSelectKey()
    }

    private fun toggleMode() {
        if (buf.isNotEmpty()) { commitText(buf.replace("'", "")); clearBuf() }
        cnMode = !cnMode
        modeKey?.text = if (cnMode) "中" else "EN"
        updateLetterCaps()
    }

    private fun commitText(t: String) {
        currentInputConnection?.commitText(t, 1)
    }

    private fun writeBack(word: String, key: String, weight: Int) {
        val f = dictFile
        writeExec.execute {
            try { PinyinDict.updateDictFile(f, word, key, weight) } catch (e: Exception) { /* 忽略 */ }
        }
    }

    // ---------------------------------------------------------------- 刷新候选 UI
    private fun updatePreview() {
        pinyinPreview?.text = segs.joinToString("'")
        val container = candidatesContainer ?: return
        container.removeAllViews()
        if (dict == null && buf.isNotEmpty()) {
            container.addView(hintView("词库未加载:请把 pinyin_simp.dict.yaml 放到内部存储根目录并授予文件权限"))
        } else {
            for ((i, c) in cands.withIndex()) {
                container.addView(candView(c.word, i))
            }
        }
        val show = buf.isNotEmpty()
        // 有拼音输入:显示预览+候选;无输入:显示常驻工具条。两者总高相同,键盘不跳动。
        pinyinPreview?.visibility = if (show) View.VISIBLE else View.GONE
        candidatesScroll?.visibility = if (show) View.VISIBLE else View.GONE
        toolbarRow?.visibility = if (show) View.GONE else View.VISIBLE
        candidatesScroll?.scrollTo(0, 0)
    }

    private fun candView(word: String, index: Int): TextView {
        return TextView(this).apply {
            text = if (index < 9) "${index + 1} $word" else word
            textSize = 17f
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(4), dp(12), dp(4))
            setTextColor(colText())
            isClickable = true
            setOnClickListener { choose(index) }
        }
    }

    private fun hintView(msg: String): TextView {
        return TextView(this).apply {
            text = msg
            textSize = 13f
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(6), dp(12), dp(6))
            setTextColor(Color.parseColor("#B00020"))
        }
    }

    override fun onStartInput(attribute: android.view.inputmethod.EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        clearBuf()
    }

    /** 编辑框选区变化回调:缓存光标/选区位置,供选择模式精确扩展选区。 */
    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int,
        newSelStart: Int, newSelEnd: Int,
        candidatesStart: Int, candidatesEnd: Int
    ) {
        super.onUpdateSelection(
            oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd
        )
        curSelStart = newSelStart
        curSelEnd = newSelEnd
    }

    override fun onDestroy() {
        cancelPendingRecognition()
        try { recognizer?.close() } catch (e: Exception) { /* 忽略 */ }
        writeExec.shutdown()
        super.onDestroy()
    }

    companion object {
        const val PREFS = "pyime"
        const val KEY_ROW_HEIGHT = "row_height_dp"
        const val KEY_TOOL_ORDER = "toolbar_order"   // 工具按钮顺序(逗号分隔的 id)
        const val TOOL_TOP_COUNT = 5                  // 顶栏工具按钮个数(+下拉按钮共 6 个),其余进展开面板
        const val DEFAULT_ROW_HEIGHT = 46
        const val MIN_ROW_HEIGHT = 36
        const val MAX_ROW_HEIGHT = 76

        const val SHIFT_OFF = 0    // 小写
        const val SHIFT_ONCE = 1   // 单次大写(打一个字母后复位)
        const val SHIFT_LOCK = 2   // 大写锁定

        const val TAB_CLIP = 0     // 工具面板:剪贴板
        const val TAB_PHRASE = 1   // 工具面板:常用语
    }
}
