package com.farzanshibu.meowclaw.agent

import com.farzanshibu.meowclaw.UiNode

/**
 * Deterministic execution of simple goal clauses — "open settings then open
 * display", "open chrome and search for weather in london". Each later clause
 * is a tap on a named element or a search; when the screen clearly offers what
 * the clause needs, the agent acts without a model call. Anything it cannot
 * resolve falls back to the LLM for that step, so this only ever speeds tasks
 * up and makes small on-device models far more reliable.
 */
class GoalFastPath(goal: String, skipFirstOpen: Boolean) {

    sealed interface Step {
        data class Tap(val target: String) : Step
        data class Search(val query: String) : Step
    }

    sealed interface Move {
        data class Tap(val node: UiNode, val label: String) : Move
        data class Type(val text: String) : Move
        data object Submit : Move
    }

    private val steps: List<Step> = parseSteps(goal).let { all ->
        if (skipFirstOpen && all.firstOrNull() is Step.Tap && OPEN_VERB.containsMatchIn(clauses(goal).first())) all.drop(1) else all
    }
    private var index = 0
    private var searchPhase = 0 // 0: find field, 1: typed, needs submit

    val hasSteps: Boolean get() = steps.isNotEmpty()
    val isComplete: Boolean get() = steps.isNotEmpty() && index >= steps.size

    /** The next move if the current screen makes it unambiguous, else null. */
    fun next(nodes: List<UiNode>): Move? = when (val step = steps.getOrNull(index)) {
        is Step.Tap -> bestMatch(step.target, nodes)?.let { Move.Tap(it, it.text.ifEmpty { it.contentDescription }) }
        is Step.Search -> when {
            searchPhase == 1 -> Move.Submit
            nodes.any { it.isEditable } -> Move.Type(step.query)
            else -> searchButton(nodes)?.let { Move.Tap(it, it.text.ifEmpty { it.contentDescription }) }
        }
        null -> null
    }

    /** Records that [move] succeeded. */
    fun advance(move: Move) {
        when (move) {
            is Move.Tap -> if (steps.getOrNull(index) is Step.Tap) index++ // a search-button tap keeps the search step open
            is Move.Type -> searchPhase = 1
            Move.Submit -> {
                searchPhase = 0
                index++
            }
        }
    }

    companion object {
        private val SPLIT = Regex("\\b(?:and then|then|after that|and)\\b|[,;]", RegexOption.IGNORE_CASE)
        private val OPEN_VERB = Regex("^\\s*(?:please\\s+)?(?:open|launch|start)\\b", RegexOption.IGNORE_CASE)
        private val TAP_CLAUSE = Regex(
            "^\\s*(?:please\\s+)?(?:open|tap|click|press|select|choose|go to|goto|go into|enter|launch)\\s+" +
                "(?:on\\s+|into\\s+)?(?:the\\s+|my\\s+)?(.+?)" +
                "(?:\\s+(?:option|button|tab|menu|page|screen|section|settings?))?\\s*[.!]?$",
            RegexOption.IGNORE_CASE,
        )
        private val SEARCH_CLAUSE = Regex(
            "^\\s*(?:please\\s+)?(?:search|look up|google)\\s+(?:for\\s+)?(.+?)\\s*[.!]?$",
            RegexOption.IGNORE_CASE,
        )

        fun clauses(goal: String): List<String> = goal.split(SPLIT).map { it.trim() }.filter { it.isNotEmpty() }

        /** The goal as fast-path steps; empty when any clause is something else. */
        fun parseSteps(goal: String): List<Step> {
            val parts = clauses(goal)
            val firstApp = parts.firstOrNull()?.let { TAP_CLAUSE.find(it)?.groupValues?.get(1)?.lowercase() }
            val result = mutableListOf<Step>()
            for (clause in parts) {
                val search = SEARCH_CLAUSE.find(clause)
                if (search != null) {
                    var query = search.groupValues[1].trim()
                    // "search cats on youtube" when YouTube was just opened.
                    if (firstApp != null) query = query.replace(Regex("\\s+(?:on|in)\\s+${Regex.escape(firstApp)}$", RegexOption.IGNORE_CASE), "")
                    result += Step.Search(query)
                    continue
                }
                val tap = TAP_CLAUSE.find(clause) ?: return emptyList()
                result += Step.Tap(tap.groupValues[1].trim().lowercase())
            }
            return result
        }

        /** Back-compat helper for tap-only goals. */
        fun parseTargets(goal: String): List<String> =
            parseSteps(goal).let { steps -> if (steps.all { it is Step.Tap }) steps.map { (it as Step.Tap).target } else emptyList() }

        private fun norm(s: String) = s.lowercase().replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()

        fun bestMatch(target: String, nodes: List<UiNode>): UiNode? {
            val t = norm(target)
            if (t.isEmpty()) return null
            val words = t.split(' ')
            var best: UiNode? = null
            var bestScore = 0
            for (node in nodes) {
                if (node.isEditable) continue
                val label = norm(node.text.ifEmpty { node.contentDescription })
                if (label.isEmpty() || label.length > 60) continue
                val score = when {
                    label == t -> 4
                    label.startsWith("$t ") -> 3
                    words.all { w -> label.split(' ').contains(w) } -> 2
                    else -> 0
                }
                val tieBreak = if (node.isClickable) 1 else 0
                if (score > 0 && score * 2 + tieBreak > bestScore) {
                    bestScore = score * 2 + tieBreak
                    best = node
                }
            }
            return best
        }

        /** A visible control that opens search ("Search", "Search YouTube", a magnifier icon). */
        fun searchButton(nodes: List<UiNode>): UiNode? = nodes
            .filter { it.isClickable || it.className.contains("Button") || it.className.contains("Image") }
            .firstOrNull { node ->
                val label = norm(node.text.ifEmpty { node.contentDescription })
                label == "search" || label.startsWith("search ") || label.endsWith(" search") || label.contains("search or type")
            }
    }
}
