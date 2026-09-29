package com.example.ludoduel.engine

import java.security.SecureRandom

object Dice {
    private val random = SecureRandom()

    fun roll(): Int = random.nextInt(6) + 1
}
