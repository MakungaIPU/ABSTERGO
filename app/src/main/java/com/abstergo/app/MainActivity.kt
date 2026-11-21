@file:OptIn(androidx.compose.ui.text.ExperimentalTextApi::class, androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.abstergo.app

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.BitmapFactory
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.widget.VideoView
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.style.TextAlign
import kotlinx.coroutines.launch
import kotlin.math.absoluteValue
import androidx.compose.ui.text.input.PasswordVisualTransformation
import java.io.File
import java.security.MessageDigest
import java.text.Normalizer
import javax.security.auth.x500.X500Principal

class MainActivity : ComponentActivity() {
    private val pendingUri = mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)
        setContent { App(pendingUri) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    // "Abrir con Abstergo" (VIEW) o "Compartir" (SEND) desde otra app
    @Suppress("DEPRECATION")
    private fun handleIntent(i: Intent?) {
        if (i == null) return
        val u: Uri? = when (i.action) {
            Intent.ACTION_VIEW -> i.data
            Intent.ACTION_SEND -> i.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            else -> null
        }
        if (u != null) pendingUri.value = u
    }
}

// ---------- Tema blanco y negro ----------
private val LightColors = lightColorScheme(
    primary = Color.Black, onPrimary = Color.White,
    background = Color.White, onBackground = Color.Black,
    surface = Color(0xFFF2F2F2), onSurface = Color.Black,
    surfaceVariant = Color(0xFFF2F2F2), onSurfaceVariant = Color(0xFF444444),
    outline = Color(0xFF8A8A8A), error = Color.Black, onError = Color.White,
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF7F7F7), surfaceContainer = Color(0xFFF2F2F2),
    surfaceContainerHigh = Color(0xFFEAEAEA), surfaceContainerHighest = Color(0xFFE2E2E2)
)
private val DarkColors = darkColorScheme(
    primary = Color.White, onPrimary = Color.Black,
    background = Color.Black, onBackground = Color.White,
    surface = Color(0xFF121212), onSurface = Color.White,
    surfaceVariant = Color(0xFF1C1C1C), onSurfaceVariant = Color(0xFFBBBBBB),
    outline = Color(0xFF777777), error = Color.White, onError = Color.Black,
    surfaceContainerLowest = Color.Black, surfaceContainerLow = Color(0xFF0E0E0E), surfaceContainer = Color(0xFF121212),
    surfaceContainerHigh = Color(0xFF1A1A1A), surfaceContainerHighest = Color(0xFF222222)
)

// ---------- Tipografía (serif tipo Claude; Source Serif 4, licencia OFL) ----------
private fun serif(w: FontWeight) = Font(
    R.font.sourceserif4, w, variationSettings = FontVariation.Settings(FontVariation.weight(w.weight))
)
private val AppFont = FontFamily(
    serif(FontWeight.Normal), serif(FontWeight.Medium), serif(FontWeight.SemiBold),
    serif(FontWeight.Bold), serif(FontWeight.Black)
)
private fun appTypography(): Typography {
    val d = Typography()
    fun TextStyle.f() = copy(fontFamily = AppFont)
    return Typography(
        displayLarge = d.displayLarge.f(), displayMedium = d.displayMedium.f(), displaySmall = d.displaySmall.f(),
        headlineLarge = d.headlineLarge.f(), headlineMedium = d.headlineMedium.f(), headlineSmall = d.headlineSmall.f(),
        titleLarge = d.titleLarge.f(), titleMedium = d.titleMedium.f(), titleSmall = d.titleSmall.f(),
        bodyLarge = d.bodyLarge.f(), bodyMedium = d.bodyMedium.f(), bodySmall = d.bodySmall.f(),
        labelLarge = d.labelLarge.f(), labelMedium = d.labelMedium.f(), labelSmall = d.labelSmall.f()
    )
}
private val AppTypography = appTypography()

@Composable
fun AbstergoTheme(dark: Boolean, content: @Composable () -> Unit) =
    MaterialTheme(colorScheme = if (dark) DarkColors else LightColors, typography = AppTypography, content = content)

