package com.teacher.shotlab

import android.content.Context
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.graphics.ImageFormat
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 一个物理 / 逻辑摄像头。手机上通常同时存在超广角、主摄、长焦，
 * 教学时要明确用的是哪一颗，尤其主摄（1x）才是拍摄主力。
 */
data class LensOption(
    val cameraId: String,        // 可传给 cameraManager.openCamera 的逻辑摄像头 id
    val physicalId: String? = null, // 逻辑多摄下的物理子镜头 id；null = 直接打开逻辑摄像头
    val facing: Int,
    val eqFocal: Float,      // 35mm 等效焦距，0 表示读不到
    val label: String,
    val manualSensor: Boolean
) {
    val display: String
        get() = if (eqFocal > 0f) "$label ${eqFocal.roundToInt()}mm" else label
}

object Lenses {

    /** 去重后的可用镜头（供变焦档/切换使用）；OPPO 等机型每颗镜头经多摄+独立逻辑摄像头双重暴露，这里只保留可真正切过去的独立逻辑摄像头 */
    fun enumerate(context: Context): List<LensOption> = dedupeSameFocal(rawList(context))

    /** 去重前的全部检测镜头（含重复暴露项），供「镜头信息」面板展示，确认设备到底暴露了几颗、各自焦距/路径 */
    fun enumerateAll(context: Context): List<LensOption> = rawList(context)

