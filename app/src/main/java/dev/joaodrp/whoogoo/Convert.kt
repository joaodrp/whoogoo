package dev.joaodrp.whoogoo

import java.io.InputStream
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.zip.ZipInputStream

/**
 * One Health Connect record as a plain map. This is the shape of records.json, which the CLI's
 * `verify` reads, and [toRecord] turns it into the Health Connect object.
 */
typealias Record = Map<String, Any>

private typealias Row = Map<String, String>

/** Timestamp a record is filed under: its point in time, or the start of its interval. */
fun Record.time(): OffsetDateTime = OffsetDateTime.parse((this["time"] ?: this["start"]) as String)

/** WHOOP activity names to ExerciseSessionRecord.EXERCISE_TYPE_* suffixes; anything else is OTHER_WORKOUT. */
private val exerciseTypes = mapOf(
    "Cycling" to "BIKING", "Mountain Biking" to "BIKING", "Spin" to "BIKING_STATIONARY",
    "Spinning" to "BIKING_STATIONARY", "Indoor Cycling" to "BIKING_STATIONARY",
    "Running" to "RUNNING", "Jogging" to "RUNNING", "Track & Field" to "RUNNING", "Treadmill" to "RUNNING_TREADMILL",
    "Walking" to "WALKING", "Stroller Walking" to "WALKING", "Rucking" to "WALKING", "Hiking" to "HIKING",
    "Weightlifting" to "WEIGHTLIFTING", "Powerlifting" to "WEIGHTLIFTING", "Strength Trainer" to "STRENGTH_TRAINING",
    "Functional Fitness" to "HIGH_INTENSITY_INTERVAL_TRAINING", "HIIT" to "HIGH_INTENSITY_INTERVAL_TRAINING",
    "CrossFit" to "HIGH_INTENSITY_INTERVAL_TRAINING", "Boot Camp" to "BOOT_CAMP", "Calisthenics" to "CALISTHENICS",
    "Yoga" to "YOGA", "Pilates" to "PILATES", "Barre" to "EXERCISE_CLASS", "Stretching" to "STRETCHING",
    "Meditation" to "GUIDED_BREATHING", "Breathwork" to "GUIDED_BREATHING",
    "Rowing" to "ROWING", "Indoor Rowing" to "ROWING_MACHINE", "Elliptical" to "ELLIPTICAL",
    "Stairmaster" to "STAIR_CLIMBING_MACHINE", "Stair Climbing" to "STAIR_CLIMBING",
    "Swimming" to "SWIMMING_POOL", "Open Water Swimming" to "SWIMMING_OPEN_WATER",
    "Paddleboarding" to "PADDLING", "Kayaking" to "PADDLING", "Canoeing" to "PADDLING", "Surfing" to "SURFING",
    "Sailing" to "SAILING", "Scuba Diving" to "SCUBA_DIVING", "Water Polo" to "WATER_POLO",
    "Boxing" to "BOXING", "Kickboxing" to "MARTIAL_ARTS", "Martial Arts" to "MARTIAL_ARTS",
    "Jiu Jitsu" to "MARTIAL_ARTS", "Muay Thai" to "MARTIAL_ARTS", "Fencing" to "FENCING",
    "Climbing" to "ROCK_CLIMBING", "Rock Climbing" to "ROCK_CLIMBING", "Bouldering" to "ROCK_CLIMBING",
    "Skiing" to "SKIING", "Cross Country Skiing" to "SKIING", "Snowboarding" to "SNOWBOARDING",
    "Snowshoeing" to "SNOWSHOEING", "Ice Skating" to "ICE_SKATING", "Skateboarding" to "SKATING",
    "Inline Skating" to "SKATING", "Roller Skating" to "SKATING", "Paragliding" to "PARAGLIDING",
    "Tennis" to "TENNIS", "Table Tennis" to "TABLE_TENNIS", "Badminton" to "BADMINTON", "Squash" to "SQUASH",
    "Racquetball" to "RACQUETBALL", "Golf" to "GOLF", "Soccer" to "SOCCER", "Basketball" to "BASKETBALL",
    "Baseball" to "BASEBALL", "Softball" to "SOFTBALL", "Volleyball" to "VOLLEYBALL", "Cricket" to "CRICKET",
    "Handball" to "HANDBALL", "Rugby" to "RUGBY", "American Football" to "FOOTBALL_AMERICAN",
    "Australian Football" to "FOOTBALL_AUSTRALIAN", "Ice Hockey" to "ICE_HOCKEY", "Roller Hockey" to "ROLLER_HOCKEY",
    "Ultimate" to "FRISBEE_DISC", "Disc Golf" to "FRISBEE_DISC", "Gymnastics" to "GYMNASTICS", "Dance" to "DANCING",
    "Wheelchair" to "WHEELCHAIR"
)

