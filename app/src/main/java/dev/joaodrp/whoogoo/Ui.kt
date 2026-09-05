package dev.joaodrp.whoogoo

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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

    /** What the export holds, and which of it the person wants moved. */
    data class Choosing(val counts: Map<String, Int>, val selected: Set<String>) : Ui {
        fun toggle(type: String) = copy(selected = if (type in selected) selected - type else selected + type)

        val total: Int get() = counts.filterKeys { it in selected }.values.sum()
    }

    data class Running(val done: Int, val total: Int, val from: LocalDate, val to: LocalDate, val at: LocalDate) : Ui

    data class Done(val counts: Map<String, Int>) : Ui

    data class Failed(val message: String) : Ui
}

@OptIn(ExperimentalTextApi::class)
private val geist = FontFamily(
    listOf(FontWeight.Medium, FontWeight.Bold).map {
        Font(R.font.geist, it, variationSettings = FontVariation.Settings(it, FontStyle.Normal))
    }
)

// One look in both themes: the blue is the brand.
private val ultramarine = Color(0xFF1E2BFF)
private val white = Color.White
private val soft = Color(0xD9FFFFFF)
private val dim = Color(0x66FFFFFF)
private val track = Color(0x38FFFFFF)
private val black = Color(0xFF0A0A0F)

private val display =
    TextStyle(
        fontFamily = geist,
        fontWeight = FontWeight.Bold,
        fontSize = 56.sp,
        lineHeight = 56.sp,
        letterSpacing = (-2.5).sp
    )
private val title =
    TextStyle(
        fontFamily = geist,
        fontWeight = FontWeight.Bold,
        fontSize = 38.sp,
        lineHeight = 42.sp,
        letterSpacing = (-1.5).sp
    )
private val body = TextStyle(fontFamily = geist, fontWeight = FontWeight.Medium, fontSize = 17.sp, lineHeight = 25.sp)
private val small = TextStyle(fontFamily = geist, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 18.sp)
private val figure =
    TextStyle(
        fontFamily = geist,
        fontWeight = FontWeight.Bold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
        fontFeatureSettings = "tnum"
    )

/** What each record type is called on screen, and what is worth knowing before ticking it. */
private class Kind(val label: String, val note: String)

private val labels = mapOf(
    "resting_heart_rate" to Kind("Resting heart rate", "Measured while you slept"),
    "hrv" to Kind("Heart rate variability", "RMSSD, measured while you slept"),
    "spo2" to Kind("Blood oxygen", "A night average, not a spot reading"),
    "respiratory_rate" to Kind("Respiratory rate", "A night average, not a spot reading"),
    "exercise" to Kind("Workouts", "Time and type only, no heart rate or route")
)

private val month: DateTimeFormatter = DateTimeFormatter.ofPattern("MMMM\nyyyy")
private val day: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy")

