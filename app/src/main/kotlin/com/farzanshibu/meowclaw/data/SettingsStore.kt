package com.farzanshibu.meowclaw.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.URI

enum class ThemeMode { SYSTEM, LIGHT, DARK }

enum class LlmProvider { API, ON_DEVICE }

data class AppSettings(
    val apiKey: String = "",
    val baseUrl: String = DEFAULT_BASE_URL,
    val model: String = DEFAULT_MODEL,
    val maxSteps: Int = 15,
    val disableMaxSteps: Boolean = false,
    val temperature: Double = 1.0,
    val maxTokens: Int = 1024,
    val useScreenCompression: Boolean = true,
    val useSystemPrompt: Boolean = true,
    val telegramToken: String = "",
    val telegramEnabled: Boolean = false,
    val telegramOwnerChatId: String = "",
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val onboardingCompleted: Boolean = false,
    val needleEnabled: Boolean = true,
    val needleMinConfidence: Double = 0.55,
    val llmProvider: LlmProvider = LlmProvider.API,
    val localModelId: String = "",
    /** Attach a screenshot to each task step when the model can see images. */
    val sendScreenshots: Boolean = true,
    /** The API model accepts image input (OpenAI-style image_url parts). */
    val apiVision: Boolean = false,
) {
    val effectiveMaxSteps: Int get() = if (disableMaxSteps) 999 else maxSteps

    /** Local servers (llama.cpp, Ollama, LM Studio) usually run without a key. */
    val isApiConfigured: Boolean
        get() = baseUrl.isNotBlank() && model.isNotBlank() &&
            (apiKey.isNotBlank() || isLocalEndpoint(baseUrl))

    companion object {
        const val DEFAULT_BASE_URL = "https://api.deepseek.com"
        const val DEFAULT_MODEL = "deepseek-chat"

        fun isLocalEndpoint(url: String): Boolean {
            val host = runCatching { URI(url.trim()).host }.getOrNull()?.lowercase() ?: return false
            return host == "localhost" || host == "127.0.0.1" || host.startsWith("10.") ||
                host.startsWith("192.168.") || host.endsWith(".local") ||
                Regex("^172\\.(1[6-9]|2\\d|3[01])\\.").containsMatchIn(host)
        }
    }
}

class SettingsStore(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("meowclaw", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(read())

    val settings: StateFlow<AppSettings> = state.asStateFlow()
    val current: AppSettings get() = state.value

    fun update(transform: (AppSettings) -> AppSettings) {
        val next = transform(state.value)
        write(next)
        state.value = next
    }

    private fun read() = AppSettings(
        apiKey = prefs.getString(K_API_KEY, "") ?: "",
        baseUrl = prefs.getString(K_BASE_URL, null) ?: AppSettings.DEFAULT_BASE_URL,
        model = prefs.getString(K_MODEL, null) ?: AppSettings.DEFAULT_MODEL,
        maxSteps = prefs.getInt(K_MAX_STEPS, 15),
        disableMaxSteps = prefs.getBoolean(K_DISABLE_MAX_STEPS, false),
        temperature = prefs.getFloat(K_TEMPERATURE, 1f).toDouble(),
        maxTokens = prefs.getInt(K_MAX_TOKENS, 1024),
        useScreenCompression = prefs.getBoolean(K_SCREEN_COMPRESSION, true),
        useSystemPrompt = prefs.getBoolean(K_SYSTEM_PROMPT, true),
        telegramToken = prefs.getString(K_TELEGRAM_TOKEN, "") ?: "",
        telegramEnabled = prefs.getBoolean(K_TELEGRAM_ENABLED, false),
        telegramOwnerChatId = prefs.getString(K_TELEGRAM_OWNER, "") ?: "",
        theme = runCatching { ThemeMode.valueOf(prefs.getString(K_THEME, "SYSTEM")!!) }
            .getOrDefault(ThemeMode.SYSTEM),
        onboardingCompleted = prefs.getBoolean(K_ONBOARDING, false),
        needleEnabled = prefs.getBoolean(K_NEEDLE_ENABLED, true),
        needleMinConfidence = prefs.getFloat(K_NEEDLE_CONFIDENCE, 0.55f).toDouble(),
        llmProvider = runCatching { LlmProvider.valueOf(prefs.getString(K_LLM_PROVIDER, "API")!!) }
            .getOrDefault(LlmProvider.API),
        localModelId = prefs.getString(K_LOCAL_MODEL, "") ?: "",
        sendScreenshots = prefs.getBoolean(K_SCREENSHOTS, true),
        apiVision = prefs.getBoolean(K_API_VISION, false),
    )

    private fun write(s: AppSettings) = prefs.edit {
        putString(K_API_KEY, s.apiKey)
        putString(K_BASE_URL, s.baseUrl)
        putString(K_MODEL, s.model)
        putInt(K_MAX_STEPS, s.maxSteps)
        putBoolean(K_DISABLE_MAX_STEPS, s.disableMaxSteps)
        putFloat(K_TEMPERATURE, s.temperature.toFloat())
        putInt(K_MAX_TOKENS, s.maxTokens)
        putBoolean(K_SCREEN_COMPRESSION, s.useScreenCompression)
        putBoolean(K_SYSTEM_PROMPT, s.useSystemPrompt)
        putString(K_TELEGRAM_TOKEN, s.telegramToken)
        putBoolean(K_TELEGRAM_ENABLED, s.telegramEnabled)
        putString(K_TELEGRAM_OWNER, s.telegramOwnerChatId)
        putString(K_THEME, s.theme.name)
        putBoolean(K_ONBOARDING, s.onboardingCompleted)
        putBoolean(K_NEEDLE_ENABLED, s.needleEnabled)
        putFloat(K_NEEDLE_CONFIDENCE, s.needleMinConfidence.toFloat())
        putString(K_LLM_PROVIDER, s.llmProvider.name)
        putString(K_LOCAL_MODEL, s.localModelId)
        putBoolean(K_SCREENSHOTS, s.sendScreenshots)
        putBoolean(K_API_VISION, s.apiVision)
    }

    companion object {
        const val K_API_KEY = "api_key"
        const val K_BASE_URL = "api_base_url"
        const val K_MODEL = "api_model"
        const val K_MAX_STEPS = "api_max_steps"
        const val K_DISABLE_MAX_STEPS = "api_disable_max_steps"
        const val K_TEMPERATURE = "api_temperature"
        const val K_MAX_TOKENS = "api_max_tokens"
        const val K_SCREEN_COMPRESSION = "api_use_screen_compression"
        const val K_SYSTEM_PROMPT = "api_use_system_prompt"
        const val K_TELEGRAM_TOKEN = "telegram_bot_token"
        const val K_TELEGRAM_ENABLED = "telegram_enabled"
        const val K_TELEGRAM_OWNER = "telegram_owner_chat_id"
        const val K_THEME = "theme_mode"
        const val K_ONBOARDING = "onboarding_completed"
        const val K_NEEDLE_ENABLED = "needle_enabled"
        const val K_NEEDLE_CONFIDENCE = "needle_min_confidence"
        const val K_LLM_PROVIDER = "llm_provider"
        const val K_LOCAL_MODEL = "local_model_id"
        const val K_SCREENSHOTS = "send_screenshots"
        const val K_API_VISION = "api_vision"
    }
}
