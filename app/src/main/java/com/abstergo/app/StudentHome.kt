package com.abstergo.app

import android.content.Context
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime

// ---------- Barra superior: nombre + foto (con menú) ----------
@Composable
fun HomeTopBar(profile: Profile, subtitle: String, avatar: ImageBitmap?, dark: Boolean, onToggle: () -> Unit, onReset: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 20.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("Abstergo", fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.weight(1f))
        Box {
            Avatar(avatar, 44, Modifier.clickable { menu = true })
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text(profile.name, fontWeight = FontWeight.Bold)
                    Text(subtitle, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                HorizontalDivider()
                DropdownMenuItem(text = { Text(if (dark) "Modo claro" else "Modo oscuro") }, onClick = { menu = false; onToggle() })
                DropdownMenuItem(text = { Text("Cambiar de perfil") }, onClick = { menu = false; onReset() })
            }
        }
    }
}

private data class TabItem(val label: String, val symbol: Int)
private val TABS = listOf(TabItem("Horario", 0), TabItem("Evaluaciones", 1), TabItem("Entrenar", 3), TabItem("Calendario", 4))

sealed class ImportState {
    data class Confirm(val s: Schedule, val replacing: Boolean) : ImportState()
    data class Msg(val text: String) : ImportState()
}

private fun analyzeImport(ctx: Context, uri: Uri, group: String, existing: List<Schedule>): ImportState {
    val name = ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
        val i = c.getColumnIndex("_display_name"); if (c.moveToFirst() && i >= 0) c.getString(i) else ""
    } ?: ""
    val grids = Xlsx.read(ctx, uri)
        ?: return ImportState.Msg("No pude leer el archivo. Debe ser un Excel .xlsx (si es .xls, ábrelo y guárdalo como .xlsx).")
    val head = grids.flatMap { g -> g.rows.toSortedMap().entries.take(4).flatMap { e -> e.value.values } }.joinToString(" ")
    val section = classify(name) ?: classify(head)
    return when (section) {
        Section.HORARIO -> when (val r = parseSchedule(grids, group)) {
            is Parsed.Ok -> ImportState.Confirm(r.s, existing.any { it.date == r.s.date && norm(it.group) == norm(r.s.group) })
            is Parsed.Err -> ImportState.Msg(r.msg)
        }
        null -> ImportState.Msg("No pude saber a qué sección pertenece este archivo. Ponle al nombre una palabra como «horario», «entrenamiento», «tareas», «pruebas» o «eventos».")
        else -> ImportState.Msg("Detecté que es de ${section.label}. Esa sección se activa en una próxima versión.")
    }
}

