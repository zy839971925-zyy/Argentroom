/*
 * Silverphase Android port — materials: catalog, "当前胶片/当前相纸" module and the in-panel
 * vertical material list. GPLv3.
 * MaterialChooser.swift (MobileMaterialFilter, MaterialChooser vertical branch,
 * PhoneMaterialModule) and StockCatalogCore.swift. Catalog data is Silverphase's own
 * StockCatalog.json (assets/silverphase/stock_catalog.json); packaging artwork is the
 * Silverphase AI cover set (CC BY-SA 4.0, license/ARTWORK.md), downscaled to 384px WebP.
 */
package com.spectrafilm.app.silverphase

import com.spectrafilm.app.*
import com.spectrafilm.app.editor.*
import com.spectrafilm.app.library.*
import com.spectrafilm.app.look.*
import com.spectrafilm.app.render.*
import com.spectrafilm.app.settings.*
import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.ui.unit.IntOffset
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.json.JSONObject
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.spectrafilm.app.R

/** One Silverphase catalog stock (StockCatalogCore.Stock). */
internal data class SpStock(
    val id: String,
    val name: String,
    val isPaper: Boolean,
    val isPositive: Boolean,
    val isMonochrome: Boolean,
    val isExperimental: Boolean,
    val speedLabel: String?,
    val targetPrint: String?,
) {
    val isFilm: Boolean get() = !isPaper
    /** packagingArtworkURL: Portra 800 pushes reuse the Portra 800 box. */
    val coverAsset: String
        get() = "silverphase/covers/" + (if (id == "kodak_portra_800_push1" || id == "kodak_portra_800_push2") "kodak_portra_800" else id) + ".webp"
    val kindLabel: String
        get() = when {
            isMonochrome -> spS(R.string.sp_kind_mono)
            isPaper -> spS(R.string.sp_kind_paper)
            isPositive -> spS(R.string.sp_kind_positive)
            else -> spS(R.string.sp_kind_negative)
        }
    val subtitle: String get() = listOfNotNull(kindLabel, speedLabel).joinToString(" · ")
}

internal object SpCatalog {
    @Volatile private var cache: List<SpStock>? = null

    fun all(ctx: Context): List<SpStock> = cache ?: synchronized(this) {
        cache ?: load(ctx).also { cache = it }
    }

    private fun load(ctx: Context): List<SpStock> = runCatching {
        val root = JSONObject(ctx.assets.open("silverphase/stock_catalog.json").use { it.readBytes().decodeToString() })
        val arr = root.getJSONArray("stocks")
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val speed = if (o.isNull("nominalSpeed")) null else o.optDouble("nominalSpeed").let { s ->
                val unit = o.optString("speedUnit", "ISO").ifBlank { "ISO" }
                "$unit ${if (s == Math.floor(s)) s.toLong().toString() else s.toString()}"
            }
            SpStock(
                id = o.getString("id"),
                name = o.getString("name"),
                isPaper = o.optString("stage") == "printing",
                isPositive = o.optString("type") == "positive",
                isMonochrome = o.optString("channelModel") == "bw",
                isExperimental = o.optBoolean("experimental", false),
                speedLabel = speed,
                targetPrint = o.optString("targetPrint").takeIf { !o.isNull("targetPrint") && it.isNotBlank() },
            )
        }
    }.getOrDefault(emptyList())

    fun stock(ctx: Context, id: String): SpStock? = all(ctx).firstOrNull { it.id == id }
    fun films(ctx: Context): List<SpStock> = all(ctx).filter { it.isFilm && !it.isExperimental }
    fun papers(ctx: Context): List<SpStock> = all(ctx).filter { it.isPaper }
}

/** Favourites and recents persist per device, like the iOS @State seeded per session. */
internal class SpMaterialMemory(ctx: Context) {
    private val prefs = ctx.getSharedPreferences("silverphase_materials", Context.MODE_PRIVATE)
    val favorites = mutableStateListOf<String>().apply { addAll(prefs.getString("fav", "")!!.split(',').filter { it.isNotBlank() }) }
    val recent = mutableStateListOf<String>().apply { addAll(prefs.getString("recent", "")!!.split(',').filter { it.isNotBlank() }) }
    fun toggleFavorite(id: String) {
        if (id in favorites) favorites.remove(id) else favorites.add(id)
        prefs.edit().putString("fav", favorites.joinToString(",")).apply()
    }
    fun touch(id: String) {
        recent.remove(id); recent.add(0, id)
        while (recent.size > 20) recent.removeAt(recent.lastIndex)
        prefs.edit().putString("recent", recent.joinToString(",")).apply()
    }
}

