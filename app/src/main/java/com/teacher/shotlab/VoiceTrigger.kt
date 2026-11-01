package com.teacher.shotlab

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import kotlin.concurrent.thread
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 声控拍照：内置 TFLite 关键词唤醒（KWS），完全离线、不依赖任何系统语音服务。
 *
 * 架构（与训练脚本 tools/train_kws.py 严格一致，零特征差异）：
 *   麦克风 16k/MONO PCM
 *     -> 取最近 1.0s 波形并加汉宁窗分帧（FRAME_LEN=400, FRAME_STEP=160）
 *     -> 512 点 FFT 取幅度谱前 257 bin
 *     -> 乘 mel 矩阵(assets/kws_mel.txt, 40×257) 得 40 维 Mel
 *     -> 取自然对数得 log-Mel 频谱图 [98, 40]
 *     -> 送入模型（输入 [1,98,40,1]，输出 [1,5]）
 *
 * 之所以把 STFT/Mel 放在端上算：模型内部若含 tf.signal 算子，TFLite 转换器
 * 无法导出；现在模型只含 Conv/BN/Pool/Dense 等原生算子，才能导出并在手机运行。
 *
 * 类别（与 train_kws.py 标签顺序严格一致）：
 *   0 = 其他(背景)  1 = 拍照  2 = 拍张照  3 = 咔嚓  4 = 茄子
 * 命中 1..4 任一类且得分超过阈值即拍照。
 */
class VoiceTrigger(private val app: Context) : VoiceEngine {

    private var cb: VoiceEngine.Callback? = null
    private var interpreter: Interpreter? = null
    private var rec: AudioRecord? = null

    @Volatile private var running = false
    @Volatile private var busy = false
    @Volatile private var stopped = true

    /** 环形缓冲：持续写入最近 1.0s 波形 */
    private val ring = FloatArray(WIN)
    private var writePos = 0
    private var filled = 0

    /** mel 矩阵，扁平存放 [N_MEL * N_BINS] */
    private var melMat: FloatArray? = null

    /** 模型输入 [1][N_FRAMES][N_MEL][1]，输出 [1][N_CLASSES] */
    private val input = Array(1) { Array(N_FRAMES) { Array(N_MEL) { FloatArray(1) } } }
    private val output = Array(1) { FloatArray(N_CLASSES) }

    /** FFT 复用缓冲，避免每帧分配 */
    private val fftRe = FloatArray(N_FFT)
    private val fftIm = FloatArray(N_FFT)
    private val magBuf = FloatArray(N_BINS)
    private val window = FloatArray(WIN)

    @Volatile private var lastHitMs = 0L

    companion object {
        private const val SR = 16000
        private const val WIN = SR                 // 1.0s 窗口
        private const val FRAME_LEN = 400          // 25ms
        private const val FRAME_STEP = 160         // 10ms
        private const val N_FFT = 512
        private const val N_BINS = N_FFT / 2 + 1   // 257
        private const val N_MEL = 40
        private const val N_FRAMES = 98            // 1 + (16000-400)/160
        private const val N_CLASSES = 5
        private const val COOLDOWN_MS = 2500L      // 触发后冷却，避免一句话连拍多张
        private const val MODEL_ASSET = "kws.tflite"
        private const val MEL_ASSET = "kws_mel.txt"
        // 关键词命中阈值：刻意取一个「很确信」的高值。低于它一律什么都不做，
        // 宁可漏拍也不误拍。实测(合成集)：0.85 时召回 86.9%、误触发仅 0.75%；
        // 若实机觉得喊不动，可降到 0.70(召回 91.9%、误触发 2.5%)。
        private const val THRESHOLD = 0.85f
        // 能量门控：RMS 低于此值视为静音，直接跳过推理。
        // 没说话时麦克风接近纯静音，属训练分布外，模型易高置信度乱判关键词
        // （表现为「什么也没说却不停拍照」）。这是比模型更靠前的硬防线。
        private const val ENERGY_GATE = 0.008f
        private val WORDS = arrayOf("", "拍照", "拍张照", "咔嚓", "茄子")

        /** 汉宁窗(symmetric)：win[n] = 0.5 - 0.5*cos(2π n/(N-1))，与 numpy np.hanning 一致 */
        private val HANN = FloatArray(FRAME_LEN) { n ->
            (0.5f - 0.5f * cos(2.0 * Math.PI * n / (FRAME_LEN - 1)).toFloat())
        }
    }

