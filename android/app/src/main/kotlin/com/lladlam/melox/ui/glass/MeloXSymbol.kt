package com.lladlam.melox.ui.glass

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.material3.Icon
import androidx.compose.ui.res.painterResource
import com.lladlam.melox.R
import com.lladlam.melox.ui.animation.MeloXMotion

/**
 * Semantic icon inventory aligned with SF Symbols names. The bundled SF font is
 * the only icon font used by this component; unresolved symbols fail fast.
 */
enum class MeloXSymbol(
    val sfSymbolName: String,
    val materialLigature: String,
) {
    Home("house", "home"),
    Explore("safari", "explore"),
    Library("music.note.list", "library_music"),
    Settings("gear", "settings"),
    Person("person.crop.circle", "account_circle"),
    Search("magnifyingglass", "search"),
    ChevronLeft("chevron.left", "chevron_left"),
    ChevronRight("chevron.right", "chevron_right"),
    ChevronUpDown("chevron.up.chevron.down", "unfold_more"),
    Xmark("xmark", "close"),
    Ellipsis("ellipsis", "more_horiz"),
    Clock("clock", "schedule"),
    Plus("plus", "add"),
    Download("arrow.down.circle", "download"),
    Share("square.and.arrow.up", "ios_share"),
    Mail("paperplane", "mail"),
    Message("message", "chat_bubble"),
    Info("info.circle", "info"),
    Heart("heart", "favorite"),
    Star("star", "star"),
    List("list.bullet", "format_list_bulleted"),
    Check("checkmark", "check"),
    ArrowUp("arrow.up.circle.fill", "arrow_upward"),
    ArrowDown("arrow.down.circle", "arrow_downward"),
    Refresh("arrow.clockwise", "refresh"),
    MusicNote("music.note", "music_note"),
    Calendar("calendar", "calendar_month"),
    Flame("flame.fill", "local_fire_department"),
    RadioWaves("dot.radiowaves.left.and.right", "graphic_eq"),
    Walk("figure.walk.motion", "directions_walk"),
    Sparkles("sparkles", "auto_awesome"),
    Quote("quote.bubble", "format_quote"),
    Devices("display", "devices"),
    Landscape("rectangle.landscape.rotate", "screen_rotation"),
    PictureInPicture("pip", "picture_in_picture"),
    Apps("circle.grid.2x2.fill", "apps"),
    Microphone("mic", "mic"),
    Storage("internaldrive", "storage"),
    Bug("ladybug", "bug_report"),
    Play("play.fill", "play_arrow"),
    Pause("pause.fill", "pause"),
    Previous("backward.fill", "skip_previous"),
    Next("forward.fill", "skip_next"),
    Shuffle("shuffle", "shuffle"),
    Repeat("repeat", "repeat"),
    RepeatOne("repeat.1", "repeat_one"),
    Infinity("infinity", "all_inclusive"),
    Lyrics("quote.bubble", "lyrics"),
    AutoMix("waveform", "graphic_eq"),
    AddToPlaylist("text.badge.plus", "playlist_add"),
    Trash("trash", "delete"),
    Moon("moon", "bedtime"),
    Switch("switch.2", "swap_horiz"),
    Book("book.pages", "menu_book"),
    Comment("bubble.left", "chat_bubble"),
    Volume("speaker.wave.2.fill", "volume_up"),
    Queue("text.line.first.and.arrowtriangle.forward", "queue_music"),
    MoreVertical("ellipsis", "more_vert"),
    Circle("circle", "radio_button_unchecked"),
    CheckCircle("checkmark.circle.fill", "check_circle"),
    Unknown("questionmark.circle", "help"),
}

enum class MeloXSymbolVariant {
    Regular,
    Fill,
}

