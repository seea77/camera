package com.teacher.shotlab

import android.graphics.ColorMatrix
import android.hardware.camera2.params.ColorSpaceTransform
import android.hardware.camera2.params.RggbChannelVector
import android.util.Range
import kotlin.math.abs

/** 当前镜头的能力：决定快门 / ISO 滑杆能给出哪些档位，以及帧率选择里哪些可点亮 */
data class CameraCaps(
    val isoRange: IntRange?,
    val exposureNs: LongRange?,
    val manualSensor: Boolean,
    val fpsRanges: List<Range<Int>> = emptyList()
)

/**
 * 手动参数的纯计算部分（不碰相机对象，便于单独验证）。
 * 实际写入 CaptureRequest 的逻辑在 Camera2Engine.applyTo。
 *
 * 白平衡与调色统一走**相机硬件**的 COLOR_CORRECTION_TRANSFORM_MATRIX（需要 MANUAL_POST_PROCESSING 能力，
 * OPPO 这类旗舰支持）。不支持的设备由应用层 RenderEffect / 照片后处理兜底（见 MainActivity / Camera2Engine）。
 * 手动白平衡采用「以自动白平衡为锚点的相对偏移」（v1.8.8）：保留厂商 CCM、仅按目标/锚点色温差缩放 R/B 增益，
 * 切换平滑不跳变、不偏色。调色用原生 setSaturation，覆盖所有设备。
 */
object ManualCamera {

    /** Tint 轴最大增益偏移幅度：±0.5 表示绿通道最多 ±50% 偏移，足够明显且不溢出 */
    private const val TINT_GAIN = 0.5f

    /** Tint → 绿通道增益因子：tint<0 偏绿（升 G）、tint>0 偏紫（降 G）。Lightroom 约定左滑绿、右滑紫。 */
    fun tintFactor(tint: Float): Float {
        val t = tint.coerceIn(-1f, 1f)
        return (1f - t * TINT_GAIN).coerceAtLeast(0.2f)
    }

    /**
     * 色温 → 「把该色温的光校成中性白」所需的 RGB 增益。
     *
     * 旧实现是 `(K-3200)/4300` 的线性斜坡（R 恒定 ≥1、B 只在 2.0→1.0 之间）：
     * 与真实色温的增益关系偏离很大，B 通道随色温的变化幅度只有真实值的约一半，
     * 于是滑杆每 1000K 的实际效果被压缩——必须把数值推到很远才看得出冷暖变化。
     *
     * 现按标准色度学流程计算：
     *   色温 → Planckian 轨迹色度 xy（Kim et al. 近似）→ xyY → XYZ → 线性 sRGB →
     *   以绿通道归一 → 取倒数。
     * 暖光（低 K）缺蓝 → 压红提蓝；冷光（高 K）缺红 → 提红压蓝，方向与幅度都符合物理。
     *
     * 注：增益的**绝对值**不影响结果——手动白平衡走的是「相对自动锚点的比例」，
     * 这里真正决定手感的是增益随色温变化的**形状**。
     */
    fun gainsForKelvin(kelvin: Int): RggbChannelVector {
        val (x, y) = planckianChromaticity(kelvin.toDouble())
        if (y <= 0.0) return RggbChannelVector(1f, 1f, 1f, 1f)
        val X = x / y          // xyY，Y = 1
        val Y = 1.0
        val Z = (1.0 - x - y) / y
        // XYZ → 线性 sRGB
        val rLin = 3.2406 * X - 1.5372 * Y - 0.4986 * Z
        val gLin = -0.9689 * X + 1.8758 * Y + 0.0415 * Z
        val bLin = 0.0557 * X - 0.2040 * Y + 1.0570 * Z
        if (rLin <= 0.0 || gLin <= 0.0 || bLin <= 0.0) return RggbChannelVector(1f, 1f, 1f, 1f)
        // 绿通道归一后取倒数；夹到实用区间，避免极暖色温算出数十倍的蓝增益
        val r = (gLin / rLin).coerceIn(0.30, 3.50)
        val b = (gLin / bLin).coerceIn(0.30, 3.50)
        return RggbChannelVector(r.toFloat(), 1.0f, 1.0f, b.toFloat())
    }

    /**
     * Planckian 轨迹上给定相关色温（K）的 xy 色度。
     * Kim et al.(2002) 三次样条近似，1667K~25000K 有效（覆盖相机白平衡的常用范围）。
     */
    private fun planckianChromaticity(cct: Double): Pair<Double, Double> {
        val t = cct.coerceIn(1667.0, 25000.0)
        val t2 = t * t
        val t3 = t2 * t
        val x = if (t < 4000.0) {
            -0.2661239e9 / t3 - 0.2343589e6 / t2 + 0.8776956e3 / t + 0.179910
        } else {
            -3.0258469e9 / t3 + 2.1070379e6 / t2 + 0.2226347e3 / t + 0.240390
        }
        val x2 = x * x
        val x3 = x2 * x
        val y = when {
            t < 2222.0 -> -1.1063814 * x3 - 1.34811020 * x2 + 2.18555832 * x - 0.20219683
            t < 4000.0 -> -0.9549476 * x3 - 1.37418593 * x2 + 2.09137015 * x - 0.16748867
            else -> 3.0817580 * x3 - 5.87338670 * x2 + 3.75112997 * x - 0.37001483
        }
        return x to y
    }

