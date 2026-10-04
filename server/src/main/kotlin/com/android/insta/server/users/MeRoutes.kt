package com.android.insta.server.users

import com.android.insta.server.common.ApiException
import com.android.insta.server.common.pageRequest
import com.android.insta.server.plugins.currentUserId
import com.android.insta.server.posts.PostService
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import org.koin.ktor.ext.inject

/** Routes for the signed-in user and public profiles. Must be mounted inside `authenticate`. */
fun Route.meRoutes() {
    val users by inject<UserRepository>()
    val profiles by inject<ProfileService>()
    val posts by inject<PostService>()

    get("/me") {
        val user = users.findById(call.currentUserId())
            ?: throw ApiException(HttpStatusCode.Unauthorized, "UNAUTHORIZED", "Account no longer exists")
        call.respond(user.toDto())
    }
    patch("/me") {
        call.respond(profiles.update(call.currentUserId(), call.receive<UpdateProfileRequest>()))
    }
    get("/users/{username}") {
        call.respond(profiles.profile(call.currentUserId(), call.parameters["username"].orEmpty()))
    }
    get("/users/{username}/posts") {
        val authorId = profiles.userIdFor(call.parameters["username"].orEmpty())
        call.respond(posts.byAuthor(call.currentUserId(), authorId, call.pageRequest()))
    }
}
