package com.example.ludoduel.data

import java.security.SecureRandom
import kotlin.random.Random
import kotlin.random.asKotlinRandom

/** Room codes: 6 characters without look-alike characters (no 0/O/1/I/L). */
object RoomCodes {
    const val ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
    const val LENGTH = 6

    private val secureRandom = SecureRandom().asKotlinRandom()

    fun generate(random: Random = secureRandom): String =
        buildString { repeat(LENGTH) { append(ALPHABET[random.nextInt(ALPHABET.length)]) } }

    /**
     * Cleans raw user input: uppercases, drops spaces and any character that can't be in a code
     * (including the look-alikes 0, O, 1, I, L), and keeps at most [LENGTH] characters.
     */
    fun clean(input: String): String =
        input.uppercase().filter { it in ALPHABET }.take(LENGTH)

    fun isComplete(code: String): Boolean = code.length == LENGTH && code.all { it in ALPHABET }
}
