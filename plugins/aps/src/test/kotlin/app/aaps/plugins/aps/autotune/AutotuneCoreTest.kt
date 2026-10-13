package app.aaps.plugins.aps.autotune

import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.model.data.Block
import app.aaps.core.data.model.data.TargetBlock
import app.aaps.core.data.time.T
import app.aaps.core.interfaces.profile.PureProfile
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.IntKey
import app.aaps.core.objects.profile.ProfileSealed
import app.aaps.core.utils.JsonHelper
import app.aaps.plugins.aps.autotune.data.ATProfile
import app.aaps.plugins.aps.autotune.data.BGDatum
import app.aaps.plugins.aps.autotune.data.CRDatum
import app.aaps.plugins.aps.autotune.data.LocalInsulin
import app.aaps.plugins.aps.autotune.data.PreppedGlucose
import app.aaps.shared.tests.TestBaseWithProfile
import com.google.common.truth.Truth.assertThat
import org.json.JSONArray
import org.json.JSONObject
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mock
import org.mockito.kotlin.whenever
import java.io.File
import java.util.Arrays
import java.util.Calendar
import java.util.TimeZone

class AutotuneCoreTest : TestBaseWithProfile() {

    @Mock lateinit var autotuneFS: AutotuneFS
    private lateinit var autotuneCore: AutotuneCore
    private var min5mCarbImpact = 0.0
    private var autotuneMin = 0.0
    private var autotuneMax = 0.0

    @BeforeEach
    fun initData() {
        autotuneCore = AutotuneCore(preferences, autotuneFS)
        TimeZone.setDefault(TimeZone.getTimeZone("GMT+2"))
    }

    @Suppress("SpellCheckingInspection")
    @Test
    fun autotuneCoreTest1() { // Test if AutotuneCore with input files of OpenAPS categorisation gives correct output profile
        val prepJson = File("src/test/res/autotune/test1/autotune.2022-05-21.json").readText()
        val inputProfileJson = File("src/test/res/autotune/test1/profile.pump.json").readText()
        val inputProfile = atProfileFromOapsJson(JSONObject(inputProfileJson), dateUtil)!!
        val prep = PreppedGlucose(JSONObject(prepJson), dateUtil)

        whenever(preferences.get(DoubleKey.AutosensMax)).thenReturn(autotuneMax)
        whenever(preferences.get(DoubleKey.AutosensMin)).thenReturn(autotuneMin)
        whenever(preferences.get(DoubleKey.ApsSmbMin5MinCarbsImpact)).thenReturn(min5mCarbImpact)
        whenever(preferences.get(BooleanKey.AutotuneMultiIsfIc)).thenReturn(false)
        val oapsOutputProfileJson = File("src/test/res/autotune/test1/aapsorefprofile.json").readText()
        val oapsOutputProfile = atProfileFromOapsJson(JSONObject(oapsOutputProfileJson), dateUtil)!!
        val outProfile = autotuneCore.tuneAllTheThings(prep, inputProfile, inputProfile)
        assertThat(Arrays.equals(outProfile.isf, oapsOutputProfile.isf)).isTrue()
        assertThat(Arrays.equals(outProfile.ic, oapsOutputProfile.ic)).isTrue()
        for (i in 0..23) {
            assertThat(outProfile.basal[i]).isEqualTo(oapsOutputProfile.basal[i])
        }
    }