private val MeloXSfSymbolCodePoints = mapOf(
    "house" to 0x10039E,
    "safari" to 0x1003AC,
    "music.note.list" to 0x10046C,
    "gear" to 0x10035F,
    "person.crop.circle" to 0x10026D,
    "magnifyingglass" to 0x1002AB,
    "chevron.left" to 0x100189,
    "chevron.right" to 0x10018A,
    "chevron.up.chevron.down" to 0x10018F,
    "xmark" to 0x100184,
    "ellipsis" to 0x100360,
    "clock" to 0x10042B,
    "plus" to 0x10017C,
    "square.and.arrow.up" to 0x100202,
    "message" to 0x100324,
    "info.circle" to 0x100174,
    "heart" to 0x1002B4,
    "list.bullet" to 0x1002F2,
    "checkmark" to 0x100185,
    "arrow.down.circle" to 0x100078,
    "paperplane" to 0x10021F,
    "arrow.up.circle.fill" to 0x100077,
    "arrow.clockwise" to 0x100148,
    "music.note" to 0x10046A,
    "calendar" to 0x100249,
    "flame.fill" to 0x10066D,
    "dot.radiowaves.left.and.right" to 0x100319,
    "figure.walk.motion" to 0x101411,
    "sparkles" to 0x1001BF,
    "quote.bubble" to 0x10032E,
    "display" to 0x1008B9,
    "pip" to 0x100833,
    "circle.grid.2x2.fill" to 0x1007BF,
    "rectangle.landscape.rotate" to 0x101EEF,
    "mic" to 0x1002B0,
    "internaldrive" to 0x10097E,
    "ladybug" to 0x100BD4,
    "play.fill" to 0x100284,
    "pause.fill" to 0x100286,
    "backward.fill" to 0x10028A,
    "forward.fill" to 0x10028C,
    "shuffle" to 0x10029D,
    "repeat" to 0x10029E,
    "repeat.1" to 0x10029F,
    "infinity" to 0x100BE0,
    "waveform" to 0x10066B,
    "text.badge.plus" to 0x1002F8,
    "trash" to 0x100211,
    "moon" to 0x1001B9,
    "switch.2" to 0x10070A,
    "book.pages" to 0x10173E,
    "bubble.left" to 0x10032A,
    "speaker.wave.2.fill" to 0x1002A7,
    "text.line.first.and.arrowtriangle.forward" to 0x10163F,
    "questionmark.circle" to 0x10005C,
    "checkmark.circle.fill" to 0x100063,
)

@Composable
fun MeloXSymbolIcon(
    symbol: MeloXSymbol,
    modifier: Modifier = Modifier,
    color: Color,
    variant: MeloXSymbolVariant = MeloXSymbolVariant.Regular,
    iconSize: TextUnit = 24.sp,
    contentDescription: String? = null,
) {
    // Brand overrides: these SF Symbol glyphs are replaced by traced vectors.
    //   Settings  -> the MeloX chainring disc instead of the SF gear.
    //   Microphone-> ic_melox_mic, extracted from the same `mic` glyph with its
    //                broken stem winding fixed (see the drawable's header note).
    // Both the bottom-bar Settings tab and the Settings-list "通用" item flow
    // through this symbol, so they pick up the brand disc automatically; likewise
    // every 听歌识曲 entry (search field + Home quick action card) gets the
    // sealed-off microphone without any change at the call sites.
    when (symbol) {
        MeloXSymbol.Settings -> {
            Icon(
                painter = painterResource(R.drawable.ic_melox_brand_gear),
                contentDescription = contentDescription,
                modifier = modifier,
                tint = color,
            )
            return
        }
        MeloXSymbol.Microphone -> {
            MicrophoneVectorIcon(
                modifier = modifier,
                color = color,
                iconSize = iconSize,
                contentDescription = contentDescription,
            )
            return
        }
        MeloXSymbol.Plus -> {
            PlusVectorIcon(
                modifier = modifier,
                color = color,
                iconSize = iconSize,
                contentDescription = contentDescription,
            )
            return
        }
        MeloXSymbol.Star -> {
            StarVectorIcon(
                modifier = modifier,
                color = color,
                iconSize = iconSize,
                contentDescription = contentDescription,
                variant = variant,
            )
            return
        }
        else -> Unit
    }
    // Material Symbols uses a taller font box than SF Symbols. Rendering the
    // glyph at a slightly smaller em size leaves a real optical inset inside
    // callers' 18/20/24dp icon boxes, instead of clipping the gear and arrows
    // at their ascender/descender edges.
    val codePoint = requireNotNull(MeloXSfSymbolCodePoints[symbol.sfSymbolName]) {
        "Missing SF Symbol mapping: ${symbol.sfSymbolName}"
    }
    SfGlyphIcon(
        codePoint = codePoint,
        modifier = modifier,
        color = color,
        iconSize = iconSize,
        contentDescription = contentDescription,
        weight = if (variant == MeloXSymbolVariant.Fill) FontWeight.Medium else FontWeight.Normal,
    )
}

