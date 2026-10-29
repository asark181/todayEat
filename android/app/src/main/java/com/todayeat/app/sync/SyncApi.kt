package com.todayeat.app.sync

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** 服务器拒绝（有 HTTP 状态码）；[detail] 是服务端给的中文提示 */
class ApiException(val code: Int, val detail: String) : IOException(detail)

/** /api/te 接口，见 docs/SPEC.md 第 3 节 */
class SyncApi(private val baseUrl: String) {

    class Remote(val version: Int, val blob: String?)

    fun register(username: String, auth: String, device: String): Pair<String, String> =
        authCall("/api/te/auth/register", username, auth, device)

    fun login(username: String, auth: String, device: String): Pair<String, String> =
        authCall("/api/te/auth/login", username, auth, device)

    /** 返回 (token, 服务器上的用户名) */
    private fun authCall(path: String, username: String, auth: String, device: String): Pair<String, String> {
        val body = JSONObject().put("username", username).put("auth", auth).put("device", device).toString()
        val o = json("POST", path, null, body)
        return o.getString("token") to o.getJSONObject("user").getString("username")
    }

    fun logout(token: String) {
        json("POST", "/api/te/auth/logout", token, "{}")
    }

    fun getSnapshot(token: String): Remote {
        val o = json("GET", "/api/te/sync", token, null)
        return Remote(o.optInt("version", 0), if (o.isNull("blob")) null else o.optString("blob"))
    }

    /** 返回新版本号；云端已被别的设备更新时抛 409 */
    fun putSnapshot(token: String, baseVersion: Int, blob: String): Int {
        val body = JSONObject().put("base_version", baseVersion).put("blob", blob).toString()
        return json("PUT", "/api/te/sync", token, body).getInt("version")
    }

    fun listPhotos(token: String): Set<String> {
        val arr = json("GET", "/api/te/photos", token, null).getJSONArray("ids")
        return (0 until arr.length()).map { arr.getString(it) }.toSet()
    }

    fun putPhoto(token: String, id: String, sealed: ByteArray) {
        val conn = open("PUT", "/api/te/photos/$id", token)
        conn.setRequestProperty("Content-Type", "application/octet-stream")
        conn.doOutput = true
        conn.setFixedLengthStreamingMode(sealed.size)
        conn.outputStream.use { it.write(sealed) }
        finish(conn)
    }

    fun getPhoto(token: String, id: String): ByteArray {
        val conn = open("GET", "/api/te/photos/$id", token)
        try {
            val code = conn.responseCode
            if (code != 200) throw ApiException(code, readError(conn))
            return conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }

    private fun open(method: String, path: String, token: String?): HttpURLConnection =
        (URL(baseUrl.trimEnd('/') + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("Accept", "application/json")
            if (token != null) setRequestProperty("Authorization", "Bearer $token")
        }

    private fun json(method: String, path: String, token: String?, body: String?): JSONObject {
        val conn = open(method, path, token)
        if (body != null) {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        }
        return finish(conn)
    }

    private fun finish(conn: HttpURLConnection): JSONObject {
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw ApiException(code, readError(conn))
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            return runCatching { JSONObject(text) }.getOrElse { JSONObject() }
        } finally {
            conn.disconnect()
        }
    }

    private fun readError(conn: HttpURLConnection): String {
        val text = runCatching { conn.errorStream?.bufferedReader()?.use { it.readText() } }.getOrNull().orEmpty()
        val detail = runCatching { JSONObject(text).optString("detail") }.getOrNull()
        return detail?.takeIf { it.isNotBlank() } ?: when (conn.responseCode) {
            401 -> "登录已失效，请重新登录"
            404 -> "服务器上没有这个接口"
            in 500..599 -> "服务器出了点问题，稍后再试"
            else -> "请求失败（${conn.responseCode}）"
        }
    }
}