internal enum class SpMaterialFilter(@androidx.annotation.StringRes val titleRes: Int) {
    ALL(R.string.sp_filter_all), POSITIVE(R.string.sp_filter_positive), NEGATIVE(R.string.sp_filter_negative),
    MONOCHROME(R.string.sp_filter_mono), EXPERIMENTAL(R.string.sp_filter_experimental),
    FAVORITES(R.string.sp_filter_favorites), RECENT(R.string.sp_filter_recent);
    val title: String get() = spS(titleRes)

    fun includes(s: SpStock, fav: Collection<String>, recent: Collection<String>): Boolean = when (this) {
        ALL -> true
        POSITIVE -> s.isPositive && !s.isExperimental
        NEGATIVE -> s.isFilm && !s.isPositive && !s.isExperimental
        MONOCHROME -> s.isMonochrome
        EXPERIMENTAL -> s.isExperimental
        FAVORITES -> s.id in fav
        RECENT -> s.id in recent
    }
}

/**
 * Packaging artwork cache. Covers are 384x216 WebP (CC BY-SA 4.0); each is decoded once off the
 * main thread at the smallest power-of-two sample that still covers the requested width, as
 * RGB_565 (no alpha in the artwork), and kept in a 12 MB LruCache, so scrolling the material
 * list never decodes on the main thread.
 */
internal object SpCovers {
    private val cache = object : android.util.LruCache<String, ImageBitmap>(12 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.width * value.height * 2
    }
    fun cached(asset: String, reqWidthPx: Int): ImageBitmap? = cache.get(key(asset, reqWidthPx))
    private fun key(asset: String, w: Int) = spCoverKey(asset, w)
    fun load(ctx: Context, asset: String, reqWidthPx: Int): ImageBitmap? {
        cached(asset, reqWidthPx)?.let { return it }
        val bmp = runCatching {
            val opts = BitmapFactory.Options().apply {
                inSampleSize = spCoverSampleSize(reqWidthPx)
                inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
            }
            ctx.assets.open(asset).use { BitmapFactory.decodeStream(it, null, opts) }?.asImageBitmap()
        }.getOrNull() ?: return null
        bmp.prepareToDraw()
        cache.put(key(asset, reqWidthPx), bmp)
        return bmp
    }
}

@Composable
internal fun SpCover(asset: String?, fallback: SpGlyph, modifier: Modifier, reqWidth: androidx.compose.ui.unit.Dp = 112.dp) {
    val ctx = LocalContext.current.applicationContext
    val reqPx = with(androidx.compose.ui.platform.LocalDensity.current) { reqWidth.roundToPx() }
    val request = asset?.let { spCoverKey(it, reqPx) }
    val bitmap = rememberSpLoadedValue(
        request,
        cached = { asset?.let { SpCovers.cached(it, reqPx) } },
        load = { withContext(Dispatchers.IO) { asset?.let { SpCovers.load(ctx, it, reqPx) } } },
    )
    Box(modifier.background(SpStyle.well), contentAlignment = Alignment.Center) {
        if (bitmap == null) SpGlyphIcon(fallback, SpStyle.primary, Modifier.size(SpDesign.glyphSize))
        key(request) {
            SpArtworkReveal(bitmap, Modifier.fillMaxSize())
        }
    }
}

private data class SpMaterialCard(val name: String, val subtitle: String, val coverAsset: String?)

/** What the phone panels need from the editor to read and change the materials. */
internal interface SpMaterialHost {
    val filmId: String
    val printId: String
    val scanFilm: Boolean
    /** Digital Intermediate in effect (requested and not overridden by scan_film). */
    val digitalIntermediate: Boolean get() = false
    /** The film can carry a DI (it is a negative). */
    val digitalIntermediateAvailable: Boolean get() = false
    fun setFilm(stock: SpStock)
    fun setPaper(stock: SpStock?)
    /** Choose the DI in place of a paper: scan_film off, DI on. */
    fun setDigitalIntermediate() {}
    fun clearDigitalIntermediate() {}
}

