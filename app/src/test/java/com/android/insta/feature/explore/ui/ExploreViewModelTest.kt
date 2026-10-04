package com.android.insta.feature.explore.ui

import com.android.insta.core.network.ApiResult
import com.android.insta.feature.social.data.UserSummary
import com.android.insta.testutil.FakeSocialRepository
import com.android.insta.testutil.MainDispatcherRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ExploreViewModelTest {
    @get:Rule val main = MainDispatcherRule(StandardTestDispatcher())
    private val social = FakeSocialRepository()

    @Test
    fun `search waits for typing to pause and only sends the latest query`() = runTest(main.dispatcher) {
        social.searchResult = ApiResult.Success(listOf(UserSummary("1", "sam", "Sam", null, false)))
        val viewModel = ExploreViewModel(social)
        backgroundScope.launch { viewModel.search.collect {} }

        listOf("s", "sa", "sam").forEach {
            viewModel.onQueryChange(it)
            advanceTimeBy(100)
        }
        runCurrent()
        assertTrue(social.calls.isEmpty())

        advanceTimeBy(ExploreViewModel.SEARCH_DEBOUNCE_MS)
        runCurrent()
        assertEquals(listOf("search:sam"), social.calls)
        assertEquals(listOf("sam"), viewModel.search.value.results.map { it.username })
    }

    @Test
    fun `clearing the query clears results without a request`() = runTest(main.dispatcher) {
        val viewModel = ExploreViewModel(social)
        backgroundScope.launch { viewModel.search.collect {} }
        viewModel.onQueryChange("   ")
        advanceTimeBy(ExploreViewModel.SEARCH_DEBOUNCE_MS + 1)
        runCurrent()
        assertTrue(social.calls.isEmpty())
        assertTrue(viewModel.search.value.results.isEmpty())
    }
}
