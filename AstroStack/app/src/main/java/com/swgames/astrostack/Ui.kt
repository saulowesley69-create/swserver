package com.swgames.astrostack

import android.content.Context
import android.graphics.Bitmap
import android.view.View
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView

// Vermelho = não ofusca a visão noturna (padrão dos apps de astronomia)
val Red = Color(0xFFFF4D4D)
val RedSoft = Color(0xFFB83A3A)
val RedDim = Color(0xFF5A1A1A)
val Panel = Color(0xF00E0505)
val ChipBg = Color(0xFF220909)

enum class Mode { IDLE, COUNTDOWN, LIGHTS, DARKS, DITHER }

/** Tudo que a tela mostra. A câmera e o empilhador escrevem aqui. */
class AppState {
    var status by mutableStateOf("Abrindo a câmera…")
    var mode by mutableStateOf(Mode.IDLE)
    var countdown by mutableStateOf(0)
    var done by mutableStateOf(0)
    var total by mutableStateOf(0)
    var stacked by mutableStateOf(0)
    var stars by mutableStateOf(0)
    var rejectedPct by mutableStateOf(0f)
    var result by mutableStateOf<Bitmap?>(null)
    var showResult by mutableStateOf(false)

    var exposures by mutableStateOf(listOf(10.0))
    var expIdx by mutableStateOf(0)
    var isos by mutableStateOf(listOf(1600))
    var isoIdx by mutableStateOf(0)
    val frameOptions = listOf(5, 10, 20, 30, 50, 100)
    var framesIdx by mutableStateOf(2)

    var strength by mutableStateOf(50f)
    var removeGradient by mutableStateOf(true)
    var dither by mutableStateOf(true)
    var darkInfo by mutableStateOf<String?>(null)

    val busy get() = mode != Mode.IDLE
    val exposure get() = exposures[expIdx.coerceIn(0, exposures.size - 1)]
    val iso get() = isos[isoIdx.coerceIn(0, isos.size - 1)]
    val frames get() = frameOptions[framesIdx]

    fun expLabel(): String {
        val e = exposure
        return if (e < 1.0) "1/${(1 / e).toInt()} s" else "${e.toInt()} s"
    }
}

interface Actions {
    fun start()
    fun stop()
    fun takeDarks()
    fun clearDarks()
    fun save()
    fun rerender()
}

@Composable
fun AstroApp(s: AppState, a: Actions, preview: (Context) -> View) {
    MaterialTheme(colorScheme = darkColorScheme(primary = Red, background = Color.Black, surface = Panel)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            AndroidView(factory = preview, modifier = Modifier.fillMaxSize())

            val bmp = s.result
            if (s.showResult && bmp != null) {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = "Resultado empilhado",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize().background(Color.Black),
                )
            }

            Column(Modifier.fillMaxWidth().align(Alignment.TopCenter).statusBarsPadding()) {
                TopBar(s)
                AnimatedVisibility(
                    visible = s.busy,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                ) {
                    ProgressRing(s)
                }
            }

            BottomPanel(s, a, Modifier.align(Alignment.BottomCenter))
        }
    }
}

