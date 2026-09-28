package com.farzanshibu.meowclaw.llm.cactus

import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

enum class Runtime { V1, V2 }

/** A downloadable on-device LLM, pinned to one Hugging Face revision and checksum. */
data class LocalModel(
    val id: String,
    val name: String,
    val runtime: Runtime,
    val repo: String,
    val revision: String,
    val file: String,
    val sizeBytes: Long,
    val sha256: String,
    val vision: Boolean = false,
    val tools: Boolean = false,
    val audio: Boolean = false,
    val note: String,
) {
    val url get() = "https://huggingface.co/Cactus-Compute/$repo/resolve/$revision/$file"
    val sizeLabel: String
        get() = if (sizeBytes >= 1_000_000_000) "%.1f GB".format(sizeBytes / 1e9) else "${sizeBytes / 1_000_000} MB"
}

object LocalModelCatalog {
    val models = listOf(
        LocalModel(
            "gemma-4-e2b", "Gemma 4 E2B", Runtime.V2, "gemma-4-E2B-it",
            "b305fe22361c50a28b8705e0d13349e5a93999b9", "gemma-4-e2b-it-cq2.zip", 2_278_035_905,
            "d0cc5bb7e611ae793032f9c7ed1f71023a3ec96111c0e75e44bbfb0c52a4d160",
            vision = true, tools = true, audio = true,
            note = "Best overall: sees screenshots, calls tools, multi-step tasks. Needs ~3 GB free RAM.",
        ),
        LocalModel(
            "qwen3.5-2b", "Qwen3.5 2B", Runtime.V1, "Qwen3.5-2B",
            "f906dab20c19645665a3149cfd75ae44454714da", "weights/qwen3.5-2b-int4.zip", 1_327_893_436,
            "e57c9e50ece5f9fd3ca366a5a0db5cc0e79303e595cf004912e9c6fa871feeb4",
            vision = true, tools = true, note = "Strong vision + tool calling for multi-step tasks.",
        ),
        LocalModel(
            "lfm2.5-1.2b-instruct", "LFM2.5 1.2B", Runtime.V1, "LFM2.5-1.2B-Instruct",
            "8e6f50a2b1b06108b11e12b81bc3772bc2cec262", "weights/lfm2.5-1.2b-instruct-int4.zip", 642_548_199,
            "3a42a4ebbdbd12f8deb93f680fdc504189997621ecfd4e34030573920fa24148",
            tools = true, note = "Fast text-only chat and tool calling from Liquid AI. Good pick for mid-range phones.",
        ),
        LocalModel(
            "qwen3.5-0.8b", "Qwen3.5 0.8B", Runtime.V1, "Qwen3.5-0.8B",
            "897a350e53237d51a33a9941223181850c0a773b", "weights/qwen3.5-0.8b-int4.zip", 572_752_875,
            "9fe7299a2a62fccc2b98ebe511babe805327a9bba04e75edfc29b7c0c6c1ae72",
            vision = true, tools = true, note = "Small vision + tools model for mid-range phones.",
        ),
        LocalModel(
            "lfm2.5-350m", "LFM2.5 350M", Runtime.V1, "LFM2.5-350M",
            "e9b5d30cb010281ad4ffdf91c20a85795c0e9cce", "weights/lfm2.5-350m-int4.zip", 208_802_942,
            "2ff2b73704c1a2507d83feaa67fde32c3d6f7f892e5eabd3583d17a9f84d2111",
            tools = true, note = "Tiny and very fast. Simple chat and tool calls on low-end phones.",
        ),
        LocalModel(
            "functiongemma-270m", "FunctionGemma 270M", Runtime.V1, "functiongemma-270m-it",
            "445f33df88f164522dd5d2a31247dc69ed99fa71", "weights/functiongemma-270m-it-int4.zip", 224_174_466,
            "f5b66b010fdbd8fd6b474d5d379b97bbdfb36e8b4f1b7bc6f28f2c71903e4455",
            tools = true, note = "Google's function-calling Gemma. Picks actions; not for chat.",
        ),
        LocalModel(
            "lfm2.5-vl-1.6b", "LFM2.5-VL 1.6B", Runtime.V1, "LFM2.5-VL-1.6B",
            "c23faf062eca5bf8544aaf75323a778274276052", "weights/lfm2.5-vl-1.6b-int4.zip", 1_284_108_073,
            "77144d33156013477060be27d913f54399b3bc8c6ddba73df11350b30a8964c3",
            vision = true, note = "Vision-language model for describing screens and images.",
        ),
        LocalModel(
            "lfm2-vl-450m", "LFM2-VL 450M", Runtime.V2, "LFM2-VL-450M",
            "bdb19a8e30ac9891a7ae316cb8668b1a309a2c74", "lfm2-vl-450m-cq4.zip", 382_870_180,
            "b3adcf299df8b0eac1a2224e8c90f910a1a88a719fb6f5211b14c7dc5e892e19",
            vision = true, note = "Tiny vision model. Quick screen descriptions.",
        ),
    )

    fun byId(id: String?) = models.firstOrNull { it.id == id }
}

sealed interface ModelState {
    data object NotDownloaded : ModelState
    data class Downloading(val progress: Float) : ModelState
    data object Extracting : ModelState
    data object Ready : ModelState
    data class Failed(val message: String) : ModelState
}

