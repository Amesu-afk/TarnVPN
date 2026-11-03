package io.nekohasekai.sfa.tarn.data

import io.nekohasekai.sfa.database.Profile
import io.nekohasekai.sfa.database.TypedProfile
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class TarnSubscriptionDeletionTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `only profiles tagged with the deleted subscription are selected`() {
        fun profile(id: Long, subscription: String?): Profile {
            val file = File(temporaryFolder.root, "$id.json").apply { writeText("{}") }
            if (subscription != null) File(temporaryFolder.root, "$id.sub").writeText(subscription)
            return Profile(id = id, typed = TypedProfile().apply { path = file.path })
        }

        val profiles = listOf(
            profile(1, "wanted"),
            profile(2, " wanted\n"),
            profile(3, "other"),
            profile(4, null),
            profile(5, ""),
        )

        assertEquals(
            listOf(1L, 2L),
            TarnServerRepository.profilesOwnedBySubscription(profiles, "wanted").map { it.id },
        )
        assertEquals(
            listOf(3L),
            TarnServerRepository.profilesOwnedBySubscription(profiles, "other").map { it.id },
        )
    }
}
