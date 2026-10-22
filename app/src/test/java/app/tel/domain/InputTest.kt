package app.tel.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InputTest {
    private fun p(s: String) = app.tel.domain.format.Input.parse(s)

    @Test fun parsesUserInput() {
        assertEquals(bd("1234.5"), p("1234.5"))
        assertEquals(bd("1234.5"), p("1,234.5"))
        assertEquals(bd("1234.5"), p("1234,5"))
        assertEquals(bd("52431"), p("52,431"))
        assertEquals(bd("3988.56"), p("3.988,56"))
        assertNull(p(""))
        assertNull(p("12a"))
        assertNull(p("1.2.3"))
    }

    @Test fun formatsForInput() {
        assertEquals("42.18", app.tel.domain.format.Input.format(bd("42.180000")))
        assertEquals("100", app.tel.domain.format.Input.format(bd("1E+2")))
        assertEquals("", app.tel.domain.format.Input.format(null))
        assertEquals("10.567", app.tel.domain.format.Input.format(bd("10.56688")))
    }
}