    @Suppress("SpellCheckingInspection")
    @Test
    fun autotuneCoreTest4() { // Test if limiting modification with Min Max Autosens parameter works (18h on basal and on ISF value)
        val prepJson = File("src/test/res/autotune/test4/autotune.2022-05-30.json").readText()
        val inputProfileJson = File("src/test/res/autotune/test4/profile.2022-05-30.json").readText()
        val inputProfile = atProfileFromOapsJson(JSONObject(inputProfileJson), dateUtil)!!
        val pumpProfileJson = File("src/test/res/autotune/test4/profile.pump.json").readText()
        val pumpProfile = atProfileFromOapsJson(JSONObject(pumpProfileJson), dateUtil)!!
        val prep = PreppedGlucose(JSONObject(prepJson), dateUtil)
        whenever(preferences.get(DoubleKey.AutosensMax)).thenReturn(autotuneMax)
        whenever(preferences.get(DoubleKey.AutosensMin)).thenReturn(autotuneMin)
        whenever(preferences.get(DoubleKey.ApsSmbMin5MinCarbsImpact)).thenReturn(min5mCarbImpact)
        whenever(preferences.get(BooleanKey.AutotuneMultiIsfIc)).thenReturn(false)
        val oapsOutputProfileJson = File("src/test/res/autotune/test4/newprofile.2022-05-30.json").readText()
        val oapsOutputProfile = atProfileFromOapsJson(JSONObject(oapsOutputProfileJson), dateUtil)!!
        val outProfile = autotuneCore.tuneAllTheThings(prep, inputProfile, pumpProfile)
        assertThat(Arrays.equals(outProfile.isf, oapsOutputProfile.isf)).isTrue()
        assertThat(Arrays.equals(outProfile.ic, oapsOutputProfile.ic)).isTrue()
        for (i in 0..23) {
            assertThat(outProfile.basal[i]).isEqualTo(oapsOutputProfile.basal[i])
        }
    }

    @Suppress("SpellCheckingInspection")
    @Test
    fun autotuneCoreMultiBlockFallsBackToClassicWhenThresholdsAreHigh() {
        val prepJson = File("src/test/res/autotune/test1/autotune.2022-05-21.json").readText()
        val inputProfileJson = File("src/test/res/autotune/test1/profile.pump.json").readText()
        val prep = PreppedGlucose(JSONObject(prepJson), dateUtil)

        whenever(preferences.get(DoubleKey.AutosensMax)).thenReturn(1.2)
        whenever(preferences.get(DoubleKey.AutosensMin)).thenReturn(0.7)
        whenever(preferences.get(DoubleKey.ApsSmbMin5MinCarbsImpact)).thenReturn(0.0)

        whenever(preferences.get(BooleanKey.AutotuneMultiIsfIc)).thenReturn(false)
        val classicInput = atProfileFromOapsJson(JSONObject(inputProfileJson), dateUtil)!!
        val classicResult = autotuneCore.tuneAllTheThings(prep, classicInput, classicInput)

        whenever(preferences.get(BooleanKey.AutotuneMultiIsfIc)).thenReturn(true)
        whenever(preferences.get(IntKey.AutotuneTimeBlockHours)).thenReturn(6)
        whenever(preferences.get(IntKey.AutotuneMinIsfDataPoints)).thenReturn(10_000)
        whenever(preferences.get(IntKey.AutotuneMinCrMeals)).thenReturn(10_000)
        val multiInput = atProfileFromOapsJson(JSONObject(inputProfileJson), dateUtil)!!
        val multiResult = autotuneCore.tuneAllTheThings(prep, multiInput, multiInput)

        assertThat(Arrays.equals(classicResult.isf, multiResult.isf)).isTrue()
        assertThat(Arrays.equals(classicResult.ic, multiResult.ic)).isTrue()
        for (i in 0..23) {
            assertThat(classicResult.basal[i]).isEqualTo(multiResult.basal[i])
        }
    }

    @Test
    fun autotuneCoreMultiBlockTunesIsfPerBlockWhenDataIsSufficient() {
        whenever(preferences.get(DoubleKey.AutosensMax)).thenReturn(1.2)
        whenever(preferences.get(DoubleKey.AutosensMin)).thenReturn(0.7)
        whenever(preferences.get(DoubleKey.ApsSmbMin5MinCarbsImpact)).thenReturn(0.0)

        whenever(preferences.get(BooleanKey.AutotuneMultiIsfIc)).thenReturn(false)
        val classicProfile = flatAtProfile(isf = 100.0, ic = 10.0)
        val classicResult = autotuneCore.tuneAllTheThings(preppedGlucoseWithIsfData(), classicProfile, flatAtProfile(isf = 100.0, ic = 10.0))
        val classicIsf = classicResult.getIsf(0)

        whenever(preferences.get(BooleanKey.AutotuneMultiIsfIc)).thenReturn(true)
        whenever(preferences.get(IntKey.AutotuneTimeBlockHours)).thenReturn(6)
        whenever(preferences.get(IntKey.AutotuneMinIsfDataPoints)).thenReturn(2)
        whenever(preferences.get(IntKey.AutotuneMinCrMeals)).thenReturn(1000)
        val blockResult = autotuneCore.tuneAllTheThings(preppedGlucoseWithIsfData(), flatAtProfile(isf = 100.0, ic = 10.0), flatAtProfile(isf = 100.0, ic = 10.0))

        assertThat(blockResult.getIsf(0)).isGreaterThan(classicIsf)
        assertThat(blockResult.getIsf(6)).isLessThan(classicIsf)
        assertThat(blockResult.getIsf(0)).isNotEqualTo(blockResult.getIsf(6))
        assertThat(blockResult.getIsf(12)).isEqualTo(classicIsf)
        assertThat(blockResult.getIsf(23)).isEqualTo(classicIsf)
        assertThat(blockResult.getIc(0)).isEqualTo(blockResult.getIc(23))
    }

