package com.farzanshibu.meowclaw.llm.cactus

import android.system.Os
import android.util.Log

/** Receives streamed tokens from either Cactus runtime (bound by method name). */
fun interface CactusTokenCallback {
    fun onToken(token: String, tokenId: Int)
}

/**
 * The runtimes also honour these at startup; the build additionally compiles
 * cloud telemetry and cloud handoff out (scripts/build-cactus.sh).
 */
private fun disableCloud() {
    runCatching {
        Os.setenv("CACTUS_NO_CLOUD_TELE", "1", true)
        Os.setenv("CACTUS_DISABLE_CLOUD_HANDOFF", "1", true)
    }
}

/** Cactus v2 runtime (libcactus_engine.so): Gemma 4 and LFM2-VL CQ bundles. */
internal object CactusV2Native {
    val loaded: Boolean = runCatching {
        disableCloud()
        System.loadLibrary("cactus_engine")
    }.onFailure { Log.w("CactusV2", "Runtime unavailable: ${it.message}") }.isSuccess

    @JvmStatic external fun nativeInit(modelPath: String, corpusDir: String?, cacheIndex: Boolean): Long
    @JvmStatic external fun nativeDestroy(handle: Long)
    @JvmStatic external fun nativeReset(handle: Long)
    @JvmStatic external fun nativeStop(handle: Long)
    @JvmStatic external fun nativeComplete(
        handle: Long, messagesJson: String, responseBuffer: ByteArray, optionsJson: String?,
        toolsJson: String?, callback: CactusTokenCallback?, pcmData: ByteArray?,
    ): Int
    @JvmStatic external fun nativeGetLastError(): String
    @JvmStatic external fun nativeSetTelemetryEnvironment(framework: String?, cacheLocation: String?, version: String?)
}

/** Cactus v1.14 runtime (libcactus.so): Qwen3/3.5, FunctionGemma, Gemma 3, LFM2 int4 bundles. */
internal object CactusV1Native {
    val loaded: Boolean = runCatching {
        disableCloud()
        System.loadLibrary("cactus")
    }.onFailure { Log.w("CactusV1", "Runtime unavailable: ${it.message}") }.isSuccess

    @JvmStatic external fun nativeInit(modelPath: String, corpusDir: String?, cacheIndex: Boolean): Long
    @JvmStatic external fun nativeDestroy(handle: Long)
    @JvmStatic external fun nativeReset(handle: Long)
    @JvmStatic external fun nativeStop(handle: Long)
    @JvmStatic external fun nativeComplete(
        handle: Long, messagesJson: String, optionsJson: String?, toolsJson: String?,
        callback: CactusTokenCallback?, pcmData: ByteArray?,
    ): String
    @JvmStatic external fun nativeGetLastError(): String
    @JvmStatic external fun nativeSetCacheDir(cacheDir: String)
}
