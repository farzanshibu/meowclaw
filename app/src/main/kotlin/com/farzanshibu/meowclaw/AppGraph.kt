package com.farzanshibu.meowclaw

import android.app.Application
import android.content.Context
import com.farzanshibu.meowclaw.agent.ActionHandler
import com.farzanshibu.meowclaw.agent.AgentBrain
import com.farzanshibu.meowclaw.agent.AgentController
import com.farzanshibu.meowclaw.agent.ScreenReader
import com.farzanshibu.meowclaw.agent.StepExecutor
import com.farzanshibu.meowclaw.agent.TaskExecutor
import com.farzanshibu.meowclaw.data.ChatHistoryStore
import com.farzanshibu.meowclaw.data.SettingsStore
import com.farzanshibu.meowclaw.data.SkillMemory
import com.farzanshibu.meowclaw.data.TaskHistoryStore
import com.farzanshibu.meowclaw.device.AlarmControl
import com.farzanshibu.meowclaw.device.AppLauncher
import com.farzanshibu.meowclaw.device.Communication
import com.farzanshibu.meowclaw.device.HandsFreeVoice
import com.farzanshibu.meowclaw.device.ContactsRepository
import com.farzanshibu.meowclaw.device.Notifier
import com.farzanshibu.meowclaw.device.ShizukuShell
import com.farzanshibu.meowclaw.device.Speaker
import com.farzanshibu.meowclaw.device.SystemControl
import com.farzanshibu.meowclaw.device.Toaster
import com.farzanshibu.meowclaw.input.InputController
import com.farzanshibu.meowclaw.llm.LanguageModel
import com.farzanshibu.meowclaw.llm.LlmClient
import com.farzanshibu.meowclaw.llm.cactus.LocalLlm
import com.farzanshibu.meowclaw.llm.cactus.LocalModel
import com.farzanshibu.meowclaw.llm.cactus.LocalModelManager
import com.farzanshibu.meowclaw.llm.needle.IntentRouter
import com.farzanshibu.meowclaw.llm.needle.NeedleEngine
import com.farzanshibu.meowclaw.service.AgentService
import com.farzanshibu.meowclaw.service.LiveStatus
import com.farzanshibu.meowclaw.service.NotificationTools
import com.farzanshibu.meowclaw.update.AppUpdater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Process-wide singletons. Constructed once in [MeowClawApp]. */
class AppGraph(val context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val settings = SettingsStore(context)
    val chatHistory = ChatHistoryStore(context)
    val taskHistory = TaskHistoryStore(context)
    val skills = SkillMemory(context)

    val apps = AppLauncher(context)
    val contacts = ContactsRepository(context)
    val communication = Communication(context, contacts)
    val alarms = AlarmControl(context)
    val system = SystemControl(context)
    val shell = ShizukuShell()
    val notifier = Notifier(context)
    val liveStatus = LiveStatus()
    val notifications = NotificationTools(context)
    val toaster = Toaster(context)
    val speaker by lazy { Speaker(context).also { it.onSpeaking = handsFree::pause } }

    val input = InputController(context, shell)
    val screen = ScreenReader()

    val llm = LlmClient { settings.current }
    val localModels = LocalModelManager(context)
    val localLlm = LocalLlm(context, localModels)
    val language = LanguageModel({ settings.current }, llm, localLlm, localModels)
    val needle = NeedleEngine(context)
    val router = IntentRouter(needle)

    val steps = StepExecutor(this)
    val taskExecutor = TaskExecutor(this)
    val actions = ActionHandler(this)
    val brain = AgentBrain(this)
    val controller = AgentController(this)
    /** Continuous voice: new commands when idle, steering while a task runs. */
    val handsFree = HandsFreeVoice(context) { controller.send(it) }
    val updater = AppUpdater(context)

    /**
     * Deletes one on-device model, or all of them when [model] is null. The
     * runtime is unloaded first so no weights stay mapped from deleted files.
     */
    suspend fun deleteLocalModels(model: LocalModel? = null) {
        if (model == null || localLlm.loadedModel == model) localLlm.unload()
        if (model == null) localModels.deleteAll() else localModels.delete(model)
        if (model == null || settings.current.localModelId == model.id) settings.update { it.copy(localModelId = "") }
    }

    /** Background wiring; called once the graph is reachable through [graph]. */
    fun start() {
        // Keep the foreground service in step with Telegram and running tasks.
        scope.launch {
            settings.settings.distinctUntilChangedBy { listOf(it.telegramEnabled, it.telegramToken, it.onboardingCompleted, it.agentEnabled) }
                .collect { AgentService.sync(context) }
        }
        // Master power off: stop listening and anything in flight.
        scope.launch {
            settings.settings.map { it.agentEnabled }.distinctUntilChanged().collect { on ->
                if (!on) {
                    handsFree.stop()
                    controller.cancel()
                }
            }
        }
        // Background listening needs the foreground service's microphone type.
        scope.launch { handsFree.active.collect { AgentService.sync(context) } }
    }
}

class MeowClawApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
        graph.start()
    }

    /** On-device models hold hundreds of MB to GBs; give them back when Android asks. */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        @Suppress("DEPRECATION")
        if (level >= TRIM_MEMORY_BACKGROUND && !graph.controller.isBusy) {
            graph.scope.launch { graph.localLlm.unload() }
        }
    }
}

val Context.graph: AppGraph get() = (applicationContext as MeowClawApp).graph