    override fun isRunning() = running

    override fun start(callback: VoiceEngine.Callback) {
        if (running || busy) return
        cb = callback
        if (ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            callback.onError("需要麦克风权限才能使用声控")
            return
        }
        busy = true
        thread(name = "kws-init") {
            try {
                // 先加载 mel 矩阵，再加载模型（缺任一都会走 catch 提示，不会崩）
                melMat = loadMelMatrix()
                val buffer = loadModel()
                val opts = Interpreter.Options().apply { setNumThreads(4) }
                val interp = Interpreter(buffer, opts)

                val minBuf = AudioRecord.getMinBufferSize(
                    SR, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
                )
                val audio = AudioRecord(
                    MediaRecorder.AudioSource.MIC, SR,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(minBuf, 2048)
                )
                audio.startRecording()

                interpreter = interp
                rec = audio
                stopped = false
                running = true
                busy = false
                runOnUi { callback.onReady() }
                listenLoop(audio, interp)
            } catch (t: Throwable) {
                running = false
                busy = false
                stopped = true
                runOnUi { cb?.onError("声控启动失败：${t.message ?: t.javaClass.simpleName}") }
            }
        }
    }

    private fun listenLoop(audio: AudioRecord, interp: Interpreter) {
        val buf = ShortArray(1024)
        while (!stopped) {
            val n = runCatching { audio.read(buf, 0, buf.size) }.getOrElse { -1 }
            if (n <= 0) { Thread.sleep(20); continue }

            for (i in 0 until n) {
                val s = buf[i] / 32768f
                ring[writePos] = if (s > 1f) 1f else if (s < -1f) -1f else s
                writePos = (writePos + 1) % WIN
                if (filled < WIN) filled++
            }

            if (filled >= WIN) {
                // 环形缓冲按时间顺序拷成 1.0s 窗口
                for (i in 0 until WIN) window[i] = ring[(writePos + i) % WIN]
                // 能量门控：静音/极弱底噪直接跳过推理，避免分布外输入误触发
                if (rms(window) < ENERGY_GATE) continue
                // 端上计算 log-Mel 频谱图
                computeLogMel(window)
                interp.run(input, output)
                val scores = output[0]

                // 触发判断（刻意简单且保守）：
                // 只在「4 个关键词里的最高分 >= THRESHOLD」时才拍照。
                // 其余一切情况 —— 静音、环境噪声、说的不是关键词、模型拿不准 ——
                // 全部什么都不做。
                var kwIdx = -1          // 命中的关键词下标；-1 表示没有像样的关键词
                var kw = 0f             // 关键词里的最高分
                for (k in 1 until N_CLASSES) {   // 下标 0 是「其他/背景」，不参与
                    if (scores[k] > kw) { kw = scores[k]; kwIdx = k }
                }
                if (kwIdx >= 1 && kw >= THRESHOLD) {
                    val now = System.currentTimeMillis()
                    if (now - lastHitMs >= COOLDOWN_MS) {
                        lastHitMs = now
                        runOnUi { cb?.onTrigger(WORDS[kwIdx]) }
                    }
                }
            }
        }
    }

    /** 波形 RMS 能量，用于静音门控。 */
    private fun rms(wav: FloatArray): Float {
        var sum = 0f
        for (v in wav) sum += v * v
        return sqrt(sum / wav.size)
    }

