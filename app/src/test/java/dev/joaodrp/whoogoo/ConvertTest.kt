package dev.joaodrp.whoogoo

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private const val SLEEPS = """Cycle start time,Cycle end time,Cycle timezone,Sleep onset,Wake onset,Sleep performance %,Respiratory rate (rpm),Asleep duration (min),In bed duration (min),Light sleep duration (min),Deep (SWS) duration (min),REM duration (min),Awake duration (min),Sleep need (min),Sleep debt (min),Sleep efficiency %,Sleep consistency %,Nap
2026-07-26 00:10:39,2026-07-26 23:52:10,UTC+01:00,2026-07-26 00:10:39,2026-07-26 07:39:40,86,14.0,427,449,239,110,78,22,508,49,95,81,false
"""

private const val CYCLES = """Cycle start time,Cycle end time,Cycle timezone,Recovery score %,Resting heart rate (bpm),Heart rate variability (ms),Skin temp (celsius),Blood oxygen %,Day Strain,Energy burned (cal),Max HR (bpm),Average HR (bpm),Sleep onset,Wake onset,Sleep performance %,Respiratory rate (rpm),Asleep duration (min),In bed duration (min),Light sleep duration (min),Deep (SWS) duration (min),REM duration (min),Awake duration (min),Sleep need (min),Sleep debt (min),Sleep efficiency %,Sleep consistency %
2026-07-26 23:52:10,,UTCZ,41,59,42,33.82,97.47,,,,,2026-07-26 23:52:10,2026-07-27 07:52:09,88,13.8,466,479,277,88,101,13,560,52,97,87
2026-07-26 00:10:39,2026-07-26 23:52:10,UTC+01:00,76,53,54,33.92,97.61,17.8,2725,183,75,2026-07-26 00:10:39,2026-07-26 07:39:40,86,14.0,427,449,239,110,78,22,508,49,95,81
"""

private const val WORKOUTS = """Cycle start time,Cycle end time,Cycle timezone,Workout start time,Workout end time,Duration (min),Activity name,Activity Strain,Energy burned (cal),Max HR (bpm),Average HR (bpm),HR Zone 1 %,HR Zone 2 %,HR Zone 3 %,HR Zone 4 %,HR Zone 5 %,GPS enabled
2026-07-26 00:10:39,2026-07-26 23:52:10,UTC+01:00,2026-07-26 17:47:03,2026-07-26 19:24:40,97,Cycling,17.1,1137.0,183,147,17,16,42,23,1,false
2026-07-26 00:10:39,2026-07-26 23:52:10,UTC+01:00,2026-07-26 20:00:00,2026-07-26 20:30:00,30,"Basket Weaving, Underwater",1.0,0.0,100,90,0,0,0,0,0,false
"""

class ConvertTest {
    private val csvs = mapOf("sleeps.csv" to SLEEPS, "physiological_cycles.csv" to CYCLES, "workouts.csv" to WORKOUTS)

    @Test
    fun convertsEveryType() {
        val records = convert(csvs)
        val want = mapOf(
            "respiratory_rate" to 1,
            "resting_heart_rate" to 2,
            "hrv" to 2,
            "spo2" to 2,
            "exercise" to 2
        )
        assertEquals(want, counts(records))
        assertEquals(
            "9 records (exercise=2 hrv=2 respiratory_rate=1 resting_heart_rate=2 spo2=2)",
            countsString(counts(records))
        )
        assertEquals(records.sortedBy { it.time() }, records)

        val by = records.associateBy { it["id"] }

        val odd = by.getValue("whoop:workout:2026-07-26 20:00:00")
        assertEquals("OTHER_WORKOUT", odd["exerciseType"])
        assertEquals("Basket Weaving, Underwater", odd["title"])
        assertEquals("2026-07-26T20:00:00+01:00", odd["start"]) // seconds always written
        assertEquals("2026-07-27T07:52:09Z", by.getValue("whoop:rhr:2026-07-26 23:52:10")["time"])
        assertEquals(59L, by.getValue("whoop:rhr:2026-07-26 23:52:10")["bpm"])
    }

