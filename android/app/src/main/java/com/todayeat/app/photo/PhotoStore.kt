package com.todayeat.app.photo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import com.todayeat.app.data.newId
import java.io.File

/**
 * 照片存在 filesDir/photos/<id>.jpg。导入时按 EXIF 摆正、长边压到 1600、JPEG 82，
 * 一张一般 200~400KB，加密后上传云端。
 */
class PhotoStore(private val context: Context) {
    private val dir = File(context.filesDir, "photos").also { it.mkdirs() }

    fun file(id: String) = File(dir, "$id.jpg")
    fun has(id: String) = file(id).let { it.exists() && it.length() > 0 }
    fun allIds(): Set<String> = dir.listFiles()?.map { it.nameWithoutExtension }?.toSet().orEmpty()

    /** 给相机用的临时文件地址 */
    fun newCameraUri(): Pair<Uri, File> {
        val tmp = File(context.cacheDir, "camera").also { it.mkdirs() }.let { File(it, "shot-${System.currentTimeMillis()}.jpg") }
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", tmp) to tmp
    }

    /** 读入、摆正、压缩，返回新照片 id；读不了返回 null */
    fun import(uri: Uri): String? = runCatching {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= LONG_EDGE) sample *= 2
        val raw = resolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null
        val rotation = resolver.openInputStream(uri)?.use {
            when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } ?: 0f
        val scale = minOf(1f, LONG_EDGE.toFloat() / maxOf(raw.width, raw.height))
        val m = Matrix().apply { postScale(scale, scale); postRotate(rotation) }
        val out = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true)
        val id = newId()
        file(id).outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, 82, it) }
        if (out !== raw) raw.recycle()
        out.recycle()
        id
    }.getOrNull()

    fun write(id: String, bytes: ByteArray) {
        val tmp = File(dir, "$id.part")
        tmp.writeBytes(bytes)
        tmp.renameTo(file(id))
    }

    fun delete(id: String) {
        file(id).delete()
    }

    /** 缩略图：按目标宽度采样解码 */
    fun decode(id: String, targetPx: Int): Bitmap? {
        val f = file(id)
        if (!f.exists()) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, bounds)
        var sample = 1
        while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= targetPx) sample *= 2
        return BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    companion object {
        private const val LONG_EDGE = 1600
    }
}
