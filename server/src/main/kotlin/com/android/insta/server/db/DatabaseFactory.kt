package com.android.insta.server.db

import com.android.insta.server.config.DbConfig
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.jdbc.Database

class AppDatabase(val exposed: Database, private val dataSource: HikariDataSource) {
    fun close() = dataSource.close()
}

object DatabaseFactory {
    fun connect(config: DbConfig): AppDatabase {
        val dataSource = HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = config.url
                username = config.user
                password = config.password
                maximumPoolSize = config.maxPoolSize
                isAutoCommit = false
                transactionIsolation = "TRANSACTION_READ_COMMITTED"
                poolName = "insta-db"
            },
        )
        Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .load()
            .migrate()
        return AppDatabase(Database.connect(dataSource), dataSource)
    }
}
