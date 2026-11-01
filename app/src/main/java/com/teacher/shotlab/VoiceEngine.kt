package com.teacher.shotlab

/**
 * 声控拍照引擎接口。[VoiceTrigger] 为内置 TFLite 关键词唤醒实现，
 * 完全离线、不依赖系统语音服务，APK 体积增量约 4MB（TFLite 运行时 + <1MB 模型）。
 */
interface VoiceEngine {

    interface Callback {
        fun onReady()
        fun onTrigger(word: String)
        fun onError(msg: String)
    }

    fun start(callback: Callback)
    fun stop()
    fun isRunning(): Boolean
}