    @Test
    fun rejectsBadCells() {
        val bad = csvs + ("physiological_cycles.csv" to CYCLES.replace(",53,54,", ",lots,54,"))
        val e = runCatching { convert(bad) }.exceptionOrNull()
        assertEquals("physiological_cycles.csv: Resting heart rate (bpm): not a number", e?.message)
    }

    @Test
    fun rejectsRaggedRowsAndEmptyExports() {
        val ragged = csvs + ("sleeps.csv" to SLEEPS.trimEnd().substringBeforeLast(','))
        assertEquals(
            "sleeps.csv: row has 17 cells, header has 18",
            runCatching {
                convert(ragged)
            }.exceptionOrNull()?.message
        )
        val empty = csvs.mapValues { it.value.lineSequence().first() }
        assertEquals(
            "no sleeps, cycles or workouts in the export",
            runCatching {
                convert(empty)
            }.exceptionOrNull()?.message
        )
    }

    @Test
    fun filtersByTypeAndDate() {
        val records = convert(csvs)
        val vitals = setOf("hrv", "spo2")
        assertEquals(setOf("hrv", "spo2"), filter(records, vitals).map { it["type"] }.toSet())
        assertEquals(4, filter(records, vitals).size)

        // Vitals are stamped at wake time, so the cycle waking on the 27th is the later one.
        val until = filter(records, vitals, until = LocalDate.of(2026, 7, 26))
        assertEquals(2, until.size)
        assertEquals(listOf("2026-07-26T07:39:40+01:00"), until.map { it["time"] }.distinct())

        assertEquals(emptyList<Record>(), filter(records, emptySet()))
    }

    @Test
    fun readsCsvsAnywhereInTheZip() {
        val buf = ByteArrayOutputStream()
        ZipOutputStream(buf).use { z ->
            for ((name, text) in csvs) {
                z.putNextEntry(ZipEntry("my_whoop_data/$name"))
                z.write(text.toByteArray())
            }
            z.putNextEntry(ZipEntry("my_whoop_data/journal_entries.csv"))
        }
        assertEquals(csvs, readExport(ByteArrayInputStream(buf.toByteArray())))
    }

    @Test
    fun skipsWhatAnotherAppAlreadyHas() {
        val records = convert(csvs)
        val workout = records.first { it["type"] == "exercise" }
        val start = OffsetDateTime.parse(workout["start"] as String).toInstant().toEpochMilli()
        val end = OffsetDateTime.parse(workout["end"] as String).toInstant().toEpochMilli()

        assertEquals(emptySet<String>(), alreadyThere(records, emptyMap(), emptyList()))

        // A vital is one reading a night, so another app's reading that day is the same reading.
        val day = (records.first { it["type"] == "hrv" }).time().toLocalDate()
        assertEquals(
            records.filter { it["type"] == "hrv" && it.time().toLocalDate() == day }.map { it["id"] },
            alreadyThere(records, mapOf("hrv" to setOf(day)), emptyList()).toList()
        )

        // A workout only counts as the same one when it overlaps; touching at the minute does not.
        assertEquals(setOf(workout["id"]), alreadyThere(records, emptyMap(), listOf(start + 1..end)))
        assertEquals(emptySet<String>(), alreadyThere(records, emptyMap(), listOf(end..end + 60_000)))
    }

    @Test
    fun skipsASleepStillInProgress() {
        // A sleep the export caught mid-flight has a respiratory rate but no wake time yet.
        val rows = SLEEPS.trimEnd().lines()
        val cells = rows[1].split(",").toMutableList()
        cells[4] = ""
        val inProgress = (rows[0] + "\n" + cells.joinToString(",")) + "\n"
        val records = convert(csvs + ("sleeps.csv" to inProgress))
        assertEquals(0, records.count { it["type"] == "respiratory_rate" })
        assertTrue(records.isNotEmpty())
    }
}
