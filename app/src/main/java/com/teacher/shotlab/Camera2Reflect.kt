package com.teacher.shotlab

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import java.lang.reflect.Method

/**
 * 本机编译桩（android-35）缺失部分 Camera2 多摄 API：
 *  - CameraManager.getPhysicalCameraCharacteristics(String, String)（API 28+）
 *  - CaptureRequest.Builder.setPhysicalCameraId(String)（API 28+）
 *  - CameraCharacteristics.PHYSICAL_CAMERA_IDS / getPhysicalCameraIds()（API 28+）
 * 这些在真机（Android 10+，含 OPPO Find X7 Ultra）运行时均存在，仅编译桩不完整。
 * 用反射访问：既能通过当前 SDK 编译，又能在真机生效；方法缺失时自动退化，不会崩溃。
 */

/** 把请求锁定到指定物理子镜头；physId 为 null 或方法不可用时为无操作（顶层扩展，便于直接调用） */
private val setPhysIdMethod: Method? by lazy {
    runCatching {
        CaptureRequest.Builder::class.java.getMethod("setPhysicalCameraId", String::class.java)
    }.getOrNull()
}

fun CaptureRequest.Builder.setPhysicalCameraIdSafe(physId: String?) {
    if (physId == null) return
    setPhysIdMethod?.let { m -> runCatching { m.invoke(this, physId) } }
}

object Camera2Reflect {

    private val physCharsMethod: Method? by lazy {
        runCatching {
            CameraManager::class.java.getMethod(
                "getPhysicalCameraCharacteristics",
                String::class.java, String::class.java
            )
        }.getOrNull()
    }

    /** 读取逻辑摄像头下某物理子镜头的特性；退化路径：部分 HAL 允许直接用物理 id 调 getCameraCharacteristics */
    fun physicalCharacteristics(
        manager: CameraManager,
        logicalId: String,
        physId: String
    ): CameraCharacteristics? {
        if (physCharsMethod != null) {
            val r = runCatching { physCharsMethod!!.invoke(manager, logicalId, physId) as? CameraCharacteristics }
                .getOrNull()
            if (r != null) return r
        }
        return runCatching { manager.getCameraCharacteristics(physId) }.getOrNull()
    }

    private val physIdsKey: Any? by lazy {
        runCatching { CameraCharacteristics::class.java.getField("PHYSICAL_CAMERA_IDS").get(null) }.getOrNull()
    }
    private val getKeyMethod: Method? by lazy {
        runCatching {
            CameraCharacteristics::class.java.getMethod(
                "get", Class.forName("android.hardware.camera2.CameraCharacteristics\$Key")
            )
        }.getOrNull()
    }
    private val getPhysIdsMethod: Method? by lazy {
        runCatching { CameraCharacteristics::class.java.getMethod("getPhysicalCameraIds") }.getOrNull()
    }

    /** 返回逻辑摄像头下挂的物理子镜头 id；无法读取时返回 null */
    fun physicalCameraIds(chars: CameraCharacteristics): Array<out String>? {
        if (physIdsKey != null && getKeyMethod != null) {
            val arr = runCatching { getKeyMethod!!.invoke(chars, physIdsKey) as? Array<*> }
                .getOrNull()
                ?.filterIsInstance<String>()
                ?.toTypedArray()
            if (!arr.isNullOrEmpty()) return arr
        }
        if (getPhysIdsMethod != null) {
            val set = runCatching { getPhysIdsMethod!!.invoke(chars) }.getOrNull()
            val arr = (set as? Set<*>)?.filterIsInstance<String>()?.toTypedArray()
            if (!arr.isNullOrEmpty()) return arr
        }
        return null
    }
}
