package app.tel.domain.format

import app.tel.domain.model.UnitSystem
import app.tel.domain.units.Units
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.text.NumberFormat
import java.util.Currency
import java.util.Locale

/** Turns canonical values into display strings in the chosen unit system and currency. */
class Formatter(
    val system: UnitSystem,
    private val locale: Locale = Locale.getDefault(),
) {
    fun number(value: BigDecimal, decimals: Int, grouping: Boolean = true): String {
        val df = DecimalFormat.getNumberInstance(locale) as DecimalFormat
        df.isGroupingUsed = grouping
        df.minimumFractionDigits = decimals
        df.maximumFractionDigits = decimals
        df.roundingMode = RoundingMode.HALF_UP
        return df.format(value)
    }

    fun distance(km: BigDecimal?, decimals: Int = 0): String =
        km?.let { "${number(Units.kmToDisplay(it, system), decimals)} ${Units.distanceUnit(system)}" } ?: "—"

    fun volume(l: BigDecimal?, decimals: Int = 2): String =
        l?.let { "${number(Units.litresToDisplay(it, system), decimals)} ${Units.volumeUnit(system)}" } ?: "—"

    fun economy(kmPerL: BigDecimal?): String =
        kmPerL?.let { "${number(Units.economyToDisplay(it, system), 1)} ${Units.economyUnit(system)}" } ?: "—"

    fun consumptionPer100(kmPerL: BigDecimal?): String =
        kmPerL?.let { Units.litresPer100Km(it) }?.let { "${number(it, 1)} L/100 km" } ?: "—"

    fun percent(p: BigDecimal?): String = p?.let { "${number(it, if (it.stripTrailingZeros().scale() > 0) 1 else 0)} %" } ?: "—"

    fun money(amount: BigDecimal?, currencyCode: String, decimals: Int? = null): String {
        if (amount == null) return "—"
        val nf = NumberFormat.getCurrencyInstance(locale)
        val currency = runCatching { Currency.getInstance(currencyCode) }.getOrNull()
        if (currency != null) {
            nf.currency = currency
            (nf as? DecimalFormat)?.let { df ->
                val syms = DecimalFormatSymbols.getInstance(locale)
                syms.currencySymbol = CurrencyInfo.symbol(currencyCode)
                df.decimalFormatSymbols = syms
            }
        }
        val d = decimals ?: currency?.defaultFractionDigits?.coerceAtLeast(0) ?: 2
        nf.minimumFractionDigits = d
        nf.maximumFractionDigits = d
        nf.roundingMode = RoundingMode.HALF_UP
        return nf.format(amount)
    }

    fun pricePerVolume(pricePerL: BigDecimal?, currencyCode: String): String =
        pricePerL?.let { "${money(Units.pricePerLToDisplay(it, system), currencyCode, 2)}/${Units.volumeUnit(system)}" } ?: "—"

    fun costPerDistance(costPerKm: BigDecimal?, currencyCode: String): String =
        costPerKm?.let { "${money(Units.costPerKmToDisplay(it, system), currencyCode, 2)}/${Units.distanceUnit(system)}" } ?: "—"
}

object CurrencyInfo {
    /** Currencies offered in the picker. Any valid ISO 4217 code is accepted. */
    val common = listOf("INR", "USD", "EUR", "GBP", "AED", "CAD", "AUD", "SGD", "JPY", "CHF", "SAR", "NZD", "ZAR", "PKR", "BDT", "LKR", "NPR")

    private val symbols = mapOf(
        "INR" to "₹", "USD" to "$", "EUR" to "€", "GBP" to "£", "JPY" to "¥",
        "AED" to "AED ", "CAD" to "CA$", "AUD" to "A$", "SGD" to "S$", "NZD" to "NZ$",
        "CHF" to "CHF ", "SAR" to "SAR ", "ZAR" to "R", "PKR" to "Rs ", "BDT" to "৳",
        "LKR" to "Rs ", "NPR" to "Rs ",
    )

    fun symbol(code: String): String = symbols[code] ?: runCatching { Currency.getInstance(code).symbol }.getOrDefault("$code ")

    fun isValid(code: String): Boolean = code.length == 3 && runCatching { Currency.getInstance(code) }.isSuccess

    fun displayName(code: String): String = runCatching { Currency.getInstance(code).getDisplayName(Locale.getDefault()) }.getOrDefault(code)
}
