package com.splitfree.domain.money

import java.math.BigDecimal
import java.text.DecimalFormatSymbols
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ExpenseInputParserTest {
    private val parser = ExpenseInputParser(Locale.US)

    @Test
    fun `money is converted without floating point rounding`() {
        assertEquals(10000L, parser.money("100", "INR"))
        assertEquals(29L, parser.money("0.29", "USD"))
        assertEquals(123456789012L, parser.money("1234567890.12", "INR"))
        assertEquals(10L, parser.money("0.1", "USD"))
    }

    @Test
    fun `currency controls zero two and three digit precision`() {
        assertEquals(50L, parser.money("50", "JPY"))
        assertEquals(5012L, parser.money("50.12", "INR"))
        assertEquals(50123L, parser.money("50.123", "KWD"))
        assertEquals(12L, parser.money("0.012", "BHD"))
        assertEquals(1000L, parser.money("1", " kwd "))
    }

    @Test
    fun `excess precision is rejected even when extra digits are zero`() {
        listOf("1.0" to "JPY", "1.001" to "INR", "1.000" to "USD", "1.0000" to "KWD").forEach { (input, currency) ->
            assertThrows(input, IllegalArgumentException::class.java) { parser.money(input, currency) }
        }
    }

    @Test
    fun `comma locale accepts both comma and dot as the decimal separator`() {
        val german = ExpenseInputParser(Locale.GERMANY)
        assertEquals(1250L, german.money("12.50", "EUR"))
        assertEquals(1250L, german.money("12,50", "EUR"))
        assertEquals(1234L, german.money("12,34", "EUR"))
        assertEquals(BigDecimal("12.5"), german.weight("12,5"))
        assertEquals(BigDecimal("12.5"), german.weight("12.5"))
    }

    @Test
    fun `when both comma and dot appear the last one is the decimal and the other is grouping`() {
        val german = ExpenseInputParser(Locale.GERMANY)
        assertEquals(123450L, german.money("1.234,50", "EUR"))
        assertEquals(123450L, german.money("1,234.50", "EUR"))
        assertEquals(123456789L, german.money("1.234.567,89", "EUR"))
        assertEquals(123450L, parser.money("1,234.50", "USD"))
        assertEquals(123450L, parser.money("1.234,50", "USD"))
    }

    @Test
    fun `a lone separator is the decimal separator regardless of locale`() {
        val german = ExpenseInputParser(Locale.GERMANY)
        // "1.234" in de_DE reads as one-point-two-three-four, which EUR cannot hold; never as 1234.
        assertThrows(IllegalArgumentException::class.java) { german.money("1.234", "EUR") }
        assertEquals(1234L, german.money("1.234", "KWD"))
        assertEquals(1234L, parser.money("12,34", "USD"))
        assertThrows(IllegalArgumentException::class.java) { parser.money("1,234", "USD") }
    }

    @Test
    fun `repeated separators and non grouping whitespace are rejected`() {
        val german = ExpenseInputParser(Locale.GERMANY)
        listOf("1.2.3", "12,5,0", "1,2,3", "1..5", "1,,5", "1.2,3.4", "1,2.3,4", "1 234,56", "1\u00a0234,56").forEach {
            assertThrows(it, IllegalArgumentException::class.java) { german.money(it, "EUR") }
            assertThrows(it, IllegalArgumentException::class.java) { parser.money(it, "USD") }
        }
    }

    @Test
    fun `locale grouping separator that is neither comma nor dot is stripped`() {
        val french = ExpenseInputParser(Locale.FRANCE)
        val grouping = DecimalFormatSymbols.getInstance(Locale.FRANCE).groupingSeparator
        assertEquals(123456L, french.money("1${grouping}234,56", "EUR"))
        assertEquals(123456L, french.money("1${grouping}234.56", "EUR"))
        assertEquals(123400L, french.money("1${grouping}234", "EUR"))
        assertThrows(IllegalArgumentException::class.java) { french.money("1${grouping}${grouping}234", "EUR") }
        assertThrows(IllegalArgumentException::class.java) { french.money("1,234${grouping}56", "EUR") }
    }

    @Test
    fun `separator hint names both accepted decimal separators`() {
        val german = assertThrows(IllegalArgumentException::class.java) {
            ExpenseInputParser(Locale.GERMANY).money("1.2.3", "EUR")
        }
        assertTrue(german.message!!, german.message!!.contains("','") && german.message!!.contains("'.'"))
        val us = assertThrows(IllegalArgumentException::class.java) { parser.money("1.2.3", "USD") }
        assertTrue(us.message!!, us.message!!.contains("'.'"))
    }

    @Test
    fun `malformed exponent sign and whitespace inputs are rejected`() {
        val malformed =
            listOf("", " ", " 1", "1 ", "+1", "-1", "1e2", "1E+2", "NaN", "Infinity", ".5", "1.", "1..5", "１２")
        malformed.forEach { input ->
            assertThrows(input, IllegalArgumentException::class.java) { parser.money(input, "USD") }
        }
    }

    @Test
    fun `long maximum is exact and overflow is rejected`() {
        assertEquals(Long.MAX_VALUE, parser.money("92233720368547758.07", "USD"))
        assertEquals(Long.MAX_VALUE, parser.money(Long.MAX_VALUE.toString(), "JPY"))
        assertThrows(IllegalArgumentException::class.java) { parser.money("92233720368547758.08", "USD") }
        assertThrows(IllegalArgumentException::class.java) { parser.money("9223372036854775808", "JPY") }
    }

    @Test
    fun `input work is bounded including weights`() {
        assertThrows(IllegalArgumentException::class.java) { parser.money("0".repeat(33), "USD") }
        assertThrows(IllegalArgumentException::class.java) { parser.weight("9".repeat(33)) }
        assertEquals(BigDecimal("9".repeat(32)), parser.weight("9".repeat(32)))
    }

    @Test
    fun `weights accept decimal shares without rounding`() {
        assertEquals(BigDecimal("12.500001"), parser.weight("12.500001"))
        assertThrows(IllegalArgumentException::class.java) { parser.weight("0.0000001") }
    }

    @Test
    fun `Arabic Egypt money and weights accept regional and ASCII digits`() {
        val locale = Locale.forLanguageTag("ar-EG")
        val regional = ExpenseInputParser(locale)
        val separator = DecimalFormatSymbols.getInstance(locale).decimalSeparator
        assertEquals(1234L, regional.money("١٢${separator}٣٤", "EGP"))
        assertEquals(12345L, regional.money("١٢${separator}٣٤٥", "KWD"))
        assertEquals(12L, regional.money("١٢", "JPY"))
        assertEquals(BigDecimal("12.500001"), regional.weight("١٢${separator}٥٠٠٠٠١"))
        assertEquals(1234L, regional.money("12${separator}34", "EGP"))
        assertEquals(BigDecimal("12.5"), regional.weight("12${separator}5"))
        assertEquals(1234L, regional.money("١2${separator}3٤", "EGP"))
    }

    @Test
    fun `Persian Iran money and weights accept regional and ASCII digits`() {
        val locale = Locale.forLanguageTag("fa-IR")
        val regional = ExpenseInputParser(locale)
        val separator = DecimalFormatSymbols.getInstance(locale).decimalSeparator
        assertEquals(1234L, regional.money("۱۲${separator}۳۴", "INR"))
        assertEquals(12345L, regional.money("۱۲${separator}۳۴۵", "KWD"))
        assertEquals(12L, regional.money("۱۲", "JPY"))
        assertEquals(BigDecimal("12.500001"), regional.weight("۱۲${separator}۵۰۰۰۰۱"))
        assertEquals(1234L, regional.money("12${separator}34", "INR"))
        assertEquals(BigDecimal("12.5"), regional.weight("12${separator}5"))
        assertEquals(1234L, regional.money("۱2${separator}3۴", "INR"))
    }

    @Test
    fun `regional inputs still reject grouping wrong separators signs exponents and excess precision`() {
        for (tag in listOf("ar-EG", "fa-IR")) {
            val locale = Locale.forLanguageTag(tag)
            val regional = ExpenseInputParser(locale)
            val symbols = DecimalFormatSymbols.getInstance(locale)
            val one = symbols.zeroDigit + 1
            val two = symbols.zeroDigit + 2
            val decimal = symbols.decimalSeparator
            val malformed = listOf(
                "$one$decimal$two.$one",
                "$one$decimal$two,$one",
                "$one$decimal$two$decimal$one",
                "$one${symbols.groupingSeparator}${symbols.groupingSeparator}$two",
                "$one $two",
                "+$one",
                "-$one",
                "${one}e$two"
            )
            malformed.forEach { input ->
                assertThrows("$tag $input", IllegalArgumentException::class.java) { regional.money(input, "INR") }
                assertThrows("$tag $input", IllegalArgumentException::class.java) { regional.weight(input) }
            }
            // ASCII separators are accepted as the decimal, and the regional grouping separator is stripped.
            assertEquals(120L, regional.money("$one.$two", "INR"))
            assertEquals(120L, regional.money("$one,$two", "INR"))
            assertEquals(1200L, regional.money("$one${symbols.groupingSeparator}$two", "INR"))
            assertEquals(122212L, regional.money("$one${symbols.groupingSeparator}$two$two$two$decimal$one$two", "INR"))
            assertThrows(IllegalArgumentException::class.java) { regional.money("$one$decimal$two", "JPY") }
            assertThrows(IllegalArgumentException::class.java) { regional.money("$one$decimal$two$two$two", "INR") }
            assertThrows(IllegalArgumentException::class.java) {
                regional.weight("$one$decimal${two.toString().repeat(7)}")
            }
        }
    }

    @Test
    fun `only locale digit range and ASCII are accepted rather than all Unicode numerals`() {
        for (tag in listOf("en-US", "ar-EG", "fa-IR")) {
            val regional = ExpenseInputParser(Locale.forLanguageTag(tag))
            for (input in listOf("１２", "१२", "²", "½", "Ⅻ")) {
                assertThrows("$tag $input", IllegalArgumentException::class.java) { regional.money(input, "INR") }
                assertThrows("$tag $input", IllegalArgumentException::class.java) { regional.weight(input) }
            }
        }
        assertThrows(IllegalArgumentException::class.java) { parser.money("١٢", "INR") }
        assertThrows(IllegalArgumentException::class.java) { parser.money("۱۲", "INR") }
        assertThrows(IllegalArgumentException::class.java) {
            ExpenseInputParser(Locale.forLanguageTag("ar-EG")).weight("۱۲")
        }
        assertThrows(IllegalArgumentException::class.java) {
            ExpenseInputParser(Locale.forLanguageTag("fa-IR")).weight("١٢")
        }
    }

    @Test
    fun `invalid and non monetary currencies are rejected`() {
        listOf("", "X", "ZZZ", "123", "XXX").forEach { currency ->
            assertThrows(currency, IllegalArgumentException::class.java) { parser.money("1", currency) }
        }
    }
}
