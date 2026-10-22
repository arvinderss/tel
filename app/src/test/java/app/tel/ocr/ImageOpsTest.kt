package app.tel.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageOpsTest {
    @Test fun grayscaleUsesLuminance() {
        val g = ImageOps.grayscale(intArrayOf(0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0xFF00FF00.toInt()))
        assertEquals(255, g[0]); assertEquals(0, g[1]); assertEquals(149, g[2])
    }

    @Test fun otsuSeparatesTwoLevels() {
        val img = IntArray(100) { if (it < 50) 40 else 200 }
        val t = ImageOps.otsuThreshold(img)
        assertTrue("threshold $t", t in 40 until 200)
        val bin = ImageOps.threshold(img, t)
        assertEquals(0, bin[0]); assertEquals(255, bin[99])
        assertEquals(255, ImageOps.threshold(img, t, invert = true)[0])
    }

    @Test fun contrastStretchUsesFullRange() {
        val img = IntArray(1000) { 100 + it % 50 }
        val s = ImageOps.stretchContrast(img)
        assertTrue(s.min() <= 5); assertTrue(s.max() >= 250)
    }

    @Test fun dilateClosesSegmentGap() {
        // A dark pixel with a 1-pixel white gap to the next dark pixel.
        val w = 5; val h = 1
        val img = intArrayOf(0, 255, 0, 255, 255)
        val d = ImageOps.dilateDark(img, w, h, 1)
        assertEquals(0, d[1])
    }

    @Test fun sharpnessLowerForBlurred() {
        val w = 40; val h = 40
        val checker = IntArray(w * h) { i -> if (((i % w) / 4 + (i / w) / 4) % 2 == 0) 0 else 255 }
        val blurred = ImageOps.boxBlur(ImageOps.boxBlur(checker, w, h), w, h)
        assertTrue(ImageOps.sharpness(checker, w, h) > ImageOps.sharpness(blurred, w, h) * 2)
    }
}