// sf_pro_subset.ttf 中 mic 字形的度量，用于把矢量还原到与字号渲染同尺寸。
private const val MIC_UPM = 2048f
private const val MIC_INK_UNITS = 2140f
// ic_melox_mic.xml 的 viewport 与墨迹高度。
private const val MIC_VIEWPORT = 24f
private const val MIC_INK_H = 23.90f

// sf_pro_subset.ttf 中 plus（U+10017C）字形的度量，同样用于把矢量还原到与字号渲染同尺寸。
// 该 glyph 由两条独立轮廓（竖直棒 + 水平棒）组成，两条都是实体，无绕向问题。
// 字体墨迹 bbox = (255,-103)-(1905,1547)，即 1650 × 1650 units。
private const val PLUS_UPM = 2048f
private const val PLUS_INK_UNITS = 1650f
// ic_melox_plus.xml 的 viewport 与墨迹高度（18.29 ≈ 1650/2048 × 24 缩放后，与字模同形）。
private const val PLUS_VIEWPORT = 24f
private const val PLUS_INK_H = 18.29f

/**
 * 听歌识曲麦克风。字体里那支 `mic` 字形（U+1002B0）在 nonzero 规则下自身是破的
 * —— 竖杆绕向与其余实体相反，在「拾音弧底部」「底座横线」两处被挖成孔，
 *   所以改为使用 [R.drawable.ic_melox_mic]：同一字形 glyf 轮廓的 1:1 提取，
 *   只把竖杆绕向翻过来，余下像素逐点未变（推导见该 drawable 的头部注释）。
 *
 * ⚠ **尺寸刻意不跟随调用方的 Box**：SF 字形本来就是按 `iconSize`（sp）渲染的，
 *   外面的 18dp / 22dp 图标盒只起占位作用 —— mic 的墨迹纵向本来就会溢出。
 *   所以这里照字体的度量换算，而不是直接把矢量塞进 `modifier`：
 *     字体 unitsPerEm = 2048，mic 墨迹 = 1431 × 2140 单位
 *     ⇒ 墨迹 = iconSize × 2140/2048，默认 24.sp 时 = 16.77 × 25.08 dp
 *     本矢量墨迹占 23.90/24 ⇒ 渲染尺寸 = 墨迹 ÷ (23.90/24)
 *   这样默认 24.sp 得到 25.2dp，首页快捷卡那种 `iconSize = 72.sp` 的大水印得到
 *   75.6dp，**任何字号下都与原字形同尺寸**，换成矢量不会被压缩掉光学字号。
 *
 * ⚠ alpha 走 [androidx.compose.ui.draw.alpha] 而不是带 alpha 的 tint。单路径
 *   fill 本身不会双重混合，但一旦将来把它拆成多条路径，逐路径施加的
 *   ColorFilter.tint 会在轮廓叠合处二次混合出深斑；图层级 alpha 免疫这个问题。
 */
@Composable
private fun MicrophoneVectorIcon(
    modifier: Modifier,
    color: Color,
    iconSize: TextUnit,
    contentDescription: String?,
) {
    val density = LocalDensity.current
    val renderSize = remember(density, iconSize) {
        val emPx = with(density) { iconSize.toPx() }
        with(density) { (emPx * MIC_INK_UNITS / MIC_UPM * MIC_VIEWPORT / MIC_INK_H).toDp() }
    }
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Icon(
            painter = painterResource(R.drawable.ic_melox_mic),
            contentDescription = contentDescription,
            // ⚠ 必须 requiredSize 而不是 size：`size()` 会服从父约束，而调用方给的
            //   18dp / 22dp 图标盒只是占位，mic 本来就会溢出它。用 `size()` 会被
            //   压成 18dp（实测墨迹掉到 12 × 18 dp，整整小三成）—— `requiredSize`
            //   才等价于原来 Canvas 里按 24.sp 自由绘制的行为。
            modifier = Modifier.requiredSize(renderSize).alpha(color.alpha),
            tint = color.copy(alpha = 1f),
        )
    }
}

