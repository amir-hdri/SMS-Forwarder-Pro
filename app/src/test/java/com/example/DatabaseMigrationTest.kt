package com.example

import android.app.Application
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.local.AppDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DatabaseMigrationTest {

    /**
     * Seeds a legacy database from a preserved schema snapshot and stamps its version, so Room
     * runs the real registered migrations on open.
     */
    private fun seedLegacyDatabase(name: String, schemaResource: String, version: Int) {
        val context = ApplicationProvider.getApplicationContext<Application>()
        context.deleteDatabase(name)
        val file = context.getDatabasePath(name)
        file.parentFile!!.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { old ->
            val schema = javaClass.classLoader!!.getResourceAsStream(schemaResource)!!
                .bufferedReader().readText()
            schema.lineSequence().filter { it.startsWith("CREATE TABLE") }.forEach { old.execSQL(it) }
            val values = ContentValues()
            old.rawQuery("PRAGMA table_info(app_config)", null).use { columns ->
                while (columns.moveToNext()) {
                    val field = columns.getString(1)
                    if (columns.getString(2) == "INTEGER") values.put(field, 0) else values.put(field, "")
                }
            }
            values.put("id", 1)
            values.put("authType", "CUSTOM_HEADER")
            values.put("filterMode", "SPECIFIC_RULES_ONLY")
            values.put("driverPhone", "09120000001")
            values.put("fallbackServerPhoneNumber", "09120000002")
            values.put("authHeaderValue", "synthetic-migration-secret")
            values.put("isMasterEnabled", 1)
            values.put("userConsentGiven", 1)
            old.insertOrThrow("app_config", null, values)
            old.execSQL("INSERT INTO filter_rules VALUES (1, '20007777', 'PREFIX', 'original rule', '', 1, 1000)")
            old.version = version
        }
    }

    @Test fun versionEightUpgradePreservesSetupAndEnablesPrimaryRelay() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val name = "migration-8-10-test.db"
        seedLegacyDatabase(name, "schema-v8.sql", version = 8)
        // The production builder registers the migrations; Room validates all tables on open.
        AppDatabase.getDatabase(context)
        // Isolated named database running the full registered chain, no destructive fallback.
        val migrated = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(AppDatabase.MIGRATION_8_9, AppDatabase.MIGRATION_9_10).build()
        try {
            val config = migrated.forwardConfigDao().getConfig()!!
            assertEquals("09120000001", config.driverPhone)
            assertEquals("09120000002", config.fallbackServerPhoneNumber)
            assertEquals("synthetic-migration-secret", config.authHeaderValue)
            assertTrue(config.isMasterEnabled && config.userConsentGiven && config.primarySmsRelayEnabled)
            // The new second-SIM leg must arrive empty, never fabricated.
            assertEquals("", config.hubIrancellPhoneNumber)
            migrated.openHelper.readableDatabase.query("SELECT label FROM filter_rules WHERE id = 1").use {
                assertTrue(it.moveToFirst())
                assertEquals("original rule", it.getString(0))
            }
        } finally {
            migrated.close()
            context.deleteDatabase(name)
        }
    }

    /**
     * Guards MIGRATION_9_10 on its own: an existing v9 install keeps its single configured Hub SIM
     * and gains an empty Irancell leg, so the operator opts into dual-SIM explicitly.
     */
    @Test fun versionNineUpgradeAddsEmptyIrancellHubLegAndKeepsData() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val name = "migration-9-10-test.db"
        seedLegacyDatabase(name, "schema-v9.sql", version = 9)
        val migrated = Room.databaseBuilder(context, AppDatabase::class.java, name)
            .addMigrations(AppDatabase.MIGRATION_9_10).build()
        try {
            val config = migrated.forwardConfigDao().getConfig()!!
            assertEquals("09120000002", config.fallbackServerPhoneNumber)
            assertEquals("", config.hubIrancellPhoneNumber)
            assertEquals("09120000001", config.driverPhone)
            migrated.openHelper.readableDatabase.query("SELECT label FROM filter_rules WHERE id = 1").use {
                assertTrue(it.moveToFirst())
                assertEquals("original rule", it.getString(0))
            }
        } finally {
            migrated.close()
            context.deleteDatabase(name)
        }
    }
}
