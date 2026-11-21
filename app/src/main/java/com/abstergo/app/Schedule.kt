package com.abstergo.app

import android.content.Context
import android.net.Uri
import android.util.Xml
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.File
import java.time.LocalDate
import java.util.zip.ZipInputStream

// ---------- Asignaturas ----------
val SUBJECTS = mapOf(
    "q" to "Química", "f" to "Física", "m" to "Matemática", "h" to "Historia",
    "cp" to "Cultura Política", "i" to "Inglés", "g" to "Geografía", "ll" to "Literatura y Lengua",
    "b" to "Biología", "inf" to "Informática", "ef" to "Educación Física", "dr" to "Debate y Reflexión",
    "ca" to "Cultura Artística", "ac" to "Actividad complementaria", "fl" to "Formación Laboral"
)

fun subjectName(code: String): String? = SUBJECTS[norm(code).replace(" ", "")]

// ---------- Turnos (minutos desde las 0:00) ----------
data class Block(val fixed: Boolean, val name: String, val start: Int, val end: Int, val key: String? = null, val pm: Boolean = false)

val BLOCKS = listOf(
    Block(true, "Entrada", 460, 480),
    Block(true, "Formación", 480, 495),
    Block(false, "1er turno", 495, 540, "1"),
    Block(false, "2do turno", 540, 585, "2"),
    Block(true, "Receso", 585, 600),
    Block(false, "3er turno", 605, 650, "3"),
    Block(false, "4to turno", 650, 695, "4"),
    Block(false, "5to turno", 695, 740, "5"),
    Block(false, "6to turno", 740, 785, "6"),
    Block(true, "Almuerzo", 785, 840, null, true),
    Block(false, "1er turno de la tarde", 840, 885, "T1", true),
    Block(false, "2do turno de la tarde", 885, 930, "T2", true),
    Block(false, "3er turno de la tarde", 930, 975, "T3", true)
)

fun hm(min: Int): String {
    val h = min / 60
    val hh = if (h > 12) h - 12 else h
    return "$hh:${"%02d".format(min % 60)}"
}

// ---------- Fechas ----------
val DAY_NAMES = listOf("lunes", "martes", "miércoles", "jueves", "viernes", "sábado", "domingo")
val MONTH_NAMES = listOf("enero", "febrero", "marzo", "abril", "mayo", "junio", "julio", "agosto", "septiembre", "octubre", "noviembre", "diciembre")
private val MONTH_KEYS = listOf("ene", "feb", "mar", "abr", "may", "jun", "jul", "ago", "sep", "oct", "nov", "dic")

fun dateText(d: LocalDate): String =
    DAY_NAMES[d.dayOfWeek.value - 1].replaceFirstChar { it.uppercase() } + " ${d.dayOfMonth} de ${MONTH_NAMES[d.monthValue - 1]} de ${d.year}"

private fun mkDate(y: String, m: Int, d: String): LocalDate? = try {
    val yy = y.toInt().let { if (it < 100) 2000 + it else it }
    LocalDate.of(yy, m, d.toInt())
} catch (e: Exception) { null }

fun findDate(texts: List<String>): LocalDate? {
    val named = Regex("(\\d{1,2})\\s*[/\\-. ]\\s*([A-Za-zÁÉÍÓÚáéíóú]{3,})\\s*[/\\-. ]\\s*(\\d{2,4})")
    val numeric = Regex("(\\d{1,2})\\s*[/\\-.]\\s*(\\d{1,2})\\s*[/\\-.]\\s*(\\d{2,4})")
    for (t in texts) {
        named.find(t)?.let { m ->
            val mi = MONTH_KEYS.indexOf(norm(m.groupValues[2]).take(3))
            if (mi >= 0) mkDate(m.groupValues[3], mi + 1, m.groupValues[1])?.let { return it }
        }
        numeric.find(t)?.let { m ->
            mkDate(m.groupValues[3], m.groupValues[2].toInt(), m.groupValues[1])?.let { return it }
        }
    }
    return null
}