@Composable
private fun TopBar(s: AppState) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("AstroStack", color = Red, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.weight(1f))
        if (s.stacked > 0) {
            StatChip("★ ${s.stars}")
            Spacer(Modifier.width(6.dp))
            StatChip("▣ ${s.stacked}")
            Spacer(Modifier.width(6.dp))
            Box(
                Modifier.clip(RoundedCornerShape(50)).background(if (s.showResult) Red else ChipBg)
                    .clickable { s.showResult = !s.showResult }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text(
                    if (s.showResult) "Resultado" else "Câmera",
                    color = if (s.showResult) Color.Black else Red,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}

@Composable
private fun StatChip(text: String) {
    Box(
        Modifier.clip(RoundedCornerShape(50)).background(ChipBg).padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(text, color = Red, fontSize = 13.sp)
    }
}

@Composable
private fun ProgressRing(s: AppState) {
    val frac = if (s.total > 0) s.done.toFloat() / s.total else 0f
    val anim by animateFloatAsState(frac, label = "progresso")
    Box(
        Modifier.padding(top = 8.dp).size(132.dp).clip(CircleShape).background(Color(0xB3000000)),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize().padding(8.dp)) {
            val stroke = Stroke(width = 8.dp.toPx(), cap = StrokeCap.Round)
            drawArc(RedDim, -90f, 360f, false, style = stroke)
            drawArc(Red, -90f, 360f * anim, false, style = stroke)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            val big = when (s.mode) {
                Mode.COUNTDOWN -> "${s.countdown}"
                Mode.DITHER -> "↔"
                else -> "${s.done}/${s.total}"
            }
            Text(big, color = Red, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            val small = when (s.mode) {
                Mode.COUNTDOWN -> "não encoste"
                Mode.LIGHTS -> "fotografando"
                Mode.DARKS -> "darks"
                Mode.DITHER -> "mexa de leve"
                Mode.IDLE -> ""
            }
            Text(small, color = RedSoft, fontSize = 12.sp)
        }
    }
}

@Composable
private fun BottomPanel(s: AppState, a: Actions, modifier: Modifier) {
    Column(
        modifier.fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
            .background(Panel)
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Text(s.status, color = Red, fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(12.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            SettingPill("Exposição", s.expLabel(), !s.busy, Modifier.weight(1f)) {
                s.expIdx = (s.expIdx + 1) % s.exposures.size
            }
            SettingPill("ISO", "${s.iso}", !s.busy, Modifier.weight(1f)) {
                s.isoIdx = (s.isoIdx + 1) % s.isos.size
            }
            SettingPill("Fotos", "${s.frames}x", !s.busy, Modifier.weight(1f)) {
                s.framesIdx = (s.framesIdx + 1) % s.frameOptions.size
            }
        }

        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Realce", color = RedSoft, fontSize = 13.sp, modifier = Modifier.width(56.dp))
            Slider(
                value = s.strength,
                onValueChange = { s.strength = it },
                onValueChangeFinished = { a.rerender() },
                valueRange = 0f..100f,
                colors = SliderDefaults.colors(
                    thumbColor = Red,
                    activeTrackColor = Red,
                    inactiveTrackColor = RedDim,
                ),
                modifier = Modifier.weight(1f),
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            ToggleChip("Tirar poluição", s.removeGradient, Modifier.weight(1f)) {
                s.removeGradient = !s.removeGradient
                a.rerender()
            }
            ToggleChip("Lembrar de mexer", s.dither, Modifier.weight(1f)) {
                s.dither = !s.dither
            }
        }

        Spacer(Modifier.height(16.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceEvenly,
            modifier = Modifier.fillMaxWidth(),
        ) {
            RoundAction(
                icon = "◐",
                label = if (s.darkInfo != null) "Darks ✓" else "Darks",
                enabled = !s.busy,
                onClick = { a.takeDarks() },
            )
            Shutter(s.busy) { if (s.busy) a.stop() else a.start() }
            RoundAction(icon = "↓", label = "Salvar", enabled = s.stacked > 0, onClick = { a.save() })
        }
        val di = s.darkInfo
        if (di != null) {
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(di, color = RedSoft, fontSize = 11.sp, textAlign = TextAlign.Center)
                Spacer(Modifier.width(10.dp))
                Text(
                    "✕ apagar",
                    color = Red,
                    fontSize = 11.sp,
                    modifier = Modifier.clickable(enabled = !s.busy) { a.clearDarks() },
                )
            }
        }
    }
}

@Composable
private fun SettingPill(label: String, value: String, enabled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier.clip(RoundedCornerShape(16.dp))
            .background(ChipBg)
            .border(1.dp, RedDim, RoundedCornerShape(16.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(label, color = RedSoft, fontSize = 11.sp)
        Text(value, color = if (enabled) Red else RedSoft, fontSize = 17.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun ToggleChip(label: String, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier.clip(RoundedCornerShape(50))
            .background(if (on) RedDim else ChipBg)
            .border(1.dp, if (on) Red else RedDim, RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text((if (on) "● " else "○ ") + label, color = Red, fontSize = 13.sp)
    }
}

@Composable
private fun Shutter(running: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(80.dp).clip(CircleShape).border(4.dp, Red, CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (running) {
            Box(Modifier.size(28.dp).clip(RoundedCornerShape(6.dp)).background(Red))
        } else {
            Box(Modifier.size(62.dp).clip(CircleShape).background(Red))
        }
    }
}

@Composable
private fun RoundAction(
    icon: String,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(54.dp).clip(CircleShape).background(ChipBg)
                .border(1.dp, if (enabled) Red else RedDim, CircleShape)
                .clickable(enabled = enabled) { onClick() },
            contentAlignment = Alignment.Center,
        ) {
            Text(icon, color = if (enabled) Red else RedDim, fontSize = 22.sp)
        }
        Spacer(Modifier.height(4.dp))
        Text(label, color = if (enabled) RedSoft else RedDim, fontSize = 11.sp)
    }
}
