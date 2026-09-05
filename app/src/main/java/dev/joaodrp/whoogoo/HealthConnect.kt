package dev.joaodrp.whoogoo

import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.Record as HealthRecord
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.health.connect.client.units.Percentage
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.reflect.KClass

private val whoop = Device(manufacturer = "WHOOP", type = Device.TYPE_FITNESS_BAND)

/** Resolves e.g. "BIKING" to ExerciseSessionRecord.EXERCISE_TYPE_BIKING; records carry constant suffixes. */
private fun constant(cls: Class<*>, prefix: String, name: String): Int = cls.getField("$prefix$name").getInt(null)

private fun Record.str(key: String): String = this[key] as String

private fun Record.num(key: String): Double = (this[key] as Number).toDouble()

private fun Record.at(key: String): OffsetDateTime = OffsetDateTime.parse(str(key))

/** The Health Connect class each exported type becomes; deleting needs it without a record in hand. */
fun recordClass(type: String): KClass<out HealthRecord> = when (type) {
    "exercise" -> ExerciseSessionRecord::class
    "resting_heart_rate" -> RestingHeartRateRecord::class
    "hrv" -> HeartRateVariabilityRmssdRecord::class
    "spo2" -> OxygenSaturationRecord::class
    "respiratory_rate" -> RespiratoryRateRecord::class
    else -> error("unknown record type $type")
}

/**
 * Stamped on every record of one import so a later import wins. Health Connect only overwrites a
 * record when the incoming version is strictly higher, so a fixed version would make re-importing
 * corrected values do nothing at all.
 */
private val version = System.currentTimeMillis()

fun toRecord(o: Record): HealthRecord {
    val meta = Metadata.autoRecorded(clientRecordId = o.str("id"), clientRecordVersion = version, device = whoop)
    return when (val type = o.str("type")) {
        "exercise" -> {
            val s = o.at("start")
            val e = o.at("end")
            ExerciseSessionRecord(
                startTime = s.toInstant(),
                startZoneOffset = s.offset,
                endTime = e.toInstant(),
                endZoneOffset = e.offset,
                exerciseType = constant(ExerciseSessionRecord::class.java, "EXERCISE_TYPE_", o.str("exerciseType")),
                title = o.str("title"),
                metadata = meta
            )
        }

        "resting_heart_rate" -> o.at("time").let {
            RestingHeartRateRecord(
                time = it.toInstant(),
                zoneOffset = it.offset,
                beatsPerMinute = (o["bpm"] as Number).toLong(),
                metadata = meta
            )
        }

        "hrv" -> o.at("time").let {
            HeartRateVariabilityRmssdRecord(
                time = it.toInstant(),
                zoneOffset = it.offset,
                heartRateVariabilityMillis = o.num("ms"),
                metadata = meta
            )
        }

        "spo2" -> o.at("time").let {
            OxygenSaturationRecord(
                time = it.toInstant(),
                zoneOffset = it.offset,
                percentage = Percentage(o.num("pct")),
                metadata = meta
            )
        }

        "respiratory_rate" -> o.at("time").let {
            RespiratoryRateRecord(
                time = it.toInstant(),
                zoneOffset = it.offset,
                rate = o.num("rpm"),
                metadata = meta
            )
        }

        else -> error("unknown record type $type")
    }
}

/** What other apps have already put in Health Connect, in the shape [alreadyThere] compares against. */
class Existing(val days: Map<String, Set<LocalDate>>, val workouts: List<LongRange>, val apps: Set<String>)

/** The day a reading is filed under, by its own offset when it kept one. */
private fun date(time: Instant, offset: ZoneOffset?): LocalDate =
    time.atOffset(offset ?: ZoneId.systemDefault().rules.getOffset(time)).toLocalDate()

/** Every page of one record type in the window, minus this app's own writes. */
private suspend fun <T : HealthRecord> HealthConnectClient.others(
    type: KClass<T>,
    window: TimeRangeFilter,
    mine: String
): List<T> {
    val out = mutableListOf<T>()
    var token: String? = null
    do {
        val page = readRecords(ReadRecordsRequest(type, window, pageToken = token))
        out += page.records.filter { it.metadata.dataOrigin.packageName != mine }
        token = page.pageToken
    } while (token != null)
    return out
}

/**
 * What other apps already hold between [from] and [until]. Needs the read permissions, and without
 * READ_HEALTH_DATA_HISTORY it only ever sees the last 30 days, so the caller must hold both.
 *
 * The vitals repeat themselves because the interface tying their timestamps together is internal
 * to the library.
 */
suspend fun existing(client: HealthConnectClient, mine: String, from: Instant, until: Instant): Existing {
    val window = TimeRangeFilter.between(from, until)
    val apps = mutableSetOf<String>()
    suspend fun <T : HealthRecord> days(type: KClass<T>, at: (T) -> Pair<Instant, ZoneOffset?>): Set<LocalDate> =
        client.others(type, window, mine)
            .onEach { apps += it.metadata.dataOrigin.packageName }
            .mapTo(mutableSetOf()) { at(it).let { (t, o) -> date(t, o) } }
    val days = mapOf(
        "resting_heart_rate" to days(RestingHeartRateRecord::class) { it.time to it.zoneOffset },
        "hrv" to days(HeartRateVariabilityRmssdRecord::class) { it.time to it.zoneOffset },
        "spo2" to days(OxygenSaturationRecord::class) { it.time to it.zoneOffset },
        "respiratory_rate" to days(RespiratoryRateRecord::class) { it.time to it.zoneOffset }
    )
    val workouts = client.others(ExerciseSessionRecord::class, window, mine)
        .onEach { apps += it.metadata.dataOrigin.packageName }
        .map { it.startTime.toEpochMilli()..it.endTime.toEpochMilli() }
    return Existing(days, workouts, apps)
}
