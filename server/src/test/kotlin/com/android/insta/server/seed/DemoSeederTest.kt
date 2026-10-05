package com.android.insta.server.seed

import com.android.insta.server.db.DatabaseFactory
import com.android.insta.server.db.Follows
import com.android.insta.server.db.Media
import com.android.insta.server.db.Messages
import com.android.insta.server.db.Posts
import com.android.insta.server.db.Users
import com.android.insta.server.di.appModule
import com.android.insta.server.support.IntegrationTest
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.junit.jupiter.api.Test
import org.koin.dsl.koinApplication
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DemoSeederTest : IntegrationTest() {

    @Test
    fun `seeds a lived-in demo once and is idempotent`() {
        System.setProperty("java.awt.headless", "true")
        val config = testConfig()
        val database = DatabaseFactory.connect(config.db)
        val koin = koinApplication { modules(appModule(config, database.exposed)) }.koin
        try {
            val seeder = DemoSeeder(koin.get(), koin.get(), koin.get(), koin.get(), koin.get(), koin.get(), koin.get())
            runBlocking { repeat(2) { seeder.seed() } } // second run must be a no-op

            transaction(database.exposed) {
                assertEquals(6, Users.selectAll().count())
                assertEquals(18, Posts.selectAll().count())
                assertEquals(18, Follows.selectAll().count())
                assertEquals(3, Messages.selectAll().count())
                assertEquals(24, Media.selectAll().count()) // 18 photos + 6 avatars
                assertTrue(Users.selectAll().all { it[Users.avatarMediaId] != null })
                // Demo users sign in by phone, so every seeded number must pass the same validation as the API.
                Users.selectAll().forEach { assertEquals(it[Users.phone], com.android.insta.server.auth.PhoneNumbers.normalize(it[Users.phone])) }
                assertTrue(Posts.selectAll().sumOf { it[Posts.likeCount] } > 0)
            }
        } finally {
            koin.close()
            database.close()
        }
    }
}
