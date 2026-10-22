package app.tel.ocr

/**
 * Pure image-preprocessing operations on 8-bit grayscale buffers (row-major, 0 = black).
 * No Android types, so they are unit-tested on the JVM.
 *
 * Used to make LCD / seven-segment displays easier for the OCR engine:
 * contrast stretch -> sharpen -> threshold -> (optional) dilate to join segment gaps.
 */
object ImageOps {

    /** ARGB pixels -> luminance (Rec. 601). */
    fun grayscale(argb: IntArray): IntArray = IntArray(argb.size) { i ->
        val p = argb[i]
        val r = (p shr 16) and 0xFF; val g = (p shr 8) and 0xFF; val b = p and 0xFF
        (r * 299 + g * 587 + b * 114) / 1000
    }

    fun toArgb(gray: IntArray): IntArray = IntArray(gray.size) { i ->
        val v = gray[i].coerceIn(0, 255)
        (0xFF shl 24) or (v shl 16) or (v shl 8) or v
    }

    fun histogram(gray: IntArray): IntArray {
        val h = IntArray(256)
        for (v in gray) h[v.coerceIn(0, 255)]++
        return h
    }

    /** Linear stretch so the [lowPct]..[highPct] percentiles map to 0..255 (robust to glare). */
    fun stretchContrast(gray: IntArray, lowPct: Double = 0.01, highPct: Double = 0.99): IntArray {
        if (gray.isEmpty()) return gray
        val h = histogram(gray)
        val n = gray.size
        fun percentile(p: Double): Int {
            val target = (n * p).toLong()
            var acc = 0L
            for (i in 0..255) { acc += h[i]; if (acc > target) return i }
            return 255
        }
        val lo = percentile(lowPct)
        val hi = percentile(highPct)
        if (hi <= lo) return gray.copyOf()
        val range = (hi - lo).toDouble()
        return IntArray(n) { i -> (((gray[i] - lo) / range) * 255.0).toInt().coerceIn(0, 255) }
    }

    /** 3×3 box blur, used for noise reduction before thresholding. */
    fun boxBlur(gray: IntArray, w: Int, h: Int): IntArray {
        val out = IntArray(gray.size)
        for (y in 0 until h) for (x in 0 until w) {
            var sum = 0; var cnt = 0
            for (dy in -1..1) {
                val yy = y + dy; if (yy < 0 || yy >= h) continue
                for (dx in -1..1) {
                    val xx = x + dx; if (xx < 0 || xx >= w) continue
                    sum += gray[yy * w + xx]; cnt++
                }
            }
            out[y * w + x] = sum / cnt
        }
        return out
    }

    /** Unsharp-style 3×3 sharpen kernel [0,-1,0; -1,5,-1; 0,-1,0]. */
    fun sharpen(gray: IntArray, w: Int, h: Int): IntArray {
        val out = gray.copyOf()
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            val v = 5 * gray[i] - gray[i - 1] - gray[i + 1] - gray[i - w] - gray[i + w]
            out[i] = v.coerceIn(0, 255)
        }
        return out
    }

    /** Otsu's method: the threshold that best separates the histogram into two classes. */
    fun otsuThreshold(gray: IntArray): Int {
        val h = histogram(gray)
        val total = gray.size.toDouble()
        var sumAll = 0.0
        for (i in 0..255) sumAll += i * h[i].toDouble()
        var sumB = 0.0; var wB = 0.0; var best = 0.0; var threshold = 127
        for (t in 0..255) {
            wB += h[t]
            if (wB == 0.0) continue
            val wF = total - wB
            if (wF == 0.0) break
            sumB += t * h[t].toDouble()
            val mB = sumB / wB
            val mF = (sumAll - sumB) / wF
            val between = wB * wF * (mB - mF) * (mB - mF)
            if (between > best) { best = between; threshold = t }
        }
        return threshold
    }

    /** Binary image with dark text on white. If [invert], light pixels become the text. */
    fun threshold(gray: IntArray, t: Int, invert: Boolean = false): IntArray =
        IntArray(gray.size) { i -> if ((gray[i] > t) != invert) 255 else 0 }

    /**
     * Grows dark (text) pixels by [radius]. Seven-segment digits have gaps between segments;
     * closing them helps OCR treat each digit as one glyph.
     */
    fun dilateDark(bin: IntArray, w: Int, h: Int, radius: Int): IntArray {
        if (radius <= 0) return bin
        // Separable: horizontal then vertical min-filter.
        val tmp = IntArray(bin.size)
        for (y in 0 until h) for (x in 0 until w) {
            var m = 255
            for (dx in -radius..radius) { val xx = x + dx; if (xx in 0 until w && bin[y * w + xx] < m) m = bin[y * w + xx] }
            tmp[y * w + x] = m
        }
        val out = IntArray(bin.size)
        for (y in 0 until h) for (x in 0 until w) {
            var m = 255
            for (dy in -radius..radius) { val yy = y + dy; if (yy in 0 until h && tmp[yy * w + x] < m) m = tmp[yy * w + x] }
            out[y * w + x] = m
        }
        return out
    }

    /** Variance of the Laplacian: low values mean a blurry photo. */
    fun sharpness(gray: IntArray, w: Int, h: Int): Double {
        if (w < 3 || h < 3) return 0.0
        var sum = 0.0; var sumSq = 0.0; var n = 0
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            val lap = (4 * gray[i] - gray[i - 1] - gray[i + 1] - gray[i - w] - gray[i + w]).toDouble()
            sum += lap; sumSq += lap * lap; n++
        }
        val mean = sum / n
        return sumSq / n - mean * mean
    }

    /** Mean brightness 0..255. */
    fun mean(gray: IntArray): Double = if (gray.isEmpty()) 0.0 else gray.sumOf { it.toLong() }.toDouble() / gray.size
}
