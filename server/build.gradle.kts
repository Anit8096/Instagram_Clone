import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

group = "com.android.insta"
version = "0.1.0"

application {
    mainClass = "com.android.insta.server.ApplicationKt"
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_21
        optIn.add("kotlin.uuid.ExperimentalUuidApi")
        optIn.add("io.lettuce.core.ExperimentalLettuceCoroutinesApi")
    }
}

dependencies {
    implementation(platform(libs.ktor.bom))
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.json)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.server.call.id)
    implementation(libs.ktor.server.auth)
    implementation(libs.ktor.server.auth.jwt)
    implementation(libs.ktor.server.websockets)
    implementation(libs.ktor.server.swagger)

    implementation(platform(libs.koin.bom))
    implementation(libs.koin.ktor)
    implementation(libs.koin.logger.slf4j)

    implementation(platform(libs.exposed.bom))
    implementation(libs.exposed.core)
    implementation(libs.exposed.jdbc)
    implementation(libs.exposed.java.time)

    implementation(libs.flyway.core)
    runtimeOnly(libs.flyway.postgresql)
    implementation(libs.hikari)
    runtimeOnly(libs.postgresql)
    implementation(libs.libphonenumber)
    // Redis: job queue (Streams), rate limits, cache.
    implementation(libs.lettuce)
    implementation(libs.kotlinx.coroutines.reactive)
    implementation(libs.thumbnailator)
    // FCM push (FcmPushSender); unused at runtime unless FIREBASE_CREDENTIALS_FILE is set.
    implementation(libs.firebase.admin)
    runtimeOnly(libs.twelvemonkeys.jpeg)
    runtimeOnly(libs.twelvemonkeys.webp)
    runtimeOnly(libs.logback)

    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.content.negotiation)
    testImplementation(kotlin("test-junit5"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
    testImplementation(platform(libs.testcontainers.bom))
    testImplementation(libs.testcontainers.postgresql)
}

// Demo data for local runs (same env vars as the server). In Docker: see Seed.kt.
tasks.register<JavaExec>("seed") {
    group = "application"
    description = "Creates demo accounts, posts, follows, likes, comments and a conversation."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "com.android.insta.server.seed.SeedKt"
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