/**
 * SF Symbols `plus`（U+10017C）的矢量兜底。
 *
 * 换矢量的动机 —— 原先走 `SfGlyphIcon` 靠 `Paint.getTextBounds` 计算 baseline，
 *   但不同 Android 版本 / Skia 版本对同一 glyph 的 `bounds.top` / `bounds.bottom`
 *   会因 hinting、`SUBPIXEL_TEXT_FLAG` 等差异而差 1~2 像素，导致「加号」在不同机型
 *   上纵向偏移 2~4dp（用户 2026-09-26 反馈：其他机型加号偏下）。改走矢量与
 *   [MicrophoneVectorIcon] / [MeloXSymbol.Settings] 同一条兜底路径，跨设备一致。
 *
 * 尺寸刻意不跟随调用方的 Box：调用方给的 `Modifier.size(20.dp)` 只是**占位**，
 *   字号仍是 24.sp；本矢量按同样的字体度量换算，任何 `iconSize` 下都还原成与
 *   原字形**同墨迹尺寸**的矢量（默认 24.sp ⇒ 25.37dp），换成矢量不会被压扁。
 *
 * ⚠ alpha 走 [androidx.compose.ui.draw.alpha] 而不是带 alpha 的 tint（与 mic 一致）：
 *   单路径 fill 本身不会双重混合，但一旦将来把两条棒拆成两条 path，逐路径施加的
 *   ColorFilter.tint 会在叠合处二次混合出深斑；图层级 alpha 免疫这个问题。
 */
@Composable
private fun PlusVectorIcon(
    modifier: Modifier,
    color: Color,
    iconSize: TextUnit,
    contentDescription: String?,
) {
    val density = LocalDensity.current
    val renderSize = remember(density, iconSize) {
        val emPx = with(density) { iconSize.toPx() }
        with(density) { (emPx * PLUS_INK_UNITS / PLUS_UPM * PLUS_VIEWPORT / PLUS_INK_H).toDp() }
    }
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Icon(
            painter = painterResource(R.drawable.ic_melox_plus),
            contentDescription = contentDescription,
            // 同 mic：requiredSize 而非 size，避免被父 20dp 图标盒压扁。
            modifier = Modifier.requiredSize(renderSize).alpha(color.alpha),
            tint = color.copy(alpha = 1f),
        )
    }
}

/**
 * 播放页收藏按钮星形。两个子集字体都没有 star 字形，所以这里用手绘矢量五角星兜底
 * （与 mic / plus 同一条品牌矢量路径）：
 *   - [MeloXSymbolVariant.Regular] → 描边（未收藏）
 *   - [MeloXSymbolVariant.Fill]    → 实心（已收藏）
 *
 * 内凹比 0.40 取自示意图量取的实体/内点半径比；星形外缘占满调用方给的图标盒
 * （与 ellipsis 同样 22.dp），保证两个按钮视觉重量一致（用户要求“协调好大小”）。
 *
 * ⚠ alpha 走图层级 [androidx.compose.ui.draw.alpha] 而不是带 alpha 的 tint，
 *   与 mic / plus 一致：单路径 fill 不会双重混合，但描边在轮廓叠合处若用逐路径
 *   tint 会二次混合出深斑；图层级 alpha 免疫该问题。
 */
