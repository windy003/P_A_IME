package com.windy.pyime

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import java.io.File

/**
 * 引导页:启用输入法、授予「所有文件访问」权限、查看词库文件状态。
 * 不含任何同步功能;词库由用户手动放到内部存储根目录。
 */
class SetupActivity : Activity() {

    private lateinit var status: TextView

    private val dictFile: File
        get() = File(Environment.getExternalStorageDirectory(), "1/pinyin_simp.dict.yaml")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pad = dp(20)
        val ll = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        ll.addView(title("PyIME 拼音输入法"))

        // 测试输入框:点一下即可调出输入法,方便边改边测
        ll.addView(body("测试输入框(点这里调出输入法):"))
        ll.addView(EditText(this).apply {
            hint = "在此输入,测试输入法效果"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 2
            gravity = Gravity.TOP
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        })

        ll.addView(body(
            "词库文件位置:\n${dictFile.absolutePath}\n\n" +
            "请用文件管理器把 pinyin_simp.dict.yaml 放到上面这个路径(内部存储根目录),然后按下面三步操作。"
        ))

        ll.addView(button("① 启用输入法") {
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
        })
        ll.addView(button("② 切换到 PyIME 拼音") {
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
        })
        ll.addView(button("③ 授予「所有文件访问」权限") {
            requestAllFilesAccess()
        })
        ll.addView(button("刷新状态") { refreshStatus() })

        // 键盘高度调节
        ll.addView(heightSection())

        status = body("")
        ll.addView(status)

        setContentView(ScrollView(this).apply { addView(ll) })
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
    }

    private fun refreshStatus() {
        val hasFiles = hasAllFilesAccess()
        val f = dictFile
        val dictInfo = if (f.exists()) "已找到(${f.length() / 1024} KB)" else "未找到 ✗"
        status.text = buildString {
            append("当前状态\n")
            append("· 文件权限:${if (hasFiles) "已授予 ✓" else "未授予 ✗"}\n")
            append("· 词库文件:$dictInfo\n")
            if (!hasFiles) append("\n没有文件权限,输入法将无法读取词库,请点 ③。")
            else if (!f.exists()) append("\n词库还没放好,请把 yaml 文件复制到上面的路径。")
            else append("\n一切就绪,可以在任意输入框使用了。")
        }
    }

    /** 键盘行高滑块:36–76 dp,实时写入 SharedPreferences,下次唤起键盘即生效。 */
    private fun heightSection(): LinearLayout {
        val prefs = getSharedPreferences(PinyinImeService.PREFS, MODE_PRIVATE)
        val cur = prefs.getInt(PinyinImeService.KEY_ROW_HEIGHT, PinyinImeService.DEFAULT_ROW_HEIGHT)
        val min = PinyinImeService.MIN_ROW_HEIGHT
        val max = PinyinImeService.MAX_ROW_HEIGHT

        val label = body("键盘高度:$cur")
        val seek = SeekBar(this).apply {
            this.max = max - min
            progress = cur.coerceIn(min, max) - min
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, fromUser: Boolean) {
                    val h = p + min
                    label.text = "键盘高度:$h"
                    prefs.edit().putInt(PinyinImeService.KEY_ROW_HEIGHT, h).apply()
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {}
            })
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(12), 0, 0)
            addView(label)
            addView(seek)
            addView(body("调好后回到输入框、重新唤起键盘即生效。"))
        }
    }

    private fun hasAllFilesAccess(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager()
        else checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    private fun requestAllFilesAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                startActivity(Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:$packageName")
                ))
            } catch (e: Exception) {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        } else {
            requestPermissions(arrayOf(
                android.Manifest.permission.READ_EXTERNAL_STORAGE,
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE
            ), 1)
        }
    }

    // ---- UI helpers ----
    private fun title(t: String) = TextView(this).apply {
        text = t; textSize = 22f; setTextColor(Color.parseColor("#202020"))
        setPadding(0, 0, 0, dp(12))
    }

    private fun body(t: String) = TextView(this).apply {
        text = t; textSize = 15f; setTextColor(Color.parseColor("#404040"))
        setPadding(0, dp(8), 0, dp(8))
    }

    private fun button(t: String, onClick: () -> Unit) = Button(this).apply {
        text = t
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) }
        setOnClickListener { onClick() }
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
