package com.teacher.shotlab

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import java.util.Locale

data class Shot(
    val uri: Uri,
    val name: String,
    val isVideo: Boolean,
    val date: Long
)

/**
 * 素材存取：统一落在 Pictures/ShotLab 与 Movies/ShotLab 两个公共目录，
 * 用户可在系统相册里直接看到，卸载后仍保留。
 */
object ShotStore {

    private const val ALBUM = "ShotLab"

    fun imageContentValues(name: String): ContentValues = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, name)
        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/$ALBUM")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
    }

    fun videoContentValues(name: String): ContentValues = ContentValues().apply {
        put(MediaStore.Video.Media.DISPLAY_NAME, name)
        put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
        put(MediaStore.Video.Media.DATE_TAKEN, System.currentTimeMillis())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // 落到手机默认相册（系统相机所在的 DCIM/Camera），方便在系统相册里直接看到
            put(MediaStore.Video.Media.RELATIVE_PATH, "DCIM/Camera")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
    }

    /**
     * 兜底扫描：MediaStore 落库后，部分定制 ROM（如 ColorOS）不会立刻刷新相册索引。
     * 通过真实文件路径再扫一遍，确保视频出现在相册里。
     */
    fun scanFile(context: Context, uri: Uri) {
        val path = runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(MediaStore.Video.Media.DATA),
                null, null, null
            )?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull()
        if (!path.isNullOrEmpty()) {
            MediaScannerConnection.scanFile(context, arrayOf(path), arrayOf("video/mp4"), null)
        }
    }

    fun query(cr: ContentResolver): List<Shot> {
        val result = mutableListOf<Shot>()
        result += queryImages(cr)
        result += queryVideos(cr)
        return result.sortedByDescending { it.date }
    }

    private fun queryImages(cr: ContentResolver): List<Shot> {
        val out = mutableListOf<Shot>()
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_ADDED
        )
        val selection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "${MediaStore.Images.Media.BUCKET_DISPLAY_NAME} = ?"
        } else null
        val args = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) arrayOf(ALBUM) else null
        cr.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection, selection, args,
            "${MediaStore.Images.Media.DATE_ADDED} DESC"
        )?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val dateCol = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                val uri = android.content.ContentUris.withAppendedId(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id
                )
                out += Shot(uri, c.getString(nameCol), false, c.getLong(dateCol) * 1000L)
            }
        }
        return out
    }

    private fun queryVideos(cr: ContentResolver): List<Shot> {
        val out = mutableListOf<Shot>()
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DATE_ADDED
        )
        // 视频已统一存到 DCIM/Camera（默认相册），不再依赖子目录桶名；
        // 用本 App 的文件名前缀 SL_ 过滤，确保应用内相册只显示自己录制的视频
        val selection = "${MediaStore.Video.Media.DISPLAY_NAME} LIKE ?"
        val args = arrayOf("SL_%")
        cr.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            projection, selection, args,
            "${MediaStore.Video.Media.DATE_ADDED} DESC"
        )?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val dateCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                val uri = android.content.ContentUris.withAppendedId(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id
                )
                out += Shot(uri, c.getString(nameCol), true, c.getLong(dateCol) * 1000L)
            }
        }
        return out
    }
}
