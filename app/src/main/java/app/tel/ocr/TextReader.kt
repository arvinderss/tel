package app.tel.ocr

import app.tel.domain.ocr.OcrLine
import java.io.File

/** Text recognition abstraction so the rest of the app (and tests) don't depend on ML Kit. */
interface TextReader {
    /** Returns recognised lines from several preprocessed variants of the image, plus a sharpness score. */
    suspend fun read(file: File): ReadResult
}

data class ReadResult(val lines: List<OcrLine>, val sharpness: Double, val brightness: Double)

