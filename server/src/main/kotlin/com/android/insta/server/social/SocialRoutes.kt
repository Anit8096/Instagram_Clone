package com.android.insta.server.social

import com.android.insta.server.common.pageRequest
import com.android.insta.server.plugins.currentUserId
import com.android.insta.server.posts.PostService
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import org.koin.ktor.ext.inject

/** Mount inside `authenticate`. */
fun Route.socialRoutes() {
    val social by inject<SocialService>()
    val posts by inject<PostService>()

    put("/users/{username}/follow") {
        call.respond(social.follow(call.currentUserId(), call.parameters["username"].orEmpty()))
    }
    delete("/users/{username}/follow") {
        call.respond(social.unfollow(call.currentUserId(), call.parameters["username"].orEmpty()))
    }
    get("/users/{username}/followers") {
        call.respond(social.followers(call.currentUserId(), call.parameters["username"].orEmpty(), call.pageRequest()))
    }
    get("/users/{username}/following") {
        call.respond(social.following(call.currentUserId(), call.parameters["username"].orEmpty(), call.pageRequest()))
    }
    get("/search/users") {
        call.respond(social.search(call.currentUserId(), call.request.queryParameters["q"].orEmpty()))
    }
    get("/feed") {
        call.respond(posts.feed(call.currentUserId(), call.pageRequest(defaultLimit = 20)))
    }
    get("/explore") {
        call.respond(posts.explore(call.currentUserId(), call.pageRequest(defaultLimit = 30)))
    }
}
