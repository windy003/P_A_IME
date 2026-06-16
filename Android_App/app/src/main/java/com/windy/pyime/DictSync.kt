package com.windy.pyime

import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * 词库文件(pinyin_simp.dict.yaml)同步。
 *
 * 统一用「内容哈希(SHA-256)」判断有没有更新,而不是 ETag/Last-Modified:
 *   - 下载远端文件,算它的 SHA-256;
 *   - 和本地文件的 SHA-256 比较;
 *   - 不一致 -> 有更新(changed=true,带回新内容);一致 -> 已是最新。
 *
 * 这样不依赖服务器是否支持条件请求,GitHub raw 与自建 http.server 都通用。
 * fetch()/check() 为阻塞网络调用,必须在后台线程使用。
 */
object DictSync {

    /** GitHub 词库 raw 直链。换分支/换路径时改这里即可。 */
    const val GITHUB_URL =
        "https://raw.githubusercontent.com/windy003/P_A_IME/refs/heads/main/pyime/pinyin_simp.dict.yaml"

    /**
     * @param changed 远端内容是否与本地不同(本地不存在时恒为 true)
     * @param hash    远端内容的 SHA-256(十六进制)
     * @param bytes   远端文件字节
     */
    class Result(val changed: Boolean, val hash: String, val bytes: ByteArray)

    /** 下载 url 的内容(整体读入内存)。非 2xx 抛异常。 */
    fun fetch(url: String): ByteArray {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15000
            readTimeout = 30000
            instanceFollowRedirects = true
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw RuntimeException("HTTP $code")
            return conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }

    /**
     * 下载 url 并与本地内容哈希比较。
     * @param localBytes 本地词库字节;本地无文件时传 null。
     */
    fun check(url: String, localBytes: ByteArray?): Result {
        val bytes = fetch(url)
        val remoteHash = sha256(bytes)
        val localHash = localBytes?.let { sha256(it) }
        return Result(remoteHash != localHash, remoteHash, bytes)
    }

    /** 计算字节内容的 SHA-256,返回小写十六进制字符串。 */
    fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }
}
