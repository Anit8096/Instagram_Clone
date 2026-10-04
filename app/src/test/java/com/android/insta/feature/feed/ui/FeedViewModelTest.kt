package com.android.insta.feature.feed.ui

import androidx.paging.PagingData
import com.android.insta.feature.feed.data.FeedRepository
import com.android.insta.feature.post.data.Post
import com.android.insta.testutil.FakePostRepository
import com.android.insta.testutil.FakeSocialRepository
import com.android.insta.testutil.MainDispatcherRule
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@org.junit.runner.RunWith(androidx.test.ext.junit.runners.AndroidJUnit4::class)
class FeedViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private val posts = FakePostRepository()
    private val social = FakeSocialRepository()
    private val feed = object : FeedRepository {
        override fun feed(): Flow<PagingData<Post>> = flowOf(PagingData.empty())
        override suspend fun removeFromCache(postId: String) = Unit
        override suspend fun clearCache() = Unit
    }

    /** Regression: a follow made on another tab (feed not composed) must still refresh the feed later. */
    @Test
    fun `follow changes while the feed is off screen mark it stale until handled`() = runTest {
        val viewModel = FeedViewModel(feed, posts, social, com.android.insta.testutil.testActionQueue())
        assertFalse(viewModel.needsRefresh.value)

        social.changes.emit(Unit)
        assertTrue(viewModel.needsRefresh.value)

        viewModel.onRefreshHandled()
        assertFalse(viewModel.needsRefresh.value)
    }

    @Test
    fun `own post published marks the feed stale`() = runTest {
        val viewModel = FeedViewModel(feed, posts, social, com.android.insta.testutil.testActionQueue())
        posts.changes.emit(Unit)
        assertTrue(viewModel.needsRefresh.value)
    }
}
