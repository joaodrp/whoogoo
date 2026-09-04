package dev.joaodrp.whoogoo

import android.os.Bundle
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.Record
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
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.OffsetDateTime

/** Reads filesDir/records.json (output of whoop2hc.py) and upserts it into Health Connect. */
class MainActivity : ComponentActivity() {
    private val permissions = listOf(
        SleepSessionRecord::class, ExerciseSessionRecord::class,
        ActiveCaloriesBurnedRecord::class, TotalCaloriesBurnedRecord::class,
        RestingHeartRateRecord::class, HeartRateVariabilityRmssdRecord::class,
        OxygenSaturationRecord::class, RespiratoryRateRecord::class, SkinTemperatureRecord::class,
    ).map { HealthPermission.getWritePermission(it) }.toSet()

    private lateinit var out: TextView
    private val client by lazy { HealthConnectClient.getOrCreate(this) }
    private val requestPermissions =
        registerForActivityResult(PermissionController.createRequestPermissionResultContract()) { granted ->
            if (granted.containsAll(permissions)) import() else log("denied: ${permissions - granted}")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        out = TextView(this).apply { setPadding(32, 32, 32, 32) }
        setContentView(ScrollView(this).apply { addView(out) })
        lifecycleScope.launch {
            if (client.permissionController.getGrantedPermissions().containsAll(permissions)) import()
            else requestPermissions.launch(permissions)
        }
    }

    private fun import() = lifecycleScope.launch {
        val file = File(filesDir, "records.json")
        if (!file.exists()) return@launch log("missing ${file.path}")
        val arr = JSONArray(file.readText())
        val records = runCatching { (0 until arr.length()).map { toRecord(arr.getJSONObject(it)) } }
            .getOrElse { return@launch log("bad record: $it") }
        log("parsed ${records.size} records")
        var done = 0
        for (chunk in records.chunked(500)) {
            runCatching { client.insertRecords(chunk) }
                .onFailure { log("insert failed at $done: $it"); return@launch }
            done += chunk.size
            log("inserted $done/${records.size}")
        }
        log("done")
    }

    private fun log(msg: String) {
        android.util.Log.i("Whoogoo", msg)
        out.append("$msg\n")
    }
}

private val whoop = Device(manufacturer = "WHOOP", type = Device.TYPE_FITNESS_BAND)

/** Resolves e.g. "BIKING" to ExerciseSessionRecord.EXERCISE_TYPE_BIKING; the JSON carries constant suffixes. */
private fun constant(cls: Class<*>, prefix: String, name: String): Int =
    cls.getField("$prefix$name").getInt(null)

private fun JSONObject.at(key: String): OffsetDateTime = OffsetDateTime.parse(getString(key))

private fun toRecord(o: JSONObject): Record {
    val meta = Metadata.autoRecorded(clientRecordId = o.getString("id"), device = whoop)
    return when (val type = o.getString("type")) {
        "sleep" -> {
            val s = o.at("start"); val e = o.at("end")
            val stages = o.getJSONArray("stages")
            SleepSessionRecord(
                startTime = s.toInstant(), startZoneOffset = s.offset,
                endTime = e.toInstant(), endZoneOffset = e.offset,
                title = o.getString("title"),
                stages = (0 until stages.length()).map { stages.getJSONObject(it) }.map {
                    SleepSessionRecord.Stage(
                        startTime = it.at("start").toInstant(), endTime = it.at("end").toInstant(),
                        stage = constant(SleepSessionRecord::class.java, "STAGE_TYPE_", it.getString("stage")),
                    )
                },
                metadata = meta,
            )
        }
        "exercise" -> {
            val s = o.at("start"); val e = o.at("end")
            ExerciseSessionRecord(
                startTime = s.toInstant(), startZoneOffset = s.offset,
                endTime = e.toInstant(), endZoneOffset = e.offset,
                exerciseType = constant(ExerciseSessionRecord::class.java, "EXERCISE_TYPE_", o.getString("exerciseType")),
                title = o.getString("title"), metadata = meta,
            )
        }
        "active_calories" -> {
            val s = o.at("start"); val e = o.at("end")
            ActiveCaloriesBurnedRecord(
                startTime = s.toInstant(), startZoneOffset = s.offset,
                endTime = e.toInstant(), endZoneOffset = e.offset,
                energy = Energy.kilocalories(o.getDouble("kcal")), metadata = meta,
            )
        }
        "total_calories" -> {
            val s = o.at("start"); val e = o.at("end")
            TotalCaloriesBurnedRecord(
                startTime = s.toInstant(), startZoneOffset = s.offset,
                endTime = e.toInstant(), endZoneOffset = e.offset,
                energy = Energy.kilocalories(o.getDouble("kcal")), metadata = meta,
            )
        }
        "resting_heart_rate" -> o.at("time").let {
            RestingHeartRateRecord(time = it.toInstant(), zoneOffset = it.offset,
                beatsPerMinute = o.getLong("bpm"), metadata = meta)
        }
        "hrv" -> o.at("time").let {
            HeartRateVariabilityRmssdRecord(time = it.toInstant(), zoneOffset = it.offset,
                heartRateVariabilityMillis = o.getDouble("ms"), metadata = meta)
        }
        "spo2" -> o.at("time").let {
            OxygenSaturationRecord(time = it.toInstant(), zoneOffset = it.offset,
                percentage = Percentage(o.getDouble("pct")), metadata = meta)
        }
        "respiratory_rate" -> o.at("time").let {
            RespiratoryRateRecord(time = it.toInstant(), zoneOffset = it.offset,
                rate = o.getDouble("rpm"), metadata = meta)
        }
        "skin_temperature" -> {
            val s = o.at("start"); val e = o.at("end")
            SkinTemperatureRecord(
                startTime = s.toInstant(), startZoneOffset = s.offset,
                endTime = e.toInstant(), endZoneOffset = e.offset,
                baseline = Temperature.celsius(o.getDouble("baseline")),
                // Health Connect requires delta times strictly inside the record interval.
                deltas = listOf(SkinTemperatureRecord.Delta(
                    time = e.toInstant().minusSeconds(1),
                    delta = TemperatureDelta.celsius(o.getDouble("delta")))),
                measurementLocation = SkinTemperatureRecord.MEASUREMENT_LOCATION_WRIST,
                metadata = meta,
            )
        }
        else -> error("unknown record type $type")
    }
}
