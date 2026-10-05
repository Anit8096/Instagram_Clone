package com.android.insta.feature.profile.data

import android.net.Uri
import com.android.insta.core.media.ImageCompressor
import com.android.insta.core.network.ApiResult
import com.android.insta.core.network.AppError
import com.android.insta.core.network.UrlResolver
import com.android.insta.core.network.jsonBody
import com.android.insta.core.network.map
import com.android.insta.core.network.safeApiCall
import com.android.insta.core.session.SessionStore
import com.android.insta.feature.auth.data.OtpChallenge
import com.android.insta.feature.auth.data.OtpChallengeDto
import com.android.insta.feature.auth.data.PhoneOtpRequest
import com.android.insta.feature.auth.data.UserDto
import com.android.insta.feature.auth.data.VerifyOtpRequest
import com.android.insta.feature.auth.data.toChallenge
import com.android.insta.feature.auth.data.toSessionUser
import io.ktor.client.request.post
import io.ktor.client.request.put
import com.android.insta.feature.post.data.MediaKind
import com.android.insta.feature.post.data.PageDto
import com.android.insta.feature.post.data.Post
import com.android.insta.feature.post.data.PostApi
import com.android.insta.feature.post.data.PostDto
import com.android.insta.feature.post.data.toPost
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.patch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.Serializable
import timber.log.Timber
import kotlin.coroutines.cancellation.CancellationException

@Serializable
data class ProfileDto(
    val user: UserDto,
    val postCount: Long,
    val followerCount: Long,
    val followingCount: Long,
    val isMe: Boolean,
    val isFollowing: Boolean = false,
)

@Serializable
data class UpdateProfileRequest(
    val displayName: String? = null,
    val bio: String? = null,
    val avatarMediaId: String? = null,
    val removeAvatar: Boolean = false,
)

class ProfileApi(private val client: HttpClient) {
    suspend fun me(): ApiResult<UserDto> = safeApiCall { client.get("api/v1/me") }

    suspend fun profile(username: String): ApiResult<ProfileDto> = safeApiCall { client.get("api/v1/users/$username") }

    suspend fun posts(username: String, cursor: String?, limit: Int): ApiResult<PageDto<PostDto>> = safeApiCall {
        client.get("api/v1/users/$username/posts") {
            parameter("limit", limit)
            cursor?.let { parameter("cursor", it) }
        }
    }

    suspend fun updateMe(request: UpdateProfileRequest): ApiResult<UserDto> =
        safeApiCall { client.patch("api/v1/me") { jsonBody(request) } }

    /** Change phone, step 1: the code goes to the new number. */
    suspend fun requestPhoneChange(request: PhoneOtpRequest): ApiResult<OtpChallengeDto> =
        safeApiCall { client.post("api/v1/me/phone/otp") { jsonBody(request) } }

    suspend fun confirmPhoneChange(request: VerifyOtpRequest): ApiResult<UserDto> =
        safeApiCall { client.put("api/v1/me/phone") { jsonBody(request) } }
}

data class Profile(
    val id: String,
    val username: String,
    val displayName: String,
    val bio: String,
    val avatarUrl: String?,
    val postCount: Long,
    val followerCount: Long,
    val followingCount: Long,
    val isMe: Boolean,
    val isFollowing: Boolean,
)

data class PostPage(val items: List<Post>, val nextCursor: String?)

/** An avatar uploaded but not yet saved to the profile. */
data class UploadedAvatar(val mediaId: String, val url: String)

interface ProfileRepository {
    /** Emits after the signed-in user's profile was edited on this device (any field, bio included). */
    val profileChanged: SharedFlow<Unit>

    suspend fun profile(username: String): ApiResult<Profile>
    suspend fun posts(username: String, cursor: String?): ApiResult<PostPage>
    suspend fun uploadAvatar(uri: Uri): ApiResult<UploadedAvatar>

    /** Saves profile edits and refreshes the cached session user. Null fields stay unchanged. */
    suspend fun updateProfile(displayName: String?, bio: String?, avatarMediaId: String?, removeAvatar: Boolean): ApiResult<Unit>

    /** Sends a code to the new (E.164) number; `PHONE_IN_USE` if another account has it. */
    suspend fun requestPhoneChange(phone: String): ApiResult<OtpChallenge>

    /** Verifies the code; the new number replaces the old one in the account and the cached session user. */
    suspend fun confirmPhoneChange(challengeId: String, code: String): ApiResult<Unit>
}

class DefaultProfileRepository(
    private val api: ProfileApi,
    private val postApi: PostApi,
    private val compressor: ImageCompressor,
    private val sessionStore: SessionStore,
    private val urls: UrlResolver,
) : ProfileRepository {

    private val _profileChanged = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    override val profileChanged: SharedFlow<Unit> = _profileChanged.asSharedFlow()

    override suspend fun profile(username: String): ApiResult<Profile> = api.profile(username).map { dto ->
        Profile(
            id = dto.user.id,
            username = dto.user.username,
            displayName = dto.user.displayName,
            bio = dto.user.bio,
            avatarUrl = urls.resolve(dto.user.avatarUrl),
            postCount = dto.postCount,
            followerCount = dto.followerCount,
            followingCount = dto.followingCount,
            isMe = dto.isMe,
            isFollowing = dto.isFollowing,
        )
    }

    override suspend fun posts(username: String, cursor: String?): ApiResult<PostPage> =
        api.posts(username, cursor, PAGE_SIZE).map { page -> PostPage(page.items.map { it.toPost(urls) }, page.nextCursor) }

    override suspend fun uploadAvatar(uri: Uri): ApiResult<UploadedAvatar> {
        val file = try {
            compressor.compress(uri)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.w(e, "Could not prepare avatar")
            return ApiResult.Failure(AppError.Unexpected(e))
        }
        return try {
            postApi.uploadMedia(file.readBytes(), MediaKind.AVATAR).map { UploadedAvatar(it.id, urls.resolve(it.thumbUrl)!!) }
        } finally {
            file.delete()
        }
    }

    override suspend fun updateProfile(displayName: String?, bio: String?, avatarMediaId: String?, removeAvatar: Boolean): ApiResult<Unit> =
        api.updateMe(UpdateProfileRequest(displayName, bio, avatarMediaId, removeAvatar)).also { result ->
            if (result is ApiResult.Success) {
                sessionStore.updateUser(result.value.toSessionUser())
                _profileChanged.tryEmit(Unit)
            }
        }.map { }

    override suspend fun requestPhoneChange(phone: String): ApiResult<OtpChallenge> =
        api.requestPhoneChange(PhoneOtpRequest(phone)).map { it.toChallenge() }

    override suspend fun confirmPhoneChange(challengeId: String, code: String): ApiResult<Unit> =
        api.confirmPhoneChange(VerifyOtpRequest(challengeId, code.trim())).also { result ->
            if (result is ApiResult.Success) sessionStore.updateUser(result.value.toSessionUser())
        }.map { }

    private companion object {
        const val PAGE_SIZE = 30
    }
}
