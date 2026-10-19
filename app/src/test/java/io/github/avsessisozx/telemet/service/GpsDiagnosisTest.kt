package io.github.avsessisozx.telemet.service

import io.github.avsessisozx.telemet.service.GpsDiagnosis.Issue
import org.junit.Assert.assertEquals
import org.junit.Test

class GpsDiagnosisTest {

    @Test
    fun `konum kapaliysa hemen uyarir`() {
        assertEquals(Issue.LOCATION_OFF, GpsDiagnosis.diagnose(false, false, 0, 0))
        assertEquals(Issue.LOCATION_OFF, GpsDiagnosis.diagnose(false, true, 0, 8))
    }

    @Test
    fun `konum varken sorun yok`() {
        assertEquals(Issue.NONE, GpsDiagnosis.diagnose(true, true, 0, 8))
        assertEquals(Issue.NONE, GpsDiagnosis.diagnose(true, true, 120_000, 0))
    }

    @Test
    fun `ilk 30 saniye beklenir`() {
        assertEquals(Issue.NONE, GpsDiagnosis.diagnose(true, false, 29_999, 0))
        assertEquals(Issue.NONE, GpsDiagnosis.diagnose(true, false, 5_000, 3))
    }

    @Test
    fun `uydu verisi yoksa sahte konum uyarisi`() {
        // Kullanıcının günlüğü: saatlerce "0 GPS fixes", GPS'ten uydu bilgisi yok
        assertEquals(Issue.NO_SATELLITES, GpsDiagnosis.diagnose(true, false, 30_000, 0))
        assertEquals(Issue.NO_SATELLITES, GpsDiagnosis.diagnose(true, false, 3_600_000, 0))
    }

    @Test
    fun `uydu gorunuyor ama konum yoksa acik hava uyarisi`() {
        assertEquals(Issue.NO_POSITION, GpsDiagnosis.diagnose(true, false, 45_000, 5))
    }
}
