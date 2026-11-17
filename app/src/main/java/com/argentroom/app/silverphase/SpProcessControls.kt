/*
 * Silverphase Android port — the seven process panels. GPLv3.
 * Order, labels, ranges, steps and display formats follow ProcessControls.swift and
 * MobileProcessContract (ProcessContract.swift / DesktopControlDefaults.json). Every row is
 * bound to the Argentroom-for-Android ParamsState field that drives the same engine stage;
 * the mapping and its status (exact / equivalent / missing) is recorded in
 * worklog/ENGINE_MAPPING.md. Rows whose stage the Android engine does not have are shown in
 * place but disabled (SpUnavailable), never wired to a look-alike.
 */
package com.argentroom.app.silverphase

import com.argentroom.app.*
import com.argentroom.app.editor.*
import com.argentroom.app.library.*
import com.argentroom.app.look.*
import com.argentroom.app.render.*
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.argentroom.app.editor.PARAM_DEFAULTS
import com.argentroom.app.R
import com.argentroom.app.look.ParamsState
import com.argentroom.app.look.SilverPrint
import com.argentroom.libraw.WhiteBalance
import kotlin.math.abs
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.sqrt

/** What the panels need from the editor beyond ParamsState. */
internal interface SpEditorHost : SpMaterialHost {
    val state: ParamsState
    val isRaw: Boolean
    /** Long/short of the photo as shown (for film_format_mm from the frame's short side). */
    val imageAspect: Float
    val enabled: Boolean
    /** The scene-latitude measurement for the 拍摄 panel (SpLatitude.kt). */
    val latitude: SpLatitudeUi get() = SpLatitudeUi.Idle
    fun retryLatitude() {}
    /** Continuous ruler interaction began (true) or ended (false). */
    fun interaction(active: Boolean)
    /** The Android crop tool's panel, shown inside the 构图 panel. */
    @Composable fun ColumnScope.cropControls()
    /** Silverphase film_render.halation.antihalation_mode (ANTIHALATION_MODES). */
    fun setAntihalationMode(mode: String) { state.antihalationMode = mode }
    /** Silverphase print_render.edr_enabled ("Highlight Expansion"). */
    fun setExtendedDynamicRange(on: Boolean) { state.extendedDynamicRange = on }
    /** Auto-exposure metering id (engine AUTO_EXPOSURE_METHODS), or null = auto exposure off. */
    fun setMeteringMethod(method: String?) {
        if (method == null) state.autoExposure = false else { state.autoExposure = true; state.autoExposureMethod = method }
    }
    /** Silverphase io.digital_intermediate_blue_compensation. */
    fun setDigitalIntermediateBlueCompensation(on: Boolean) { state.digitalIntermediateBlueCompensation = on }
}

/** MobileProcessContract.ui.frames. */
internal data class SpFrame(val id: String, val short: Double, val long: Double)

internal val SP_FRAMES = listOf(
    SpFrame("Super 8", 4.01, 5.79), SpFrame("16mm", 7.49, 10.26), SpFrame("Super 16", 7.41, 12.52),
    SpFrame("Super 35", 14.0, 24.89), SpFrame("65mm", 23.01, 52.63), SpFrame("110", 13.0, 17.0),
    SpFrame("APS", 16.7, 30.2), SpFrame("135", 24.0, 36.0), SpFrame("120", 56.0, 56.0), SpFrame("Custom", 24.0, 36.0),
)

/** Silverphase-only UI keys that have no ParamsState field (film-frame helpers). Per editor session. */
internal class SpUiKeys {
    var filmFrame by mutableStateOf("135")
    var filmSide by mutableStateOf("short")
    var sideUnit by mutableStateOf("mm")
    var sideLengthMM by mutableStateOf(24.0)
}

private const val GRAIN_BASE_AREA = 0.2   // ParamsState default particle area (µm²) = size scale 1
private const val GLARE_BASE_PERCENT = 0.03 // argentroom glare.percent default = glare amount 1
private const val SCANNER_BASE = 0.7      // scanner unsharp amount at UI strength 1 (ProcessContract)

