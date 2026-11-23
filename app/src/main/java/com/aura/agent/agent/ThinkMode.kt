package com.aura.agent.agent

/**
 * How hard and how long the agent works on a task. Each mode sets the provider's reasoning level (when the provider
 * supports one), how many steps and fix attempts it may use, and how it is told to approach the work.
 */
enum class ThinkMode(
    val id: String,
    val label: String,
    val blurb: String,
    /** value for the provider's reasoning_effort, or null to leave the provider default */
    val effort: String?,
    val steps: Int,
    /** fix attempts for builds and checks before the agent must stop and report */
    val verify: Int,
    /** failed steps in a row before the run is stopped */
    val streak: Int,
    /** run the auto-check (build/typecheck) after code changes */
    val autoVerify: Boolean,
    val prompt: String,
) {
    SWIFT(
        "swift", "Swift", "Fast and light. Short answers, few steps, no extra checks.", "low", 25, 2, 4, false,
        "Mode: Swift. Be quick and direct. Answer briefly, use as few tool calls as possible and skip optional checks.",
    ),
    STEADY(
        "steady", "Steady", "Balanced and reliable. The default.", null, 60, 6, 8, true, "",
    ),
    DEEP(
        "deep", "Deep", "Thinks harder, double-checks, tries more fixes.", "high", 90, 9, 10, true,
        "Mode: Deep. Think carefully before you act. Consider edge cases and double-check your result before you answer.",
    ),
    SAGE(
        "sage", "Sage", "Plans first, verifies every step, reviews its own work.", "high", 130, 12, 12, true,
        "Mode: Sage. Start with a short plan, work through it step by step, verify each result and review your own work before you finish. Use delegate for research when it keeps your context small.",
    ),
    TITAN(
        "titan", "Titan", "Maximum rigor and persistence. Keeps going until it is done.", "high", 200, 20, 15, true,
        "Mode: Titan. Maximum rigor and persistence. Plan first, split the work into steps, use delegate for investigation, verify everything, and when something fails try a different approach each time until the task is truly done or you are certain it is blocked.",
    );

    companion object {
        fun fromId(id: String?) = entries.firstOrNull { it.id == id } ?: STEADY
    }
}
