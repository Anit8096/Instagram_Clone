package com.android.insta.server.db

import com.android.insta.server.support.TestDatabase
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.sql.Connection
import java.sql.DriverManager
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

/** V6 moves every post's single photo into post_media without losing posts or media. */
class CarouselMigrationTest {

    @Test
    fun `existing single-photo posts become one-item carousels`() {
        assumeTrue(TestDatabase.dockerAvailable, "Docker is not available; skipping migration test")
        val container = TestDatabase.container
        val dbName = "migration_v6_${System.nanoTime()}"
        DriverManager.getConnection(container.jdbcUrl, container.username, container.password).use {
            it.createStatement().execute("CREATE DATABASE $dbName")
        }
        val url = container.jdbcUrl.replaceAfterLast('/', dbName)
        fun flyway(target: String) = Flyway.configure()
            .dataSource(url, container.username, container.password)
            .locations("classpath:db/migration")
            .target(target)
            .load()

        flyway("5").migrate()
        val user = UUID.randomUUID()
        val media = UUID.randomUUID()
        val post = UUID.randomUUID()
        DriverManager.getConnection(url, container.username, container.password).use { conn ->
            conn.exec("INSERT INTO users (id, username, google_sub, phone_e164) VALUES ('$user', 'old.timer', 'sub-old', '+919876500001')")
            conn.exec("INSERT INTO media (id, owner_id, kind, full_path, thumb_path, width, height) VALUES ('$media', '$user', 'post', 'a_full.jpg', 'a_thumb.jpg', 1080, 1350)")
            conn.exec("INSERT INTO posts (id, author_id, media_id, caption) VALUES ('$post', '$user', '$media', 'from v1')")
        }

        flyway("6").migrate()
        DriverManager.getConnection(url, container.username, container.password).use { conn ->
            assertEquals(listOf("$post|0|$media"), conn.rows("SELECT post_id, position, media_id FROM post_media"))
            assertEquals(listOf("post|published|from v1"), conn.rows("SELECT kind, status, caption FROM posts"))
            assertEquals(listOf("photo|ready"), conn.rows("SELECT type, status FROM media"))
            // Deleting the user still cascades cleanly through posts, post_media and media.
            conn.exec("DELETE FROM users")
            assertEquals(listOf("0"), conn.rows("SELECT (SELECT count(*) FROM posts) + (SELECT count(*) FROM post_media) + (SELECT count(*) FROM media)"))
        }
    }

    private fun Connection.exec(sql: String) {
        createStatement().use { it.execute(sql) }
    }

    private fun Connection.rows(sql: String): List<String> = createStatement().use { st ->
        st.executeQuery(sql).use { rs ->
            val columns = rs.metaData.columnCount
            generateSequence { if (rs.next()) (1..columns).joinToString("|") { rs.getString(it) } else null }.toList()
        }
    }
}
