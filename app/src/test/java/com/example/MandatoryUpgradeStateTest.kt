package com.example

import android.app.Application
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import com.example.update.UpdateStore
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class MandatoryUpgradeStateTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val store get() = UpdateStore.getInstance(context)
    @Before fun reset() {
        context.getSharedPreferences("barpro_update_state", 0).edit().clear().commit()
        UpdateStore::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
    }
    @Test fun aNewerSignedPolicyCanWithdrawARequirementButAnOlderOneCannot() {
        store.recordConfigRequirement(10, 7, 2)
        assertTrue(store.blockedByMandatoryUpdate)
        store.recordConfigRequirement(0, 7, 3)
        assertFalse(store.blockedByMandatoryUpdate)
        store.recordConfigRequirement(10, 7, 2)
        assertFalse(store.blockedByMandatoryUpdate)
        store.recordReleaseRequirement(11, 7, 2)
        assertTrue(store.blockedByMandatoryUpdate)
        store.recordReleaseRequirement(0, 7, 3)
        assertFalse(store.blockedByMandatoryUpdate)
    }
    @Test fun anUpgradeBelowEitherAcceptedRequirementStaysBlockedAcrossRestart() {
        store.recordConfigRequirement(10, 7, 2)
        store.recordReleaseRequirement(11, 7, 2)
        UpdateStore::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, null)
        store.reconcileInstalledVersion(10)
        assertTrue(store.blockedByMandatoryUpdate)
        store.reconcileInstalledVersion(11)
        assertFalse(store.blockedByMandatoryUpdate)
    }
    @Test fun aLegacyBooleanOnlyGateRequiresBothAcceptedPolicySources() {
        store.blockedByMandatoryUpdate = true
        store.reconcileInstalledVersion(50)
        assertTrue(store.blockedByMandatoryUpdate)
        store.recordConfigRequirement(10, 50, 2)
        assertTrue(store.blockedByMandatoryUpdate)
        store.recordReleaseRequirement(11, 50, 2)
        assertFalse(store.blockedByMandatoryUpdate)
    }
    @Test fun anOmittedMinimumPreservesTheGateAndAdvancesTheAcceptedPolicyRevision() {
        store.recordConfigRequirement(10, 7, 2)
        store.recordConfigRequirement(null, 7, 3)
        assertTrue(store.blockedByMandatoryUpdate)
        store.recordConfigRequirement(0, 7, 2)
        assertTrue(store.blockedByMandatoryUpdate)
        store.recordConfigRequirement(0, 7, 3)
        assertTrue("An altered same-revision document cannot withdraw the previous explicit minimum",
            store.blockedByMandatoryUpdate)
        store.recordConfigRequirement(0, 7, 4)
        assertFalse(store.blockedByMandatoryUpdate)
    }
    @Test fun anOmittedMinimumCannotResolveALegacyUnknownGate() {
        store.blockedByMandatoryUpdate = true
        store.recordConfigRequirement(null, 50, 3)
        store.recordReleaseRequirement(0, 50, 3)
        assertTrue(store.blockedByMandatoryUpdate)
        store.recordConfigRequirement(0, 50, 4)
        assertFalse(store.blockedByMandatoryUpdate)
    }
    @Test fun failedPersistenceCannotOpenTheGateEvenIfAndroidUpdatedItsPreferenceCache() {
        val persisted = context.getSharedPreferences("synthetic_failed_update_policy", 0)
        persisted.edit().clear().commit()
        val preferences = object : SharedPreferences by persisted {
            override fun edit(): SharedPreferences.Editor {
                val editor = persisted.edit()
                return java.lang.reflect.Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader,
                    arrayOf(SharedPreferences.Editor::class.java)) { proxy, method, args ->
                    if (method.name == "commit") {
                        editor.apply() // Android may update its process cache before disk failure.
                        false
                    } else {
                        val result = method.invoke(editor, *(args ?: emptyArray()))
                        if (result is SharedPreferences.Editor) proxy else result
                    }
                } as SharedPreferences.Editor
            }
        }
        val failed = UpdateStore::class.java.getDeclaredConstructor(SharedPreferences::class.java)
            .apply { isAccessible = true }.newInstance(preferences)
        assertTrue(runCatching { failed.recordConfigRequirement(0, 10, 2) }.isFailure)
        assertTrue("Unknown durable policy must stop forwarding despite a false cached Boolean",
            failed.blockedByMandatoryUpdate)
    }
}
