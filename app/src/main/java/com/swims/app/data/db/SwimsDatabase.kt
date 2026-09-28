package com.swims.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.swims.app.data.model.BanditArmStat
import com.swims.app.data.model.BanditDecisionRecord
import com.swims.app.data.model.IntakeLog
import com.swims.app.data.model.UserProfile
import net.sqlcipher.database.SQLiteDatabase
import net.sqlcipher.database.SupportFactory
import java.security.SecureRandom
import java.util.Base64

@Database(
    entities = [
        UserProfile::class,
        IntakeLog::class,
        BanditArmStat::class,
        BanditDecisionRecord::class,
    ],
    version = 4, // v4: contextual-bandit tables (migrated, not destroyed)
    exportSchema = true
)
abstract class SwimsDatabase : RoomDatabase() {

    abstract fun dao(): SwimsDao

    companion object {
        private const val DB_NAME = "swims_encrypted.db"
        private const val PREF_FILE = "swims_secure_prefs"
        private const val KEY_DB_PASS = "db_passphrase"

        @Volatile
        private var INSTANCE: SwimsDatabase? = null

        fun getInstance(context: Context): SwimsDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: buildDatabase(context).also { INSTANCE = it }
            }
        }

        /**
         * Builds a SQLCipher-encrypted Room database.
         *
         * Security approach:
         * 1. A random 32-byte passphrase is generated ONCE on first launch.
         * 2. The passphrase is stored in EncryptedSharedPreferences (AES-256-GCM),
         *    which itself is protected by the Android Keystore hardware key.
         * 3. SQLCipher uses this passphrase to encrypt the entire .db file with AES-256.
         *
         * Result: Even if someone extracts the .db file from the device,
         * they cannot read it without the hardware-bound key — no data leak.
         */
        private fun buildDatabase(context: Context): SwimsDatabase {
            val passphrase = getOrCreatePassphrase(context)
            val factory = SupportFactory(SQLiteDatabase.getBytes(passphrase.toCharArray()))

            return Room.databaseBuilder(
                context.applicationContext,
                SwimsDatabase::class.java,
                DB_NAME
            )
                .openHelperFactory(factory)
                .addMigrations(MIGRATION_3_4)
                // Only earlier, pre-release schemas fall back to a wipe; from v3
                // onward upgrades are migrated so user history survives updates.
                .fallbackToDestructiveMigration()
                .build()
        }

        /**
         * v3 → v4: adds the contextual-bandit tables. Purely additive, so all
         * existing profile and intake data is preserved across the upgrade.
         */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // New opt-in flags on the existing profile row.
                db.execSQL(
                    "ALTER TABLE `user_profile` ADD COLUMN `banditEnabled` INTEGER NOT NULL DEFAULT 1"
                )
                db.execSQL(
                    "ALTER TABLE `user_profile` ADD COLUMN `federatedEnabled` INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `bandit_arm_stats` (
                        `contextKey` TEXT NOT NULL,
                        `arm` INTEGER NOT NULL,
                        `alpha` REAL NOT NULL,
                        `beta` REAL NOT NULL,
                        `syncedAlpha` REAL NOT NULL,
                        `syncedBeta` REAL NOT NULL,
                        PRIMARY KEY(`contextKey`, `arm`)
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `bandit_decisions` (
                        `id` INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT,
                        `contextKey` TEXT NOT NULL,
                        `arm` INTEGER NOT NULL,
                        `decidedAtMs` INTEGER NOT NULL,
                        `consumedAtDecisionMl` INTEGER NOT NULL,
                        `resolved` INTEGER NOT NULL,
                        `reward` REAL NOT NULL
                    )
                    """.trimIndent()
                )
            }
        }

        /**
         * Returns the stored passphrase, or generates and stores a new one.
         * Uses EncryptedSharedPreferences backed by Android Keystore (hardware-bound).
         */
        private fun getOrCreatePassphrase(context: Context): String {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            val prefs = EncryptedSharedPreferences.create(
                context,
                PREF_FILE,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )

            return prefs.getString(KEY_DB_PASS, null) ?: run {
                val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
                val newPass = Base64.getEncoder().encodeToString(bytes)
                prefs.edit().putString(KEY_DB_PASS, newPass).apply()
                newPass
            }
        }
    }
}
