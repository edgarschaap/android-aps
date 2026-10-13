package app.aaps.plugins.aps.autotune.data

import app.aaps.core.data.model.GlucoseUnit
import app.aaps.core.data.model.data.Block
import app.aaps.core.data.time.T
import app.aaps.core.interfaces.insulin.Insulin
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.profile.Profile
import app.aaps.core.interfaces.profile.ProfileStore
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.profile.PureProfile
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.Round
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.IntKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.objects.extensions.blockValueBySeconds
import app.aaps.core.objects.extensions.pureProfileFromJson
import app.aaps.core.objects.profile.ProfileSealed
import app.aaps.core.utils.MidnightUtils
import app.aaps.plugins.aps.R
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.text.DecimalFormat
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Provider
import kotlin.math.min

class ATProfile @Inject constructor(
    private val activePlugin: ActivePlugin,
    private val preferences: Preferences,
    private val profileUtil: ProfileUtil,
    private val dateUtil: DateUtil,
    private val rh: ResourceHelper,
    private val profileStoreProvider: Provider<ProfileStore>,
    private val aapsLogger: AAPSLogger
) {

    lateinit var profile: ProfileSealed
    lateinit var localInsulin: LocalInsulin
    lateinit var circadianProfile: ProfileSealed
    private lateinit var pumpProfile: ProfileSealed
    var profileName: String = ""
    var basal = DoubleArray(24)
    var basalUnTuned = IntArray(24)
    var ic = DoubleArray(24)
    var isf = DoubleArray(24)
    var dia = 0.0
    var peak = 0
    var isValid: Boolean = false
    var from: Long = 0
    private var pumpProfileAvgISF = 0.0
    private var pumpProfileAvgIC = 0.0

    val icSize: Int
        get() = profile.getIcsValues().size
    val isfSize: Int
        get() = profile.getIsfsMgdlValues().size
    private val profileAvgISF: Double
        get() = if (profile.getIsfsMgdlValues().size == 1) profile.getIsfsMgdlValues()[0].value else Round.roundTo(averageProfileValue(profile.getIsfsMgdlValues()), 0.01)
    private val profileAvgIC: Double
        get() = if (profile.getIcsValues().size == 1) profile.getIcsValues()[0].value else Round.roundTo(averageProfileValue(profile.getIcsValues()), 0.01)

    val averageIc: Double
        get() = Round.roundTo(ic.average(), 0.001)
    val averageIsf: Double
        get() = Round.roundTo(isf.average(), 0.001)

    fun with(profile: Profile, localInsulin: LocalInsulin): ATProfile {
        this.profile = profile as ProfileSealed
        this.localInsulin = localInsulin

        circadianProfile = profile
        isValid = profile.isValid
        if (isValid) {
            //initialize tuned value with current profile values
            var minBasal = 1.0
            var minIc = Double.MAX_VALUE
            var minIsf = Double.MAX_VALUE
            for (h in 0..23) {
                basal[h] = Round.roundTo(profile.basalBlocks.blockValueBySeconds(T.hours(h.toLong()).secs().toInt(), 1.0, 0), 0.001)
                ic[h] = Round.roundTo(profile.icBlocks.blockValueBySeconds(T.hours(h.toLong()).secs().toInt(), 1.0, 0), 0.001)
                isf[h] = Round.roundTo(profile.isfBlocks.blockValueBySeconds(T.hours(h.toLong()).secs().toInt(), 1.0, 0), 0.001)
                minBasal = min(minBasal, basal[h])
                minIc = min(minIc, ic[h])
                minIsf = min(minIsf, isf[h])
            }
            if (minIc * minIsf * minBasal == 0.0)     // Additional validity check to avoid error later in AutotunePrep
                isValid = false
            pumpProfile = profile
            pumpProfileAvgIC = profileAvgIC
            pumpProfileAvgISF = profileAvgISF
        }
        dia = localInsulin.dia
        peak = localInsulin.peak
        return this
    }

    fun getBasal(timestamp: Long): Double = basal[MidnightUtils.secondsFromMidnight(timestamp) / 3600]
    fun getIc(hour: Int): Double = ic[hour.mod(ic.size)]
    fun getIc(timestamp: Long): Double = getIc(MidnightUtils.secondsFromMidnight(timestamp) / 3600)
    fun getIsf(hour: Int): Double = isf[hour.mod(isf.size)]
    fun getIsf(timestamp: Long): Double = getIsf(MidnightUtils.secondsFromMidnight(timestamp) / 3600)

    // for localProfilePlugin Synchronisation
    fun basal() = jsonArray(basal)
    fun ic(circadian: Boolean = false): JSONArray {
        if (preferences.get(BooleanKey.AutotuneMultiIsfIc)) return jsonArray(ic)
        if (circadian)
            return jsonArray(pumpProfile.icBlocks, averageIc / pumpProfileAvgIC)
        return jsonArray(averageIc)
    }

    fun isf(circadian: Boolean = false): JSONArray {
        if (preferences.get(BooleanKey.AutotuneMultiIsfIc)) return jsonArray(fromMgdlToUnits(isf))
        if (circadian)
            return jsonArray(pumpProfile.isfBlocks, averageIsf / pumpProfileAvgISF)
        return jsonArray(profileUtil.fromMgdlToUnits(averageIsf, profile.units))
    }

    fun getProfile(circadian: Boolean = false): PureProfile {
        return if (circadian)
            circadianProfile.convertToNonCustomizedProfile(dateUtil)
        else
            profile.convertToNonCustomizedProfile(dateUtil)
    }

    fun updateProfile() {
        data()?.let { profile = ProfileSealed.Pure(value = it, activePlugin = null) }
        data(true)?.let { circadianProfile = ProfileSealed.Pure(value = it, activePlugin = null) }
    }

    //Export json string with oref0 format used for autotune
    // Include min_5m_carbimpact, insulin type, single value for carb_ratio and isf
    fun profileToOrefJSON(): String {
        var jsonString = ""
        val json = JSONObject()
        val insulinInterface: Insulin = activePlugin.activeInsulin
        val multiBlockIcIsf = preferences.get(BooleanKey.AutotuneMultiIsfIc)
        try {
            json.put("name", profileName)
            json.put("min_5m_carbimpact", preferences.get(DoubleKey.ApsAmaMin5MinCarbsImpact))
            json.put("dia", dia)
            if (insulinInterface.id === Insulin.InsulinType.OREF_ULTRA_RAPID_ACTING) json.put(
                "curve",
                "ultra-rapid"
            ) else if (insulinInterface.id === Insulin.InsulinType.OREF_RAPID_ACTING) json.put("curve", "rapid-acting") else if (insulinInterface.id === Insulin.InsulinType.OREF_LYUMJEV) {
                json.put("curve", "ultra-rapid")
                json.put("useCustomPeakTime", true)
                json.put("insulinPeakTime", 45)
            } else if (insulinInterface.id === Insulin.InsulinType.OREF_FREE_PEAK) {
                val peakTime: Int = preferences.get(IntKey.InsulinOrefPeak)
                json.put("curve", if (peakTime > 50) "rapid-acting" else "ultra-rapid")
                json.put("useCustomPeakTime", true)
                json.put("insulinPeakTime", peakTime)
            }
            val basals = JSONArray()
            for (h in 0..23) {
                val secondFromMidnight = h * 60 * 60
                val time: String = DecimalFormat("00").format(h) + ":00:00"
                basals.put(
                    JSONObject()
                        .put("start", time)
                        .put("minutes", h * 60)
                        .put(
                            "rate", profile.getBasalTimeFromMidnight(secondFromMidnight)
                        )
                )
            }
            json.put("basalprofile", basals)
            val isfValues = if (multiBlockIcIsf) isf else DoubleArray(24) { averageIsf }
            json.put(
                "isfProfile",
                JSONObject().put(
                    "sensitivities",
                    JSONArray().also { sensitivities ->
                        for (h in 0..23) {
                            sensitivities.put(
                                JSONObject()
                                    .put("i", h)
                                    .put("start", DecimalFormat("00").format(h) + ":00:00")
                                    .put("sensitivity", Round.roundTo(isfValues[h], 0.001))
                                    .put("offset", h * 60)
                                    .put("x", h)
                                    .put("endoffset", (h + 1) * 60)
                            )
                        }
                    }
                )
            )
            json.put("carb_ratio", if (multiBlockIcIsf) jsonArray(ic) else averageIc)
            json.put("autosens_max", preferences.get(DoubleKey.AutosensMax))
            json.put("autosens_min", preferences.get(DoubleKey.AutosensMin))
            json.put("units", GlucoseUnit.MGDL.asText)
            json.put("timezone", TimeZone.getDefault().id)
            jsonString = json.toString(2).replace("\\/", "/")
        } catch (e: JSONException) {
            aapsLogger.error(LTag.CORE, e.stackTraceToString())
        }

        return jsonString
    }

    fun data(circadian: Boolean = false): PureProfile? {
        val json: JSONObject = profile.toPureNsJson(dateUtil)
        val multiBlockIcIsf = preferences.get(BooleanKey.AutotuneMultiIsfIc)
        try {
            json.put("dia", dia)
            if (multiBlockIcIsf) {
                json.put("sens", jsonArray(fromMgdlToUnits(isf)))
                json.put("carbratio", jsonArray(ic))
            } else if (circadian) {
                json.put("sens", jsonArray(pumpProfile.isfBlocks, averageIsf / pumpProfileAvgISF))
                json.put("carbratio", jsonArray(pumpProfile.icBlocks, averageIc / pumpProfileAvgIC))
            } else {
                json.put("sens", jsonArray(profileUtil.fromMgdlToUnits(averageIsf, profile.units)))
                json.put("carbratio", jsonArray(averageIc))
            }
            json.put("basal", jsonArray(basal))
        } catch (e: JSONException) {
            aapsLogger.error(LTag.CORE, e.stackTraceToString())
        }
        return pureProfileFromJson(json, dateUtil, profile.units.asText)
    }

    fun profileStore(circadian: Boolean = false): ProfileStore? {
        var profileStore: ProfileStore? = null
        val json = JSONObject()
        val store = JSONObject()
        val tunedProfile = if (circadian) circadianProfile else profile
        if (profileName.isEmpty())
            profileName = rh.gs(R.string.autotune_tunedprofile_name)
        try {
            store.put(profileName, tunedProfile.toPureNsJson(dateUtil))
            json.put("defaultProfile", profileName)
            json.put("store", store)
            json.put("startDate", dateUtil.toISOAsUTC(dateUtil.now()))
            profileStore = profileStoreProvider.get().with(json)
        } catch (e: JSONException) {
            aapsLogger.error(LTag.CORE, e.stackTraceToString())
        }
        return profileStore
    }

    private fun jsonArray(values: DoubleArray): JSONArray {
        val json = JSONArray()
        for (h in 0..23) {
            val secondFromMidnight = h * 60 * 60
            val df = DecimalFormat("00")
            val time = df.format(h.toLong()) + ":00"
            json.put(
                JSONObject()
                    .put("time", time)
                    .put("timeAsSeconds", secondFromMidnight)
                    .put("value", values[h])
            )
        }
        return json
    }

    private fun jsonArray(value: Double): JSONArray =
        JSONArray().put(
            JSONObject()
                .put("time", "00:00")
                .put("timeAsSeconds", 0)
                .put("value", value)
        )

    private fun fromMgdlToUnits(values: DoubleArray): DoubleArray = DoubleArray(values.size) { profileUtil.fromMgdlToUnits(values[it], profile.units) }

    private fun jsonArray(values: List<Block>, multiplier: Double = 1.0): JSONArray {
        val json = JSONArray()
        var elapsedHours = 0L
        values.forEach {
            val value = values.blockValueBySeconds(T.hours(elapsedHours).secs().toInt(), multiplier, 0)
            json.put(
                JSONObject()
                    .put("time", DecimalFormat("00").format(elapsedHours) + ":00")
                    .put("timeAsSeconds", T.hours(elapsedHours).secs())
                    .put("value", value)
            )
            elapsedHours += T.msecs(it.duration).hours()
        }
        return json
    }

    companion object {

        fun averageProfileValue(pf: Array<Profile.ProfileValue>?): Double {
            var avgValue = 0.0
            val secondPerDay = 24 * 60 * 60
            if (pf == null) return avgValue
            for (i in pf.indices) {
                avgValue += pf[i].value * ((if (i == pf.size - 1) secondPerDay else pf[i + 1].timeAsSeconds) - pf[i].timeAsSeconds)
            }
            avgValue /= secondPerDay.toDouble()
            return avgValue
        }
    }
}