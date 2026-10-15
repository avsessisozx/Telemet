package io.github.avsessisozx.telemet.sensors

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

class RideDynamicsTest {

    /**
     * Sabit yarıçaplı virajı canlandırır: 72 km/sa'te 100 m yarıçap.
     * Gerçek yatış atan(v²/(r·g)) ≈ 22,2°. Telefon motorla birlikte yattığı için jiroskop
     * gerçek dönüş hızının cos(yatış) katını ölçer.
     */
    @Test
    fun leanFromSteadyCorner() {
        val v = 20.0
        val r = 100.0
        val trueYaw = v / r
        val trueLean = Math.toDegrees(atan(v * v / (r * RideDynamics.G)))
        val measured = trueYaw * cos(Math.toRadians(trueLean))
        val lean = RideDynamics.leanDeg(v, measured)
        assertEquals(trueLean, lean, 1.0)
        assertTrue(lean > 0) // sola dönüş pozitif
        assertEquals(-lean, RideDynamics.leanDeg(v, -measured), 1e-9)
    }

    @Test
    fun hardCornerAt45Degrees() {
        // 45° yatış: v·ω = g
        val v = 25.0
        val trueYaw = RideDynamics.G / v
        val measured = trueYaw * cos(Math.toRadians(45.0))
        assertEquals(45.0, RideDynamics.leanDeg(v, measured), 2.0)
        assertEquals(1.0, RideDynamics.lateralG(v, measured, 45.0), 0.05)
    }

    @Test
    fun noLeanWhenSlowOrStraight() {
        assertEquals(0.0, RideDynamics.leanDeg(1.0, 1.0), 0.0)
        assertEquals(0.0, RideDynamics.leanDeg(30.0, 0.0), 1e-9)
        assertEquals(0.0, RideDynamics.lateralG(2.0, 1.0, 0.0), 0.0)
    }

    @Test
    fun leanIsClamped() {
        assertEquals(RideDynamics.MAX_LEAN_DEG, RideDynamics.leanDeg(60.0, 5.0), 0.0)
    }

    @Test
    fun brakingAndAcceleration() {
        // 100 km/sa'ten 1 sn'de 80 km/sa'e: ~ -0,57 g
        val g = RideDynamics.longitudinalG(100 / 3.6, 80 / 3.6, 1.0)
        assertEquals(-0.566, g, 0.01)
        assertEquals(0.0, RideDynamics.longitudinalG(10.0, 20.0, 10.0), 0.0) // uzun kesinti sayılmaz
        assertEquals(0.5, RideDynamics.totalG(0.3, 0.4), 1e-9)
    }

    @Test
    fun projectionOnUpAxis() {
        // Telefon gidonda 30° geriye yatık dursa bile dönüş hızı doğru eksenden okunur.
        val tilt = Math.toRadians(30.0)
        val gravity = floatArrayOf(0f, (9.81 * cos(tilt)).toFloat(), (9.81 * sin(tilt)).toFloat())
        val up = RideDynamics.normalize(gravity)!!
        val yaw = 0.5
        val gyro = floatArrayOf(0f, (yaw * cos(tilt)).toFloat(), (yaw * sin(tilt)).toFloat())
        assertEquals(yaw, RideDynamics.dot(gyro, up), 1e-6)
        assertEquals(0.0, tan(0.0), 0.0)
    }
}
