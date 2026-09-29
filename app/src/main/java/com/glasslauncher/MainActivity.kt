package com.glasslauncher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent { LauncherScreen() }
    }
}

data class AppInfo(val label: String, val pkg: String, val cn: ComponentName, val icon: ImageBitmap)

val LocalWall = compositionLocalOf<ImageBitmap?> { null }
val LocalRoot = compositionLocalOf { IntSize(1080, 2400) }

/* ---------- data helpers ---------- */

fun loadApps(c: Context): List<AppInfo> {
    val pm = c.packageManager
    val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return pm.queryIntentActivities(i, 0)
        .filter { it.activityInfo.packageName != c.packageName }
        .map { r ->
            AppInfo(
                r.loadLabel(pm).toString(),
                r.activityInfo.packageName,
                ComponentName(r.activityInfo.packageName, r.activityInfo.name),
                r.loadIcon(pm).toBitmap(144, 144).asImageBitmap()
            )
        }
        .sortedBy { it.label.lowercase() }
}

fun openApp(c: Context, a: AppInfo) {
    c.startActivity(
        Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(a.cn).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    )
}

fun prefs(c: Context) = c.getSharedPreferences("glass", Context.MODE_PRIVATE)
fun loadDock(c: Context): List<String> = prefs(c).getString("dock", "")!!.split(",").filter { it.isNotBlank() }
fun saveDock(c: Context, l: List<String>) = prefs(c).edit().putString("dock", l.joinToString(",")).apply()

fun decodeWall(f: File): Bitmap? {
    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(f.path, o)
    var s = 1
    while (maxOf(o.outWidth, o.outHeight) / s > 2200) s *= 2
    return BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = s })
}

fun loadWall(c: Context, tex: Boolean): ImageBitmap? {
    val f = File(c.filesDir, "wall.jpg")
    if (!f.exists()) return null
    val b = decodeWall(f) ?: return null
    return (if (tex) glassify(b) else b).asImageBitmap()
}

fun saveWall(c: Context, u: Uri, tex: Boolean): ImageBitmap? {
    val f = File(c.filesDir, "wall.jpg")
    c.contentResolver.openInputStream(u)?.use { i -> f.outputStream().use { o -> i.copyTo(o) } } ?: return null
    return loadWall(c, tex)
}

/* ---------- glass ---------- */

private fun DrawScope.drawWall(w: ImageBitmap, root: IntSize, at: IntOffset) {
    val s = maxOf(root.width.toFloat() / w.width, root.height.toFloat() / w.height)
    val sw = minOf(w.width, (root.width / s).roundToInt())
    val sh = minOf(w.height, (root.height / s).roundToInt())
    drawImage(
        w,
        srcOffset = IntOffset((w.width - sw) / 2, (w.height - sh) / 2),
        srcSize = IntSize(sw, sh),
        dstOffset = at,
        dstSize = root
    )
}

/** Frosted glass: a blurred copy of the wallpaper, aligned to the screen, plus a tint and a light edge. */
@Composable
fun Glass(modifier: Modifier = Modifier, radius: Dp = 28.dp, content: @Composable BoxScope.() -> Unit) {
    val wall = LocalWall.current
    val root = LocalRoot.current
    val shape = RoundedCornerShape(radius)
    var pos by remember { mutableStateOf(IntOffset.Zero) }
    Box(
        modifier.clip(shape).onGloballyPositioned {
            val p = it.positionInRoot()
            pos = IntOffset(p.x.roundToInt(), p.y.roundToInt())
        }
    ) {
        Canvas(Modifier.matchParentSize().graphicsLayer { renderEffect = BlurEffect(45f, 45f, TileMode.Clamp) }) {
            if (wall != null) drawWall(wall, root, IntOffset(-pos.x, -pos.y))
            else drawRect(Color(0xFF2A2F45))
        }
        Box(
            Modifier.matchParentSize().background(
                Brush.linearGradient(listOf(Color.White.copy(alpha = 0.24f), Color.White.copy(alpha = 0.07f)))
            )
        )
        Box(
            Modifier.matchParentSize().border(
                1.2.dp,
                Brush.linearGradient(
                    listOf(Color.White.copy(alpha = 0.75f), Color.White.copy(alpha = 0.06f), Color.White.copy(alpha = 0.4f))
                ),
                shape
            )
        )
        content()
    }
}

