package com.android.insta.server.posts

import com.android.insta.server.common.uuidParam
import com.android.insta.server.plugins.currentUserId
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import org.koin.ktor.ext.inject

/** Mount inside `authenticate`. */
fun Route.postRoutes() {
    val service by inject<PostService>()

    route("/posts/{id}") {
        put {
            val result = service.create(call.currentUserId(), call.uuidParam("id"), call.receive<CreatePostRequest>())
            call.respond(if (result.created) HttpStatusCode.Created else HttpStatusCode.OK, result.post.toDto())
        }
        get {
            call.respond(service.getDto(call.currentUserId(), call.uuidParam("id")))
        }
        delete {
            service.delete(call.currentUserId(), call.uuidParam("id"))
            call.respond(HttpStatusCode.NoContent)
        }
    }
}
