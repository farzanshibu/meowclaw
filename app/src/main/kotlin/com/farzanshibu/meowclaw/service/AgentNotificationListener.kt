package com.farzanshibu.meowclaw.service

import android.app.Notification
import android.app.RemoteInput
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.text.DateFormat
import java.util.Date

/**
 * Notification access: lets the agent read notifications, press their
 * actions (including inline replies), dismiss them, and see and control
 * media sessions. The user grants it in Settings → Notification access.
 */
class AgentNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        instance = this
    }

    override fun onListenerDisconnected() {
        instance = null
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    /** Visible notifications from other apps, newest first. */
    fun list(): List<StatusBarNotification> = runCatching { activeNotifications.orEmpty().toList() }.getOrDefault(emptyList())
        .filter { it.packageName != packageName && !it.notification.isGroupSummary() && it.text().isNotBlank() }
        .sortedByDescending { it.postTime }

    companion object {
        @Volatile var instance: AgentNotificationListener? = null
            private set

        fun component(context: Context) = ComponentName(context, AgentNotificationListener::class.java)

        fun hasAccess(context: Context): Boolean =
            Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
                ?.split(':')?.any { ComponentName.unflattenFromString(it) == component(context) } == true

        /** Opens the system page where the user grants notification access. */
        fun requestAccess(context: Context) {
            val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                    .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, component(context).flattenToString())
            } else {
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            }
            runCatching { context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                .onFailure { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }

        private fun Notification.isGroupSummary() = flags and Notification.FLAG_GROUP_SUMMARY != 0

        private fun StatusBarNotification.title(): String =
            notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()

        private fun StatusBarNotification.text(): String {
            val e = notification.extras
            return (e.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: e.getCharSequence(Notification.EXTRA_TEXT))
                ?.toString().orEmpty()
        }
    }
}

/** Notification and media-session tools for the agent; every call explains itself when access is missing. */
class NotificationTools(private val context: Context) {
    private val pm = context.packageManager
    private val listener get() = AgentNotificationListener.instance

    private fun appName(pkg: String) = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)

    private fun missingAccess(): String {
        AgentNotificationListener.requestAccess(context)
        return "Allow notification access for MeowClaw (the settings page is open), then ask again."
    }

    private fun current(): List<StatusBarNotification>? = listener?.list()

    /** Numbered list the model can refer back to by index. */
    fun read(app: String? = null, limit: Int = 15): String {
        val items = current() ?: return missingAccess()
        val filtered = if (app.isNullOrBlank()) items else items.filter { appName(it.packageName).contains(app, ignoreCase = true) }
        if (filtered.isEmpty()) return if (app.isNullOrBlank()) "No notifications." else "No notifications from $app."
        val time = DateFormat.getTimeInstance(DateFormat.SHORT)
        return buildString {
            appendLine("Notifications (newest first):")
            filtered.take(limit).forEach { sbn ->
                val e = sbn.notification.extras
                val title = e.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
                val text = (e.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: e.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
                val actions = sbn.notification.actions.orEmpty().mapNotNull { a ->
                    a.title?.toString()?.let { if (a.remoteInputs?.isNotEmpty() == true) "$it (reply)" else it }
                }
                append("[${items.indexOf(sbn)}] ${appName(sbn.packageName)} · ${time.format(Date(sbn.postTime))}")
                if (title.isNotBlank()) append(" · $title")
                appendLine()
                if (text.isNotBlank()) appendLine("    ${text.take(300)}")
                if (actions.isNotEmpty()) appendLine("    actions: ${actions.joinToString(", ")}")
            }
        }.trimEnd()
    }

    /** Presses [action] (by title) on notification [index]; [reply] fills an inline reply field. */
    fun act(index: Int, action: String?, reply: String?): String {
        val items = current() ?: return missingAccess()
        val sbn = items.getOrNull(index) ?: return "Error: no notification [$index]. Read notifications again."
        val n = sbn.notification
        return try {
            if (action.isNullOrBlank() && reply.isNullOrBlank()) {
                val intent = n.contentIntent ?: return "Error: that notification cannot be opened."
                intent.send()
                return "Opened the ${appName(sbn.packageName)} notification"
            }
            val actions = n.actions.orEmpty()
            val chosen = actions.firstOrNull { it.title?.toString().equals(action, ignoreCase = true) }
                ?: actions.firstOrNull { action != null && it.title?.toString()?.contains(action, ignoreCase = true) == true }
                ?: (if (!reply.isNullOrBlank()) actions.firstOrNull { it.remoteInputs?.isNotEmpty() == true } else null)
                ?: return "Error: no \"$action\" action. Available: ${actions.mapNotNull { it.title }.joinToString(", ")}"
            val inputs = chosen.remoteInputs
            if (!inputs.isNullOrEmpty()) {
                if (reply.isNullOrBlank()) return "Error: \"${chosen.title}\" needs reply text."
                val fill = Intent()
                val results = Bundle().apply { inputs.forEach { putCharSequence(it.resultKey, reply) } }
                RemoteInput.addResultsToIntent(inputs, fill, results)
                chosen.actionIntent.send(context, 0, fill)
                "Replied \"$reply\" in ${appName(sbn.packageName)}"
            } else {
                chosen.actionIntent.send()
                "Pressed \"${chosen.title}\" on the ${appName(sbn.packageName)} notification"
            }
        } catch (e: Exception) {
            "Error: ${e.message}"
        }
    }

    fun dismiss(index: Int?): String {
        val l = listener ?: return missingAccess()
        if (index == null) {
            l.cancelAllNotifications()
            return "Dismissed all notifications"
        }
        val sbn = l.list().getOrNull(index) ?: return "Error: no notification [$index]. Read notifications again."
        l.cancelNotification(sbn.key)
        return "Dismissed the ${appName(sbn.packageName)} notification"
    }

    private fun sessions(): List<MediaController>? {
        if (listener == null && !AgentNotificationListener.hasAccess(context)) return null
        return runCatching {
            context.getSystemService(MediaSessionManager::class.java)
                .getActiveSessions(AgentNotificationListener.component(context))
        }.getOrNull()
    }

    fun mediaSessions(): String {
        val list = sessions() ?: return missingAccess()
        if (list.isEmpty()) return "Nothing is playing."
        return list.joinToString("\n") { c ->
            val m = c.metadata
            val title = m?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty()
            val artist = m?.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty()
            val state = when (c.playbackState?.state) {
                PlaybackState.STATE_PLAYING -> "playing"
                PlaybackState.STATE_PAUSED -> "paused"
                PlaybackState.STATE_BUFFERING -> "buffering"
                PlaybackState.STATE_STOPPED -> "stopped"
                else -> "idle"
            }
            "${appName(c.packageName)}: $state" + (if (title.isNotBlank()) " · $title" else "") + (if (artist.isNotBlank()) " — $artist" else "")
        }
    }

    /** Controls the active session precisely; null when there is none (caller falls back to media keys). */
    fun control(command: String): String? {
        val c = sessions()?.firstOrNull() ?: return null
        val t = c.transportControls
        when (command) {
            "play" -> t.play()
            "pause" -> t.pause()
            "toggle" -> if (c.playbackState?.state == PlaybackState.STATE_PLAYING) t.pause() else t.play()
            "next" -> t.skipToNext()
            "previous" -> t.skipToPrevious()
            else -> return "Error: unknown media command \"$command\""
        }
        return "${appName(c.packageName)}: $command"
    }
}
