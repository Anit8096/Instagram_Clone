package com.android.insta.server.seed

import com.android.insta.server.chat.ChatService
import com.android.insta.server.config.AppConfig
import com.android.insta.server.db.DatabaseFactory
import com.android.insta.server.di.appModule
import com.android.insta.server.media.MediaKind
import com.android.insta.server.media.MediaService
import com.android.insta.server.posts.CreatePostRequest
import com.android.insta.server.posts.EngagementService
import com.android.insta.server.posts.PostService
import com.android.insta.server.social.SocialService
import com.android.insta.server.users.NewUser
import com.android.insta.server.users.ProfileService
import com.android.insta.server.users.UpdateProfileRequest
import com.android.insta.server.users.UserRepository
import kotlinx.coroutines.runBlocking
import org.koin.dsl.koinApplication
import java.awt.AlphaComposite
import java.awt.Color
import java.awt.Font
import java.awt.GradientPaint
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.random.Random
import kotlin.uuid.Uuid

// Demo data so a reviewer opens a lived-in app: six accounts with avatars, posts, follows, likes, comments and a
// conversation. Goes through the real services (images are processed and stored like uploads). Idempotent: does
// nothing if the demo accounts already exist.
//
// Run inside the compose stack:
//   docker compose exec server java -cp "/app/lib/*" com.android.insta.server.seed.SeedKt
// or locally against a running Postgres: `./gradlew seed` (same env vars as the server).
fun main() {
    System.setProperty("java.awt.headless", "true")
    val config = AppConfig.fromEnv()
    val database = DatabaseFactory.connect(config.db) // runs Flyway, so a fresh database works too
    val koin = koinApplication { modules(appModule(config, database.exposed)) }.koin
    try {
        runBlocking { DemoSeeder(koin.get(), koin.get(), koin.get(), koin.get(), koin.get(), koin.get(), koin.get()).seed() }
    } finally {
        koin.close()
        database.close()
    }
}

private data class DemoUser(val username: String, val name: String, val bio: String, val hue: Float, val captions: List<String>)

