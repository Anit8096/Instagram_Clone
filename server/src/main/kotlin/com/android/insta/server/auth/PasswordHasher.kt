package com.android.insta.server.auth

import com.password4j.Argon2Function
import com.password4j.Password
import com.password4j.types.Argon2

interface PasswordHasher {
    fun hash(raw: String): String
    fun verify(raw: String, hash: String): Boolean
}

/**
 * Argon2id with OWASP's minimum recommended parameters (19 MiB, 2 iterations, 1 lane).
 * Parameters are encoded in the hash, so verification keeps working if they are raised later.
 */
class Argon2PasswordHasher : PasswordHasher {
    private val function = Argon2Function.getInstance(19_456, 2, 1, 32, Argon2.ID)

    override fun hash(raw: String): String = Password.hash(raw).addRandomSalt(16).with(function).result

    override fun verify(raw: String, hash: String): Boolean =
        runCatching { Password.check(raw, hash).with(Argon2Function.getInstanceFromHash(hash)) }.getOrDefault(false)
}