    private fun rawList(context: Context): List<LensOption> {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            ?: return emptyList()
        val result = mutableListOf<LensOption>()
        for (id in manager.cameraIdList) {
            val chars = runCatching { manager.getCameraCharacteristics(id) }.getOrNull() ?: continue
            val facing = chars.get(CameraCharacteristics.LENS_FACING) ?: continue
            // 手动传感器能力是「逻辑摄像头」级别的属性；物理子镜头通常不单独上报，
            // 必须取自逻辑相机，否则主摄会被误判为「不支持手动」。
            val logicalManual = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                ?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR) == true
            // 高端机（如 OPPO Find X7 Ultra）把超广角 / 主摄 / 长焦挂在同一个逻辑摄像头下，
            // 只靠 getCameraCharacteristics(id) 读到的是逻辑镜头（等效焦距通常取到超广角），
            // 长焦根本不会作为独立选项出现。必须枚举物理子镜头、用其真实焦距单独成项。
            val physIds = Camera2Reflect.physicalCameraIds(chars)
            // 过滤掉逻辑 id 自身：单摄逻辑摄像头调 getPhysicalCameraIds 只返回自己，若不过滤会重复枚举同一颗镜头。
            val realPhys = physIds?.filter { it != id }?.filter { it.isNotBlank() }
            if (!realPhys.isNullOrEmpty()) {
                for (pid in realPhys) {
                    val pchars = Camera2Reflect.physicalCharacteristics(manager, id, pid) ?: continue
                    val opt = buildOption(id, pid, facing, pchars, logicalManual) ?: continue
                    result += opt
                }
            } else {
                val opt = buildOption(id, null, facing, chars, logicalManual) ?: continue
                result += opt
            }
        }
        return result
    }

    /**
     * 同焦段去重：部分机型（OPPO Find X8 Pro 等）既把超广角/主摄/长焦挂在同一个逻辑多摄下
     * （物理子镜头 #2/#3/#4），又额外暴露每颗镜头的**独立逻辑摄像头**（#2/#3/#4）。
     * 两者焦段相同，但独立摄像头可以直接 openCamera 打开；物理子镜头则依赖请求级
     * setPhysicalCameraId —— 部分 HAL 对预览流会忽略该 id，造成「选项在、切换不生效」。
     * 因此同焦段优先保留独立逻辑摄像头（保证每个焦段都能真正切过去），
     * 没有独立摄像头的机型自然回落到物理子镜头路径。
     */
    private fun dedupeSameFocal(all: List<LensOption>): List<LensOption> {
        val back = all.filter { it.facing == CameraCharacteristics.LENS_FACING_BACK }
        val front = all.filter { it.facing != CameraCharacteristics.LENS_FACING_BACK }
        val deduped = back
            // 按 2mm 档聚类：同一颗镜头的两条路径读数略有出入（如 24.5 与 25.1）应合并，
            // 而真实不同的镜头（如 24 与 26）不该被误并
            .groupBy { (it.eqFocal / 2f).roundToInt() }
            .map { (_, group) ->
                group.sortedWith(
                    compareBy(
                        { it.physicalId != null },   // 独立逻辑摄像头优先
                        { !it.manualSensor }          // 其次保留支持手动的
                    )
                ).first()
            }
            .sortedBy { it.eqFocal }
        return deduped + front
    }

    /**
     * 由一组相机特性构造镜头选项。镜头类型（超广角/主摄/长焦）按等效焦距判定，
     * 设备真实支持几颗物理镜头就给出几项，不补充、不置灰。
     * 跳过无成像能力 / 无可用输出流的传感器（如 ToF、IR），它们没有 JPEG 或预览输出尺寸。
     */
    private fun buildOption(
        cameraId: String,
        physicalId: String?,
        facing: Int,
        chars: CameraCharacteristics,
        logicalManual: Boolean
    ): LensOption? {
        val focal = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: return null
        val sensor = chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: return null
        if (sensor.width <= 0f) return null
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val streamable = map?.getOutputSizes(ImageFormat.JPEG)?.isNotEmpty() == true
            || map?.getOutputSizes(SurfaceTexture::class.java)?.isNotEmpty() == true
        if (!streamable) return null
        val eq = focal / sensor.width * 36f
        return LensOption(
            cameraId = cameraId,
            physicalId = physicalId,
            facing = facing,
            eqFocal = eq,
            label = labelOf(eq),
            manualSensor = logicalManual
        )
    }

    fun backLenses(all: List<LensOption>): List<LensOption> =
        all.filter { it.facing == CameraCharacteristics.LENS_FACING_BACK }

    /** 主摄优先：等效焦距最接近 26mm（主流主摄）且支持手动；退化到最接近 26mm 的后置镜头。
     *  不用 label == "主摄" 字符串匹配，避免部分机型等效焦距算出界外时把超广角误当主摄（即"1x 像 0.6x"的根因）。 */
    fun pickMain(all: List<LensOption>): LensOption? {
        val back = backLenses(all)
        if (back.isEmpty()) return all.firstOrNull()
        val byMain = back.sortedBy { abs(it.eqFocal - 26f) }
        return byMain.firstOrNull { it.manualSensor }
            ?: byMain.firstOrNull()
            ?: back.first()
    }

    fun frontLens(all: List<LensOption>): LensOption? =
        all.firstOrNull { it.facing == CameraCharacteristics.LENS_FACING_FRONT }

    /**
     * 主摄基准的光学变焦倍率选项：主摄 = 1x，其余后置镜头按等效焦距换算
     * （超广角约 0.6x，长焦 2x / 5x…）。返回的即「设备真实支持的光学倍率」。
     */
    data class ZoomOption(val label: String, val ratio: Float, val lens: LensOption)

    fun zoomOptions(all: List<LensOption>): List<ZoomOption> {
        val main = pickMain(all) ?: return emptyList()
        if (main.eqFocal <= 0f) return emptyList()
        val back = backLenses(all).filter { it.eqFocal > 0f }
        if (back.isEmpty()) return emptyList()
        return back.map { it to it.eqFocal / main.eqFocal }
            .groupBy { (it.second * 10).roundToInt() }
            .map { (_, list) ->
                val r10 = (list.first().second * 10).roundToInt()
                val ratio = r10 / 10f
                val label = if (r10 % 10 == 0) "${r10 / 10}x" else "${r10 / 10.0}x"
                ZoomOption(label, ratio, list.first().first)
            }
            .sortedBy { it.ratio }
    }

    private fun labelOf(eq: Float): String = when {
        eq <= 0f -> "后置"
        eq < 20f -> "超广角"
        eq <= 38f -> "主摄"
        eq <= 75f -> "长焦"
        else -> "超长焦"
    }
}
