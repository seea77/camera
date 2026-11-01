package com.teacher.shotlab

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 拍摄参数模型 + 教学解读引擎。
 * 所有文案面向短视频 / 电商拍摄场景，参数值统一由这里解释，UI 只负责展示。
 */
data class ShotParams(
    val iso: Int = 0,          // 0 = 自动
    val shutterNs: Long = 0L,  // 0 = 自动，单位纳秒
    val kelvin: Int = 0,       // 0 = 自动，否则为色温 K
    val tint: Float = 0f,      // 0 = 中性；负值偏绿、正值偏紫（Lightroom 约定：左滑绿、右滑紫），范围约 [-1,1]
    val ev: Float = 0f,        // 曝光补偿，EV
    val focusMeters: Float = 0f, // 0 = 自动对焦，否则为对焦距离（米）
    val fps: Int = 25,
    val saturation: Float = 1f,  // 调色：饱和度，1 = 中性，0 = 黑白，范围 0~2
    val sharpness: Int = 0       // 调色：清晰度 0-100，0 = 不增强
) {
    val isManual: Boolean get() = iso > 0 || shutterNs > 0 || kelvin > 0 || focusMeters > 0f
}

object ParamCoach {

    /** ISO 档位（1/3 步长，索引与滑杆一致），0 = 自动 */
    val ISO_STOPS = listOf(0, 100, 125, 160, 200, 250, 320, 400, 500, 640, 800, 1000, 1250,
        1600, 2000, 2500, 3200, 4000, 5000, 6400)

    /** 快门档位（纳秒，1/3 步长，索引与滑杆一致），0 = 自动 */
    val SHUTTER_STOPS = listOf(
        0L,
        1_000_000_000L / 8000, 1_000_000_000L / 6400, 1_000_000_000L / 5000, 1_000_000_000L / 4000,
        1_000_000_000L / 3200, 1_000_000_000L / 2500, 1_000_000_000L / 2000, 1_000_000_000L / 1600,
        1_000_000_000L / 1250, 1_000_000_000L / 1000, 1_000_000_000L / 800, 1_000_000_000L / 640,
        1_000_000_000L / 500, 1_000_000_000L / 400, 1_000_000_000L / 320, 1_000_000_000L / 250,
        1_000_000_000L / 200, 1_000_000_000L / 160, 1_000_000_000L / 125, 1_000_000_000L / 100,
        1_000_000_000L / 80, 1_000_000_000L / 60, 1_000_000_000L / 50, 1_000_000_000L / 40,
        1_000_000_000L / 30, 1_000_000_000L / 25, 1_000_000_000L / 20, 1_000_000_000L / 15,
        1_000_000_000L / 13, 1_000_000_000L / 10, 1_000_000_000L / 8, 1_000_000_000L / 6,
        1_000_000_000L / 5, 1_000_000_000L / 4
    )

    /** 白平衡预设（K） */
    val WB_PRESETS = listOf(0, 3200, 4200, 5200, 5600, 6500, 7500)

    /** 按设备的 ISO 实际能力生成滑杆档位，避免给出硬件不支持的值 */
    fun isoStopsFor(range: IntRange?): List<Int> {
        val ok = if (range == null) ISO_STOPS.drop(1) else ISO_STOPS.drop(1).filter { it >= range.first && it <= range.last }
        return listOf(0) + ok.ifEmpty { ISO_STOPS.drop(1) }
    }

    /** 按设备的曝光时间范围生成快门档位（纳秒）。
     *  滑杆方向：左端=低速/长曝光，右端=高速/短曝光，与「左小右大」直觉一致。
     *  故非自动档位统一由慢到快排列（索引 0 恒为 0=自动）。 */
    fun shutterStopsFor(range: LongRange?): List<Long> {
        val ok = if (range == null) SHUTTER_STOPS.drop(1) else SHUTTER_STOPS.drop(1).filter { it >= range.first && it <= range.last }
        val ordered = ok.ifEmpty { SHUTTER_STOPS.drop(1) }.reversed()   // 由慢到快
        return listOf(0L) + ordered
    }

    fun isoLabel(iso: Int): String = if (iso == 0) "自动" else "ISO $iso"

    fun shutterLabel(ns: Long): String {
        if (ns <= 0L) return "自动"
        val denom = (1_000_000_000f / ns).roundToInt()
        return "1/${denom}s"
    }

