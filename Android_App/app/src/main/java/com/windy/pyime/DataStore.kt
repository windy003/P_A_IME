package com.windy.pyime

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * 剪贴板历史 + 常用语(含文件夹)的本地 SQLite 存储。
 *
 * 三张表都带 uuid(稳定主键)、updated_at(毫秒)、deleted(软删除 0/1),
 * 为第二阶段与 Cloudflare D1 的时间戳增量双向同步、删除传播预留结构。
 */
class DataStore(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    // ---- 数据模型 ----
    data class Clip(val uuid: String, val content: String, val updatedAt: Long)
    data class Folder(val uuid: String, val name: String, val updatedAt: Long)
    data class Phrase(
        val uuid: String,
        val folderUuid: String?,
        val content: String,
        val lastUsedAt: Long,
        val updatedAt: Long,
    )

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE clipboard (" +
                "uuid TEXT PRIMARY KEY, content TEXT NOT NULL, " +
                "updated_at INTEGER NOT NULL, deleted INTEGER NOT NULL DEFAULT 0)"
        )
        db.execSQL(
            "CREATE TABLE phrase_folder (" +
                "uuid TEXT PRIMARY KEY, name TEXT NOT NULL, " +
                "sort_order INTEGER NOT NULL DEFAULT 0, " +
                "updated_at INTEGER NOT NULL, deleted INTEGER NOT NULL DEFAULT 0)"
        )
        db.execSQL(
            "CREATE TABLE phrase (" +
                "uuid TEXT PRIMARY KEY, folder_uuid TEXT, content TEXT NOT NULL, " +
                "last_used_at INTEGER NOT NULL DEFAULT 0, " +
                "sort_order INTEGER NOT NULL DEFAULT 0, " +
                "updated_at INTEGER NOT NULL, deleted INTEGER NOT NULL DEFAULT 0)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // v2:常用语条目支持拖动排序,新增 sort_order 列
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE phrase ADD COLUMN sort_order INTEGER NOT NULL DEFAULT 0")
        }
        // v3:文件夹也支持拖动排序
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE phrase_folder ADD COLUMN sort_order INTEGER NOT NULL DEFAULT 0")
        }
    }

    private fun now() = System.currentTimeMillis()
    private fun newUuid() = UUID.randomUUID().toString()

    // ---------------------------------------------------------------- 剪贴板
    /** 最近的剪贴板条目(未删除),按更新时间倒序。 */
    fun recentClips(limit: Int = 50): List<Clip> {
        val out = ArrayList<Clip>()
        readableDatabase.rawQuery(
            "SELECT uuid, content, updated_at FROM clipboard " +
                "WHERE deleted = 0 ORDER BY updated_at DESC LIMIT ?",
            arrayOf(limit.toString())
        ).use { c ->
            while (c.moveToNext()) out.add(Clip(c.getString(0), c.getString(1), c.getLong(2)))
        }
        return out
    }

    /**
     * 把内容存入剪贴板历史并置顶:
     * 若已存在相同内容(未删除),只刷新它的 updated_at;否则新插入一条。
     */
    fun upsertClipTop(content: String) {
        val db = writableDatabase
        val ts = now()
        db.rawQuery(
            "SELECT uuid FROM clipboard WHERE content = ? AND deleted = 0 LIMIT 1",
            arrayOf(content)
        ).use { c ->
            if (c.moveToNext()) {
                val uuid = c.getString(0)
                db.update("clipboard", ContentValues().apply {
                    put("updated_at", ts)
                }, "uuid = ?", arrayOf(uuid))
                return
            }
        }
        db.insert("clipboard", null, ContentValues().apply {
            put("uuid", newUuid())
            put("content", content)
            put("updated_at", ts)
            put("deleted", 0)
        })
    }

    /** 点击某条后置顶。 */
    fun touchClip(uuid: String) {
        writableDatabase.update("clipboard", ContentValues().apply {
            put("updated_at", now())
        }, "uuid = ?", arrayOf(uuid))
    }

    /** 硬删除:剪贴板不参与同步,直接从表中删除即可(无需墓碑)。 */
    fun deleteClip(uuid: String) {
        writableDatabase.delete("clipboard", "uuid = ?", arrayOf(uuid))
    }

    // ---------------------------------------------------------------- 文件夹
    fun folders(): List<Folder> {
        val out = ArrayList<Folder>()
        readableDatabase.rawQuery(
            "SELECT uuid, name, updated_at FROM phrase_folder " +
                "WHERE deleted = 0 ORDER BY sort_order ASC, updated_at ASC",
            null
        ).use { c ->
            while (c.moveToNext()) out.add(Folder(c.getString(0), c.getString(1), c.getLong(2)))
        }
        return out
    }

    fun addFolder(name: String): String {
        val db = writableDatabase
        val uuid = newUuid()
        db.insert("phrase_folder", null, ContentValues().apply {
            put("uuid", uuid)
            put("name", name)
            put("sort_order", nextFolderSortOrder(db))   // 排到末尾
            put("updated_at", now())
            put("deleted", 0)
        })
        return uuid
    }

    /** 当前最大文件夹 sort_order + 1。 */
    private fun nextFolderSortOrder(db: SQLiteDatabase): Long {
        db.rawQuery(
            "SELECT COALESCE(MAX(sort_order), -1) + 1 FROM phrase_folder WHERE deleted = 0", null
        ).use { c -> if (c.moveToNext()) return c.getLong(0) }
        return 0
    }

    /** 按给定 uuid 顺序重写文件夹 sort_order(拖动排序后调用)。不改 updated_at,排序不参与同步。 */
    fun reorderFolders(orderedUuids: List<String>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            orderedUuids.forEachIndexed { i, uuid ->
                db.update("phrase_folder", ContentValues().apply { put("sort_order", i) },
                    "uuid = ?", arrayOf(uuid))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** 重命名文件夹。 */
    fun renameFolder(uuid: String, name: String) {
        writableDatabase.update("phrase_folder", ContentValues().apply {
            put("name", name)
            put("updated_at", now())
        }, "uuid = ?", arrayOf(uuid))
    }

    /** 软删除文件夹(留墓碑供同步),并连带硬删除其下所有常用语条目。 */
    fun deleteFolder(uuid: String) {
        val db = writableDatabase
        db.update("phrase_folder", ContentValues().apply {
            put("deleted", 1); put("updated_at", now())
        }, "uuid = ?", arrayOf(uuid))
        db.delete("phrase", "folder_uuid = ?", arrayOf(uuid))
    }

    // ---------------------------------------------------------------- 常用语
    private fun readPhrases(sql: String, args: Array<String>?): List<Phrase> {
        val out = ArrayList<Phrase>()
        readableDatabase.rawQuery(sql, args).use { c ->
            while (c.moveToNext()) {
                out.add(
                    Phrase(
                        uuid = c.getString(0),
                        folderUuid = if (c.isNull(1)) null else c.getString(1),
                        content = c.getString(2),
                        lastUsedAt = c.getLong(3),
                        updatedAt = c.getLong(4),
                    )
                )
            }
        }
        return out
    }

    fun phrasesIn(folderUuid: String?): List<Phrase> {
        val base = "SELECT uuid, folder_uuid, content, last_used_at, updated_at FROM phrase " +
            "WHERE deleted = 0 AND "
        // sort_order 为手动拖动排序;相同时(如旧数据)再按 updated_at 保持原顺序
        val order = "ORDER BY sort_order ASC, updated_at ASC"
        return if (folderUuid == null) {
            readPhrases("$base folder_uuid IS NULL $order", null)
        } else {
            readPhrases("$base folder_uuid = ? $order", arrayOf(folderUuid))
        }
    }

    /** 「最近」面板:点选过(last_used_at>0)的常用语,按点选时间倒序。 */
    fun recentPhrases(limit: Int = 30): List<Phrase> {
        return readPhrases(
            "SELECT uuid, folder_uuid, content, last_used_at, updated_at FROM phrase " +
                "WHERE deleted = 0 AND last_used_at > 0 ORDER BY last_used_at DESC LIMIT $limit",
            null
        )
    }

    fun addPhrase(folderUuid: String?, content: String): String {
        val db = writableDatabase
        val uuid = newUuid()
        db.insert("phrase", null, ContentValues().apply {
            put("uuid", uuid)
            if (folderUuid == null) putNull("folder_uuid") else put("folder_uuid", folderUuid)
            put("content", content)
            put("last_used_at", 0)
            put("sort_order", nextSortOrder(db, folderUuid))   // 排到本文件夹末尾
            put("updated_at", now())
            put("deleted", 0)
        })
        return uuid
    }

    /** 取某文件夹内当前最大 sort_order + 1,作为新条目的排序值。 */
    private fun nextSortOrder(db: SQLiteDatabase, folderUuid: String?): Long {
        val sql = "SELECT COALESCE(MAX(sort_order), -1) + 1 FROM phrase " +
            "WHERE deleted = 0 AND " +
            if (folderUuid == null) "folder_uuid IS NULL" else "folder_uuid = ?"
        val args = if (folderUuid == null) null else arrayOf(folderUuid)
        db.rawQuery(sql, args).use { c -> if (c.moveToNext()) return c.getLong(0) }
        return 0
    }

    /** 按给定 uuid 顺序重写 sort_order(拖动排序后调用)。不改 updated_at。 */
    fun reorderPhrases(orderedUuids: List<String>) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            orderedUuids.forEachIndexed { i, uuid ->
                db.update("phrase", ContentValues().apply { put("sort_order", i) },
                    "uuid = ?", arrayOf(uuid))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    /** 点选常用语后更新最近使用时间。 */
    fun touchPhrase(uuid: String) {
        writableDatabase.update("phrase", ContentValues().apply {
            put("last_used_at", now())
        }, "uuid = ?", arrayOf(uuid))
    }

    /** 修改常用语内容。 */
    fun updatePhrase(uuid: String, content: String) {
        writableDatabase.update("phrase", ContentValues().apply {
            put("content", content)
            put("updated_at", now())
        }, "uuid = ?", arrayOf(uuid))
    }

    /** 硬删除:常用语不留软删除墓碑,直接从表中删除。 */
    fun deletePhrase(uuid: String) {
        writableDatabase.delete("phrase", "uuid = ?", arrayOf(uuid))
    }

    // ---------------------------------------------------------------- 同步导出/导入
    /** 导出全部行(含已删除),供与云端比较/上传。字段名即列名。 */
    fun exportFolders(): JSONArray =
        queryToJson("SELECT uuid, name, sort_order, updated_at, deleted FROM phrase_folder")

    fun exportPhrases(): JSONArray =
        queryToJson(
            "SELECT uuid, folder_uuid, content, last_used_at, sort_order, updated_at, deleted FROM phrase"
        )

    private fun queryToJson(sql: String): JSONArray {
        val arr = JSONArray()
        readableDatabase.rawQuery(sql, null).use { c ->
            val cols = c.columnNames
            while (c.moveToNext()) {
                val o = JSONObject()
                for (i in cols.indices) {
                    when (c.getType(i)) {
                        Cursor.FIELD_TYPE_NULL -> o.put(cols[i], JSONObject.NULL)
                        Cursor.FIELD_TYPE_INTEGER -> o.put(cols[i], c.getLong(i))
                        else -> o.put(cols[i], c.getString(i))
                    }
                }
                arr.put(o)
            }
        }
        return arr
    }

    /** 排序不参与同步:已存在的文件夹只覆盖内容字段、保留本地 sort_order;新行排到本地末尾。 */
    fun applyFolderRow(o: JSONObject) {
        val db = writableDatabase
        val uuid = o.getString("uuid")
        val cv = ContentValues().apply {
            put("name", o.getString("name"))
            put("updated_at", o.optLong("updated_at"))
            put("deleted", o.optInt("deleted"))
        }
        val rows = db.update("phrase_folder", cv, "uuid = ?", arrayOf(uuid))
        if (rows == 0) {
            cv.put("uuid", uuid)
            cv.put("sort_order", nextFolderSortOrder(db))
            db.insert("phrase_folder", null, cv)
        }
    }

    /** 同上:已存在的常用语保留本地 sort_order,只覆盖内容/归属/时间;新行排到对应文件夹末尾。 */
    fun applyPhraseRow(o: JSONObject) {
        val db = writableDatabase
        val uuid = o.getString("uuid")
        val folderUuid = if (o.isNull("folder_uuid")) null else o.getString("folder_uuid")
        val cv = ContentValues().apply {
            if (folderUuid == null) putNull("folder_uuid") else put("folder_uuid", folderUuid)
            put("content", o.getString("content"))
            put("last_used_at", o.optLong("last_used_at"))
            put("updated_at", o.optLong("updated_at"))
            put("deleted", o.optInt("deleted"))
        }
        val rows = db.update("phrase", cv, "uuid = ?", arrayOf(uuid))
        if (rows == 0) {
            cv.put("uuid", uuid)
            cv.put("sort_order", nextSortOrder(db, folderUuid))
            db.insert("phrase", null, cv)
        }
    }

    /** 切换账号登录时清空本机同步数据(三张表整表删除),改用新账号的云端数据。 */
    fun clearSyncTables() {
        val db = writableDatabase
        db.execSQL("DELETE FROM clipboard")
        db.execSQL("DELETE FROM phrase_folder")
        db.execSQL("DELETE FROM phrase")
    }

    companion object {
        const val DB_NAME = "pyime_data.db"
        const val DB_VERSION = 3
    }
}
