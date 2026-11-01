package com.teacher.shotlab

import android.content.ContentResolver
import android.net.Uri
import androidx.exifinterface.media.ExifInterface

/**
 * 从拍下的 JPEG 里读回硬件真正写进去的参数。
 * 用途：告诉学生「你设了 ISO 400，相机实际用了 ISO 400」——或在某些机型上「相机忽略了你的设置」。
 */
data class ActualParams(
    val iso: Int?,
    val shutterNs: Long?,
    val whiteBalance: String?,   // 自动 / 手动
    val aperture: Float?,
    val focal: Float?,
    val wbKelvin: Int? = null    // 自动模式下由 AWB 增益反推的实时色温，优先于 whiteBalance 显示
) {
    fun isEmpty(): Boolean = iso == null && shutterNs == null && whiteBalance == null && aperture == null

    fun summary(): String {
        if (isEmpty()) return "实际参数：照片未写入 EXIF（该机型未提供）"
        val parts = mutableListOf<String>()
        iso?.let { parts += "ISO $it" }
        shutterNs?.let { parts += ParamCoach.shutterLabel(it) }
        aperture?.let { parts += "f/${String.format("%.1f", it)}" }
        focal?.let { parts += "${String.format("%.0f", it)}mm" }
        whiteBalance?.let {
            parts += if (wbKelvin != null && wbKelvin > 0) "白平衡 ${wbKelvin}K" else "白平衡 $it"
        }
        return "实际生效：" + parts.joinToString(" · ")
    }
}

object ExifReader {

    fun read(cr: ContentResolver, uri: Uri): ActualParams? = runCatching {
        cr.openInputStream(uri)?.use { stream ->
            val exif = ExifInterface(stream)

            val iso = firstPositive(
                exif.getAttributeInt(ExifInterface.TAG_ISO_SPEED, -1),
                exif.getAttributeInt(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, -1),
                exif.getAttributeInt(ExifInterface.TAG_ISO_SPEED_RATINGS, -1)
            )

            val exposure = exif.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, -1.0)
            val shutterNs = if (exposure > 0.0) (exposure * 1_000_000_000.0).toLong() else null

            val wbRaw = exif.getAttributeInt(ExifInterface.TAG_WHITE_BALANCE, -1)
            val wb = when (wbRaw) {
                0 -> "自动"
                1 -> "手动"
                else -> null
            }

            val fnum = exif.getAttributeDouble(ExifInterface.TAG_F_NUMBER, -1.0)
                .takeIf { it > 0.0 }?.toFloat()

            val focal = exif.getAttributeDouble(ExifInterface.TAG_FOCAL_LENGTH, -1.0)
                .takeIf { it > 0.0 }?.toFloat()

            ActualParams(iso, shutterNs, wb, fnum, focal)
        }
    }.getOrNull()

    private fun firstPositive(vararg values: Int): Int? = values.firstOrNull { it > 0 }
}