// ---------- Datos ----------
data class Profile(val role: String, val name: String, val extra: String)

private fun loadProfile(p: SharedPreferences): Profile? {
    val n = p.getString("name", null) ?: return null
    return Profile(p.getString("role", "student")!!, n, p.getString("extra", "")!!)
}

internal fun norm(s: String) = Normalizer.normalize(s, Normalizer.Form.NFD)
    .replace(Regex("\\p{M}+"), "").lowercase()
    .replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()

internal fun loadBitmap(ctx: Context, uri: Uri): ImageBitmap? = try {
    val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
    ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }?.asImageBitmap()
} catch (e: Exception) { null }

// ---------- App ----------
private const val TEACHER_HASH = "cd1411bdcad33007216d3094dc32542129c42eacd4c915f9d9fe5d0c8099a6a3"

private fun sha256(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

private fun teacherError(prefs: SharedPreferences, pass: String): String? {
    val now = System.currentTimeMillis()
    val lock = prefs.getLong("lock_until", 0L)
    if (now < lock) return "Demasiados intentos. Espera ${(lock - now) / 1000 + 1} s."
    if (sha256("abstergo-v1:$pass") == TEACHER_HASH) { prefs.edit().putInt("fails", 0).apply(); return null }
    val f = prefs.getInt("fails", 0) + 1
    val e = prefs.edit()
    if (f >= 5) e.putInt("fails", 0).putLong("lock_until", now + 60_000) else e.putInt("fails", f)
    e.apply()
    return "Contraseña incorrecta."
}

@Composable
fun App(pendingUri: MutableState<Uri?>) {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("abstergo", Context.MODE_PRIVATE) }
    val systemDark = isSystemInDarkTheme()
    var dark by remember { mutableStateOf(prefs.getBoolean("dark", systemDark)) }
    var screen by remember { mutableStateOf("intro") }
    var profile by remember { mutableStateOf(loadProfile(prefs)) }
    val toggle = { dark = !dark; prefs.edit().putBoolean("dark", dark).apply() }
    val reset = {
        prefs.edit().remove("name").remove("role").remove("extra").apply()
        File(ctx.filesDir, "profile.jpg").delete()
        profile = null; screen = "profile"
    }

    AbstergoTheme(dark) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Crossfade(targetState = screen, animationSpec = tween(500), label = "screen") { current ->
                when (current) {
                    "intro" -> IntroScreen {
                        screen = when {
                            !prefs.getBoolean("onboarding_done", false) -> "onboarding"
                            profile != null -> "home"
                            else -> "profile"
                        }
                    }
                    "onboarding" -> OnboardingScreen {
                        prefs.edit().putBoolean("onboarding_done", true).apply()
                        screen = if (profile != null) "home" else "profile"
                    }
                    "profile" -> ProfileScreen(dark, toggle) { p -> profile = p; screen = "home" }
                    else -> profile?.let { pr ->
                        if (pr.role == "student") {
                            StudentHome(pr, dark, toggle, pendingUri.value, { pendingUri.value = null }, reset)
                        } else {
                            TeacherHome(pr, dark, toggle, reset)
                        }
                    }
                }
            }
        }
    }
}

// ---------- Video de intro (tocar para saltar) ----------
@Composable
fun IntroScreen(onDone: () -> Unit) {
    var finished by remember { mutableStateOf(false) }
    val finish = { if (!finished) { finished = true; onDone() } }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { c ->
                VideoView(c).apply {
                    // No pedir el foco de audio: así no se pausa la música del teléfono
                    setAudioFocusRequest(AudioManager.AUDIOFOCUS_NONE)
                    setVideoURI(Uri.parse("android.resource://${c.packageName}/${R.raw.intro}"))
                    setOnPreparedListener { it.isLooping = false; it.setVolume(0f, 0f); start() }
                    setOnCompletionListener { finish() }
                    setOnErrorListener { _, _, _ -> finish(); true }
                }
            },
            onRelease = { it.stopPlayback() }
        )
        Box(
            Modifier.fillMaxSize().clickable(
                interactionSource = remember { MutableInteractionSource() }, indication = null
            ) { finish() }
        )
    }
}

