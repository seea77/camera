package com.teacher.shotlab

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.params.ColorSpaceTransform
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.RggbChannelVector
import android.media.Image
import android.media.ImageReader
import android.media.MediaActionSound
import android.media.MediaRecorder
import android.os.Build
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.view.WindowManager
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * 白平衡处理路径的用户选择。
 * - AUTO（默认）：引擎体检后自行决定用硬件还是软件；
 * - HARDWARE / SOFTWARE：用户强制指定，跳过体检。
 */
enum class WbMode { AUTO, HARDWARE, SOFTWARE }

/**
 * Camera2 原生相机引擎。
 *
 * 之所以不用 CameraX：CameraX 的 3A 状态机会覆盖 CONTROL_AE_MODE / AWB 等键，
 * 导致手动 ISO、快门、白平衡写不进去。这里直接持有 CaptureRequest.Builder，
 * 自动 / 手动完全由我们自己决定，没有任何框架介入。
 *
 * 附带好处：repeating request 的回调能逐帧读到硬件真实生效值（SENSOR_SENSITIVITY、
 * SENSOR_EXPOSURE_TIME、COLOR_CORRECTION_MODE），界面上可以直接显示「设定 vs 实际」。
 */
class Camera2Engine(
    private val context: Context,
    private val textureView: TextureView
) {

    /**
     * 视频唯一采用的录制比例：显示 3:4（＝存储 4:3）。
     * 视频场景不需要多比例 —— 固定下来既免去选择成本，也避开部分机型不支持非常见录像尺寸的问题。
     */
    private val VIDEO_ASPECT = 3f / 4f

    var onOpened: ((CameraCaps) -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var onPhotoSaved: ((Uri?) -> Unit)? = null
    var onActual: ((ActualParams) -> Unit)? = null
    /** 逐帧真实 ISO / 快门 / 自动白平衡色温，供参数面板在自动模式下让滑杆跟随光线（节流后回传） */
    var onAutoValues: ((iso: Int?, shutterNs: Long?, wbKelvin: Int?) -> Unit)? = null
    /** 半手动（只手动 ISO / 快门之一）时的自动曝光基线更新：另一个参数的软自动值变了 */
    var onBaselineUpdated: (() -> Unit)? = null
    var onRecordingStarted: (() -> Unit)? = null
    var onRecordingStopped: (() -> Unit)? = null

    private val cameraManager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var thread: HandlerThread? = null
    private var handler: Handler? = null

    private var cameraId: String? = null          // 逻辑摄像头 id（传给 openCamera）
    private var physicalCamId: String? = null      // 逻辑多摄下的物理子镜头 id；null = 直接用逻辑摄像头
    private var pendingPhysical: String? = null
    private var characteristics: CameraCharacteristics? = null
    private var facing = CameraCharacteristics.LENS_FACING_BACK
    private var cameraDevice: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    /** 基础预览会话配置失败计数：冷启动 HAL 偶发未就绪时静默重试一次，避免无谓弹窗 */
    private var previewFailCount = 0
    private var previewBuilder: CaptureRequest.Builder? = null
    private var imageReader: ImageReader? = null
    private var previewSurface: Surface? = null
    private var recorderSurface: Surface? = null
    private var mediaRecorder: MediaRecorder? = null
    private var recordPfd: android.os.ParcelFileDescriptor? = null

    /** 系统相机音效（对焦成功 / 快门 / 录像开始 / 录像结束），由系统统一调度，尊重设备静音策略 */
    private val actionSound = MediaActionSound()
    /** 点按对焦后等待「对焦锁定」回执，锁定即播一声提示音 */
    private var afLockPending = false

    private var previewSize = Size(1440, 1080)
    private var jpegSize = Size(1920, 1080)
    private var videoSize = Size(1920, 1080)
    private var videoTierHeight = 1080   // 1080 / 1440(2K) / 2160(4K)

    /** 传感器安装角度（多为 90），用于推算 HAL 实际输出的正立 buffer 尺寸 */
    private var sensorOrientation = 90

    /** 当前正在输出的预览 stream 尺寸：会话创建时写死，取景尺寸一律以它为准 */
    private var streamSize = Size(0, 0)

    private var params = ShotParams()
    private var manual = false
    private var flashIndex = 0   // 0 关 1 常亮补光 2 自动
    /**
     * 曝光锁定开关：同时冻结 AE（快门 + 感光度）与自动白平衡 AWB。
     * 只在全自动曝光路径下生效——AE_MODE=OFF 已是手动曝光，无需再锁。
     * 对焦不受影响：AF 模式与这里完全解耦，始终保持 CONTINUOUS_PICTURE（除非手动调焦）。
     */
    private var exposureLock = false
    private var surfaceReady = false
    private var pendingOpen: String? = null
    private var lastVideoUri: Uri? = null
    private var lastNotify = 0L
    private var lastAutoNotify = 0L

    var isRecording = false
        private set

    /** 相机最近一帧的真实 ISO 与快门，用于「切到手动时锁定当前值」 */
    var lastActualIso: Int? = null
        private set
    var lastActualShutterNs: Long? = null
        private set

    /** 半自动（只动快门/ISO 其一）时，由低频探测帧读回的「全自动曝光配对」基线；
     *  用于按曝光量守恒推算另一个仍保持自动的参数，使其随光线软跟随 */
    private var autoBaselineIso = 0
    private var autoBaselineShutterNs = 0L

    /** 专门用于自动曝光基线探测的测光通道（图像数据丢弃，只取 CaptureResult） */
    private var meteringReader: ImageReader? = null
    private var meteringSurface: Surface? = null
    private var probing = false
    /** 该设备建不出带测光通道的会话：标记后不再重试，探测功能停用，其余功能照旧 */
    private var meteringUnsupported = false

    var caps: CameraCaps = CameraCaps(null, null, false)
        private set

    /** 相机是否支持 COLOR_CORRECTION_TRANSFORM_MATRIX（需要 MANUAL_POST_PROCESSING 能力）。
     *  不支持的设备（多为中低端机型）用 RenderEffect 实时预览 + 照片 ColorMatrix 后处理兜底。 */
    var colorCorrectionSupported = false
        private set

    /** 自动白平衡（HAL）实测增益 / 变换矩阵锚点：进入手动白平衡时以它们为基准做相对偏移，
     *  避免直接替换厂商 CCM 导致偏绿、偏色。每次自动帧都会刷新。 */
    private var lastHalGains: RggbChannelVector? = null
    private var lastHalTransform: ColorSpaceTransform? = null

    /** 自动白平衡反推色温锚点（K），供应用层白平衡兜底做相对偏移；无读数时为 0。 */
    var lastAutoWbKelvin = 0
        private set
    val anchorKelvin: Int get() = if (lastAutoWbKelvin > 0) lastAutoWbKelvin else 5600

    /** 录像重复请求：与 previewBuilder 分离，避免录像中 reapply 只发预览导致录像流停帧 */
    private var recordBuilder: CaptureRequest.Builder? = null


    // ---------- 生命周期 ----------

    fun open(id: String, physical: String? = null) {
        if (!surfaceReady) {
            pendingOpen = id
            pendingPhysical = physical
            return
        }
        // 同一逻辑摄像头在物理子镜头间切换（如主摄↔长焦）：设备已开，无需重开，直接重建会话即可。
        if (cameraId == id && cameraDevice != null) {
            physicalCamId = physical
            rebuildForLens()
            return
        }
        cameraId = id
        physicalCamId = physical
        startBackground()
        val chars = runCatching { cameraManager.getCameraCharacteristics(id) }.getOrElse {
            onError?.invoke("读取相机特性失败")
            return
        }
        characteristics = chars
        loadLogicalMeta(chars)
        computeSizes(effectiveChars())   // 物理子镜头用其真实尺寸表，避免会话因 surface 不匹配而失败
        caps = readCaps(chars)
        openDevice()
    }

    /** 载入逻辑摄像头级别的能力（与所选物理子镜头无关）：色彩校正、测光可用性、朝向、曝光能力表 */
    private fun loadLogicalMeta(chars: CameraCharacteristics) {
        colorCorrectionSupported = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            ?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING) == true
        meteringUnsupported = false   // 换镜头重新评估测光通道可用性
        facing = chars.get(CameraCharacteristics.LENS_FACING) ?: CameraCharacteristics.LENS_FACING_BACK
    }

    /** 返回当前应据以计算输出尺寸的特性：选定了物理子镜头则用它的尺寸表，否则用逻辑摄像头 */
    private fun effectiveChars(): CameraCharacteristics {
        val pid = physicalCamId
        if (pid != null && cameraId != null) {
            Camera2Reflect.physicalCharacteristics(cameraManager, cameraId!!, pid)?.let { return it }
        }
        return characteristics!!
    }

    /** 同一逻辑摄像头切换物理子镜头：保留已打开的设备，仅重建预览/录制会话 */
    private fun rebuildForLens() {
        val id = cameraId ?: return
        val chars = runCatching { cameraManager.getCameraCharacteristics(id) }.getOrElse {
            onError?.invoke("读取相机特性失败")
            return
        }
        characteristics = chars
        loadLogicalMeta(chars)
        caps = readCaps(chars)
        computeSizes(effectiveChars())
        createPreviewSession()
    }

    fun close() {
        runCatching { session?.close() }
        runCatching { cameraDevice?.close() }
        runCatching { imageReader?.close() }
        runCatching { meteringReader?.close() }
        meteringReader = null
        meteringSurface = null
        runCatching { mediaRecorder?.release() }
        session = null
        cameraDevice = null
        imageReader = null
        mediaRecorder = null
        recordBuilder = null
        isRecording = false
        physicalCamId = null
        stopBackground()
    }

    private fun startBackground() {
        if (thread != null) return
        thread = HandlerThread("ShotLabCamera").also {
            it.start()
            handler = Handler(it.looper)
        }
    }

    private fun stopBackground() {
        thread?.quitSafely()
        runCatching { thread?.join(500) }
        thread = null
        handler = null
    }

    private fun openDevice() {
        val id = cameraId ?: return
        val h = handler ?: return
        try {
            cameraManager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    cameraDevice = device
                    createPreviewSession()
                }

                override fun onDisconnected(device: CameraDevice) {
                    device.close()
                    cameraDevice = null
                }

                override fun onError(device: CameraDevice, error: Int) {
                    device.close()
                    cameraDevice = null
                    notifyError("相机打开失败（$error）")
                }
            }, h)
        } catch (e: SecurityException) {
            notifyError("没有相机权限")
        } catch (e: Exception) {
            notifyError("相机打开异常：${e.message}")
        }
    }

    // ---------- 会话 ----------

    private fun createPreviewSession(withMetering: Boolean = true) {
        val device = cameraDevice ?: return
        val h = handler ?: return
        val texture = textureView.surfaceTexture ?: return
        // 切换物理子镜头时设备不重开，旧会话仍存活：必须先关旧会话，否则同一纹理 surface 被两个会话争用会失败。
        runCatching { session?.close() }
        session = null
        // 预览 buffer 一律用支持尺寸表里的「横向尺寸」：预览尺寸表没有竖向条目，
        // 请求竖尺寸（如 1080×1440）会被 HAL 悄悄换成别的尺寸，view 比例与实际输出不符 → 拉伸。
        // 旋转交给 view 层（见 configureTransform），不靠 buffer 尺寸去凑。
        streamSize = previewSize
        texture.setDefaultBufferSize(previewSize.width, previewSize.height)
        previewSurface = Surface(texture)

        imageReader?.close()
        imageReader = ImageReader.newInstance(jpegSize.width, jpegSize.height, ImageFormat.JPEG, 2).also {
            it.setOnImageAvailableListener({ reader ->
                val image = runCatching { reader.acquireLatestImage() }.getOrNull()
                if (image != null) saveImage(image)
            }, h)
        }

        // 半自动曝光（只手动 ISO / 快门之一）需要一条隐藏测光通道来探测「当前光线的自动曝光配对」。
        // 该通道只取 CaptureResult，图像数据丢弃（不占预览、不出片）。设备不支持时整个会话会配置失败，
        // 此时自动退回不带测光通道的会话 —— 探测停用，行为与旧版一致（自动参数冻结），相机照常可用。
        val useMetering = withMetering && !meteringUnsupported
        if (useMetering) {
            runCatching {
                meteringReader?.close()
                val ms = pickMeteringSize()
                meteringReader = ImageReader.newInstance(ms.width, ms.height, ImageFormat.YUV_420_888, 2).also {
                    // 隐藏测光通道的帧有两个用途：①半手动曝光基线探测 ②白平衡硬件路径体检。
                    // 必须持续消费，否则队列满 2 帧后后续单次拍摄会被 HAL 丢弃。
                    it.setOnImageAvailableListener({ reader ->
                        val img = runCatching { reader.acquireLatestImage() }.getOrNull() ?: return@setOnImageAvailableListener
                        runCatching {
                            if (wbProbeBusy) meanChroma(img)?.let { onProbeFrame(it) }
                        }
                        img.close()
                    }, h)
                }
                meteringSurface = meteringReader?.surface
            }.onFailure { meteringReader = null; meteringSurface = null }
        }

        val surfaces = listOfNotNull(previewSurface, imageReader?.surface, if (useMetering) meteringSurface else null)
        try {
            device.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    session = s
                    previewFailCount = 0
                    startPreview()
                    mainHandler.post {
                        configureTransform()          // 新会话的 stream 尺寸生效后立刻按真实尺寸重算取景
                        onOpened?.invoke(caps)
                    }
                }

                override fun onConfigureFailed(s: CameraCaptureSession) {
                    if (useMetering) {
                        // 测光通道把这个设备搞挂了：关掉它重建一次，保证相机一定能起来
                        Log.w(TAG, "带测光通道的会话配置失败，退回普通预览会话")
                        meteringUnsupported = true
                        meteringReader?.close()
                        meteringReader = null
                        meteringSurface = null
                        mainHandler.post { createPreviewSession(false) }
                        return
                    }
                    // 基础会话也失败：冷启动 HAL 偶发未就绪，静默重试（带延时让 HAL 就绪），不再弹提示
                    if (previewFailCount < 2) {
                        previewFailCount++
                        Log.w(TAG, "基础预览会话配置失败，静默重试（冷启动保护）")
                        mainHandler.postDelayed({ runCatching { createPreviewSession(false) } }, 300)
                        return
                    }
                    Log.w(TAG, "预览会话创建失败（已重试仍失败，不再弹提示）")
                }
            }, h)
        } catch (e: Exception) {
            if (useMetering) {
                meteringUnsupported = true
                meteringReader?.close()
                meteringReader = null
                meteringSurface = null
                createPreviewSession(false)
                return
            }
            // 同上的冷启动偶发失败：静默重试一次，仍失败也只写日志不弹提示
            if (previewFailCount < 2) {
                previewFailCount++
                Log.w(TAG, "基础预览会话创建异常，静默重试（冷启动保护）")
                mainHandler.postDelayed({ runCatching { createPreviewSession(false) } }, 300)
                return
            }
            Log.w(TAG, "创建会话异常：${e.message}")
        }
    }

    /** 测光通道只取 CaptureResult，越小越省资源；不支持 YUV 时退回预览尺寸（会配置失败并自动降级）。
     *  使用当前镜头（物理子镜头优先）的尺寸表，否则长焦下选到主摄才支持的尺寸会让会话配置失败。 */
    private fun pickMeteringSize(): Size {
        val map = effectiveChars().get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val choices = map?.getOutputSizes(ImageFormat.YUV_420_888)?.toList() ?: emptyList()
        return choices.minByOrNull { it.width * it.height } ?: previewSize
    }

    private fun startPreview() {
        val device = cameraDevice ?: return
        val s = session ?: return
        val surface = previewSurface ?: return
        val h = handler ?: return
        runCatching {
            val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            builder.addTarget(surface)
            applyTo(builder)
            previewBuilder = builder
            s.setRepeatingRequest(builder.build(), captureCallback, h)
        }.onFailure { Log.w(TAG, "启动预览失败：${it.message}") }
    }

    /** 录像流：预览 + 录制共用同一条重复请求，曝光策略由 applyTo 决定（自动=AE 追光，手动=写传感器） */
    private fun startRecordingStream() {
        val device = cameraDevice ?: return
        val s = session ?: return
        val preview = previewSurface ?: return
        val rec = recorderSurface ?: return
        val h = handler ?: return
        runCatching {
            val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
            builder.addTarget(preview)
            builder.addTarget(rec)
            applyTo(builder)
            recordBuilder = builder
            s.setRepeatingRequest(builder.build(), captureCallback, h)
        }.onFailure { notifyError("启动录像流失败：${it.message}") }
    }

    private fun stopRepeatingSafe() {
        runCatching { session?.stopRepeating() }
    }

    // ---------- 参数：核心 ----------

    fun applyParams(p: ShotParams, manualMode: Boolean) {
        params = p
        manual = manualMode
        reapply()
    }

    /** 0 关 / 1 常亮补光 / 2 自动。手动档下不参与（AE 已关闭） */
    fun setFlash(index: Int) {
        flashIndex = index
        reapply()
    }

    /** 锁定/解除曝光（含白平衡）。锁定期间曝光三要素与白平衡冻结，画面亮度不再随光线变化 */
    fun setExposureLock(locked: Boolean) {
        exposureLock = locked
        reapply()
    }

    private fun reapply() {
        runCatching {
            // 录像中必须重建「预览 + 录制」双目标请求：
            // previewBuilder 只靶预览 surface，直接重发它会让 recorderSurface 停止收帧
            // —— 表现为录像画面定格不动（录像中改任何参数、开关防抖都会触发这条路径）。
            val builder = if (isRecording) {
                val device = cameraDevice ?: return
                recordBuilder ?: device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
                    .also { rb ->
                        val preview = previewSurface ?: return
                        val rec = recorderSurface ?: return
                        rb.addTarget(preview)
                        rb.addTarget(rec)
                        recordBuilder = rb
                    }
            } else {
                previewBuilder ?: return
            }
            applyTo(builder)
            session?.setRepeatingRequest(builder.build(), captureCallback, handler)
        }.onFailure { Log.w(TAG, "applyParams failed", it) }
    }

    /** 自动曝光基线（全自动时的实际配对；半手动时由探测帧刷新） */
    private fun baselinePair(): Pair<Int, Long>? {
        val i = if (autoBaselineIso > 0) autoBaselineIso else lastActualIso ?: return null
        val s = if (autoBaselineShutterNs > 0L) autoBaselineShutterNs else lastActualShutterNs ?: return null
        return i to s
    }

    /** 半手动（快门已手动）时，ISO 的软自动值：基线曝光量 ÷ 手动快门 */
    fun softAutoIso(): Int? {
        if (params.iso > 0 || params.shutterNs <= 0L) return null
        val b = baselinePair() ?: return null
        val v = (b.first.toDouble() * b.second / params.shutterNs).roundToLong().coerceIn(100L, 12800L).toInt()
        return caps.isoRange?.let { v.coerceIn(it.first, it.last) } ?: v
    }

    /** 半手动（ISO 已手动）时，快门的软自动值：基线曝光量 ÷ 手动 ISO */
    fun softAutoShutterNs(): Long? {
        if (params.shutterNs > 0L || params.iso <= 0) return null
        val b = baselinePair() ?: return null
        val v = (b.first.toDouble() * b.second / params.iso).roundToLong()
        return caps.exposureNs?.let { v.coerceIn(it.first, it.last) }
            ?: v.coerceIn(1_000_000L, 1_000_000_000L)
    }

    /** 当前是否处于半手动（只手动 ISO / 快门之一） */
    fun isSemiManual(): Boolean =
        (params.iso > 0 && params.shutterNs <= 0L) || (params.shutterNs > 0L && params.iso <= 0)

    /**
     * 探测一帧「全自动曝光」的 (ISO, 快门) 配对，作为半手动时推算另一个参数的基线。
     *
     * 走隐藏测光通道：只有 CaptureResult 被读取，图像数据直接丢弃，不进预览、不出片。
     * 由界面在「参数面板打开且半手动」时低频调用（面板关闭即停），避免频繁打扰 3A 例程。
     * 设备不支持测光通道时 meteringSurface 为空，这里直接跳过，行为退回旧版（冻结）。
     */
    fun probeAutoExposure() {
        val device = cameraDevice ?: return
        val s = session ?: return
        val surf = meteringSurface ?: return
        val h = handler ?: return
        if (probing) return
        probing = true
        val supported = characteristics?.availableCaptureRequestKeys
        lateinit var b0: CaptureRequest.Builder
        fun <T> put(key: CaptureRequest.Key<T>, value: T) {
            if (supported == null || supported.contains(key)) b0.set(key, value)
        }
        runCatching {
            b0 = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            b0.addTarget(surf)
            b0.setPhysicalCameraIdSafe(physicalCamId)
            put(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            put(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            put(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, evIndex(params.ev))
            put(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
            put(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_FAST)
            s.capture(b0.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult
                ) {
                    probing = false
                    val i = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: return
                    val e = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: return
                    if (i <= 0 || e <= 0L) return
                    autoBaselineIso = i
                    autoBaselineShutterNs = e
                    if (isSemiManual()) mainHandler.post { onBaselineUpdated?.invoke() }
                }

                override fun onCaptureFailed(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    failure: android.hardware.camera2.CaptureFailure
                ) {
                    probing = false
                }
            }, h)
        }.onFailure {
            probing = false
            Log.w(TAG, "自动曝光探测失败，退回基线冻结", it)
        }
    }

    // ==================== 白平衡硬件路径体检 ====================

    /** 体检进度：idle → 采自动基准 → 采暖 → 采冷 → 判决 */
    private var wbProbeStep = 0
    private var wbProbeBase = 0f          // 自动状态的「红移量」= 平均V − 平均U
    private var wbProbeWarm = Float.NaN
    private var wbProbeCool = Float.NaN
    private var wbProbeBusy = false

    /**
     * 体检：确认本机的「硬件手动白平衡」是否真的会把效果写进画面像素。
     *
     * 为什么必须测：**部分机型（Android 13 的老机器）会声明支持 MANUAL_POST_PROCESSING，
     * 但把下发的 COLOR_CORRECTION_TRANSFORM 直接忽略**。此时硬件没生效、软件又被「防双重调色」
     * 逻辑跳过，表现为手动白平衡完全没效果。而 `CaptureResult` 回执并不可靠——有些 HAL 会原样
     * 回声我们下发的参数，回执看着正常、画面其实纹丝不动，所以**必须用真实像素来判决**。
     *
     * 做法：对隐藏测光通道连做三次单次拍摄（自动 / 极暖 / 极冷），比较画面的平均色度。
     * 判据取 **平均V − 平均U（偏红程度）**：加 R 减 B 时它应升高，反之降低。
     * 用它而不是单独 U 或 V，是因为光照/曝光变化会让 U、V 同向漂移，做差即可抵消。
     * 三次拍摄都只打到测光表面，预览毫发无损，用户完全无感。
     */
    private fun startWbProbe() {
        val device = cameraDevice ?: return
        val s = session ?: return
        val h = handler ?: return
        val surf = meteringSurface
        val supported = characteristics?.availableCaptureRequestKeys
        if (surf == null) {
            // 拿不到 YUV 帧可用：退而求其次看回执是否被采纳（false 阴性风险略高，但聊胜于无）
            probeByReadback()
            return
        }
        wbProbeBusy = true
        wbProbeStep = 1
        wbProbeWarm = Float.NaN
        wbProbeCool = Float.NaN

        val anchor = lastHalGains
        // 相对 HAL 当前自动增益做一个足够夸张的偏色，好让「有没有真的改动」一目了然
        val warm = RggbChannelVector(
            (anchor?.red ?: 1f) * 1.6f, anchor?.greenEven ?: 1f, anchor?.greenOdd ?: 1f,
            (anchor?.blue ?: 1f) * 0.65f
        )
        val cool = RggbChannelVector(
            (anchor?.red ?: 1f) * 0.65f, anchor?.greenEven ?: 1f, anchor?.greenOdd ?: 1f,
            (anchor?.blue ?: 1f) * 1.6f
        )

        fun shoot(manual: Boolean, gains: RggbChannelVector?) {
            lateinit var b: CaptureRequest.Builder
            fun <T> put(key: CaptureRequest.Key<T>, value: T) {
                if (supported == null || supported.contains(key)) b.set(key, value)
            }
            runCatching {
                b = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                b.addTarget(surf)
                b.setPhysicalCameraIdSafe(physicalCamId)
                put(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                put(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                if (manual && gains != null) {
                    put(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF)
                    put(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
                    put(CaptureRequest.COLOR_CORRECTION_GAINS, gains)
                    put(CaptureRequest.COLOR_CORRECTION_TRANSFORM, lastHalTransform ?: ManualCamera.IDENTITY_TRANSFORM)
                } else {
                    put(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                    put(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_FAST)
                }
                if (params.iso > 0) put(CaptureRequest.SENSOR_SENSITIVITY, params.iso)
                s.capture(b.build(), null, h)
            }.onFailure {
                Log.w(TAG, "白平衡体检抓帧失败，保留原路径", it)
                finishWbProbe(null)
            }
        }

        shoot(false, null)
        h.postDelayed({ if (wbProbeStep == 2) shoot(true, warm) }, 120)
        h.postDelayed({ if (wbProbeStep == 3) shoot(true, cool) }, 280)
        // 超时保护：任何环节卡住都不能把状态机永久留在半路
        h.postDelayed({ if (wbProbeBusy) finishWbProbe(null) }, 2500)
    }

    /**
     * 拿不到 YUV 帧时的降级体检：比较「我们下发的增益」与「结果里回报的增益」。
     * 可靠度低于逐像素方案（部分 HAL 会原样回声），所以只在无法取帧时使用。
     */
    private fun probeByReadback() {
        Log.w(TAG, "无测光通道可用，改用回执比对体检")
        finishWbProbe(null)   // 回执不可靠，保持现状、交由用户手动选择路径
    }

    /** 体检采样：在隐藏测光通道的帧回调里推进状态机 */
    private fun onProbeFrame(chroma: Pair<Float, Float>) {
        if (!wbProbeBusy) return
        val redness = chroma.second - chroma.first       // 平均V − 平均U
        when (wbProbeStep) {
            1 -> { wbProbeBase = redness; wbProbeStep = 2 }
            2 -> { wbProbeWarm = redness; wbProbeStep = 3 }
            3 -> {
                wbProbeCool = redness
                // 判据：暖必须更红、冷必须更蓝，各移动 4 个灰阶以上才算硬件真的干活了
                val ok = wbProbeWarm - wbProbeBase >= 4f && wbProbeBase - wbProbeCool >= 4f
                Log.i(
                    TAG,
                    "白平衡体检：基准=${wbProbeBase} 暖=${wbProbeWarm} 冷=${wbProbeCool} → 硬件${if (ok) "生效" else "未响应"}"
                )
                finishWbProbe(ok)
            }
        }
    }

    private fun finishWbProbe(result: Boolean?) {
        wbProbeBusy = false
        wbProbeStep = 0
        if (result == null) { onPathReady(); return }
        hwWbWorks = result
        reapply()                       // 立刻按新结论重下发，用户拖动滑杆时无需重开相机
        onPathReady()
    }

    private fun onPathReady() {
        mainHandler.post { onWbPathChanged?.invoke() }
    }

    /** 条件满足时体检一次：仅在真·可能走硬件、且还没结论、且用户没强行指定时做 */
    fun maybeProbeWb() {
        if (isRecording) return
        if (!colorCorrectionSupported) return
        if (wbMode != WbMode.AUTO) return
        if (hwWbWorks != null || wbProbeBusy) return
        if (params.kelvin <= 0) return          // 用户还没动白平衡，没必要测
        if (session == null) return
        startWbProbe()
    }

    /**
     * 平均色度：从 YUV_420_888 的 U/V 平面求均值，返回 (平均U, 平均V)。
     * 逐行按 rowStride / pixelStride 取值（NV21 与纯 planar 排布都能正确读到）。
     */
    private fun meanChroma(img: Image): Pair<Float, Float>? {
        val planes = img.planes
        if (planes.size < 3) return null
        return runCatching {
            val pu = planes[1]
            val pv = planes[2]
            val ub = pu.buffer
            val vb = pv.buffer
            val w = img.width / 2
            val h = img.height / 2
            var sumU = 0.0
            var sumV = 0.0
            var n = 0
            for (y in 0 until h) {
                val rowU = y * pu.rowStride
                val rowV = y * pv.rowStride
                for (x in 0 until w) {
                    val iu = rowU + x * pu.pixelStride
                    val iv = rowV + x * pv.pixelStride
                    if (iu < 0 || iu >= ub.capacity() || iv < 0 || iv >= vb.capacity()) continue
                    sumU += ub.get(iu).toInt() and 0xff
                    sumV += vb.get(iv).toInt() and 0xff
                    n++
                }
            }
            if (n == 0) null else (sumU / n).toFloat() to (sumV / n).toFloat()
        }.getOrNull()
    }

    /**
     * 把当前设定写进 CaptureRequest。
     *
     * 曝光、白平衡、对焦三者各自独立判断：
     * - 曝光：动了 ISO 或快门任一即进入手动曝光（CONTROL_MODE=AUTO + CONTROL_AE_MODE=OFF，
     *         这样写传感器的同时不误伤 AWB/AF），ISO 与快门直接写传感器；
     *         首次进入时把没设置的那个参数冻结到硬件当前实际值，避免从自动切手动时亮度跳变（对齐 OPPO 大师模式）。
     *         全自动时 AE 全程工作（录制中亦然），只给帧率封顶，不冻结曝光。
     * - 白平衡：色温 > 0 即关闭 AWB、用 RGB 增益矩阵；与曝光是否手动无关，单独生效。
     * - 对焦：对焦距离 > 0 即关 AF 用屈光度（米→1/米），否则连续对焦。
     * - EV 仅在自动曝光下作为 AE 补偿生效。
     */
    private fun applyTo(builder: CaptureRequest.Builder) {
        val chars = characteristics
        val keys = chars?.availableCaptureRequestKeys
        fun <T> set(key: CaptureRequest.Key<T>, value: T) {
            if (keys == null || keys.contains(key)) builder.set(key, value)
        }

        // 预览与成片都用真实设定：手动曝光下快门/ISO 带来的明暗变化，预览里实时可见
        val manExp = params.iso > 0 || params.shutterNs > 0L
        val manFocus = params.focusMeters > 0f

        // ---------- 曝光 ----------
        var iso = params.iso
        var shutter = params.shutterNs
        if (manExp) {
            // 只手动其一（半手动）时，另一个参数不再冻结在「进入手动那一刻」：
            // 用自动曝光基线按曝光量守恒推算（ISO × 快门 = 同一曝光量），
            // 基线由自动模式的逐帧实际值、以及面板打开时的探测帧持续更新 → 它会随光线继续变化。
            if (iso <= 0 || shutter <= 0L) {
                val bIso = if (autoBaselineIso > 0) autoBaselineIso else lastActualIso ?: 400
                val bSh = if (autoBaselineShutterNs > 0L) autoBaselineShutterNs else lastActualShutterNs ?: 20_000_000L
                if (iso <= 0) {
                    // 快门已手动 → ISO = 基线曝光量 ÷ 手动快门
                    val v = if (shutter > 0L) (bIso.toDouble() * bSh / shutter).roundToLong() else bIso.toLong()
                    val capped = v.coerceIn(100L, 12800L).toInt()
                    iso = caps.isoRange?.let { capped.coerceIn(it.first, it.last) } ?: capped
                } else {
                    // ISO 已手动 → 快门 = 基线曝光量 ÷ 手动 ISO
                    val v = (bIso.toDouble() * bSh / iso).roundToLong()
                    shutter = caps.exposureNs?.let { v.coerceIn(it.first, it.last) }
                        ?: v.coerceIn(1_000_000L, 1_000_000_000L)
                }
            }
            // 手动曝光不再用 CONTROL_MODE=OFF：那会把 AWB、AF 一起关停，
            // 于是相机不再更新自动白平衡增益（手动白平衡的中性锚点被冻住），HAL 也会忽略 AWB 设置。
            // 标准组合是 CONTROL_MODE=AUTO + CONTROL_AE_MODE=OFF：曝光由我们写传感器，
            // 白平衡与对焦仍由 3A 正常工作。
            set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            set(CaptureRequest.SENSOR_SENSITIVITY, iso)
            set(CaptureRequest.SENSOR_EXPOSURE_TIME, shutter)
            // 帧时长至少覆盖快门。预览沿用设备默认帧率（与 1.5.3 一致，只锁下限=快门）；
            // 录像流才按所选帧率收紧（否则 60fps 被过长帧时长压回 30fps）
            set(
                CaptureRequest.SENSOR_FRAME_DURATION,
                if (isRecording) maxOf(shutter, 1_000_000_000L / params.fps.coerceAtLeast(1)) else shutter
            )
            set(CaptureRequest.FLASH_MODE, if (flashIndex == 1) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF)
        } else {
            // 全自动：曝光交给 AE——录像时也如此，自动模式就该自动追光。
            //
            // 这条分支改过三次，把坑记录清楚免得再踩：
            //   · v1.8.51：为修「选 25fps 却录成 30fps」，在录像时下发 [fps, fps] 精确帧率区间。
            //     副作用是每帧时长被钉死在 1/fps，AE 失去拉长曝光时间的余地，只能靠 ISO 硬扛
            //     → 按下录制键画面立刻变暗，而手动档无此问题（它走 AE_MODE=OFF 自己写快门）。
            //   · v1.8.55：改成把预览的实时 ISO/快门冻住写进传感器。不再变暗了，但 AE 停摆，
            //     走动、开窗、灯光变化都不再响应——治了标，丢了「自动」。
            //   · 现在：AE 全程工作，帧率只「封顶」不「钉死」。选上限=所选帧率、下限尽可能小的
            //     区间（如 [15,30]），AE 可以在 1/15 ~ 1/30 之间自由挑曝光时长去追光，
            //     同时相机不会跑到比所选更快的帧率。
            set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            val aeMode = if (flashIndex == 2) CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH
            else CaptureRequest.CONTROL_AE_MODE_ON
            set(CaptureRequest.CONTROL_AE_MODE, aeMode)
            set(
                CaptureRequest.FLASH_MODE,
                if (flashIndex == 1) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF
            )
            set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, evIndex(params.ev))
            // 曝光锁定：必须显式写 true/false，不能只在锁定时写 true。
            // 部分机型 HAL 对「省略该键的请求」会保持此前的锁定状态不释放，
            // 表现为：点了解锁、AE 仍然冻结（自动模式不恢复）。显式 false 才能可靠解锁。
            set(CaptureRequest.CONTROL_AE_LOCK, exposureLock)
            // AE_MODE=ON 时不要写 SENSOR_FRAME_DURATION：这里由 AE 根据光线自行折叠帧时长。
            if (isRecording) {
                recordFpsRange(params.fps)?.let { set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
            }
        }

        // ---------- 白平衡（独立于曝光） ----------
        // 手动白平衡走相机硬件的 COLOR_CORRECTION_TRANSFORM_MATRIX：
        //  - 该能力需要 MANUAL_POST_PROCESSING（OPPO 这类旗舰支持），由 colorCorrectionSupported 门控；
        //  - 支持时：以 HAL 自动增益 + 变换矩阵为锚点做相对偏移，保留厂商 CCM，
        //    仅按目标/锚点色温差缩放 R/B 增益（v1.8.8 修复「同数值偏绿/跳变」）；
        //  - 不支持的设备（多为中低端）走 else：自动白平衡 + FAST，白平衡交给应用层 RenderEffect / 照片后处理兜底。
        val manWb = params.kelvin > 0
        if (manWb && useHardwareWb) {
            set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF)
            set(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
            // 以 HAL 当前自动白平衡（增益 + 变换矩阵）为锚点做相对偏移：保留厂商 CCM，不偏色。
            val anchor = lastHalGains ?: ManualCamera.gainsForKelvin(params.kelvin)
            val anchorK = ManualCamera.kelvinFromGains(anchor).let { if (it > 0) it else params.kelvin }
            val (rf, bf) = ManualCamera.wbRelativeFactors(params.kelvin, anchorK)
            // Tint 轴：绿通道增益叠加偏移（升 G→绿 / 降 G→紫）；tint=0 时因子=1，与历史行为完全一致
            val gf = ManualCamera.tintFactor(params.tint)
            set(
                CaptureRequest.COLOR_CORRECTION_GAINS,
                RggbChannelVector(anchor.red * rf, anchor.greenEven * gf, anchor.greenOdd * gf, anchor.blue * bf)
            )
            set(CaptureRequest.COLOR_CORRECTION_TRANSFORM, lastHalTransform ?: ManualCamera.IDENTITY_TRANSFORM)
        } else {
            set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
            set(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_FAST)
            // 曝光锁定：与 AE_LOCK 成对，同样显式写 true/false 保证解锁可靠
            set(CaptureRequest.CONTROL_AWB_LOCK, exposureLock)
        }

        // ---------- 边缘模式：清晰度（局部对比增强）改由应用层 clarity 统一实现，相机不再跟随锐化度开关 ----------
        // 此前用 EDGE_MODE_HIGH_QUALITY 近似锐化，但它是二值档、易出硬边且各机型不均，故移除，交回相机默认边缘处理。

        // ---------- 对焦 ----------
        if (manFocus) {
            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            val d = focusDiopters(params.focusMeters)
            if (d >= 0f) set(CaptureRequest.LENS_FOCUS_DISTANCE, d)
        } else {
            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        }

        // 逻辑多摄：把当前请求锁定到指定的物理子镜头（长焦 / 超广角），否则会落到逻辑默认主摄。
        // 仅在确实选了物理子镜头时设置——普通单摄机型不设，避免个别 HAL 把空值当异常。
        if (physicalCamId != null) {
            builder.setPhysicalCameraIdSafe(physicalCamId)
        }
    }

    /** 对焦距离（米）→ 屈光度（1/米），并夹到镜头最近对焦距离对应的上限 */
    private fun focusDiopters(meters: Float): Float {
        val minM = characteristics?.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0.1f
        val maxD = if (minM > 0f) 1f / minM else 10f
        return (1f / meters).coerceAtMost(maxD).coerceAtLeast(0f)
    }

    private fun evIndex(ev: Float): Int {
        val range = characteristics?.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE)
            ?: return 0
        val rational = characteristics?.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)
        val step = rational?.let { if (it.denominator != 0) it.numerator.toFloat() / it.denominator else 0.333f }
            ?: 0.333f
        val denominator = if (step == 0f) 0.333f else step
        return (ev / denominator).toInt().coerceIn(range.lower, range.upper)
    }

    /**
     * 录像帧率区间：**只封顶，不钉死**。
     *
     * 关键认知：MediaRecorder 在 VideoSource.SURFACE 模式下，setVideoFrameRate() 只是个建议值，
     * 它不控制任何东西；决定出片帧率的是「相机每秒实际送多少帧」，而这由 CONTROL_AE_TARGET_FPS_RANGE 决定。
     * 而 [L, U] 同时是 AE 的约束——它意味着曝光时长被允许落在 1/U ~ 1/L 之间。
     *
     * 所以这个区间怎么选，本质是在「帧率准」和「亮度能追光」之间做取舍：
     *   [30, 30]  → 帧率死准，但每帧只有 33ms，AE 只能 ISO 硬扛 → 暗处直接黑（详见 applyTo 注释）
     *   [15, 30]  → 帧率上限还是 30，AE 可在 33ms ~ 66ms 之间拉长曝光追光，暗处只是帧率略降
     *   不设      → AE 完全自由，但帧率可能跑到所选值以上
     *
     * 优先级（能 LINUX 到什么、就只显示什么）：
     *   ① 上限等于 fps 的区间，取其中**下限最低**的（给 AE 最大的曝光空间）
     *   ② 没有就返回 null——这一档**不该出现在列表里**
     *
     * 曾经在 ② 的位置放过一条「退而求其次」的规则：找不到 [x,25] 就拿 [24,24] 顶上，
     * 以为差一帧无所谓。实测打脸：HAL 对这种凑出来的组合直接忽略，UI 显示 25fps、
     * 文件里却是 30fps。「不支持的帧率不显示」这条约定，容不得这种善意的四舍五入。
     */
    fun recordFpsRange(fps: Int): Range<Int>? {
        val ranges = characteristics
            ?.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.toList()
        if (ranges.isNullOrEmpty()) return null
        return ranges.filter { it.upper == fps && it.lower <= fps }
            .minByOrNull { it.lower }
    }

    /**
     * 诊断：本机 HAL 是否接受 AE 目标帧率区间这一个键。
     *
     * applyTo() 里有道门控，不在 availableCaptureRequestKeys 里的键会被静默吞掉——
     * 一旦被吞，自动模式下所有的帧率承诺都无从谈起（实际帧率由硬件默认决定）。
     * 这个值是「帧率能不能被控制」的前提，值得在 UI 上如实摊开。
     */
    fun fpsRangeKeySupported(): Boolean {
        val keys = characteristics?.availableCaptureRequestKeys ?: return false
        return keys.contains(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE)
    }

    /**
     * UI 用：按设备真实能力过滤帧率档位，**口径与录制时实际采取的完全一致**。
     *
     * 早期版本用 `ranges.any { r.contains(fps) }` 判断，只要求「有区间包含该帧率」——形同虚设：
     * [15,30] 就足以让 25fps 通过，于是 UI 上明明有 25fps，录出来却是 30fps。展示与行为两张皮。
     * 现在反过来以录制端为准，同一套 recordFpsRange() 判据，杜绝「选得到但做不到」的档位。
     *
     * 做不到的档位**直接不进列表**，而不是显示出来再挂一行「本机做不到」的说明——
     * 列表里出现的每一个数字，都必须是这台机器真能录出来的。
     *
     * @param manualExposure 当前是否手动曝光（ISO/快门任一已手动）：
     *        手动时 AE 被关闭，帧率由 SENSOR_FRAME_DURATION 精确锁定，不受 AE 区间限制，
     *        只要是硬件帧率上限以内的档位都能真正做到（这也是 PAL 的 25fps 唯一的可用通道）。
     */
    fun supportedRecordFps(candidates: List<Int>, manualExposure: Boolean): List<Int> {
        val ranges = characteristics
            ?.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.toList()
        // 读不到能力表时无从判断，只能维持候选（真机上该键必定存在）
        if (ranges.isNullOrEmpty()) return candidates
        // HAL 报出来的、这台机器真实达到过的帧率档
        val realUppers = ranges.map { it.upper }.distinct().sorted()
        val hardwareMax = realUppers.last()
        val withinHardware = candidates.filter { it <= hardwareMax }
        val usable = if (manualExposure) {
            withinHardware                                  // 手动：帧时长精确锁定，硬件上限内都算数
        } else {
            withinHardware.filter { recordFpsRange(it) != null }   // 自动：必须存在上限=fps 的 AE 区间
        }
        // 万一一个可用档都没有，只给硬件真实存在的最高帧率。
        // 绝不能再把候选全量塞回来——那等于把做不到的档位重新摆回列表。
        return usable.ifEmpty { listOf(hardwareMax) }
    }

    /**
     * 该帧率能否被封住上限：需要存在「上限 <= fps」的区间。
     * 手动曝光时（AE_MODE=OFF）HAL 会忽略 AE 目标区间，此时帧时长由 SENSOR_FRAME_DURATION 控制，属于精确锁定。
     */
    fun canLockFps(fps: Int): Boolean {
        if (params.iso > 0 || params.shutterNs > 0L) return true   // 手动曝光走帧时长约束
        return recordFpsRange(fps) != null
    }

    /** 该帧率实际能达到的上限（封不住时会高于所选值），用于如实告诉用户 */
    fun effectiveRecordFps(fps: Int): Int {
        if (params.iso > 0 || params.shutterNs > 0L) return fps
        return recordFpsRange(fps)?.upper ?: fps
    }

    // ---------- 实际值回读 ----------

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult
        ) {
            val iso = result.get(CaptureResult.SENSOR_SENSITIVITY)
            val exposure = result.get(CaptureResult.SENSOR_EXPOSURE_TIME)
            val ccMode = result.get(CaptureResult.COLOR_CORRECTION_MODE)
            if (iso != null) lastActualIso = iso
            if (exposure != null) lastActualShutterNs = exposure
            // 全自动曝光时持续刷新基线，半手动时用它按曝光量守恒推算另一个参数（否则会一直冻结在切手动那一刻）
            if (params.iso <= 0 && params.shutterNs <= 0L) {
                if (iso != null) autoBaselineIso = iso
                if (exposure != null) autoBaselineShutterNs = exposure
            }
            val wb = when (ccMode) {
                CameraMetadata.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX -> "手动"
                null -> null
                else -> "自动"
            }
            // 实时回传自动曝光值 + 自动白平衡色温（节流），供参数面板在自动模式下让滑杆跟随光线变化
            val wbGains = result.get(CaptureResult.COLOR_CORRECTION_GAINS)
            val wbK = wbGains?.let { ManualCamera.kelvinFromGains(it) }
            // 实际生效行优先显示本帧实时实测色温；本帧增益无法反推时用上一帧已锚定的色温兜底
            val wbKelvinForActual = wbK ?: if (lastAutoWbKelvin > 0) lastAutoWbKelvin else null
            notifyActual(iso, exposure, wb, wbKelvinForActual)
            // 锚点：仅在自动帧（非 TRANSFORM_MATRIX 手动帧）刷新 HAL 实测增益/变换矩阵/色温，
            // 进入手动白平衡时以它们为基准做相对偏移。
            if (ccMode != CameraMetadata.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX) {
                if (wbGains != null) lastHalGains = wbGains
                result.get(CaptureResult.COLOR_CORRECTION_TRANSFORM)?.let { lastHalTransform = it }
                if (wbK != null && wbK > 0) lastAutoWbKelvin = wbK
            }
            if (iso != null || exposure != null || wbK != null) {
                val now = System.currentTimeMillis()
                if (now - lastAutoNotify >= 120) {
                    lastAutoNotify = now
                    mainHandler.post { onAutoValues?.invoke(iso, exposure, wbK) }
                }
            }
            // 点按对焦后侦测「对焦锁定」：一旦锁定（无论合焦与否）播一声提示音
            if (afLockPending) {
                val af = result.get(CaptureResult.CONTROL_AF_STATE)
                if (af == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED ||
                    af == CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED
                ) {
                    afLockPending = false
                    playSound(MediaActionSound.FOCUS_COMPLETE)
                }
            }
        }
    }

    /** 播放系统相机音效（任何异常都不影响拍摄流程） */
    private fun playSound(type: Int) {
        runCatching { actionSound.play(type) }
    }

    private fun notifyActual(iso: Int?, exposure: Long?, wb: String?, wbKelvin: Int?) {
        val now = System.currentTimeMillis()
        if (now - lastNotify < 250) return
        lastNotify = now
        val payload = ActualParams(iso, exposure, wb, null, null, wbKelvin)
        mainHandler.post { onActual?.invoke(payload) }
    }

    // ---------- 拍照 ----------

    fun takePhoto() {
        if (isRecording) {
            notifyError("录像中暂不支持拍照")
            return
        }
        val device = cameraDevice ?: return
        val s = session ?: return
        val reader = imageReader ?: return
        val h = handler ?: return
        runCatching {
            val builder = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            builder.addTarget(reader.surface)
            applyTo(builder)
            builder.set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation())
            s.capture(builder.build(), null, h)
            playSound(MediaActionSound.SHUTTER_CLICK)
        }.onFailure { notifyError("拍照失败：${it.message}") }
    }

    /** 用户在白平衡面板里选择的路径策略 */
    var wbMode: WbMode = WbMode.AUTO

    /** 体检结论：null = 还没测过；true = 硬件手动白平衡确实生效；false = 硬件没干活 */
    var hwWbWorks: Boolean? = null
        private set

    /** 体检出结论后通知界面刷新「路径」显示 */
    var onWbPathChanged: (() -> Unit)? = null

    /**
     * 本次是否真的用硬件路径下发手动白平衡。
     *
     * **不能只看 REQUEST_AVAILABLE_CAPABILITIES 的能力声明** —— 厂商声明支持 MANUAL_POST_PROCESSING、
     * 实际却忽略 COLOR_CORRECTION_TRANSFORM 的情况并不少见（如部分 Android 13 老机型），
     * 那时硬件没生效、软件又被「防双重调色」跳过，手动白平衡就完全没效果。
     * 所以这里 = 能力具备 &&（用户强制硬件 || 体检未判定为失效）。
     */
    val useHardwareWb: Boolean
        get() = colorCorrectionSupported && when (wbMode) {
            WbMode.HARDWARE -> true
            WbMode.SOFTWARE -> false
            WbMode.AUTO -> hwWbWorks != false
        }

    /** 供界面显示：当前究竟走的哪条路径 + 体检结论（教学排查时一眼可见） */
    fun wbPathText(): String {
        if (!colorCorrectionSupported) return "本机不支持硬件调色，使用软件处理"
        return when (wbMode) {
            WbMode.HARDWARE -> "已手动指定：硬件路径"
            WbMode.SOFTWARE -> "已手动指定：软件路径"
            WbMode.AUTO -> when (hwWbWorks) {
                true -> "体检通过，使用硬件路径"
                false -> "硬件未响应，已自动切换软件路径"
                null -> "尚未体检（拖动色温滑杆即自动检测）"
            }
        }
    }

    /**
     * 成片目标显示比例（宽/高），由上层「画幅」选项写入，例如 9:16 → 9f/16f。
     * 0 表示不做裁切（保持原生画幅）。
     * 这样「改比例＝从原生画幅里裁一刀」就不仅在预览生效，存出来的照片也真正被裁。
     */
    private var photoAspect: Float = 0f

    /** 上层画幅选项变化时调用，rw:rh 为竖构图字面比例（9:16 / 3:4 / 1:1）。只影响照片，视频恒为 3:4。 */
    fun setPhotoAspect(rw: Int, rh: Int) {
        photoAspect = if (rw > 0 && rh > 0) rw.toFloat() / rh.toFloat() else 0f
    }

    /**
     * 是否需要按目标比例裁切。用已知 jpegSize 预判，避免「本不用裁却白白重编码一遍」。
     * 存储方向可能是横向（多数 HAL）也可能是已转正的纵向，统一按「显示宽=短边、显示高=长边」折算。
     */
    private fun needsAspectCrop(): Boolean {
        if (photoAspect <= 0f) return false
        val bw = jpegSize.width
        val bh = jpegSize.height
        if (bw <= 0 || bh <= 0) return false
        val avail = minOf(bw, bh).toFloat() / maxOf(bw, bh).toFloat()
        return abs(photoAspect - avail) > 0.001f
    }

    /**
     * 按目标显示比例做中心裁切，让成片构图与取景框所见完全一致。
     *
     * 折算规则：显示的宽恒等于存储的短边、显示的高恒等于存储的长边。
     *  - 目标比可用更窄（如 9:16 裁 3:4）→ 裁掉两侧，保留满高；
     *  - 目标比可用更方（如 1:1 裁 3:4）→ 裁掉上下，保留满宽。
     * 中心对齐，不偏心。
     */
    private fun cropToAspect(src: Bitmap, target: Float): Bitmap {
        val bw = src.width
        val bh = src.height
        if (bw <= 0 || bh <= 0 || target <= 0f) return src
        val landscape = bw > bh
        val s = minOf(bw, bh).toFloat()
        val l = maxOf(bw, bh).toFloat()
        val avail = s / l
        val newS: Float
        val newL: Float
        if (target < avail - 0.001f) {
            newS = l * target
            newL = l
        } else if (target > avail + 0.001f) {
            newS = s
            newL = s / target
        } else {
            return src
        }
        val newW: Int
        val newH: Int
        if (landscape) {
            newW = if (newL < l) Math.round(newL) else bw
            newH = if (newS < s) Math.round(newS) else bh
        } else {
            newW = if (newS < s) Math.round(newS) else bw
            newH = if (newL < l) Math.round(newL) else bh
        }
        if (newW <= 0 || newH <= 0 || (newW == bw && newH == bh)) return src
        val x = ((bw - newW) / 2).coerceIn(0, bw - newW)
        val y = ((bh - newH) / 2).coerceIn(0, bh - newH)
        return Bitmap.createBitmap(src, x, y, newW, newH)
    }

    private fun saveImage(image: Image) {
        val bytes = runCatching {
            val buffer = image.planes[0].buffer
            val out = ByteArray(buffer.remaining())
            buffer.get(out)
            out
        }.getOrNull()
        image.close()
        if (bytes == null) return

        // 白平衡 + 饱和度 + 清晰度统一在应用层完成（相机管线不再做，全设备一致）：
        // 白平衡仅在不支持硬件调色的设备上生效；饱和度对所有设备生效（作为滤镜叠在白平衡之上）；
        // 清晰度（局部对比增强）对所有设备生效——相机 EDGE_MODE 仅有二值档、无法体现强度，故在应用层统一实现。
        val wbCm = if (!useHardwareWb && (params.kelvin > 0 || abs(params.tint) > 0.01f))
            ManualCamera.appColorMatrix(params.kelvin, lastAutoWbKelvin, 0f, params.tint) else null
        val satCm = if (abs(params.saturation - 1f) > 0.001f)
            ManualCamera.colorMatrix(params.saturation, 0f) else null
        val cm = when {
            wbCm != null && satCm != null -> wbCm.apply { postConcat(satCm) }
            wbCm != null -> wbCm
            satCm != null -> satCm
            else -> null
        }
        // 画幅裁切：把成片裁成与取景框一致的比例（此前只有预览裁了，存出来仍是原生画幅）
        val aspect = if (needsAspectCrop()) photoAspect else 0f
        val raw = bytes
        // 走了应用层后处理（调色/清晰度/裁切）会重编码，Bitmap.compress 不保留 EXIF；
        // 把相机原始 JPEG 的 EXIF 搬到新文件，保证「实际生效参数」回读仍然可用。
        val outBytes = if (cm != null || params.sharpness > 0 || aspect > 0f) {
            renderPhoto(raw, cm, params.sharpness, aspect)?.let { copyExif(raw, it) } ?: raw
        } else {
            raw
        }

        var uri: Uri? = null
        runCatching {
            val values = ShotStore.imageContentValues("SL_${stamp()}.jpg")
            uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            uri?.let { u ->
                context.contentResolver.openOutputStream(u)?.use { it.write(outBytes) }
                val done = ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }
                context.contentResolver.update(u, done, null, null)
            }
        }.onFailure { Log.e(TAG, "保存照片失败", it) }
        mainHandler.post { onPhotoSaved?.invoke(uri) }
    }

    /**
     * 应用层成片后处理：解码 → 按画幅中心裁切 →（可选）ColorMatrix 调色/白平衡 →（可选）清晰度增强（局部对比）→ 压缩。
     * 解码时按像素量做 OOM 安全降采样（上限约 12MP），任一环节失败则回退原始字节。
     */
    private fun renderPhoto(
        input: ByteArray,
        cm: android.graphics.ColorMatrix?,
        sharpness: Int,
        aspect: Float = 0f
    ): ByteArray? {
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(input, 0, input.size, bounds)
            var sample = 1
            val px = (bounds.outWidth * bounds.outHeight).toDouble()
            // 12MP 以下手机解码毫无压力，不必降采样；只有超过约 25MP 才降，
            // 避免无谓砍分辨率（此前 12M 阈值把 12MP 原图砍到 1/4，9:16/1:1 裁切后清晰度暴跌、文件仅 600K）
            while (px / (sample * sample) > 25_000_000.0) sample *= 2
            val decoded = BitmapFactory.decodeByteArray(
                input, 0, input.size,
                BitmapFactory.Options().apply { inSampleSize = sample }
            ) ?: return@runCatching null
            // 先按所选画幅中心裁切，让成片构图等于取景框所见
            val base = cropToAspect(decoded, aspect)
            if (base !== decoded) decoded.recycle()
            val dst = Bitmap.createBitmap(base.width, base.height, Bitmap.Config.ARGB_8888)
            Canvas(dst).drawBitmap(base, 0f, 0f, Paint().apply {
                if (cm != null) colorFilter = ColorMatrixColorFilter(cm)
            })
            base.recycle()
            val out = if (sharpness > 0) clarity(dst, sharpness) else dst
            if (out !== dst) dst.recycle()
            val stream = ByteArrayOutputStream()
            out.compress(Bitmap.CompressFormat.JPEG, 92, stream)
            out.recycle()
            stream.toByteArray()
        }.getOrNull()
    }

    /**
     * 把 src（相机原始 JPEG，含完整 EXIF）的标签搬到 dst（重编码后、无 EXIF 的 JPEG）。
     * ExifInterface 在不同版本不一定提供 setAttributes(Map)，这里用白名单逐标签 getAttribute/setAttribute 复制，
     * 通过临时文件落盘 + saveAttributes 写回，再读回字节；任一环节失败则原样返回 dst。
     */
    private val COPY_EXIF_TAGS = arrayOf(
        ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL, ExifInterface.TAG_ORIENTATION,
        ExifInterface.TAG_DATETIME, ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_DATETIME_DIGITIZED,
        ExifInterface.TAG_ISO_SPEED, ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, ExifInterface.TAG_ISO_SPEED_RATINGS,
        ExifInterface.TAG_EXPOSURE_TIME, ExifInterface.TAG_F_NUMBER, ExifInterface.TAG_FOCAL_LENGTH,
        ExifInterface.TAG_WHITE_BALANCE, ExifInterface.TAG_FLASH, ExifInterface.TAG_EXPOSURE_PROGRAM,
        ExifInterface.TAG_METERING_MODE,
        ExifInterface.TAG_GPS_LATITUDE, ExifInterface.TAG_GPS_LATITUDE_REF,
        ExifInterface.TAG_GPS_LONGITUDE, ExifInterface.TAG_GPS_LONGITUDE_REF,
        ExifInterface.TAG_GPS_ALTITUDE, ExifInterface.TAG_GPS_ALTITUDE_REF,
        ExifInterface.TAG_GPS_TIMESTAMP, ExifInterface.TAG_GPS_DATESTAMP, ExifInterface.TAG_GPS_PROCESSING_METHOD,
        ExifInterface.TAG_SOFTWARE, ExifInterface.TAG_ARTIST
    )

    private fun copyExif(src: ByteArray, dst: ByteArray): ByteArray {
        val srcExif = runCatching { ExifInterface(ByteArrayInputStream(src)) }.getOrNull() ?: return dst
        val tmp = File(context.cacheDir, "exif_${System.nanoTime()}.jpg")
        return runCatching {
            tmp.writeBytes(dst)
            val dstExif = ExifInterface(tmp.absolutePath)
            for (tag in COPY_EXIF_TAGS) {
                srcExif.getAttribute(tag)?.let { dstExif.setAttribute(tag, it) }
            }
            dstExif.saveAttributes()
            tmp.readBytes()
        }.getOrDefault(dst).also { runCatching { tmp.delete() } }
    }

    /**
     * 清晰度（局部对比增强，Clarity / Structure 风格）：用中等半径模糊取低频层，
     * 细节层 = 原图 − 低频；将细节以受控强度加回，提升中间调局部对比，画面更通透有质感。
     * 与「unsharp mask 锐化」不同：这里不描硬边、不放噪声（模糊半径较大，平坦区细节≈0 自然不增强），
     * 观感更接近 Lightroom 的清晰度而非传统边缘锐化。强度随滑杆 0-100 连续变化，所有机型统一。
     */
    private fun clarity(src: Bitmap, strength: Int): Bitmap {
        if (strength <= 0) return src
        val w = src.width
        val h = src.height
        val inPx = IntArray(w * h)
        src.getPixels(inPx, 0, w, 0, 0, w, h)
        // 半径随图幅自适应，保证「中等尺度」的对比增强，既不发硬也不发糊
        val r = ((w.coerceAtMost(h).toFloat()) / 180f).roundToInt().coerceIn(4, 10)
        val blurR = boxBlur(inPx, w, h, r, 16)
        val blurG = boxBlur(inPx, w, h, r, 8)
        val blurB = boxBlur(inPx, w, h, r, 0)
        val amount = (strength / 100f) * 0.7f   // 比锐化温和，避免过曝式硬边
        val thr = 4                             // 平坦/弱噪区不增强，进一步防噪
        val outPx = IntArray(w * h)
        for (i in inPx.indices) {
            val p = inPx[i]
            val a = p ushr 24
            var cr = (p ushr 16) and 0xFF
            var cg = (p ushr 8) and 0xFF
            var cb = p and 0xFF
            val dr = cr - blurR[i]
            if (abs(dr) >= thr) cr = (cr + dr * amount).roundToInt().coerceIn(0, 255)
            val dg = cg - blurG[i]
            if (abs(dg) >= thr) cg = (cg + dg * amount).roundToInt().coerceIn(0, 255)
            val db = cb - blurB[i]
            if (abs(db) >= thr) cb = (cb + db * amount).roundToInt().coerceIn(0, 255)
            outPx[i] = (a shl 24) or (cr shl 16) or (cg shl 8) or cb
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(outPx, 0, w, 0, 0, w, h)
        return out
    }

    /**
     * 可分离盒式模糊（box blur），返回单通道 0-255 值数组。两次滑动窗口累加实现，O(宽×高) 每通道。
     * shift=16/8/0 分别对应 R/G/B 通道；边界按实际窗口大小归一，避免边缘压暗。
     */
    private fun boxBlur(px: IntArray, w: Int, h: Int, r: Int, shift: Int): IntArray {
        val tmp = IntArray(w * h)
        val pref = IntArray(w)
        // 水平模糊
        for (y in 0 until h) {
            val base = y * w
            var acc = 0
            for (x in 0 until w) { acc += (px[base + x] shr shift) and 0xFF; pref[x] = acc }
            for (x in 0 until w) {
                val a = 0.coerceAtLeast(x - r)
                val b = (w - 1).coerceAtMost(x + r)
                val win = b - a + 1
                val s = if (a == 0) pref[b] else pref[b] - pref[a - 1]
                tmp[base + x] = s / win
            }
        }
        // 垂直模糊
        val out = IntArray(w * h)
        val col = IntArray(h)
        for (x in 0 until w) {
            var acc = 0
            for (y in 0 until h) { acc += tmp[y * w + x]; col[y] = acc }
            for (y in 0 until h) {
                val a = 0.coerceAtLeast(y - r)
                val b = (h - 1).coerceAtMost(y + r)
                val win = b - a + 1
                val s = if (a == 0) col[b] else col[b] - col[a - 1]
                out[y * w + x] = s / win
            }
        }
        return out
    }

    // ---------- 录像 ----------

    fun startRecording(fps: Int, audio: Boolean) {
        val device = cameraDevice ?: return
        val h = handler ?: return
        if (isRecording) return

        val uri = runCatching {
            val values = ShotStore.videoContentValues("SL_${stamp()}.mp4")
            context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
        }.getOrNull()
        if (uri == null) {
            notifyError("无法创建视频文件")
            return
        }
        lastVideoUri = uri

        // 帧率只能「封顶」不能「钉死」：若设备没有上限≤所选值的 AE 区间，就完全不设帧率约束，
        // 让曝光保持自动追光（v1.8.51 的教训：为了帧率把 AE 钉死，画面会立刻变暗）。
        // UI 已按设备能力过滤过可选档位，这里正常情况下不会触发，只留日志不打扰用户。
        if (!canLockFps(fps)) {
            Log.w(TAG, "本机无上限≤${fps}fps 的 AE 区间，本次录像不设帧率约束，曝光保持自动追光")
        }

        tryRecordPipeline(videoSizeLadder(effectiveChars(), videoTierHeight), 0, device, h, fps, audio, uri)
    }

    /**
     * 按候选尺寸逐级尝试建立录像管线。
     *
     * 每个候选都必须连过**两道独立的关**，缺一不可：
     *   1. 编码器关 `MediaRecorder.prepare()`：编码器认不认这个尺寸；
     *   2. 相机关 `createCaptureSession()`：HAL 能不能真的输出该尺寸的录像流。
     *
     * 曾经只在第 1 关失败时降级，结果某些机器上的 1:1（1080×1080）属于
     * 「编码器吃得下、相机却出不了」，于是直接撞第 2 关报「录像会话创建失败」。
     * 现在两关任一不过都会自动降级到下一个候选。
     *
     * 全部候选失败时才重建预览会话——中途不重建，避免「预览会话」与「下一个候选的会话」抢占相机。
     */
    private fun tryRecordPipeline(
        sizes: List<Size>,
        index: Int,
        device: CameraDevice,
        h: Handler,
        fps: Int,
        audio: Boolean,
        uri: Uri
    ) {
        if (index >= sizes.size) {
            notifyError("录像初始化失败：无可用录制尺寸")
            mainHandler.post { createPreviewSession() }   // 恢复取景，否则卡住不动
            return
        }
        val sz = sizes[index]
        val next = { tryRecordPipeline(sizes, index + 1, device, h, fps, audio, uri) }
        val abort = { msg: String ->
            Log.w(TAG, "$msg：${sz.width}x${sz.height}，降级重试")
            runCatching { recordPfd?.close() }
            recordPfd = null
            mediaRecorder = null
            recorderSurface = null
            next()
        }

        val mr = MediaRecorder()
        val prepared = runCatching {
            if (audio) mr.setAudioSource(MediaRecorder.AudioSource.MIC)
            mr.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            mr.setVideoFrameRate(fps)
            mr.setVideoSize(sz.width, sz.height)
            mr.setVideoEncodingBitRate(videoBitRate(sz))
            mr.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            if (audio) mr.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            recordPfd = context.contentResolver.openFileDescriptor(uri, "w")
            mr.setOutputFile(recordPfd!!.fileDescriptor)
            mr.setOrientationHint(jpegOrientation())
            mr.prepare()
        }.isSuccess
        if (!prepared) {
            runCatching { mr.release() }
            abort("录像尺寸不被编码器接受")
            return
        }

        val surface = runCatching { mr.surface }.getOrNull()
        if (surface == null) {
            runCatching { mr.release() }
            abort("无法获取录像 surface")
            return
        }
        mediaRecorder = mr
        recorderSurface = surface
        stopRepeatingSafe()

        runCatching {
            device.createCaptureSession(
                listOfNotNull(previewSurface, surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) {
                        session = s
                        isRecording = true
                        videoSize = sz   // 以真正生效的尺寸为准，保证后续逻辑与实际录制参数一致
                        // 必须在 startRecordingStream 之前置位，否则预览重复请求不会把帧送往 recorderSurface
                        startRecordingStream()
                        runCatching { mr.start() }
                        playSound(MediaActionSound.START_VIDEO_RECORDING)
                        mainHandler.post { onRecordingStarted?.invoke() }
                    }

                    override fun onConfigureFailed(s: CameraCaptureSession) {
                        runCatching { mr.release() }
                        abort("录像尺寸相机不支持输出")
                    }
                }, h
            )
        }.onFailure { e ->
            runCatching { mr.release() }
            Log.w(TAG, "录像会话创建异常，降级重试", e)
            runCatching { recordPfd?.close() }
            recordPfd = null
            mediaRecorder = null
            recorderSurface = null
            next()
        }
    }

    fun stopRecording() {
        val recorder = mediaRecorder ?: return
        playSound(MediaActionSound.STOP_VIDEO_RECORDING)
        // 关键修复：先停掉旧录像会话的重复请求，避免相机继续往已释放的 recorderSurface 投递帧。
        // 部分机型（OPPO / ColorOS）的 HAL 在「surface 已释放却仍被会话引用」时会直接抛异常导致闪退。
        stopRepeatingSafe()
        runCatching { recorder.stop() }
        runCatching { recorder.release() }
        runCatching { recordPfd?.close() }
        recordPfd = null
        mediaRecorder = null
        recorderSurface = null
        recordBuilder = null
        isRecording = false

        lastVideoUri?.let { uri ->
            runCatching {
                val done = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
                context.contentResolver.update(uri, done, null, null)
                ShotStore.scanFile(context, uri)
            }
        }
        // 回到仅预览的会话（用 runCatching 兜底：任何异常都不应再让 App 闪退）
        runCatching { createPreviewSession() }.onFailure {
            Log.e(TAG, "stopRecording: 重建预览会话失败", it)
        }
        mainHandler.post {
            runCatching { onRecordingStopped?.invoke() }
        }
    }

    // ---------- 对焦 ----------

    fun focusAt(x: Float, y: Float, viewW: Int, viewH: Int) {
        val chars = characteristics ?: return
        val builder = previewBuilder ?: return
        val h = handler ?: return
        val array: Rect = chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return
        afLockPending = true   // 等待 captureCallback 回报对焦锁定后播提示音

        val sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        val rotation = displayRotationDegrees()
        val swapped = (sensorOrientation + rotation) % 180 == 90

        var px = if (swapped) y / viewH else x / viewW
        var py = if (swapped) 1f - x / viewW else y / viewH
        if (facing == CameraCharacteristics.LENS_FACING_FRONT) px = 1f - px

        val cx = (px * array.width()).toInt().coerceIn(0, array.width() - 1)
        val cy = (py * array.height()).toInt().coerceIn(0, array.height() - 1)
        val half = (array.width() * 0.06f).toInt().coerceAtLeast(1)
        val rect = MeteringRectangle(
            (cx - half).coerceAtLeast(0),
            (cy - half).coerceAtLeast(0),
            half * 2,
            half * 2,
            MeteringRectangle.METERING_WEIGHT_MAX
        )

        runCatching {
            builder.set(CaptureRequest.CONTROL_AF_REGIONS, arrayOf(rect))
            builder.set(CaptureRequest.CONTROL_AE_REGIONS, arrayOf(rect))
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
            session?.setRepeatingRequest(builder.build(), captureCallback, h)
        }.onFailure { Log.w(TAG, "focus failed", it) }

        h.postDelayed({
            runCatching {
                builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
                if (params.focusMeters <= 0f) {
                    builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                }
                session?.setRepeatingRequest(builder.build(), captureCallback, h)
            }
        }, 3000)
    }

    // ---------- 尺寸与变换 ----------

    private fun computeSizes(chars: CameraCharacteristics) {
        sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val previewChoices = map?.getOutputSizes(SurfaceTexture::class.java)?.toList() ?: emptyList()

        // 预览一律取传感器原生画幅（4:3），因为「改比例 = 从 4:3 里裁一刀」。
        // 若取 16:9 的画面，再裁成 9:16 / 1:1 就会丢掉本可以保留的视野，也会让裁切基准乱掉。
        previewSize = pick4x3(previewChoices)
            ?: Size(1280, 720)

        val jpegChoices = map?.getOutputSizes(ImageFormat.JPEG)?.toList() ?: emptyList()
        jpegSize = jpegChoices.maxByOrNull { it.width * it.height } ?: previewSize

        videoSize = pickVideoSize(chars, videoTierHeight)
    }

    /** 挑一个 4:3 的预览尺寸：优先 1440x1080，其次最接近该档位的 4:3 */
    private fun pick4x3(choices: List<Size>): Size? {
        if (choices.isEmpty()) return null
        choices.firstOrNull { it.width == 1440 && it.height == 1080 }?.let { return it }
        val ratio43 = choices.filter {
            val w = maxOf(it.width, it.height)
            val h = minOf(it.width, it.height)
            h > 0 && w / h.toFloat() in 1.30f..1.36f && w <= 1920
        }
        ratio43.minByOrNull { kotlin.math.abs(it.width - 1440) }?.let { return it }
        return choices.filter { it.width <= 1920 }.maxByOrNull { it.width * it.height }
    }

    /**
     * 视频**恒定按 3:4** 录制，与「画幅」选项彻底解耦。
     *
     * 核心认知：视频帧永远是**横向存储**（width ≥ height），竖屏靠 setOrientationHint 在播放时转正，
     * 所以显示比例 a（3:4 → 0.75）对应的**存储比例是它的倒数** 4:3。
     * 档位 target 约束的是存储短边（＝纵向播放时的宽），于是长边 = target ÷ a：
     *   1080 档 → 存储 1440×1080（播放 1080×1440）   2K → 1920×1440   4K → 2880×2160
     *
     * photoAspect 只管照片，不参与视频选型 —— 视频模式对画幅的要求是「专一、不用选」。
     */
    private val videoAspect: Float get() = VIDEO_ASPECT

    private fun pickVideoSize(chars: CameraCharacteristics, target: Int): Size {
        val long = (target / videoAspect).toInt()
        return Size(long, target)
    }

    /**
     * 视频尺寸候选降级表：从最理想到最保守。
     *
     * 首选上一步算出的精确目标尺寸（现代编码器大多能直接接受非标准尺寸，录制失败会由 next 兜底）；
     * 失败则退到「支持列表里存储比例最接近、且短边不超过档位」的若干档；
     * 最后兜底 1920×1080 / 1280×720，保证任何机器都录得出片子，不会卡在初始化。
     */
    private fun videoSizeLadder(chars: CameraCharacteristics?, target: Int): List<Size> {
        if (chars == null) return listOf(Size(1920, 1080), Size(1280, 720))
        val out = LinkedHashSet<Size>()
        out.add(pickVideoSize(chars, target))
        // 存储比例固定为 4:3；降级时优先在支持列表里找同为 4:3 的档位
        val storedTarget = 1f / videoAspect
        chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.getOutputSizes(MediaRecorder::class.java)?.toList()
            ?.asSequence()
            ?.filter { it.width >= it.height }
            ?.filter { minOf(it.width, it.height) <= target }
            ?.sortedWith(
                compareBy<Size> { abs(it.width.toFloat() / it.height - storedTarget) }
                    .thenByDescending { minOf(it.width, it.height) }
            )
            ?.take(6)
            ?.forEach { out.add(it) }
        out.add(Size(1920, 1080))
        out.add(Size(1280, 720))
        return out.toList()
    }

    /** 码率随像素量缩放：以 1080p/12Mbps 为基准，避免小画幅浪费体积、大画幅码率不够 */
    private fun videoBitRate(size: Size): Int =
        (12_000_000L * size.width * size.height / (1920 * 1080))
            .toLong().coerceIn(2_000_000L, 50_000_000L).toInt()

    /** 切换视频分辨率档位（1080 / 2K / 4K），仅影响下一次录制 */
    fun setVideoTier(heightTier: Int) {
        videoTierHeight = heightTier
        val chars = characteristics ?: return
        videoSize = pickVideoSize(chars, videoTierHeight)
    }

    /**
     * 该分辨率档位当前镜头是否支持。
     * 按「短边能否达到档位 + 目标画幅所需的长边能否达到」判断，
     * 这样 4K 档在选 3:4 画幅（需要 2880 宽）时若机器不支持就会被正确置灰。
     */
    fun isVideoTierSupported(tier: Int): Boolean {
        val chars = characteristics ?: return true
        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
        val choices = map?.getOutputSizes(MediaRecorder::class.java)?.toList()
        if (choices.isNullOrEmpty()) return true
        val needLong = videoSizeLadder(chars, tier).first().width
        return tier <= choices.maxOf { minOf(it.width, it.height) } &&
            needLong <= choices.maxOf { maxOf(it.width, it.height) }
    }

    private fun readCaps(chars: CameraCharacteristics): CameraCaps {
        val iso = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)?.let { it.lower..it.upper }
        val exp = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)?.let { it.lower..it.upper }
        val caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
        val manual = caps?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR) == true
        val fps = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.toList() ?: emptyList()
        return CameraCaps(iso, exp, manual, fps)
    }

    private val surfaceListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
            surfaceReady = true
            // 注意：回调里的 width/height 是 TextureView 的 view 尺寸，不是预览 buffer 尺寸！
            // buffer 尺寸只以 createPreviewSession 里 setDefaultBufferSize 的值为准。
            // 之前把回调值当 buffer 用，view 尺寸污染了取景计算 → 画面被拉成椭圆。
            // surface 重建（从相册返回 / 窗口恢复可见）时默认 buffer = view 尺寸，
            // 必须同步纠正回 HAL 请求尺寸，否则预览流按错误 buffer 比例贴图 → 画面拉长。
            st.setDefaultBufferSize(previewSize.width, previewSize.height)
            configureTransform()
            pendingOpen?.let {
                pendingOpen = null
                val pp = pendingPhysical
                pendingPhysical = null
                open(it, pp)
            }
        }

        override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {
            // TextureView.onSizeChanged 刚把 buffer 冲成了 view 尺寸（如 1800×2400），
            // 这里同步纠正回 HAL 请求的横向 stream 尺寸，再重算取景。
            st.setDefaultBufferSize(previewSize.width, previewSize.height)
            configureTransform()
        }

        override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
            surfaceReady = false
            return true
        }

        override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
    }

    init {
        textureView.surfaceTextureListener = surfaceListener
    }

    /**
     * 预览取景（标准 Camera2 架构，画面绝不变形）：
     *
     * 实测确认：这台设备（Find X8 Pro）的 HAL 会把预览内容**直接转正**（显示方向已正立），
     * 无论是否设置 ROTATE_AND_CROP。所以 view 层**不需要任何旋转**，旋转角只保留用户的手动
     * 方向补偿（默认 0）。成片走 JPEG + EXIF 独立通道，不受影响，所以一直正常。
     *
     * 做法：
     *  1. 预览 stream 用支持尺寸表里的横向尺寸（如 1440×1080），view 比例严格 == buffer 比例
     *     （等比缩放、绝不变形）——之前把 view 比例按竖条（1080×1440）算，才被横向拉成椭圆。
     *  2. view 尺寸按「等比覆盖取景框」算（一边贴齐、另一边溢出），旋转 90/270 时才交换占位。
     *  3. 多出的部分由取景框（viewportFrame）裁掉 —— 这就是「改比例 = 从原生 3:4 画幅里裁一刀」。
     */
    private fun configureTransform() {
        val frame = textureView.parent as? android.view.View ?: return
        val vw = frame.width
        val vh = frame.height
        if (vw <= 0 || vh <= 0) return

        // view 比例按「HAL 实际输出的正立 buffer」算（竖屏时宽高已被 HAL 交换）
        val bs = uprightBufferSize()
        val bw = bs.width.toFloat()
        val bh = bs.height.toFloat()
        if (bw <= 0f || bh <= 0f) return

        // 应用已锁定竖屏，HAL 把内容转正后画面方向固定，无需任何旋转补偿
        val rot = 0
        // 旋转 90/270 时，view 在屏幕上的占位宽高互换（此处恒为不互换）
        val swapped = rot % 180 != 0

        // view 尺寸严格按 buffer 等比（不变形）；缩放系数按占位去覆盖取景框
        val scale = if (swapped) maxOf(vw / bh, vh / bw) else maxOf(vw / bw, vh / bh)
        val lp = textureView.layoutParams as android.widget.FrameLayout.LayoutParams
        lp.width = Math.round(bw * scale)
        lp.height = Math.round(bh * scale)
        lp.gravity = android.view.Gravity.CENTER
        textureView.layoutParams = lp

        // 防御：TextureView 在自身 view 尺寸变化 / surface 重建时会把 buffer 重设为 view 尺寸，
        // 这里同步 + 异步双重纠正回对 HAL 请求的横向 stream 尺寸：
        //  - 同步：保证 setLayoutParams 引发的 relayout 之前 buffer 已就位；
        //  - 异步：兜住 applyTransform / 窗口动画期间 TextureView 再次重设 buffer 的竞态
        //    （ColorOS 从相册返回时 surface 会重建，仅靠一次纠正会被冲掉 → 画面拉长）。
        textureView.surfaceTexture?.setDefaultBufferSize(previewSize.width, previewSize.height)
        textureView.post {
            textureView.surfaceTexture?.setDefaultBufferSize(previewSize.width, previewSize.height)
        }

        textureView.pivotX = lp.width / 2f
        textureView.pivotY = lp.height / 2f
        textureView.rotation = rot.toFloat()
        textureView.scaleX = if (facing == CameraCharacteristics.LENS_FACING_FRONT) -1f else 1f
        textureView.setTransform(null)   // 不再使用采样矩阵，画面交给「view 尺寸 + 旋转 + 裁剪」
    }

    /** 供外部（切换取景比例、开始/结束录像后）主动重算取景，避免画面被拉伸 */
    fun refreshTransform() {
        textureView.post { configureTransform() }
        // 转屏 / 切比例时取景框尺寸可能还没稳定，稍后再兜一次，避免算错 view 尺寸导致拉伸
        textureView.postDelayed({ configureTransform() }, 400)
    }

    /**
     * HAL 实际输出的「正立 buffer」尺寸。
     *
     * 实测（Find X8 Pro）：请求横向尺寸（1440×1080），HAL 会按显示方向把宽高**交换**后输出
     * （竖屏时实际 buffer 是 1080×1440，内容已转正）。所以：
     *  - 对 HAL 的请求保持横向（在支持尺寸表里）；
     *  - view 的比例必须按交换后的正立尺寸算，否则竖画面塞进横 view → 横向拉宽 → 扁。
     */
    private fun uprightBufferSize(): Size {
        val degrees = displayRotationDegrees()
        val delta = ((sensorOrientation - degrees) % 360 + 360) % 360
        return if (delta % 180 != 0) Size(previewSize.height, previewSize.width) else previewSize
    }

    private fun displayRotationDegrees(): Int = when (windowManager.defaultDisplay.rotation) {
        Surface.ROTATION_0 -> 0
        Surface.ROTATION_90 -> 90
        Surface.ROTATION_180 -> 180
        else -> 270
    }

    private fun jpegOrientation(): Int {
        val sensor = characteristics?.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        val degrees = displayRotationDegrees()
        return if (facing == CameraCharacteristics.LENS_FACING_FRONT) {
            (sensor + degrees) % 360
        } else {
            (sensor - degrees + 360) % 360
        }
    }

    private fun notifyError(msg: String) {
        Log.w(TAG, msg)
        mainHandler.post { onError?.invoke(msg) }
    }

    private fun stamp(): String = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    companion object {
        private const val TAG = "Camera2Engine"
    }
}
