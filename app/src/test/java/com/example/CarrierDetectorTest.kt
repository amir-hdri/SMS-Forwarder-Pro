package com.example

import com.example.utils.CarrierDetector
import com.example.utils.MobileCarrier
import org.junit.Assert.*
import org.junit.Test

class CarrierDetectorTest {

    @Test
    fun testDetectMciPrefixes() {
        assertEquals(MobileCarrier.MCI, CarrierDetector.detectCarrierFromPhone("09121234567"))
        assertEquals(MobileCarrier.MCI, CarrierDetector.detectCarrierFromPhone("+989121234567"))
        assertEquals(MobileCarrier.MCI, CarrierDetector.detectCarrierFromPhone("09901234567"))
        assertEquals(MobileCarrier.MCI, CarrierDetector.detectCarrierFromPhone("09191234567"))
    }

    @Test
    fun testDetectIrancellPrefixes() {
        assertEquals(MobileCarrier.IRANCELL, CarrierDetector.detectCarrierFromPhone("09351234567"))
        assertEquals(MobileCarrier.IRANCELL, CarrierDetector.detectCarrierFromPhone("09301234567"))
        assertEquals(MobileCarrier.IRANCELL, CarrierDetector.detectCarrierFromPhone("09001234567"))
        assertEquals(MobileCarrier.IRANCELL, CarrierDetector.detectCarrierFromPhone("09021234567"))
        assertEquals(MobileCarrier.IRANCELL, CarrierDetector.detectCarrierFromPhone("+989361234567"))
    }

    @Test
    fun testDetectRightelPrefixes() {
        assertEquals(MobileCarrier.RIGHTEL, CarrierDetector.detectCarrierFromPhone("09211234567"))
        assertEquals(MobileCarrier.RIGHTEL, CarrierDetector.detectCarrierFromPhone("09221234567"))
    }

    @Test
    fun testResolveRoutePrefersSameCarrier() {
        val mciHub = "09120000001"
        val irancellHub = "09350000001"

        // Driver with MCI -> Primary must be MCI Hub
        val mciRoute = CarrierDetector.resolveRoute("09129999999", mciHub, irancellHub)
        assertEquals(mciHub, mciRoute.primaryNumber)
        assertEquals(irancellHub, mciRoute.failoverNumber)

        // Driver with Irancell -> Primary must be Irancell Hub
        val irancellRoute = CarrierDetector.resolveRoute("09359999999", mciHub, irancellHub)
        assertEquals(irancellHub, irancellRoute.primaryNumber)
        assertEquals(mciHub, irancellRoute.failoverNumber)
    }

    @Test
    fun testResolveRouteWithSingleNumberFallback() {
        val mciHub = "09120000001"
        val route = CarrierDetector.resolveRoute("09359999999", mciHub, "")
        assertEquals(mciHub, route.primaryNumber)
        assertEquals(mciHub, route.failoverNumber)
    }

    @Test
    fun testResolveRouteDefaultsToMciWhenDriverCarrierUnknown() {
        val mciHub = "09120000001"
        val irancellHub = "09350000001"

        // Unrecognized carrier prefix -> Defaults to MCI (Hamrah-e Aval) due to superior Iranian road coverage
        val unknownRoute = CarrierDetector.resolveRoute("09501234567", mciHub, irancellHub)
        assertEquals(mciHub, unknownRoute.primaryNumber)
        assertEquals(irancellHub, unknownRoute.failoverNumber)

        val emptyRoute = CarrierDetector.resolveRoute("", mciHub, irancellHub)
        assertEquals(mciHub, emptyRoute.primaryNumber)
        assertEquals(irancellHub, emptyRoute.failoverNumber)
    }