/** PhoneMaterialModule: thumbnail, "当前胶片/当前相纸", full name, kind · speed, "更换 ›". */
@Composable
internal fun SpPhoneMaterialModule(
    host: SpMaterialHost, paper: Boolean, enabled: Boolean,
    onOrigin: (SpOrigin) -> Unit = {}, onChoose: () -> Unit,
) {
    val ctx = LocalContext.current
    val stock = SpCatalog.stock(ctx, if (paper) host.printId else host.filmId)
    val positive = SpCatalog.stock(ctx, host.filmId)?.isPositive == true
    val bypasses = paper && (positive || host.scanFilm || host.digitalIntermediate)
    val name = when {
        paper && positive -> spS(R.string.sp_positive_direct_scan)
        paper && host.scanFilm -> spS(R.string.sp_no_print_profile)
        paper && host.digitalIntermediate -> spS(R.string.sp_digital_intermediate)
        else -> stock?.name ?: spS(if (paper) R.string.sp_choose_paper else R.string.sp_choose_film)
    }
    val subtitle = when {
        bypasses -> spS(R.string.sp_scan_film_directly_sub)
        stock == null -> spS(if (paper) R.string.sp_print_material else R.string.sp_film_material)
        else -> stock.subtitle
    }
    val canChange = !(paper && positive)
    val reduce = LocalSpReduceMotion.current
    val card = SpMaterialCard(name, subtitle, if (bypasses) null else stock?.coverAsset)
    val fallback = if (paper) SpGlyph.PHOTO else SpGlyph.FILM
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp)
            .spOriginOf(onOrigin, radiusPx = with(androidx.compose.ui.platform.LocalDensity.current) { 12.dp.toPx() })
            // C05: the whole card dips and springs back under the finger.
            .spPressable(enabled = enabled && canChange, onClick = onChoose)
            .clip(RoundedCornerShape(12.dp)).background(SpStyle.well)
            .padding(12.dp)
            .semantics { contentDescription = spS(if (paper) R.string.sp_paper_desc else R.string.sp_film_desc, name) + if (canChange) spS(R.string.sp_change_suffix) else "" },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // The outgoing content can overlap the new card briefly. Keep every cover paired
        // with the exact title and subtitle that belong to its transition state.
        androidx.compose.animation.AnimatedContent(
            card, Modifier.weight(1f).clearAndSetSemantics {},
            transitionSpec = spCardSwapTransition(reduce), label = "materialCard",
        ) { shown ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SpCover(
                    shown.coverAsset, fallback,
                    Modifier.width(96.dp).height(54.dp).clip(RoundedCornerShape(6.dp)),
                    reqWidth = 96.dp,
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(spS(if (paper) R.string.sp_current_paper else R.string.sp_current_film), style = SpDesign.help, color = SpStyle.secondary)
                    Text(shown.name, style = SpDesign.selectedValue, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(shown.subtitle, style = SpDesign.help, color = SpStyle.secondary)
                }
            }
        }
        if (canChange) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(spS(R.string.sp_change), style = SpDesign.parameter, color = SpStyle.accent)
                SpGlyphIcon(SpGlyph.CHEVRON_RIGHT, SpStyle.accent, Modifier.size(11.dp))
            }
        }
    }
}

/**
 * The vertical material list shown inside the same floating panel (MaterialChooser,
 * vertical = true, locksMaterialKind = true). Category menu + search; tapping a material
 * re-develops at once and keeps the list; long-press toggles favourite.
 */