/* ---------- rippled-glass wallpaper texture ---------- */

private fun hash(x: Int, y: Int, s: Int): Float {
    var h = x * 374761393 + y * 668265263 + s * 1274126177
    h = (h xor (h ushr 13)) * 1274126177
    h = h xor (h ushr 16)
    return (h and 0xFFFF) / 65535f
}

private fun noise(x: Float, y: Float, s: Int): Float {
    val xi = kotlin.math.floor(x).toInt()
    val yi = kotlin.math.floor(y).toInt()
    val fx = x - xi
    val fy = y - yi
    val u = fx * fx * (3 - 2 * fx)
    val v = fy * fy * (3 - 2 * fy)
    val a = hash(xi, yi, s)
    val b = hash(xi + 1, yi, s)
    val c = hash(xi, yi + 1, s)
    val d = hash(xi + 1, yi + 1, s)
    return a + (b - a) * u + (c - a) * v + (a - b - c + d) * u * v
}

/** Oil-glass look: pixels are pushed around by smooth noise and lightly shaded. */
fun glassify(src: Bitmap): Bitmap {
    val k = minOf(1f, 1500f / maxOf(src.width, src.height))
    val b = if (k < 1f) Bitmap.createScaledBitmap(src, (src.width * k).toInt(), (src.height * k).toInt(), true) else src
    val w = b.width
    val h = b.height
    val px = IntArray(w * h)
    b.getPixels(px, 0, w, 0, 0, w, h)
    val out = IntArray(w * h)
    for (y in 0 until h) {
        for (x in 0 until w) {
            val dx = (noise(x / 26f, y / 26f, 1) - 0.5f) * 22f + (noise(x / 9f, y / 9f, 2) - 0.5f) * 7f
            val dy = (noise(x / 26f, y / 26f, 3) - 0.5f) * 22f + (noise(x / 9f, y / 9f, 4) - 0.5f) * 7f
            val sx = (x + dx).toInt().coerceIn(0, w - 1)
            val sy = (y + dy).toInt().coerceIn(0, h - 1)
            val p = px[sy * w + sx]
            val shade = 1f + (noise(x / 11f, y / 11f, 5) - 0.5f) * 0.3f
            val r = (((p shr 16) and 0xFF) * shade).toInt().coerceIn(0, 255)
            val g = (((p shr 8) and 0xFF) * shade).toInt().coerceIn(0, 255)
            val bl = ((p and 0xFF) * shade).toInt().coerceIn(0, 255)
            out[y * w + x] = (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
        }
    }
    return Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
}

/* ---------- glass clock digits ---------- */

/** Clock digits made of glass: the wallpaper shows through, slightly magnified, with bright edges. */
@Composable
fun GlassDigits(text: String, sizePx: Float) {
    val wall = LocalWall.current
    val root = LocalRoot.current
    val density = LocalDensity.current
    val paint = remember(sizePx) {
        android.graphics.Paint().apply {
            isAntiAlias = true
            textSize = sizePx
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
    }
    val fm = paint.fontMetrics
    val wPx = paint.measureText(text)
    val hPx = fm.descent - fm.ascent
    val path = remember(text, sizePx) {
        val p = android.graphics.Path()
        paint.getTextPath(text, 0, text.length, 0f, -fm.ascent, p)
        p.asComposePath()
    }
    var pos by remember { mutableStateOf(IntOffset.Zero) }
    Box(
        Modifier.size(with(density) { wPx.toDp() }, with(density) { hPx.toDp() })
            .onGloballyPositioned {
                val q = it.positionInRoot()
                pos = IntOffset(q.x.roundToInt(), q.y.roundToInt())
            }
    ) {
        Canvas(Modifier.matchParentSize().graphicsLayer { renderEffect = BlurEffect(6f, 6f, TileMode.Decal) }) {
            clipPath(path) {
                if (wall != null) {
                    withTransform({ scale(1.12f, 1.12f, Offset(size.width / 2f, size.height / 2f)) }) {
                        drawWall(wall, root, IntOffset(-pos.x, -pos.y))
                    }
                } else {
                    drawRect(Color(0xFF3A4060))
                }
            }
        }
        Canvas(Modifier.matchParentSize()) {
            translate(0f, 6f) { drawPath(path, Color.Black.copy(alpha = 0.22f), style = Stroke(width = 8f)) }
            drawPath(path, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.34f), Color.White.copy(alpha = 0.06f))))
            drawPath(
                path,
                Brush.linearGradient(listOf(Color.White.copy(alpha = 0.95f), Color.White.copy(alpha = 0.15f), Color.White.copy(alpha = 0.6f))),
                style = Stroke(width = 3.5f)
            )
        }
    }
}