    /**
     * 以「自动白平衡色温」为锚点的相对色温偏移因子（R/B 增益比）。
     * targetK 为目标色温，anchorK 为当前自动白平衡色温（由 HAL 实际增益反推）。
     * 当 targetK == anchorK 时因子均为 1，手动与自动完全一致：切手动瞬间不跳变、不偏色。
     * 这是 v1.8.8 修复「同数值偏绿」的核心——手动不再凭空绝对设定增益，而是相对自动锚点偏移。
     */
    fun wbRelativeFactors(targetK: Int, anchorK: Int): Pair<Float, Float> {
        val a = gainsForKelvin(anchorK.coerceIn(2000, 10000))
        val t = gainsForKelvin(targetK.coerceIn(2000, 10000))
        return (t.red / a.red) to (t.blue / a.blue)
    }

    /**
     * 应用层白平衡 ColorMatrix（相对锚点偏移）：仅按 R/B 比例缩放，G 不变。
     * 用于不支持相机硬件调色（MANUAL_POST_PROCESSING）的设备——RenderEffect 预览 + 照片后处理，
     * 与硬件路径的视觉逻辑一致（都相对自动白平衡做偏移）。
     */
    fun wbMatrix(targetK: Int, anchorK: Int, tint: Float = 0f): ColorMatrix {
        val (rf, bf) = wbRelativeFactors(targetK, anchorK)
        val gf = tintFactor(tint)
        val m = FloatArray(20)
        m[0] = rf; m[6] = gf; m[12] = bf; m[18] = 1f
        return ColorMatrix(m)
    }

    /**
     * 调色矩阵：色调旋转 × 饱和度缩放，写进 COLOR_CORRECTION_TRANSFORM。
     * 与 colorMatrix 共用同一套矩阵：先原生 setSaturation（REC.709 亮度权重、亮度守恒、不会偏色），
     * 再叠加色调旋转，取出其 3x3 RGB 部分转成 rational 矩阵。
     * hueDeg = 0 且 saturation = 1 时返回单位阵（即无调色）。
     */
    fun colorTransform(hueDeg: Float, saturation: Float): ColorSpaceTransform {
        if (abs(hueDeg) < 0.01f && abs(saturation - 1f) < 0.001f) return IDENTITY_TRANSFORM
        val a = colorMatrix(saturation, hueDeg).getArray()
        // ColorMatrix 4x5 的 RGB 乘法部分位于 a[0..2] / a[5..7] / a[10..12]
        val m = floatArrayOf(a[0], a[1], a[2], a[5], a[6], a[7], a[10], a[11], a[12])
        return ColorSpaceTransform(floatToTransform(m))
    }

    /** 单位变换矩阵：仅做白平衡增益、不做额外调色时使用，避免进入 TRANSFORM_MATRIX 模式却不给变换矩阵 */
    val IDENTITY_TRANSFORM = ColorSpaceTransform(
        intArrayOf(1, 1, 0, 1, 0, 1, 0, 1, 1, 1, 0, 1, 0, 1, 0, 1, 1, 1)
    )

    /**
     * 调色 ColorMatrix（4x5 共 20 个元素，最后一列偏移为 0）。
     * 用于 RenderEffect 实时预览 与 照片后处理，覆盖所有不支持相机硬件调色的设备，
     * 让「调色」在任意机型上都可见、可出片。
     *
     * 饱和度用 Android 原生 setSaturation 生成——它采用 REC.709 亮度权重、严格亮度守恒，
     * 调高时只放大与灰度的偏差、不会把画面整体推向某一色相（不会出现「越调越绿」）。
     */
    fun colorMatrix(saturation: Float, hueDeg: Float): ColorMatrix {
        val cm = ColorMatrix()
        cm.setSaturation(saturation.coerceIn(0f, 3f))
        if (abs(hueDeg) > 0.01f) cm.postConcat(hueMatrix(hueDeg))
        return cm
    }

    /**
     * 应用层综合调色矩阵（白平衡 + 色调），给不支持硬件调色的设备用：
     * 先套白平衡相对偏移，再叠色调旋转。anchorKelvin 为自动白平衡锚点色温。
     */
    fun appColorMatrix(kelvin: Int, anchorKelvin: Int, hueDeg: Float = 0f, tint: Float = 0f): ColorMatrix {
        val cm = if (kelvin > 0) {
            wbMatrix(kelvin, if (anchorKelvin > 0) anchorKelvin else 5600, tint)
        } else {
            // 未指定色温时仅按 Tint 轴缩放绿通道（升 G→绿、降 G→紫），保持中性不偏色温
            ColorMatrix().apply { setScale(1f, tintFactor(tint), 1f, 1f) }
        }
        if (abs(hueDeg) > 0.01f) cm.postConcat(hueMatrix(hueDeg))
        return cm
    }

