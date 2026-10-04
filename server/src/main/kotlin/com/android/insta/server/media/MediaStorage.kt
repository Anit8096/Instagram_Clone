package com.android.insta.server.media

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** Where processed images live. Paths are relative keys; swap in an S3 implementation later. */
interface MediaStorage {
    fun write(key: String, bytes: ByteArray)

    /** Local file for [key], or null if it doesn't exist. */
    fun resolve(key: String): Path?

    fun delete(keys: Collection<String>)
}

class LocalDiskMediaStorage(root: String) : MediaStorage {
    private val root: Path = Path.of(root).toAbsolutePath().normalize().also { Files.createDirectories(it) }

    override fun write(key: String, bytes: ByteArray) {
        val target = pathFor(key)
        Files.createDirectories(target.parent)
        // Write-then-move so readers never see a half-written file.
        val temp = Files.createTempFile(target.parent, ".upload", ".tmp")
        try {
            Files.write(temp, bytes)
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    override fun resolve(key: String): Path? = pathFor(key).takeIf { Files.isRegularFile(it) }

    override fun delete(keys: Collection<String>) {
        keys.forEach { runCatching { Files.deleteIfExists(pathFor(it)) } }
    }

    private fun pathFor(key: String): Path {
        val path = root.resolve(key).normalize()
        require(path.startsWith(root)) { "Media key escapes storage root: $key" }
        return path
    }
}
