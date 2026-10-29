package com.todayeat.app.update

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

class HttpStatusException(val code: Int) : IOException("http $code")

class CancelledDownload : IOException("cancelled")

/** 一次打开的响应；[total] 为完整文件大小，服务器未告知时为 -1 */
class Opened(
    val code: Int,
    val total: Long,
    val stream: InputStream,
    private val closeExtra: () -> Unit = {},
) : AutoCloseable {
    override fun close() {
        try {
            stream.close()
        } finally {
            closeExtra()
        }
    }
}

fun interface ByteSource {
    /** [rangeFrom] > 0 时请求断点续传；网络失败抛 [IOException] */
    fun open(url: String, rangeFrom: Long): Opened
}

/**
 * 下载到 [dest]（.part 临时文件），支持断点续传；网络错误时每个地址重试一次，HTTP 状态错误直接失败。
 */
class ApkFetcher(private val source: ByteSource, private val attempts: Int = 2) {
    fun fetch(urls: List<String>, dest: File, cancelled: () -> Boolean, onProgress: (Long, Long) -> Unit) {
        var network: IOException? = null
        for (url in urls) {
            var tried = 0
            while (tried < attempts) {
                if (cancelled()) throw CancelledDownload()
                tried++
                try {
                    fetchOne(url, dest, cancelled, onProgress)
                    return
                } catch (e: HttpStatusException) {
                    throw e
                } catch (e: CancelledDownload) {
                    throw e
                } catch (e: IOException) {
                    network = e
                }
            }
        }
        throw network ?: IOException("no url")
    }

    private fun fetchOne(url: String, dest: File, cancelled: () -> Boolean, onProgress: (Long, Long) -> Unit) {
        val have = if (dest.exists()) dest.length() else 0L
        source.open(url, have).use { opened ->
            val append = opened.code == 206 && have > 0
            if (!append && dest.exists()) dest.delete()
            dest.parentFile?.mkdirs()
            var written = if (append) have else 0L
            val stream = if (append) java.io.FileOutputStream(dest, true) else dest.outputStream()
            stream.use { out ->
                val buf = ByteArray(16 * 1024)
                while (true) {
                    if (cancelled()) throw CancelledDownload()
                    val n = opened.stream.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    written += n
                    onProgress(written, opened.total)
                }
            }
            onProgress(dest.length(), opened.total)
        }
    }
}

object HttpByteSource : ByteSource {
    override fun open(url: String, rangeFrom: Long): Opened {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 15_000
            readTimeout = 30_000
            if (rangeFrom > 0) setRequestProperty("Range", "bytes=$rangeFrom-")
        }
        try {
            val code = conn.responseCode
            if (code != 200 && code != 206) {
                conn.disconnect()
                throw HttpStatusException(code)
            }
            val total = when {
                code == 206 -> conn.getHeaderField("Content-Range")?.substringAfter('/')?.toLongOrNull() ?: -1L
                conn.contentLengthLong > 0 -> conn.contentLengthLong
                else -> -1L
            }
            return Opened(code, total, conn.inputStream) { conn.disconnect() }
        } catch (e: HttpStatusException) {
            throw e
        } catch (e: IOException) {
            conn.disconnect()
            throw e
        }
    }
}
