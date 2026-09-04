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
    private val permissions by lazy {
        packageManager.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions.orEmpty().filter { it.startsWith("android.permission.health.") }.toSet()
    }

    private val client by lazy { HealthConnectClient.getOrCreate(this) }
    private var ui by mutableStateOf<Ui>(Ui.Idle)
    private var pending: (() -> InputStream)? = null

    private val requestPermissions =
        registerForActivityResult(PermissionController.createRequestPermissionResultContract()) { granted ->
            val denied = permissions - granted
            if (denied.isEmpty()) import() else fail("permissions denied: $denied")
        }

    private val pickZip = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { start { contentResolver.openInputStream(it) ?: error("cannot open $it") } }
    }

    // Providers label zips differently; mail clients and Drive often fall back to octet-stream.
    private val zipTypes = arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(SystemBarStyle.dark(Color.TRANSPARENT), SystemBarStyle.dark(Color.TRANSPARENT))
        setContent { App(ui, onPick = { pickZip.launch(zipTypes) }, onReset = { ui = Ui.Idle }) }
        onNewIntent(intent)
    }

    /** The CLI's launch; singleTop delivers it here even while the activity is already showing. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Exported activity: the extra is untrusted, so only a plain file name inside filesDir counts.
        intent.getStringExtra("zip")?.takeIf {
            '/' !in it
        }?.let { name -> start { File(filesDir, name).inputStream() } }
    }

    private fun start(open: () -> InputStream) {
        pending = open
        if (HealthConnectClient.getSdkStatus(this) != HealthConnectClient.SDK_AVAILABLE) {
            return fail("Health Connect is not available on this device")
        }
        lifecycleScope.launch {
            if (client.permissionController.getGrantedPermissions().containsAll(permissions)) {
                import()
            } else {
                requestPermissions.launch(permissions)
            }
        }
    }

    private fun import() = lifecycleScope.launch(Dispatchers.IO) {
        val open = pending ?: return@launch
        try {
            ui = Ui.Reading
            val records = open().use { convert(readExport(it)) }
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
        } catch (e: Exception) {
            fail(e.message ?: e.toString())
        }
    }

    private fun fail(message: String) {
        log("error: $message")
        ui = Ui.Failed(message)
    }

    private fun log(msg: String) = Log.i("Whoogoo", msg)
}