    /** 色调旋转矩阵（仅当 hueDeg != 0 使用），将画面整体按色相环偏移 */
    private fun hueMatrix(hueDeg: Float): ColorMatrix {
        val rad = Math.toRadians(hueDeg.toDouble())
        val cos = kotlin.math.cos(rad).toFloat()
        val sin = kotlin.math.sin(rad).toFloat()
        val m = floatArrayOf(
            0.213f + cos * 0.787f - sin * 0.213f, 0.715f - cos * 0.715f - sin * 0.715f, 0.072f - cos * 0.072f + sin * 0.928f,
            0.213f - cos * 0.213f + sin * 0.143f, 0.715f + cos * 0.285f + sin * 0.140f, 0.072f - cos * 0.072f - sin * 0.283f,
            0.213f - cos * 0.213f - sin * 0.787f, 0.715f - cos * 0.715f + sin * 0.715f, 0.072f + cos * 0.928f + sin * 0.072f
        )
        val arr = FloatArray(20)
        arr[0] = m[0]; arr[1] = m[1]; arr[2] = m[2]
        arr[5] = m[3]; arr[6] = m[4]; arr[7] = m[5]
        arr[10] = m[6]; arr[11] = m[7]; arr[12] = m[8]
        arr[18] = 1f   // alpha 通道保持 1
        return ColorMatrix(arr)
    }

    /**
     * AWB 增益 → 真实色温的系统性偏差补偿（单位 mired = 1e6/K）。
     *
     * COLOR_CORRECTION_GAINS 是**传感器原生空间**的增益，而下面 kelvinFromGains 的
     * 反推只能按线性 sRGB 的 RGB→XYZ 矩阵计算（Camera2 不暴露传感器原生矩阵），
     * 于是反推出的色温存在系统性偏差：实测 OPPO 旗舰晴天室外（真实约 5200K）
     * 反推却读作约 7000K，偏高约 +49 mired。
     *
     * 按色温校正的惯例（色温滤镜 / 校色都用 mired 平移而非直接加减 K）做补偿，
     * 使日光档位回到正确读数：反推 7000K → 补正后 5212K。
     * 换机型后若日光读数仍有偏差，调整此常量即可（正值 = 反推偏冷、需回调）。
     */
    private const val AWB_MIRED_BIAS = 49.0

    /**
     * 由 AWB 实际生效的 RGB 增益反推近似色温（K）。
     * COLOR_CORRECTION_GAINS 是「把传感器读数校成中性白」的增益，灯光颜色是其反向（绿归一为 1），
     * 经线性 RGB → XYZ → McCamy 公式得到 CCT，再按 AWB_MIRED_BIAS 补偿传感器空间带来的系统性偏差。
     * 结果夹在 2000K~10000K，用于自动白平衡锚点色温与滑杆跟随显示。
     */
    fun kelvinFromGains(g: RggbChannelVector): Int {
        val r = g.red
        val b = g.blue
        if (r <= 0f || b <= 0f) return 0
        val gAvg = (g.greenEven + g.greenOdd) / 2f
        val lr = gAvg / r
        val lg = 1f
        val lb = gAvg / b
        val x = lr * 0.4124f + lg * 0.3576f + lb * 0.1805f
        val y = lr * 0.2126f + lg * 0.7152f + lb * 0.0722f
        val z = lr * 0.0193f + lg * 0.1192f + lb * 0.9505f
        val sum = x + y + z
        if (sum <= 0f) return 0
        val n = (x / sum - 0.3320f) / (0.1858f - y / sum)
        val cct = (449f * n * n * n + 3525f * n * n + 6823.3f * n + 5520.33f).toInt()
        // mired 平移补偿：抵消「传感器原生增益按 sRGB 矩阵反推」带来的系统性偏冷
        val mired = 1e6 / cct.coerceIn(1000, 20000)
        val corrected = (1e6 / (mired + AWB_MIRED_BIAS)).toInt()
        return corrected.coerceIn(2000, 10000)
    }

    /** 浮点 3x3 → ColorSpaceTransform 的 18 元素 IntArray（每对 = 分子, 分母，分母固定 1000） */
    private fun floatToTransform(m: FloatArray): IntArray {
        val denom = 1000
        val out = IntArray(18)
        for (i in 0 until 9) {
            out[i * 2] = Math.round(m[i] * denom).coerceIn(-32768, 32767) // 分子
            out[i * 2 + 1] = denom                                         // 分母
        }
        return out
    }
}