    @Test
    fun testResolveRouteAutoSwapsSwappedHubFields() {
        // Operator accidentally swapped the input fields: put Irancell in mci slot and MCI in irancell slot
        val swappedSlotMci = "09350000001"
        val swappedSlotIrancell = "09120000001"

        // MCI driver must still get the actual MCI number as primary
        val mciRoute = CarrierDetector.resolveRoute("09129999999", swappedSlotMci, swappedSlotIrancell)
        assertEquals("09120000001", mciRoute.primaryNumber)
        assertEquals("09350000001", mciRoute.failoverNumber)

        // Irancell driver must still get the actual Irancell number as primary
        val irancellRoute = CarrierDetector.resolveRoute("09359999999", swappedSlotMci, swappedSlotIrancell)
        assertEquals("09350000001", irancellRoute.primaryNumber)
        assertEquals("09120000001", irancellRoute.failoverNumber)
    }

    // ---------------------------------------------------------------------
    // Hub number resolution (regression guard).
    //
    // The first implementation read the Irancell leg from BuildConfig only. That property is never
    // defined for a normal build, so every shipped APK had an empty Irancell number: resolveRoute
    // always collapsed to single-number delivery and the SIM failover branch became unreachable.
    // These tests pin the precedence rule that keeps dual-SIM reachable from configuration alone.
    // ---------------------------------------------------------------------

    @Test
    fun hubNumbersFallBackToOperatorConfigurationForBothLegs() {
        // A plain build: no -PBARPRO_HUB_PHONE_* properties, so BuildConfig defaults are empty.
        val numbers = CarrierDetector.resolveHubNumbers(
            buildDefaultMci = "",
            buildDefaultIrancell = "",
            configuredMci = "09120000001",
            configuredIrancell = "09350000001"
        )
        assertEquals("09120000001", numbers.mci)
        assertEquals("09350000001", numbers.irancell)
        assertTrue("configured dual-SIM must be recognised as dual-SIM", numbers.isDualSim)
    }

    @Test
    fun hubNumbersPreferFleetProvisionedBuildDefaults() {
        val numbers = CarrierDetector.resolveHubNumbers(
            buildDefaultMci = "09121111111",
            buildDefaultIrancell = "09351111111",
            configuredMci = "09122222222",
            configuredIrancell = "09352222222"
        )
        assertEquals("09121111111", numbers.mci)
        assertEquals("09351111111", numbers.irancell)
    }

    @Test
    fun hubNumbersResolvePerLegAndTrimWhitespace() {
        val numbers = CarrierDetector.resolveHubNumbers(
            buildDefaultMci = "  09121111111  ",
            buildDefaultIrancell = "   ",
            configuredMci = "09122222222",
            configuredIrancell = "  09352222222 "
        )
        assertEquals("09121111111", numbers.mci)
        assertEquals("09352222222", numbers.irancell)
        assertTrue(numbers.isDualSim)
    }

    @Test
    fun hubNumbersReportSingleSimWhenIrancellLegMissing() {
        val numbers = CarrierDetector.resolveHubNumbers(
            buildDefaultMci = "",
            buildDefaultIrancell = "",
            configuredMci = "09120000001",
            configuredIrancell = ""
        )
        assertEquals("09120000001", numbers.mci)
        assertEquals("", numbers.irancell)
        assertFalse(numbers.isDualSim)
    }

    @Test
    fun configuredDualSimProducesDistinctFailoverTarget() {
        // End-to-end of the wiring contract: configuration -> hub numbers -> route.
        // A distinct failover number is what makes the repository's failover branch reachable.
        val numbers = CarrierDetector.resolveHubNumbers(
            buildDefaultMci = "",
            buildDefaultIrancell = "",
            configuredMci = "09120000001",
            configuredIrancell = "09350000001"
        )
        val route = CarrierDetector.resolveRoute("09359999999", numbers.mci, numbers.irancell)
        assertEquals("09350000001", route.primaryNumber)
        assertEquals("09120000001", route.failoverNumber)
        assertNotEquals(
            "failover must differ from primary, otherwise the repository failover branch is dead",
            route.primaryNumber,
            route.failoverNumber
        )
    }
}