@Composable
internal fun ColumnScope.SpProcessControls(stage: SpStage, host: SpEditorHost, ui: SpUiKeys) {
    val s = host.state
    val ctx = LocalContext.current
    val film = SpCatalog.stock(ctx, s.filmProfile)
    val paper = SpCatalog.stock(ctx, s.printProfile)
    val filmMono = film?.isMonochrome == true
    val paperMono = paper?.isMonochrome == true
    val printBypassed = s.scanFilm || host.digitalIntermediate
    val en = host.enabled

    @Composable
    fun num(
        title: String, value: Double, lo: Double, hi: Double, default: Double, step: Double? = null,
        digits: Int = 2, enabled: Boolean = true, entryLo: Double = lo, entryHi: Double = hi,
        help: Int? = null,
        set: (Double) -> Unit,
    ) = SpRulerRow(
        title, value, lo, hi, default, onValue = set, onReset = { set(default) }, onInteraction = host::interaction,
        fractionDigits = digits, step = step, enabled = en && enabled, entryLo = entryLo, entryHi = entryHi, help = help,
    )

    when (stage) {
        SpStage.CAMERA -> {
            val pair = when {
                s.scanFilm -> film?.name ?: spS(R.string.sp_film)
                else -> (film?.name ?: spS(R.string.sp_film)) + " · " + (paper?.name ?: spS(R.string.sp_paper))
            }
            SpHelp(pair)
            SpLatitudeSection(host.latitude, onRetry = host::retryLatitude)
            SpSectionTitle(spS(R.string.sp_metering_exposure))
            // MobileMeteringMode: the engine now meters Silverphase's RFC-015 intents itself, so
            // the ids are written as they are. A document from before them that metered with
            // argentroom's "matrix" reads as Balanced; any other legacy id is shown as is.
            val meter = if (!s.autoExposure) "custom" else when (s.autoExposureMethod) {
                "matrix" -> "balanced"
                else -> s.autoExposureMethod
            }
            val meterIds = listOf("custom", "balanced", "center", "protect_highlights", "protect_shadows", "center_weighted")
            SpChoice(
                spS(R.string.sp_metering_mode), meter,
                listOf(
                    "custom" to spS(R.string.sp_auto_exposure_off_raw_baseline), "balanced" to spS(R.string.sp_balanced), "center" to spS(R.string.sp_center),
                    "protect_highlights" to spS(R.string.sp_protect_highlights), "protect_shadows" to spS(R.string.sp_protect_shadows),
                    "center_weighted" to spS(R.string.sp_center_weighted),
                ) + if (meter !in meterIds) listOf(meter to meter) else emptyList(),
                enabled = en, help = R.string.sp_help_metering) { id ->
                when (id) {
                    "custom" -> host.setMeteringMethod(null)
                    // Re-picking the legacy row keeps the legacy id (no silent re-meter).
                    meter -> if (id !in meterIds) host.setMeteringMethod(s.autoExposureMethod) else host.setMeteringMethod(id)
                    else -> host.setMeteringMethod(id)
                }
            }
            num(spS(R.string.sp_exposure_compensation_ev), s.exposureCompensationEv.toDouble(), -4.0, 4.0, 0.0, step = 1.0 / 3, help = R.string.sp_help_exposure_comp) {
                s.exposureCompensationEv = it.toFloat()
            }
            SpSectionTitle(spS(R.string.sp_tone))
            // C03: camera tone, ported from Silverphase spk_camera_tone (engine filming stage).
            num(spS(R.string.sp_highlights), s.cameraToneHighlights.toDouble(), -100.0, 100.0, 0.0, step = 1.0, digits = 0, help = R.string.sp_help_camera_tone) { s.cameraToneHighlights = it.toFloat() }
            num(spS(R.string.sp_shadows), s.cameraToneShadows.toDouble(), -100.0, 100.0, 0.0, step = 1.0, digits = 0, help = R.string.sp_help_camera_tone) { s.cameraToneShadows = it.toFloat() }
            num(spS(R.string.sp_whites), s.cameraToneWhites.toDouble(), -100.0, 100.0, 0.0, step = 1.0, digits = 0, help = R.string.sp_help_camera_tone) { s.cameraToneWhites = it.toFloat() }
            num(spS(R.string.sp_blacks), s.cameraToneBlacks.toDouble(), -100.0, 100.0, 0.0, step = 1.0, digits = 0, help = R.string.sp_help_camera_tone) { s.cameraToneBlacks = it.toFloat() }
            SpSectionTitle(spS(R.string.sp_white_balance))
            if (host.isRaw) {
                val preset = when (s.rawWhiteBalance) {
                    WhiteBalance.AS_SHOT -> "asShot"; WhiteBalance.DAYLIGHT -> "daylight"; WhiteBalance.TUNGSTEN -> "tungsten"
                    else -> when (s.rawTemperature.toInt()) { 6500 -> "cloudy"; 7500 -> "shade"; 4000 -> "fluorescent"; else -> "custom" }
                }
                SpChoice(
                    spS(R.string.sp_white_balance_preset), preset,
                    listOf("asShot" to spS(R.string.sp_as_shot), "daylight" to spS(R.string.sp_daylight), "cloudy" to spS(R.string.sp_cloudy), "shade" to spS(R.string.sp_wb_shade), "tungsten" to spS(R.string.sp_tungsten), "fluorescent" to spS(R.string.sp_fluorescent), "custom" to spS(R.string.sp_custom)),
                    enabled = en, help = R.string.sp_help_wb_preset) { id ->
                    when (id) {
                        "asShot" -> s.rawWhiteBalance = WhiteBalance.AS_SHOT
                        "daylight" -> s.rawWhiteBalance = WhiteBalance.DAYLIGHT
                        "tungsten" -> s.rawWhiteBalance = WhiteBalance.TUNGSTEN
                        "cloudy" -> { s.rawWhiteBalance = WhiteBalance.CUSTOM; s.rawTemperature = 6500f }
                        "shade" -> { s.rawWhiteBalance = WhiteBalance.CUSTOM; s.rawTemperature = 7500f }
                        "fluorescent" -> { s.rawWhiteBalance = WhiteBalance.CUSTOM; s.rawTemperature = 4000f }
                        else -> s.rawWhiteBalance = WhiteBalance.CUSTOM
                    }
                }
                val custom = s.rawWhiteBalance == WhiteBalance.CUSTOM
                num(spS(R.string.sp_temperature_k), s.rawTemperature.toDouble(), 2000.0, 12000.0, 5500.0, step = 100.0, digits = 0, help = R.string.sp_help_temperature) {
                    s.rawWhiteBalance = WhiteBalance.CUSTOM; s.rawTemperature = it.toFloat()
                }
                // Silverphase tint −150…150 → LibRaw green multiplier 0.5…1.5 (linear, uncalibrated).
                num(spS(R.string.sp_tint), (s.rawTint - 1.0) * 300.0, -150.0, 150.0, 0.0, step = 5.0, digits = 0, help = R.string.sp_help_tint) {
                    s.rawWhiteBalance = WhiteBalance.CUSTOM; s.rawTint = (1.0 + it / 300.0).toFloat()
                }
                SpFlag(spS(R.string.sp_temperature_as_shot), !custom, en) { if (it) s.rawWhiteBalance = WhiteBalance.AS_SHOT }
                SpTextAction(spS(R.string.sp_restore_as_shot_white_balance), en) {
                    s.rawWhiteBalance = WhiteBalance.AS_SHOT; s.rawTemperature = PARAM_DEFAULTS.rawTemperature; s.rawTint = PARAM_DEFAULTS.rawTint
                }
            } else {
                SpHelp(spS(R.string.sp_white_balance_is_baked_into_this_file_a_r))
            }
            // C03: creative white balance (LookUiParams, a pre-engine input op) for every file.
            num(spS(R.string.sp_creative_warmth), s.creativeWbTemp.toDouble(), -100.0, 100.0, 0.0, step = 1.0, digits = 0, help = R.string.sp_help_creative_wb) {
                s.creativeWbTemp = it.toFloat()
            }
            num(spS(R.string.sp_creative_tint), s.creativeWbTint.toDouble(), -100.0, 100.0, 0.0, step = 1.0, digits = 0, help = R.string.sp_help_creative_wb) {
                s.creativeWbTint = it.toFloat()
            }
            SpFlag(spS(R.string.sp_balance_to_film), s.balanceToFilmStock, en, help = R.string.sp_help_balance_to_film) { s.balanceToFilmStock = it }
            SpSectionTitle(spS(R.string.sp_lens_diffusion))
            SpChoice(
                spS(R.string.sp_colour_filter), s.cameraFilter,
                listOf("" to spS(R.string.sp_off), "w8" to spS(R.string.sp_filter_w8), "w11" to spS(R.string.sp_filter_w11), "w12" to spS(R.string.sp_filter_w12),
                    "w15" to spS(R.string.sp_filter_w15), "w21" to spS(R.string.sp_filter_w21), "w25" to spS(R.string.sp_filter_w25),
                    "w47" to spS(R.string.sp_filter_w47), "w58" to spS(R.string.sp_filter_w58)),
                enabled = en, help = R.string.sp_help_colour_filter,
            ) { s.cameraFilter = it }
            SpDiffusion(spS(R.string.sp_shooting_diffusion), s.cameraDiffusionState, en, host, R.string.sp_help_lens_diffusion)
        }

        SpStage.FILM -> {
            SpPhoneMaterialModule(host, paper = false, enabled = en, onOrigin = { host.materialOrigin(false, it) }) { host.openMaterials(paper = false) }
            SpSectionTitle(spS(R.string.sp_frame))
            SpChoice(spS(R.string.sp_film_format), ui.filmFrame, SP_FRAMES.map { it.id to (if (it.id == "Custom") spS(R.string.sp_custom) else it.id) }, enabled = en, help = R.string.sp_help_film_format) { id ->
                ui.filmFrame = id
                SP_FRAMES.firstOrNull { it.id == id }?.takeIf { id != "Custom" }?.let { f ->
                    ui.sideLengthMM = if (ui.filmSide == "long") f.long else f.short
                }
                applyFilmFormat(s, ui, host.imageAspect)
            }
            SpChoice(spS(R.string.sp_size_basis), ui.filmSide, listOf("short" to spS(R.string.sp_short), "long" to spS(R.string.sp_long_edge)), enabled = en) { side ->
                ui.filmSide = side
                SP_FRAMES.firstOrNull { it.id == ui.filmFrame }?.takeIf { it.id != "Custom" }?.let { f ->
                    ui.sideLengthMM = if (side == "long") f.long else f.short
                }
                applyFilmFormat(s, ui, host.imageAspect)
            }
            SpChoice(spS(R.string.sp_length_unit), ui.sideUnit, listOf("mm" to "mm", "cm" to "cm", "inch" to "inch"), enabled = en) { ui.sideUnit = it }
            val factor = when (ui.sideUnit) { "inch" -> 25.4; "cm" -> 10.0; else -> 1.0 }
            num(
                spS(R.string.sp_reference_size, ui.sideUnit), ui.sideLengthMM / factor, 4 / factor, 200 / factor, 24 / factor,
                digits = if (ui.sideUnit == "inch") 3 else 1, enabled = ui.filmFrame == "Custom",
            ) { ui.sideLengthMM = it * factor; applyFilmFormat(s, ui, host.imageAspect) }

            SpSectionTitle(spS(R.string.sp_grain_halation))
            SpFlag(spS(R.string.sp_grain), s.grainActive, en, help = R.string.sp_help_grain) { s.grainActive = it }
            val grainOn = s.grainActive
            num(spS(R.string.sp_grain_strength), s.grainAmount.toDouble(), 0.0, 2.0, 1.0, step = 0.05, enabled = grainOn, help = R.string.sp_help_grain) { s.grainAmount = it.toFloat() }
            SpFlag(spS(R.string.sp_sub_layers), s.grainSublayersActive, en && grainOn && !filmMono, help = R.string.sp_help_sublayers) { s.grainSublayersActive = it }
            // grain_size_scale: area = base · scale² (Silverphase pipeline.cpp: particle_area_um2·size_scale²).
            num(spS(R.string.sp_grain_size), sqrt(s.grainParticleAreaUm2 / GRAIN_BASE_AREA), 0.5, 2.0, 1.0, step = 0.1, enabled = grainOn, help = R.string.sp_help_grain_size) {
                s.grainParticleAreaUm2 = (GRAIN_BASE_AREA * it * it).toFloat()
            }
            // _grainSoftness · 0.65 = grain_softness = film_render.grain.blur.
            num(spS(R.string.sp_grain_softness), s.grainBlur / 0.65, 0.0, 2.0, 1.0, step = 0.1, enabled = grainOn, help = R.string.sp_help_grain_softness) {
                s.grainBlur = (it * 0.65).toFloat()
            }
            // ProcessControls.swift `_halation`: the anti-halation layer, named by how much
            // halation it lets through (no layer = High ... strong layer = Low), plus Off.
            val halMode = if (!s.halationActive) "off" else s.antihalationMode.takeIf { it in com.argentroom.engine.ANTIHALATION_MODES } ?: "profile"
            SpChoice(
                spS(R.string.sp_film_halation), halMode,
                listOf(
                    "profile" to spS(R.string.sp_follow_film), "none" to spS(R.string.sp_halation_high),
                    "weak" to spS(R.string.sp_halation_medium), "strong" to spS(R.string.sp_halation_low),
                    "off" to spS(R.string.sp_off),
                ), enabled = en, help = R.string.sp_help_halation) { id ->
                if (id == "off") s.halationActive = false
                else { s.halationActive = true; host.setAntihalationMode(id) }
            }
            val halOn = s.halationActive
            num(spS(R.string.sp_halation_strength), s.halHalationAmount.toDouble(), 0.0, 4.0, 1.0, step = 0.1, enabled = halOn, help = R.string.sp_help_halation_strength) { s.halHalationAmount = it.toFloat() }
            // The CineStill back-reflection headroom only exists while the film's own (no-layer)
            // tag is followed; Silverphase also disables it at halation strength 0.
            if (s.filmProfile == "cinestill_800t") num(
                spS(R.string.sp_highlight_headroom_ev), s.halationHeadroomEv.toDouble(), 0.0, 8.0, 4.0, step = 0.1,
                enabled = halOn && halMode == "profile" && s.halHalationAmount != 0f, help = R.string.sp_help_headroom,
            ) { s.halationHeadroomEv = it.toFloat() }
            num(spS(R.string.sp_scatter), s.halScatterAmount.toDouble(), 0.0, 1.0, 1.0, step = 0.1, enabled = halOn, help = R.string.sp_help_scatter) { s.halScatterAmount = it.toFloat() }
        }

        SpStage.DEVELOPMENT -> {
            SpSectionTitle(spS(R.string.sp_negative_response))
            // density_curve_gamma → film_render.density_curve_gamma (ParamsState.filmGammaFactor).
            num(spS(R.string.sp_development_contrast), s.filmGammaFactor.toDouble(), 0.5, 1.5, 1.0, step = 0.05, help = R.string.sp_help_dev_contrast) { s.filmGammaFactor = it.toFloat() }
            SpFlag(spS(R.string.sp_dir_effect), s.couplersActive, en && !filmMono, help = R.string.sp_help_dir) { s.couplersActive = it }
            num(spS(R.string.sp_couplers_strength), s.couplersAmount.toDouble(), 0.0, 1.4, 1.0, step = 0.05, enabled = s.couplersActive && !filmMono, help = R.string.sp_help_couplers_strength) {
                s.couplersAmount = it.toFloat()
            }
        }

        SpStage.PRINT -> {
            SpPhoneMaterialModule(host, paper = true, enabled = en, onOrigin = { host.materialOrigin(true, it) }) { host.openMaterials(paper = true) }
            val positive = film?.isPositive == true
            SpFlag(spS(R.string.sp_scan_film_directly), s.scanFilm, en && !positive, help = R.string.sp_help_scan_direct) { s.scanFilm = it }
            SpFlag(spS(R.string.sp_digital_intermediate), host.digitalIntermediate, en && host.digitalIntermediateAvailable, help = R.string.sp_help_di) { on ->
                if (on) host.setDigitalIntermediate() else host.clearDigitalIntermediate()
            }
            // iOS keeps this as a preference (diBlueCompensation); here it rides with the recipe.
            SpFlag(
                spS(R.string.sp_di_blue_compensation), s.digitalIntermediateBlueCompensation,
                en && host.digitalIntermediateAvailable && host.digitalIntermediate, help = R.string.sp_help_di_blue,
            ) { host.setDigitalIntermediateBlueCompensation(it) }
            if (!printBypassed) {
                SpSectionTitle(spS(R.string.sp_exposure_filtration))
                // _printBrightnessStops: print_exposure = 2^-stops.
                val stops = -log2(s.printExposure.toDouble().coerceAtLeast(1e-6))
                num(spS(R.string.sp_enlarger_exposure_ev), stops, -3.0, 3.0, 0.0, step = 0.25, help = R.string.sp_help_enlarger_exposure) { s.printExposure = 2.0.pow(-it).toFloat() }
                // print_contrast → density_curves_morph.gamma_factor, morph active iff ≠ 1 (params.cpp).
                num(spS(R.string.sp_paper_contrast), s.morphGammaFactor.toDouble().takeIf { s.morphActive } ?: 1.0, 0.7, 1.3, 1.0, step = 0.05, help = R.string.sp_help_paper_contrast) {
                    s.morphGammaFactor = it.toFloat(); s.morphActive = abs(it - 1.0) > 1e-9
                }
                if (SilverPrint.isSilverPaper(s.printProfile)) {
                    SpChoice(spS(R.string.sp_paper_grade), s.paperGrade, SilverPrint.GRADES.map { it.label to it.label }, enabled = en, help = R.string.sp_help_paper_grade) { s.paperGrade = it }
                } else {
                    num(spS(R.string.sp_yellow_y), s.printYFilterShift.toDouble(), -50.0, 50.0, 0.0, step = 1.0, help = R.string.sp_help_filter_y) { s.printYFilterShift = it.toFloat() }
                    num(spS(R.string.sp_magenta_m), s.printMFilterShift.toDouble(), -50.0, 50.0, 0.0, step = 1.0, help = R.string.sp_help_filter_m) { s.printMFilterShift = it.toFloat() }
                }
                var printEffects by rememberSaveable { mutableStateOf(true) }
                SpFlag(spS(R.string.sp_print_effects), printEffects, en, help = R.string.sp_help_print_effects) { on ->
                    printEffects = on
                    if (!on) { s.preflashExposure = 0f; s.glareActive = false }
                }
                num(spS(R.string.sp_pre_flash), s.preflashExposure.toDouble(), 0.0, 0.05, 0.0, step = 0.005, digits = 3, enabled = printEffects, entryHi = 1.0, help = R.string.sp_help_preflash) {
                    s.preflashExposure = it.toFloat()
                }
                SpDiffusion(spS(R.string.sp_soft_focus_enlargement), s.printDiffusionState, en, host, R.string.sp_help_enlarger_diffusion)
            } else {
                SpHelp(spS(R.string.sp_direct_scan_and_digital_intermediate_bypa))
            }
        }

        SpStage.OUTPUT -> {
            SpSectionTitle(spS(R.string.sp_scan_rendering))
            val (sigma, amount) = s.scanUnsharpMask
            val sharpOn = amount > 0f
            var lastStrength by rememberSaveable { mutableStateOf(1.0) }
            SpFlag(spS(R.string.sp_scan_sharpening), sharpOn, en, help = R.string.sp_help_scan_sharpen) { on ->
                s.scanUnsharpMask = sigma to if (on) (SCANNER_BASE * lastStrength).toFloat() else 0f
            }
            num(spS(R.string.sp_sharpening_strength), if (sharpOn) amount / SCANNER_BASE else lastStrength, 0.0, 2.0, 1.0, step = 0.1, enabled = sharpOn, help = R.string.sp_help_scan_sharpen) {
                lastStrength = it; s.scanUnsharpMask = sigma to (SCANNER_BASE * it).toFloat()
            }
            SpFlag(spS(R.string.sp_paper_glare), s.glareActive && !printBypassed, en && !printBypassed, help = R.string.sp_help_paper_glare) { s.glareActive = it }
            num(spS(R.string.sp_glare_strength), s.glarePercent / GLARE_BASE_PERCENT, 0.0, 4.0, 1.0, step = 0.1, enabled = !printBypassed && s.glareActive, help = R.string.sp_help_glare_strength) {
                s.glarePercent = (GLARE_BASE_PERCENT * it).toFloat()
            }
            // print_render.edr_enabled: the paper's measured edr_tone_map; nothing to expand when
            // the print is bypassed or on the silver paper (no table).
            val edrAvailable = !printBypassed && !paperMono
            SpFlag(
                spS(R.string.sp_highlight_expansion), s.extendedDynamicRange && edrAvailable, en && edrAvailable,
                help = R.string.sp_help_highlight_expansion,
            ) { host.setExtendedDynamicRange(it) }
            // C03: digital colour grade after the scan (ColorGrade / ContrastCurve, existing Android stages).
            SpSectionTitle(spS(R.string.sp_colour_grade))
            SpHelp(spS(R.string.sp_help_grade))
            num(spS(R.string.sp_grade_contrast), s.contrast.toDouble(), -100.0, 100.0, 0.0, step = 1.0, digits = 0, help = R.string.sp_help_grade_contrast) {
                s.contrast = it.toFloat()
                // Contrast is composed into the master tone curve, which only renders while the
                // tone-curve stage is on.
                if (it != 0.0 && !s.toneCurveActive) s.toneCurveActive = true
            }
            num(spS(R.string.sp_grade_saturation), s.saturation.toDouble(), -100.0, 100.0, 0.0, step = 1.0, digits = 0, help = R.string.sp_help_grade_saturation) {
                s.saturation = it.toFloat()
            }
            num(spS(R.string.sp_grade_vibrance), s.vibrance.toDouble(), -100.0, 100.0, 0.0, step = 1.0, digits = 0, help = R.string.sp_help_grade_vibrance) {
                s.vibrance = it.toFloat()
            }
            num(spS(R.string.sp_grade_gamut), s.gamutCompress.toDouble(), 0.0, 100.0, 0.0, step = 1.0, digits = 0, help = R.string.sp_help_grade_gamut) {
                s.gamutCompress = it.toFloat()
            }
        }

        SpStage.GEOMETRY -> {
            SpSectionTitle(spS(R.string.sp_composition))
            with(host) { cropControls() }
        }

        SpStage.FILM_EDGE -> SpFilmEdgeControls(s, en, host::interaction)
    }
}