// ---------- Modelo y almacenamiento ----------
data class Schedule(val date: LocalDate, val group: String, val slots: Map<String, String>, val imported: Long)

private fun schedFile(ctx: Context) = File(ctx.filesDir, "schedules.json")

fun loadSchedules(ctx: Context): List<Schedule> = try {
    val arr = JSONArray(schedFile(ctx).readText())
    (0 until arr.length()).map { i ->
        val o = arr.getJSONObject(i)
        val sl = o.getJSONObject("slots")
        Schedule(
            LocalDate.ofEpochDay(o.getLong("date")), o.getString("group"),
            sl.keys().asSequence().associateWith { k -> sl.getString(k) }, o.optLong("imported")
        )
    }
} catch (e: Exception) { emptyList() }

fun saveSchedules(ctx: Context, list: List<Schedule>) {
    val arr = JSONArray()
    list.forEach { s ->
        arr.put(
            JSONObject().put("date", s.date.toEpochDay()).put("group", s.group)
                .put("imported", s.imported).put("slots", JSONObject(s.slots))
        )
    }
    schedFile(ctx).writeText(arr.toString())
}

// ---------- Lector de Excel (.xlsx) ----------
class Grid(val rows: Map<Int, Map<Int, String>>)

object Xlsx {
    fun read(ctx: Context, uri: Uri): List<Grid>? {
        val files = HashMap<String, ByteArray>()
        try {
            ctx.contentResolver.openInputStream(uri)?.use { ins ->
                ZipInputStream(ins).use { z ->
                    var e = z.nextEntry
                    while (e != null) {
                        val n = e.name
                        if (n == "xl/sharedStrings.xml" || (n.startsWith("xl/worksheets/sheet") && n.endsWith(".xml"))) {
                            files[n] = z.readBytes()
                        }
                        e = z.nextEntry
                    }
                }
            }
        } catch (ex: Exception) { return null }
        val sheets = files.keys.filter { it.startsWith("xl/worksheets/") }
            .sortedBy { k -> k.filter { it.isDigit() }.toIntOrNull() ?: 0 }
        if (sheets.isEmpty()) return null
        val sst = files["xl/sharedStrings.xml"]?.let { parseShared(it) } ?: emptyList()
        return try { sheets.map { parseSheet(files[it]!!, sst) } } catch (ex: Exception) { null }
    }

    private fun parseShared(bytes: ByteArray): List<String> {
        val p = Xml.newPullParser(); p.setInput(ByteArrayInputStream(bytes), null)
        val out = ArrayList<String>(); val sb = StringBuilder()
        var inSi = false; var inT = false
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG) {
                if (p.name == "si") { inSi = true; sb.setLength(0) } else if (p.name == "t") inT = inSi
            } else if (ev == XmlPullParser.TEXT) {
                if (inT) sb.append(p.text)
            } else if (ev == XmlPullParser.END_TAG) {
                if (p.name == "t") inT = false else if (p.name == "si") { inSi = false; out.add(sb.toString()) }
            }
            ev = p.next()
        }
        return out
    }

    private fun parseRef(ref: String): Pair<Int, Int> {
        var col = 0; var i = 0
        while (i < ref.length && ref[i].isLetter()) { col = col * 26 + (ref[i].uppercaseChar() - 'A' + 1); i++ }
        return Pair(ref.substring(i).toIntOrNull() ?: 0, col)
    }

    private fun parseSheet(bytes: ByteArray, sst: List<String>): Grid {
        val p = Xml.newPullParser(); p.setInput(ByteArrayInputStream(bytes), null)
        val rows = HashMap<Int, HashMap<Int, String>>()
        var ref = ""; var type: String? = null
        val v = StringBuilder(); val inl = StringBuilder()
        var inV = false; var inT = false
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG) {
                when (p.name) {
                    "c" -> { ref = p.getAttributeValue(null, "r") ?: ""; type = p.getAttributeValue(null, "t"); v.setLength(0); inl.setLength(0) }
                    "v" -> inV = true
                    "t" -> inT = true
                }
            } else if (ev == XmlPullParser.TEXT) {
                if (inV) v.append(p.text) else if (inT) inl.append(p.text)
            } else if (ev == XmlPullParser.END_TAG) {
                when (p.name) {
                    "v" -> inV = false
                    "t" -> inT = false
                    "c" -> {
                        val raw = when (type) {
                            "s" -> v.toString().trim().toIntOrNull()?.let { sst.getOrNull(it) } ?: ""
                            "inlineStr" -> inl.toString()
                            else -> v.toString()
                        }.trim()
                        if (raw.isNotEmpty() && ref.isNotEmpty()) {
                            val (r, c) = parseRef(ref)
                            rows.getOrPut(r) { HashMap() }[c] = raw
                        }
                    }
                }
            }
            ev = p.next()
        }
        return Grid(rows)
    }
}