@Composable
private fun StarVectorIcon(
    modifier: Modifier,
    color: Color,
    iconSize: TextUnit,
    contentDescription: String?,
    variant: MeloXSymbolVariant,
) {
    Canvas(
        modifier = modifier
            .alpha(color.alpha)
            .then(
                if (contentDescription != null) {
                    Modifier.semantics(mergeDescendants = true) {
                        this.contentDescription = contentDescription
                    }
                } else {
                    Modifier
                },
            ),
    ) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val outer = min(size.width, size.height) / 2f * 0.92f
        val innerRatio = 0.40f
        val strokeW = outer * 0.16f
        // 描边态沿路径中心线绘制，若中心线取 outer，外缘会比实心态多出 strokeW/2。
        // 这里把描边态的路径内缩半个描边宽，使外缘恰好落在 outer 上，
        // 两种态外轮廓半径一致 → 实心星与描边星视觉大小相同。
        val drawOuter = if (variant == MeloXSymbolVariant.Fill) outer else outer - strokeW / 2f
        val inner = drawOuter * innerRatio
        val path = Path().apply {
            for (i in 0 until 10) {
                val r = if (i % 2 == 0) drawOuter else inner
                val angle = (-Math.PI / 2.0 + i * Math.PI / 5.0).toFloat()
                val x = cx + r * cos(angle)
                val y = cy + r * sin(angle)
                if (i == 0) moveTo(x, y) else lineTo(x, y)
            }
            close()
        }
        drawPath(
            path = path,
            color = color.copy(alpha = 1f),
            style = if (variant == MeloXSymbolVariant.Fill) {
                Fill
            } else {
                Stroke(
                    width = strokeW,
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round,
                )
            },
        )
    }
}

/**
 * Apple Music-style search affordance: the magnifier and back arrow share one
 * 300ms path-like transition instead of abruptly replacing the glyph.
 */
@Composable
fun MeloXSearchBackMorphIcon(
    focused: Boolean,
    modifier: Modifier = Modifier,
    color: Color,
    contentDescription: String? = null,
) {
    AnimatedContent(
        targetState = focused,
        transitionSpec = {
            fadeIn(tween(MeloXMotion.IconEnterMillis)) togetherWith
                fadeOut(tween(MeloXMotion.IconExitMillis))
        },
        modifier = modifier,
        label = "search-back-sf-transition",
    ) { isFocused ->
        MeloXSymbolIcon(
            symbol = if (isFocused) MeloXSymbol.ChevronLeft else MeloXSymbol.Search,
            modifier = Modifier,
            color = color,
            iconSize = 24.sp,
            contentDescription = contentDescription,
        )
    }
}

@Composable
private fun SfGlyphIcon(
    codePoint: Int,
    modifier: Modifier,
    color: Color,
    iconSize: TextUnit,
    contentDescription: String?,
    weight: FontWeight,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    val typeface = androidx.compose.runtime.remember(context, weight) {
        val font = androidx.core.content.res.ResourcesCompat.getFont(context, R.font.sf_pro_subset)
            ?: error("SF Symbols font could not be loaded")
        android.graphics.Typeface.create(font, weight.weight)
    }
    androidx.compose.foundation.Canvas(
        modifier = modifier,
    ) {
        drawIntoCanvas { canvas ->
            val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.SUBPIXEL_TEXT_FLAG).apply {
                this.color = color.toArgb()
                this.typeface = typeface
                textSize = with(density) { iconSize.toPx() }
                textAlign = android.graphics.Paint.Align.LEFT
                fontFeatureSettings = "'ss16' 1"
            }
            val glyph = String(Character.toChars(codePoint))
            val bounds = android.graphics.Rect()
            paint.getTextBounds(glyph, 0, glyph.length, bounds)
            val x = size.width / 2f - (bounds.left + bounds.right) / 2f
            val baseline = size.height / 2f - (bounds.top + bounds.bottom) / 2f
            canvas.nativeCanvas.drawText(glyph, x, baseline, paint)
        }
    }
}

