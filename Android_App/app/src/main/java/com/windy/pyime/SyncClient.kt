package com.windy.pyime

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 与 Cloudflare Worker 同步后端通信(账号体系版)。
 *
 * 协议:
 *   POST /register  头 X-Admin-Key   body {username,password}  仅管理员可注册
 *   POST /login                      body {username,password}  -> {token,username}
 *   POST /pull      头 X-Auth-Token                            -> {clipboard,folders,phrases}
 *   POST /push      头 X-Auth-Token  body {folders,phrases} 写入/更新;
 *                                    body {del:{folders:[uuid],phrases:[uuid]}} 按 uuid 硬删除
 *
 * pull/push 需要先 login 拿到 token 并用本实例的 token 字段。
 * 所有方法均为阻塞网络调用,必须在后台线程使用。
 */
class SyncClient(baseUrl: String, private val token: String? = null) {
    private val base = baseUrl.trimEnd('/')

    class RemoteData(
        val clipboard: JSONArray,
        val folders: JSONArray,
        val phrases: JSONArray,
    )

    /** 管理员注册新账号(adminKey = Worker 的 ADMIN_KEY)。成功返回 null,失败返回错误信息。 */
    fun register(adminKey: String, username: String, password: String): String? = try {
        post("/register", JSONObject().apply {
            put("username", username); put("password", password)
        }, headers = mapOf("X-Admin-Key" to adminKey))
        null
    } catch (e: Exception) {
        e.message ?: "注册失败"
    }

    /** 登录,成功返回 token,失败抛异常(异常信息为服务器返回的错误文案)。 */
    fun login(username: String, password: String): String {
        val o = post("/login", JSONObject().apply {
            put("username", username); put("password", password)
        })
        val t = o.optString("token")
        if (t.isEmpty()) throw RuntimeException("服务器未返回 token")
        return t
    }

    fun pull(): RemoteData {
        val o = post("/pull", JSONObject(), authed = true)
        return RemoteData(
            o.optJSONArray("clipboard") ?: JSONArray(),
            o.optJSONArray("folders") ?: JSONArray(),
            o.optJSONArray("phrases") ?: JSONArray(),
        )
    }

    /** 上传文件夹与常用语(剪贴板不参与同步)。 */
    fun push(folders: JSONArray, phrases: JSONArray) {
        post("/push", JSONObject().apply {
            put("folders", folders)
            put("phrases", phrases)
        }, authed = true)
    }

    /** 删除云端的文件夹/常用语(按 uuid 硬删除);参数为各自的 uuid 字符串数组。 */
    fun pushDelete(folders: JSONArray, phrases: JSONArray) {
        post("/push", JSONObject().apply {
            put("del", JSONObject().apply {
                put("folders", folders)
                put("phrases", phrases)
            })
        }, authed = true)
    }

    /** 发 POST 请求并返回响应 JSON;非 2xx 时抛出携带服务器 error 文案的异常。 */
    private fun post(
        path: String,
        body: JSONObject,
        authed: Boolean = false,
        headers: Map<String, String> = emptyMap(),
    ): JSONObject {
        val conn = (URL(base + path).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15000
            readTimeout = 20000
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            if (authed) setRequestProperty("X-Auth-Token", token ?: "")
            for ((k, v) in headers) setRequestProperty(k, v)
        }
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                val msg = try { JSONObject(text).optString("error") } catch (e: Exception) { "" }
                throw RuntimeException(if (msg.isNotEmpty()) msg else "HTTP $code")
            }
            return if (text.isBlank()) JSONObject() else JSONObject(text)
        } finally {
            conn.disconnect()
        }
    }
}
