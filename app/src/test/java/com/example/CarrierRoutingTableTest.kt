package com.example

import com.example.utils.CarrierRoutingRule
import com.example.utils.CarrierRoutingTable
import com.example.utils.MobileCarrier
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class CarrierRoutingTableTest {

    @Before
    fun setUp() {
        CarrierRoutingTable.resetToDefaults()
    }

    @Test
    fun testAllMciPrefixesRouteToMci() {
        val mciPrefixes = (0..9).map { "091$it" } + listOf("0990", "0991", "0992", "0993", "0994", "0995", "0996", "0997")
        for (prefix in mciPrefixes) {
            val phone = "${prefix}1234567"
            val res = CarrierRoutingTable.resolveRoute(phone)
            assertEquals("Prefix $prefix should be MCI", MobileCarrier.MCI, res.detectedCarrier)
            assertEquals("Prefix $prefix should target Hub MCI", MobileCarrier.MCI, res.targetHubCarrier)
            assertFalse("Prefix $prefix should not be unknown", res.isUnknownPrefix)
            assertNull(res.warning)
        }
    }

    @Test
    fun testAllIrancellPrefixesRouteToIrancell() {
        val irancellPrefixes = listOf("0930", "0933", "0935", "0936", "0937", "0938", "0939") +
                listOf("0900", "0901", "0902", "0903", "0904", "0905", "0941")
        for (prefix in irancellPrefixes) {
            val phone = "${prefix}1234567"
            val res = CarrierRoutingTable.resolveRoute(phone)
            assertEquals("Prefix $prefix should be IRANCELL", MobileCarrier.IRANCELL, res.detectedCarrier)
            assertEquals("Prefix $prefix should target Hub IRANCELL", MobileCarrier.IRANCELL, res.targetHubCarrier)
            assertFalse(res.isUnknownPrefix)
            assertNull(res.warning)
        }
    }

    @Test
    fun testRightelPrefixesRouteToHubIrancellSim() {
        val rightelPrefixes = listOf("0920", "0921", "0922", "0923")
        for (prefix in rightelPrefixes) {
            val phone = "${prefix}1234567"
            val res = CarrierRoutingTable.resolveRoute(phone)
            assertEquals("Prefix $prefix should be detected as RIGHTEL", MobileCarrier.RIGHTEL, res.detectedCarrier)
            // User requirement: RighTel -> send from Hub Irancell SIM
            assertEquals("Prefix $prefix should target Hub IRANCELL SIM", MobileCarrier.IRANCELL, res.targetHubCarrier)
            assertFalse(res.isUnknownPrefix)
            assertNull(res.warning)
        }
    }

    @Test
    fun testTaliyaAndTkcPrefixesRouteToHubMciSim() {
        // Talia (0932)
        val taliyaRes = CarrierRoutingTable.resolveRoute("09321234567")
        assertEquals(MobileCarrier.TALIYA, taliyaRes.detectedCarrier)
        assertEquals(MobileCarrier.MCI, taliyaRes.targetHubCarrier)
        assertFalse(taliyaRes.isUnknownPrefix)

        // TKC (0934)
        val tkcRes = CarrierRoutingTable.resolveRoute("09341234567")
        assertEquals(MobileCarrier.TKC, tkcRes.detectedCarrier)
        assertEquals(MobileCarrier.MCI, tkcRes.targetHubCarrier)
        assertFalse(tkcRes.isUnknownPrefix)

        // TKC with 6-digit prefix 093476
        val tkc6Res = CarrierRoutingTable.resolveRoute("09347612345")
        assertEquals(MobileCarrier.TKC, tkc6Res.detectedCarrier)
        assertEquals(MobileCarrier.MCI, tkc6Res.targetHubCarrier)
        assertFalse(tkc6Res.isUnknownPrefix)

        // Spadan (0931)
        val spadanRes = CarrierRoutingTable.resolveRoute("09311234567")
        assertEquals(MobileCarrier.SPADAN, spadanRes.detectedCarrier)
        assertEquals(MobileCarrier.MCI, spadanRes.targetHubCarrier)
        assertFalse(spadanRes.isUnknownPrefix)
    }

    @Test
    fun testMvnoPrefixesAndLongestPrefixMatching() {
        // Shatel Mobile (0998, 09981, 09982) -> Irancell Hub
        val shatelRes = CarrierRoutingTable.resolveRoute("09981234567")
        assertEquals(MobileCarrier.SHATEL, shatelRes.detectedCarrier)
        assertEquals(MobileCarrier.IRANCELL, shatelRes.targetHubCarrier)

        // LotusTel 09990 (5-digit) vs SamanTel 0999 (4-digit)
        // Longest-Prefix Match must pick LotusTel (len 5) and target Hub MCI!
        val lotusRes = CarrierRoutingTable.resolveRoute("09990123456")
        assertEquals("09990 must match LOTUSTEL via LPM", MobileCarrier.LOTUSTEL, lotusRes.detectedCarrier)
        assertEquals("LOTUSTEL must route to Hub MCI", MobileCarrier.MCI, lotusRes.targetHubCarrier)

        // Aptel 09991 & 09993 (5-digit) vs SamanTel 0999 (4-digit)
        // Longest-Prefix Match must pick Aptel (len 5) and target Hub MCI!
        val aptelRes1 = CarrierRoutingTable.resolveRoute("09991123456")
        assertEquals("09991 must match APTEL via LPM", MobileCarrier.APTEL, aptelRes1.detectedCarrier)
        assertEquals("APTEL must route to Hub MCI", MobileCarrier.MCI, aptelRes1.targetHubCarrier)

        val aptelRes3 = CarrierRoutingTable.resolveRoute("09993123456")
        assertEquals(MobileCarrier.APTEL, aptelRes3.detectedCarrier)
        assertEquals(MobileCarrier.MCI, aptelRes3.targetHubCarrier)

        // SamanTel 09999 & 09995 & 0999 fallback -> Irancell Hub
        val samanRes9 = CarrierRoutingTable.resolveRoute("09999123456")
        assertEquals(MobileCarrier.SAMANTEL, samanRes9.detectedCarrier)
        assertEquals(MobileCarrier.IRANCELL, samanRes9.targetHubCarrier)

        val samanResFallback = CarrierRoutingTable.resolveRoute("09994123456")
        assertEquals(MobileCarrier.SAMANTEL, samanResFallback.detectedCarrier)
        assertEquals(MobileCarrier.IRANCELL, samanResFallback.targetHubCarrier)

        // Fanap Mobile
        val fanapRes = CarrierRoutingTable.resolveRoute("09992123456")
        assertEquals(MobileCarrier.FANAP, fanapRes.detectedCarrier)
        assertEquals(MobileCarrier.IRANCELL, fanapRes.targetHubCarrier)

        // ArianTel
        val arianTelRes = CarrierRoutingTable.resolveRoute("09998123456")
        assertEquals(MobileCarrier.ARIANTEL, arianTelRes.detectedCarrier)
        assertEquals(MobileCarrier.IRANCELL, arianTelRes.targetHubCarrier)

        // Smartcomm
        val smartcommRes = CarrierRoutingTable.resolveRoute("09988123456")
        assertEquals(MobileCarrier.SMARTCOMM, smartcommRes.detectedCarrier)
        assertEquals(MobileCarrier.IRANCELL, smartcommRes.targetHubCarrier)

        // Tosan (09989)
        val tosanRes = CarrierRoutingTable.resolveRoute("09989123456")
        assertEquals(MobileCarrier.TOSAN, tosanRes.detectedCarrier)
        assertEquals(MobileCarrier.IRANCELL, tosanRes.targetHubCarrier)
    }

    @Test
    fun testIsKnownOperator() {
        assertTrue(CarrierRoutingTable.isKnownOperator("09121234567"))
        assertTrue(CarrierRoutingTable.isKnownOperator("09351234567"))
        assertTrue(CarrierRoutingTable.isKnownOperator("09211234567"))
        assertTrue(CarrierRoutingTable.isKnownOperator("09951234567"))
        assertTrue(CarrierRoutingTable.isKnownOperator("09971234567"))
        assertTrue(CarrierRoutingTable.isKnownOperator("09989123456"))
        assertFalse(CarrierRoutingTable.isKnownOperator("09501234567"))
        assertFalse(CarrierRoutingTable.isKnownOperator("12345"))
    }

    @Test
    fun testUnknownPrefixFallbackToDefaultAndWarns() {
        val unknownNumbers = listOf("09501234567", "09801234567", "09081234567", "")
        for (phone in unknownNumbers) {
            val res = CarrierRoutingTable.resolveRoute(phone)
            assertEquals(MobileCarrier.UNKNOWN, res.detectedCarrier)
            assertEquals(MobileCarrier.MCI, res.targetHubCarrier) // default fallback
            assertTrue(res.isUnknownPrefix)
            assertNotNull(res.warning)
        }
    }

    @Test
    fun testExtensibleCustomRuleRegistration() {
        // Register a new custom virtual operator routing to Irancell
        val customRule = CarrierRoutingRule(
            operatorName = "Test Custom MVNO",
            carrier = MobileCarrier.UNKNOWN,
            targetHubCarrier = MobileCarrier.IRANCELL,
            prefixes = setOf("0955")
        )
        CarrierRoutingTable.registerRule(customRule)

        val res = CarrierRoutingTable.resolveRoute("09551234567")
        assertEquals(MobileCarrier.IRANCELL, res.targetHubCarrier)
        assertFalse(res.isUnknownPrefix)
    }

    @Test
    fun testExtensiblePrefixAddition() {
        // Add new test prefix to MCI
        CarrierRoutingTable.addPrefixes(MobileCarrier.MCI, setOf("0949"))
        val res = CarrierRoutingTable.resolveRoute("09491234567")
        assertEquals(MobileCarrier.MCI, res.detectedCarrier)
        assertEquals(MobileCarrier.MCI, res.targetHubCarrier)
        assertFalse(res.isUnknownPrefix)
    }

    @Test
    fun testPersianDigitsAndInternationalFormatHandling() {
        // Persian digits for MCI
        val persianRes = CarrierRoutingTable.resolveRoute("۰۹۱۲۳۴۵۶۷۸۹")
        assertEquals(MobileCarrier.MCI, persianRes.detectedCarrier)
        assertEquals(MobileCarrier.MCI, persianRes.targetHubCarrier)

        // International +98 for Irancell
        val intlRes = CarrierRoutingTable.resolveRoute("+98 930 365 3432")
        assertEquals(MobileCarrier.IRANCELL, intlRes.detectedCarrier)
        assertEquals(MobileCarrier.IRANCELL, intlRes.targetHubCarrier)
    }
}
