package com.farzanshibu.meowclaw.service

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.farzanshibu.meowclaw.R
import com.farzanshibu.meowclaw.graph
import com.farzanshibu.meowclaw.remote.TelegramBot
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch

/**
 * Keeps the process alive while the agent works in the background: during a
 * task started from the app, while hands-free voice is listening (which needs
 * the microphone service type) and while Telegram remote control is on.
 */
class AgentService : LifecycleService() {
    private var telegramJob: Job? = null
    private var telegramToken: String? = null
    private var foregroundType = -1

    override fun onCreate() {
        super.onCreate()
        val g = graph
        // Re-render as the task moves along (the Live Update's chip, progress and text).
        lifecycleScope.launch {
            combine(g.liveStatus.current, g.localLlm.loading, g.handsFree.active) { _, _, _ -> }
                .conflate()
                .collect {
                    if (foregroundType != -1) {
                        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification())
                    }
                    // The system rate-limits notification updates; stay well under it.
                    delay(300)
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val g = graph
        when (intent?.action) {
            ACTION_STOP_TASK -> g.controller.cancel()
            ACTION_STOP_LISTENING -> g.handsFree.stop()
        }
        val s = g.settings.current
        val telegramOn = s.telegramEnabled && s.telegramToken.isNotBlank()
        val busy = g.controller.isBusy
        val listening = g.handsFree.isActive
        if (!telegramOn && !busy && !listening) {
            stopSelf()
            return START_NOT_STICKY
        }
        enterForeground(notification(), listening && g.handsFree.hasPermission())

        if (telegramOn && (telegramJob?.isActive != true || telegramToken != s.telegramToken)) {
            telegramJob?.cancel()
            telegramToken = s.telegramToken
            telegramJob = lifecycleScope.launch { TelegramBot(g, s.telegramToken).run() }
        } else if (!telegramOn) {
            telegramJob?.cancel()
            telegramJob = null
        }
        return START_STICKY
    }

    /** A Live Update while a request runs; otherwise a quiet status line. */
    private fun notification(): android.app.Notification {
        val g = graph
        val busy = g.controller.isBusy
        val listening = g.handsFree.isActive
        val actions = buildList {
            if (busy) add(action(ACTION_STOP_TASK, "Stop"))
            if (listening) add(action(ACTION_STOP_LISTENING, "Mic off"))
        }
        val task = g.liveStatus.current.value
        if (busy && task != null) {
            return LiveStatus.notification(this, task, g.localLlm.loading.value, listening, g.notifier.openAppIntent(), actions)
        }
        val text = when {
            busy -> "Working on your task…"
            listening -> "Listening for voice commands"
            else -> "Listening for Telegram commands"
        }
        return g.notifier.serviceNotification(text, actions)
    }

    private fun enterForeground(notification: android.app.Notification, microphone: Boolean) {
        val special = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        val type = if (microphone && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) special or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else special
        if (type == foregroundType) {
            // Same type: only the text changed. Re-entering the foreground with the
            // microphone type from the background is refused on Android 14+.
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
            return
        }
        for (candidate in listOf(type, special).distinct()) {
            try {
                ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, candidate)
                foregroundType = candidate
                return
            } catch (e: Exception) {
                Log.w(TAG, "Could not enter the foreground with type $candidate", e)
            }
        }
    }

    private fun action(name: String, label: String) = NotificationCompat.Action(
        R.drawable.ic_stat_agent, label,
        PendingIntent.getService(
            this, name.hashCode(), Intent(this, AgentService::class.java).setAction(name),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        ),
    )

    companion object {
        private const val TAG = "AgentService"
        private const val NOTIFICATION_ID = 42
        private const val ACTION_STOP_TASK = "com.farzanshibu.meowclaw.STOP_TASK"
        private const val ACTION_STOP_LISTENING = "com.farzanshibu.meowclaw.STOP_LISTENING"

        /** Starts, refreshes or stops the service to match current needs. */
        fun sync(context: Context) {
            val g = context.graph
            val s = g.settings.current
            val needed = (s.telegramEnabled && s.telegramToken.isNotBlank()) || g.controller.isBusy || g.handsFree.isActive
            val intent = Intent(context, AgentService::class.java)
            try {
                if (needed) context.startForegroundService(intent) else context.stopService(intent)
            } catch (e: Exception) {
                // Android 12+ refuses foreground starts from the background; the
                // bound accessibility service still keeps running tasks alive.
                Log.w(TAG, "Service sync skipped: ${e.message}")
            }
        }
    }
}
