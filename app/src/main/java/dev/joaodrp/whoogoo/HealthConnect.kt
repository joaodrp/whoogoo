package dev.joaodrp.whoogoo

import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.Record as HealthRecord
import androidx.health.connect.client.records.RespiratoryRateRecord
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SkinTemperatureRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.units.Energy
import androidx.health.connect.client.units.Percentage
import androidx.health.connect.client.units.Temperature
import androidx.health.connect.client.units.TemperatureDelta
import java.time.OffsetDateTime

private val whoop = Device(manufacturer = "WHOOP", type = Device.TYPE_FITNESS_BAND)

/** Resolves e.g. "BIKING" to ExerciseSessionRecord.EXERCISE_TYPE_BIKING; records carry constant suffixes. */
private fun constant(cls: Class<*>, prefix: String, name: String): Int = cls.getField("$prefix$name").getInt(null)

private fun Record.str(key: String): String = this[key] as String

private fun Record.num(key: String): Double = (this[key] as Number).toDouble()

private fun Record.at(key: String): OffsetDateTime = OffsetDateTime.parse(str(key))

fun toRecord(o: Record): HealthRecord {
    val meta = Metadata.autoRecorded(clientRecordId = o.str("id"), device = whoop)
    return when (val type = o.str("type")) {
        "sleep" -> {
            val s = o.at("start")
            val e = o.at("end")
            SleepSessionRecord(
                startTime = s.toInstant(),
                startZoneOffset = s.offset,
                endTime = e.toInstant(),
                endZoneOffset = e.offset,
                title = o.str("title"),
                notes = o.str("notes").takeIf { it.isNotBlank() },
                metadata = meta
            )
        }

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

        "active_calories" -> {
            val s = o.at("start")
            val e = o.at("end")
            ActiveCaloriesBurnedRecord(
                startTime = s.toInstant(),
                startZoneOffset = s.offset,
                endTime = e.toInstant(),
                endZoneOffset = e.offset,
                energy = Energy.kilocalories(o.num("kcal")),
                metadata = meta
            )
        }

        "total_calories" -> {
            val s = o.at("start")
            val e = o.at("end")
            TotalCaloriesBurnedRecord(
                startTime = s.toInstant(),
                startZoneOffset = s.offset,
                endTime = e.toInstant(),
                endZoneOffset = e.offset,
                energy = Energy.kilocalories(o.num("kcal")),
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

        "skin_temperature" -> {
            val s = o.at("start")
            val e = o.at("end")
            SkinTemperatureRecord(
                startTime = s.toInstant(),
                startZoneOffset = s.offset,
                endTime = e.toInstant(),
                endZoneOffset = e.offset,
                baseline = Temperature.celsius(o.num("baseline")),
                // Health Connect requires delta times strictly inside the record interval.
                deltas = listOf(
                    SkinTemperatureRecord.Delta(
                        time = e.toInstant().minusSeconds(1),
                        delta = TemperatureDelta.celsius(o.num("delta"))
                    )
                ),
                measurementLocation = SkinTemperatureRecord.MEASUREMENT_LOCATION_WRIST,
                metadata = meta
            )
        }

        else -> error("unknown record type $type")
    }
}
