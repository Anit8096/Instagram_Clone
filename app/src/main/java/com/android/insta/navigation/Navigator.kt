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
     * Opens a deep-link target with a sensible back stack: a conversation goes on the Home tab above the Inbox (the
     * way it's normally reached); other screens are pushed onto the current tab. Re-opening the screen that is already
     * on top does nothing, so tapping a second notification for the same thread doesn't stack duplicates.
     */
    fun openDeepLink(route: NavKey) {
        when (route) {
            in state.backStacks.keys -> navigate(route)
            is DetailRoute.Thread -> {
                state.topLevelRoute = state.startRoute
                val stack = currentStack()
                if (stack.lastOrNull() == route) return
                // Another conversation replaces the open one, so back still leads to the inbox.
                if (stack.lastOrNull() is DetailRoute.Thread) stack.removeAt(stack.lastIndex)
                if (stack.lastOrNull() != DetailRoute.Inbox) stack.add(DetailRoute.Inbox)
                stack.add(route)
            }
            else -> if (currentStack().lastOrNull() != route) currentStack().add(route)
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
