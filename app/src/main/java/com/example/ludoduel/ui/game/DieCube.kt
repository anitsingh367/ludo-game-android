package com.example.ludoduel.ui.game

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** A point or direction in 3D (math axes: x right, y up, z towards the viewer). */
data class Vec3(val x: Float, val y: Float, val z: Float) {
    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun times(k: Float) = Vec3(x * k, y * k, z * k)
    infix fun dot(o: Vec3) = x * o.x + y * o.y + z * o.z
    fun normalized(): Vec3 = sqrt(this dot this).let { Vec3(x / it, y / it, z / it) }
}

/** One face of the die: its value, outward normal and the two in-face directions (u right, v up). */
data class CubeFace(val value: Int, val normal: Vec3, val u: Vec3, val v: Vec3)

/**
 * The geometry of a real six-sided die (pure Kotlin, unit-tested): a cube of half-size 1, rotated
 * with rotation matrices about X, then Y, then Z, seen through a small fixed tilt (so the top and a
 * side are visible) and projected with perspective. Opposite faces add up to 7, like a real die.
 */
object DieCube {
    val faces = listOf(
        CubeFace(1, Vec3(0f, 0f, 1f), Vec3(1f, 0f, 0f), Vec3(0f, 1f, 0f)),
        CubeFace(6, Vec3(0f, 0f, -1f), Vec3(-1f, 0f, 0f), Vec3(0f, 1f, 0f)),
        CubeFace(3, Vec3(1f, 0f, 0f), Vec3(0f, 0f, -1f), Vec3(0f, 1f, 0f)),
        CubeFace(4, Vec3(-1f, 0f, 0f), Vec3(0f, 0f, 1f), Vec3(0f, 1f, 0f)),
        CubeFace(2, Vec3(0f, 1f, 0f), Vec3(1f, 0f, 0f), Vec3(0f, 0f, -1f)),
        CubeFace(5, Vec3(0f, -1f, 0f), Vec3(1f, 0f, 0f), Vec3(0f, 0f, 1f)),
    )

    /** The 8 corners of the cube. */
    val corners: List<Vec3> = listOf(-1f, 1f).flatMap { x -> listOf(-1f, 1f).flatMap { y -> listOf(-1f, 1f).map { z -> Vec3(x, y, z) } } }

    /** Rotation (degrees about X, Y, Z) that turns [value] towards the viewer. */
    fun restAngles(value: Int): Triple<Float, Float, Float> = when (value) {
        1 -> Triple(0f, 0f, 0f)
        6 -> Triple(0f, 180f, 0f)
        3 -> Triple(0f, -90f, 0f)
        4 -> Triple(0f, 90f, 0f)
        2 -> Triple(90f, 0f, 0f)
        5 -> Triple(-90f, 0f, 0f)
        else -> error("A die has no face $value")
    }

    /** The fixed viewing tilt: looking a little from above and from the right. */
    const val VIEW_TILT_X = -24f
    const val VIEW_TILT_Y = 20f

    /** Light from the top left, slightly in front. */
    val light = Vec3(-0.45f, 0.7f, 0.6f).normalized()

    /** Rotates [p] about X, then Y, then Z (degrees), then applies the viewing tilt. */
    fun transform(p: Vec3, ax: Float, ay: Float, az: Float): Vec3 {
        var v = rotX(p, ax)
        v = rotY(v, ay)
        v = rotZ(v, az)
        v = rotY(v, VIEW_TILT_Y)
        return rotX(v, VIEW_TILT_X)
    }

    /** Which value faces the viewer most directly after rotating by (ax, ay, az). */
    fun frontFace(ax: Float, ay: Float, az: Float): Int =
        faces.maxBy { transform(it.normal, ax, ay, az).z }.value

    /**
     * Perspective projection to 2D (x right, y down), in cube half-sizes, for a camera on the z axis
     * at [CAMERA_DISTANCE].
     */
    fun project(p: Vec3): Pair<Float, Float> {
        val k = CAMERA_DISTANCE / (CAMERA_DISTANCE - p.z)
        return p.x * k to -p.y * k
    }

    /** A face is visible when it points towards the camera. */
    fun isVisible(center: Vec3, normal: Vec3): Boolean = normal dot Vec3(-center.x, -center.y, CAMERA_DISTANCE - center.z) > 0f

    const val CAMERA_DISTANCE = 6f

    /** Pip positions (u, v in -1..1) for each value. */
    fun pips(value: Int): List<Pair<Float, Float>> {
        val a = 0.5f
        return when (value) {
            1 -> listOf(0f to 0f)
            2 -> listOf(-a to a, a to -a)
            3 -> listOf(-a to a, 0f to 0f, a to -a)
            4 -> listOf(-a to a, a to a, -a to -a, a to -a)
            5 -> listOf(-a to a, a to a, 0f to 0f, -a to -a, a to -a)
            else -> listOf(-a to a, a to a, -a to 0f, a to 0f, -a to -a, a to -a)
        }
    }

    private fun rotX(p: Vec3, deg: Float): Vec3 {
        val r = Math.toRadians(deg.toDouble()); val c = cos(r).toFloat(); val s = sin(r).toFloat()
        return Vec3(p.x, p.y * c - p.z * s, p.y * s + p.z * c)
    }

    private fun rotY(p: Vec3, deg: Float): Vec3 {
        val r = Math.toRadians(deg.toDouble()); val c = cos(r).toFloat(); val s = sin(r).toFloat()
        return Vec3(p.x * c + p.z * s, p.y, -p.x * s + p.z * c)
    }

    private fun rotZ(p: Vec3, deg: Float): Vec3 {
        val r = Math.toRadians(deg.toDouble()); val c = cos(r).toFloat(); val s = sin(r).toFloat()
        return Vec3(p.x * c - p.y * s, p.x * s + p.y * c, p.z)
    }
}