/** Downloads, verifies and unpacks on-device model bundles. */
class LocalModelManager(context: Context) {
    private val root = File(context.filesDir, "models")
    private val cache = File(context.cacheDir, "model-downloads")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = mutableMapOf<String, Job>()
    private val http = OkHttpClient.Builder().readTimeout(60, TimeUnit.SECONDS).build()

    private val statesFlow = MutableStateFlow(LocalModelCatalog.models.associate { it.id to initial(it) })
    val states: StateFlow<Map<String, ModelState>> = statesFlow.asStateFlow()

    /** Cactus ships arm64 runtimes only. */
    val isSupported: Boolean get() = Build.SUPPORTED_64_BIT_ABIS.contains("arm64-v8a")

    fun dir(model: LocalModel) = File(root, model.id)
    fun isReady(model: LocalModel) = statesFlow.value[model.id] == ModelState.Ready

    private fun initial(model: LocalModel): ModelState =
        if (File(dir(model), "config.txt").isFile) ModelState.Ready else ModelState.NotDownloaded

    private fun set(id: String, state: ModelState) = statesFlow.update { it + (id to state) }

    /** Re-reads installed bundles from disk, leaving in-flight downloads alone. */
    fun refresh() = statesFlow.update { current ->
        current.mapValues { (id, state) ->
            if (jobs[id]?.isActive == true) state
            else LocalModelCatalog.byId(id)?.let(::initial) ?: state
        }
    }

    fun download(model: LocalModel) {
        if (jobs[model.id]?.isActive == true) return
        jobs[model.id] = scope.launch {
            val zip = File(cache, "${model.id}.zip")
            try {
                set(model.id, ModelState.Downloading(0f))
                cache.mkdirs()
                fetch(model, zip)
                set(model.id, ModelState.Extracting)
                val staging = File(root, ".${model.id}.staging").apply { deleteRecursively(); mkdirs() }
                unzip(zip, staging)
                val bundle = promoteSingleRoot(staging)
                check(File(bundle, "config.txt").isFile) { "Archive has no config.txt" }
                val target = dir(model)
                target.deleteRecursively()
                check(bundle.renameTo(target)) { "Could not install the model" }
                staging.deleteRecursively()
                zip.delete()
                set(model.id, ModelState.Ready)
            } catch (e: Exception) {
                Log.e(TAG, "Model ${model.id} failed", e)
                set(model.id, if (e is kotlinx.coroutines.CancellationException) ModelState.NotDownloaded else ModelState.Failed(e.message ?: "Download failed"))
            }
        }
    }

    fun cancel(model: LocalModel) {
        jobs[model.id]?.cancel()
    }

    fun delete(model: LocalModel) {
        cancel(model)
        dir(model).deleteRecursively()
        File(root, ".${model.id}.staging").deleteRecursively()
        File(cache, "${model.id}.zip").delete()
        set(model.id, ModelState.NotDownloaded)
    }

    /** Removes every bundle, partial download and leftover folder, including models no longer in the catalog. */
    fun deleteAll() {
        jobs.values.forEach { it.cancel() }
        root.deleteRecursively()
        cache.deleteRecursively()
        statesFlow.value = LocalModelCatalog.models.associate { it.id to ModelState.NotDownloaded }
    }

    /** Bytes on disk for installed bundles and partial downloads. */
    fun usedBytes(): Long = listOf(root, cache).sumOf { dir -> dir.walkBottomUp().filter { it.isFile }.sumOf { it.length() } }

    /** Resumable download with SHA-256 verification. */
    private suspend fun fetch(model: LocalModel, zip: File) {
        val existing = if (zip.isFile) zip.length() else 0L
        if (existing == model.sizeBytes && sha256(zip) == model.sha256) return
        val request = Request.Builder().url(model.url)
            .apply { if (existing in 1 until model.sizeBytes) header("Range", "bytes=$existing-") }
            .build()
        http.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "HTTP ${response.code}" }
            val append = response.code == 206
            var written = if (append) existing else 0L
            response.body.byteStream().use { input ->
                java.io.FileOutputStream(zip, append).use { output ->
                    val buffer = ByteArray(256 * 1024)
                    var lastReport = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        written += n
                        if (written - lastReport > 2_000_000) {
                            lastReport = written
                            set(model.id, ModelState.Downloading(written.toFloat() / model.sizeBytes))
                        }
                    }
                }
            }
        }
        check(zip.length() == model.sizeBytes) { "Incomplete download" }
        if (sha256(zip) != model.sha256) {
            zip.delete()
            error("Checksum mismatch — download corrupted")
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 20)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private suspend fun unzip(zip: File, dest: File) {
        val canonical = dest.canonicalPath + File.separator
        ZipInputStream(zip.inputStream().buffered()).use { zin ->
            while (true) {
                currentCoroutineContext().ensureActive()
                val entry = zin.nextEntry ?: break
                val out = File(dest, entry.name)
                // Zip-slip guard.
                check(out.canonicalPath.startsWith(canonical)) { "Unsafe path in archive: ${entry.name}" }
                if (entry.isDirectory) out.mkdirs() else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { zin.copyTo(it, 1 shl 20) }
                }
            }
        }
    }

    /** Archives either hold the bundle at the root or under one top-level folder. */
    private fun promoteSingleRoot(dir: File): File {
        if (File(dir, "config.txt").isFile) return dir
        val children = dir.listFiles()?.filter { it.name != "__MACOSX" }.orEmpty()
        return if (children.size == 1 && children[0].isDirectory) children[0] else dir
    }

    companion object {
        private const val TAG = "LocalModels"
    }
}
