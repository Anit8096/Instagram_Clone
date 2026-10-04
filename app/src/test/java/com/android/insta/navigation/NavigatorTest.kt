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
    fun `chat deep link opens the thread above the inbox on the home tab, once`() {
        navigator.navigate(MainRoute.Explore)
        repeat(2) { navigator.openDeepLink(DetailRoute.Thread("bob")) }
        assertEquals(MainRoute.Feed, state.topLevelRoute)
        assertEquals(listOf(MainRoute.Feed, DetailRoute.Inbox, DetailRoute.Thread("bob")), state.backStacks.getValue(MainRoute.Feed).toList())

        navigator.openDeepLink(DetailRoute.Thread("carol")) // another conversation replaces the open one
        assertEquals(listOf(MainRoute.Feed, DetailRoute.Inbox, DetailRoute.Thread("carol")), state.backStacks.getValue(MainRoute.Feed).toList())
    }

    @Test
    fun `other deep links push onto the current tab or switch tabs`() {
        navigator.navigate(MainRoute.Explore)
        repeat(2) { navigator.openDeepLink(DetailRoute.PostDetail("p1")) }
        assertEquals(listOf(MainRoute.Explore, DetailRoute.PostDetail("p1")), state.backStacks.getValue(MainRoute.Explore).toList())

        navigator.openDeepLink(MainRoute.Profile)
        assertEquals(MainRoute.Profile, state.topLevelRoute)
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
