package dev.joaodrp.whoogoo

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.lifecycleScope
import java.io.File
import java.io.InputStream
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray

/**
 * Converts a WHOOP export zip and upserts it into Health Connect. The zip comes from the file
 * picker, or from the CLI, which copies it into filesDir and starts the activity with its name in
 * the "zip" extra. Progress goes to logcat under the "Whoogoo" tag; the CLI stops at "done" or a
 * line starting with "error:", then pulls filesDir/records.json for `verify`.
 */
class MainActivity : ComponentActivity() {
    // The manifest is the only list of Health Connect permissions.
    private val declared by lazy {
        packageManager.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions.orEmpty().filter { it.startsWith("android.permission.health.") }
    }

    /** Writing is what the app is for, so it is asked for up front. */
    private val permissions by lazy { declared.filter { "WRITE_" in it }.toSet() }

    /** Reading is only for the duplicate check, so it is asked for when someone taps it. */
    private val readPermissions by lazy { declared.filter { "READ_" in it }.toSet() }

    private val client by lazy { HealthConnectClient.getOrCreate(this) }
    private var ui by mutableStateOf<Ui>(Ui.Idle)
    private var pending: (() -> InputStream)? = null
    private var converted: List<Record> = emptyList()

    /** What the CLI asked for; null when a person picked the file and chooses on screen. */
    private var cli: Cli? = null

    /** Ids another app already covers; null until someone asks for the duplicate check. */
    private var already: Set<String>? = null

    private class Cli(
        val skip: Set<String>,
        val from: LocalDate?,
        val until: LocalDate?,
        /** A file in filesDir listing client record ids to delete, one per line, instead of importing. */
        val delete: String?,
        /** Report what the run would do and stop. */
        val dry: Boolean
    )

    private val requestPermissions =
        registerForActivityResult(PermissionController.createRequestPermissionResultContract()) { granted ->
            val denied = permissions - granted
            if (denied.isEmpty()) read() else fail("permissions denied: $denied")
        }

    private val requestRead =
        registerForActivityResult(PermissionController.createRequestPermissionResultContract()) { granted ->
            if (granted.containsAll(readPermissions)) scan() else redraw(denied = true)
        }

