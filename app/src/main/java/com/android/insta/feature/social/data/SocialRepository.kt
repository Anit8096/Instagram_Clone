package com.android.insta.feature.social.data

import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.android.insta.core.network.ApiResult
import com.android.insta.core.network.AppError
import com.android.insta.core.network.UrlResolver
import com.android.insta.core.network.map
import com.android.insta.core.network.safeApiCall
import com.android.insta.feature.post.data.PageDto
import com.android.insta.feature.post.data.Post
import com.android.insta.feature.post.data.PostDto
import com.android.insta.feature.post.data.toPost
import io.ktor.client.HttpClient
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.put
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.Serializable

@Serializable
data class UserSummaryDto(val id: String, val username: String, val displayName: String, val avatarUrl: String? = null, val isFollowing: Boolean)

@Serializable
data class FollowStateDto(val isFollowing: Boolean, val followerCount: Long)

data class UserSummary(val id: String, val username: String, val displayName: String, val avatarUrl: String?, val isFollowing: Boolean)

class SocialApi(private val client: HttpClient) {
    suspend fun follow(username: String): ApiResult<FollowStateDto> = safeApiCall { client.put("api/v1/users/$username/follow") }
    suspend fun unfollow(username: String): ApiResult<FollowStateDto> = safeApiCall { client.delete("api/v1/users/$username/follow") }
    suspend fun search(query: String): ApiResult<List<UserSummaryDto>> =
        safeApiCall { client.get("api/v1/search/users") { parameter("q", query) } }

    suspend fun relations(username: String, followers: Boolean, cursor: String?): ApiResult<PageDto<UserSummaryDto>> = safeApiCall {
        client.get("api/v1/users/$username/${if (followers) "followers" else "following"}") { cursor?.let { parameter("cursor", it) } }
    }

    suspend fun feed(cursor: String?, limit: Int): ApiResult<PageDto<PostDto>> = safeApiCall {
        client.get("api/v1/feed") {
            parameter("limit", limit)
            cursor?.let { parameter("cursor", it) }
        }
    }

    suspend fun explore(cursor: String?, limit: Int): ApiResult<PageDto<PostDto>> = safeApiCall {
        client.get("api/v1/explore") {
            parameter("limit", limit)
            cursor?.let { parameter("cursor", it) }
        }
    }
}

/** Paging needs exceptions; this carries our typed error through LoadResult.Error. */
class AppErrorException(val error: AppError) : Exception(error.toString())

interface SocialRepository {
    /** Emits after a follow/unfollow from this device, so the feed and explore refresh. */
    val followChanged: SharedFlow<Unit>
    suspend fun setFollowing(username: String, follow: Boolean): ApiResult<FollowStateDto>
    suspend fun search(query: String): ApiResult<List<UserSummary>>
    fun relationsSource(username: String, followers: Boolean): PagingSource<String, UserSummary>
    fun exploreSource(): PagingSource<String, Post>
}

class DefaultSocialRepository(private val api: SocialApi, private val urls: UrlResolver) : SocialRepository {
    private val _followChanged = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    override val followChanged: SharedFlow<Unit> = _followChanged.asSharedFlow()

    override suspend fun setFollowing(username: String, follow: Boolean): ApiResult<FollowStateDto> =
        (if (follow) api.follow(username) else api.unfollow(username)).also {
            if (it is ApiResult.Success) _followChanged.tryEmit(Unit)
        }

    override suspend fun search(query: String): ApiResult<List<UserSummary>> = api.search(query).map { list -> list.map { it.toSummary() } }

    override fun relationsSource(username: String, followers: Boolean): PagingSource<String, UserSummary> =
        CursorPagingSource { cursor -> api.relations(username, followers, cursor).map { page -> page.items.map { it.toSummary() } to page.nextCursor } }

    override fun exploreSource(): PagingSource<String, Post> =
        CursorPagingSource { cursor -> api.explore(cursor, 30).map { page -> page.items.map { it.toPost(urls) } to page.nextCursor } }

    private fun UserSummaryDto.toSummary() = UserSummary(id, username, displayName, urls.resolve(avatarUrl), isFollowing)
}

/** Network-only paging over our `{items, nextCursor}` endpoints. */
class CursorPagingSource<T : Any>(
    private val fetch: suspend (cursor: String?) -> ApiResult<Pair<List<T>, String?>>,
) : PagingSource<String, T>() {
    override suspend fun load(params: LoadParams<String>): LoadResult<String, T> =
        when (val result = fetch(params.key)) {
            is ApiResult.Success -> LoadResult.Page(data = result.value.first, prevKey = null, nextKey = result.value.second)
            is ApiResult.Failure -> LoadResult.Error(AppErrorException(result.error))
        }

    // Cursors only move forward; a refresh starts again from the top.
    override fun getRefreshKey(state: PagingState<String, T>): String? = null
}
