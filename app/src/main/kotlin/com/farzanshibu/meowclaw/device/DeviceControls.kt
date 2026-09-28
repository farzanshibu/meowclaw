package com.farzanshibu.meowclaw.device

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.bluetooth.BluetoothManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.Settings
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

data class InstalledApp(val name: String, val packageName: String)

class AppLauncher(private val context: Context) {
    @Volatile private var cache: List<InstalledApp>? = null

    suspend fun installedApps(): List<InstalledApp> = cache ?: withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        pm.queryIntentActivities(intent, 0)
            .map { InstalledApp(it.loadLabel(pm).toString(), it.activityInfo.packageName) }
            .distinctBy { it.packageName }
            .sortedBy { it.name.lowercase() }
    }.also { cache = it }

    fun clearCache() {
        cache = null
    }

    /** Fuzzy match by label: exact, then prefix, then contains, then package name. */
    suspend fun find(appName: String): InstalledApp? {
        val query = appName.trim().lowercase().removeSuffix(" app").trim()
        if (query.isEmpty()) return null
        val apps = installedApps()
        val compact = query.replace(" ", "")
        return apps.firstOrNull { it.name.lowercase() == query }
            ?: apps.firstOrNull { it.name.lowercase().replace(" ", "") == compact }
            ?: apps.firstOrNull { it.name.lowercase().startsWith(query) }
            ?: apps.firstOrNull { it.name.lowercase().contains(query) }
            ?: apps.firstOrNull { it.packageName.lowercase().contains(compact) }
    }

    suspend fun openApp(appName: String): String {
        val app = find(appName) ?: return "Could not find app \"$appName\". Try being more specific."
        return launch(app.packageName, app.name)
    }

    fun openPackage(packageName: String): String = launch(packageName, packageName)

    /** Installed apps whose name or package matches [query]; all apps when blank. */
    suspend fun lookup(query: String?): String {
        val q = query?.trim()?.lowercase().orEmpty()
        val apps = installedApps().filter { q.isEmpty() || it.name.lowercase().contains(q) || it.packageName.lowercase().contains(q) }
        if (apps.isEmpty()) return "No installed app matches \"$query\"."
        return apps.take(40).joinToString("\n", prefix = "Installed apps:\n") { "${it.name} (${it.packageName})" } +
            if (apps.size > 40) "\n…and ${apps.size - 40} more" else ""
    }

    /** Opens any URI (app deep link, geo:, tel:, market:, https:), optionally in one [packageName]. */
    fun openDeepLink(uri: String, packageName: String?): String {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri.trim())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (!packageName.isNullOrBlank()) intent.setPackage(packageName.trim())
        return try {
            context.startActivity(intent)
            "Opened $uri"
        } catch (e: ActivityNotFoundException) {
            "Error: no app can open $uri"
        } catch (e: SecurityException) {
            "Error: $uri is not allowed from other apps"
        }
    }

    fun webSearch(query: String): String {
        if (query.isBlank()) return "Error: empty search"
        return openUrl("https://www.google.com/search?q=" + Uri.encode(query.trim())).let {
            if (it.startsWith("Opened")) "Searching the web for \"$query\"" else it
        }
    }

    private fun launch(packageName: String, label: String): String {
        val pm = context.packageManager
        // getLaunchIntentForPackage needs package visibility; the launcher-intent query
        // declared in the manifest finds the same activity for any installed app.
        val intent = pm.getLaunchIntentForPackage(packageName)
            ?: pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(packageName), 0)
                .firstOrNull()?.activityInfo
                ?.let { Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setClassName(it.packageName, it.name) }
            ?: return "Error opening $label: no launchable activity"
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return try {
            context.startActivity(intent)
            "Opened $label"
        } catch (e: Exception) {
            "Error opening $label: ${e.message}"
        }
    }

    fun openUrl(url: String): String {
        val target = if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:").containsMatchIn(url)) url else "https://$url"
        return try {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(target)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            "Opened $target"
        } catch (e: ActivityNotFoundException) {
            "Cannot open $target"
        }
    }
}

data class ContactEntry(val name: String, val phone: String?, val email: String?)