// ---------- Componentes ----------
@Composable
fun TopBar(dark: Boolean, onToggle: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("ABSTERGO", fontSize = 18.sp, fontWeight = FontWeight.Black, letterSpacing = 3.sp)
        Spacer(Modifier.weight(1f))
        OutlinedButton(onClick = onToggle, shape = CircleShape, contentPadding = PaddingValues(0.dp), modifier = Modifier.size(44.dp)) {
            Text(if (dark) "☀" else "☾", fontSize = 20.sp)
        }
    }
}

@Composable
fun RoleButton(text: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    if (selected) Button(onClick, modifier, shape = RoundedCornerShape(14.dp)) { Text(text) }
    else OutlinedButton(onClick, modifier, shape = RoundedCornerShape(14.dp)) { Text(text) }
}

@Composable
fun Avatar(bitmap: ImageBitmap?, size: Int, modifier: Modifier = Modifier) {
    Box(
        modifier.size(size.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceVariant)
            .border(2.dp, MaterialTheme.colorScheme.onBackground, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) Image(bitmap, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Icon(Icons.Default.Person, null, Modifier.size((size / 2).dp))
    }
}

// ---------- Crear perfil ----------
@Composable
fun ProfileScreen(dark: Boolean, onToggle: () -> Unit, onSaved: (Profile) -> Unit) {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("abstergo", Context.MODE_PRIVATE) }
    var role by remember { mutableStateOf("student") }
    var photo by remember { mutableStateOf<Uri?>(null) }
    var name by remember { mutableStateOf("") }
    var extra by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { if (it != null) photo = it }
    val bitmap = remember(photo) { photo?.let { loadBitmap(ctx, it) } }
    val student = role == "student"

    Column(
        Modifier.fillMaxSize().systemBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        TopBar(dark, onToggle)
        Text("Crear perfil", fontSize = 30.sp, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            RoleButton("Estudiante", student, Modifier.weight(1f)) { role = "student"; error = null }
            RoleButton("Profesor", !student, Modifier.weight(1f)) { role = "teacher"; error = null }
        }
        if (student) {
            Avatar(bitmap, 128, Modifier.align(Alignment.CenterHorizontally).clickable {
                photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            })
            Text("Toca para elegir foto de perfil (opcional)", Modifier.align(Alignment.CenterHorizontally),
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
        }
        OutlinedTextField(name, { name = it; error = null }, Modifier.fillMaxWidth(),
            label = { Text("Nombre completo") }, singleLine = true, shape = RoundedCornerShape(14.dp))
        OutlinedTextField(extra, { extra = it; error = null }, Modifier.fillMaxWidth(),
            label = { Text(if (student) "Grupo" else "Asignatura que impartes") }, singleLine = true,
            supportingText = { if (student) Text("Como aparece en el horario, por ejemplo 10mo1") },
            shape = RoundedCornerShape(14.dp))
        if (!student) {
            OutlinedTextField(password, { password = it; error = null }, Modifier.fillMaxWidth(),
                label = { Text("Contraseña de profesor") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), shape = RoundedCornerShape(14.dp))
        }
        error?.let {
            Box(Modifier.fillMaxWidth().border(2.dp, MaterialTheme.colorScheme.onBackground, RoundedCornerShape(14.dp)).padding(14.dp)) {
                Text("⚠  $it", fontWeight = FontWeight.SemiBold)
            }
        }
        Button(
            onClick = {
                error = when {
                    name.isBlank() -> "Escribe tu nombre completo."
                    extra.isBlank() -> if (student) "Escribe tu grupo." else "Escribe la asignatura que impartes."
                    student && !Regex("^\\d{2}[a-z]{2}\\d+$").matches(norm(extra).replace(" ", "")) ->
                        "Escribe tu grupo como aparece en el horario, por ejemplo 10mo1."
                    !student -> teacherError(prefs, password)
                    else -> null
                }
                if (error == null) {
                    if (student) {
                        val f = File(ctx.filesDir, "profile.jpg")
                        if (photo == null) f.delete()
                        photo?.let { u ->
                            try {
                                ctx.contentResolver.openInputStream(u)?.use { i -> f.outputStream().use { o -> i.copyTo(o) } }
                            } catch (_: Exception) {}
                        }
                    }
                    prefs.edit().putString("role", role).putString("name", name.trim()).putString("extra", extra.trim()).apply()
                    onSaved(Profile(role, name.trim(), extra.trim()))
                }
            },
            modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(14.dp)
        ) { Text("Continuar", fontSize = 16.sp, fontWeight = FontWeight.Bold) }
    }
}

// ---------- Introducción (solo la primera vez) ----------
data class OnbPage(val title: String, val text: String, val symbol: Int) // symbol -1 = logo

private val onboardingPages = listOf(
    OnbPage("Abstergo", "La mano derecha de los estudiantes y profesores del Preuniversitario", -1),
    OnbPage("Sigue tu horario al pie de la letra", "Consulta tus clases y el calendario de eventos, siempre al día.", 0),
    OnbPage("Trabajos y tareas, sin olvidos", "Organiza tus trabajos prácticos y tareas evaluativas y entrégalos a tiempo.", 1),
    OnbPage("Prepárate para cada prueba", "Ten a la vista tus pruebas parciales y finales y sigue tu progreso.", 2),
    OnbPage("Entrena tu mente", "Practica con los entrenamientos que preparan tus profesores y llega con confianza a cada evaluación.", 3)
)

private fun line(x1: Float, y1: Float, x2: Float, y2: Float) = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
private fun rrect(l: Float, t: Float, r: Float, b: Float, rad: Float) =
    Path().apply { addRoundRect(RoundRect(Rect(l, t, r, b), CornerRadius(rad, rad))) }
private fun polyline(vararg p: Float) = Path().apply {
    moveTo(p[0], p[1]); var i = 2; while (i < p.size) { lineTo(p[i], p[i + 1]); i += 2 }
}

/** Símbolos minimalistas en una cuadrícula de 100x100, cada trazo es un camino independiente. */
private fun symbolPaths(kind: Int): List<Path> = when (kind) {
    0 -> listOf( // reloj
        Path().apply { addOval(Rect(Offset(50f, 50f), 38f)) },
        line(50f, 50f, 50f, 28f), line(50f, 50f, 66f, 58f),
        line(50f, 16f, 50f, 22f), line(84f, 50f, 78f, 50f), line(50f, 84f, 50f, 78f), line(16f, 50f, 22f, 50f)
    )
    1 -> listOf( // lista de tareas
        rrect(22f, 10f, 78f, 90f, 8f),
        polyline(32f, 34f, 36f, 38f, 44f, 30f), line(52f, 34f, 68f, 34f),
        polyline(32f, 54f, 36f, 58f, 44f, 50f), line(52f, 54f, 68f, 54f),
        polyline(32f, 74f, 36f, 78f, 44f, 70f), line(52f, 74f, 68f, 74f)
    )
    2 -> listOf( // progreso
        line(10f, 88f, 90f, 88f), rrect(16f, 58f, 32f, 88f, 3f), rrect(42f, 40f, 58f, 88f, 3f), rrect(68f, 20f, 84f, 88f, 3f)
    )
    3 -> listOf( // entrenamiento
        line(32f, 50f, 68f, 50f), rrect(20f, 34f, 32f, 66f, 4f), rrect(68f, 34f, 80f, 66f, 4f),
        rrect(8f, 42f, 20f, 58f, 3f), rrect(80f, 42f, 92f, 58f, 3f)
    )
    else -> listOf( // calendario
        rrect(14f, 20f, 86f, 88f, 8f), line(14f, 42f, 86f, 42f), line(34f, 10f, 34f, 28f), line(66f, 10f, 66f, 28f),
        line(30f, 58f, 38f, 58f), line(46f, 58f, 54f, 58f), line(62f, 58f, 70f, 58f), line(30f, 72f, 38f, 72f), line(46f, 72f, 54f, 72f)
    )
}

@Composable
fun LineSymbol(kind: Int, progress: Float, color: Color, modifier: Modifier = Modifier, strokeW: Float = 3.2f) {
    val paths = remember(kind) { symbolPaths(kind) }
    Canvas(modifier) {
        val sc = size.minDimension / 100f
        withTransform({ scale(sc, sc, Offset.Zero) }) {
            val m = PathMeasure()
            paths.forEach { p ->
                m.setPath(p, false)
                val d = Path()
                m.getSegment(0f, m.length * progress, d, true)
                drawPath(d, color, style = Stroke(width = strokeW, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
    }
}

@Composable
fun OnboardingPage(page: OnbPage, active: Boolean, modifier: Modifier = Modifier) {
    val enter by animateFloatAsState(if (active) 1f else 0f, tween(900), label = "enter")
    Column(
        modifier.fillMaxSize().padding(horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(Modifier.height(190.dp), contentAlignment = Alignment.Center) {
            if (page.symbol < 0) {
                Image(
                    painterResource(R.drawable.logo), "Logo Abstergo",
                    Modifier.size(180.dp)
                        .graphicsLayer { scaleX = 0.8f + 0.2f * enter; scaleY = 0.8f + 0.2f * enter; alpha = enter }
                        .clip(RoundedCornerShape(40.dp))
                )
            } else {
                LineSymbol(page.symbol, enter, MaterialTheme.colorScheme.onBackground, Modifier.size(160.dp))
            }
        }
        Spacer(Modifier.height(32.dp))
        Text(
            page.title, fontSize = if (page.symbol < 0) 40.sp else 28.sp, fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center, lineHeight = if (page.symbol < 0) 46.sp else 34.sp,
            modifier = Modifier.graphicsLayer { alpha = enter; translationY = (1f - enter) * 40f }
        )
        Spacer(Modifier.height(12.dp))
        Text(
            page.text, fontSize = 17.sp, lineHeight = 26.sp, textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.graphicsLayer { alpha = enter; translationY = (1f - enter) * 40f }
        )
    }
}

@Composable
fun PageDot(active: Boolean) {
    val w by animateDpAsState(if (active) 24.dp else 8.dp, tween(300), label = "dotW")
    val c by animateColorAsState(
        if (active) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.outline, label = "dotC"
    )
    Box(Modifier.width(w).height(8.dp).clip(CircleShape).background(c))
}

@Composable
fun OnboardingScreen(onDone: () -> Unit) {
    val pager = rememberPagerState { onboardingPages.size }
    val scope = rememberCoroutineScope()
    val last = pager.currentPage == onboardingPages.lastIndex
    Column(Modifier.fillMaxSize().systemBarsPadding().padding(24.dp)) {
        HorizontalPager(state = pager, modifier = Modifier.weight(1f).fillMaxWidth()) { i ->
            val off = ((pager.currentPage - i) + pager.currentPageOffsetFraction).absoluteValue.coerceIn(0f, 1f)
            OnboardingPage(
                onboardingPages[i], pager.currentPage == i,
                Modifier.graphicsLayer { alpha = 1f - off; val sc = 1f - 0.12f * off; scaleX = sc; scaleY = sc }
            )
        }
        Row(
            Modifier.align(Alignment.CenterHorizontally).padding(vertical = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) { repeat(onboardingPages.size) { PageDot(it == pager.currentPage) } }
        Button(
            onClick = { if (last) onDone() else scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } },
            modifier = Modifier.fillMaxWidth().height(54.dp), shape = RoundedCornerShape(14.dp)
        ) {
            Crossfade(last, label = "btn") { Text(if (it) "Hecho" else "Continuar", fontSize = 16.sp, fontWeight = FontWeight.Bold) }
        }
    }
}
