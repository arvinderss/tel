package app.tel.ocr

import app.tel.domain.model.ImageType
import app.tel.domain.ocr.OcrField
import app.tel.domain.ocr.OcrLine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.math.BigDecimal

class OcrServiceTest {
    private class FakeReader(private val lines: List<String>, private val sharp: Double = 100.0, private val fail: Boolean = false) : TextReader {
        override suspend fun read(file: File): ReadResult {
            if (fail) error("engine crashed")
            return ReadResult(lines.mapIndexed { i, t -> OcrLine(t, null, "original", i) }, sharp, 120.0)
        }
    }

    @Test fun pumpPhotoIsParsed() = runBlocking {
        val r = OcrService(FakeReader(listOf("4086.10", "₹", "36.35", "Litres", "112.41", "₹/Lit", "753.5", "Density in Kg / m3")))
            .process(ImageType.PUMP_METER, File("x"))
        assertEquals(BigDecimal("36.35"), r[OcrField.FUEL_QUANTITY]!!.best!!.value)
        assertNull(r.problem)
    }

    @Test fun engineFailureNeverThrows() = runBlocking {
        val r = OcrService(FakeReader(emptyList(), fail = true)).process(ImageType.BEFORE_HUD, File("x"))
        assertNotNull(r.problem)
    }

    @Test fun blurryPhotoGetsHint() = runBlocking {
        val r = OcrService(FakeReader(listOf("---"), sharp = 2.0)).process(ImageType.AFTER_HUD, File("x"))
        assertEquals(true, r.problem!!.contains("difficult to read"))
    }
}