/** MobileProcessContract.wire: film_format_mm from the chosen side length and photo aspect. */
internal fun applyFilmFormat(s: ParamsState, ui: SpUiKeys, aspect: Float) {
    val a = if (aspect > 0f) maxOf(aspect, 1f / aspect).toDouble() else 1.5
    val long = if (ui.filmSide == "long") ui.sideLengthMM else ui.sideLengthMM * a
    s.filmFormatMm = long.coerceIn(4.0, 200.0).toFloat()
}

@Composable
private fun SpDiffusion(title: String, d: com.argentroom.app.look.DiffusionState, en: Boolean, host: SpEditorHost, help: Int) {
    SpChoice(
        title, if (d.active) d.family else "off",
        listOf("off" to spS(R.string.sp_off), "glimmerglass" to "Glimmerglass", "black_pro_mist" to spS(R.string.sp_black_pro_mist), "pro_mist" to spS(R.string.sp_pro_mist), "cinebloom" to "CineBloom"),
        enabled = en, help = help,
    ) { id -> if (id == "off") d.active = false else { d.family = id; d.active = true } }
    SpRulerRow(
        spS(R.string.sp_diffusion_strength), d.strength.toDouble(), 0.125, 2.0, 0.5, { d.strength = it.toFloat() }, { d.strength = 0.5f },
        host::interaction, step = 0.125, enabled = en && d.active, help = R.string.sp_help_diffusion_strength,
    )
    SpRulerRow(
        spS(R.string.sp_diffusion_spread), d.spatialScale.toDouble(), 0.5, 2.0, 1.0, { d.spatialScale = it.toFloat() }, { d.spatialScale = 1f },
        host::interaction, step = 0.1, enabled = en && d.active, help = R.string.sp_help_diffusion_spread,
    )
}

/** Opening the material list is the panel's job; panels call back through the host. */
internal fun SpEditorHost.openMaterials(paper: Boolean) = (this as? SpMaterialOpener)?.openMaterials(paper) ?: Unit

internal interface SpMaterialOpener {
    fun openMaterials(paper: Boolean)
    /** Where the material browser grows from (the PhoneMaterialModule card). */
    fun materialOrigin(paper: Boolean, origin: SpOrigin) {}
}

internal fun SpEditorHost.materialOrigin(paper: Boolean, origin: SpOrigin) { (this as? SpMaterialOpener)?.materialOrigin(paper, origin) }

