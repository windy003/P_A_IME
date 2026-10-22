package com.windy.pyime

import android.content.SharedPreferences

/**
 * 工具条按钮目录(图标 / 名称)与排序的读写。
 * 输入法服务与「编辑排序」全屏页共用,保证两边显示的是同一组按钮、同一份顺序。
 */
object ToolCatalog {

    data class Def(val id: String, val icon: String, val label: String)

    /**
     * 全部可排序的工具按钮(顺序仅作为首次使用时的默认排序)。
     * 展开按钮 ☰ 不在此列表中:它固定在顶栏第一格,避免被挤出顶栏后无法再打开展开面板。
     */
    val defs = listOf(
        Def("search", "🔍", "搜索"),
        Def("symbol", "➕", "符号"),
        Def("clip", "📋", "剪贴板"),
        Def("phrase", "📌", "常用语"),
        Def("paste", "⎘", "粘贴最近"),
        Def("cursor", "✥", "光标"),
        Def("hide", "⌄", "收起键盘"),
        Def("handwriting", "✍", "手写"),
        Def("sync", "☁", "同步"),
        Def("selectall", "🆎", "全选文字"),
        Def("copy", "📄", "复制"),
        Def("clear", "❌", "清除"),
    )

    val ids: List<String> = defs.map { it.id }

    fun def(id: String): Def? = defs.firstOrNull { it.id == id }

    fun icon(id: String): String = def(id)?.icon ?: ""

    /** 从设置读取顺序;补齐新增按钮、剔除已失效的 id。 */
    fun loadOrder(prefs: SharedPreferences): MutableList<String> {
        val saved = prefs.getString(PinyinImeService.KEY_TOOL_ORDER, null)
            ?.split(",")?.filter { it.isNotBlank() } ?: emptyList()
        val order = saved.filter { it in ids }.toMutableList()
        for (id in ids) if (id !in order) {
            // 搜索是常用入口:新按钮(含老用户已保存的顺序里没有它时)插到展开按钮旁边,
            // 而不是沉到展开面板
            if (id == "search") order.add(minOf(1, order.size), id) else order.add(id)
        }
        return order
    }

    fun saveOrder(prefs: SharedPreferences, order: List<String>) {
        prefs.edit().putString(PinyinImeService.KEY_TOOL_ORDER, order.joinToString(",")).apply()
    }
}
