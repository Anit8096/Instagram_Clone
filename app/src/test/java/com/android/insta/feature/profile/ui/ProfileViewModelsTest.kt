package com.android.insta.feature.profile.ui

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.insta.R
import com.android.insta.core.network.ApiResult
import com.android.insta.core.network.AppError
import com.android.insta.core.session.Session
import com.android.insta.core.session.SessionManager
import com.android.insta.core.ui.UiMessage
import com.android.insta.feature.profile.data.PostPage
import com.android.insta.testutil.FakePostRepository
import com.android.insta.testutil.FakeProfileRepository
import com.android.insta.testutil.FakeSocialRepository
import com.android.insta.testutil.testProfile
import com.android.insta.testutil.FakeSessionStore
import com.android.insta.testutil.MainDispatcherRule
import com.android.insta.testutil.TEST_USER
import com.android.insta.testutil.testPost
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

class ProfileViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private val profiles = FakeProfileRepository()
    private val posts = FakePostRepository()

    private val social = FakeSocialRepository()

    private fun viewModel(username: String = "jane.doe") = ProfileViewModel(username, profiles, posts, social)

    @Test
    fun `loads profile and first page`() {
        profiles.pages[null] = ApiResult.Success(PostPage(listOf(testPost("p2"), testPost("p1")), nextCursor = "c1"))
        val state = viewModel().state.value

        assertEquals("jane.doe", state.profile?.username)
        assertEquals(listOf("p2", "p1"), state.posts.map { it.id })
        assertTrue(state.canLoadMore)
        assertFalse(state.isLoading)
    }

    @Test
    fun `load more appends the next page and de-duplicates`() {
        profiles.pages[null] = ApiResult.Success(PostPage(listOf(testPost("p3"), testPost("p2")), "c1"))
        profiles.pages["c1"] = ApiResult.Success(PostPage(listOf(testPost("p2"), testPost("p1")), null))
        val viewModel = viewModel()

        viewModel.onEvent(ProfileEvent.LoadMore)
        assertEquals(listOf("p3", "p2", "p1"), viewModel.state.value.posts.map { it.id })
        assertFalse(viewModel.state.value.canLoadMore)
    }

    @Test
    fun `a post published or deleted on this device refreshes the profile`() = runTest {
        val viewModel = viewModel()
        profiles.pages[null] = ApiResult.Success(PostPage(listOf(testPost("new")), null))

        posts.changes.emit(Unit)
        assertEquals(listOf("new"), viewModel.state.value.posts.map { it.id })
    }

    @Test
    fun `a bio-only profile edit refreshes the profile`() = runTest {
        val viewModel = viewModel()
        profiles.profileResult = ApiResult.Success(testProfile().copy(bio = "Hello from the journey"))

        profiles.changes.emit(Unit)
        assertEquals("Hello from the journey", viewModel.state.value.profile?.bio)
    }

    @Test
    fun `failed profile load shows an error with retry`() {
        profiles.profileResult = ApiResult.Failure(AppError.Network)
        val state = viewModel().state.value
        assertNull(state.profile)
        assertEquals(UiMessage.Resource(R.string.error_network), state.error)
    }

    @Test
    fun `follow is shown immediately and reconciled with the server`() {
        profiles.profileResult = ApiResult.Success(testProfile(isMe = false).copy(username = "bob", followerCount = 4))
        social.followResult = { ApiResult.Success(com.android.insta.feature.social.data.FollowStateDto(it, 7)) }
        val viewModel = viewModel("bob")

        viewModel.onEvent(ProfileEvent.ToggleFollow)
        assertEquals(true, viewModel.state.value.profile?.isFollowing)
        assertEquals(7L, viewModel.state.value.profile?.followerCount) // server count wins
        assertEquals(listOf("follow:bob:true"), social.calls)
    }

    @Test
    fun `failed follow reverts and shows an error`() {
        profiles.profileResult = ApiResult.Success(testProfile(isMe = false).copy(username = "bob", followerCount = 4))
        social.followResult = { ApiResult.Failure(AppError.Network) }
        val viewModel = viewModel("bob")

        viewModel.onEvent(ProfileEvent.ToggleFollow)
        assertEquals(false, viewModel.state.value.profile?.isFollowing)
        assertEquals(4L, viewModel.state.value.profile?.followerCount)
        assertEquals(UiMessage.Resource(R.string.error_network), viewModel.state.value.error)
    }
}

@RunWith(AndroidJUnit4::class)
class EditProfileViewModelTest {
    @get:Rule val main = MainDispatcherRule()
    private val profiles = FakeProfileRepository()
    private val sessionManager = SessionManager(FakeSessionStore(Session("a", "r", TEST_USER)), TestScope(main.dispatcher))

    @Test
    fun `prefills from the profile and saves edits`() {
        val viewModel = EditProfileViewModel(profiles, sessionManager)
        assertEquals("Jane", viewModel.state.value.displayName)
        assertEquals("Hi", viewModel.state.value.bio)

        viewModel.onEvent(EditProfileEvent.BioChanged(" New bio "))
        viewModel.onEvent(EditProfileEvent.Save)
        assertTrue("update:Jane|New bio|null|false" in profiles.calls)
        assertTrue(viewModel.state.value.saved)
    }

    @Test
    fun `picked avatar is uploaded and saved with the profile`() {
        val viewModel = EditProfileViewModel(profiles, sessionManager)
        viewModel.onEvent(EditProfileEvent.AvatarPicked(Uri.parse("content://media/1")))
        assertEquals("http://test/a.jpg", viewModel.state.value.shownAvatarUrl)

        viewModel.onEvent(EditProfileEvent.Save)
        assertTrue(profiles.calls.last().endsWith("|avatar-1|false"))
    }

    @Test
    fun `too long bio blocks saving`() {
        val viewModel = EditProfileViewModel(profiles, sessionManager)
        viewModel.onEvent(EditProfileEvent.BioChanged("b".repeat(EditProfileViewModel.BIO_MAX + 1)))
        assertEquals(UiMessage.Resource(R.string.error_bio_length), viewModel.state.value.bioError)
        viewModel.onEvent(EditProfileEvent.Save)
        assertFalse(profiles.calls.any { it.startsWith("update:") })
    }
}
