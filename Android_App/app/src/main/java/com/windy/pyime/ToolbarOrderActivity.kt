package com.windy.pyime

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 「编辑排序」全屏页:输入法面板里点 ✎ 编辑排序 后打开。
 *
 * 所见即所得:按真实工具条排版把全部按钮显示在屏幕顶部(☰ 固定第一格 +
 * 顶栏 11 格,顶栏放不下的收在下方「展开面板」行),图标就是按钮本身。
 * 长按某个图标提起后拖动,网格实时重排预览松手后的最终排列;松手即保存,
 * 返回输入法时由 [PinyinImeService.onStartInputView] 重新读取顺序。
 */
class ToolbarOrderActivity : Activity() {

    private lateinit var prefs: android.content.SharedPreferences
    private var order: MutableList<String> = mutableListOf()
    private val tiles = mutableListOf<View>()   // 预览网格里的格子,下标即槽位

    private lateinit var host: FrameLayout
    private lateinit var content: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = getSharedPreferences(PinyinImeService.PREFS, MODE_PRIVATE)
        order = ToolCatalog.loadOrder(prefs)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#ECEFF1"))
            clipChildren = false   // 提起来的图标可以浮到标题/提示行上方
        }

        // 顶部标题 + 完成
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(8), dp(4))
            addView(TextView(this@ToolbarOrderActivity).apply {
                text = "编辑排序"
                textSize = 20f
                setTextColor(Color.parseColor("#202020"))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(textButton("✓ 完成") { finish() })
        })

        root.addView(TextView(this).apply {
            text = "长按图标提起后拖动排序,松手放下。\n" +
                "「☰」固定在顶栏第一格,不可拖动;前 ${PinyinImeService.TOOL_TOP_COUNT - 1} 个显示在输入法顶栏,其余收进展开面板。"
            textSize = 12f
            setTextColor(Color.parseColor("#808080"))
            setPadding(dp(16), dp(2), dp(16), dp(10))
        })

        // 网格放在 FrameLayout 里:拖动的图标提起到该容器做浮层,
        // 网格内容随手指移动实时重建(预览最终排列),不影响正在拖动的手势。
        host = FrameLayout(this).apply {
            clipChildren = false
            clipToPadding = false
        }
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        host.addView(content, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        host.setOnTouchListener(makeSortTouch())
        root.addView(host)

        // 其余空间留白,让按钮始终停在屏幕顶部
        root.addView(View(this), LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f
        ))

        setContentView(root)
        renderPreview(order, draggingId = null)
    }

    // ---------------------------------------------------------------- 网格
    /** 按给定顺序渲染面板(拖动时传预览顺序);draggingId 对应的格子显示为高亮占位格。 */
    private fun renderPreview(orderToShow: List<String>, draggingId: String?) {
        content.removeAllViews()
        tiles.clear()

        // 顶栏:☰ 固定第一格 + 前 TOOL_TOP_COUNT-1 个按钮(行优先、右→左,与输入法顶栏一致)
        val topCells = ArrayList<View>(PinyinImeService.TOOL_TOP_COUNT)
        topCells.add(fixedTile())
        for (id in orderToShow.take(PinyinImeService.TOOL_TOP_COUNT - 1)) {
            val cell = tile(id, placeholder = id == draggingId)
            tiles.add(cell)
            topCells.add(cell)
        }
        val topGrid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
        }
        fillRows(topGrid, topCells, rightToLeft = true)
        content.addView(topGrid)

        // 展开面板:顶栏放不下的按钮,与输入法展开面板一样从左往右排成一行
        content.addView(TextView(this).apply {
            text = "展开面板"
            textSize = 12f
            setTextColor(Color.parseColor("#9AA0A6"))
            setPadding(dp(19), dp(12), dp(19), dp(2))
        })
        val panelIds = orderToShow.drop(PinyinImeService.TOOL_TOP_COUNT - 1)
        if (panelIds.isEmpty()) return
        val panelRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
        }
        for (id in panelIds) {
            val cell = tile(id, placeholder = id == draggingId)
            tiles.add(cell)
            panelRow.addView(cell)
        }
        repeat((PinyinImeService.TOOL_TOP_COUNT - 1 - panelIds.size).coerceAtLeast(0)) {
            panelRow.addView(View(this).apply {
                layoutParams = LinearLayout.LayoutParams(0, dp(ROW_HEIGHT), 1f)
            })
        }
        content.addView(panelRow)
    }

    /** 每行 COLS 个格子;rightToLeft 时逻辑靠前的格子显示在行最右边。 */
    private fun fillRows(container: LinearLayout, cells: List<View>, rightToLeft: Boolean) {
        var i = 0
        while (i < cells.size) {
            val end = minOf(i + COLS, cells.size)
            val chunk = cells.subList(i, end)
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                clipChildren = false
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
            if (rightToLeft) {
                repeat(COLS - chunk.size) { row.addView(spacer()) }
                for (k in chunk.indices.reversed()) row.addView(chunk[k])
            } else {
                for (cell in chunk) row.addView(cell)
                repeat(COLS - chunk.size) { row.addView(spacer()) }
            }
            container.addView(row)
            i = end
        }
    }

    private fun tile(id: String, placeholder: Boolean): TextView {
        val def = ToolCatalog.def(id)
        return TextView(this).apply {
            text = def?.icon ?: ""
            textSize = 15f
            gravity = Gravity.CENTER
            setTextColor(Color.parseColor("#202020"))
            alpha = if (placeholder) 0.45f else 1f
            background = tileBg(placeholder)
            setPadding(dp(2), dp(2), dp(2), dp(2))
            layoutParams = LinearLayout.LayoutParams(0, dp(BTN_HEIGHT), 1f).apply {
                setMargins(dp(3), dp(3), dp(3), dp(3))
            }
            tag = id
            contentDescription = def?.label ?: id
        }
    }

    private fun fixedTile(): TextView = TextView(this).apply {
        text = "☰"
        textSize = 15f
        gravity = Gravity.CENTER
        setTextColor(Color.parseColor("#9AA0A6"))
        background = tileBg(false)
        setPadding(dp(2), dp(2), dp(2), dp(2))
        layoutParams = LinearLayout.LayoutParams(0, dp(BTN_HEIGHT), 1f).apply {
            setMargins(dp(3), dp(3), dp(3), dp(3))
        }
        contentDescription = "展开所有按钮(固定第一格)"
    }

    private fun spacer(): View = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(0, dp(ROW_HEIGHT), 1f).apply {
            setMargins(dp(3), dp(3), dp(3), dp(3))
        }
    }

    private fun tileBg(highlighted: Boolean): GradientDrawable = GradientDrawable().apply {
        setColor(if (highlighted) Color.parseColor("#D6E9FA") else Color.WHITE)
        if (highlighted) setStroke(dp(2), Color.parseColor("#4A90D9"))
        cornerRadius = dp(6).toFloat()
    }

    // ---------------------------------------------------------------- 拖动
    /**
     * 面板手势:按下命中图标,长按后把它提起到浮层跟随手指,网格实时重排;
     * 手势监听挂在不会重建的 host 上,所以拖动途中可以放心重建网格内容。
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun makeSortTouch(): View.OnTouchListener = object : View.OnTouchListener {
        val slop = ViewConfiguration.get(this@ToolbarOrderActivity).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var lastX = 0f
        var lastY = 0f
        var pressed: View? = null
        var lifted = false
        var draggingId: String? = null
        var previewOrder: MutableList<String>? = null
        var floating: View? = null
        var offsetX = 0f
        var offsetY = 0f

        fun moveFloating(rawX: Float, rawY: Float) {
            val tile = floating ?: return
            val loc = IntArray(2)
            host.getLocationOnScreen(loc)
            val lp = tile.layoutParams as? FrameLayout.LayoutParams ?: return
            lp.leftMargin = (rawX - loc[0] - offsetX).toInt()
            lp.topMargin = (rawY - loc[1] - offsetY).toInt()
            tile.layoutParams = lp
        }

        val lift = Runnable {
            val tile = pressed ?: return@Runnable
            val id = tile.tag as? String ?: return@Runnable
            val w = if (tile.width > 0) tile.width
                    else resources.displayMetrics.widthPixels / COLS
            val h = if (tile.height > 0) tile.height else dp(BTN_HEIGHT)
            val loc = IntArray(2)
            tile.getLocationOnScreen(loc)
            offsetX = lastX - loc[0]
            offsetY = lastY - loc[1]
            lifted = true
            draggingId = id
            val preview = order.toMutableList()
            previewOrder = preview
            // 先按当前顺序重建网格(被拖格子显示为高亮占位),移动时继续实时重排
            renderPreview(preview, id)
            // 原格子从行里摘下,提到 host 做浮层
            (tile.parent as? ViewGroup)?.removeView(tile)
            host.addView(tile, FrameLayout.LayoutParams(w, h))
            floating = tile
            tile.scaleX = 1.08f
            tile.scaleY = 1.08f
            tile.alpha = 0.92f
            tile.translationZ = dp(10).toFloat()
            tile.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            moveFloating(lastX, lastY)
            host.parent?.requestDisallowInterceptTouchEvent(true)
        }

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (lifted) return true   // 拖动中忽略第二根手指
                    downX = e.rawX
                    downY = e.rawY
                    lastX = e.rawX
                    lastY = e.rawY
                    pressed = tileAt(e.rawX, e.rawY)
                    if (pressed != null) {
                        host.postDelayed(
                            lift, ViewConfiguration.getLongPressTimeout().toLong()
                        )
                    }
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    lastX = e.rawX
                    lastY = e.rawY
                    if (!lifted) {
                        if (Math.abs(e.rawX - downX) > slop || Math.abs(e.rawY - downY) > slop) {
                            host.removeCallbacks(lift)
                            pressed = null
                        }
                        return true
                    }
                    moveFloating(e.rawX, e.rawY)
                    val id = draggingId ?: return true
                    val preview = previewOrder ?: return true
                    val hover = nearestTileIndex(e.rawX, e.rawY)
                    val cur = preview.indexOf(id)
                    if (hover >= 0 && cur >= 0 && hover != cur) {
                        preview.removeAt(cur)
                        preview.add(hover.coerceIn(0, preview.size), id)
                        renderPreview(preview, id)
                    }
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    host.removeCallbacks(lift)
                    host.parent?.requestDisallowInterceptTouchEvent(false)
                    if (!lifted) { pressed = null; return true }
                    lifted = false
                    if (e.actionMasked == MotionEvent.ACTION_UP) {
                        previewOrder?.let { preview ->
                            if (preview != order) {
                                order = preview.toMutableList()
                                ToolCatalog.saveOrder(prefs, order)
                            }
                        }
                    }
                    floating?.let { host.removeView(it) }
                    floating = null
                    previewOrder = null
                    draggingId = null
                    pressed = null
                    // 延后重建,避免在事件派发途中移除视图
                    host.post { renderPreview(order, null) }
                    return true
                }
            }
            return false
        }
    }

    /** 手指位置命中的格子(点在 ☰ 或空白处返回 null)。 */
    private fun tileAt(rawX: Float, rawY: Float): View? {
        val loc = IntArray(2)
        for (t in tiles) {
            if (t.width <= 0 || t.height <= 0) continue
            t.getLocationOnScreen(loc)
            if (rawX >= loc[0] && rawX <= loc[0] + t.width &&
                rawY >= loc[1] && rawY <= loc[1] + t.height) return t
        }
        return null
    }

    /** 离手指最近的格子下标;槽位固定,实时重排时不会来回抖动。 */
    private fun nearestTileIndex(rawX: Float, rawY: Float): Int {
        var best = -1
        var bestD = Float.MAX_VALUE
        val loc = IntArray(2)
        for (i in tiles.indices) {
            val t = tiles[i]
            t.getLocationOnScreen(loc)
            val cx = loc[0] + t.width / 2f
            val cy = loc[1] + t.height / 2f
            val d = (cx - rawX) * (cx - rawX) + (cy - rawY) * (cy - rawY)
            if (d < bestD) { bestD = d; best = i }
        }
        return best
    }

    // ---------------------------------------------------------------- 小工具
    private fun textButton(text: String, onClick: () -> Unit): TextView = TextView(this).apply {
        this.text = text
        textSize = 15f
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        background = GradientDrawable().apply {
            setColor(Color.parseColor("#4A90D9"))
            cornerRadius = dp(6).toFloat()
        }
        setPadding(dp(14), dp(8), dp(14), dp(8))
        isClickable = true
        setOnClickListener { onClick() }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        private const val COLS = 6       // 与输入法顶栏列数一致
        private const val BTN_HEIGHT = 44
        private const val ROW_HEIGHT = BTN_HEIGHT + 6
    }
}
