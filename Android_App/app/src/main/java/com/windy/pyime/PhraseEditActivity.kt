package com.windy.pyime

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * 文字编辑页。输入法窗口内无法调起键盘打字,所以「新建文件夹 / 新建常用语」都跳到本页编辑,
 * 保存后直接写入共享的 [DataStore](同一进程内的 SQLite),返回输入法时面板重新读取即可看到。
 *
 * 通过 Intent extra 传入:
 *  - [EXTRA_MODE]:[MODE_FOLDER] 新建文件夹 / [MODE_PHRASE] 新建常用语 /
 *                 [MODE_RENAME_FOLDER] 重命名文件夹 / [MODE_EDIT_PHRASE] 编辑常用语
 *  - [EXTRA_TITLE]:页面标题
 *  - [EXTRA_FOLDER_UUID]:常用语所属文件夹;重命名模式下为被重命名的文件夹(folder 新建模式忽略)
 *  - [EXTRA_PHRASE_UUID]:编辑常用语模式下,被编辑的条目 uuid
 *  - [EXTRA_MULTILINE]:输入框是否多行
 *  - [EXTRA_INITIAL]:输入框预填内容(如重命名/编辑时的原内容)
 */
class PhraseEditActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_FOLDER
        val titleText = intent.getStringExtra(EXTRA_TITLE) ?: "输入"
        val folderUuid = intent.getStringExtra(EXTRA_FOLDER_UUID)
        val phraseUuid = intent.getStringExtra(EXTRA_PHRASE_UUID)
        val multiline = intent.getBooleanExtra(EXTRA_MULTILINE, false)
        val initial = intent.getStringExtra(EXTRA_INITIAL)
        title = titleText

        val pad = dp(20)
        val ll = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }

        ll.addView(TextView(this).apply {
            text = titleText
            textSize = 20f
            setTextColor(Color.parseColor("#202020"))
            setPadding(0, 0, 0, dp(12))
        })

        val edit = EditText(this).apply {
            hint = if (mode == MODE_PHRASE || mode == MODE_EDIT_PHRASE) "常用语内容" else "文件夹名称"
            inputType = if (multiline)
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            else InputType.TYPE_CLASS_TEXT
            if (multiline) { minLines = 3; gravity = Gravity.TOP }
            if (!initial.isNullOrEmpty()) { setText(initial); setSelection(initial.length) }
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        ll.addView(edit)

        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(16), 0, 0)
        }
        btnRow.addView(Button(this).apply {
            text = "取消"
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { finish() }
        })
        btnRow.addView(Button(this).apply {
            text = "保存"
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                val t = edit.text.toString().trim()
                if (t.isEmpty()) {
                    Toast.makeText(this@PhraseEditActivity, "内容不能为空", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                val ds = DataStore(this@PhraseEditActivity)
                when (mode) {
                    MODE_FOLDER -> ds.addFolder(t)
                    MODE_RENAME_FOLDER -> if (folderUuid != null) ds.renameFolder(folderUuid, t)
                    MODE_EDIT_PHRASE -> if (phraseUuid != null) ds.updatePhrase(phraseUuid, t)
                    else -> ds.addPhrase(folderUuid, t)
                }
                Toast.makeText(this@PhraseEditActivity, "已保存", Toast.LENGTH_SHORT).show()
                finish()
            }
        })
        ll.addView(btnRow)

        setContentView(ll)

        // 进入即聚焦输入框并弹出键盘
        edit.requestFocus()
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        const val EXTRA_MODE = "mode"
        const val EXTRA_TITLE = "title"
        const val EXTRA_FOLDER_UUID = "folder_uuid"
        const val EXTRA_PHRASE_UUID = "phrase_uuid"
        const val EXTRA_MULTILINE = "multiline"
        const val EXTRA_INITIAL = "initial"
        const val MODE_FOLDER = "folder"
        const val MODE_PHRASE = "phrase"
        const val MODE_RENAME_FOLDER = "rename_folder"
        const val MODE_EDIT_PHRASE = "edit_phrase"
    }
}
