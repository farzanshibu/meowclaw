package com.farzanshibu.meowclaw.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.Icon
import android.os.Build
import androidx.core.app.NotificationCompat
import com.farzanshibu.meowclaw.R
import com.farzanshibu.meowclaw.device.Notifier
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** What the agent is doing right now. */
data class LiveTask(
    val goal: String,
    val phase: Phase,
    /** Short action name for the status-bar chip while [phase] is ACTING ("Tap", "Type"). */
    val action: String = "",
    val detail: String = "",
    val step: Int = 0,
    val maxSteps: Int = 0,
) {
    enum class Phase { STARTING, LOADING, THINKING, ACTING, WAITING, RECOVERING }

    val chip: String
        get() = when (phase) {
            Phase.STARTING -> "Starting"
            Phase.LOADING -> "Loading"
            Phase.THINKING -> if (step > 0) "Step $step" else "Thinking"
            Phase.ACTING -> action.ifBlank { "Working" }
            Phase.WAITING -> "Waiting"
            Phase.RECOVERING -> "Retrying"
        }

    val headline: String
        get() = when (phase) {
            Phase.STARTING -> "Starting…"
            Phase.LOADING -> "Loading the on-device model…"
            Phase.THINKING -> "Thinking…"
            Phase.ACTING -> "${action.ifBlank { "Working" }}…"
            Phase.WAITING -> "Waiting for the screen…"
            Phase.RECOVERING -> "Trying another way…"
        }
}

/**
 * Live progress of the running request, rendered by [AgentService] as an
 * Android Live Update: a status-bar chip, a lock-screen card and the top of
 * the notification shade (Samsung's Now Bar shows it too).
 */
class LiveStatus {
    private val flow = MutableStateFlow<LiveTask?>(null)
    val current: StateFlow<LiveTask?> = flow.asStateFlow()

    fun start(goal: String) {
        flow.value = LiveTask(goal, LiveTask.Phase.THINKING)
    }

    fun update(transform: (LiveTask) -> LiveTask) = flow.update { it?.let(transform) }

    fun clear() {
        flow.value = null
    }

    companion object {
        private val ACCENT = Color.parseColor("#9B6BFF")

        /** Ongoing notification for [task]; promoted to a Live Update on Android 16+. */
        fun notification(
            context: Context,
            task: LiveTask,
            loadingModel: Boolean,
            listening: Boolean,
            contentIntent: PendingIntent,
            actions: List<NotificationCompat.Action>,
        ): Notification {
            val shown = if (loadingModel && task.phase == LiveTask.Phase.THINKING) task.copy(phase = LiveTask.Phase.LOADING) else task
            val title = shown.headline
            val text = buildString {
                append(shown.goal)
                if (shown.detail.isNotBlank()) append(" · ").append(shown.detail)
                if (listening) append(" · 🎙 listening")
            }
            val indeterminate = shown.maxSteps <= 0 || shown.phase == LiveTask.Phase.LOADING
            return if (Build.VERSION.SDK_INT >= 36) {
                live(context, shown, title, text, indeterminate, contentIntent, actions)
            } else {
                NotificationCompat.Builder(context, Notifier.CHANNEL_SERVICE)
                    .setSmallIcon(R.drawable.ic_stat_agent)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setSubText(if (shown.maxSteps > 0) "Step ${shown.step}/${shown.maxSteps}" else null)
                    .setProgress(shown.maxSteps.coerceAtLeast(1), shown.step, indeterminate)
                    .setOngoing(true)
                    .setOnlyAlertOnce(true)
                    .setColor(ACCENT)
                    .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                    .setContentIntent(contentIntent)
                    .apply { actions.forEach(::addAction) }
                    .build()
            }
        }

        @androidx.annotation.RequiresApi(36)
        private fun live(
            context: Context,
            task: LiveTask,
            title: String,
            text: String,
            indeterminate: Boolean,
            contentIntent: PendingIntent,
            actions: List<NotificationCompat.Action>,
        ): Notification {
            val style = Notification.ProgressStyle()
                .setStyledByProgress(false)
                .setProgressTrackerIcon(Icon.createWithResource(context, R.drawable.ic_stat_agent))
            if (indeterminate) {
                style.setProgressIndeterminate(true)
            } else {
                // One segment per step, so finished steps read as filled pips.
                style.setProgressSegments(List(task.maxSteps) { Notification.ProgressStyle.Segment(1).setColor(ACCENT) })
                style.setProgress(task.step.coerceIn(0, task.maxSteps))
            }
            return Notification.Builder(context, Notifier.CHANNEL_SERVICE)
                .setSmallIcon(R.drawable.ic_stat_agent)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(style)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setColor(ACCENT)
                .setCategory(Notification.CATEGORY_PROGRESS)
                // The status-bar chip: short and glanceable.
                .setShortCriticalText(task.chip)
                .setContentIntent(contentIntent)
                .apply {
                    // Ask the system to promote this to a Live Update.
                    extras.putBoolean(EXTRA_REQUEST_PROMOTED_ONGOING, true)
                    actions.forEach { a ->
                        addAction(Notification.Action.Builder(a.iconCompat?.toIcon(context), a.title, a.actionIntent).build())
                    }
                }
                .build()
        }

        private const val EXTRA_REQUEST_PROMOTED_ONGOING = "android.requestPromotedOngoing"
    }
}
