package com.todayeat.app.update

import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** 线上版本信息：https://<server>/todayeat-version.json */
const val VERSION_PATH = "/todayeat-version.json"
const val APK_PATH = "/download/todayeat.apk"

data class UpdateOffer(
    val versionCode: Int,
    val versionName: String,
    val notes: String,
    val pageUrl: String,
    val apkUrl: String,
    val sha256: String
)

/** 解析版本 JSON；缺省 apkUrl 时回落到服务器固定下载地址 */
fun parseOffer(body: String, serverUrl: String): UpdateOffer {
    val o = JSONObject(body)
    val server = serverUrl.trimEnd('/')
    return UpdateOffer(
        versionCode = o.getInt("versionCode"),
        versionName = o.optString("versionName", "").ifBlank { o.getInt("versionCode").toString() },
        notes = o.optString("notes", ""),
        pageUrl = o.optString("url", ""),
        apkUrl = o.optString("apkUrl", "").trim().ifBlank { server + APK_PATH },
        sha256 = o.optString("sha256", "").trim().lowercase()
    )
}

/** 服务器没公布 sha256 时只校验 APK 头 */
fun sha256Matches(actualHex: String, expected: String): Boolean =
    expected.isBlank() || actualHex.equals(expected.trim(), ignoreCase = true)

/** APK 本质是 zip，开头为 "PK" */
fun looksLikeApk(header: ByteArray): Boolean =
    header.size >= 2 && header[0] == 0x50.toByte() && header[1] == 0x4B.toByte()

fun sha256Hex(file: File): String {
    val dig = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buf = ByteArray(16 * 1024)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            dig.update(buf, 0, n)
        }
    }
    return dig.digest().joinToString("") { "%02x".format(it) }
}

fun verifyApk(file: File, sha256: String): Boolean {
    if (!file.exists() || file.length() < 4) return false
    val header = ByteArray(4)
    file.inputStream().use { if (it.read(header) < 2) return false }
    if (!looksLikeApk(header)) return false
    return sha256Matches(sha256Hex(file), sha256)
}
