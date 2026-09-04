package dev.joaodrp.whoogoo

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

sealed interface Ui {
    data object Idle : Ui

    data object Reading : Ui

    data class Running(val done: Int, val total: Int, val from: LocalDate, val to: LocalDate, val at: LocalDate) : Ui

    data class Done(val counts: Map<String, Int>) : Ui

    data class Failed(val message: String) : Ui
}

@OptIn(ExperimentalTextApi::class)
private val manrope = FontFamily(
    listOf(FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold).map {
        Font(R.font.manrope, it, variationSettings = FontVariation.Settings(it, FontStyle.Normal))
    }
)

private val paper = Color(0xFFF3F4F0)
private val ink = Color(0xFF151B26)
private val night = Color(0xFF10151F)
private val mist = Color(0xFF6F7786)
private val fog = Color(0xFF9AA2AF)
private val amber = Color(0xFFE8A23A)

private val display =
    TextStyle(
        fontFamily = manrope,
        fontWeight = FontWeight.SemiBold,
        fontSize = 34.sp,
        lineHeight = 38.sp,
        letterSpacing = (-0.8).sp
    )
private val body = TextStyle(fontFamily = manrope, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp)
private val small =
    TextStyle(fontFamily = manrope, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 18.sp)
private val figure =
    TextStyle(
        fontFamily = manrope,
        fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp,
        lineHeight = 24.sp,
        fontFeatureSettings = "tnum"
    )

private val labels = mapOf(
    "sleep" to "Nights of sleep", "respiratory_rate" to "Respiratory rate",
    "resting_heart_rate" to "Resting heart rate", "hrv" to "Heart rate variability", "spo2" to "Blood oxygen",
    "skin_temperature" to "Skin temperature", "total_calories" to "Daily calories", "exercise" to "Workouts",
    "active_calories" to "Workout calories"
)

private val month: DateTimeFormatter = DateTimeFormatter.ofPattern("MMMM yyyy")
private val day: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy")

@Composable
fun App(ui: Ui, onPick: () -> Unit, onReset: () -> Unit) {
    val dark = isSystemInDarkTheme()
    val fg = if (dark) paper else ink
    val muted = if (dark) fog else mist
    val scheme = if (dark) {
        darkColorScheme(
            primary = amber,
            onPrimary = night,
            background = night,
            onBackground = paper,
            surface = night,
            onSurface = paper
        )
    } else {
        lightColorScheme(
            primary = ink,
            onPrimary = paper,
            background = paper,
            onBackground = ink,
            surface = paper,
            onSurface = ink
        )
    }
    MaterialTheme(colorScheme = scheme) {
        Surface(Modifier.fillMaxSize(), color = scheme.background) {
            Column(Modifier.safeDrawingPadding().fillMaxSize().padding(horizontal = 24.dp, vertical = 20.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(ImageVector.vectorResource(R.drawable.hypnogram), null, Modifier.size(24.dp), tint = amber)
                    Text(
                        "whoogoo",
                        style = small.copy(fontWeight = FontWeight.Bold, letterSpacing = 0.4.sp),
                        color = fg
                    )
                }
                Spacer(Modifier.weight(1f))
                when (ui) {
                    Ui.Idle -> {
                        Text("Your WHOOP history,\ninto Google Health.", style = display, color = fg)
                        Text(
                            "Choose the export zip WHOOP emailed you. Sleep, vitals and workouts go into Health " +
                                "Connect, and Google Health syncs them to your account.",
                            style = body,
                            color = muted,
                            modifier = Modifier.padding(top = 16.dp)
                        )
                        Action("Choose export", onPick)
                    }

                    Ui.Reading -> {
                        Text("Reading", style = small, color = muted)
                        Text("your export", style = display, color = fg)
                        Timeline(0f, fg)
                    }

                    is Ui.Running -> {
                        Text("Importing", style = small, color = muted)
                        Text(month.format(ui.at), style = display, color = fg)
                        val span = ChronoUnit.DAYS.between(ui.from, ui.to).coerceAtLeast(1)
                        Timeline(ChronoUnit.DAYS.between(ui.from, ui.at).toFloat() / span, fg)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(day.format(ui.from), style = small, color = muted)
                            Text(day.format(ui.to), style = small, color = muted)
                        }
                        Text(
                            "%,d of %,d records".format(ui.done, ui.total),
                            style = body,
                            color = muted,
                            modifier = Modifier.padding(top = 20.dp)
                        )
                    }

                    is Ui.Done -> {
                        Text("All moved over.", style = display, color = fg)
                        Column(Modifier.padding(top = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            for ((type, label) in labels) {
                                val n = ui.counts[type] ?: continue
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text(label, style = body, color = muted)
                                    Text("%,d".format(n), style = figure, color = fg)
                                }
                            }
                        }
                        Text(
                            "Now open Google Health, connect Health Connect and allow historical data. " +
                                "Older records take a while to show up.",
                            style = body,
                            color = muted,
                            modifier = Modifier.padding(top = 24.dp)
                        )
                        Action("Import another export", onPick)
                    }

                    is Ui.Failed -> {
                        Text("That didn't work.", style = display, color = fg)
                        Text(ui.message, style = body, color = muted, modifier = Modifier.padding(top = 16.dp))
                        Action("Try again", onReset)
                    }
                }
            }
        }
    }
}

/** The export's date range, filled up to the record being written. */
@Composable
private fun Timeline(fraction: Float, fg: Color) {
    LinearProgressIndicator(
        progress = { fraction.coerceIn(0.02f, 1f) },
        modifier = Modifier.fillMaxWidth().padding(top = 28.dp, bottom = 10.dp).height(6.dp),
        color = amber,
        trackColor = fg.copy(alpha = 0.12f),
        strokeCap = StrokeCap.Round,
        gapSize = 0.dp,
        drawStopIndicator = {}
    )
}

@Composable
private fun Action(label: String, onClick: () -> Unit) {
    Button(
        onClick,
        Modifier.fillMaxWidth().padding(top = 32.dp).height(56.dp),
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors()
    ) {
        Text(label, style = body.copy(fontWeight = FontWeight.SemiBold))
    }
}