@Composable
fun MeloXActionIcon(
    token: String,
    modifier: Modifier = Modifier,
    color: Color,
    enabled: Boolean = true,
    contentDescription: String? = null,
    // ★ SF 字形是按字号渲染的，`Modifier.size(...)` 只是占位框、不裁剪溢出墨迹。
    //   默认 24.sp 对「墨迹近乎满 em」的字形（Share / Plus / 方框类）会溢出到
    //   ~26dp 高 —— 放在 30dp 圆底按钮里就会把圆底顶满。需要贴合框时显式调小。
    iconSize: TextUnit = 24.sp,
) {
    val symbol = when (token) {
        "◷" -> MeloXSymbol.Clock
        "+", "＋" -> MeloXSymbol.Plus
        "↓×" -> MeloXSymbol.Download
        "↗" -> MeloXSymbol.Share
        "✉" -> MeloXSymbol.Mail
        "◎", "◌" -> MeloXSymbol.Message
        "i", "#" -> MeloXSymbol.Info
        "♡", "♥" -> MeloXSymbol.Heart
        "▣", "⇥", "♬" -> MeloXSymbol.List
        "✓" -> MeloXSymbol.Check
        "○" -> MeloXSymbol.Circle
        "♫", "♬" -> MeloXSymbol.MusicNote
        "✦" -> MeloXSymbol.Sparkles
        "❞" -> MeloXSymbol.Quote
        "▣" -> MeloXSymbol.Devices
        "▱" -> MeloXSymbol.Landscape
        "▤" -> MeloXSymbol.PictureInPicture
        "☷", "▦", "▥" -> MeloXSymbol.Apps
        "⌁" -> MeloXSymbol.Microphone
        "▰" -> MeloXSymbol.Storage
        "⚙" -> MeloXSymbol.Settings
        "ⓘ" -> MeloXSymbol.Info
        "⌘" -> MeloXSymbol.Bug
        "↑" -> MeloXSymbol.ArrowUp
        "↓" -> MeloXSymbol.Download
        "‹" -> MeloXSymbol.ChevronLeft
        "›" -> MeloXSymbol.ChevronRight
        "•••", "…" -> MeloXSymbol.Ellipsis
        "×" -> MeloXSymbol.Xmark
        "♪" -> MeloXSymbol.MusicNote
        "↻" -> MeloXSymbol.Refresh
        else -> MeloXSymbol.Unknown
    }
    val semanticLabel = contentDescription ?: when (symbol) {
        MeloXSymbol.Clock -> "历史记录"
        MeloXSymbol.Plus -> "添加"
        MeloXSymbol.Download -> "下载"
        MeloXSymbol.Share -> "分享"
        MeloXSymbol.Mail -> "私信"
        MeloXSymbol.Message -> "消息"
        MeloXSymbol.Info -> "信息"
        MeloXSymbol.Heart -> "收藏"
        MeloXSymbol.Star -> "收藏"
        MeloXSymbol.List -> "列表"
        MeloXSymbol.Check -> "完成"
        MeloXSymbol.MusicNote -> "音乐"
        MeloXSymbol.Sparkles -> "智能功能"
        MeloXSymbol.Quote -> "引用"
        MeloXSymbol.Devices -> "设备"
        MeloXSymbol.Landscape -> "横屏"
        MeloXSymbol.PictureInPicture -> "画中画"
        MeloXSymbol.Apps -> "页面布局"
        MeloXSymbol.Microphone -> "听歌识曲"
        MeloXSymbol.Storage -> "存储"
        MeloXSymbol.Settings -> "设置"
        MeloXSymbol.Bug -> "诊断"
        MeloXSymbol.Play -> "播放"
        MeloXSymbol.Pause -> "暂停"
        MeloXSymbol.Previous -> "上一首"
        MeloXSymbol.Next -> "下一首"
        MeloXSymbol.Shuffle -> "随机播放"
        MeloXSymbol.Repeat -> "重复播放"
        MeloXSymbol.Volume -> "音量"
        MeloXSymbol.Queue -> "播放队列"
        MeloXSymbol.MoreVertical -> "更多操作"
        MeloXSymbol.Circle -> "未选择"
        MeloXSymbol.CheckCircle -> "已选择"
        MeloXSymbol.ArrowUp -> "上移"
        MeloXSymbol.ArrowDown -> "下移"
        MeloXSymbol.ChevronLeft -> "返回"
        MeloXSymbol.ChevronRight -> "打开"
        MeloXSymbol.ChevronUpDown -> "选择选项"
        MeloXSymbol.Ellipsis -> "更多操作"
        MeloXSymbol.Xmark -> "关闭"
        MeloXSymbol.Refresh -> "刷新"
        MeloXSymbol.Unknown -> "未知操作图标 $token"
        else -> symbol.sfSymbolName
    }
    MeloXSymbolIcon(
        symbol = symbol,
        modifier = modifier,
        color = color.copy(alpha = if (enabled) color.alpha else color.alpha * 0.38f),
        variant = if (token == "♥") MeloXSymbolVariant.Fill else MeloXSymbolVariant.Regular,
        iconSize = iconSize,
        contentDescription = semanticLabel,
    )
}