// ---------- Detección de sección y lectura del horario ----------
enum class Section(val label: String) {
    HORARIO("Horarios"), ENTRENAR("Entrenar"), EVALUACIONES("Evaluaciones"), CALENDARIO("Calendario")
}

fun classify(text: String): Section? {
    val t = norm(text)
    return when {
        "horario" in t -> Section.HORARIO
        "entrena" in t -> Section.ENTRENAR
        listOf("tarea", "trabajo", "prueba", "parcial", "final", "evaluativ").any { it in t } -> Section.EVALUACIONES
        listOf("evento", "calendario").any { it in t } -> Section.CALENDARIO
        else -> null
    }
}

sealed class Parsed {
    data class Ok(val s: Schedule) : Parsed()
    data class Err(val msg: String) : Parsed()
}

private val GROUP_RE = Regex("^\\d{2}[a-z]{2}\\d+$")
private val TURN_RE = Regex("^(\\d)\\s*[a-zº°]{0,3}\\s*(t)?$")

fun parseSchedule(grids: List<Grid>, group: String): Parsed {
    val g = norm(group).replace(" ", "")
    val texts = grids.flatMap { gr -> gr.rows.toSortedMap().values.flatMap { it.values } }
        .sortedByDescending { "horario" in norm(it) }
    val date = findDate(texts)
        ?: return Parsed.Err("No encontré la fecha en el título del Excel (por ejemplo «VIERNES 20/NOV/26»).")
    for (gr in grids) {
        val sorted = gr.rows.toSortedMap()
        var hr = -1; var hc = -1
        outer@ for ((r, cols) in sorted) {
            for ((c, t) in cols) {
                if (norm(t).replace(" ", "") == g) { hr = r; hc = c; break@outer }
            }
        }
        if (hr < 0) continue
        val slots = HashMap<String, String>()
        for ((r, cols) in sorted) {
            if (r <= hr) continue
            if (cols.values.count { GROUP_RE.matches(norm(it).replace(" ", "")) } >= 2) break
            val lab = cols.filterKeys { it < hc }.toSortedMap().entries.reversed()
                .firstNotNullOfOrNull { e -> TURN_RE.matchEntire(e.value.trim().lowercase()) }
            if (lab != null) {
                val key = if (lab.groupValues[2].isNotEmpty()) "T" + lab.groupValues[1] else lab.groupValues[1]
                val v = (cols[hc] ?: "").trim()
                slots[key] = if (v == "-" || v == "–" || v == "—") "" else v
            }
        }
        if (slots.isEmpty()) return Parsed.Err("Encontré tu grupo, pero no los turnos debajo.")
        return Parsed.Ok(Schedule(date, group.trim(), slots, System.currentTimeMillis()))
    }
    return Parsed.Err("No encontré tu grupo ($group) en este horario.")
}