/** A WHOOP local timestamp with its cycle timezone ("UTC+01:00" or "UTCZ"). */
private fun parseTS(local: String, tz: String): OffsetDateTime =
    OffsetDateTime.of(LocalDateTime.parse(local.replace(' ', 'T')), ZoneOffset.of(tz.removePrefix("UTC")))

private fun iso(t: OffsetDateTime): String = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(t)

/** A bad numeric cell aborts the conversion instead of becoming a zero-valued record. */
private fun Row.num(col: String): Double =
    this[col]?.toDoubleOrNull() ?: throw IllegalArgumentException("$col: not a number")

private fun Row.has(col: String): Boolean = !this[col].isNullOrEmpty()

// ponytail: fields never span lines in WHOOP exports, so a line is a row.
private fun splitCSV(line: String): List<String> {
    val out = mutableListOf<String>()
    val cur = StringBuilder()
    var quoted = false
    var i = 0
    while (i < line.length) {
        val c = line[i]
        when {
            c == '"' && quoted && line.getOrNull(i + 1) == '"' -> {
                cur.append('"')
                i++
            }

            c == '"' -> quoted = !quoted

            c == ',' && !quoted -> {
                out += cur.toString()
                cur.clear()
            }

            else -> cur.append(c)
        }
        i++
    }
    out += cur.toString()
    return out
}

private fun parseCSV(text: String): List<Row> {
    val lines = text.lineSequence().filter { it.isNotBlank() }.map(::splitCSV).toList()
    if (lines.isEmpty()) return emptyList()
    return lines.drop(1).map {
        require(it.size == lines[0].size) { "row has ${it.size} cells, header has ${lines[0].size}" }
        lines[0].zip(it).toMap()
    }
}

/** The sleeps file is read for the respiratory rate alone; the README says why sleep is left out. */
// A sleep still in progress when the export was taken has no wake time to stamp the reading with.
private fun sleeps(rows: List<Row>): List<Record> =
    rows.filter { it.has("Respiratory rate (rpm)") && it.has("Wake onset") }.map { r ->
        mapOf(
            "type" to "respiratory_rate",
            "id" to "whoop:rr:" + r["Sleep onset"],
            "time" to iso(parseTS(r.getValue("Wake onset"), r.getValue("Cycle timezone"))),
            "rpm" to r.num("Respiratory rate (rpm)")
        )
    }

private fun cycles(rows: List<Row>): List<Record> {
    return rows.flatMap { r ->
        val tz = r.getValue("Cycle timezone")
        val key = r.getValue("Cycle start time")
        val out = mutableListOf<Record>()
        if (!r.has("Wake onset")) return@flatMap out
        // Vitals are measured during sleep; stamp them at wake time so they land on the right day.
        val wake = iso(parseTS(r.getValue("Wake onset"), tz))
        if (r.has("Resting heart rate (bpm)")) {
            out +=
                mapOf(
                    "type" to "resting_heart_rate",
                    "id" to "whoop:rhr:$key",
                    "time" to wake,
                    "bpm" to r.num("Resting heart rate (bpm)").toLong()
                )
        }
        if (r.has("Heart rate variability (ms)")) {
            out +=
                mapOf(
                    "type" to "hrv",
                    "id" to "whoop:hrv:$key",
                    "time" to wake,
                    "ms" to r.num("Heart rate variability (ms)")
                )
        }
        if (r.has("Blood oxygen %")) {
            out += mapOf("type" to "spo2", "id" to "whoop:spo2:$key", "time" to wake, "pct" to r.num("Blood oxygen %"))
        }
        out
    }
}

