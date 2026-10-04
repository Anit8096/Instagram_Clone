package com.android.insta.feature.post.ui

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.insta.R
import com.android.insta.core.network.ApiResult
import com.android.insta.core.network.AppError
import com.android.insta.core.session.Session
import com.android.insta.core.session.SessionManager
import com.android.insta.core.ui.UiMessage
import com.android.insta.testutil.FakePostRepository
import com.android.insta.testutil.FakeSessionStore
import com.android.insta.testutil.MainDispatcherRule
import com.android.insta.testutil.TEST_USER
import com.android.insta.testutil.testPost
import kotlinx.coroutines.test.TestScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CreatePostViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private val repository = FakePostRepository()
    private val viewModel = CreatePostViewModel(repository)
    private val photo = Uri.parse("content://media/picked/1")

    @Test
    fun `cannot share without a photo or with an over-long caption`() {
        assertFalse(viewModel.state.value.canShare)
        viewModel.onEvent(CreatePostEvent.ImagePicked(photo))
        assertTrue(viewModel.state.value.canShare)

        viewModel.onEvent(CreatePostEvent.CaptionChanged("x".repeat(CAPTION_MAX + 1)))
        assertEquals(UiMessage.Resource(R.string.error_caption_length), viewModel.state.value.captionError)
        assertFalse(viewModel.state.value.canShare)
        viewModel.onEvent(CreatePostEvent.Share)
        assertTrue(repository.calls.isEmpty())
    }

    @Test
    fun `sharing queues the post and resets after the screen acknowledges`() {
        viewModel.onEvent(CreatePostEvent.ImagePicked(photo))
        viewModel.onEvent(CreatePostEvent.CaptionChanged("sunset"))
        viewModel.onEvent(CreatePostEvent.Share)

        assertEquals(listOf("create:sunset"), repository.calls)
        assertTrue(viewModel.state.value.shared)
        viewModel.onEvent(CreatePostEvent.SharedHandled)
        assertNull(viewModel.state.value.imageUri)
        assertFalse(viewModel.state.value.shared)
    }

    @Test
    fun `unreadable photo shows an error and keeps the form`() {
        repository.createResult = Result.failure(IllegalStateException())
        viewModel.onEvent(CreatePostEvent.ImagePicked(photo))
        viewModel.onEvent(CreatePostEvent.Share)
        assertEquals(UiMessage.Resource(R.string.error_image_unreadable), viewModel.state.value.error)
        assertEquals(photo, viewModel.state.value.imageUri)
        assertFalse(viewModel.state.value.isSharing)
    }
}

@RunWith(AndroidJUnit4::class)
class PostDetailViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private val repository = FakePostRepository()
    private val sessionManager = SessionManager(FakeSessionStore(Session("a", "r", TEST_USER)), TestScope(main.dispatcher))

    @Test
    fun `own post can be deleted and the screen is told to close`() {
        repository.getResult = ApiResult.Success(testPost(authorId = TEST_USER.id))
        val viewModel = PostDetailViewModel("p1", repository, sessionManager, com.android.insta.testutil.testActionQueue())
        assertTrue(viewModel.state.value.isMine)

        viewModel.onEvent(PostDetailEvent.Delete)
        assertEquals(listOf("delete:p1"), repository.calls)
        assertTrue(viewModel.state.value.deleted)
    }

    @Test
    fun `someone else's post can't be deleted`() {
        repository.getResult = ApiResult.Success(testPost(authorId = "someone-else"))
        val viewModel = PostDetailViewModel("p1", repository, sessionManager, com.android.insta.testutil.testActionQueue())
        viewModel.onEvent(PostDetailEvent.Delete)
        assertFalse(viewModel.state.value.isMine)
        assertTrue(repository.calls.isEmpty())
    }

    @Test
    fun `missing post shows not found rather than an error`() {
        repository.getResult = ApiResult.Failure(AppError.Api(404, "NOT_FOUND", "Post not found"))
        val viewModel = PostDetailViewModel("p1", repository, sessionManager, com.android.insta.testutil.testActionQueue())
        assertTrue(viewModel.state.value.notFound)
        assertNull(viewModel.state.value.error)
    }
}
