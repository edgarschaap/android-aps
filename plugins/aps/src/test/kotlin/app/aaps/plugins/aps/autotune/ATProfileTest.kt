package app.aaps.plugins.aps.autotune

import app.aaps.core.keys.BooleanKey
import app.aaps.plugins.aps.autotune.data.ATProfile
import app.aaps.plugins.aps.autotune.data.LocalInsulin
import app.aaps.shared.tests.TestBaseWithProfile
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.whenever

class ATProfileTest : TestBaseWithProfile() {

    @Test
    fun updateProfileRoundTripKeepsHourlyIsfForMmolProfiles() {
        whenever(preferences.get(BooleanKey.AutotuneMultiIsfIc)).thenReturn(true)

        val original = ATProfile(activePlugin, preferences, profileUtil, dateUtil, rh, profileStoreProvider, aapsLogger)
            .with(validProfile, LocalInsulin("insulin", 75, 5.0))

        for (hour in 0..23) {
            original.basal[hour] = 1.0 + hour / 100.0
            original.ic[hour] = 10.0 + hour
            original.isf[hour] = 90.0 + hour
        }

        original.updateProfile()

        val restored = ATProfile(activePlugin, preferences, profileUtil, dateUtil, rh, profileStoreProvider, aapsLogger)
            .with(original.profile, LocalInsulin("insulin", 75, 5.0))

        for (hour in 0..23) {
            assertThat(restored.basal[hour]).isWithin(0.001).of(original.basal[hour])
            assertThat(restored.getIc(hour)).isWithin(0.001).of(original.getIc(hour))
            assertThat(restored.getIsf(hour)).isWithin(0.001).of(original.getIsf(hour))
        }
        assertThat(restored.averageIsf).isWithin(0.001).of(original.averageIsf)
        assertThat(restored.averageIc).isWithin(0.001).of(original.averageIc)
    }
}