    fun wbLabel(kelvin: Int): String = when {
        kelvin == 0 -> "自动"
        else -> "${kelvin}K"
    }

    fun evLabel(ev: Float): String = if (ev >= 0f) "+${String.format("%.1f", ev)}" else String.format("%.1f", ev)

    /** 参数条：ISO 400 · 1/50s · 5600K · EV +0.5 */
    fun summary(p: ShotParams): String = buildString {
        append(isoLabel(p.iso)).append(" · ")
        append(shutterLabel(p.shutterNs)).append(" · ")
        append(wbLabel(p.kelvin)).append(" · ")
        append("EV ").append(evLabel(p.ev))
    }

    /** 180° 快门角对应的理想快门（纳秒） */
    fun idealShutterNs(fps: Int): Long = (1_000_000_000L / (fps.toDouble() * 2)).toLong()

    /** 教学解读：逐条给出"当前设定会带来什么画面效果" */
    fun lessons(p: ShotParams): List<String> {
        val out = mutableListOf<String>()

        // 快门
        if (p.shutterNs > 0L) {
            val ideal = idealShutterNs(p.fps)
            val ratio = p.shutterNs.toDouble() / ideal.toDouble()
            out.add(
                when {
                    ratio > 1.8 -> "快门 ${shutterLabel(p.shutterNs)} 偏慢（${p.fps}fps 建议 ${shutterLabel(ideal)}），运动会有明显拖影，适合表现速度感与氛围，但手持要防抖。"
                    ratio > 1.15 -> "快门 ${shutterLabel(p.shutterNs)} 略慢于 ${p.fps}fps 的 180° 快门角（${shutterLabel(ideal)}），画面带一点动态模糊，看起来更接近电影感。"
                    ratio > 0.85 -> "快门 ${shutterLabel(p.shutterNs)} 正好落在 ${p.fps}fps 的 180° 快门角附近，动态模糊自然，是短视频最常用的设定。"
                    else -> "快门 ${shutterLabel(p.shutterNs)} 快于 180° 快门角，动作会显得干脆但偏生硬；拍静物、定格商品细节时更合适。"
                }
            )
            if (p.shutterNs > 1_000_000_000L / 30) {
                out.add("快门慢于 1/30 秒时进光量变大，白天户外容易过曝，建议配合降低 ISO 或加 ND 滤镜。")
            }
        } else {
            out.add("快门为自动：相机会为了保证曝光自行选择快门，${p.fps}fps 拍摄时它可能慢到 1/30 甚至更慢，导致动态模糊不可控。")
        }

        // ISO
        if (p.iso > 0) {
            out.add(
                when {
                    p.iso <= 100 -> "ISO ${p.iso} 画质最干净，色彩和细节保留最好，适合光线充足的户外与棚拍。"
                    p.iso <= 400 -> "ISO ${p.iso} 属于安全区间，暗部略有噪点但不影响成片，室内与窗边拍摄推荐区间。"
                    p.iso <= 1200 -> "ISO ${p.iso} 噪点开始可见，画面会轻微发糊，建议补光而不是继续拉高 ISO。"
                    else -> "ISO ${p.iso} 噪点明显、色彩饱和度下降，仅在没有补光条件时应急使用；后期可加降噪但会损失细节。"
                }
            )
        } else {
            out.add("ISO 为自动：暗光环境下相机会自动拉高 ISO，成片容易忽明忽暗，建议手动锁定一个上限值。")
        }

        // 曝光补偿
        if (abs(p.ev) < 0.05f) {
            out.add("曝光补偿 0：相机按自身测光曝光，遇到大面积白墙或逆光时容易判断失误。")
        } else if (p.ev > 0f) {
            out.add("曝光补偿 ${evLabel(p.ev)}：整体提亮，肤色与商品更讨喜；带货直播常用 +0.3 到 +0.7，但注意高光别溢出。")
        } else {
            out.add("曝光补偿 ${evLabel(p.ev)}：整体压暗，逆光剪影、保留天空细节时使用；暗部会更容易出现噪点。")
        }

        // 白平衡
        if (p.kelvin > 0) {
            out.add(
                when {
                    p.kelvin <= 3600 -> "${p.kelvin}K 暖调，适合室内暖光、餐饮与氛围感镜头，画面偏黄更有食欲。"
                    p.kelvin <= 4600 -> "${p.kelvin}K 中性偏暖，接近荧光灯环境，能中和冷白荧光带来的发青感。"
                    p.kelvin <= 5800 -> "${p.kelvin}K 接近日光，白平衡最中性，商品颜色还原准确，是电商拍摄的通用档位。"
                    else -> "${p.kelvin}K 冷调，适合阴天补冷、科技感与清冷风格；拍肤色会偏白偏冷，谨慎使用。"
                }
            )
        } else {
            out.add("白平衡自动：混合光源下（窗外自然光 + 室内灯）容易来回跳色，建议锁定到一个固定色温。")
        }

        // 对焦
        if (p.focusMeters > 0f) {
            out.add("手动对焦 ${String.format("%.1f", p.focusMeters)} 米：适合固定机位的商品特写，避免自动对焦在拍摄中来回拉风箱。")
        }

        return out
    }