// ---------- Inicio del estudiante ----------
@Composable
fun StudentHome(profile: Profile, dark: Boolean, onToggle: () -> Unit, pendingUri: Uri?, onConsumeUri: () -> Unit, onReset: () -> Unit) {
    val ctx = LocalContext.current
    var tab by remember { mutableStateOf(0) }
    var schedules by remember { mutableStateOf(loadSchedules(ctx)) }
    var importUri by remember { mutableStateOf<Uri?>(null) }
    var importState by remember { mutableStateOf<ImportState?>(null) }
    val avatar = remember { File(ctx.filesDir, "profile.jpg").let { if (it.exists()) loadBitmap(ctx, Uri.fromFile(it)) else null } }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { if (it != null) importUri = it }

    LaunchedEffect(pendingUri) { if (pendingUri != null) { importUri = pendingUri; onConsumeUri() } }
    LaunchedEffect(importUri) {
        val u = importUri ?: return@LaunchedEffect
        importUri = null
        importState = withContext(Dispatchers.IO) { analyzeImport(ctx, u, profile.extra, schedules) }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { HomeTopBar(profile, "Estudiante · Grupo ${profile.extra}", avatar, dark, onToggle, onReset) },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                TABS.forEachIndexed { i, t ->
                    NavigationBarItem(
                        selected = tab == i, onClick = { tab = i },
                        icon = { LineSymbol(t.symbol, 1f, LocalContentColor.current, Modifier.size(26.dp), 5f) },
                        label = { Text(t.label, fontSize = 12.sp) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.onPrimary,
                            selectedTextColor = MaterialTheme.colorScheme.onBackground,
                            indicatorColor = MaterialTheme.colorScheme.primary,
                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    )
                }
            }
        }
    ) { pad ->
        AnimatedContent(
            targetState = tab, modifier = Modifier.padding(pad).fillMaxSize(), label = "tab",
            transitionSpec = {
                val dir = if (targetState > initialState) 1 else -1
                (slideInHorizontally(tween(350)) { it / 4 * dir } + fadeIn(tween(350))) togetherWith
                    (slideOutHorizontally(tween(250)) { -it / 4 * dir } + fadeOut(tween(200)))
            }
        ) { t ->
            when (t) {
                0 -> HorarioTab(schedules) { picker.launch(arrayOf("*/*")) }
                1 -> ComingSoon(1, "Evaluaciones", "Aquí verás tus trabajos prácticos, tareas evaluativas y pruebas parciales y finales. Se activa al importar su Excel (próxima versión).")
                2 -> ComingSoon(3, "Entrenar", "Aquí estarán los entrenamientos de tus asignaturas con tu progreso. Se activa al importar su Excel (próxima versión).")
                else -> ComingSoon(4, "Calendario", "Aquí verás el calendario de eventos de la escuela. Se activa al importar su Excel (próxima versión).")
            }
        }
    }

    importState?.let { st ->
        when (st) {
            is ImportState.Confirm -> AlertDialog(
                onDismissRequest = { importState = null },
                containerColor = MaterialTheme.colorScheme.surface,
                title = { Text("Importar horario") },
                text = {
                    Text(
                        "Detecté un horario del ${dateText(st.s.date)}.\nGrupo ${st.s.group} · ${st.s.slots.count { it.value.isNotBlank() }} turnos con clase." +
                            if (st.replacing) "\n\nReemplazará el que ya tienes de ese día." else ""
                    )
                },
                confirmButton = {
                    TextButton({
                        val list = schedules.filterNot { it.date == st.s.date && norm(it.group) == norm(st.s.group) } + st.s
                        schedules = list; saveSchedules(ctx, list); importState = null; tab = 0
                    }) { Text("Importar") }
                },
                dismissButton = { TextButton({ importState = null }) { Text("Cancelar") } }
            )
            is ImportState.Msg -> AlertDialog(
                onDismissRequest = { importState = null },
                containerColor = MaterialTheme.colorScheme.surface,
                text = { Text(st.text) },
                confirmButton = { TextButton({ importState = null }) { Text("Entendido") } }
            )
        }
    }
}

@Composable
fun ComingSoon(symbol: Int, title: String, text: String) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
    ) {
        LineSymbol(symbol, 1f, MaterialTheme.colorScheme.onBackground, Modifier.size(110.dp))
        Spacer(Modifier.height(24.dp))
        Text(title, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        Text(text, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 22.sp)
    }
}

// ---------- Pestaña Horario ----------
private fun relLabel(d: LocalDate, today: LocalDate) = when {
    d == today -> "Hoy"
    d == today.plusDays(1) -> "Mañana"
    d.isBefore(today) -> "Anterior"
    else -> "Próximo"
}

private fun fmtLeft(sec: Int): String =
    if (sec >= 3600) "${sec / 3600} h ${(sec % 3600) / 60} min" else "%d:%02d".format(sec / 60, sec % 60)