class DemoSeeder(
    private val users: UserRepository,
    private val profiles: ProfileService,
    private val media: MediaService,
    private val posts: PostService,
    private val social: SocialService,
    private val engagement: EngagementService,
    private val chat: ChatService,
) {
    private val random = Random(42)

    private val demo = listOf(
        DemoUser("maya.travels", "Maya Okafor", "Chasing light across 30 countries ✈️", 0.58f,
            listOf("Blue hour over the harbour", "Found this alley by accident", "Morning ferry, no filter")),
        DemoUser("leo.bakes", "Leo Martins", "Sourdough nerd. Weekend bread drops.", 0.08f,
            listOf("Day 3 starter is alive", "Crumb shot, finally", "Cinnamon knots for the neighbours")),
        DemoUser("ana.draws", "Ana Lindqvist", "Illustrator · ink & watercolour", 0.83f,
            listOf("Sketchbook page 41", "Colour study, 20 minutes", "Commission finished!")),
        DemoUser("sam.runs", "Sam Patel", "Marathon #4 in training 🏃", 0.33f,
            listOf("Sunrise 10k", "New shoes, who dis", "Long run Sunday done")),
        DemoUser("noor.codes", "Noor Haddad", "Android dev. Compose all the things.", 0.70f,
            listOf("Desk setup v3", "Shipped it 🚀", "Coffee and coroutines")),
        DemoUser("kai.garden", "Kai Tanaka", "Tiny balcony, big tomatoes", 0.25f,
            listOf("First tomato of the season", "Basil everywhere", "Rain day on the balcony")),
    )

    suspend fun seed() {
        if (users.findByUsername(demo.first().username) != null) {
            println("Demo data already present; nothing to do. Sign in by phone with ${phoneFor(0)} (maya.travels)")
            return
        }
        val ids = demo.mapIndexed { index, user ->
            // Accounts are normally created by Google sign-in + onboarding; demo users get a synthetic Google identity
            // and a fictional number (555-01xx is reserved for fiction), and sign in by phone.
            val id = users.create(NewUser(user.username, null, "seed:${user.username}", phoneFor(index), user.name)).id
            val avatar = media.upload(id, MediaKind.AVATAR, avatar(user))
            profiles.update(id, UpdateProfileRequest(bio = user.bio, avatarMediaId = avatar.id.toString()))
            user.username to id
        }.toMap()

        val postIds = mutableListOf<Pair<Uuid, String>>() // post id to author username
        demo.forEach { user ->
            val author = ids.getValue(user.username)
            user.captions.forEachIndexed { index, caption ->
                // Each account's first post is a 3-photo carousel; the rest are single photos.
                val count = if (index == 0) 3 else 1
                val uploads = (0 until count).map { item ->
                    media.upload(author, MediaKind.POST, photo(user.hue + index * 0.07f + item * 0.18f)).id.toString()
                }
                val postId = Uuid.random()
                posts.create(author, postId, CreatePostRequest(caption = caption, mediaIds = uploads))
                postIds += postId to user.username
            }
        }

        // Everyone follows a few people, so each feed has content and each profile has followers.
        demo.forEachIndexed { i, user ->
            (1..3).map { demo[(i + it) % demo.size].username }.forEach { social.follow(ids.getValue(user.username), it) }
        }

        // Likes and comments from followers.
        val comments = listOf("Love this!", "So good 😍", "Where is this?", "Need the recipe", "Goals.", "Beautiful colours")
        postIds.forEach { (postId, author) ->
            demo.filter { it.username != author }.shuffled(random).take(random.nextInt(1, 5)).forEach { fan ->
                engagement.setLiked(ids.getValue(fan.username), postId, liked = true)
            }
            demo.filter { it.username != author }.shuffled(random).take(random.nextInt(0, 3)).forEach { fan ->
                engagement.addComment(ids.getValue(fan.username), postId, Uuid.random(), comments.random(random))
            }
        }

        // A conversation to open from the inbox.
        val maya = ids.getValue("maya.travels")
        val leo = ids.getValue("leo.bakes")
        val conversation = Uuid.parse(chat.open(maya, "leo.bakes").id)
        listOf(
            maya to "Your cinnamon knots look unreal",
            leo to "Thanks! Saving you some on Saturday",
            maya to "Deal. I'll bring photos from Lisbon",
        ).forEach { (sender, body) -> chat.send(sender, conversation, Uuid.random(), body) }

        println("Seeded ${demo.size} demo accounts and ${postIds.size} posts. Sign in by phone: maya.travels is ${phoneFor(0)}")
    }

    private fun avatar(user: DemoUser): ByteArray = render(512, 512) { g, w, h ->
        g.paint = GradientPaint(0f, 0f, Color.getHSBColor(user.hue, 0.55f, 0.95f), w.toFloat(), h.toFloat(), Color.getHSBColor(user.hue + 0.1f, 0.7f, 0.6f))
        g.fillRect(0, 0, w, h)
        g.color = Color.WHITE
        g.font = Font(Font.SANS_SERIF, Font.BOLD, 240)
        val initial = user.name.first().toString()
        val metrics = g.fontMetrics
        g.drawString(initial, (w - metrics.stringWidth(initial)) / 2, (h - metrics.height) / 2 + metrics.ascent)
    }

    /** An abstract "photo": a soft gradient sky with translucent circles, different for every post. */
    private fun photo(hue: Float): ByteArray = render(1080, 1350) { g, w, h ->
        g.paint = GradientPaint(0f, 0f, Color.getHSBColor(hue, 0.45f, 0.98f), 0f, h.toFloat(), Color.getHSBColor(hue + 0.12f, 0.8f, 0.45f))
        g.fillRect(0, 0, w, h)
        repeat(7) {
            g.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, random.nextDouble(0.15, 0.45).toFloat())
            g.color = Color.getHSBColor(hue + random.nextDouble(-0.15, 0.15).toFloat(), 0.5f, 1f)
            val size = random.nextInt(160, 620)
            g.fillOval(random.nextInt(-100, w - 100), random.nextInt(-100, h - 100), size, size)
        }
    }

    private fun render(width: Int, height: Int, draw: (java.awt.Graphics2D, Int, Int) -> Unit): ByteArray {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val g = image.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        draw(g, width, height)
        g.dispose()
        return ByteArrayOutputStream().also { ImageIO.write(image, "jpg", it) }.toByteArray()
    }

    companion object {
        /** Demo numbers in the US fictional range: +1 555-0101 … +1 555-0106 (area code 201). */
        fun phoneFor(index: Int) = "+1201555%04d".format(101 + index)
    }
}
