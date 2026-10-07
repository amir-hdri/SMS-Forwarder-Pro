package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.data.model.AuthType
import com.example.data.model.FilterRule
import com.example.data.model.ForwardConfig
import com.example.data.model.ForwardFilterMode
import com.example.data.model.ForwardLog
import com.example.data.model.MatchType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Database(
    entities = [FilterRule::class, ForwardLog::class, ForwardConfig::class],
    version = 8,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun filterRuleDao(): FilterRuleDao
    abstract fun forwardLogDao(): ForwardLogDao
    abstract fun forwardConfigDao(): ForwardConfigDao

    companion object {
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE forward_logs ADD COLUMN recipientPhone TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE forward_logs ADD COLUMN messageFingerprint TEXT NOT NULL DEFAULT ''")
                db.execSQL("UPDATE app_config SET endpointUrl = REPLACE(endpointUrl, '/api/v1/rpa/sms-forwarder', '/api/v1/otp/sms-forwarder')")
                db.execSQL("UPDATE app_config SET authHeaderKey = 'X-OTP-Webhook-Token' WHERE authHeaderKey = 'X-Forwarder-Secret'")
                // Version 6 shipped pre-enabled sample settings without an actual opt-in.
                db.execSQL("UPDATE app_config SET isMasterEnabled = 0, userConsentGiven = 0, enableSmsFallback = 0")
            }
        }

        /** Adds the remote-config / self-update channel and the explicit cleartext acknowledgement. */
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE app_config ADD COLUMN allowCleartextTransport INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE app_config ADD COLUMN configManifestUrl TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE app_config ADD COLUMN updateManifestUrl TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE app_config ADD COLUMN autoUpdateEnabled INTEGER NOT NULL DEFAULT 1")
                // An existing install is already configured and working; do not send it back
                // through the first-run wizard.
                db.execSQL("ALTER TABLE app_config ADD COLUMN setupCompleted INTEGER NOT NULL DEFAULT 1")
            }
        }

        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "sms_forwarder_database"
                )
                    .addCallback(DatabaseCallback(context))
                    .addMigrations(MIGRATION_6_7, MIGRATION_7_8)
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
                INSTANCE = instance
                instance
            }
        }

        fun getInstance(context: Context): AppDatabase = getDatabase(context)

        private class DatabaseCallback(
            private val context: Context
        ) : RoomDatabase.Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                super.onCreate(db)
                CoroutineScope(Dispatchers.IO).launch {
                    val database = getDatabase(context)
                    // Pre-populate with default config
                    database.forwardConfigDao().insertOrUpdate(
                        ForwardConfig()
                    )

                    // Pre-populate with helpful filter rules for BarPro, logistics, and OTPs
                    database.filterRuleDao().insertRule(
                        FilterRule(
                            senderPattern = "BAR PRO",
                            matchType = MatchType.CONTAINS,
                            label = "سامانه بارپرو (BarPro)",
                            keywordFilter = "",
                            isEnabled = true
                        )
                    )
                    database.filterRuleDao().insertRule(
                        FilterRule(
                            senderPattern = "2000",
                            matchType = MatchType.PREFIX,
                            label = "سرشماره ۲۰۰۰ (پیامک‌های اعتبارسنجی)",
                            keywordFilter = "",
                            isEnabled = true
                        )
                    )
                    database.filterRuleDao().insertRule(
                        FilterRule(
                            senderPattern = "98",
                            matchType = MatchType.PREFIX,
                            label = "سامانه‌های پیامک خدماتی و بانکی",
                            keywordFilter = "کد,رمز,OTP,بارنامه,تایید",
                            isEnabled = true
                        )
                    )
                }
            }
        }
    }
}