@Composable
fun App(ui: Ui, onPick: () -> Unit, onReset: () -> Unit, onToggle: (String) -> Unit, onImport: () -> Unit) {
    val scheme =
        darkColorScheme(
            primary = black,
            onPrimary = white,
            background = ultramarine,
            onBackground = white,
            surface = ultramarine,
            onSurface = white
        )
    MaterialTheme(colorScheme = scheme) {
        Surface(Modifier.fillMaxSize(), color = ultramarine) {
            Column(Modifier.safeDrawingPadding().fillMaxSize().padding(horizontal = 24.dp, vertical = 20.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(ImageVector.vectorResource(R.drawable.mark), null, Modifier.size(22.dp), tint = white)
                    Text("whoogoo", style = small.copy(fontWeight = FontWeight.Bold), color = white)
                }
                Column(
                    Modifier.weight(1f).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.Center
                ) {
                    when (ui) {
                        Ui.Idle -> {
                            Text("Your WHOOP\nhistory, into\nGoogle Health.", style = title, color = white)
                            Text(
                                "Choose the export zip WHOOP emailed you. Your vitals and workouts go into Health " +
                                    "Connect, and Google Health syncs them to your account.",
                                style = body,
                                color = soft,
                                modifier = Modifier.padding(top = 20.dp)
                            )
                        }

                        Ui.Reading -> {
                            Text("Reading\nyour export", style = display, color = white)
                            Band(0f)
                        }

                        is Ui.Choosing -> {
                            Text("What should\nmove over?", style = title, color = white)
                            Column(Modifier.padding(top = 20.dp)) {
                                for ((type, kind) in labels) {
                                    val n = ui.counts[type] ?: continue
                                    val on = type in ui.selected
                                    Row(
                                        Modifier.fillMaxWidth().clickable { onToggle(type) }.padding(vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(Modifier.weight(1f).padding(end = 12.dp)) {
                                            Text(kind.label, style = body, color = if (on) soft else dim)
                                            Text(kind.note, style = small, color = dim)
                                        }
                                        Text("%,d".format(n), style = figure, color = if (on) white else dim)
                                        Checkbox(
                                            checked = on,
                                            onCheckedChange = { onToggle(type) },
                                            colors = CheckboxDefaults.colors(
                                                checkedColor = white,
                                                checkmarkColor = ultramarine,
                                                uncheckedColor = track
                                            )
                                        )
                                    }
                                }
                            }
                            Text(
                                "Sleep, calories and skin temperature are missing on purpose: the export cannot " +
                                    "carry them across without inventing numbers.",
                                style = small,
                                color = dim,
                                modifier = Modifier.padding(top = 20.dp)
                            )
                        }

                        is Ui.Running -> {
                            Text(month.format(ui.at), style = display, color = white)
                            Text(
                                "%,d of %,d records moved".format(ui.done, ui.total),
                                style = body,
                                color = soft,
                                modifier = Modifier.padding(top = 16.dp)
                            )
                            val span = ChronoUnit.DAYS.between(ui.from, ui.to).coerceAtLeast(1)
                            Band(ChronoUnit.DAYS.between(ui.from, ui.at).toFloat() / span)
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(day.format(ui.from), style = small, color = soft)
                                Text(day.format(ui.to), style = small, color = soft)
                            }
                        }

                        is Ui.Done -> {
                            Text("All moved\nover.", style = title, color = white)
                            Column(Modifier.padding(top = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                for ((type, kind) in labels) {
                                    val n = ui.counts[type] ?: continue
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text(kind.label, style = body, color = soft)
                                        Text("%,d".format(n), style = figure, color = white)
                                    }
                                }
                            }
                            Text(
                                "Now open Google Health, connect Health Connect and allow historical data. " +
                                    "Older records take a while to show up.",
                                style = body,
                                color = soft,
                                modifier = Modifier.padding(top = 24.dp)
                            )
                        }

                        is Ui.Failed -> {
                            Text("That didn't\nwork.", style = title, color = white)
                            Text(ui.message, style = body, color = soft, modifier = Modifier.padding(top = 20.dp))
                        }
                    }
                }
                when (ui) {
                    Ui.Idle -> Action("Choose export", onPick)
                    is Ui.Choosing -> Action("Import %,d records".format(ui.total), onImport, enabled = ui.total > 0)
                    is Ui.Done -> Action("Import another export", onPick)
                    is Ui.Failed -> Action("Try again", onReset)
                    else -> Spacer(Modifier.height(56.dp))
                }
            }
        }
    }
}

/** The export's date range, filled up to the record being written. */
@Composable
private fun Band(fraction: Float) {
    Box(
        Modifier.fillMaxWidth().padding(
            top = 28.dp,
            bottom = 10.dp
        ).height(22.dp).background(track, RoundedCornerShape(2.dp))
    ) {
        Box(
            Modifier.fillMaxWidth(
                fraction.coerceIn(0.01f, 1f)
            ).fillMaxHeight().background(white, RoundedCornerShape(2.dp))
        )
    }
}

@Composable
private fun Action(label: String, onClick: () -> Unit, enabled: Boolean = true) {
    Button(
        onClick,
        Modifier.fillMaxWidth().height(56.dp),
        enabled = enabled,
        shape = CircleShape,
        colors = ButtonDefaults.buttonColors(containerColor = black, contentColor = white)
    ) {
        Text(label, style = body.copy(fontWeight = FontWeight.Bold))
    }
}
