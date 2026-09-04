package dev.joaodrp.whoogoo

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
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
            "sleep" to 1, "respiratory_rate" to 1, "total_calories" to 1, "resting_heart_rate" to 2,
            "hrv" to 2, "spo2" to 2, "skin_temperature" to 2, "exercise" to 2, "active_calories" to 1
        )
        assertEquals(want, counts(records))
        assertEquals(
            "14 records (active_calories=1 exercise=2 hrv=2 respiratory_rate=1 resting_heart_rate=2 skin_temperature=2 sleep=1 spo2=2 total_calories=1)",
            countsString(counts(records))
        )
        assertEquals(records.sortedBy { it.time() }, records)

        val by = records.associateBy { it["id"] }
        val sleep = by.getValue("whoop:sleep:2026-07-26 00:10:39")
        assertEquals("2026-07-26T00:10:39+01:00", sleep["start"])
        @Suppress("UNCHECKED_CAST")
        val stages = sleep["stages"] as List<Record>
        assertEquals(4, stages.size)
        assertEquals("AWAKE", stages[3]["stage"])
        assertEquals(sleep["end"], stages[3]["end"])

        val odd = by.getValue("whoop:workout:2026-07-26 20:00:00")
        assertEquals("OTHER_WORKOUT", odd["exerciseType"])
        assertEquals("Basket Weaving, Underwater", odd["title"])
        assertEquals("2026-07-26T20:00:00+01:00", odd["start"]) // seconds always written
        assertEquals("2026-07-27T07:52:09Z", by.getValue("whoop:rhr:2026-07-26 23:52:10")["time"])
        assertEquals(59L, by.getValue("whoop:rhr:2026-07-26 23:52:10")["bpm"])

        val temp = by.getValue("whoop:temp:2026-07-26 23:52:10")
        val reconstructed = temp["baseline"] as Double + temp["delta"] as Double
        assertTrue("skin temp reconstructs to $reconstructed", reconstructed in 33.81..33.83)
    }

    @Test
    fun rejectsBadCells() {
        val bad = csvs + ("workouts.csv" to WORKOUTS.replace("1137.0", "lots"))
        val e = runCatching { convert(bad) }.exceptionOrNull()
        assertEquals("workouts.csv: Energy burned (cal): not a number", e?.message)
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
}
