package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
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
    version = 3,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun filterRuleDao(): FilterRuleDao
    abstract fun forwardLogDao(): ForwardLogDao
    abstract fun forwardConfigDao(): ForwardConfigDao

    companion object {
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
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }

        private class DatabaseCallback(
            private val context: Context
        ) : RoomDatabase.Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                super.onCreate(db)
                CoroutineScope(Dispatchers.IO).launch {
                    val database = getDatabase(context)
                    // Pre-populate with default config
                    database.forwardConfigDao().insertOrUpdate(
                        ForwardConfig(
                            id = 1,
                            isMasterEnabled = true,
                            endpointUrl = "https://httpbin.org/post",
                            authType = AuthType.BEARER_TOKEN,
                            authHeaderKey = "Authorization",
                            authHeaderValue = "Bearer barpro-token-secure-2026",
                            isEncryptionEnabled = true,
                            secretEncryptionKey = "BarProSecretKey2026AESGCM!",
                            filterMode = ForwardFilterMode.ALL_MESSAGES,
                            deviceIdentifier = "BarPro Terminal 01",
                            includeMetadata = true
                        )
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
