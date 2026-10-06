package com.android.insta.testutil

import android.net.Uri
import com.android.insta.core.database.DraftItemEntity
import com.android.insta.core.database.PostDraftDao
import com.android.insta.feature.post.data.CropAspect
import com.android.insta.core.database.PostDraftEntity
import com.android.insta.core.media.ImageCompressor
import com.android.insta.core.network.ApiResult
import com.android.insta.feature.post.data.PendingUpload
import com.android.insta.feature.post.data.Post
import com.android.insta.feature.post.data.PostRepository
import com.android.insta.feature.post.data.PublishOutcome
import com.android.insta.feature.post.data.UploadScheduler
import com.android.insta.feature.profile.data.PostPage
import com.android.insta.feature.profile.data.Profile
import com.android.insta.feature.profile.data.ProfileRepository
import com.android.insta.feature.profile.data.UploadedAvatar
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.map
import java.io.File
import java.time.Instant

class FakeDraftDao : PostDraftDao {
    val rows = MutableStateFlow<Map<String, PostDraftEntity>>(emptyMap())
    /** Keyed by draft id to position. */
    val itemRows = mutableMapOf<Pair<String, Int>, DraftItemEntity>()
    override suspend fun upsert(draft: PostDraftEntity) { rows.value = rows.value + (draft.id to draft) }
    override suspend fun get(id: String) = rows.value[id]
    override fun observeAll(): Flow<List<PostDraftEntity>> = rows.map { it.values.sortedBy(PostDraftEntity::createdAt) }
    override suspend fun all() = rows.value.values.toList()
    override suspend fun delete(id: String) { rows.value = rows.value - id }
    override suspend fun deleteAll() { rows.value = emptyMap() }
    override suspend fun items(draftId: String) = itemRows.values.filter { it.draftId == draftId }.sortedBy { it.position }
    override suspend fun allItems() = itemRows.values.toList()
    override suspend fun upsertItems(items: List<DraftItemEntity>) { items.forEach { itemRows[it.draftId to it.position] = it } }
    override suspend fun setItemMedia(draftId: String, position: Int, mediaId: String) {
        itemRows[draftId to position]?.let { itemRows[draftId to position] = it.copy(mediaId = mediaId) }
    }
    override suspend fun clearItemMedia(draftId: String) {
        itemRows.replaceAll { _, item -> if (item.draftId == draftId) item.copy(mediaId = null) else item }
    }
    override suspend fun deleteItems(draftId: String) { itemRows.keys.removeAll { it.first == draftId } }
    override suspend fun deleteAllItems() { itemRows.clear() }
}

class FakeScheduler : UploadScheduler {
    val enqueued = mutableListOf<String>()
    var cancelled = false
    override fun enqueue(draftId: String) { enqueued += draftId }
    override fun cancelAll() { cancelled = true }
}

/** Writes a small file to [dir] instead of decoding the Uri. [failOn] makes only that Uri unreadable. */
class FakeCompressor(private val dir: File, var fail: Boolean = false, var failOn: Uri? = null) : ImageCompressor {
    val aspects = mutableListOf<Float?>()
    val written = mutableListOf<File>()
    override suspend fun compress(uri: Uri, aspect: Float?): File {
        if (fail || uri == failOn) error("unreadable")
        aspects += aspect
        return File.createTempFile("draft", ".jpg", dir).apply { writeBytes(byteArrayOf(1, 2, 3)) }.also { written += it }
    }
}

fun testPost(id: String = "p1", authorId: String = "u1", username: String = "jane.doe") = Post(
    id = id,
    authorId = authorId,
    authorUsername = username,
    authorDisplayName = "Jane",
    authorAvatarUrl = null,
    imageUrl = "http://test/api/v1/media/m-$id/full",
    thumbUrl = "http://test/api/v1/media/m-$id/thumb",
    width = 1080,
    height = 1080,
    caption = "caption $id",
    likeCount = 0,
    commentCount = 0,
    createdAt = Instant.parse("2026-10-04T00:00:00Z"),
)

fun testProfile(isMe: Boolean = true, postCount: Long = 0) = Profile(
    id = "u1", username = "jane.doe", displayName = "Jane", bio = "Hi", avatarUrl = null,
    postCount = postCount, followerCount = 0, followingCount = 0, isMe = isMe, isFollowing = false,
)

