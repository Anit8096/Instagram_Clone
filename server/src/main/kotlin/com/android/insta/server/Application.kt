package com.android.insta.server

import com.android.insta.server.config.AppConfig
import com.android.insta.server.db.DatabaseFactory
import com.android.insta.server.di.appModule
import com.android.insta.server.plugins.configureDocs
import com.android.insta.server.plugins.configureMonitoring
import com.android.insta.server.plugins.configureRateLimiting
import com.android.insta.server.plugins.configureRouting
import com.android.insta.server.plugins.configureSecurity
import com.android.insta.server.plugins.configureSerialization
import com.android.insta.server.plugins.configureSockets
import com.android.insta.server.plugins.configureStatusPages
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import org.koin.core.module.Module
import org.koin.ktor.plugin.KoinIsolated
import org.koin.logger.slf4jLogger

fun main() {
    val config = AppConfig.fromEnv()
    embeddedServer(Netty, port = config.port, host = "0.0.0.0") {
        module(config)
    }.start(wait = true)
}

/**
 * Wires the whole server. [extraModules] lets tests swap Koin bindings (e.g. a fake Google verifier).
 */
fun Application.module(
    config: AppConfig = AppConfig.fromEnv(),
    extraModules: List<Module> = emptyList(),
) {
    val database = DatabaseFactory.connect(config.db)
    monitor.subscribe(ApplicationStopped) { database.close() }

    install(KoinIsolated) {
        slf4jLogger()
        allowOverride(true)
        modules(listOf(appModule(config, database.exposed)) + extraModules)
    }

    configureSerialization()
    configureMonitoring()
    configureStatusPages()
    configureSecurity()
    configureRateLimiting(config.rateLimit)
    configureSockets()
    configureDocs()
    configureRouting()
}