private fun workouts(rows: List<Row>): List<Record> = rows.map { r ->
    val tz = r.getValue("Cycle timezone")
    val key = r.getValue("Workout start time")
    val name = r.getValue("Activity name")
    mapOf(
        "type" to "exercise",
        "id" to "whoop:workout:$key",
        "start" to iso(parseTS(key, tz)),
        "end" to iso(parseTS(r.getValue("Workout end time"), tz)),
        "exerciseType" to (exerciseTypes[name] ?: "OTHER_WORKOUT"),
        "title" to name
    )
}

private val parts = listOf(
    "sleeps.csv" to ::sleeps,
    "physiological_cycles.csv" to ::cycles,
    "workouts.csv" to ::workouts
)

/** The CSVs this app uses, by file name, from a WHOOP export zip (at any depth inside it). */
fun readExport(zip: InputStream): Map<String, String> {
    val wanted = parts.map { it.first }.toSet()
    val zin = ZipInputStream(zip)
    return generateSequence { zin.nextEntry }
        .filter { !it.isDirectory && it.name.substringAfterLast('/') in wanted }
        .associate { it.name.substringAfterLast('/') to zin.readBytes().decodeToString() }
}

/** Health Connect records from the export's CSVs, oldest first. */
fun convert(csvs: Map<String, String>): List<Record> {
    val records = parts.flatMap { (file, fn) ->
        // An account with no workouts has no workouts.csv, which is not a broken export. A zip
        // that is not one at all still fails below, on having produced nothing at all.
        val text = csvs[file] ?: return@flatMap emptyList()
        try {
            fn(parseCSV(text))
        } catch (e: Exception) {
            throw IllegalArgumentException("$file: ${e.message}", e)
        }
    }
    require(records.isNotEmpty()) { "no sleeps, cycles or workouts in the export" }
    check(records)
    // Parse each timestamp once: sortedBy would call time() on every comparison.
    return records.map { it.time() to it }.sortedBy { it.first }.map { it.second }
}

/** Rejects what Health Connect would refuse: duplicate client IDs and empty or inverted intervals. */
private fun check(records: List<Record>) {
    val seen = HashSet<String>()
    for (r in records) {
        val id = r["id"] as String
        require(seen.add(id)) { "duplicate record id $id" }
        val start = r["start"] as String? ?: continue
        require(OffsetDateTime.parse(start).isBefore(OffsetDateTime.parse(r["end"] as String))) {
            "$id: start is not before end"
        }
    }
}

fun counts(records: List<Record>): Map<String, Int> = records.groupingBy { it["type"] as String }.eachCount()

/** The records of the wanted types, within the date range when one is given. */
fun filter(records: List<Record>, types: Set<String>, from: LocalDate? = null, until: LocalDate? = null): List<Record> =
    records.filter {
        it["type"] as String in types &&
            (from == null || it.time().toLocalDate() >= from) &&
            (until == null || it.time().toLocalDate() <= until)
    }

/**
 * The ids of records another app has already covered, from what [existing] found: the days it
 * filled per vital, and the workout intervals it holds. A vital is one reading a night, so a day
 * someone else filled is a duplicate; a workout is a duplicate when it overlaps one already there.
 */
fun alreadyThere(records: List<Record>, days: Map<String, Set<LocalDate>>, workouts: List<LongRange>): Set<String> =
    records.filter { r ->
        val type = r["type"] as String
        if (type == "exercise") {
            val start = OffsetDateTime.parse(r["start"] as String).toInstant().toEpochMilli()
            val end = OffsetDateTime.parse(r["end"] as String).toInstant().toEpochMilli()
            workouts.any { start < it.last && end > it.first }
        } else {
            r.time().toLocalDate() in days[type].orEmpty()
        }
    }.mapTo(mutableSetOf()) { it["id"] as String }

/** "N records (a=1 b=2)", the line the CLI shows. */
fun countsString(counts: Map<String, Int>): String =
    "${counts.values.sum()} records (${counts.toSortedMap().entries.joinToString(" ") { "${it.key}=${it.value}" }})"
