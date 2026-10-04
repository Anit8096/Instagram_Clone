package com.android.insta.navigation

import androidx.compose.runtime.mutableStateOf
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigatorTest {
    @Serializable private data object PostDetail : NavKey

    private val tabs = listOf(MainRoute.Feed, MainRoute.Explore, MainRoute.Profile)
    private val state = NavigationState(
        startRoute = MainRoute.Feed,
        topLevelRoute = mutableStateOf(MainRoute.Feed),
        backStacks = tabs.associateWith { NavBackStack<NavKey>(it) },
    )
    private val navigator = Navigator(state)

    @Test
    fun `selecting a tab switches stacks and keeps the start tab underneath`() {
        navigator.navigate(MainRoute.Explore)
        assertEquals(MainRoute.Explore, state.topLevelRoute)
        assertEquals(listOf(MainRoute.Feed, MainRoute.Explore), state.routesInUse())
    }

    @Test
    fun `non-tab routes are pushed onto the current tab only`() {
        navigator.navigate(MainRoute.Explore)
        navigator.navigate(PostDetail)
        assertEquals(listOf(MainRoute.Explore, PostDetail), state.backStacks.getValue(MainRoute.Explore).toList())
        assertEquals(listOf(MainRoute.Feed), state.backStacks.getValue(MainRoute.Feed).toList())
    }

    @Test
    fun `each tab remembers its own stack`() {
        navigator.navigate(MainRoute.Explore)
        navigator.navigate(PostDetail)
        navigator.navigate(MainRoute.Profile)
        navigator.navigate(MainRoute.Explore)
        assertEquals(PostDetail, state.backStacks.getValue(MainRoute.Explore).last())
    }

    @Test
    fun `back pops within a tab, then returns to the start tab, then lets the system exit`() {
        navigator.navigate(MainRoute.Explore)
        navigator.navigate(PostDetail)

        assertTrue(navigator.goBack())
        assertEquals(listOf(MainRoute.Explore), state.backStacks.getValue(MainRoute.Explore).toList())

        assertTrue(navigator.goBack())
        assertEquals(MainRoute.Feed, state.topLevelRoute)

        assertFalse(navigator.goBack())
    }
}
