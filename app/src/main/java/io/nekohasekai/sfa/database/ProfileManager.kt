package io.nekohasekai.sfa.database

import androidx.room.Room
import io.nekohasekai.sfa.Application
import io.nekohasekai.sfa.constant.Path
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

@Suppress("RedundantSuspendModifier")
object ProfileManager {
    private val callbacks = mutableListOf<() -> Unit>()

    fun registerCallback(callback: () -> Unit) {
        callbacks.add(callback)
    }

    fun unregisterCallback(callback: () -> Unit) {
        callbacks.remove(callback)
    }

    @OptIn(DelicateCoroutinesApi::class)
    private val instance by lazy {
        Application.application.getDatabasePath(Path.PROFILES_DATABASE_PATH).parentFile?.mkdirs()
        Room
            .databaseBuilder(
                Application.application,
                ProfileDatabase::class.java,
                Path.PROFILES_DATABASE_PATH,
            )
            .addMigrations(ProfileDatabase.MIGRATION_1_2, ProfileDatabase.MIGRATION_2_3)
            .fallbackToDestructiveMigrationOnDowngrade()
            .enableMultiInstanceInvalidation()
            .setQueryExecutor { GlobalScope.launch { it.run() } }
            .build()
    }

    suspend fun nextOrder(): Long = instance.profileDao().nextOrder() ?: 0

    suspend fun nextFileID(): Long = instance.profileDao().nextFileID() ?: 1

    suspend fun get(id: Long): Profile? = instance.profileDao().get(id)

    suspend fun create(profile: Profile, andSelect: Boolean = false): Profile {
        profile.id = instance.profileDao().insert(profile)
        if (andSelect) {
            Settings.selectedProfile = profile.id
        }
        for (callback in callbacks.toList()) {
            callback()
        }
        return profile
    }

    /**
     * Same as calling [create] once per profile, except listeners are notified once at the
     * end instead of once per row. A subscription import can add dozens of profiles in one
     * go; notifying per row fired a full reload-and-reprobe-everything cycle per server,
     * which for N servers cost O(N²) TCP probes instead of O(N).
     */
    suspend fun createAll(profiles: List<Profile>): List<Profile> {
        if (profiles.isEmpty()) return profiles
        val ids = instance.profileDao().insert(profiles)
        profiles.forEachIndexed { index, profile -> profile.id = ids[index] }
        for (callback in callbacks.toList()) {
            callback()
        }
        return profiles
    }

    suspend fun update(profile: Profile): Int {
        try {
            return instance.profileDao().update(profile)
        } finally {
            for (callback in callbacks.toList()) {
                callback()
            }
        }
    }

    suspend fun update(profiles: List<Profile>): Int {
        try {
            return instance.profileDao().update(profiles)
        } finally {
            for (callback in callbacks.toList()) {
                callback()
            }
        }
    }

    suspend fun delete(profile: Profile): Int {
        try {
            return instance.profileDao().delete(profile)
        } finally {
            for (callback in callbacks.toList()) {
                callback()
            }
        }
    }

    suspend fun delete(profiles: List<Profile>): Int {
        try {
            return instance.profileDao().delete(profiles)
        } finally {
            for (callback in callbacks.toList()) {
                callback()
            }
        }
    }

    suspend fun list(): List<Profile> = instance.profileDao().list()

    fun remoteServerDao(): RemoteServer.Dao = instance.remoteServerDao()
}