    @Test
    fun autotuneCoreMultiBlockTunesIcPerBlockWhenMealDataIsSufficient() {
        whenever(preferences.get(DoubleKey.AutosensMax)).thenReturn(1.2)
        whenever(preferences.get(DoubleKey.AutosensMin)).thenReturn(0.7)
        whenever(preferences.get(DoubleKey.ApsSmbMin5MinCarbsImpact)).thenReturn(0.0)

        whenever(preferences.get(BooleanKey.AutotuneMultiIsfIc)).thenReturn(false)
        val classicProfile = flatAtProfile(isf = 100.0, ic = 10.0)
        val classicResult = autotuneCore.tuneAllTheThings(preppedGlucoseWithCrData(), classicProfile, flatAtProfile(isf = 100.0, ic = 10.0))
        val classicIc = classicResult.getIc(0)

        whenever(preferences.get(BooleanKey.AutotuneMultiIsfIc)).thenReturn(true)
        whenever(preferences.get(IntKey.AutotuneTimeBlockHours)).thenReturn(6)
        whenever(preferences.get(IntKey.AutotuneMinIsfDataPoints)).thenReturn(1000)
        whenever(preferences.get(IntKey.AutotuneMinCrMeals)).thenReturn(2)
        val blockResult = autotuneCore.tuneAllTheThings(preppedGlucoseWithCrData(), flatAtProfile(isf = 100.0, ic = 10.0), flatAtProfile(isf = 100.0, ic = 10.0))

        assertThat(blockResult.getIc(0)).isGreaterThan(classicIc)
        assertThat(blockResult.getIc(6)).isLessThan(classicIc)
        assertThat(blockResult.getIc(0)).isNotEqualTo(blockResult.getIc(6))
        assertThat(blockResult.getIc(12)).isEqualTo(classicIc)
        assertThat(blockResult.getIc(23)).isEqualTo(classicIc)
        assertThat(blockResult.getIsf(0)).isEqualTo(blockResult.getIsf(23))
    }

    @Test
    fun autotuneCoreMultiBlockTunesIcWithSingleMealPerBlockWhenThresholdIsOne() {
        whenever(preferences.get(DoubleKey.AutosensMax)).thenReturn(1.2)
        whenever(preferences.get(DoubleKey.AutosensMin)).thenReturn(0.7)
        whenever(preferences.get(DoubleKey.ApsSmbMin5MinCarbsImpact)).thenReturn(0.0)
        whenever(preferences.get(BooleanKey.AutotuneMultiIsfIc)).thenReturn(true)
        whenever(preferences.get(IntKey.AutotuneTimeBlockHours)).thenReturn(6)
        whenever(preferences.get(IntKey.AutotuneMinIsfDataPoints)).thenReturn(1000)
        whenever(preferences.get(IntKey.AutotuneMinCrMeals)).thenReturn(1)

        val blockResult = autotuneCore.tuneAllTheThings(
            preppedGlucoseWithSingleMealPerBlock(),
            flatAtProfile(isf = 100.0, ic = 10.0),
            flatAtProfile(isf = 100.0, ic = 10.0)
        )

        assertThat(blockResult.getIc(0)).isGreaterThan(10.0)
        assertThat(blockResult.getIc(6)).isLessThan(10.0)
        assertThat(blockResult.getIc(0)).isNotEqualTo(blockResult.getIc(6))
        assertThat(blockResult.getIc(12)).isEqualTo(10.0)
        assertThat(blockResult.getIc(23)).isEqualTo(10.0)
    }

