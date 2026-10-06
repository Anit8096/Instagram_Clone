package com.android.insta.feature.post.ui

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.insta.R
import com.android.insta.core.network.ApiResult
import com.android.insta.core.network.AppError
import com.android.insta.core.session.Session
import com.android.insta.core.session.SessionManager
import com.android.insta.core.ui.UiMessage
import com.android.insta.feature.post.data.CropAspect
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
    private fun photos(range: IntRange) = range.map { Uri.parse("content://media/picked/$it") }
    private val state get() = viewModel.state.value

    @Test
    fun `cannot share without a photo or with an over-long caption`() {
        assertFalse(state.canShare)
        viewModel.onEvent(CreatePostEvent.ImagesPicked(listOf(photo)))
        assertTrue(state.canShare)

        viewModel.onEvent(CreatePostEvent.CaptionChanged("x".repeat(CAPTION_MAX + 1)))
        assertEquals(UiMessage.Resource(R.string.error_caption_length), state.captionError)
        assertFalse(state.canShare)
        viewModel.onEvent(CreatePostEvent.Share)
        assertTrue(repository.calls.isEmpty())
    }

    @Test
    fun `sharing queues every photo with the chosen crop and resets after the screen acknowledges`() {
        viewModel.onEvent(CreatePostEvent.ImagesPicked(photos(1..3)))
        viewModel.onEvent(CreatePostEvent.AspectChanged(CropAspect.SQUARE))
        viewModel.onEvent(CreatePostEvent.CaptionChanged("sunset"))
        viewModel.onEvent(CreatePostEvent.Share)

        assertEquals(listOf("create:3:SQUARE:sunset"), repository.calls)
        assertTrue(state.shared)
        viewModel.onEvent(CreatePostEvent.SharedHandled)
        assertEquals(CreatePostUiState(), state)
    }

    @Test
    fun `picking again adds after the chosen photos, skips duplicates and stops at ten`() {
        viewModel.onEvent(CreatePostEvent.ImagesPicked(photos(1..4)))
        viewModel.onEvent(CreatePostEvent.ImagesPicked(photos(3..6)))
        assertEquals(photos(1..6), state.images)
        assertEquals(4, state.selected) // first newly added photo
        assertNull(state.error)

        viewModel.onEvent(CreatePostEvent.ImagesPicked(photos(7..12)))
        assertEquals(photos(1..10), state.images)
        assertEquals(UiMessage.Plural(R.plurals.error_max_photos, 10), state.error)
        assertFalse(state.canAddMore)
    }

    @Test
    fun `the selected photo can be moved and removed`() {
        val (a, b, c) = photos(1..3)
        viewModel.onEvent(CreatePostEvent.ImagesPicked(listOf(a, b, c)))
        viewModel.onEvent(CreatePostEvent.Select(2))
        viewModel.onEvent(CreatePostEvent.MoveSelected(-1))
        assertEquals(listOf(a, c, b), state.images)
        assertEquals(1, state.selected)

        viewModel.onEvent(CreatePostEvent.MoveSelected(-5)) // clamps to the cover position
        assertEquals(listOf(c, a, b), state.images)
        assertEquals(0, state.selected)

        viewModel.onEvent(CreatePostEvent.Select(2))
        viewModel.onEvent(CreatePostEvent.RemoveSelected)
        assertEquals(listOf(c, a), state.images)
        assertEquals(1, state.selected) // stays on a valid photo

        viewModel.onEvent(CreatePostEvent.ClearImages)
        assertTrue(state.images.isEmpty())
        assertFalse(state.canShare)
    }

    @Test
    fun `unreadable photo shows an error and keeps the form`() {
        repository.createResult = Result.failure(IllegalStateException())
        viewModel.onEvent(CreatePostEvent.ImagesPicked(listOf(photo)))
        viewModel.onEvent(CreatePostEvent.Share)
        assertEquals(UiMessage.Resource(R.string.error_image_unreadable), state.error)
        assertEquals(listOf(photo), state.images)
        assertFalse(state.isSharing)
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