    private val pickZip = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            cli = null
            start { contentResolver.openInputStream(it) ?: error("cannot open $it") }
        }
    }

    // Providers label zips differently; mail clients and Drive often fall back to octet-stream.
    private val zipTypes = arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(SystemBarStyle.dark(Color.TRANSPARENT), SystemBarStyle.dark(Color.TRANSPARENT))
        setContent {
            App(
                ui,
                onPick = { pickZip.launch(zipTypes) },
                onReset = { ui = Ui.Idle },
                onToggle = { type ->
                    (ui as? Ui.Choosing)?.let {
                        val selected = if (type in it.selected) it.selected - type else it.selected + type
                        choose(selected, it.from, it.until)
                    }
                },
                onDates = { from, until -> (ui as? Ui.Choosing)?.let { choose(it.selected, from, until) } },
                onCheckExisting = { check() },
                onImport = { (ui as? Ui.Choosing)?.let { importSelected(it.selected, it.from, it.until) } }
            )
        }
        // Only on a fresh start: the launch intent outlives recreation, and acting on it again
        // would import or delete a second time behind the person's back.
        if (savedInstanceState == null) onNewIntent(intent)
    }

    /** The CLI's launch; singleTop delivers it here even while the activity is already showing. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Exported activity: the extra is untrusted, so only a plain file name inside filesDir counts.
        val name = intent.getStringExtra("zip")?.takeIf { '/' !in it } ?: return
        // Consumed, so a redelivery of the same intent does nothing.
        intent.removeExtra("zip")
        cli = try {
            Cli(
                skip = intent.getStringExtra("skip").orEmpty().split(',').filter { it.isNotBlank() }.toSet(),
                from = intent.getStringExtra("from")?.let(LocalDate::parse),
                until = intent.getStringExtra("until")?.let(LocalDate::parse),
                delete = intent.getStringExtra("delete")?.takeIf { '/' !in it },
                dry = intent.getBooleanExtra("dry", false)
            )
        } catch (e: Exception) {
            // A malformed date would otherwise throw here and leave the CLI waiting for a line
            // that never comes.
            return fail("bad launch arguments: ${e.message}")
        }
        start { File(filesDir, name).inputStream() }
    }

    private fun start(open: () -> InputStream) {
        if (ui is Ui.Reading || ui is Ui.Running) return
        ui = Ui.Reading
        pending = open
        // What the last export had in common with another app says nothing about this one.
        already = null
        if (HealthConnectClient.getSdkStatus(this) != HealthConnectClient.SDK_AVAILABLE) {
            return fail("Health Connect is not available on this device")
        }
        lifecycleScope.launch {
            if (client.permissionController.getGrantedPermissions().containsAll(permissions)) {
                read()
            } else {
                requestPermissions.launch(permissions)
            }
        }
    }

    /** Converts the export, then asks what to import, or applies what the CLI already decided. */
    private fun read() = lifecycleScope.launch(Dispatchers.IO) {
        val open = pending ?: return@launch
        try {
            ui = Ui.Reading
            converted = open().use { convert(readExport(it)) }
            val types = counts(converted).keys
            val c = cli
            when {
                c == null ->
                    choose(types, converted.first().time().toLocalDate(), converted.last().time().toLocalDate())

                c.delete != null -> remove(File(filesDir, c.delete).readLines(), c.dry)

                else -> insert(filter(converted, types - c.skip, c.from, c.until), c.dry)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            fail(e.message ?: e.toString())
        }
    }

    /** Redraws the choosing screen: the counts follow the dates, the ticks do not. */
    private fun choose(selected: Set<String>, from: LocalDate, until: LocalDate, denied: Boolean = false) {
        val inRange = filter(converted, counts(converted).keys, from, until)
        ui = Ui.Choosing(
            counts = counts(inRange),
            skipped = already?.let { ids -> counts(inRange.filter { it["id"] in ids }) },
            selected = selected,
            from = from,
            until = until,
            first = converted.first().time().toLocalDate(),
            last = converted.last().time().toLocalDate(),
            denied = denied
        )
    }

    private fun redraw(denied: Boolean = false) {
        (ui as? Ui.Choosing)?.let { choose(it.selected, it.from, it.until, denied) }
    }

    /** Turns the duplicate check on, asking for read access the first time, or back off. */
    private fun check() {
        if (already != null) {
            already = null
            redraw()
            return
        }
        lifecycleScope.launch {
            if (client.permissionController.getGrantedPermissions().containsAll(readPermissions)) {
                scan()
            } else {
                requestRead.launch(readPermissions)
            }
        }
    }

    /** Looks over the whole export's span once, so changing the dates afterwards costs nothing. */
    private fun scan() = lifecycleScope.launch(Dispatchers.IO) {
        try {
            val found = existing(
                client,
                packageName,
                converted.first().time().toInstant(),
                converted.last().time().toInstant().plus(1, ChronoUnit.DAYS)
            )
            already = alreadyThere(converted, found.days, found.workouts)
            log("already there: ${already?.size} of ${converted.size}, from ${found.apps}")
            redraw()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("duplicate check failed: ${e.message}")
            redraw(denied = true)
        }
    }

    private fun importSelected(types: Set<String>, from: LocalDate, until: LocalDate) {
        // Taken on the thread the tap arrived on, so a second tap finds it already taken.
        if (ui !is Ui.Choosing) return
        ui = Ui.Reading
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val ids = already.orEmpty()
                insert(filter(converted, types, from, until).filterNot { it["id"] in ids })
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(e.message ?: e.toString())
            }
        }
    }

    /**
     * Deletes records this app wrote, named by the client record ids it gave them. Only ids the
     * export in hand actually produced are accepted, so a stray line cannot reach anything else,
     * and Health Connect scopes a client record id to the app that wrote it either way.
     */
    private suspend fun remove(lines: List<String>, dry: Boolean) {
        val wanted = lines.map { it.trim() }.filter { it.isNotEmpty() }
        val byId = converted.associateBy { it["id"] as String }
        val unknown = wanted.filterNot { it in byId }
        require(unknown.isEmpty()) { "${unknown.size} of ${wanted.size} ids are not from this export" }
        val records = wanted.mapNotNull { byId[it] }
        require(records.isNotEmpty()) { "nothing to delete: no ids given" }
        log("deleting " + countsString(counts(records)))
        if (dry) {
            log("dry run, nothing deleted\ndone")
            return
        }
        for ((type, group) in records.groupBy { it["type"] as String }) {
            val ids = group.map { it["id"] as String }
            client.deleteRecords(recordClass(type), recordIdsList = emptyList(), clientRecordIdsList = ids)
            log("deleted ${ids.size} $type")
        }
        ui = Ui.Done(counts(records), removed = true)
        log("done")
    }

    /** Upserts the records and leaves them in records.json for the CLI's `verify`. */
    private suspend fun insert(records: List<Record>, dry: Boolean = false) {
        require(records.isNotEmpty()) { "nothing to import: everything was filtered out" }
        if (dry) {
            log(countsString(counts(records)) + "\ndry run, nothing imported\ndone")
            return
        }
        File(filesDir, "records.json").writeText(JSONArray(records).toString(1))
        val counts = counts(records)
        log(countsString(counts))
        val from = records.first().time().toLocalDate()
        val to = records.last().time().toLocalDate()
        var done = 0
        for (chunk in records.chunked(100)) {
            ui = Ui.Running(done, records.size, from, to, chunk.first().time().toLocalDate())
            client.insertRecords(chunk.map(::toRecord))
            done += chunk.size
            log("inserted $done/${records.size}")
        }
        ui = Ui.Done(counts)
        log("done")
    }

    private fun fail(message: String) {
        log("error: $message")
        ui = Ui.Failed(message)
    }

    private fun log(msg: String) = Log.i("Whoogoo", msg)
}