class FakePostRepository : PostRepository {
    val pending = MutableStateFlow<List<PendingUpload>>(emptyList())
    val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    var createResult: Result<Unit> = Result.success(Unit)
    var getResult: ApiResult<Post> = ApiResult.Success(testPost())
    var deleteResult: ApiResult<Unit> = ApiResult.Success(Unit)
    var publishOutcome: PublishOutcome = PublishOutcome.Published
    val calls = mutableListOf<String>()

    override val pendingUploads: Flow<List<PendingUpload>> = pending
    override val postsChanged: SharedFlow<Unit> = changes
    override suspend fun createPost(imageUris: List<Uri>, caption: String, aspect: CropAspect): Result<Unit> {
        calls += "create:${imageUris.size}:$aspect:$caption"
        return createResult
    }
    override suspend fun publishDraft(draftId: String): PublishOutcome { calls += "publish:$draftId"; return publishOutcome }
    override suspend fun markFailed(draftId: String, message: String) { calls += "failed:$draftId:$message" }
    override suspend fun retry(draftId: String) { calls += "retry:$draftId" }
    override suspend fun discard(draftId: String) { calls += "discard:$draftId" }
    override suspend fun clearDrafts() { calls += "clear" }
    override suspend fun getPost(id: String): ApiResult<Post> = getResult
    override suspend fun deletePost(id: String): ApiResult<Unit> { calls += "delete:$id"; return deleteResult }
}

class FakeProfileRepository : ProfileRepository {
    val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    override val profileChanged: SharedFlow<Unit> = changes
    var profileResult: ApiResult<Profile> = ApiResult.Success(testProfile())
    /** Pages keyed by cursor (null = first page). */
    val pages = mutableMapOf<String?, ApiResult<PostPage>>(null to ApiResult.Success(PostPage(emptyList(), null)))
    var avatarResult: ApiResult<UploadedAvatar> = ApiResult.Success(UploadedAvatar("avatar-1", "http://test/a.jpg"))
    var updateResult: ApiResult<Unit> = ApiResult.Success(Unit)
    val calls = mutableListOf<String>()

    override suspend fun profile(username: String): ApiResult<Profile> { calls += "profile:$username"; return profileResult }
    override suspend fun posts(username: String, cursor: String?): ApiResult<PostPage> {
        calls += "posts:$cursor"
        return pages[cursor] ?: ApiResult.Success(PostPage(emptyList(), null))
    }
    override suspend fun uploadAvatar(uri: Uri): ApiResult<UploadedAvatar> { calls += "avatar"; return avatarResult }
    override suspend fun updateProfile(displayName: String?, bio: String?, avatarMediaId: String?, removeAvatar: Boolean): ApiResult<Unit> {
        calls += "update:$displayName|$bio|$avatarMediaId|$removeAvatar"
        return updateResult
    }
    var phoneOtpResult: ApiResult<com.android.insta.feature.auth.data.OtpChallenge> = ApiResult.Success(TEST_CHALLENGE)
    var confirmPhoneResult: ApiResult<Unit> = ApiResult.Success(Unit)
    override suspend fun requestPhoneChange(phone: String): ApiResult<com.android.insta.feature.auth.data.OtpChallenge> {
        calls += "phoneOtp:$phone"
        return phoneOtpResult
    }
    override suspend fun confirmPhoneChange(challengeId: String, code: String): ApiResult<Unit> {
        calls += "confirmPhone:$challengeId:$code"
        return confirmPhoneResult
    }
}

class FakeSocialRepository : com.android.insta.feature.social.data.SocialRepository {
    val changes = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    override val followChanged: SharedFlow<Unit> = changes
    var followResult: (Boolean) -> ApiResult<com.android.insta.feature.social.data.FollowStateDto> =
        { follow -> ApiResult.Success(com.android.insta.feature.social.data.FollowStateDto(follow, if (follow) 1 else 0)) }
    var searchResult: ApiResult<List<com.android.insta.feature.social.data.UserSummary>> = ApiResult.Success(emptyList())
    val calls = mutableListOf<String>()

    override suspend fun setFollowing(username: String, follow: Boolean) = followResult(follow).also { calls += "follow:$username:$follow" }
    override suspend fun search(query: String) = searchResult.also { calls += "search:$query" }
    override fun relationsSource(username: String, followers: Boolean) =
        com.android.insta.feature.social.data.CursorPagingSource<com.android.insta.feature.social.data.UserSummary> { ApiResult.Success(emptyList<com.android.insta.feature.social.data.UserSummary>() to null) }
    override fun exploreSource() =
        com.android.insta.feature.social.data.CursorPagingSource<Post> { ApiResult.Success(emptyList<Post>() to null) }
}