    /** 景别教学卡 */
    val SHOT_SIZES = listOf(
        "远景" to "展现整个环境和大场面，主体只占画面很小一部分，交代空间关系",
        "全景" to "完整呈现主体的全貌，连同周围环境一起交代",
        "中景" to "人物拍到膝盖以上，物品拍到大部分，日常叙事最常用",
        "近景" to "人物拍到胸部以上，物品拍到局部，能看清细节",
        "特写" to "只拍脸部或某个局部，突出表情、纹理和质感"
    )

    /** 运镜教学卡 */
    val MOVES = listOf(
        "推" to "镜头慢慢靠近主体，让注意力集中到一处",
        "拉" to "镜头慢慢远离，带出环境和空间感",
        "摇" to "机位不动，镜头左右或上下扫过",
        "移" to "机位跟着主体平行移动，画面平稳流畅",
        "跟" to "机位跟着主体一起走，一路记录",
        "升降" to "机位上下移动，改变视角高低",
        "绕" to "机位围绕主体转圈，增强动感和立体感"
    )

    /** 拍摄技巧小标题强调色（琥珀），与 APP 主强调色一致 */
    private const val TIP_TITLE_COLOR = "#FFC24B"

    private fun item(k: String, v: String): String =
        "<font color=\"$TIP_TITLE_COLOR\">· $k</font><br>$v"

    fun shotSizeText(): String = SHOT_SIZES.joinToString("<br><br>") { (k, v) -> item(k, v) }

    fun moveText(): String = MOVES.joinToString("<br><br>") { (k, v) -> item(k, v) }

    /** 构图教学卡 */
    val COMPOSITIONS = listOf(
        "中心构图" to "主体放在画面正中间，最稳、最突出",
        "九宫格构图" to "把主体放在横竖三分线的交叉点上，画面更灵动",
        "对角线构图" to "让主体或线条沿对角线分布，有动感和纵深感"
    )

    fun compText(): String = COMPOSITIONS.joinToString("<br><br>") { (k, v) -> item(k, v) }

    /** 静态画面教学卡 */
    val STATIC_SHOTS = listOf(
        "拍人" to "用远景交代环境、中景拍全身、近景拍表情的组合，九宫格构图让眼睛落在交叉点",
        "拍景" to "用全景拍全貌、特写抓纹理的组合，对称或九宫格构图，地平线避开正中间",
        "拍物" to "用全景拍整体、近景特写拍质感的组合，中心或对角线构图突出主体"
    )

    /** 动态画面教学卡 */
    val DYNAMIC_SHOTS = listOf(
        "拍人" to "用跟镜头紧贴主体保持稳定，结合摇镜头交代周围环境",
        "拍景" to "用摇镜头横扫全场，或用移镜头穿过空间展现层次",
        "拍物" to "用移镜头绕主体半圈，或推近到特写，体现动感和细节"
    )

    /** 技巧：静态画面 + 动态画面 合并展示 */
    fun skillText(): String = buildString {
        append("静态画面<br><br>")
        append(STATIC_SHOTS.joinToString("<br><br>") { (k, v) -> item(k, v) })
        append("<br><br>动态画面<br><br>")
        append(DYNAMIC_SHOTS.joinToString("<br><br>") { (k, v) -> item(k, v) })
    }
}
