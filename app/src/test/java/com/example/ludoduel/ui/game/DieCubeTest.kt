package com.example.ludoduel.ui.game

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class DieCubeTest {

    @Test fun `opposite faces add up to 7`() {
        for (face in DieCube.faces) {
            val opposite = DieCube.faces.single { (it.normal dot face.normal) < -0.99f }
            assertEquals(7, face.value + opposite.value)
        }
    }

    @Test fun `every value lands facing the viewer`() {
        for (value in 1..6) {
            val (ax, ay, az) = DieCube.restAngles(value)
            assertEquals(value, DieCube.frontFace(ax, ay, az))
        }
    }

    @Test fun `extra whole turns of tumbling still land on the confirmed value`() {
        val random = Random(3)
        repeat(500) {
            val value = random.nextInt(1, 7)
            val (ax, ay, az) = DieCube.restAngles(value)
            val spins = List(3) { (random.nextInt(-3, 4)) * 360f }
            assertEquals(value, DieCube.frontFace(ax + spins[0], ay + spins[1], az + spins[2]))
        }
    }

    @Test fun `at rest three faces are visible, including the value`() {
        for (value in 1..6) {
            val (ax, ay, az) = DieCube.restAngles(value)
            val visible = DieCube.faces.filter {
                val n = DieCube.transform(it.normal, ax, ay, az)
                DieCube.isVisible(n, n) // a face's center is its normal on a cube of half-size 1
            }
            assertEquals("value $value", 3, visible.size)
            assertTrue(visible.any { it.value == value })
        }
    }

    @Test fun `pip counts match the values`() {
        for (value in 1..6) assertEquals(value, DieCube.pips(value).size)
    }
}