@Composable
fun HorarioTab(schedules: List<Schedule>, onImportClick: () -> Unit) {
    val owner = LocalLifecycleOwner.current
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    // Se actualiza cada segundo solo mientras la app está abierta (nada en segundo plano)
    LaunchedEffect(owner) {
        owner.repeatOnLifecycle(Lifecycle.State.STARTED) { while (true) { now = LocalDateTime.now(); delay(1000) } }
    }
    var mode by remember { mutableStateOf(0) } // 0 = hoy, 1 = historial, 2 = ver uno del historial
    var viewing by remember { mutableStateOf<Schedule?>(null) }
    BackHandler(enabled = mode != 0) { mode = if (mode == 2) 1 else 0 }
    val today = now.toLocalDate()
    val current = schedules.firstOrNull { it.date == today }
        ?: schedules.filter { it.date.isAfter(today) }.minByOrNull { it.date.toEpochDay() }

    Column(Modifier.fillMaxSize()) {
        when (mode) {
            1 -> {
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Historial", fontSize = 28.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.weight(1f))
                    OutlinedButton({ mode = 0 }, shape = RoundedCornerShape(14.dp)) { Text("Volver") }
                }
                if (schedules.isEmpty()) {
                    Text("Aún no hay horarios guardados.", Modifier.padding(20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(schedules.sortedByDescending { it.date.toEpochDay() }) { s ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surface)
                                .clickable { viewing = s; mode = 2 }.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(dateText(s.date), fontWeight = FontWeight.SemiBold)
                                Text("Grupo ${s.group}", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text(relLabel(s.date, today), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            2 -> {
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Horario", fontSize = 28.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.weight(1f))
                    OutlinedButton({ mode = 1 }, shape = RoundedCornerShape(14.dp)) { Text("Volver") }
                }
                viewing?.let { ScheduleView(it, now, relLabel(it.date, today)) }
            }
            else -> {
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Horario", fontSize = 28.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.weight(1f))
                    OutlinedButton({ mode = 1 }, shape = RoundedCornerShape(14.dp)) { Text("Historial") }
                    Spacer(Modifier.width(8.dp))
                    Button(onImportClick, shape = RoundedCornerShape(14.dp)) { Text("Importar") }
                }
                if (current == null) {
                    Column(
                        Modifier.fillMaxSize().padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center
                    ) {
                        LineSymbol(0, 1f, MaterialTheme.colorScheme.onBackground, Modifier.size(110.dp))
                        Spacer(Modifier.height(24.dp))
                        Text("Importa el horario enviado por la dirección de la escuela", fontSize = 22.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, lineHeight = 28.sp)
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "Toca el archivo Excel y elige «Abrir con Abstergo», o usa el botón Importar.",
                            textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant, lineHeight = 22.sp
                        )
                    }
                } else {
                    ScheduleView(current, now, relLabel(current.date, today))
                }
            }
        }
    }
}

@Composable
fun ScheduleView(s: Schedule, now: LocalDateTime, label: String) {
    val live = s.date == now.toLocalDate()
    val nowSec = now.toLocalTime().toSecondOfDay()
    val afternoon = listOf("T1", "T2", "T3").any { !s.slots[it].isNullOrBlank() }
    val blocks = BLOCKS.filter { afternoon || !it.pm }
    var detail by remember { mutableStateOf<Block?>(null) }

    fun codeOf(b: Block) = (b.key?.let { s.slots[it] } ?: "").trim()
    fun stateOf(b: Block) = when {
        !live -> 2
        nowSec >= b.end * 60 -> -1
        nowSec >= b.start * 60 -> 0
        else -> 1
    }
    fun statusText(b: Block) = when (stateOf(b)) {
        2 -> "Horario del ${dateText(s.date)}"
        -1 -> "Ya pasó"
        0 -> "En curso · termina en ${fmtLeft(b.end * 60 - nowSec)}"
        else -> "Empieza en ${fmtLeft(b.start * 60 - nowSec)}"
    }
    fun titleOf(b: Block): String {
        if (b.fixed) return b.name
        val c = codeOf(b)
        return if (c.isEmpty()) "Sin clase" else (subjectName(c) ?: c)
    }

    val classCodes = blocks.filter { !it.fixed }.map { codeOf(it) }.filter { it.isNotEmpty() }
    val fullFL = classCodes.size >= 4 && classCodes.all { norm(it) == "fl" }
    val upcoming = if (live) blocks.firstOrNull { !it.fixed && it.start * 60 > nowSec && codeOf(it).isNotEmpty() } else null

    LazyColumn(contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(dateText(s.date), fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    Text("Grupo ${s.group}", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Box(
                    Modifier.border(1.5.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(50)).padding(horizontal = 12.dp, vertical = 4.dp)
                ) { Text(label, fontSize = 13.sp) }
            }
            Spacer(Modifier.height(8.dp))
        }
        if (fullFL) item {
            Text("Formación Laboral · día completo", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 6.dp))
        }
        if (live) item {
            val first = blocks.first(); val last = blocks.last()
            val idx = blocks.indexOfFirst { nowSec >= it.start * 60 && nowSec < it.end * 60 }
            val (l1, l2) = when {
                idx >= 0 -> {
                    val b = blocks[idx]
                    Pair("Ahora · " + (if (b.fixed) b.name else titleOf(b) + " · " + b.name), "Termina en " + fmtLeft(b.end * 60 - nowSec))
                }
                nowSec < first.start * 60 -> Pair("Las clases empiezan a las ${hm(first.start)}", "Faltan " + fmtLeft(first.start * 60 - nowSec))
                nowSec >= last.end * 60 -> Pair("La jornada terminó", "¡Buen trabajo hoy!")
                else -> {
                    val nb = blocks.first { it.start * 60 > nowSec }
                    Pair("Cambio de turno", "Sigue ${nb.name} a las ${hm(nb.start)}")
                }
            }
            Column(
                Modifier.fillMaxWidth().padding(bottom = 6.dp).clip(RoundedCornerShape(20.dp))
                    .background(MaterialTheme.colorScheme.onBackground).padding(18.dp)
            ) {
                val bg = MaterialTheme.colorScheme.background
                Text(l1, color = bg, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                Text(l2, color = bg.copy(alpha = 0.8f), fontSize = 15.sp)
                if (upcoming != null) {
                    Spacer(Modifier.height(6.dp))
                    Text("Luego: ${titleOf(upcoming)} · ${upcoming.name} (${hm(upcoming.start)})", color = bg.copy(alpha = 0.8f), fontSize = 14.sp)
                }
            }
        }
        items(blocks) { b ->
            val st = stateOf(b)
            val isNow = st == 0
            val bg = MaterialTheme.colorScheme.background
            val fg = if (isNow) bg else MaterialTheme.colorScheme.onBackground
            val sub = if (isNow) bg.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant
            val empty = !b.fixed && codeOf(b).isEmpty()
            Row(
                Modifier.fillMaxWidth().alpha(if (st == -1) 0.5f else 1f).clip(RoundedCornerShape(14.dp))
                    .background(if (isNow) MaterialTheme.colorScheme.onBackground else if (b.fixed) bg else MaterialTheme.colorScheme.surface)
                    .clickable { detail = b }
                    .padding(horizontal = 14.dp, vertical = if (b.fixed) 8.dp else 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("${hm(b.start)} - ${hm(b.end)}", Modifier.width(96.dp), fontSize = 13.sp, color = sub)
                if (b.fixed) {
                    Text(b.name, fontSize = 14.sp, color = sub)
                } else {
                    Column {
                        Text(titleOf(b), fontSize = 17.sp, fontWeight = if (empty) FontWeight.Normal else FontWeight.Bold, color = if (empty) sub else fg)
                        Text(b.name, fontSize = 13.sp, color = sub)
                    }
                }
            }
        }
    }

    detail?.let { b ->
        AlertDialog(
            onDismissRequest = { detail = null },
            containerColor = MaterialTheme.colorScheme.surface,
            title = { Text(titleOf(b)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Turno: ${b.name}")
                    Text("Hora: ${hm(b.start)} – ${hm(b.end)}")
                    Text("Estado: ${statusText(b)}")
                    val c = codeOf(b)
                    if (!b.fixed && c.isNotEmpty() && subjectName(c) == null) Text("Sigla «$c» no reconocida.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = { TextButton({ detail = null }) { Text("Cerrar") } }
        )
    }
}

// ---------- Inicio del profesor (por ahora un panel base) ----------
@Composable
fun TeacherHome(profile: Profile, dark: Boolean, onToggle: () -> Unit, onReset: () -> Unit) {
    val ctx = LocalContext.current
    val avatar = remember { null as ImageBitmap? }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { HomeTopBar(profile, "Profesor verificado ✓ · ${profile.extra}", avatar, dark, onToggle, onReset) }
    ) { pad ->
        Box(Modifier.padding(pad)) {
            ComingSoon(1, "Panel de profesor", "Aquí podrás crear planes de entrenamiento y asignar trabajos. Llega en una próxima versión.")
        }
    }
}
