package com.android.insta.navigation

import androidx.navigation3.runtime.NavKey

/** All back-stack mutations for the signed-in app go through here. */
class Navigator(private val state: NavigationState) {

    /** Switches tabs for a top-level route, otherwise pushes onto the current tab's stack. */
    fun navigate(route: NavKey) {
        if (route in state.backStacks.keys) {
            state.topLevelRoute = route
        } else {
            currentStack().add(route)
        }
    }

    /**
     * Pops within the current tab. At a tab's root, goes back to the start tab. Returns false at the
     * start tab's root, where the system should handle back (leave the app).
     */
    fun goBack(): Boolean {
        val stack = currentStack()
        return when {
            stack.size > 1 -> {
                stack.removeAt(stack.lastIndex)
                true
            }
            state.topLevelRoute != state.startRoute -> {
                state.topLevelRoute = state.startRoute
                true
            }
            else -> false
        }
    }

    private fun currentStack() =
        state.backStacks[state.topLevelRoute] ?: error("No back stack for ${state.topLevelRoute}")
}