    /**
     * OpenAPS profile for Autotune only have one ISF value and one IC value
     */
    @Suppress("SpellCheckingInspection")
    private fun atProfileFromOapsJson(jsonObject: JSONObject, dateUtil: DateUtil, defaultUnits: String? = null): ATProfile? {
        try {
            min5mCarbImpact = JsonHelper.safeGetDoubleAllowNull(jsonObject, "min_5m_carbimpact") ?: return null
            autotuneMin = JsonHelper.safeGetDoubleAllowNull(jsonObject, "autosens_min") ?: return null
            autotuneMax = JsonHelper.safeGetDoubleAllowNull(jsonObject, "autosens_max") ?: return null
            val txtUnits = JsonHelper.safeGetStringAllowNull(jsonObject, "units", defaultUnits) ?: return null
            val units = GlucoseUnit.fromText(txtUnits)
            val dia = JsonHelper.safeGetDoubleAllowNull(jsonObject, "dia") ?: return null
            val peak = JsonHelper.safeGetIntAllowNull(jsonObject, "insulinPeakTime") ?: return null
            val localInsulin = LocalInsulin("insulin", peak, dia)
            val timezone = TimeZone.getTimeZone(JsonHelper.safeGetString(jsonObject, "timezone", "UTC"))
            val isfJson = jsonObject.getJSONObject("isfProfile")
            val isfBlocks = blockFromValueJsonArray(isfJson.getJSONArray("sensitivities"), "sensitivity")
                ?: return null
            val icBlocks = when (val carbRatio = jsonObject.get("carb_ratio")) {
                is JSONArray -> blockFromValueJsonArray(carbRatio, "value") ?: return null
                else         -> arrayListOf(Block((T.hours(24).secs()) * 1000L, jsonObject.getDouble("carb_ratio")))
            }
            val basalBlocks = blockFromJsonArray(jsonObject.getJSONArray("basalprofile"))
                ?: return null
            val targetBlocks = ArrayList<TargetBlock>(1).also {
                it.add(0, TargetBlock((T.hours(24).secs()) * 1000L, 100.0, 100.0))
            }

            val pure = PureProfile(
                jsonObject = jsonObject,
                basalBlocks = basalBlocks,
                isfBlocks = isfBlocks,
                icBlocks = icBlocks,
                targetBlocks = targetBlocks,
                glucoseUnit = units,
                timeZone = timezone,
                dia = dia
            )
            return ATProfile(activePlugin, preferences, profileUtil, dateUtil, rh, profileStoreProvider, aapsLogger).with(ProfileSealed.Pure(pure, activePlugin), localInsulin)
        } catch (_: Exception) {
            return null
        }
    }

    private fun blockFromJsonArray(jsonArray: JSONArray?): List<Block>? {
        val size = jsonArray?.length() ?: return null
        val ret = ArrayList<Block>(size)
        try {
            for (index in 0 until jsonArray.length() - 1) {
                val o = jsonArray.getJSONObject(index)
                val tas = o.getInt("minutes") * 60
                val next = jsonArray.getJSONObject(index + 1)
                val nextTas = next.getInt("minutes") * 60
                val value = o.getDouble("rate")
                if (tas % 3600 != 0) return null
                if (nextTas % 3600 != 0) return null
                ret.add(index, Block((nextTas - tas) * 1000L, value))
            }
            val last: JSONObject = jsonArray.getJSONObject(jsonArray.length() - 1)
            val lastTas = last.getInt("minutes") * 60
            val value = last.getDouble("rate")
            ret.add(jsonArray.length() - 1, Block((T.hours(24).secs() - lastTas) * 1000L, value))
        } catch (_: Exception) {
            return null
        }
        return ret
    }