    /**
     * 计算 log-Mel 频谱图并写入 [input]。
     * 与 train_kws.py 的 compute_logmel 一一对应：汉宁窗 -> 补零到 512 -> FFT
     * -> 幅度谱前 257 bin -> mel 矩阵映射 -> 自然对数。
     */
    private fun computeLogMel(wav: FloatArray) {
        val mel = melMat!!
        for (t in 0 until N_FRAMES) {
            val start = t * FRAME_STEP
            // 加窗并补零到 N_FFT
            for (m in 0 until FRAME_LEN) {
                fftRe[m] = wav[start + m] * HANN[m]
                fftIm[m] = 0f
            }
            for (m in FRAME_LEN until N_FFT) {
                fftRe[m] = 0f
                fftIm[m] = 0f
            }
            fft(fftRe, fftIm)
            // 幅度谱
            for (b in 0 until N_BINS) {
                magBuf[b] = sqrt(fftRe[b] * fftRe[b] + fftIm[b] * fftIm[b])
            }
            // mel 映射 + log
            for (m in 0 until N_MEL) {
                var s = 0f
                val base = m * N_BINS
                for (b in 0 until N_BINS) s += mel[base + b] * magBuf[b]
                input[0][t][m][0] = kotlin.math.ln(s + 1e-6f)
            }
        }
    }

    /** 原地 radix-2 迭代 FFT，长度须为 2 的幂。未归一化（与 numpy 一致）。 */
    private fun fft(re: FloatArray, im: FloatArray) {
        val n = re.size
        // 位反转置换
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        // 蝶形运算
        var len = 2
        while (len <= n) {
            val ang = -2.0 * Math.PI / len
            val wr = cos(ang).toFloat()
            val wi = sin(ang).toFloat()
            val half = len shr 1
            var i = 0
            while (i < n) {
                var curWr = 1f
                var curWi = 0f
                for (k in 0 until half) {
                    val a = i + k
                    val b = i + k + half
                    val tre = re[b] * curWr - im[b] * curWi
                    val tim = re[b] * curWi + im[b] * curWr
                    re[b] = re[a] - tre
                    im[b] = im[a] - tim
                    re[a] = re[a] + tre
                    im[a] = im[a] + tim
                    val nwr = curWr * wr - curWi * wi
                    curWi = curWr * wi + curWi * wr
                    curWr = nwr
                }
                i += len
            }
            len = len shl 1
        }
    }

    override fun stop() {
        stopped = true
        running = false
        try { rec?.stop() } catch (_: Throwable) {}
        try { rec?.release() } catch (_: Throwable) {}
        rec = null
        try { interpreter?.close() } catch (_: Throwable) {}
        interpreter = null
    }

    /** 把 assets 里的模型映射为 ByteBuffer（<1MB，直接内存映射，无需落盘解压）。 */
    private fun loadModel(): ByteBuffer {
        val afd = app.assets.openFd(MODEL_ASSET)
        val fc = FileInputStream(afd.fileDescriptor).channel
        return fc.map(FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.declaredLength)
            .also { afd.close() }
    }

    /** 读取 mel 矩阵（kws_mel.txt：N_MEL 行 × N_BINS 列，空格分隔）。 */
    private fun loadMelMatrix(): FloatArray {
        val text = app.assets.open(MEL_ASSET).bufferedReader().use { it.readText() }
        val out = FloatArray(N_MEL * N_BINS)
        var idx = 0
        var i = 0
        val len = text.length
        while (i < len && idx < out.size) {
            // 跳过空白
            while (i < len && text[i].isWhitespace()) i++
            if (i >= len) break
            val s = i
            while (i < len && !text[i].isWhitespace()) i++
            out[idx++] = text.substring(s, i).toFloat()
        }
        require(idx == N_MEL * N_BINS) { "mel 矩阵数量异常: $idx" }
        return out
    }

    private fun runOnUi(block: () -> Unit) {
        android.os.Handler(app.mainLooper).post { runCatching { block() } }
    }
}
