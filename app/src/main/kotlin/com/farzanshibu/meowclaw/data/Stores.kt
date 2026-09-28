package com.farzanshibu.meowclaw.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

private const val TAG = "MeowClawStores"

/** Chat sessions, newest first, kept in one JSON file (same format as v1). */
class ChatHistoryStore(context: Context) {
    private val file = File(context.filesDir, "chat_history_sessions.json")
    private val mutex = Mutex()
    private val sessionsState = MutableStateFlow<List<ChatSession>>(emptyList())
    val sessions: StateFlow<List<ChatSession>> = sessionsState.asStateFlow()

    suspend fun load(): List<ChatSession> = mutex.withLock { readLocked() }.also { sessionsState.value = it }

    suspend fun save(session: ChatSession) = mutex.withLock {
        val sessions = readLocked().toMutableList()
        val index = sessions.indexOfFirst { it.id == session.id }
        if (index >= 0) sessions[index] = session else sessions.add(0, session)
        writeLocked(sessions)
    }

    suspend fun delete(id: String) = mutex.withLock {
        writeLocked(readLocked().filterNot { it.id == id })
    }

    suspend fun clear() = mutex.withLock { writeLocked(emptyList()) }

    private suspend fun readLocked(): List<ChatSession> = withContext(Dispatchers.IO) {
        if (!file.isFile) return@withContext emptyList()
        runCatching { AppJson.decodeFromString<List<ChatSession>>(file.readText()) }
            .onFailure { Log.w(TAG, "Unreadable chat history", it) }
            .getOrDefault(emptyList())
    }

    private suspend fun writeLocked(sessions: List<ChatSession>) {
        withContext(Dispatchers.IO) {
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(AppJson.encodeToString(sessions))
            tmp.renameTo(file)
        }
        sessionsState.value = sessions
    }
}

/** Append-only JSONL log of executed tasks (same format as v1). */
class TaskHistoryStore(context: Context) {
    private val file = File(context.filesDir, "task_history.jsonl")
    private val mutex = Mutex()

    suspend fun log(goal: String, status: String, totalTokens: Int, steps: Int, trace: List<String>) =
        mutex.withLock {
            withContext(Dispatchers.IO) {
                val record = TaskRecord(goal.trim(), status, totalTokens, steps, trace)
                file.appendText(AppJson.encodeToString(record) + "\n")
            }
        }

    /** Newest first. */
    suspend fun read(): List<TaskRecord> = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (!file.isFile) return@withContext emptyList()
            file.readLines().filter { it.isNotBlank() }
                .mapNotNull { runCatching { AppJson.decodeFromString<TaskRecord>(it) }.getOrNull() }
                .reversed()
        }
    }

    suspend fun clear() = mutex.withLock { withContext(Dispatchers.IO) { file.delete() } }
}

/** Remembers step sequences of successful tasks and replays them without the LLM. */
class SkillMemory(context: Context) {
    private val file = File(context.filesDir, "skills_memory.jsonl")
    private val mutex = Mutex()
    private var skills: MutableList<SavedSkill>? = null

    suspend fun find(goal: String): SavedSkill? = mutex.withLock {
        val query = keywords(goal)
        loaded().map { it to jaccard(query, it.taskKeywords) }
            .filter { it.second > 0.6 }
            .maxByOrNull { it.second }?.first
    }

    suspend fun save(goal: String, steps: List<ActionStep>) = mutex.withLock {
        val list = loaded()
        val query = keywords(goal)
        val index = list.indexOfFirst { jaccard(query, it.taskKeywords) > 0.8 }
        if (index >= 0) {
            val skill = list[index]
            list[index] = skill.copy(
                successCount = skill.successCount + 1,
                lastUsed = nowIso(),
                steps = if (steps.size < skill.steps.size) steps else skill.steps,
            )
        } else {
            list += SavedSkill(
                id = System.currentTimeMillis().toString(),
                task = goal,
                taskKeywords = query,
                successCount = 1,
                steps = steps,
            )
        }
        persist(list)
    }

    suspend fun recordFailure(id: String) = mutex.withLock {
        val list = loaded()
        val index = list.indexOfFirst { it.id == id }
        if (index >= 0) {
            list[index] = list[index].copy(failCount = list[index].failCount + 1)
            persist(list)
        }
    }

    private suspend fun loaded(): MutableList<SavedSkill> {
        skills?.let { return it }
        val read = withContext(Dispatchers.IO) {
            if (!file.isFile) mutableListOf()
            else file.readLines().filter { it.isNotBlank() }
                .mapNotNull { runCatching { AppJson.decodeFromString<SavedSkill>(it) }.getOrNull() }
                .toMutableList()
        }
        skills = read
        return read
    }

    private suspend fun persist(list: List<SavedSkill>) = withContext(Dispatchers.IO) {
        file.writeText(list.joinToString("") { AppJson.encodeToString(it) + "\n" })
    }

    companion object {
        private val STOP_WORDS = setOf(
            "to", "and", "the", "a", "in", "of", "for", "on", "with", "at", "by", "from", "go", "turn", "open",
        )

        fun keywords(text: String): List<String> = text.lowercase()
            .replace(Regex("[^a-z0-9\\s]"), "")
            .split(Regex("\\s+"))
            .filter { it.isNotEmpty() && it !in STOP_WORDS }

        fun jaccard(a: List<String>, b: List<String>): Double {
            if (a.isEmpty() || b.isEmpty()) return 0.0
            val setA = a.toSet()
            val setB = b.toSet()
            return setA.intersect(setB).size.toDouble() / setA.union(setB).size
        }
    }
}