@Composable
internal fun SpMaterialList(host: SpMaterialHost, paper: Boolean, memory: SpMaterialMemory, enabled: Boolean) {
    val ctx = LocalContext.current
    var query by rememberSaveable { mutableStateOf("") }
    var searching by rememberSaveable { mutableStateOf(false) }
    var filter by rememberSaveable(paper) { mutableStateOf(SpMaterialFilter.ALL) }
    var menuOpen by remember { mutableStateOf(false) }
    val catalog = if (paper) SpCatalog.papers(ctx) else SpCatalog.films(ctx)
    val filters = if (paper) listOf(SpMaterialFilter.ALL, SpMaterialFilter.MONOCHROME, SpMaterialFilter.FAVORITES, SpMaterialFilter.RECENT)
    else SpMaterialFilter.entries.filter { it != SpMaterialFilter.EXPERIMENTAL || SpCatalog.all(ctx).any { s -> s.isFilm && s.isExperimental } }
    val stocks = catalog.filter {
        (query.isBlank() || it.name.contains(query.trim(), ignoreCase = true)) && filter.includes(it, memory.favorites, memory.recent)
    }.let { list -> if (filter == SpMaterialFilter.RECENT) list.sortedBy { memory.recent.indexOf(it.id).takeIf { i -> i >= 0 } ?: 999 } else list }
    val positiveFilm = SpCatalog.stock(ctx, host.filmId)?.isPositive == true
    val listState = rememberLazyListState()
    // Warm the cover cache for this list off the main thread before the first scroll.
    val coverPx = with(androidx.compose.ui.platform.LocalDensity.current) { 112.dp.roundToPx() }
    LaunchedEffect(paper, coverPx) {
        withContext(Dispatchers.IO) {
            // Stops when the list closes (the effect's scope is cancelled).
            catalog.forEach { ensureActive(); SpCovers.load(ctx, it.coverAsset, coverPx) }
        }
    }
    val focus = remember { FocusRequester() }
    val reduce = LocalSpReduceMotion.current
    // C05: rows cascade in when the list opens (the list leaves composition when it folds).
    val openedAt = remember { android.os.SystemClock.uptimeMillis() }
    val listAlpha by androidx.compose.animation.core.animateFloatAsState(if (enabled) 1f else 0.5f, SpMotion.easeOut(200), label = "listEnabled")

    Column(Modifier.fillMaxSize().padding(vertical = SpDesign.cardInset).graphicsLayer { alpha = listAlpha }) {
        // C05: the category header and the search field swap with a short rise + fade.
        androidx.compose.animation.AnimatedContent(
            searching, Modifier.fillMaxWidth().height(SpDesign.panelHeader).padding(horizontal = SpDesign.cardInset + 10.dp),
            transitionSpec = spSwapTransition(reduce, rise = searching), label = "materialHeader",
        ) { isSearching -> Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            if (isSearching) {
                BasicTextField(
                    query, { query = it }, singleLine = true, textStyle = SpDesign.label.copy(color = SpStyle.primary),
                    cursorBrush = SolidColor(SpStyle.accent),
                    modifier = Modifier.weight(1f).focusRequester(focus).semantics { contentDescription = spS(R.string.sp_search_materials) },
                    decorationBox = { inner ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (query.isEmpty()) Text(spS(R.string.sp_search_materials), style = SpDesign.label, color = SpStyle.muted)
                            inner()
                        }
                    },
                )
                LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
                SpChromeButton(spS(R.string.sp_end_search), SpGlyph.CLOSE) { searching = false; query = "" }
            } else {
                val overlay = LocalSpOverlay.current
                var filterOrigin by remember { mutableStateOf<SpOrigin?>(null) }
                Box {
                    Row(
                        Modifier.heightIn(min = SpDesign.hitSize)
                            .spOriginOf({ filterOrigin = it }, radiusPx = 0f)
                            .spClickable {
                                val o = filterOrigin
                                if (overlay != null && o != null) overlay.showMenu(o, filters.map { f ->
                                    SpMenuItem(f.title, checked = f == filter) { filter = f }
                                }) else menuOpen = true
                            }
                            .semantics { contentDescription = spS(R.string.sp_material_category) },
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(filter.title, style = SpDesign.parameter, color = SpStyle.primary)
                        SpGlyphIcon(SpGlyph.CHEVRON_DOWN, SpStyle.primary, Modifier.size(10.dp))
                    }
                    if (overlay == null) DropdownMenu(menuOpen, { menuOpen = false }, containerColor = SpStyle.menuSurface) {
                        for (f in filters) DropdownMenuItem(
                            text = { Text(f.title, color = SpStyle.primary, style = SpDesign.parameter) },
                            leadingIcon = { Box(Modifier.size(14.dp)) { if (f == filter) SpGlyphIcon(SpGlyph.CHECK, SpStyle.primary, Modifier.size(14.dp)) } },
                            onClick = { filter = f; menuOpen = false },
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                SpChromeButton(spS(R.string.sp_search_materials), SpGlyph.SEARCH) { searching = true }
            }
        } }
        // Reveal the current material, else the top (MaterialChooser.reveal).
        val currentId = if (paper) host.printId else host.filmId
        LaunchedEffect(paper, filter) {
            val specials = if (paper && filter == SpMaterialFilter.ALL && query.isBlank()) (if (host.digitalIntermediateAvailable) 2 else 1) else 0
            val i = stocks.indexOfFirst { it.id == currentId }
            listState.scrollToItem(if (i >= 0) maxOf(0, i + specials - 1) else 0)
        }
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxWidth().weight(1f).testTag("materials.vertical-list"),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = SpDesign.contentInset, end = SpDesign.contentInset, bottom = SpDesign.cardInset,
            ),
        ) {
            if (paper && filter == SpMaterialFilter.ALL && query.isBlank()) {
                item("special.scan", contentType = "stock") {
                    SpMaterialRow(
                        modifier = spMaterialListItem(reduce).spListEntrance(0, openedAt, reduce),
                        spS(R.string.sp_no_print_profile), spS(R.string.sp_scan_film_directly_sub), "silverphase/covers/workflow_film_scan.webp", SpGlyph.FILM,
                        selected = host.scanFilm, favorite = false, enabled = enabled,
                        onClick = { host.setPaper(null) }, onLongClick = null,
                    )
                }
                if (host.digitalIntermediateAvailable) item("special.di", contentType = "stock") {
                    SpMaterialRow(
                        modifier = spMaterialListItem(reduce).spListEntrance(1, openedAt, reduce),
                        spS(R.string.sp_digital_intermediate), "Cineon Log", "silverphase/covers/workflow_digital_intermediate.webp", SpGlyph.FILM_STACK,
                        selected = host.digitalIntermediate && !host.scanFilm, favorite = false, enabled = enabled,
                        onClick = { host.setDigitalIntermediate() }, onLongClick = null,
                    )
                }
            }
            val specialRows = if (paper && filter == SpMaterialFilter.ALL && query.isBlank()) (if (host.digitalIntermediateAvailable) 2 else 1) else 0
            itemsIndexed(stocks, key = { _, it -> it.id }, contentType = { _, _ -> "stock" }) { i, s ->
                val selected = if (paper) host.printId == s.id && !host.scanFilm && !host.digitalIntermediate else host.filmId == s.id
                SpMaterialRow(
                    // Filter / search changes slide rows to their new places (animateItem).
                    modifier = spMaterialListItem(reduce).spListEntrance(i + specialRows, openedAt, reduce),
                    s.name, s.subtitle, s.coverAsset, if (paper) SpGlyph.PHOTO else SpGlyph.FILM,
                    selected = selected, favorite = s.id in memory.favorites,
                    enabled = enabled && !(paper && positiveFilm),
                    onClick = {
                        if (paper) host.setPaper(s) else host.setFilm(s)
                        memory.touch(s.id)
                    },
                    onLongClick = { memory.toggleFavorite(s.id) },
                )
            }
            if (stocks.isEmpty()) item("empty", contentType = "empty") {
                Text(spS(R.string.sp_no_materials_match), style = SpDesign.label, color = SpStyle.secondary, modifier = spMaterialListItem(reduce).padding(12.dp))
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyItemScope.spMaterialListItem(reduce: Boolean): Modifier =
    Modifier.animateItem(
        fadeInSpec = SpMotion.easeOut(200),
        placementSpec = if (reduce) null else SpMotion.smooth028(IntOffset.VisibilityThreshold),
        fadeOutSpec = SpMotion.easeOut(140),
    )

@Composable
private fun SpMaterialRow(
    modifier: Modifier = Modifier,
    title: String,
    subtitle: String,
    cover: String?,
    fallback: SpGlyph,
    selected: Boolean,
    favorite: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
) {
    val press = remember { MutableInteractionSource() }
    val view = LocalView.current
    Row(
        modifier.fillMaxWidth().heightIn(min = 64.dp)
            // C05: press dip + spring back; the orange selection ring and fill cross-fade in.
            .spPressFeedback(press, enabled, pressedScale = 0.98f)
            .spBrowserSelectionAnimated(selected)
            .combinedClickable(
                interactionSource = press, indication = null, enabled = enabled,
                onLongClickLabel = spS(if (favorite) R.string.sp_remove_favorite else R.string.sp_add_favorite),
                onLongClick = onLongClick,
                onClick = {
                    if (!selected) SpHaptics.select(view)
                    onClick()
                },
            )
            .padding(12.dp)
            .semantics { contentDescription = spS(R.string.sp_item_desc, title, subtitle); stateDescription = spS(if (selected) R.string.sp_selected else R.string.sp_not_selected) }
            .graphicsLayer { alpha = if (enabled) 1f else 0.5f },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Cover first: 112x63 (16:9) instead of the iOS 72x40, at the user's request; name second.
        Box(Modifier.width(112.dp).height(63.dp).clip(RoundedCornerShape(6.dp))) {
            SpCover(cover, fallback, Modifier.fillMaxSize(), reqWidth = 112.dp)
            SpPopIn(favorite, Modifier.align(Alignment.TopEnd).padding(4.dp)) {
                SpGlyphIcon(SpGlyph.STAR_FILL, SpStyle.accent, Modifier.size(12.dp))
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = SpDesign.selectedValue, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = SpDesign.help, color = SpStyle.secondary, maxLines = 1)
        }
        Box(Modifier.width(20.dp)) {
            SpPopIn(selected) { SpGlyphIcon(SpGlyph.CHECK, SpStyle.accent, Modifier.size(16.dp)) }
        }
    }
}