class ContactsRepository(private val context: Context) {
    fun hasPermission() = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CONTACTS) ==
        PackageManager.PERMISSION_GRANTED

    suspend fun search(query: String): List<ContactEntry> = withContext(Dispatchers.IO) {
        if (!hasPermission() || query.isBlank()) return@withContext emptyList()
        val resolver = context.contentResolver
        val results = linkedMapOf<Long, ContactEntry>()
        resolver.query(
            ContactsContract.Contacts.CONTENT_URI,
            arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.DISPLAY_NAME_PRIMARY),
            "${ContactsContract.Contacts.DISPLAY_NAME_PRIMARY} LIKE ?",
            arrayOf("%${query.trim()}%"),
            ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getLong(0)
                val name = cursor.getString(1) ?: continue
                results[id] = ContactEntry(name, firstValue(id, phone = true), firstValue(id, phone = false))
            }
        }
        // Exact names first, so "Mom" beats "Mommy's Bakery".
        results.values.sortedBy { if (it.name.equals(query.trim(), ignoreCase = true)) 0 else 1 }
    }

    private fun firstValue(contactId: Long, phone: Boolean): String? {
        val uri = if (phone) ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        else ContactsContract.CommonDataKinds.Email.CONTENT_URI
        val column = if (phone) ContactsContract.CommonDataKinds.Phone.NUMBER
        else ContactsContract.CommonDataKinds.Email.ADDRESS
        val idColumn = if (phone) ContactsContract.CommonDataKinds.Phone.CONTACT_ID
        else ContactsContract.CommonDataKinds.Email.CONTACT_ID
        return context.contentResolver.query(uri, arrayOf(column), "$idColumn = ?", arrayOf(contactId.toString()), null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }
    }

    suspend fun phoneNumber(name: String): String? = search(name).firstOrNull { it.phone != null }?.phone

    suspend fun searchAndFormat(query: String): String {
        if (!hasPermission()) return "Contacts permission is not granted."
        val contacts = search(query)
        if (contacts.isEmpty()) return "No contacts found matching \"$query\"."
        return buildString {
            appendLine("Found ${contacts.size} contact(s):")
            contacts.take(5).forEach { c ->
                append("• ${c.name}")
                c.phone?.let { append(" - $it") }
                c.email?.let { append(" - $it") }
                appendLine()
            }
            if (contacts.size > 5) appendLine("...and ${contacts.size - 5} more")
        }
    }
}

class Communication(private val context: Context, private val contacts: ContactsRepository) {
    private suspend fun resolve(contactName: String?, phoneNumber: String?): Pair<String?, String?> {
        if (!phoneNumber.isNullOrBlank()) return phoneNumber to null
        if (contactName.isNullOrBlank()) return null to "No phone number provided."
        val number = contacts.phoneNumber(contactName)
            ?: return null to "Could not find contact \"$contactName\"."
        return number to null
    }

    suspend fun makeCall(contactName: String?, phoneNumber: String?): String {
        val (number, error) = resolve(contactName, phoneNumber)
        if (number == null) return error!!
        // ACTION_DIAL shows the number; the user confirms the call.
        return start(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", number, null)))
            ?: "Calling $number${contactName?.let { " ($it)" } ?: ""}..."
    }

    suspend fun sendSms(contactName: String?, phoneNumber: String?, message: String): String {
        val (number, error) = resolve(contactName, phoneNumber)
        if (number == null) return error!!
        val intent = Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", number, null))
            .putExtra("sms_body", message)
        return start(intent)
            ?: "Opening SMS to $number${contactName?.let { " ($it)" } ?: ""} with message: \"$message\""
    }

    fun sendEmail(to: String, subject: String?, body: String?): String {
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:")).apply {
            putExtra(Intent.EXTRA_EMAIL, arrayOf(to))
            subject?.let { putExtra(Intent.EXTRA_SUBJECT, it) }
            body?.let { putExtra(Intent.EXTRA_TEXT, it) }
        }
        return start(intent) ?: "Opening email to $to"
    }

    /** Returns an error message, or null when the activity started. */
    private fun start(intent: Intent): String? = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        null
    } catch (e: ActivityNotFoundException) {
        "No app on this device can handle that."
    }
}

class AlarmControl(private val context: Context) {
    fun setAlarm(hour: Int, minute: Int, label: String?): String = try {
        val intent = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, hour.coerceIn(0, 23))
            .putExtra(AlarmClock.EXTRA_MINUTES, minute.coerceIn(0, 59))
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        label?.let { intent.putExtra(AlarmClock.EXTRA_MESSAGE, it) }
        context.startActivity(intent)
        "Alarm set for %02d:%02d%s".format(hour, minute, label?.let { " ($it)" } ?: "")
    } catch (e: Exception) {
        "Error setting alarm: ${e.message}"
    }

    fun setTimer(seconds: Int, label: String?): String = try {
        val intent = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, seconds.coerceAtLeast(1))
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        label?.let { intent.putExtra(AlarmClock.EXTRA_MESSAGE, it) }
        context.startActivity(intent)
        "Timer set for ${seconds / 60}m ${seconds % 60}s${label?.let { " ($it)" } ?: ""}"
    } catch (e: Exception) {
        "Error setting timer: ${e.message}"
    }
}

class SystemControl(private val context: Context) {
    private val audio = context.getSystemService(AudioManager::class.java)