/* ---------- screens ---------- */

@Composable
fun LauncherScreen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var root by remember { mutableStateOf(IntSize(1080, 2400)) }
    var wall by remember { mutableStateOf<ImageBitmap?>(null) }
    var apps by remember { mutableStateOf<List<AppInfo>>(emptyList()) }
    var tick by remember { mutableIntStateOf(0) }
    var dock by remember { mutableStateOf(loadDock(ctx)) }
    var searching by remember { mutableStateOf(false) }
    var now by remember { mutableStateOf(LocalDateTime.now()) }

    var textured by remember { mutableStateOf(prefs(ctx).getBoolean("tex", false)) }
    LaunchedEffect(textured) { wall = withContext(Dispatchers.Default) { loadWall(ctx, textured) } }
    LaunchedEffect(Unit) {
        while (true) {
            now = LocalDateTime.now()
            delay((60 - now.second) * 1000L)
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { tick++ }
    LaunchedEffect(tick) { apps = withContext(Dispatchers.Default) { loadApps(ctx) } }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch { wall = withContext(Dispatchers.Default) { saveWall(ctx, uri, textured) } }
    }
    val pager = rememberPagerState { maxOf(1, (apps.size + 23) / 24) }
    val bg = wall

    CompositionLocalProvider(LocalWall provides wall, LocalRoot provides root) {
        Box(Modifier.fillMaxSize().background(Color.Black).onSizeChanged { root = it }) {
            Canvas(Modifier.fillMaxSize()) {
                if (bg != null) drawWall(bg, root, IntOffset.Zero)
                else drawRect(Brush.verticalGradient(listOf(Color(0xFF1B2140), Color(0xFF0B0D16))))
            }

            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 16.dp)) {
                Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Spacer(Modifier.weight(1f))
                    Glass(
                        Modifier.clickable {
                            textured = !textured
                            prefs(ctx).edit().putBoolean("tex", textured).apply()
                        },
                        radius = 20.dp
                    ) {
                        Text(
                            if (textured) "Texture: on" else "Texture: off", color = Color.White, fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                        )
                    }
                    Glass(
                        Modifier.clickable {
                            pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        },
                        radius = 20.dp
                    ) {
                        Text(
                            "Wallpaper", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                        )
                    }
                }

                Column(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        now.format(DateTimeFormatter.ofPattern("EEE d MMM")),
                        color = Color.White.copy(alpha = 0.9f), fontSize = 19.sp, fontWeight = FontWeight.SemiBold
                    )
                    GlassDigits(now.format(DateTimeFormatter.ofPattern("HH:mm")), root.width * 0.27f)
                }

                HorizontalPager(pager, Modifier.weight(1f).padding(top = 8.dp)) { p ->
                    val slice = apps.drop(p * 24).take(24)
                    Column(Modifier.fillMaxSize()) {
                        for (r in 0 until 6) {
                            Row(Modifier.fillMaxWidth().weight(1f)) {
                                val row = slice.drop(r * 4).take(4)
                                for (a in row) {
                                    AppCell(a, Modifier.weight(1f).fillMaxHeight(), a.pkg in dock) {
                                        dock = if (a.pkg in dock) dock - a.pkg else (dock + a.pkg).takeLast(4)
                                        saveDock(ctx, dock)
                                    }
                                }
                                repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                    }
                }

                Row(Modifier.fillMaxWidth().padding(6.dp), horizontalArrangement = Arrangement.Center) {
                    repeat(pager.pageCount) { i ->
                        Box(
                            Modifier.padding(3.dp).size(7.dp).clip(CircleShape)
                                .background(Color.White.copy(alpha = if (i == pager.currentPage) 0.95f else 0.35f))
                        )
                    }
                }

                Glass(
                    Modifier.align(Alignment.CenterHorizontally).padding(vertical = 8.dp).clickable { searching = true },
                    radius = 20.dp
                ) {
                    Text(
                        "Search", color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp,
                        modifier = Modifier.padding(horizontal = 64.dp, vertical = 9.dp)
                    )
                }

                Glass(Modifier.fillMaxWidth().padding(bottom = 8.dp), radius = 34.dp) {
                    val dockApps = dock.mapNotNull { pkg -> apps.firstOrNull { it.pkg == pkg } }
                    Row(
                        Modifier.fillMaxWidth().padding(14.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        for (a in dockApps) {
                            Image(
                                a.icon, null,
                                Modifier.size(58.dp).clip(RoundedCornerShape(16.dp)).clickable { openApp(ctx, a) }
                            )
                        }
                        if (dockApps.isEmpty()) {
                            Text(
                                "Long-press an app to add it here", color = Color.White.copy(alpha = 0.7f),
                                fontSize = 13.sp, modifier = Modifier.padding(vertical = 18.dp)
                            )
                        }
                    }
                }
            }

            if (searching) SearchOverlay(apps) { searching = false }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AppCell(app: AppInfo, modifier: Modifier, pinned: Boolean, onPin: () -> Unit) {
    val ctx = LocalContext.current
    var menu by remember { mutableStateOf(false) }
    Column(
        modifier.combinedClickable(onClick = { openApp(ctx, app) }, onLongClick = { menu = true }),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Image(app.icon, null, Modifier.size(56.dp).clip(RoundedCornerShape(16.dp)))
        Spacer(Modifier.height(4.dp))
        Text(
            app.label, color = Color.White, fontSize = 11.sp, maxLines = 1,
            overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center
        )
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(
                text = { Text(if (pinned) "Remove from dock" else "Add to dock") },
                onClick = { menu = false; onPin() }
            )
            DropdownMenuItem(
                text = { Text("App info") },
                onClick = {
                    menu = false
                    ctx.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${app.pkg}"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            )
            DropdownMenuItem(
                text = { Text("Uninstall") },
                onClick = {
                    menu = false
                    ctx.startActivity(
                        Intent(Intent.ACTION_DELETE, Uri.parse("package:${app.pkg}"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            )
        }
    }
}

@Composable
fun SearchOverlay(apps: List<AppInfo>, onClose: () -> Unit) {
    val ctx = LocalContext.current
    var q by remember { mutableStateOf("") }
    val fr = remember { FocusRequester() }
    BackHandler(onBack = onClose)
    LaunchedEffect(Unit) { fr.requestFocus() }
    Column(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.7f))
            .statusBarsPadding().imePadding().padding(16.dp)
    ) {
        Glass(Modifier.fillMaxWidth(), radius = 22.dp) {
            Box(Modifier.padding(16.dp)) {
                if (q.isEmpty()) Text("Search apps", color = Color.White.copy(alpha = 0.5f), fontSize = 17.sp)
                BasicTextField(
                    q, { q = it }, Modifier.fillMaxWidth().focusRequester(fr), singleLine = true,
                    textStyle = TextStyle(color = Color.White, fontSize = 17.sp),
                    cursorBrush = SolidColor(Color.White)
                )
            }
        }
        LazyColumn(Modifier.padding(top = 12.dp)) {
            items(apps.filter { it.label.contains(q, ignoreCase = true) }) { a ->
                Row(
                    Modifier.fillMaxWidth().clickable { openApp(ctx, a); onClose() }.padding(vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Image(a.icon, null, Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)))
                    Spacer(Modifier.width(14.dp))
                    Text(a.label, color = Color.White, fontSize = 16.sp)
                }
            }
        }
    }
}
