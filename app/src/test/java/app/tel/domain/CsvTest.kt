package app.tel.domain

import app.tel.domain.calc.CalculationEngine
import app.tel.domain.csv.Csv
import app.tel.domain.csv.TelCsv
import app.tel.domain.model.FuelType
import app.tel.domain.model.Vehicle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class CsvTest {
    private val zone: ZoneId = ZoneId.of("Asia/Kolkata")

    @Test fun quotesAndParsesSpecialCharacters() {
        val fields = listOf("plain", "with, comma", "with \"quote\"", "multi\nline", "")
        val parsed = Csv.parse(Csv.line(fields) + "\r\n" + Csv.line(listOf("a", "b")) + "\n")
        assertEquals(fields, parsed[0])
        assertEquals(listOf("a", "b"), parsed[1])
    }

    @Test fun ignoresBomAndBlankLines() {
        val parsed = Csv.parse("﻿a,b\n\n,\nc,d")
        assertEquals(listOf(listOf("a", "b"), listOf("c", "d")), parsed)
    }

    private val car = Vehicle("car", "My Car", make = "Maruti", model = "Swift", year = 2019, fuelType = FuelType.PETROL, currencyCode = "INR", createdAt = 5, updatedAt = 6)
    private val spare = Vehicle("spare", "Spare, \"old\" one", fuelType = FuelType.DIESEL, currencyCode = "INR", createdAt = 7, updatedAt = 7)

    @Test fun roundTripKeepsEverything() {
        val a = event("143140", added = "36.35", price = "112.41", total = "4086.10", day = 20000).copy(notes = "Shell, NH44", createdAt = 11, updatedAt = 12)
        val b = event("143640", added = "38.06", price = "110.68", total = "4212.48", full = false, missed = true, day = 20010).copy(createdAt = 13, updatedAt = 14)
        val events = listOf(a, b)
        val text = TelCsv.encode(listOf(car, spare), events, CalculationEngine().compute(events), zone)

        val d = TelCsv.decode(text, zone)
        assertTrue(d.skipped.isEmpty())
        assertEquals(listOf(car.copy(updatedAt = d.vehicles[0].updatedAt), spare.copy(updatedAt = d.vehicles[1].updatedAt)).map { it.copy(createdAt = 0, updatedAt = 0) },
            d.vehicles.map { it.copy(createdAt = 0, updatedAt = 0) })
        val byId = d.events.associateBy { it.id }
        val ra = byId.getValue(a.id)
        assertEquals(a.timestamp, ra.timestamp)
        assertEquals(0, bd("4086.10").compareTo(ra.totalCost))
        assertEquals(0, bd("143140").compareTo(ra.odometerKm))
        assertEquals("Shell, NH44", ra.notes)
        assertEquals(11L, ra.createdAt)
        val rb = byId.getValue(b.id)
        assertFalse(rb.filledToFull)
        assertTrue(rb.missedPrevious)
    }

    @Test fun headerAndDateAreReadable() {
        val a = event("143140", added = "36.35", total = "4086.10", day = 20000)
        val text = TelCsv.encode(listOf(car), listOf(a), emptyMap(), zone)
        val lines = text.trim().lines()
        assertTrue(lines[0].startsWith("date,vehicle,odometer_km,amount,currency,rate_per_litre,volume_litres,tank_full"))
        // day 20000 = 2024-10-04 00:00 UTC = 05:30 in India
        assertTrue(lines[1], lines[1].startsWith("2024-10-04 05:30:00+05:30,My Car,143140,4086.1,INR,,36.35,yes"))
    }

    @Test fun acceptsSpreadsheetEdits() {
        // Columns reordered, a row added by hand without ids, a European-style decimal, a date without seconds.
        val text = """
            vehicle,date,amount,volume_litres,odometer_km,tank_full
            My Car,2026-10-20 09:15,"2,000.00","17,79",150000,yes
            My Car,25/10/2026 08:00,1500,13.3,150310,no
            My Car,not a date,1,1,1,yes
        """.trimIndent()
        val d = TelCsv.decode(text, zone)
        assertEquals(1, d.vehicles.size)
        assertEquals(2, d.events.size)
        assertEquals(1, d.skipped.size)
        assertEquals(0, bd("2000").compareTo(d.events[0].totalCost))
        assertEquals(0, bd("17.79").compareTo(d.events[0].fuelAddedL))
        assertFalse(d.events[1].filledToFull)
        assertEquals(d.events[0].vehicleId, d.events[1].vehicleId)
    }

    @Test fun rejectsOtherCsv() {
        val ok = try { TelCsv.decode("name,email\na,b", zone); false } catch (e: TelCsv.FormatException) { true }
        assertTrue(ok)
    }
}
