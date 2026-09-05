package io.nekohasekai.sfa.database

import android.app.Application
import androidx.room.Room
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class TarnProfileReconciliationTest {
    @Test
    fun `failure at deletion rolls back earlier insert and rename`() {
        val database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), ProfileDatabase::class.java)
            .allowMainThreadQueries().build()
        try {
            val original = Profile(id = 1, name = "original")
            database.profileDao().insert(original)
            database.openHelper.writableDatabase.execSQL(
                "CREATE TRIGGER fail_delete BEFORE DELETE ON profiles BEGIN SELECT RAISE(ABORT, 'injected failure'); END",
            )
            assertThrows(Exception::class.java) {
                reconcileProfiles(database, listOf(Profile(name = "new")), listOf(Profile(id = 1, name = "renamed")), listOf(original))
            }
            val rows = database.profileDao().list()
            assertEquals(1, rows.size)
            assertEquals("original", rows.single().name)
        } finally {
            database.close()
        }
    }
}
