package com.teacher.shotlab

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.SharedPreferences
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.RenderEffect
import android.graphics.Typeface
import android.hardware.Sensor
import android.text.Html
import android.text.Spannable
import android.text.SpannableString
import android.text.style.RelativeSizeSpan
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewGroup
import android.view.animation.Animation
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.view.animation.ScaleAnimation
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import kotlin.math.roundToInt
import android.widget.TextView
import android.widget.Toast
import android.text.method.ScrollingMovementMethod
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.teacher.shotlab.databinding.ActivityMainBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import org.json.JSONArray
import org.json.JSONObject

/**
 * 主界面（v1.4，参考专业相机大师模式布局）：
 * - 顶部：画幅徽章 + 录制计时；其下一条参数栏（变焦/帧率/快门/ISO/白平衡/EV/对焦），
 *   每列上标签下数值，蓝色「A」徽章代表该参数仍在自动；点任意一列弹出该参数的调节面板。
 * - 参数栏下方：主摄光学变焦倍数快切（1x / 2x / 5x…，按设备实际镜头生成）。
 * - 底部：保存/预设/构图/闪光 小按钮 + 相册圆钮 + 拍照白圈 + 录像红圆 + 技巧图标。
 * - 每个参数「值为 0」即自动（显示 A 徽章），设为非 0 即手动，互不干扰。
 */
/** 一个可命名的参数预设 */
private data class Preset(
    val name: String,
    val iso: Int,
    val shutterNs: Long,
    val kelvin: Int,
    val tint: Float,
    val ev: Float,
    val focus: Float,
    val fps: Int
)

class MainActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var engine: Camera2Engine
    private lateinit var prefs: SharedPreferences

    // 技巧弹层左滑关闭：手动追踪触摸位移
    private var tipSwipeStartX = 0f
    private var tipSwipeStartY = 0f
    private var tipSwiping = false

    // 右侧 保存/预设/复位：左滑展开、右滑收起；滑动手势期间不触发点按对焦
    private var sideExpanded = false
    // 上滑隐藏、下滑显示：仅顶部工具条（底部快门行与参数条保持常驻）
    private var topBarHidden = false
    // 曝光锁定：冻结快门 / 感光度 / 白平衡；对焦不受影响，始终由 AF 连续自动负责
    private var exposureLocked = false
    private val hideLockRunnable = Runnable {
        if (!exposureLocked) binding.btnFocusLock.visibility = View.GONE
    }
    private var swipeStartX = 0f
    private var swipeStartY = 0f
    private var swipeTracking = false
    private var swipeConsumed = false
    // 右滑收起：面板贴屏幕右缘，向右可用行程极短（仅 marginRight 8dp），
    // 纯位移阈值几乎不可能达成，改用速度 + 短位移判定，并让面板跟手平移
    private var swipeVelocity: VelocityTracker? = null
    private var sideDragging = false
    private val collapseMinPx = 18f      // 快速甩动时的最小位移
    private val collapseFastPx = 55f     // 慢速拖拽时的位移阈值
    private val collapseMinSpeed = 200f  // px/s，判定为「甩动」的下限
    private val dragMaxPx = 72f          // 跟手平移上限

    private var lensOptions: List<LensOption> = emptyList()
    private var currentLens: LensOption? = null
    private var zoomOptions: List<Lenses.ZoomOption> = emptyList()
    private var currentZoom = 1f   // 主摄基准的光学变焦倍率（1x = 主摄）

    // 启动层（全屏黑底+正圆 logo）：系统 splash 在 ColorOS 上会被强制成 squircle，
    // 故系统阶段用纯黑图标隐身，正圆 logo 由本层自绘。
    // onOpened（相机就绪）后 200ms 淡出；onResume 1.2s 兜底（权限请求中/开相机失败也能收场）。
    private var splashHidden = false

    private var caps: CameraCaps = CameraCaps(null, null, false)

    // 焦段切换丝滑缩放过渡状态：缩放挂在外层 viewportFrame，configureTransform 只动内层 textureView，互不干扰；
    // 外层恒 ≥1，靠根布局裁切，绝不露黑边。落点复位统一在 engine.onOpened 处理。
    private var lensTransitioning = false
    private val zoomInterpolator = DecelerateInterpolator(1.3f)
    /** true=本次放大（切长焦）：旧预览放大后视场已等于新长焦 S=1 视场，新相机就绪后直接归 1 即无缝 */
    private var pendingZoomIn = false
    /** 非 1 时表示本次缩小（切广角）：新广角就绪瞬间先放大到该值（=旧视场）再回落到 1，自然显出更宽视野 */
    private var pendingZoomStart = 1f

    /** 全部被检测到的镜头（去重前），供「镜头信息」面板展示，确认 7 个镜头的真实焦距/路径 */
    private var allDetectedLenses: List<LensOption> = emptyList()
    /** 长按焦段栏弹出的镜头信息面板（常驻半透明，点一下收起/展开） */
    private var lensInfoPanel: ScrollView? = null

    private var params = ShotParams()
    private var manualSupported = true

    private var isoStops: List<Int> = ParamCoach.ISO_STOPS
    private var shutterStops: List<Long> = ParamCoach.SHUTTER_STOPS
    private val evStops = listOf(-2f, -1.5f, -1f, -0.5f, 0f, 0.5f, 1f, 1.5f, 2f)

    private var gridIndex = 0   // 0 = 辅助线关闭
    private val gridModes = CompositionOverlay.GridMode.values()

    private var flashIndex = 0
    private val flashLabels = listOf("闪光 关", "闪光 补光", "闪光 自动")

    // 拍摄模式：false = 图片（拍摄键拍照），true = 视频（拍摄键录/停）
    private var videoMode = false
    /** 视频模式下暂时替用户保管的图片画幅，切回图片模式时恢复 */
    private var photoRatioIndex = 1   // 图片默认画幅 3:4
    /** 视频唯一采用的画幅在 [ratioList] 中的下标（3:4） */
    private val VIDEO_RATIO_INDEX get() = ratioList.indexOfFirst { it.first == 3 && it.second == 4 }.coerceAtLeast(0)
    private var lastBackLens: LensOption? = null   // 前后切换时记住的后置镜头

    // 帧率候选档位（常见教学/慢动作帧率）。实际显示按设备真实能力过滤：支持多少显示多少
    private val fpsCandidates = listOf(24, 25, 30, 60, 120, 240)
    private var supportedFps: List<Int> = fpsCandidates

    /** 当前是否手动曝光：手动时帧率可精确到任意硬件上限内的档，自动时受 AE 区间「封顶」能力限制 */
    private val manualExposureNow get() = params.iso > 0 || params.shutterNs > 0L

    private var videoTierIndex = 0
    private val videoTierHeights = listOf(1080, 1440, 2160)
    private val videoTierLabels = listOf("1080", "2K", "4K")

    // 白平衡滑杆：连续色温 2000K~10000K，步进 50K
    private val WB_LO = 2000
    private val WB_HI = 10000
    private val WB_STEP = 50

    /** 自动白平衡下相机实测的色温（由 AWB 增益反推），供打开面板时把滑杆停在「当前」位置 */
    private var lastAutoWbKelvin = 0

    /** 半手动（只手动 ISO / 快门之一）时，面板打开期间低频探测自动曝光基线，让另一个参数真正随光线变化 */
    private val probeHandler = Handler(Looper.getMainLooper())
    private var probeTask: Runnable? = null

    private fun startProbe() {
        stopProbe()
        if (!engine.isSemiManual()) return
        val task = object : Runnable {
            override fun run() {
                if (engine.isSemiManual()) engine.probeAutoExposure() else run { stopProbe(); return }
                probeHandler.postDelayed(this, 700)
            }
        }
        probeTask = task
        probeHandler.postDelayed(task, 300)
    }

    private fun stopProbe() {
        probeTask?.let { probeHandler.removeCallbacks(it) }
        probeTask = null
    }

    private var ratioIndex = 1   // 图片默认画幅 3:4
    private val ratioList = listOf(9 to 16, 3 to 4, 1 to 1)

    private var tipMode = 0   // 0 景别 1 运镜 2 构图

    private var currentAdj: AdjParam? = null

    private var sensorManager: SensorManager? = null
    private var accelerometer: Sensor? = null
    private val handler = Handler(Looper.getMainLooper())
    private var recSeconds = 0

    // 录像实时音量条：用 AudioRecord 旁路读取麦克风电平（与录像共用 MIC，失败则静默不显示，绝不干扰录音）
    private var volRec: AudioRecord? = null
    private var volThread: Thread? = null

    // 声控拍照：内置 TFLite 关键词唤醒（离线，模型随 APK 内置，不联网）
    private var voiceEngine: VoiceEngine? = null
    private var voiceEnabled = false     // 用户的开关意愿
    private var voiceHinted = false      // 是否已提示过「准备中」，避免重复弹吐司

    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result[Manifest.permission.CAMERA] == true) {
            openLens()
        } else {
            toast(getString(R.string.permission_denied))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        // 轻量崩溃日志：把未捕获异常写入 App 私有目录 crash.log 并输出到 Log，便于在真机上复现问题后回传定位。
        // 关键：只有【主线程（UI 线程）】的未捕获异常才是致命的，交回系统弹出崩溃提示；
        // 后台线程的良性异常（如音量计线程被 stopVolumeMeter 正常 interrupt）只记录、绝不 re-throw，
        // 否则会把"正常的线程中断"误判成致命崩溃，导致录制停止后闪退。
        val prevHandler = Thread.getDefaultUncaughtExceptionHandler()
        val mainThread = Looper.getMainLooper().thread
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            android.util.Log.e("ShotLab", "未捕获异常（线程 ${t.name}）", e)
            runCatching {
                val dir = getExternalFilesDir(null) ?: filesDir
                val stamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                    .format(java.util.Date())
                java.io.File(dir, "crash.log").appendText(
                    "$stamp\n${android.util.Log.getStackTraceString(e)}\n\n"
                )
            }
            if (t === mainThread) prevHandler?.uncaughtException(t, e)
        }
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        prefs = getSharedPreferences("shotlab_preset", Context.MODE_PRIVATE)
        loadSettings()
        engine = Camera2Engine(this, binding.textureView)
        // 引擎就绪后才能恢复白平衡路径：默认 AUTO（体检后自定），用户手动指定过就沿用
        engine.wbMode = WbMode.values().getOrElse(prefs.getInt("wbMode", 0)) { WbMode.AUTO }
        // 取景框尺寸一变（切比例）就按新尺寸重算取景，避免读到旧尺寸导致画面变形
        binding.viewportFrame.addOnLayoutChangeListener { _, l, t, r, b, _, _, _, _ ->
            if (r - l > 0 && b - t > 0) engine.refreshTransform()
        }
        setupEngineCallbacks()
        setupSensors()
        applyOrientationMetrics()
        setupUi()
        verifyOfficialBuild()

        if (allPermissionsGranted()) openLens() else requestPermissions()
    }

    // ---------- 官方签名自检 ----------

    // 官方发布证书（shotlab-release.keystore / alias=shotlab）的 SHA-256，拆成四段存放：
    // 想伪造的人得先读懂这段拼接逻辑才改得对，比直接搜一整串明文难动。
    private val officialSigParts = listOf(
        "2620d0ce4d8812f2", "3bad6498c4d9e651", "7f79b5fea2a743cc", "adccb7e4d0c59e01"
    )
    private var sigWarned = false

    /** 当前安装包的签名证书 SHA-256；取不到（个别 ROM 异常）返回 null */
    private fun currentSigningSha256(): String? = runCatching {
        val pm = packageManager
        val signers = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
                .signingInfo?.apkContentsSigners
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES).signatures
        }
        val raw = signers?.firstOrNull()?.toByteArray()
        if (raw == null) null else {
            java.security.MessageDigest.getInstance("SHA-256").digest(raw)
                .joinToString("") { b -> "%02x".format(b.toInt() and 0xff) }
        }
    }.getOrNull()

    /**
     * 校验自身签名是否为官方发布密钥，不是就弹警告。
     *
     * 挡的是「改完代码或资源、用自己的密钥重新打包再分发」这一类二改——那种包的签名
     * 必然与官方不同，这里就能抓到。有心人仍能 patch 掉这段判断，但门槛从
     * 「改个图标重新打包」抬到了「读懂并改写校验逻辑」。
     *
     * 只警告、不强制退出：宁可放过，也不能让将来换了密钥的正版用户打不开 App。
     * 调试版（debug 签名）跳过自检，免得自己开发时弹窗。
     */
    private fun verifyOfficialBuild() {
        if (BuildConfig.DEBUG || sigWarned) return
        if (currentSigningSha256() == officialSigParts.joinToString("")) return
        sigWarned = true
        AlertDialog.Builder(this)
            .setTitle("非官方版本")
            .setMessage(
                "当前安装包的签名与官方发布不一致，可能已被修改。\n\n" +
                    "建议卸载后从官方渠道重新下载，以免用到被植入不明代码的版本。"
            )
            .setPositiveButton("仍要继续", null)
            .setNegativeButton("退出") { _, _ -> finish() }
            .setCancelable(false)
            .show()
    }

    override fun onResume() {
        super.onResume()
        accelerometer?.let { sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
        currentLens?.let { engine.open(it.cameraId, it.physicalId) }
        syncVoice()          // 回到前台，开关仍开着就恢复监听
        // 启动 logo 兜底：权限请求中/相机打开失败时 onOpened 不会来，1.2s 后强制淡出，
        // 防止黑底 logo 永久停留挡住界面。正常路径由 onOpened 提前淡出，这里被 done 标志短路。
        binding.root.postDelayed({ hideSplashLogo() }, 1200L)
    }

    override fun onPause() {
        stopVoice()          // 退到后台必须停止录音，不能后台占着麦克风
        stopVolumeMeter()
        engine.close()
        sensorManager?.unregisterListener(this)
        saveSettings()
        super.onPause()
    }

    // ---------- 相机回调 ----------

    /** 淡出启动层：相机预览就绪或兜底超时后调用，防重入 */
    private fun hideSplashLogo() {
        if (splashHidden) return
        splashHidden = true
        val layer = binding.splashLayer
        layer.animate().cancel()
        layer.animate()
            .alpha(0f)
            .setDuration(350L)
            .withEndAction { layer.visibility = android.view.View.GONE }
            .start()
    }

    private fun setupEngineCallbacks() {
        engine.onOpened = { c ->
            caps = c
            manualSupported = c.manualSensor
            // 按设备真实帧率能力过滤候选档位：支持（录得到）的才显示，不支持的直接不出现。
            // 每次相机打开都检测：不同镜头（超广角/长焦）能力不同，且第一次安装没有历史存档时也能立即收敛。
            supportedFps = engine.supportedRecordFps(fpsCandidates, false)
            // 若存档默认帧率设备不支持（如某些机器最低 30），自动落到支持列表中最接近 30 的档
            if (params.fps !in supportedFps) {
                params = params.copy(
                    fps = supportedFps.minByOrNull { kotlin.math.abs(it - 30) } ?: supportedFps.first()
                )
                binding.tvFps.text = "${params.fps}fps"
                saveSettings()
            }
            refreshCaps()
            engine.setFlash(flashIndex)
            engine.setVideoTier(videoTierHeights[videoTierIndex])
            engine.refreshTransform()
            applyParams()
            if (!c.manualSensor) {
                toast("该镜头未开放手动传感器：ISO/快门可能不生效")
            }
            // 焦段切换缩放过渡的落点复位：新预览真正出帧后再归位，保证画面连续不跳。
            // 非切换场景（冷启动 / onResume 重开同镜头）也确保预览比例归位、解除过渡锁。
            val vp = binding.viewportFrame
            vp.animate().cancel()
            if (pendingZoomIn) {
                pendingZoomIn = false
                vp.scaleX = 1f; vp.scaleY = 1f
                lensTransitioning = false
            } else if (pendingZoomStart > 1.001f) {
                val start = pendingZoomStart
                pendingZoomStart = 1f
                vp.scaleX = start; vp.scaleY = start
                vp.animate()
                    .scaleX(1f).scaleY(1f)
                    .setDuration(200L)
                    .setInterpolator(zoomInterpolator)
                    .withEndAction { lensTransitioning = false }
                    .start()
            } else {
                vp.scaleX = 1f; vp.scaleY = 1f
                lensTransitioning = false
            }
            // 相机已就绪、预览即将出帧：稍候片刻让首帧真正渲染出来，再淡出正圆启动 logo，
            // 避免用户看到「logo 突然消失、黑屏一下、画面才出来」的断裂感。
            binding.root.postDelayed({ hideSplashLogo() }, 200L)
            // 兜底重算取景：从相册返回等场景下窗口恢复可见时 TextureView surface 会重建、
            // relayout 可能滞后，这里相机就绪后再算一次（内部 post + 400ms 双算），防画面拉长。
            engine.refreshTransform()
        }
        engine.onError = {
            toast(it)
            // 切换过渡若卡在「等待新相机就绪」，打开失败时必须复位，否则变焦档会被永久锁死
            binding.viewportFrame.scaleX = 1f; binding.viewportFrame.scaleY = 1f
            pendingZoomIn = false; pendingZoomStart = 1f; lensTransitioning = false
        }
        engine.onActual = { binding.tvActual.text = it.summary() }
        engine.onAutoValues = { actIso, actSh, actWb ->
            if (actWb != null && actWb > 0) lastAutoWbKelvin = actWb
            autoTrack(actIso, actSh, actWb)
            // 手动白平衡是「相对相机当前中性点」的偏移，AWB 增益随光线变化时要重算预览
            if (params.kelvin > 0) applyColorEffect()
        }
        // 半手动时探测到新的自动曝光基线：另一个参数的软自动值变了，重新下发并刷新滑杆
        engine.onBaselineUpdated = {
            applyParams()
            autoTrack(engine.lastActualIso, engine.lastActualShutterNs, null)
        }
        engine.onPhotoSaved = { uri ->
            toast(getString(R.string.saved))
            uri?.let { u ->
                ExifReader.read(contentResolver, u)?.let { actual ->
                    toast("成片 " + actual.summary().removePrefix("实际生效："))
                }
            }
        }
        engine.onRecordingStarted = {
            recSeconds = 0
            showRecTimer(true)
            startTimer()
            // 录像会话重建后按当前取景框尺寸重算变换，避免预览被拉伸
            engine.refreshTransform()
            startVolumeMeter()
        }
        engine.onRecordingStopped = {
            showRecTimer(false)
            stopTimer()
            stopVolumeMeter()
            toast("视频已保存到相册")
            engine.refreshTransform()
        }
    }

    // ---------- 权限 ----------

    private fun requiredPermissions(): List<String> {
        val list = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list += Manifest.permission.READ_MEDIA_IMAGES
            list += Manifest.permission.READ_MEDIA_VIDEO
        } else {
            list += Manifest.permission.READ_EXTERNAL_STORAGE
        }
        return list
    }

    private fun allPermissionsGranted(): Boolean =
        requiredPermissions().all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

    private fun requestPermissions() {
        permLauncher.launch(requiredPermissions().toTypedArray())
    }

    private fun openLens() {
        lensOptions = Lenses.enumerate(this)
        allDetectedLenses = Lenses.enumerateAll(this)
        zoomOptions = Lenses.zoomOptions(lensOptions)
        val zSel = matchZoom(currentZoom)
        currentLens = zSel?.lens ?: Lenses.pickMain(lensOptions)
        currentZoom = zSel?.ratio ?: 1f
        setupZoomChips()
        currentLens?.let { engine.open(it.cameraId, it.physicalId) }
    }

    private fun switchLens(lens: LensOption?) {
        currentLens = lens
        engine.close()
        lens?.let { engine.open(it.cameraId, it.physicalId) }
        updateZoomChips()
        updateParamStrip()
        updateFlipIcon()
        saveSettings()
    }

    /**
     * 焦段切换（仅变焦档快切调用，前后摄翻转仍用即时 switchLens）。
     * 已取消缩放过渡动画：直接切换镜头，预览比例立即归位，切换更干脆。
     * 缩放状态一律复位（pendingZoomIn=false / pendingZoomStart=1f），
     * 使 engine.onOpened 走「无过渡」分支直接归 1，不再有任何缩放动画。
     */
    private fun switchLensWithZoom(lens: LensOption?, fromRatio: Float, toRatio: Float) {
        val vp = binding.viewportFrame
        vp.animate().cancel()
        vp.scaleX = 1f; vp.scaleY = 1f
        pendingZoomIn = false
        pendingZoomStart = 1f
        lensTransitioning = false
        currentLens = lens
        currentZoom = toRatio
        updateZoomChips()
        updateParamStrip()
        updateFlipIcon()
        saveSettings()
        engine.close()
        lens?.let { engine.open(it.cameraId, it.physicalId) }
    }

    // ---------- 参数 ----------

    private fun refreshCaps() {
        isoStops = ParamCoach.isoStopsFor(caps.isoRange)
        shutterStops = ParamCoach.shutterStopsFor(caps.exposureNs)
    }

    private fun applyParams() {
        // 用户手动接管快门 / ISO / 白平衡后，自动解除曝光锁定：
        // 手动值本身就是固定值，保留「锁定」既多余又会与手动曝光路径冲突
        if (exposureLocked && !isAutoExposure()) {
            exposureLocked = false
            engine.setExposureLock(false)
            updateFocusLockIcon()
            binding.btnFocusLock.postDelayed(hideLockRunnable, 1200L)
        }
        // 退回自动曝光时，若当前帧率在自动模式下「封不住上限」（会录成别的帧率），
        // 回落到最接近的可行档并说明原因——免得学生看到文件属性与设定不符还以为录错了。
        if (!manualExposureNow) {
            val list = engine.supportedRecordFps(fpsCandidates, false)
            if (params.fps !in list) {
                val old = params.fps
                val fallback = list.minByOrNull { kotlin.math.abs(it - old) } ?: list.first()
                params = params.copy(fps = fallback)
                binding.tvFps.text = "${fallback}fps"
                toast("自动模式无法锁定 ${old}fps，已回落到 ${fallback}fps")
            }
            supportedFps = engine.supportedRecordFps(fpsCandidates, false)
        } else {
            supportedFps = engine.supportedRecordFps(fpsCandidates, true)
        }
        // 始终以手动模式下发：引擎内部按各参数是否非 0 自行决定自动/手动
        engine.applyParams(params, true)
        // 首次使用手动白平衡时做一次体检，确认本机硬件路径到底是否真的生效
        engine.maybeProbeWb()
        updateParamStrip()
        applyColorEffect()
        saveSettings()
    }

    /**
     * 实时预览调色：白平衡 + 饱和度，统一用 RenderEffect 叠加 ColorMatrix（API 31+）。
     * - 不支持硬件调色的设备：白平衡也走应用层矩阵，与饱和度前后相乘；
     * - 支持硬件调色的设备：白平衡已烤进画面，这里只叠加饱和度，避免双重调色。
     */
    private fun applyColorEffect() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val satCm = if (abs(params.saturation - 1f) > 0.001f)
            ManualCamera.colorMatrix(params.saturation, 0f) else null
        val wbCm = if (!engine.useHardwareWb && params.kelvin > 0)
            ManualCamera.appColorMatrix(params.kelvin, engine.anchorKelvin, 0f) else null
        val cm = when {
            wbCm != null && satCm != null -> wbCm.apply { postConcat(satCm) }
            wbCm != null -> wbCm
            satCm != null -> satCm
            else -> null
        }
        binding.textureView.setRenderEffect(
            cm?.let { RenderEffect.createColorFilterEffect(ColorMatrixColorFilter(it)) }
        )
    }

    // ---------- 设置持久化：打开时恢复上次全部参数 ----------

    private fun loadSettings() {
        params = params.copy(
            iso = prefs.getInt("iso", 0),
            shutterNs = prefs.getLong("shutterNs", 0L),
            kelvin = prefs.getInt("kelvin", 0),
            tint = prefs.getFloat("tint", 0f).coerceIn(-1f, 1f),
            ev = prefs.getFloat("ev", 0f),
            focusMeters = prefs.getFloat("focus", 0f),
            fps = prefs.getInt("fps", 25),
            saturation = 1f   // 饱和度选项已移出调色面板，固定为原始值，避免旧存档残留的非 1 值持续生效
        )
        // 旧版本存档可能存着已删除的 16:9 索引，必须夹回合法范围
        ratioIndex = prefs.getInt("ratioIndex", 1).coerceIn(0, ratioList.size - 1)
        // 一次性迁移：v1.8.37 起图片默认画幅由 9:16(0) 改为 3:4(1)；旧存档停留在旧默认的升到新默认
        if (!prefs.getBoolean("ratioDefaultMigrated", false)) {
            if (ratioIndex == 0) ratioIndex = 1
            prefs.edit().putBoolean("ratioDefaultMigrated", true).apply()
        }
        // 视频模式下 ratioIndex 会被临时改成 3:4，这里记下用户真正的图片画幅以便切回时恢复
        photoRatioIndex = ratioIndex
        videoTierIndex = prefs.getInt("videoTierIndex", 0).coerceIn(0, videoTierHeights.size - 1)
        gridIndex = prefs.getInt("gridIndex", 0)
        flashIndex = prefs.getInt("flashIndex", 0).coerceIn(0, flashLabels.size - 1)
        currentZoom = runCatching { prefs.getFloat("zoomX", 1f) }
            .getOrElse { prefs.getInt("zoomX", 1).toFloat() }   // 兼容旧版本存档
        // 一次性迁移：清掉 v1.8.11 之前残留的 saturation 旧值，避免「调色」被悄悄开启
        if (!prefs.getBoolean("sat_migrated", false)) {
            prefs.edit().remove("saturation").putBoolean("sat_migrated", true).apply()
        }
        params = params.copy(
            saturation = prefs.getFloat("saturation", 1f).coerceIn(0f, 2f),
            sharpness = prefs.getInt("sharpness", 0)
        )
        // 注意：白平衡路径的恢复不能写在这里 —— loadSettings() 早于 engine 初始化，
        // 在这里触碰 engine 会直接抛 UninitializedPropertyAccessException（App 打不开）。
        // 该项在 onCreate 里 engine 建好之后单独恢复。
    }

    private fun saveSettings() {
        prefs.edit().apply {
            putInt("iso", params.iso)
            putLong("shutterNs", params.shutterNs)
            putInt("kelvin", params.kelvin)
            putFloat("ev", params.ev)
            putFloat("focus", params.focusMeters)
            putFloat("tint", params.tint)
            putInt("fps", params.fps)
            // 视频模式下 ratioIndex 是临时的 3:4，落盘的必须是用户给照片选的画幅
            putInt("ratioIndex", if (videoMode) photoRatioIndex else ratioIndex)
            putInt("videoTierIndex", videoTierIndex)
            putInt("gridIndex", gridIndex)
            putInt("flashIndex", flashIndex)
        putFloat("zoomX", currentZoom)
        putInt("sharpness", params.sharpness)
        putFloat("saturation", params.saturation)
            putInt("wbMode", engine.wbMode.ordinal)
            apply()
        }
    }

    // ---------- UI ----------

    private fun setupUi() {
        setupTopBar()
        setupParamStrip()
        setupUtilRow()
        setupCaptureButtons()
        setupAdjSheet()
        setupChoicePopup()
        setupFocusTap()
        setupTips()
        updateParamStrip()
        applyRatio()
        updateRatioChipState()
        setupWbPathRow()
    }

    /** 顶部：画幅 / 帧率 / 分辨率 徽章，点击弹出菜单选择 */
    private fun setupTopBar() {
        binding.tvQuality.setOnClickListener {
            // 视频模式画幅锁定 3:4，不给改；点一下告诉用户原因
            if (videoMode) { toast("视频模式固定 3:4"); return@setOnClickListener }
            if (engine.isRecording) { toast("录像中不能修改"); return@setOnClickListener }
            showChoice(
                binding.tvQuality,
                "画幅比例",
                ratioList.map { "${it.first}:${it.second}" },
                ratioIndex
            ) { i ->
                ratioIndex = i
                applyRatio()
                updateQuality()
                saveSettings()
            }
        }
        binding.tvFps.setOnClickListener {
            if (!videoMode) { toast("照片模式无需设置帧率"); return@setOnClickListener }
            if (engine.isRecording) { toast("录像中不能改帧率"); return@setOnClickListener }
            // 列表按当前曝光模式实时计算：手动曝光时由帧时长精确控帧，可选的档比自动模式宽
            val list = engine.supportedRecordFps(fpsCandidates, manualExposureNow)
            showChoice(
                binding.tvFps,
                "帧率",
                list.map { "${it}fps" },
                list.indexOf(params.fps).coerceAtLeast(0)
            ) { i ->
                params = params.copy(fps = list[i])
                binding.tvFps.text = "${params.fps}fps"
                applyParams()
                saveSettings()
            }
        }
        binding.tvRes.setOnClickListener {
            if (!videoMode) { toast("照片模式无需设置视频分辨率"); return@setOnClickListener }
            if (engine.isRecording) { toast("录像中不能修改"); return@setOnClickListener }
            showChoice(
                binding.tvRes,
                "视频分辨率",
                videoTierLabels,
                videoTierIndex,
                supported = { engine.isVideoTierSupported(videoTierHeights[it]) }
            ) { i ->
                videoTierIndex = i
                engine.setVideoTier(videoTierHeights[i])
                updateRes()
                saveSettings()
            }
        }
        updateQuality()
        updateRes()
        updateVideoChipState()
    }

    private fun updateQuality() {
        val (rw, rh) = ratioList[ratioIndex]
        binding.tvQuality.text = "$rw:$rh"
    }

    private fun updateRes() {
        binding.tvRes.text = videoTierLabels[videoTierIndex]
    }

    // ---------- 通用选项弹层：比例 / 帧率 / 视频分辨率 共用 ----------

    private fun setupChoicePopup() {
        binding.choicePopup.setOnClickListener { closeChoicePopup() }
    }

    private fun closeChoicePopup() {
        binding.choicePopup.visibility = View.GONE
    }

    /**
     * 紧贴 [anchor] 徽章下方弹出一行选项：选中的高亮，不支持的置灰但保留（方便换支持该档位的手机）。
     * @param supported 返回 false 的选项会被置灰不可点（默认全部支持）。
     */
    private fun showChoice(
        anchor: View,
        title: String,
        labels: List<CharSequence>,
        selected: Int,
        vertical: Boolean = false,
        supported: (Int) -> Boolean = { true },
        onPick: (Int) -> Unit
    ) {
        binding.choiceTitle.text = title
        binding.choiceRow.removeAllViews()
        binding.choiceRow.orientation =
            if (vertical) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        val pad = (resources.displayMetrics.density * 6).toInt()
        val chip = attrColor(R.attr.uiChip)
        val dim = attrColor(R.attr.uiTextDim)
        val accent = attrColor(R.attr.uiAccent)
        val accentOn = attrColor(R.attr.uiAccentOn)
        val text = attrColor(R.attr.uiText)
        labels.forEachIndexed { i, label ->
            val ok = supported(i)
            val tv = TextView(this).apply {
                this.text = label
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(pad * 2, pad, pad * 2, pad)
                if (!ok) {
                    alpha = 0.35f
                    isEnabled = false
                    setBackgroundColor(chip)
                    setTextColor(dim)
                } else {
                    val sel = i == selected
                    setBackgroundColor(if (sel) accent else chip)
                    setTextColor(if (sel) accentOn else text)
                    setOnClickListener {
                        onPick(i)
                        closeChoicePopup()
                    }
                }
            }
            val lp = if (vertical) {
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.setMargins(4, 4, 4, 4) }
            } else {
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).also { it.setMargins(4, 0, 4, 0) }
            }
            binding.choiceRow.addView(tv, lp)
        }
        binding.choicePopup.visibility = View.VISIBLE
        // 面板定位：水平对齐到被点击的徽章，垂直贴在它正下方（下方放不下则翻到上方）
        binding.choicePanel.post { placeChoicePanelUnder(anchor) }
    }

    /** 把选项面板放到 [anchor] 正下方，并保证不超出屏幕两侧/底部 */
    private fun placeChoicePanelUnder(anchor: View) {
        val anchorPos = IntArray(2)
        val rootPos = IntArray(2)
        anchor.getLocationOnScreen(anchorPos)
        binding.root.getLocationOnScreen(rootPos)
        val panel = binding.choicePanel
        val gap = (resources.displayMetrics.density * 4).toInt()
        val edge = (resources.displayMetrics.density * 8).toInt()
        val ax = anchorPos[0] - rootPos[0]
        val ay = anchorPos[1] - rootPos[1]
        val maxX = (binding.root.width - panel.width - edge).coerceAtLeast(edge)
        val maxY = (binding.root.height - panel.height - edge).coerceAtLeast(edge)
        panel.x = ax.toFloat().coerceIn(edge.toFloat(), maxX.toFloat())
        val below = (ay + anchor.height + gap).toFloat()
        val above = (ay - gap - panel.height).toFloat()
        panel.y = when {
            below <= maxY -> below
            above >= edge -> above
            else -> maxY.toFloat()
        }
    }

    private fun dp(v: Float): Int = (resources.displayMetrics.density * v).toInt()

    /** 横屏可用高度变小：收紧顶部留白，尽量把空间留给取景框 */
    /**
     * 横竖屏排布。
     *
     * 横屏时**不按横屏重新排版**，而是让每条带继续贴住「手机的同一条物理边」：
     *   - 手机顶边那条（比例/帧率/分辨率 + 参数条） → 转到屏幕左方或右方（取决于往哪边转）
     *   - 手机底边那条（变焦 + 拍摄按钮 + 工具）   → 转到屏幕的另一侧
     * 条带内部只把横向排列改成纵向排列，**文字本身不旋转**，所以横屏握持时依然水平可读。
     */
    // 应用锁定竖屏，不再处理横竖屏切换的边距差异
    private fun applyOrientationMetrics() {
        val topBar = binding.topBar.layoutParams as android.widget.FrameLayout.LayoutParams
        topBar.topMargin = dp(40f)
        binding.topBar.layoutParams = topBar
        val paramArea = binding.paramArea.layoutParams as android.widget.FrameLayout.LayoutParams
        paramArea.topMargin = dp(116f)
        binding.paramArea.layoutParams = paramArea
    }

    /** 横屏时弹层容易顶出屏幕：限制为 85% 高度，超出部分可滚动 */
    private fun limitPanelHeight(panel: View) {
        panel.post {
            val max = (binding.root.height * 0.85f).toInt()
            if (max <= 0) return@post
            val lp = panel.layoutParams
            lp.height = if (panel.height > max) max else android.view.ViewGroup.LayoutParams.WRAP_CONTENT
            panel.layoutParams = lp
        }
    }

    private fun applyRatio() {
        binding.root.post {
            val w = binding.root.width
            val h = binding.root.height
            if (w == 0 || h == 0) return@post
            val (rw, rh) = ratioList[ratioIndex]
            // 比例一律按字面理解（9:16 就是竖构图），横竖屏不做任何特殊交换
            val target = rw.toFloat() / rh.toFloat()
            // 画幅不仅要裁预览，也必须裁成片，否则存出来的仍是原生 3:4
            engine.setPhotoAspect(rw, rh)
            var vw = w
            var vh = (w / target).toInt()
            if (vh > h) {
                vh = h
                vw = (h * target).toInt()
            }
            val lp = binding.viewportFrame.layoutParams as android.widget.FrameLayout.LayoutParams
            lp.width = vw
            lp.height = vh
            lp.gravity = Gravity.CENTER
            binding.viewportFrame.layoutParams = lp
            // 取景框尺寸变了，必须按新尺寸重算变换，否则画面会被拉伸
            engine.refreshTransform()
        }
    }

    // ---------- 参数栏 ----------

    private fun setupParamStrip() {
        binding.colShutter.setOnClickListener { openSheet(AdjParam.SHUTTER) }
        binding.colIso.setOnClickListener { openSheet(AdjParam.ISO) }
        binding.colWb.setOnClickListener { openSheet(AdjParam.WB) }
        binding.colColor.setOnClickListener { openSheet(AdjParam.COLOR) }
        binding.colEv.setOnClickListener {
            if (params.iso > 0 && params.shutterNs > 0L) {
                toast("快门与感光度均为手动时，EV 不生效")
                return@setOnClickListener
            }
            openSheet(AdjParam.EV)
        }
        binding.colFocus.setOnClickListener { openSheet(AdjParam.FOCUS) }
    }

    /** 刷新参数栏：手动显示具体值（白字），自动显示「自动」+ A 徽章 */
    private fun updateParamStrip() {
        binding.tvFps.text = "${params.fps}fps"
        // 半手动（只手动其一）时，另一个参数仍是「自动」但由曝光量守恒软跟随：
        // 这里把它的当前值一并显示出来（如「自动 1/60s」），避免看起来像被锁死不动
        val softSh = engine.softAutoShutterNs()
        val softIso = engine.softAutoIso()
        // 自动档前缀标明参数身份，便于课堂上快速分辨：S = 快门、ISO = 感光度、WB = 白平衡
        setParam(
            binding.tvShutterValue, binding.badgeShutter, params.shutterNs > 0L,
            if (params.shutterNs > 0L) ParamCoach.shutterLabel(params.shutterNs)
            else if (softSh != null) "S 自动 ${ParamCoach.shutterLabel(softSh)}" else "S 自动"
        )
        setParam(
            binding.tvIsoValue, binding.badgeIso, params.iso > 0,
            if (params.iso > 0) ParamCoach.isoLabel(params.iso)
            else if (softIso != null) "ISO 自动 ${ParamCoach.isoLabel(softIso)}" else "ISO 自动"
        )
        setParam(
            binding.tvWbValue, binding.badgeWb, params.kelvin > 0,
            if (params.kelvin > 0) ParamCoach.wbLabel(params.kelvin) else "WB 自动"
        )
        // 调色列：任意一项偏离默认即显示「已调」；否则显示自动「A」徽章
        val colorOn = colorAdjusted()
        binding.tvColorValue.text = if (colorOn) "已调" else "自动"
        binding.tvColorValue.setTextColor(
            if (colorOn) attrColor(R.attr.uiText) else attrColor(R.attr.uiTextDim)
        )
        binding.badgeColor.visibility = if (colorOn) View.GONE else View.VISIBLE
        // 快门 + 感光度都手动时 EV 无效：整列置灰
        val evBlocked = params.iso > 0 && params.shutterNs > 0L
        binding.colEv.alpha = if (evBlocked) 0.35f else 1f
        binding.colEv.isEnabled = !evBlocked
        val evManual = abs(params.ev) > 0.05f
        setParam(binding.tvEvValue, binding.badgeEv, evManual, ParamCoach.evLabel(params.ev))
        setParam(
            binding.tvFocusValue, binding.badgeFocus, params.focusMeters > 0f,
            if (params.focusMeters > 0f) String.format("%.1f", params.focusMeters) + "m" else "自动"
        )
    }

    private fun colorAdjusted(): Boolean =
        params.sharpness > 0 || abs(params.saturation - 1f) > 0.001f

    private fun setParam(value: TextView, badge: TextView, manual: Boolean, label: String) {
        value.text = label
        value.setTextColor(if (manual) attrColor(R.attr.uiText) else attrColor(R.attr.uiTextDim))
        badge.visibility = if (manual) View.GONE else View.VISIBLE
    }

    // ---------- 镜头快切：启动时自动检测后置镜头，显示几个就显示几个，文字标镜头类型 ----------

    private fun setupZoomChips() {
        binding.zoomRow.removeAllViews()
        val dot = (resources.displayMetrics.density * 34).toInt()   // 放大以容纳中文类型文字（超广角 / 超长焦）
        val margin = (resources.displayMetrics.density * 4).toInt()
        // 只显示设备真实支持的光学变焦倍率：镜头有几个就显示几个，不补充、不置灰
        zoomOptions.sortedBy { it.ratio }.forEach { zo ->
            val tv = TextView(this).apply {
                text = lensLabel(zo.lens)
                textSize = 9f
                gravity = Gravity.CENTER
                tag = zo.ratio
                setOnClickListener {
                    if (lensTransitioning) return@setOnClickListener
                    val from = currentZoom
                    val to = zo.ratio
                    if (kotlin.math.abs(to - from) < 0.001f) return@setOnClickListener
                    switchLensWithZoom(zo.lens, from, to)
                }
            }
            val lp = android.widget.LinearLayout.LayoutParams(dot, dot)
            lp.setMargins(margin, 0, margin, 0)
            binding.zoomRow.addView(tv, lp)
        }
        // 长按焦段栏弹出「镜头信息」面板：列出全部检测到的镜头（含焦距/路径/是否被选用），确认 7 个镜头的真实情况
        binding.zoomRow.setOnLongClickListener { showLensInfo(); true }
        updateZoomChips()
    }

    /** 长按焦段栏：常驻半透明面板，列出全部检测到的镜头（含等效焦距 / 逻辑&物理路径 / 是否被选用），点一下收起/展开 */
    private fun showLensInfo() {
        if (lensInfoPanel == null) {
            val tv = TextView(this).apply {
                setTextColor(0xFF7CFC00.toInt())
                textSize = 11f
                setPadding(16, 16, 16, 16)
                movementMethod = ScrollingMovementMethod.getInstance()
                setOnClickListener { lensInfoPanel?.visibility = if (visibility == View.VISIBLE) View.GONE else View.VISIBLE }
            }
            val sv = ScrollView(this).apply {
                setBackgroundColor(0xCC000000.toInt())
                val h = (resources.displayMetrics.heightPixels * 0.55f).toInt()
                val lp = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, h)
                lp.gravity = Gravity.TOP
                layoutParams = lp
                addView(tv)
                setOnClickListener { visibility = if (visibility == View.VISIBLE) View.GONE else View.VISIBLE }
            }
            (findViewById<ViewGroup>(android.R.id.content)).addView(sv)
            lensInfoPanel = sv
            lensInfoPanel?.tag = tv
        }
        val tv = lensInfoPanel?.tag as? TextView ?: return
        val kept = lensOptions
        val lines = allDetectedLenses.map { l ->
            val used = kept.any { it.cameraId == l.cameraId && it.physicalId == l.physicalId }
            val path = if (l.physicalId != null) "cam=${l.cameraId} phys=${l.physicalId}" else "cam=${l.cameraId} phys=-"
            val face = if (l.facing == CameraCharacteristics.LENS_FACING_FRONT) "前" else "后"
            "${if (used) "✔" else " "}${face} ${l.display}  $path  manual=${l.manualSensor}"
        }
        val zoom = zoomOptions.joinToString(" / ") { "${it.label}(${it.lens.physicalId ?: it.lens.cameraId})" }
        tv.text = "检测到 ${allDetectedLenses.size} 颗（去重后 ${lensOptions.size} 颗可用）\n" +
            lines.joinToString("\n") + "\n\n焦段档(UI): $zoom\n（点面板可收起/展开，再长按焦段栏关闭）"
        lensInfoPanel?.visibility = View.VISIBLE
    }

    private fun updateZoomChips() {
        val active = currentLens?.let { l ->
            zoomOptions.firstOrNull { it.lens.cameraId == l.cameraId && it.lens.physicalId == l.physicalId }?.ratio
        } ?: currentZoom
        for (i in 0 until binding.zoomRow.childCount) {
            val v = binding.zoomRow.getChildAt(i) as TextView
            val x = v.tag as? Float ?: continue
            if (!v.isEnabled) continue
            val on = abs(x - active) <= 0.12f * x
            v.background = ContextCompat.getDrawable(
                this, if (on) R.drawable.bg_dot_on else R.drawable.bg_dot_off
            )
            v.setTextColor(if (on) attrColor(R.attr.uiAccentOn) else attrColor(R.attr.uiTextDim))
        }
    }

    /** 在设备真实倍率里找与候选最接近的一档，找不到即不支持 */
    private fun matchZoom(r: Float): Lenses.ZoomOption? =
        zoomOptions.firstOrNull { abs(it.ratio - r) <= 0.12f * r }

    private fun zoomLabel(x: Float): String =
        if (x == x.toInt().toFloat()) "${x.toInt()}x" else String.format("%.1f", x) + "x"

    /** 镜头芯片显示镜头类型文字（超广角/主摄/长焦/超长焦）；同类出现多次（如双长焦）时追加等效焦距区分 */
    private fun lensLabel(lens: LensOption): String {
        val dup = zoomOptions.groupingBy { it.lens.label }.eachCount()
        val base = lens.label
        return if ((dup[base] ?: 0) > 1 && lens.eqFocal > 0f) {
            "$base${lens.eqFocal.roundToInt()}"
        } else base
    }

    // ---------- 工具：圆形「工具」按钮展开 保存 / 预设 / 辅助线 / 闪光 ----------

    private fun setupUtilRow() {
        setupHubPopup()
    }

    private fun setupHubPopup() {
        binding.btnHub.setOnClickListener { openToolPopup() }
        binding.toolPopup.setOnClickListener { closeToolPopup() }
        // 右上角快捷入口：与工具弹层里的辅助线 / 闪光共用同一状态
        binding.btnTopGrid.setOnClickListener {
            gridIndex = (gridIndex + 1) % gridModes.size
            updateGrid()
            saveSettings()
        }
        binding.btnTopFlash.setOnClickListener {
            flashIndex = (flashIndex + 1) % flashLabels.size
            engine.setFlash(flashIndex)
            updateFlashLabel()
            saveSettings()
        }
        binding.btnTopFlip.setOnClickListener { flipCamera() }
        binding.btnPopTip.setOnClickListener {
            closeToolPopup()
            showTip(tipMode)
        }
        // 右侧（默认收起为三个小点，左滑展开）：保存预设 / 调入预设 / 全部恢复自动
        binding.btnSave.setOnClickListener { trySavePreset(); setSideExpanded(false) }
        binding.btnPreset.setOnClickListener { openPresetManager(); setSideExpanded(false) }
        binding.btnResetAuto.setOnClickListener { resetAllToAuto(); setSideExpanded(false) }
        binding.btnPopAbout.setOnClickListener {
            closeToolPopup()
            showAbout()
        }
        updateGrid()
        updateFlashLabel()
        updateFlipIcon()
    }

    private fun openToolPopup() {
        updateGrid()
        updateFlashLabel()
        binding.toolPopup.visibility = View.VISIBLE
        limitPanelHeight(binding.toolPanel)
    }

    private fun closeToolPopup() {
        binding.toolPopup.visibility = View.GONE
    }

    /** 右侧三枚功能键：true 展开为图标（可点），false 收起为三个小点 */
    private fun setSideExpanded(expanded: Boolean) {
        if (sideExpanded == expanded) return
        sideExpanded = expanded
        binding.sideDots.visibility = if (expanded) View.GONE else View.VISIBLE
        binding.sidePanel.visibility = if (expanded) View.VISIBLE else View.GONE
        binding.sidePanel.animate().cancel()
        binding.sidePanel.alpha = 0f
        binding.sidePanel.scaleX = 0.7f
        binding.sidePanel.scaleY = 0.7f
        binding.sidePanel.animate()
            .alpha(1f).scaleX(1f).scaleY(1f)
            .setDuration(150)
            .start()
    }

    /**
     * 上滑隐藏 / 下滑显示顶部工具：以下两块同时向上平移出屏幕，下滑再平移回来。
     * - 顶部工具条 topBar（画幅/帧率/分辨率、声控/辅助线/闪光/翻转、录制计时）
     * - 参数条 paramArea（变焦/帧率/快门/ISO/白平衡/EV/对焦）：位于顶部下方 116dp，
     *   连同自身高度与顶部边距一起上移才完全出屏（多给 8dp 余量）
     * 底部整块 bottomBar（焦段栏 + 快门/相册/录像/模式切换行）保持常驻，确保随时能拍摄。
     * 仅做平移不设 GONE，避免重新测量导致高度抖动；移出后位于屏幕外，不会误触。
     */
    private fun setTopBarHidden(hidden: Boolean) {
        if (topBarHidden == hidden) return
        topBarHidden = hidden
        val dur = 180L
        val interp = DecelerateInterpolator(1.3f)
        val bar = binding.topBar
        bar.animate().cancel()
        bar.animate()
            .translationY(if (hidden) -(bar.height + dp(48f)).toFloat() else 0f)
            .setDuration(dur)
            .setInterpolator(interp)
            .start()
        val params = binding.paramArea
        params.animate().cancel()
        params.animate()
            .translationY(if (hidden) -(params.height + dp(124f)).toFloat() else 0f)
            .setDuration(dur)
            .setInterpolator(interp)
            .start()
    }

    /** 同步构图辅助线：关闭时显示「辅助线」，开启时显示对应模式名 */
    private fun updateGrid() {
        val mode = gridModes[gridIndex]
        binding.overlay.gridMode = mode
        updateGridPopup()
    }

    private fun updateGridPopup() {
        // 仅同步右上角快捷图标（工具弹层不再含辅助线入口）
        binding.btnTopGrid.setColorFilter(
            if (gridIndex == 0) attrColor(R.attr.uiText) else attrColor(R.attr.uiAccent)
        )
    }

    private fun updateFlashLabel() {
        val on = flashIndex != 0
        binding.btnTopFlash.setColorFilter(
            if (on) attrColor(R.attr.uiAccent) else attrColor(R.attr.uiText)
        )
    }

    /** 前后摄像头切换：后置主摄 ↔ 前置，记住切走前的后置镜头以便切回 */
    private fun flipCamera() {
        if (engine.isRecording) { toast("录像中不能切换摄像头"); return }
        val isFront = currentLens?.facing == CameraCharacteristics.LENS_FACING_FRONT
        if (isFront) {
            val back = lastBackLens ?: Lenses.pickMain(lensOptions)
            if (back != null) switchLens(back) else toast("未找到后置摄像头")
        } else {
            val front = Lenses.frontLens(lensOptions)
            if (front == null) { toast("未找到前置摄像头"); return }
            lastBackLens = currentLens
            switchLens(front)
        }
    }

    /** 同步前后切换图标：前置时高亮提示当前在前置 */
    private fun updateFlipIcon() {
        val front = currentLens?.facing == CameraCharacteristics.LENS_FACING_FRONT
        binding.btnTopFlip.setColorFilter(
            if (front) attrColor(R.attr.uiAccent) else attrColor(R.attr.uiText)
        )
    }

    // ---------- 调节弹层 ----------

    private enum class AdjParam { SHUTTER, ISO, WB, COLOR, EV, FOCUS }

    private fun setupAdjSheet() {
        binding.adjSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                val adj = currentAdj ?: return
                when (adj) {
                    AdjParam.SHUTTER -> {
                        val ns = shutterStops.getOrElse(progress) { 0L }
                        binding.adjValue.text = ParamCoach.shutterLabel(ns)
                        if (!fromUser) return   // 程序化定位（自动跟随/打开面板）只刷新显示，不改参数
                        params = params.copy(shutterNs = ns)
                    }
                    AdjParam.ISO -> {
                        val iso = isoStops.getOrElse(progress) { 0 }
                        binding.adjValue.text = ParamCoach.isoLabel(iso)
                        if (!fromUser) return
                        params = params.copy(iso = iso)
                    }
                    AdjParam.EV -> {
                        val ev = (progress - 4) * 0.5f
                        binding.adjValue.text = ParamCoach.evLabel(ev)
                        if (!fromUser) return
                        params = params.copy(ev = ev)
                    }
                    AdjParam.FOCUS -> {
                        val m = if (progress == 0) 0f else progress / 10f
                        binding.adjValue.text = if (m == 0f) "自动" else String.format("%.1f", m) + "m"
                        if (!fromUser) return
                        params = params.copy(focusMeters = m)
                    }
                    AdjParam.WB -> {
                        // 手动白平衡由应用层连续生效，不再受 HAL 档位粒度限制，可自由微调
                        val k = (WB_LO + progress * WB_STEP).coerceIn(WB_LO, WB_HI)
                        binding.adjValue.text = ParamCoach.wbLabel(k)
                        if (!fromUser) return
                        params = params.copy(kelvin = k)
                        refreshWbChips()
                    }
                    AdjParam.COLOR -> { /* 调色用独立滑杆，不在此处理 */ }
                }
                if (fromUser) {
                    applyParams()
                    updateAdjAutoState()
                }
            }

            override fun onStartTrackingTouch(sb: SeekBar?) = Unit
            override fun onStopTrackingTouch(sb: SeekBar?) = Unit
        })

        // 面板改成了可滚动容器：拖动滑杆时禁止父容器抢走触摸事件
        listOf(binding.adjSeek, binding.seekSharp, binding.seekSat).forEach { sb ->
            sb.setOnTouchListener { v, _ ->
                v.parent?.requestDisallowInterceptTouchEvent(true)
                false
            }
        }

        binding.adjAuto.setOnClickListener { resetCurrentToAuto() }
        binding.adjScrim.setOnClickListener { closeSheet() }
        // 「?」问号：默认不展示说明文字，点击才展开/收起，面板更简洁
        binding.adjHelp.setOnClickListener { toggleHelp() }

        colorSeek(binding.seekSharp) { p ->
            params = params.copy(sharpness = p)
            binding.valSharp.text = "${p}"
            applyParams()
            updateAdjAutoState()
        }
        colorSeek(binding.seekSat) { p ->
            params = params.copy(saturation = (p / 100f).coerceIn(0f, 2f))
            binding.valSat.text = String.format("%.1f", params.saturation)
            applyParams()
            updateAdjAutoState()
        }
        colorSeek(binding.seekTint) { p ->
            params = params.copy(tint = (p / 100f - 1f).coerceIn(-1f, 1f))
            binding.valTint.text = if (params.tint == 0f) "中性" else String.format("%.2f", params.tint)
            applyParams()
            updateAdjAutoState()
        }
    }

    /** 调色滑杆公用监听 */
    private fun colorSeek(sb: SeekBar, on: (Int) -> Unit) {
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) on(progress)
            }
            override fun onStartTrackingTouch(s: SeekBar?) = Unit
            override fun onStopTrackingTouch(s: SeekBar?) = Unit
        })
    }

    private fun closeSheet() {
        currentAdj = null
        stopProbe()
        binding.adjScrim.visibility = View.GONE
        binding.adjSheet.visibility = View.GONE
    }

    private fun openSheet(param: AdjParam) {
        currentAdj = param
        binding.adjScrim.visibility = View.VISIBLE
        binding.adjSheet.visibility = View.VISIBLE
        limitPanelHeight(binding.adjSheet)

        binding.adjSliderWrap.visibility = View.VISIBLE
        binding.adjWbRow.visibility = View.GONE
        binding.colorPanel.visibility = View.GONE
        binding.tintPanel.visibility = View.GONE
        binding.wbPathRow.visibility = View.GONE
        binding.wbPathStatus.visibility = View.GONE
        binding.adjValue.textSize = 13f
        // 每次打开面板都默认收起说明文字（点「?」才展开）
        binding.adjSub.visibility = View.GONE
        binding.adjHelp.setColorFilter(attrColor(R.attr.uiTextDim))

        when (param) {
            AdjParam.SHUTTER -> {
                binding.adjTitle.text = "快门（S）"
                binding.adjSub.text = "拍摄图片时，分母数字越大画面越暗，画面相对越清晰，适合光线环境好的场景。拍摄视频时，设置成跟帧速率一致或者帧速率的两倍。"
                binding.adjLabel.text = "快门速度"
                val seed = if (params.shutterNs > 0L) params.shutterNs else engine.lastActualShutterNs ?: 0L
                val idx = if (params.shutterNs > 0L) shutterStops.indexOfFirst { it == seed }.coerceAtLeast(0) else nearestShutterIndex(seed)
                binding.adjSeek.max = maxOf(0, shutterStops.size - 1)
                binding.adjSeek.progress = idx
                binding.adjValue.text = ParamCoach.shutterLabel(shutterStops.getOrElse(idx) { 0L })
            }
            AdjParam.ISO -> {
                binding.adjTitle.text = "感光度（ISO）"
                binding.adjSub.text = "ISO 越低画质越干净；暗光下拉高会出噪点，建议先补光再考虑降 ISO。"
                binding.adjLabel.text = "感光度"
                val seed = if (params.iso > 0) params.iso else engine.lastActualIso ?: 0
                val idx = if (params.iso > 0) isoStops.indexOfFirst { it == seed }.coerceAtLeast(0) else nearestIsoIndex(seed)
                binding.adjSeek.max = maxOf(0, isoStops.size - 1)
                binding.adjSeek.progress = idx
                binding.adjValue.text = ParamCoach.isoLabel(isoStops.getOrElse(idx) { 0 })
            }
            AdjParam.EV -> {
                binding.adjTitle.text = "曝光补偿（EV）"
                binding.adjSub.text = "只在自动模式生效，可手动提高或降低画面亮度。"
                binding.adjLabel.text = "曝光补偿 EV"
                val idx = (evStops.indexOf(params.ev).coerceAtLeast(0))
                binding.adjSeek.max = evStops.size - 1
                binding.adjSeek.progress = idx
                binding.adjValue.text = ParamCoach.evLabel(params.ev)
            }
            AdjParam.FOCUS -> {
                binding.adjTitle.text = "对焦（F）"
                binding.adjSub.text = "固定机位拍商品特写时锁定对焦距离，避免自动对焦来回拉风箱。"
                binding.adjLabel.text = "对焦距离"
                val idx = (params.focusMeters * 10).toInt().coerceIn(0, 100)
                binding.adjSeek.max = 100
                binding.adjSeek.progress = idx
                binding.adjValue.text = if (params.focusMeters == 0f) "自动"
                else String.format("%.1f", params.focusMeters) + "m"
            }
            AdjParam.WB -> {
                binding.adjTitle.text = "白平衡（WB）"
                binding.adjSub.text = "数字越大画面越暖（偏黄），数字越小画面越冷（偏蓝）。可点击数字卡片快速设置。如果“处理路径”为自动时没有效果，可以点击硬件或软件进行尝试。如果手动设置白平衡，色彩仍然有偏差，可尝试调整色调。"
                binding.adjSliderWrap.visibility = View.VISIBLE
                binding.adjWbRow.visibility = View.VISIBLE
                binding.adjLabel.text = ""
                binding.adjValue.textSize = 7f
                val maxP = (WB_HI - WB_LO) / WB_STEP
                // 自动时滑杆停在当前实测色温上，从这儿往两侧拖才是「相对现在的偏冷/偏暖」，切换不会跳
                val seedK = if (params.kelvin > 0) params.kelvin
                else if (lastAutoWbKelvin > 0) lastAutoWbKelvin else 5600
                binding.adjSeek.max = maxP
                binding.adjSeek.progress = ((seedK - WB_LO) / WB_STEP).coerceIn(0, maxP)
                // 自动模式也实时显示当前色温数值（与 ISO / 快门一致），不再只显示「自动」
                binding.adjValue.text = ParamCoach.wbLabel(seedK)
                binding.wbPathRow.visibility = View.VISIBLE
                binding.wbPathStatus.visibility = View.VISIBLE
                // 色调（Tint）轴：与色温同属白平衡，左滑偏绿、右滑偏紫（Lightroom 约定）
                binding.tintPanel.visibility = View.VISIBLE
                binding.seekTint.max = 200
                binding.seekTint.progress = ((params.tint + 1f) / 2f * 200f).roundToInt().coerceIn(0, 200)
                binding.valTint.text = if (params.tint == 0f) "中性" else String.format("%.2f", params.tint)
                updateWbPathUi()
                buildWbChips()
            }
            AdjParam.COLOR -> {
                binding.adjTitle.text = "调色（C）"
                binding.adjSub.text = "饱和度 1.0 为原始、小于 1 偏灰、0 为黑白、大于 1 更鲜艳；清晰度增强局部对比，让画面更通透。色彩还原请用白平衡。"
                binding.adjSliderWrap.visibility = View.GONE
                binding.adjWbRow.visibility = View.GONE
                binding.colorPanel.visibility = View.VISIBLE
                binding.seekSat.progress = (params.saturation * 100f).toInt().coerceIn(0, 200)
                binding.valSat.text = String.format("%.1f", params.saturation)
                binding.seekSharp.progress = params.sharpness.coerceIn(0, 100)
                binding.valSharp.text = "${params.sharpness}"
            }
        }
        updateAdjAutoState()
        // 半手动（只手动 ISO / 快门其一）时，面板打开期间低频探测自动曝光基线，
        // 让另一个仍为自动的参数真正跟着光线走；面板一关立刻停止，不影响日常拍摄
        if (param == AdjParam.SHUTTER || param == AdjParam.ISO) startProbe() else stopProbe()
    }

    private fun buildWbChips() {
        binding.adjWbRow.removeAllViews()
        val pad = (resources.displayMetrics.density * 6).toInt()
        ParamCoach.WB_PRESETS.forEach { k ->
            val tv = TextView(this).apply {
                text = ParamCoach.wbLabel(k)
                textSize = 6f
                gravity = Gravity.CENTER
                val selected = k == params.kelvin
                setTextColor(
                    if (selected) attrColor(R.attr.uiAccentOn) else attrColor(R.attr.uiTextDim)
                )
                setBackgroundColor(
                    if (selected) attrColor(R.attr.uiAccent) else attrColor(R.attr.uiChip)
                )
                setPadding(pad, pad / 2, pad, pad / 2)
                setOnClickListener {
                    params = params.copy(kelvin = k)
                    syncWbSeek()
                    refreshWbChips()
                    applyParams()
                    updateAdjAutoState()
                }
            }
            val lp = android.widget.LinearLayout.LayoutParams(0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            lp.setMargins(3, 0, 3, 0)
            binding.adjWbRow.addView(tv, lp)
        }
    }

    /** 仅刷新色温片高亮，避免拖动滑杆时反复重建 */
    private fun refreshWbChips() {
        for (i in 0 until binding.adjWbRow.childCount) {
            val tv = binding.adjWbRow.getChildAt(i) as TextView
            val k = ParamCoach.WB_PRESETS.getOrNull(i) ?: continue
            val sel = k == params.kelvin
            tv.setTextColor(if (sel) attrColor(R.attr.uiAccentOn) else attrColor(R.attr.uiTextDim))
            tv.setBackgroundColor(if (sel) attrColor(R.attr.uiAccent) else attrColor(R.attr.uiChip))
        }
    }

    /** 把当前色温同步到滑杆位置（自动/0 不移动） */
    private fun syncWbSeek() {
        if (params.kelvin <= 0) return
        val maxP = (WB_HI - WB_LO) / WB_STEP
        binding.adjSeek.progress = ((params.kelvin - WB_LO) / WB_STEP).coerceIn(0, maxP)
    }

    private fun resetCurrentToAuto() {
        when (currentAdj) {
            // 快门 / ISO 各自独立：点哪个自动只清哪个，互不影响，避免「点快门自动把 ISO 也清零」的连坐
            AdjParam.SHUTTER -> params = params.copy(shutterNs = 0L)
            AdjParam.ISO -> params = params.copy(iso = 0)
            AdjParam.EV -> params = params.copy(ev = 0f)
            AdjParam.FOCUS -> params = params.copy(focusMeters = 0f)
            AdjParam.WB -> params = params.copy(kelvin = 0, tint = 0f)
            AdjParam.COLOR -> params = params.copy(sharpness = 0, saturation = 1f)
            null -> return
        }
        if (currentAdj != null) openSheet(currentAdj!!)
        applyParams()
    }

    /** 是否所有参数仍为自动（任一被手动设置即视为「有设置」） */
    private fun allParamsAuto(): Boolean =
        params.shutterNs == 0L && params.iso == 0 && params.kelvin == 0 && params.tint == 0f &&
                abs(params.ev) <= 0.05f && params.focusMeters == 0f && !colorAdjusted()

    /** 右侧「保存」：全部为自动时提示并不保存；否则走命名弹窗 */
    private fun trySavePreset() {
        if (allParamsAuto()) {
            toast("没有任何设置")
            return
        }
        savePreset()
    }

    /** 右侧「复位」：一键把所有参数恢复为自动并即时下发 */
    private fun resetAllToAuto() {
        params = params.copy(
            shutterNs = 0L, iso = 0, kelvin = 0, tint = 0f,
            ev = 0f, focusMeters = 0f, sharpness = 0, saturation = 1f
        )
        if (currentAdj != null) openSheet(currentAdj!!)
        applyParams()
        toast("已恢复全部为自动")
    }

    /** 点「?」展开/收起当前参数的说明文字；展开时问号变色提示 */
    private fun toggleHelp() {
        val expanded = binding.adjSub.visibility != View.VISIBLE
        binding.adjSub.visibility = if (expanded) View.VISIBLE else View.GONE
        binding.adjHelp.setColorFilter(
            attrColor(if (expanded) R.attr.uiAccent else R.attr.uiTextDim)
        )
    }

    private fun updateAdjAutoState() {
        val active = when (currentAdj) {
            AdjParam.SHUTTER -> params.shutterNs > 0L
            AdjParam.ISO -> params.iso > 0
            AdjParam.EV -> abs(params.ev) > 0.05f
            AdjParam.FOCUS -> params.focusMeters > 0f
            AdjParam.WB -> params.kelvin > 0 || abs(params.tint) > 0.01f
            AdjParam.COLOR -> colorAdjusted()
            null -> false
        }
        binding.adjAuto.setTextColor(
            if (active) attrColor(R.attr.uiAccent) else attrColor(R.attr.uiTextDim)
        )
    }

    /**
     * 自动模式下，参数面板打开时让 ISO / 快门滑杆跟随相机真实曝光值：
     * 光线一变，控制点就滑动到当前自动档位；用户一旦手动拖动（参数非 0）即停止跟随。
     */
    private fun autoTrack(actIso: Int?, actSh: Long?, actWbK: Int?) {
        when (currentAdj) {
            AdjParam.SHUTTER -> {
                if (params.shutterNs > 0L) return          // 已手动，停止跟随
                // 半手动（ISO 已手动）：快门取软自动值 = 基线曝光量 ÷ 手动 ISO，随光线变化
                val v = if (params.iso > 0) engine.softAutoShutterNs() else actSh
                val target = v ?: return
                val idx = nearestShutterIndex(target)
                if (idx in shutterStops.indices && idx != binding.adjSeek.progress) {
                    binding.adjSeek.progress = idx
                }
            }
            AdjParam.ISO -> {
                if (params.iso > 0) return
                // 半手动（快门已手动）：ISO 取软自动值 = 基线曝光量 ÷ 手动快门，随光线变化
                val v = if (params.shutterNs > 0L) engine.softAutoIso() else actIso
                val target = v ?: return
                val idx = nearestIsoIndex(target)
                if (idx in isoStops.indices && idx != binding.adjSeek.progress) {
                    binding.adjSeek.progress = idx
                }
            }
            AdjParam.WB -> {
                if (params.kelvin > 0) return              // 已手动指定色温，停止跟随
                val k = actWbK ?: if (lastAutoWbKelvin > 0) lastAutoWbKelvin else return
                val maxP = (WB_HI - WB_LO) / WB_STEP
                val prog = ((k - WB_LO) / WB_STEP).coerceIn(0, maxP)
                if (prog != binding.adjSeek.progress) binding.adjSeek.progress = prog
                binding.adjValue.text = ParamCoach.wbLabel(k)   // 自动模式也实时刷新当前色温数值
            }
            else -> return
        }
    }

    /** 在快门档位里找与真实值最接近的索引（用于把连续自动曝光对齐到离散滑杆） */
    private fun nearestShutterIndex(value: Long): Int {
        if (value <= 0L) return 0
        var best = 0
        var bestDiff = Long.MAX_VALUE
        for (i in shutterStops.indices) {
            val d = kotlin.math.abs(shutterStops[i] - value)
            if (d < bestDiff) { bestDiff = d; best = i }
        }
        return best
    }

    /** 在 ISO 档位里找与真实值最接近的索引 */
    private fun nearestIsoIndex(value: Int): Int {
        if (value <= 0) return 0
        var best = 0
        var bestDiff = Int.MAX_VALUE
        for (i in isoStops.indices) {
            val d = kotlin.math.abs(isoStops[i] - value)
            if (d < bestDiff) { bestDiff = d; best = i }
        }
        return best
    }

    // ---------- 保存 / 预设（多预设，可改名）----------

    private fun loadPresets(): MutableList<Preset> {
        val raw = prefs.getString("list", null) ?: return mutableListOf()
        val out = mutableListOf<Preset>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(
                    Preset(
                        o.optString("name", "预设"),
                        o.optInt("iso"),
                        o.optLong("shutterNs"),
                        o.optInt("kelvin"),
                        o.optDouble("tint").toFloat(),
                        o.optDouble("ev").toFloat(),
                        o.optDouble("focus").toFloat(),
                        o.optInt("fps", 25)
                    )
                )
            }
        } catch (_: Exception) {
        }
        return out
    }

    private fun persistPresets(list: List<Preset>) {
        val arr = JSONArray()
        list.forEach { p ->
            arr.put(
                JSONObject().apply {
                    put("name", p.name)
                    put("iso", p.iso)
                    put("shutterNs", p.shutterNs)
                    put("kelvin", p.kelvin)
                    put("tint", p.tint)
                    put("ev", p.ev)
                    put("focus", p.focus)
                    put("fps", p.fps)
                }
            )
        }
        prefs.edit().putString("list", arr.toString()).apply()
    }

    /** 保存当前参数为一个新预设，弹窗输入名字（默认「预设N」） */
    private fun savePreset() {
        val list = loadPresets()
        val defaultName = "预设${list.size + 1}"
        val edit = EditText(this).apply {
            setText(defaultName)
            setSelection(defaultName.length)
            textSize = 16f
        }
        AlertDialog.Builder(this)
            .setTitle("保存预设")
            .setMessage("给这套参数起个名字")
            .setView(edit)
            .setPositiveButton("保存") { _, _ ->
                val name = edit.text.toString().ifBlank { defaultName }
                list.add(Preset(name, params.iso, params.shutterNs, params.kelvin, params.tint, params.ev, params.focusMeters, params.fps))
                persistPresets(list)
                toast("已保存：$name")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 打开预设管理器：点名称套用，改名 / 删除逐项操作 */
    private fun openPresetManager() {
        val list = loadPresets()
        if (list.isEmpty()) {
            toast("还没有保存预设，先调好参数点「保存」")
            return
        }
        val scroll = ScrollView(this)
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 16, 24, 16)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle("我的预设（点名称套用）")
            .setView(scroll)
            .setNegativeButton("关闭", null)
            .create()

        fun rebuild() {
            container.removeAllViews()
            if (list.isEmpty()) {
                container.addView(TextView(this).apply {
                    text = "暂无预设"
                    textSize = 14f
                    setTextColor(attrColor(R.attr.uiTextDim))
                })
            }
            list.forEachIndexed { idx, p ->
                val row = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                val name = TextView(this).apply {
                    text = p.name
                    textSize = 16f
                    setTextColor(attrColor(R.attr.uiText))
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    setOnClickListener {
                        params = params.copy(
                            iso = p.iso, shutterNs = p.shutterNs, kelvin = p.kelvin,
                            ev = p.ev, focusMeters = p.focus, fps = p.fps
                        )
                        if (currentAdj != null) openSheet(currentAdj!!)
                        applyParams()
                        dialog.dismiss()
                        toast("已套用：${p.name}")
                    }
                }
                val rename = TextView(this).apply {
                    text = "改名"
                    textSize = 13f
                    setPadding(14, 8, 14, 8)
                    setTextColor(attrColor(R.attr.uiAccent))
                    setOnClickListener { renamePresetDialog(list, idx) { rebuild() } }
                }
                val del = TextView(this).apply {
                    text = "删除"
                    textSize = 13f
                    setPadding(14, 8, 14, 8)
                    setTextColor(attrColor(R.attr.uiWarn))
                    setOnClickListener {
                        list.removeAt(idx)
                        persistPresets(list)
                        rebuild()
                    }
                }
                row.addView(name)
                row.addView(rename)
                row.addView(del)
                container.addView(row)
            }
        }
        rebuild()
        scroll.addView(container)
        dialog.show()
    }

    private fun renamePresetDialog(list: MutableList<Preset>, idx: Int, onDone: () -> Unit) {
        val p = list[idx]
        val edit = EditText(this).apply {
            setText(p.name)
            setSelection(p.name.length)
            textSize = 16f
        }
        AlertDialog.Builder(this)
            .setTitle("重命名预设")
            .setView(edit)
            .setPositiveButton("确定") { _, _ ->
                val name = edit.text.toString().ifBlank { p.name }
                list[idx] = p.copy(name = name)
                persistPresets(list)
                onDone()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ---------- 拍摄按钮 ----------

    private fun setupCaptureButtons() {
        // 单拍摄键：图片模式拍照，视频模式开始/停止录制
        binding.btnPhoto.setOnClickListener { onShutterPressed() }

        // 右上角声控开关：说「拍照」即按下快门
        binding.btnTopVoice.setOnClickListener { toggleVoice() }

        // 图片 / 视频模式切换
        binding.tvModePhoto.setOnClickListener { setVideoMode(false) }
        binding.tvModeVideo.setOnClickListener { setVideoMode(true) }
        updateModeUi()

        binding.btnGallery.setOnClickListener {
            // 打开素材库查看已拍内容（照片 + 视频，视频调用系统播放器播放）。
            //
            // 曾经尝试直拉系统相册（ACTION_VIEW + 各媒体集合），走不通：
            //   · 图片集合（.../images/media）只列照片，看不到视频；
            //   · Files 集合（.../file）虽含视频，但绝大多数机型没有应用声明该类型，
            //     startActivity 直接 ActivityNotFoundException，只能回退 → 仍然只有图片；
            //   · ACTION_MAIN + CATEGORY_APP_GALLERY 会被当成「桌面启动」，返回键退桌面回不来。
            // 应用内素材库（ShotStore 查询 Pictures/ShotLab + 视频目录）照片视频都覆盖，
            // 自带返回按钮，点视频走系统播放器，行为完全可控。
            startActivity(Intent(this, GalleryActivity::class.java))
        }
    }

    /** 统一快门逻辑：图片模式拍照，视频模式开始/停止录制。屏幕按钮与音量键/蓝牙遥控共用 */
    private fun onShutterPressed() {
        if (videoMode) {
            if (engine.isRecording) {
                engine.stopRecording()
                syncVoice()
            } else {
                val audio = ContextCompat.checkSelfPermission(
                    this, Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED
                engine.startRecording(params.fps, audio)
                syncVoice()     // 开始录像就把麦克风让给录音，声控自动关闭
            }
        } else {
            animateCore(binding.photoCore)
            engine.takePhoto()
        }
    }

    /** 音量键 / 耳机线控 / 蓝牙遥控快门：图片模式拍照，视频模式开始/停止录制 */
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (currentLens == null) return super.onKeyDown(keyCode, event)
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
            keyCode == KeyEvent.KEYCODE_CAMERA || keyCode == KeyEvent.KEYCODE_HEADSETHOOK) {
            if ((event?.repeatCount ?: 0) > 0) return true   // 屏蔽长按连发，单次触发
            onShutterPressed()
            return true   // 消费按键，避免系统音量条弹出
        }
        return super.onKeyDown(keyCode, event)
    }

    // ---------- 声控拍照（离线，模型内置） ----------

    private fun toggleVoice() {
        // 视频模式 / 录像中不启用声控，静默返回（按用户要求不弹提示）
        if (videoMode || engine.isRecording) return
        voiceEnabled = !voiceEnabled
        voiceHinted = false
        if (voiceEnabled) {
            startVoice()
        } else {
            stopVoice()
            toast("声控已关闭")
        }
        updateVoiceIcon()
    }

    /** 声控引擎：内置 TFLite 关键词唤醒（离线，不依赖系统语音服务） */
    private fun createVoiceEngine(): VoiceEngine = VoiceTrigger(this)

    private fun startVoice() {
        val vt = voiceEngine ?: createVoiceEngine().also { voiceEngine = it }
        if (!voiceHinted) {
            toast("说声“拍照”即可")
            voiceHinted = true
        }
        vt.start(object : VoiceEngine.Callback {
            override fun onReady() {
                voiceHinted = true
                updateVoiceIcon()
                // 不弹提示：开启时已提示过一次，图标由暗变亮即表示就绪
            }

            override fun onTrigger(word: String) {
                if (videoMode || engine.isRecording) return
                animateCore(binding.photoCore)
                engine.takePhoto()
                // 不弹提示：快门动画与成片本身就是反馈
            }

            override fun onError(msg: String) {
                voiceEnabled = false
                updateVoiceIcon()
                toast(msg)
            }
        })
    }

    private fun stopVoice() {
        voiceEngine?.stop()
        updateVoiceIcon()
    }

    /** 视频模式 / 录像中自动让位；回到图片模式且开关仍开着则自动恢复监听 */
    private fun syncVoice() {
        val allowed = !videoMode && !engine.isRecording
        if (!allowed) {
            if (voiceEngine?.isRunning() == true) {
                // 录像时静默让出麦克风，不弹提示
                voiceEngine?.stop()
            }
        } else if (voiceEnabled && voiceEngine?.isRunning() != true) {
            startVoice()
        }
        updateVoiceIcon()
    }

    /** 图标形状 = 开关意愿；明暗 = 是否真正在监听（准备中显示为暗色麦克风）。
     *  视频模式 / 录像中声控不可用：强制灰色关闭态（半透明），不可误以为还在监听。 */
    private fun updateVoiceIcon() {
        val blocked = videoMode || engine.isRecording
        val live = voiceEnabled && !blocked && voiceEngine?.isRunning() == true
        binding.btnTopVoice.setImageResource(
            if (voiceEnabled && !blocked) R.drawable.ic_voice else R.drawable.ic_voice_off
        )
        val tv = android.util.TypedValue()
        theme.resolveAttribute(
            if (live) R.attr.uiText else R.attr.uiTextDim, tv, true
        )
        binding.btnTopVoice.imageTintList =
            android.content.res.ColorStateList.valueOf(tv.data)
        binding.btnTopVoice.alpha = if (blocked) 0.55f else 1f
    }

    // ---------- 录像实时音量条 ----------

    private fun startVolumeMeter() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) return
        if (volRec != null) return
        val sr = 16000
        val minBuf = AudioRecord.getMinBufferSize(sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = runCatching {
            AudioRecord(
                MediaRecorder.AudioSource.MIC, sr,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuf, 2048)
            )
        }.getOrNull()
        // 录像已独占麦克风时，第二个采集往往初始化失败 → 静默放弃，不影响录音/录像
        if (rec == null || rec.state != AudioRecord.STATE_INITIALIZED) {
            runCatching { rec?.release() }
            return
        }
        volRec = rec
        runCatching { rec.startRecording() }
        binding.volumeMeter.visibility = View.VISIBLE
        updateVolumeFill(0.0)
        volThread = Thread {
            val buf = ShortArray(1024)
            try {
                while (!Thread.currentThread().isInterrupted) {
                    val n = runCatching { rec.read(buf, 0, buf.size) }.getOrDefault(0)
                    if (n <= 0) { Thread.sleep(60); continue }
                    var sum = 0.0
                    for (i in 0 until n) {
                        val v = buf[i].toDouble()
                        sum += v * v
                    }
                    val rms = kotlin.math.sqrt(sum / n) / 32768.0
                    val level = (rms * 3.2).coerceIn(0.0, 1.0)   // 放大便于观察，封顶 1.0
                    runOnUiThread { updateVolumeFill(level) }
                    Thread.sleep(80)
                }
            } catch (_: InterruptedException) {
                // stopVolumeMeter() 会 interrupt 本线程来正常结束：视为退出信号，不算异常，避免冒泡成崩溃
            }
        }.also { it.start() }
    }

    private fun stopVolumeMeter() {
        volThread?.interrupt()
        volThread = null
        runCatching { volRec?.stop() }
        runCatching { volRec?.release() }
        volRec = null
        binding.volumeMeter.visibility = View.GONE
        updateVolumeFill(0.0)
    }

    private fun updateVolumeFill(level: Double) {
        val w = (binding.volumeMeter.width * level).toInt()
        val lp = binding.volumeFill.layoutParams
        if (lp.width != w) {
            lp.width = w
            binding.volumeFill.requestLayout()
        }
    }

    /** 切换图片/视频拍摄模式（录制中禁止切换），同步切换键与拍摄键外观。
     *
     * 视频模式：画幅强制锁定为 3:4（对应存储 4:3），并把「画幅」徽章置灰不可点 ——
     *   视频只有一种比例，留着选项只会误导。原先用户选择的画幅记在 [photoRatioIndex]，切回时原样恢复。
     */
    private fun setVideoMode(v: Boolean) {
        if (v == videoMode) return
        if (engine.isRecording) { toast("录像中不能切换模式"); return }
        videoMode = v
        if (videoMode) {
            photoRatioIndex = ratioIndex                  // 记住图片模式的画幅
            ratioIndex = VIDEO_RATIO_INDEX                // 视频固定 3:4
        } else {
            ratioIndex = photoRatioIndex                  // 回到图片模式，恢复用户原本的选择
        }
        applyRatio()
        updateQuality()
        updateRatioChipState()
        updateVideoChipState()
        updateModeUi()
        syncVoice()          // 视频模式下声控自动让位
    }

    /** 视频模式：画幅徽章置灰 + 禁用点击；图片模式：恢复正常（强调色、可点） */
    private fun updateRatioChipState() {
        binding.tvQuality.isEnabled = !videoMode
        binding.tvQuality.alpha = if (videoMode) 0.45f else 1f
        binding.tvQuality.setTextColor(
            if (videoMode) attrColor(R.attr.uiTextDim) else attrColor(R.attr.uiText)
        )
    }

    /** 照片模式：帧率 / 视频分辨率 徽章置灰 + 禁用点击；视频模式：恢复正常 */
    private fun updateVideoChipState() {
        val dim = !videoMode
        val a = if (dim) 0.4f else 1f
        val c = if (dim) attrColor(R.attr.uiTextDim) else attrColor(R.attr.uiText)
        binding.tvFpsLabel.isEnabled = !dim
        binding.tvFps.isEnabled = !dim
        binding.tvResLabel.isEnabled = !dim
        binding.tvRes.isEnabled = !dim
        binding.tvFpsLabel.alpha = a; binding.tvFps.alpha = a
        binding.tvResLabel.alpha = a; binding.tvRes.alpha = a
        binding.tvFpsLabel.setTextColor(c); binding.tvFps.setTextColor(c)
        binding.tvResLabel.setTextColor(c); binding.tvRes.setTextColor(c)
    }

    private fun updateModeUi() {
        // 分段切换：当前项为琥珀胶囊高亮（黑字改为图示白字风格），另一项无底色灰白文字
        binding.tvModePhoto.setBackgroundResource(if (videoMode) 0 else R.drawable.bg_chip_active)
        binding.tvModeVideo.setBackgroundResource(if (videoMode) R.drawable.bg_chip_active else 0)
        binding.tvModePhoto.setTypeface(null, if (videoMode) Typeface.NORMAL else Typeface.BOLD)
        binding.tvModeVideo.setTypeface(null, if (videoMode) Typeface.BOLD else Typeface.NORMAL)
        // 拍摄键中心：图片模式白色圆点，视频模式红色圆点
        binding.photoCore.setBackgroundResource(if (videoMode) R.drawable.bg_core_red else R.drawable.bg_core)
    }

    /** 白平衡面板里的「处理路径」：默认自动体检，也可强制硬件 / 软件 */
    private fun setupWbPathRow() {
        binding.wbPathAuto.setOnClickListener { setWbMode(WbMode.AUTO) }
        binding.wbPathHw.setOnClickListener { setWbMode(WbMode.HARDWARE) }
        binding.wbPathSw.setOnClickListener { setWbMode(WbMode.SOFTWARE) }
        engine.onWbPathChanged = { if (currentAdj == AdjParam.WB) updateWbPathUi() }
    }

    private fun setWbMode(mode: WbMode) {
        if (engine.wbMode == mode) return
        engine.wbMode = mode
        saveSettings()
        updateWbPathUi()
        applyParams()   // 路径变了必须立即重下发，否则要等下一次拖滑杆才见效
    }

    private fun updateWbPathUi() {
        val mode = engine.wbMode
        fun style(tv: TextView, selected: Boolean) {
            tv.setTextColor(if (selected) attrColor(R.attr.uiAccent) else attrColor(R.attr.uiText))
            tv.setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
        }
        style(binding.wbPathAuto, mode == WbMode.AUTO)
        style(binding.wbPathHw, mode == WbMode.HARDWARE)
        style(binding.wbPathSw, mode == WbMode.SOFTWARE)
        binding.wbPathStatus.text = engine.wbPathText()
    }

    private fun setupTips() {
        binding.tabShot.setOnClickListener { showTip(0) }
        binding.tabMove.setOnClickListener { showTip(1) }
        binding.tabComp.setOnClickListener { showTip(2) }
        binding.tabSkill.setOnClickListener { showTip(3) }
        binding.btnTipClose.setOnClickListener { binding.tipLayer.visibility = View.GONE }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (binding.tipLayer.visibility == View.VISIBLE) {
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    tipSwipeStartX = ev.x
                    tipSwipeStartY = ev.y
                    tipSwiping = true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (tipSwiping) {
                        val dx = ev.x - tipSwipeStartX
                        val dy = ev.y - tipSwipeStartY
                        // 明显的向左滑动（左移 > 80px、且以横向位移为主）即关闭
                        if (dx < -80 && kotlin.math.abs(dx) > kotlin.math.abs(dy) * 1.5f) {
                            binding.tipLayer.visibility = View.GONE
                            tipSwiping = false
                        }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> tipSwiping = false
            }
            return super.dispatchTouchEvent(ev)
        }
        // 右侧功能键手势：左滑展开、右滑收起（无弹层时才响应）
        val popupOpen = binding.toolPopup.visibility == View.VISIBLE ||
                binding.choicePopup.visibility == View.VISIBLE ||
                binding.adjScrim.visibility == View.VISIBLE
        if (!popupOpen) {
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    swipeStartX = ev.x
                    swipeStartY = ev.y
                    swipeTracking = true
                    swipeConsumed = false
                    sideDragging = false
                    swipeVelocity?.recycle()
                    swipeVelocity = VelocityTracker.obtain()
                    swipeVelocity?.addMovement(ev)
                }
                MotionEvent.ACTION_MOVE -> {
                    swipeVelocity?.addMovement(ev)
                    // 多指（双指捏合缩放）时停止追踪：避免捏合过程误触发横/纵向滑动
                    if (ev.pointerCount > 1) swipeTracking = false
                    if (swipeTracking && !swipeConsumed) {
                        val dx = ev.x - swipeStartX
                        val dy = ev.y - swipeStartY
                        // 以横向位移为主，避免与纵向滑动（焦段/参数）冲突
                        val horizontal = kotlin.math.abs(dx) > kotlin.math.abs(dy) * 1.5f
                        if (horizontal && dx < -80) {
                            // 左滑：展开右侧功能键（左侧行程充裕，沿用位移阈值）
                            swipeConsumed = true
                            setSideExpanded(true)
                        } else if (horizontal && sideExpanded && dx > 0) {
                            // 右滑：收起。面板贴右边缘，向右行程极短，
                            // 因此「短位移 + 甩动速度」或「较长位移」任一满足即收起
                            sideDragging = true
                            binding.rightActions.translationX = dx.coerceIn(0f, dragMaxPx)
                            swipeVelocity?.computeCurrentVelocity(1000)
                            val vx = swipeVelocity?.xVelocity ?: 0f
                            val flick = dx >= collapseMinPx && vx >= collapseMinSpeed
                            val longDrag = dx >= collapseFastPx
                            if (flick || longDrag) {
                                swipeConsumed = true
                                sideDragging = false
                                binding.rightActions.translationX = 0f
                                setSideExpanded(false)
                            }
                        } else if (kotlin.math.abs(dy) > kotlin.math.abs(dx) * 1.5f &&
                            kotlin.math.abs(dy) > 60
                        ) {
                            // 纵向滑动：上滑隐藏顶部工具条，下滑重新显示
                            // 与横向手势互斥（各自要求本方向位移 ≥ 另一方向的 1.5 倍）
                            swipeConsumed = true
                            setTopBarHidden(dy < 0)
                        }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    // 未达收起阈值：面板回弹原位
                    if (sideDragging) {
                        sideDragging = false
                        binding.rightActions.animate().cancel()
                        binding.rightActions.animate().translationX(0f).setDuration(120).start()
                    }
                    swipeVelocity?.recycle()
                    swipeVelocity = null
                    // ACTION_UP 不重置 swipeConsumed：让子 View 判断本次是否为滑动手势，避免滑动时误对焦
                    if (ev.action == MotionEvent.ACTION_CANCEL) swipeTracking = false
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun showTipTabs(mode: Int) {
        tipMode = mode
        val tabs = listOf(binding.tabShot, binding.tabMove, binding.tabComp, binding.tabSkill)
        val onColor = attrColor(R.attr.uiAccent)
        val offColor = attrColor(R.attr.uiChip)
        val onText = attrColor(R.attr.uiAccentOn)
        val offText = attrColor(R.attr.uiTextDim)
        tabs.forEachIndexed { i, tab ->
            val on = i == mode
            tab.setBackgroundColor(if (on) onColor else offColor)
            tab.setTextColor(if (on) onText else offText)
        }
    }

    private fun showTip(mode: Int) {
        binding.tipTabs.visibility = View.VISIBLE
        showTipTabs(mode)
        binding.tvTipTitle.text = when (mode) {
            0 -> "景别"
            1 -> "运镜"
            2 -> "构图"
            3 -> "技巧"
            else -> "关于"
        }
        binding.tvTipBody.text = when (mode) {
            0 -> Html.fromHtml(ParamCoach.shotSizeText(), Html.FROM_HTML_MODE_LEGACY)
            1 -> Html.fromHtml(ParamCoach.moveText(), Html.FROM_HTML_MODE_LEGACY)
            2 -> Html.fromHtml(ParamCoach.compText(), Html.FROM_HTML_MODE_LEGACY)
            3 -> Html.fromHtml(ParamCoach.skillText(), Html.FROM_HTML_MODE_LEGACY)
            else -> aboutText()
        }
        binding.tipLayer.visibility = View.VISIBLE
    }

    private fun showAbout() {
        binding.tipTabs.visibility = View.GONE
        binding.tvTipTitle.text = "关于"
        binding.tvTipBody.text = aboutText()
        binding.tipLayer.visibility = View.VISIBLE
    }

    private fun aboutText(): String {
        return "此软件用于摄影课堂教学VIP专用，禁止外传！此软件的设计、开发、制作由江海洋完成，大江滚滚向东流，就是这么牛！"
    }

    private fun setupFocusTap() {
        binding.overlay.setOnTouchListener { _, event ->
            // 抬起时才对焦：这样左滑展开右侧功能键的手势不会被误判成点按对焦
            if (event.action == MotionEvent.ACTION_UP && !swipeConsumed) {
                engine.focusAt(event.x, event.y, binding.textureView.width, binding.textureView.height)
                binding.overlay.showFocusRing(event.x, event.y)
                showFocusLockIcon(event.x, event.y)
            }
            true
        }
        binding.btnFocusLock.setOnClickListener { toggleExposureLock() }
    }

    // ---------- 曝光锁定（只对全自动曝光开放，不锁对焦） ----------

    /** 判断当前是否为全自动曝光：三项全为 0 才提供「锁定」入口（手动曝光本身即已固定） */
    private fun isAutoExposure(): Boolean =
        params.iso == 0 && params.shutterNs == 0L && params.kelvin == 0

    /**
     * 把小锁放到刚出现的对焦框旁边。
     * 坐标换算：点击坐标是相对 overlay 的，而小锁挂在 root 上，
     * 需要加上 viewportFrame 在 root 中的偏移（viewportFrame 有 clipChildren，不能把小锁放进去）。
     */
    private fun showFocusLockIcon(x: Float, y: Float) {
        val lock = binding.btnFocusLock
        lock.removeCallbacks(hideLockRunnable)
        // 非全自动曝光：只在已锁定时保留（供解锁），否则不显示
        if (!isAutoExposure()) {
            if (!exposureLocked) lock.visibility = View.GONE
            return
        }
        val vf = binding.viewportFrame
        val cx = vf.left + x
        val cy = vf.top + y
        val size = dp(40f).toFloat()
        // 与 CompositionOverlay 的对焦框半边长保持一致（80px），再加 8dp 间距
        val halfBox = 80f
        val gap = halfBox + dp(8f).toFloat()
        // 默认贴在对焦框右侧，右侧放不下就翻到左侧，避免图标出屏点不到
        var centerX = cx + gap + size / 2f
        if (centerX + size / 2f > vf.right) centerX = cx - gap - size / 2f
        lock.x = (centerX - size / 2f).coerceIn(0f, (binding.root.width - size).coerceAtLeast(0f))
        lock.y = (cy - size / 2f).coerceIn(0f, (binding.root.height - size).toFloat().coerceAtLeast(0f))
        lock.visibility = View.VISIBLE
        updateFocusLockIcon()
        // 未锁定时与对焦框同步淡出，避免长期挡住构图；已锁定则常驻以便随时解锁
        if (!exposureLocked) lock.postDelayed(hideLockRunnable, 2200L)
    }

    private fun updateFocusLockIcon() {
        binding.btnFocusLock.setImageResource(
            if (exposureLocked) R.drawable.ic_lock else R.drawable.ic_lock_open
        )
    }

    private fun toggleExposureLock() {
        exposureLocked = !exposureLocked
        engine.setExposureLock(exposureLocked)
        updateFocusLockIcon()
        if (exposureLocked) {
            binding.btnFocusLock.removeCallbacks(hideLockRunnable)
            binding.btnFocusLock.visibility = View.VISIBLE
        }
        updateParamStrip()
        saveSettings()
        toast(
            if (exposureLocked) "已锁定曝光：快门 / 感光度 / 白平衡不再变化（对焦仍自动）"
            else "已解除曝光锁定"
        )
        if (!exposureLocked) binding.btnFocusLock.postDelayed(hideLockRunnable, 1200L)
    }

    private fun animateCore(core: View) {
        val anim = ScaleAnimation(
            1f, 0.86f, 1f, 0.86f,
            Animation.RELATIVE_TO_SELF, 0.5f,
            Animation.RELATIVE_TO_SELF, 0.5f
        ).apply {
            duration = 110
            repeatCount = 1
            repeatMode = Animation.REVERSE
        }
        core.startAnimation(anim)
    }

    // ---------- 录像计时 ----------

    private fun showRecTimer(show: Boolean) {
        binding.tvRecTimer.visibility = if (show) View.VISIBLE else View.GONE
        val density = resources.displayMetrics.density
        // 录制中：拍摄键中心变为白方块（经典录像指示）、外圈变红；停止后按当前模式恢复
        if (show) {
            setCore(38, R.drawable.bg_core_square_red, density)
            binding.photoRing.setBackgroundResource(R.drawable.bg_ring_red)
            playRecordPop()
        } else {
            setCore(54, if (videoMode) R.drawable.bg_core_red else R.drawable.bg_core, density)
            binding.photoRing.setBackgroundResource(R.drawable.bg_ring)
        }
    }

    private fun setCore(sizeDp: Int, bg: Int, density: Float) {
        val size = (density * sizeDp).toInt()
        val lp = binding.photoCore.layoutParams
        lp.width = size
        lp.height = size
        binding.photoCore.layoutParams = lp
        binding.photoCore.setBackgroundResource(bg)
    }

    /** 按下录制后，按钮中心弹出动画 */
    private fun playRecordPop() {
        val pop = ScaleAnimation(
            0.4f, 1f, 0.4f, 1f,
            Animation.RELATIVE_TO_SELF, 0.5f,
            Animation.RELATIVE_TO_SELF, 0.5f
        ).apply {
            duration = 280
            fillAfter = true
            interpolator = OvershootInterpolator()
        }
        binding.photoCore.startAnimation(pop)
    }

    private val timerRunnable = object : Runnable {
        override fun run() {
            recSeconds++
            binding.tvRecTimer.text = String.format("REC %02d:%02d", recSeconds / 60, recSeconds % 60)
            handler.postDelayed(this, 1000L)
        }
    }

    private fun startTimer() {
        handler.removeCallbacks(timerRunnable)
        handler.postDelayed(timerRunnable, 1000L)
    }

    private fun stopTimer() {
        handler.removeCallbacks(timerRunnable)
    }

    // ---------- 传感器 ----------

    private fun setupSensors() {
        sensorManager = getSystemService(SENSOR_SERVICE) as? SensorManager
        accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return
        val x = event.values[0]
        val y = event.values[1]
        binding.overlay.roll = Math.toDegrees(atan2(x.toDouble(), -y.toDouble())).toFloat()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    // ---------- 工具 ----------

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
    }
}