    private fun blockFromValueJsonArray(jsonArray: JSONArray?, valueKey: String): ArrayList<Block>? {
        val size = jsonArray?.length() ?: return null
        val ret = ArrayList<Block>(size)
        try {
            for (index in 0 until size) {
                val current = jsonArray.getJSONObject(index)
                val currentTas = timeAsSeconds(current)
                val nextTas = if (index == size - 1) T.hours(24).secs().toInt() else timeAsSeconds(jsonArray.getJSONObject(index + 1))
                ret.add(index, Block((nextTas - currentTas) * 1000L, current.getDouble(valueKey)))
            }
        } catch (_: Exception) {
            return null
        }
        return ret
    }

    private fun timeAsSeconds(jsonObject: JSONObject): Int = when {
        jsonObject.has("timeAsSeconds") -> jsonObject.getInt("timeAsSeconds")
        jsonObject.has("offset")        -> jsonObject.getInt("offset") * 60
        jsonObject.has("minutes")       -> jsonObject.getInt("minutes") * 60
        jsonObject.has("start")         -> {
            val parts = jsonObject.getString("start").split(":")
            parts[0].toInt() * 3600 + parts[1].toInt() * 60 + if (parts.size > 2) parts[2].toInt() else 0
        }

        else                           -> 0
    }

    private fun flatAtProfile(isf: Double, ic: Double): ATProfile {
        val atProfile = ATProfile(activePlugin, preferences, profileUtil, dateUtil, rh, profileStoreProvider, aapsLogger)
            .with(validProfile, LocalInsulin("insulin", 75, 5.0))
        atProfile.isf.fill(isf)
        atProfile.ic.fill(ic)
        atProfile.basal.fill(1.0)
        return atProfile
    }

    private fun preppedGlucoseWithIsfData(): PreppedGlucose = PreppedGlucose(
        from = now,
        crData = emptyList(),
        csfGlucoseData = emptyList(),
        isfGlucoseData = listOf(
            bgDatum(hour = 1, deviation = 10.0, bgi = 10.0),
            bgDatum(hour = 2, deviation = 10.0, bgi = 10.0),
            bgDatum(hour = 7, deviation = -5.0, bgi = 10.0),
            bgDatum(hour = 8, deviation = -5.0, bgi = 10.0)
        ),
        basalGlucoseData = emptyList(),
        dateUtil = dateUtil
    )

    private fun preppedGlucoseWithCrData(): PreppedGlucose = PreppedGlucose(
        from = now,
        crData = listOf(
            crDatum(hour = 1, carbs = 60.0, insulin = 4.0),
            crDatum(hour = 2, carbs = 60.0, insulin = 4.0),
            crDatum(hour = 7, carbs = 30.0, insulin = 4.0),
            crDatum(hour = 8, carbs = 30.0, insulin = 4.0)
        ),
        csfGlucoseData = emptyList(),
        isfGlucoseData = emptyList(),
        basalGlucoseData = emptyList(),
        dateUtil = dateUtil
    )

    private fun preppedGlucoseWithSingleMealPerBlock(): PreppedGlucose = PreppedGlucose(
        from = now,
        crData = listOf(
            crDatum(hour = 1, carbs = 60.0, insulin = 4.0),
            crDatum(hour = 7, carbs = 30.0, insulin = 4.0)
        ),
        csfGlucoseData = emptyList(),
        isfGlucoseData = emptyList(),
        basalGlucoseData = emptyList(),
        dateUtil = dateUtil
    )

    private fun bgDatum(hour: Int, deviation: Double, bgi: Double): BGDatum = BGDatum(dateUtil).also {
        it.date = timestampAtHour(hour)
        it.deviation = deviation
        it.bgi = bgi
        it.avgDelta = 0.0
    }

    private fun crDatum(hour: Int, carbs: Double, insulin: Double): CRDatum = CRDatum(dateUtil).also {
        it.crInitialCarbTime = timestampAtHour(hour)
        it.crEndTime = timestampAtHour(hour + 1)
        it.crInitialBG = 100.0
        it.crEndBG = 100.0
        it.crInitialIOB = 0.0
        it.crEndIOB = 0.0
        it.crCarbs = carbs
        it.crInsulin = insulin
    }

    private fun timestampAtHour(hour: Int): Long = Calendar.getInstance(TimeZone.getDefault()).apply {
        set(2026, Calendar.JANUARY, 1, hour, 0, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
}