    fun setVolume(level: Int): String = try {
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, (level.coerceIn(0, 100) * max / 100.0).roundToInt(), 0)
        "Volume set to $level%"
    } catch (e: Exception) {
        "Error setting volume: ${e.message}"
    }

    fun canWriteSettings(): Boolean = Settings.System.canWrite(context)

    /** One step louder/quieter, or mute/unmute, on the media stream, showing the system slider. */
    fun adjustVolume(direction: String): String {
        val adjust = when (direction.lowercase()) {
            "up", "raise", "louder" -> AudioManager.ADJUST_RAISE
            "down", "lower", "quieter" -> AudioManager.ADJUST_LOWER
            "mute" -> AudioManager.ADJUST_MUTE
            "unmute" -> AudioManager.ADJUST_UNMUTE
            "toggle_mute" -> AudioManager.ADJUST_TOGGLE_MUTE
            else -> return "Error: volume direction must be up, down, mute or unmute"
        }
        return try {
            audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, adjust, AudioManager.FLAG_SHOW_UI)
            val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val now = audio.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / max.coerceAtLeast(1)
            if (audio.isStreamMute(AudioManager.STREAM_MUSIC)) "Media muted" else "Volume $now%"
        } catch (e: Exception) {
            "Error changing volume: ${e.message}"
        }
    }

    /** Battery, connectivity, sound, screen and storage in one glance. */
    @SuppressLint("MissingPermission")
    fun status(): String = buildString {
        val battery = context.getSystemService(BatteryManager::class.java)
        val level = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        appendLine("Time: ${java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.FULL, java.text.DateFormat.SHORT).format(java.util.Date())}")
        appendLine("Battery: $level%" + if (battery.isCharging) " (charging)" else "")

        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = runCatching { cm.getNetworkCapabilities(cm.activeNetwork) }.getOrNull()
        val network = when {
            caps == null -> "offline"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile data"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "VPN"
            else -> "connected"
        }
        appendLine("Internet: $network")
        runCatching { context.getSystemService(WifiManager::class.java).isWifiEnabled }.getOrNull()
            ?.let { appendLine("Wi-Fi: ${if (it) "on" else "off"}") }
        runCatching { context.getSystemService(BluetoothManager::class.java).adapter?.isEnabled }.getOrNull()
            ?.let { appendLine("Bluetooth: ${if (it) "on" else "off"}") }
        val airplane = Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1
        appendLine("Airplane mode: ${if (airplane) "on" else "off"}")

        val ringer = when (audio.ringerMode) {
            AudioManager.RINGER_MODE_SILENT -> "silent"
            AudioManager.RINGER_MODE_VIBRATE -> "vibrate"
            else -> "ring"
        }
        val dnd = context.getSystemService(android.app.NotificationManager::class.java).currentInterruptionFilter
        appendLine("Sound: $ringer" + if (dnd > android.app.NotificationManager.INTERRUPTION_FILTER_ALL) ", Do Not Disturb on" else "")
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        appendLine("Media volume: ${audio.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / max}%")
        runCatching { Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS) }.getOrNull()
            ?.let { appendLine("Brightness: ${it * 100 / 255}%") }

        val stat = android.os.StatFs(android.os.Environment.getDataDirectory().path)
        append("Storage: %.1f GB free of %.1f GB".format(stat.availableBytes / 1e9, stat.totalBytes / 1e9))
    }

    /** Torch on the first camera with a flash; needs no permission. */
    fun setFlashlight(on: Boolean): String = try {
        val cameras = context.getSystemService(CameraManager::class.java)
        val id = cameras.cameraIdList.firstOrNull {
            cameras.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: return "Error: this phone has no flashlight"
        cameras.setTorchMode(id, on)
        if (on) "Flashlight on" else "Flashlight off"
    } catch (e: Exception) {
        "Error switching the flashlight: ${e.message}"
    }

    /** Play, pause, next or previous on whatever app is playing media. */
    fun media(command: String): String {
        val code = when (command) {
            "play" -> KeyEvent.KEYCODE_MEDIA_PLAY
            "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
            "toggle" -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "previous" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            else -> return "Error: unknown media command \"$command\""
        }
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        return when (command) {
            "play" -> "Playing"
            "pause" -> "Paused"
            "next" -> "Skipped to the next track"
            "previous" -> "Back to the previous track"
            else -> "Toggled playback"
        }
    }

    /** System brightness needs "Modify system settings"; it persists across apps unlike window brightness. */
    @SuppressLint("InlinedApi")
    fun setBrightness(level: Int): String {
        if (!canWriteSettings()) {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${context.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
            return "Allow \"Modify system settings\" for MeowClaw, then try again."
        }
        return try {
            val resolver = context.contentResolver
            Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
            Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, (level.coerceIn(0, 100) * 255 / 100.0).roundToInt())
            "Brightness set to $level%"
        } catch (e: Exception) {
            "Error setting brightness: ${e.message}"
        }
    }
}
