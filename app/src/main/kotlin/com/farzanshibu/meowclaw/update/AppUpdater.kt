package com.farzanshibu.meowclaw.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import com.farzanshibu.meowclaw.BuildConfig
import com.farzanshibu.meowclaw.data.AppJson
import com.farzanshibu.meowclaw.graph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** One APK in a release, as listed in update.json. */
@Serializable
data class UpdateApk(val url: String, val sha256: String, val size: Long = 0)

/** update.json, published with every GitHub release by the CI workflow. */
@Serializable
data class UpdateManifest(
    val versionCode: Int,
    val versionName: String,
    val tag: String = "",
    val notes: String = "",
    val releaseUrl: String = "",
    /** Keyed by ABI ("arm64-v8a", …) plus "universal". */
    val apks: Map<String, UpdateApk> = emptyMap(),
)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val manifest: UpdateManifest) : UpdateState
    data class Downloading(val manifest: UpdateManifest, val progress: Float?) : UpdateState
    data class Installing(val manifest: UpdateManifest) : UpdateState
    data class Failed(val message: String, val manifest: UpdateManifest? = null) : UpdateState
}

/**
 * Self-update from GitHub releases: reads update.json from the latest release,
 * downloads the APK for this device's ABI, checks its SHA-256 and installs it
 * with PackageInstaller. Updates must be signed with the same release key.
 */
class AppUpdater(private val context: Context) {
    private val state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val status: StateFlow<UpdateState> = state.asStateFlow()

    private val prefs = context.getSharedPreferences("meowclaw_update", Context.MODE_PRIVATE)
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    val currentVersion: String get() = BuildConfig.VERSION_NAME

    /** Checks at most every few hours unless [force]; quiet on failure when not forced. */
    suspend fun check(force: Boolean = false) {
        val busy = state.value
        if (busy is UpdateState.Checking || busy is UpdateState.Downloading || busy is UpdateState.Installing) return
        val now = System.currentTimeMillis()
        if (!force && now - prefs.getLong(K_LAST_CHECK, 0) < CHECK_INTERVAL_MS) return
        state.value = UpdateState.Checking
        state.value = try {
            val manifest = fetchManifest()
            prefs.edit().putLong(K_LAST_CHECK, now).apply()
            if (manifest.versionCode > BuildConfig.VERSION_CODE) UpdateState.Available(manifest) else UpdateState.UpToDate
        } catch (e: Exception) {
            Log.w(TAG, "Update check failed", e)
            if (force) UpdateState.Failed("Could not check for updates: ${e.message}") else UpdateState.Idle
        }
    }

    /** Downloads and installs [manifest]; asks for the install permission first when needed. */
    suspend fun install(manifest: UpdateManifest) {
        if (!canInstall()) {
            openInstallPermission()
            state.value = UpdateState.Failed("Allow MeowClaw to install apps, then tap UPDATE again.", manifest)
            return
        }
        val apk = pickApk(manifest) ?: run {
            state.value = UpdateState.Failed("This release has no APK for your device.", manifest)
            return
        }
        try {
            state.value = UpdateState.Downloading(manifest, null)
            val file = download(apk) { progress -> state.value = UpdateState.Downloading(manifest, progress) }
            state.value = UpdateState.Installing(manifest)
            withContext(Dispatchers.IO) { commit(file) }
        } catch (e: Exception) {
            Log.w(TAG, "Update failed", e)
            state.value = UpdateState.Failed("Update failed: ${e.message}", manifest)
        }
    }

    /** Called by [UpdateInstallReceiver] when the installer finishes without restarting us. */
    internal fun onInstallResult(status: Int, message: String?) {
        val manifest = (state.value as? UpdateState.Installing)?.manifest
        state.value = when (status) {
            PackageInstaller.STATUS_SUCCESS -> UpdateState.UpToDate
            PackageInstaller.STATUS_FAILURE_ABORTED -> manifest?.let { UpdateState.Available(it) } ?: UpdateState.Idle
            PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                UpdateState.Failed("Install refused: the update is signed with a different key. Uninstall this build first.", manifest)
            else -> UpdateState.Failed("Install failed: ${message ?: "status $status"}", manifest)
        }
    }

    private fun canInstall(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    private fun openInstallPermission() {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    private suspend fun fetchManifest(): UpdateManifest = withContext(Dispatchers.IO) {
        client.newCall(Request.Builder().url(BuildConfig.UPDATE_URL).header("Cache-Control", "no-cache").build()).execute().use { r ->
            if (!r.isSuccessful) throw IllegalStateException("HTTP ${r.code}")
            AppJson.decodeFromString(UpdateManifest.serializer(), r.body.string())
        }
    }

    private fun pickApk(manifest: UpdateManifest): UpdateApk? =
        Build.SUPPORTED_ABIS.firstNotNullOfOrNull { manifest.apks[it] } ?: manifest.apks["universal"]

    private suspend fun download(apk: UpdateApk, onProgress: (Float?) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { deleteRecursively(); mkdirs() }
        val file = File(dir, "update.apk")
        val digest = MessageDigest.getInstance("SHA-256")
        client.newCall(Request.Builder().url(apk.url).build()).execute().use { r ->
            if (!r.isSuccessful) throw IllegalStateException("HTTP ${r.code}")
            val total = r.body.contentLength().takeIf { it > 0 } ?: apk.size.takeIf { it > 0 }
            var read = 0L
            var lastReported = -1
            r.body.byteStream().use { input ->
                file.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        digest.update(buffer, 0, n)
                        read += n
                        val percent = total?.let { (read * 100 / it).toInt() } ?: -1
                        if (percent != lastReported) {
                            lastReported = percent
                            onProgress(total?.let { read.toFloat() / it })
                        }
                    }
                }
            }
        }
        val actual = digest.digest().joinToString("") { "%02x".format(it) }
        if (!actual.equals(apk.sha256, ignoreCase = true)) {
            file.delete()
            throw IllegalStateException("checksum mismatch")
        }
        file
    }

    private fun commit(file: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(file.length())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("base.apk", 0, file.length()).use { out ->
                file.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
            val callback = PendingIntent.getBroadcast(
                context, sessionId, Intent(context, UpdateInstallReceiver::class.java), flags,
            )
            session.commit(callback.intentSender)
        }
    }

    companion object {
        private const val TAG = "AppUpdater"
        private const val K_LAST_CHECK = "last_check"
        private const val CHECK_INTERVAL_MS = 6 * 60 * 60 * 1000L
    }
}

/** Receives PackageInstaller results: shows the confirm dialog when the system asks for one. */
class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            @Suppress("DEPRECATION")
            val confirm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
            } else intent.getParcelableExtra(Intent.EXTRA_INTENT)
            confirm?.let { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            return
        }
        context.graph.updater.onInstallResult(status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))
    }
}
