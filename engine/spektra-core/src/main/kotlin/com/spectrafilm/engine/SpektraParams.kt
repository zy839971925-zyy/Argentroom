/*
 * Spektrafilm for Android — engine parameters.
 * Copyright (C) 2026 Spektrafilm Android contributors. GPLv3.
 *
 * 1:1 mirror of spektrafilm's RuntimePhotoParams tree
 * (src/spektrafilm/runtime/params_schema.py). Defaults match upstream so that, for a
 * given profile pair, SpektraParams() reproduces spektrafilm's out-of-the-box look.
 * The JNI bridge marshals these into the flat `spk_params` C struct.
 */
package com.spectrafilm.engine

/** Output color space, mirrors io.output_color_space + spk_color_space. */
enum class ColorSpace { SRGB, ADOBE_RGB, PROPHOTO, REC2020, ACES2065_1, LINEAR_SRGB }

/**
 * Output gamut compression algorithm (opt-in, default-off). Ordinals MUST match the C++
 * enum class OutputGamutCompress (model/gamut_compression.h) and the int32 the JNI
 * marshaller reads:
 *   LEGACY_CLIP=0 — DEFAULT. The engine's existing final clip in scanning; byte-identical
 *                   to every parity golden.
 *   OFF=1         — oracle "off": output RGB passes through with no compression and no clip.
 *   ACES_RGC=2    — ACES Reference Gamut Compression v1.3 (per-channel knee on the
 *                   achromatic distance) in the linear output space.
 *   OKLCH=3       — perceptual-hue-preserving chroma compression at constant Oklch (L, h):
 *                   a Reinhard knee on C / C_max(L, h), where the per-output-space gamut
 *                   boundary C_max is regenerated in-engine by bisection.
 *   JZAZBZ=5      — the same chroma reduction in JzCzhz (Safdar 2017, Y_w=100 cd/m²):
 *                   hue-stable across the blue/cyan arc where Oklch twists (#201).
 *   CAM16UCS=6    — the same reduction in CIECAM16-UCS (Li 2017; L_A=64, Y_b=20, Average
 *                   surround) — upstream 3bb2c2d's default algorithm, opt-in here (#201).
 *   OKLRAB=4      — the OKLCH reduction with the C_max lookup indexed by Ottosson's rebased
 *                   lightness Lr = f(L) (a monotonic remap of OkLab L toward CIELAB L*), for
 *                   a more perceptually uniform knee across light/dark. Same per-pixel cost.
 * The remaining perceptual algos (jzazbz/cam16ucs, C++ ordinals 5..6) are reserved upstream
 * and not yet ported, so they are intentionally absent here.
 */
enum class OutputGamutCompress { LEGACY_CLIP, OFF, ACES_RGC, OKLCH, OKLRAB, JZAZBZ, CAM16UCS }

/**
 * Input gamut compression algorithm (opt-in, default-off). Ordinals MUST match the C++
 * enum class InputGamutCompress:
 *   OFF=0 — DEFAULT. The Hanatos filming tc_lut is built exactly as before; byte-identical.
 *   XY=1  — ACES-RGC-style radial compression in CIE 1931 chromaticity from the film
 *           reference illuminant toward the visible spectral locus, baked into the tc_lut.
 * The oklch variant (C++ ordinal 2) is reserved/unported and intentionally absent.
 */
enum class InputGamutCompress { OFF, XY }

/** RGB→spectral upsampling method, mirrors settings.rgb_to_raw_method. */
enum class Rgb2Raw { HANATOS2025, MALLETT2019 }

/** White-balance mode for RAW import (GUI 'import raw'). */
enum class WhiteBalance { AS_SHOT, DAYLIGHT, TUNGSTEN, CUSTOM }

data class CameraParams(
    val exposureCompensationEv: Float = 0.0f,
    val autoExposure: Boolean = true,
    val autoExposureMethod: String = "center_weighted",
    val lensBlurUm: Float = 0.0f,
    val filmFormatMm: Float = 35.0f,
    val filterUv: Triple<Float, Float, Float> = Triple(0.0f, 410.0f, 8.0f),
    val filterIr: Triple<Float, Float, Float> = Triple(0.0f, 675.0f, 15.0f),
    val diffusionFilter: DiffusionFilterParams = DiffusionFilterParams(),
    val sceneLatitude: SceneLatitudeParams = SceneLatitudeParams(),
    val dateStamp: DateStampParams = DateStampParams(),
    val filmEdge: FilmEdgeParams = FilmEdgeParams(),
    /**
     * Lens colour (Kodak Wratten) filter, Silverphase `camera.filter` (wire key
     * `camera_filter`): "" = none (default, byte-identical), or one of [CAMERA_FILTERS].
     * Applied to the film sensitivity when the filming tc_lut is built, normalised by
     * the green-sensitive layer's response to the film reference illuminant.
     */
    val colorFilter: String = "",
    /**
     * Silverphase camera tone (`camera.tone_*`, -100..100 each, default 0 = off): a
     * per-pixel scene-linear gain from the pixel's luminance in stops off mid-grey,
     * applied after auto-exposure / scene latitude and before the film is exposed.
     */
    val toneHighlights: Float = 0.0f,
    val toneShadows: Float = 0.0f,
    val toneWhites: Float = 0.0f,
    val toneBlacks: Float = 0.0f,
)

/**
 * Auto-exposure metering ids the native engine knows (runtime/stages/autoexposure.h).
 * The first seven are spektrafilm's; the last four are Silverphase's RFC-015 intents
 * (exact statistics, metered on a 1600 px preview like Silverphase's session meter).
 * Old documents keep their spektrafilm ids, which render exactly as before.
 */
val AUTO_EXPOSURE_METHODS: List<String> = listOf(
    "center_weighted", "average", "median", "partial", "matrix", "multi_zone", "highlight_weighted",
    "balanced", "center", "protect_highlights", "protect_shadows",
)

/** Silverphase camera colour filter ids (native model/camera_filters.h); "" is none. */
val CAMERA_FILTERS: List<String> = listOf("w8", "w11", "w12", "w15", "w21", "w25", "w47", "w58")

/**
 * Opt-in scene latitude mapping (native model/scene_latitude.h). A smooth knee in
 * stops from the metered mid-grey, applied to the scene before the film is exposed:
 * highlights above [highlightKnee] are compressed into at most [highlightRoom] more
 * stops, shadows below [shadowKnee] into at most [shadowRoom]. A room of 0 turns that
 * side off; `active = false` (the default) leaves every render byte-identical.
 * Android-only (not an upstream spektrafilm stage); idea from SpektraLab RFC-023.
 */
data class SceneLatitudeParams(
    val active: Boolean = false,
    val highlightKnee: Float = 1.0f,
    val highlightRoom: Float = 2.5f,
    val shadowKnee: Float = -2.0f,
    val shadowRoom: Float = 0.0f,
    val rolloff: Float = 2.0f,
    val maxLift: Float = 4.0f,
)

/**
 * Opt-in date-back imprint (native model/date_stamp.h): seven-segment LED digits
 * exposed onto the negative in the corner of the framed picture, so they develop,
 * halate and grain with it. Idea from SpektraLab's "Date Back" (RFC-031,
 * GPL-3.0-or-later); independent implementation.
 *
 * [text] accepts digits, space and ' - . : / (anything else is skipped).
 * [corner]: 0 bottom-right, 1 bottom-left, 2 top-right, 3 top-left. [height] and
 * [margin] are fractions of the frame's short edge. [exposureEv] is the LED's
 * brightness in stops above mid-grey; [color] is linear ProPhoto RGB.
 */
data class DateStampParams(
    val active: Boolean = false,
    val text: String = "",
    val corner: Int = 0,
    val height: Float = 0.045f,
    val margin: Float = 0.05f,
    val exposureEv: Float = 2.5f,
    val color: Triple<Float, Float, Float> = Triple(1.0f, 0.18f, 0.0f),
)

/** Film-space controls. Capture metadata is resolved per source before rendering. */
data class FilmEdgeParams(
    val active: Boolean = false,
    val format: String = "135",
    val mode: String = "strip",
    val gate: String = "auto",
    val holes: String = "white",
    val carrier: String = "none",
    val cameraSeed: Int = 1,
    val frameSeed: Int = 1,
    val frameNumber: Int = 0,
    val fog: Float = 1f,
    val leaks: Float = 0f,
    val dateActive: Boolean = false,
    val dateText: String = "",
    val dateStyle: String = "lcd",
    val dateExposureEv: Float = 3.5f,
    val dateSize: Float = 1f,
    val dateOrder: String = "japan",
    val dateWithData: Boolean = false,
    val datePlacement: String = "frame",
    val dateCorner: String = "br",
    val dateInsetX: Float = 3f,
    val dateInsetY: Float = 2.4f,
    val edgeText: String = "",
    val captureDate: String = "",
    val captureData: String = "",
    val aperture: Float = 0f,
) {
    val renderDateText: String
        get() = dateText.ifBlank { if (dateStyle == "data") captureData else captureDate }
    val renderDataText: String
        get() = if (active && dateWithData && dateStyle != "data") captureData else ""
    val spatial: Boolean
        get() = active || dateActive
}

data class EnlargerParams(
    /** Native currently has the TH-KG3 SPD; unsupported IDs fail closed. */
    val illuminant: String = "TH-KG3",
    val printExposure: Float = 1.0f,
    val printExposureCompensation: Boolean = true,
    val normalizePrintExposure: Boolean = true,
    val yFilterShift: Float = 0.0f,
    val mFilterShift: Float = 0.0f,
    val yFilterNeutral: Float = 55f, // kodak cc values
    val mFilterNeutral: Float = 65f,
    val cFilterNeutral: Float = 0f,
    val lensBlur: Float = 0.0f,
    val diffusionFilter: DiffusionFilterParams = DiffusionFilterParams(),
    val preflashExposure: Float = 0.0f,
    val preflashYFilterShift: Float = 0.0f,
    val preflashMFilterShift: Float = 0.0f,
)

data class ScannerParams(
    val lensBlur: Float = 0.0f,
    val whiteCorrection: Boolean = false,
    val blackCorrection: Boolean = false,
    val whiteLevel: Float = 0.98f,
    val blackLevel: Float = 0.01f,
    val unsharpMask: Pair<Float, Float> = 0.7f to 0.7f,
)

data class GrainParams(
    val active: Boolean = true,
    val sublayersActive: Boolean = true,
    val agxParticleAreaUm2: Float = 0.2f,
    // Silverphase v1.2.5 core/params.hpp GrainParams (1.0.1; were spektrafilm c1d0e44's).
    val agxParticleScale: Triple<Float, Float, Float> = Triple(1.6f, 1.6f, 3.2f),
    val agxParticleScaleLayers: Triple<Float, Float, Float> = Triple(2.0f, 1.0f, 0.5f),
    val densityMin: Triple<Float, Float, Float> = Triple(0.03f, 0.03f, 0.03f),
    val uniformity: Triple<Float, Float, Float> = Triple(0.97f, 0.99f, 0.97f),
    val blur: Float = 0.65f,
    val blurDyeCloudsUm: Float = 1.0f,
    val microStructure: Pair<Float, Float> = 0.2f to 30f,
    val nSubLayers: Int = 1,
    /**
     * Silverphase grain strength (`film_render.grain.amount`, 0..2): the grained density
     * mixed over the ungrained one, (1 - a) * in + a * grained. 1 (default) is the model
     * as is (byte-identical); <= 0 skips grain.
     */
    val amount: Float = 1.0f,
)

data class HalationParams(
    val active: Boolean = true,
    val scatterAmount: Float = 1.0f,
    val scatterSpatialScale: Float = 1.0f,
    val halationAmount: Float = 1.0f,
    val halationSpatialScale: Float = 1.0f,
    val scatterCoreUm: Triple<Float, Float, Float> = Triple(2.2f, 2.0f, 1.6f),
    val scatterTailUm: Triple<Float, Float, Float> = Triple(9.3f, 9.7f, 9.1f),
    val scatterTailWeight: Triple<Float, Float, Float> = Triple(0.78f, 0.65f, 0.67f),
    val boostEv: Float = 0.0f,
    val boostRange: Float = 0.3f,
    val protectEv: Float = 4.0f,
    val halationStrength: Triple<Float, Float, Float> = Triple(0.05f, 0.015f, 0.0f),
    val halationFirstSigmaUm: Triple<Float, Float, Float> = Triple(65.0f, 65.0f, 65.0f),
    val halationNBounces: Int = 3,
    val halationBounceDecay: Float = 0.5f,
    val halationRenormalize: Boolean = true,
    /**
     * Silverphase CineStill highlight headroom (`film_render.halation.headroom_ev`, 0..8,
     * default 0 here so the default render is a no-op; Silverphase ships 4 and the UI raises it): the stops a digital frame clipped off its brightest highlights, given
     * back before halation. Only a film with no anti-halation layer (profile
     * antihalation "no", e.g. cinestill_800t) with halation active is affected.
     */
    val headroomEv: Float = 0.0f,
    /**
     * Silverphase `film_render.halation.antihalation_mode` (wire key `antihalation_mode`):
     * "profile" (default) = the film profile's own anti-halation tag, byte-identical to
     * before; "strong" / "weak" = that preset's halation strengths; "none" = no
     * anti-halation layer (0.30 / 0.10 / 0.015). Only "profile" on a film whose own tag
     * is "no" (cinestill_800t) engages the CineStill highlight headroom. See
     * [ANTIHALATION_MODES]; an unknown id reads as "profile".
     */
    val antihalationMode: String = "profile",
)

/** Silverphase antihalation_mode ids (native spektra.h antihalation_mode). */
val ANTIHALATION_MODES: List<String> = listOf("profile", "strong", "weak", "none")

data class DirCouplersParams(
    val active: Boolean = true,
    val amount: Float = 1.0f,
    val inhibitionSamelayer: Float = 1.0f,
    val inhibitionInterlayer: Float = 1.0f,
    val gammaSamelayerRgb: Triple<Float, Float, Float> = Triple(0.341f, 0.324f, 0.273f),
    val gammaInterlayerRToGb: Pair<Float, Float> = 0.355f to 0.305f,
    val gammaInterlayerGToRb: Pair<Float, Float> = 0.154f to 0.358f,
    val gammaInterlayerBToRg: Pair<Float, Float> = 0.171f to 0.225f,
    val diffusionSizeUm: Float = 20.0f,
    val diffusionTailUm: Float = 200.0f,
    val diffusionTailWeight: Float = 0.06f,
)

data class GlareParams(
    val active: Boolean = true,
    val percent: Float = 0.03f,
    val roughness: Float = 0.7f,
    val blur: Float = 0.5f,
)

data class DiffusionFilterParams(
    val active: Boolean = false,
    /** glimmerglass, black_pro_mist, pro_mist, or cinebloom. */
    val filterFamily: String = "black_pro_mist",
    val strength: Float = 0.5f,
    val spatialScale: Float = 1.0f,
    val haloWarmth: Float = 0.0f,
    val coreIntensity: Float = 1.0f,
    val coreSize: Float = 1.0f,
    val haloIntensity: Float = 1.0f,
    val haloSize: Float = 1.0f,
    val bloomIntensity: Float = 1.0f,
    val bloomSize: Float = 1.0f,
)

data class FilmRenderingParams(
    val densityCurveGamma: Float = 1.0f,
    val grain: GrainParams = GrainParams(),
    val halation: HalationParams = HalationParams(),
    val dirCouplers: DirCouplersParams = DirCouplersParams(),
    val glare: GlareParams = GlareParams(),
)

// OPT-IN s023 print density-curve morph (print_render.density_curves_morph).
// `active` defaults false -> the engine uses the stored print density_curves table
// (byte-identical to the parity goldens). When active, the print density table is
// rebuilt from the paper's density_curves_model and morphed by a coupled gamma
// (global x band(fast/slow) x RGB) plus a developer-exhaustion Gumbel blend.
data class PrintCurvesMorphParams(
    val active: Boolean = false,
    val gammaFactor: Float = 1.0f,
    val gammaFactorFast: Float = 1.0f,
    val gammaFactorSlow: Float = 1.0f,
    val gammaFactorRed: Float = 1.0f,
    val gammaFactorGreen: Float = 1.0f,
    val gammaFactorBlue: Float = 1.0f,
    val developerExhaustion: Float = 0.0f,
)

/**
 * OPT-IN virtual contrast mask at the print (Android-only, not an upstream
 * spektrafilm stage). An unsharp mask of the paper exposure, like a darkroom
 * contrast mask sandwiched with the negative: big bright and dark areas move
 * toward mid-grey while edges and texture keep their contrast. Print route only.
 * Idea from SpektraLab RFC-024 (GPL-3.0-or-later); independent implementation.
 *
 * [highlights] / [shadows]: stops a print highlight / shadow far from mid-grey
 * is pulled in (0..3). [core]: stops around mid-grey left alone (0..3).
 * [scale]: blur radius as a fraction of the frame's long edge (0.002..0.12).
 */
data class ContrastMaskParams(
    val active: Boolean = false,
    val highlights: Float = 1.0f,
    val shadows: Float = 0.5f,
    val core: Float = 0.25f,
    val scale: Float = 0.03f,
)

data class PrintRenderingParams(
    val densityCurveGamma: Float = 1.0f,
    val glare: GlareParams = GlareParams(),
    val densityCurvesMorph: PrintCurvesMorphParams = PrintCurvesMorphParams(),
    val contrastMask: ContrastMaskParams = ContrastMaskParams(),
    /**
     * Silverphase "Highlight Expansion" (`print_render.edr_enabled`, wire key
     * `extended_dynamic_range`): the print paper's measured edr_tone_map (native print
     * luminance -> expanded luminance, identity between the paper's toe and shoulder,
     * output stays SDR <= 1) applied to the linear print scan right before the output
     * encode. Print route only; a paper with no table (ilford_multigrade_iv_rc) and the
     * direct-scan / Digital Intermediate routes are unchanged. Default false.
     */
    val extendedDynamicRange: Boolean = false,
)

data class IoParams(
    /** Native currently accepts ProPhoto RGB (linear) and rejects other spaces. */
    val inputColorSpace: String = "ProPhoto RGB",
    val inputCctfDecoding: Boolean = false,
    val outputColorSpace: ColorSpace = ColorSpace.SRGB,
    val outputCctfEncoding: Boolean = true,
    /** Opt-in output gamut compression (default LEGACY_CLIP => byte-identical render). */
    val outputGamutCompress: OutputGamutCompress = OutputGamutCompress.LEGACY_CLIP,
    /** Opt-in input (filming-side) gamut compression (default OFF => byte-identical). */
    val inputGamutCompress: InputGamutCompress = InputGamutCompress.OFF,
    val crop: Boolean = false,
    val cropCenter: Pair<Float, Float> = 0.5f to 0.5f,
    val cropSize: Pair<Float, Float> = 0.1f to 0.1f,
    val upscaleFactor: Float = 1.0f,
    /** scan negative directly, skipping the print stage (RuntimePhotoParams.io.scan_film). */
    val scanFilm: Boolean = false,
    /**
     * Silverphase Digital Intermediate (`io.digital_intermediate`, RFC-028): the
     * developed negative is read in printing density (the film's own target paper, else
     * the print profile), base removed, reversed on the film's neutral curve, mapped to
     * ProPhoto RGB and written as Cineon log, bypassing the print (enlarger, paper, glare,
     * scanner sharpening, output gamut compression, Highlight Expansion and scene latitude
     * are all off on this route). The render shows it through Silverphase's "DI view"
     * (Cineon -> ProPhoto RGB), encoded in [outputColorSpace]. scanFilm wins over it and
     * a positive film ignores it. Default false.
     */
    val digitalIntermediate: Boolean = false,
    /**
     * With [digitalIntermediate]: output the Cineon log codes themselves (0..1, ProPhoto
     * primaries, code 95/1023 = film base, 685/1023 = reference white) instead of the DI
     * view -- the DI master file a colourist grades. Not a look: for a DI export only.
     */
    val digitalIntermediateMaster: Boolean = false,
    /**
     * With [digitalIntermediate]: Silverphase's optional blue-sector compensation
     * (`io.digital_intermediate_blue_compensation`; the iOS preference
     * "diBlueCompensation"): one Oklab hue rotation and chroma scale, fitted per film on
     * synthetic blues through its own chain, applied inside the blue window (hue
     * 280 +/- 55 deg) and faded out below chroma 0.02..0.08 so greys stay exact.
     * Default false.
     */
    val digitalIntermediateBlueCompensation: Boolean = false,
)

data class SettingsParams(
    // The former useFastStats and previewMode knobs are intentionally absent:
    // native has no alternate stats implementation, and preview is selected by
    // SpektraEngine's explicit preview entry point. A repository-wide source
    // search found no callers, so removing them avoids exposing inert controls.
    val rgbToRawMethod: Rgb2Raw = Rgb2Raw.HANATOS2025,
    val applyHanatos2025AdaptationWindow: Boolean = true,
    val applyHanatos2025AdaptationSurface: Boolean = false,
    val spectralGaussianBlur: Float = 0.0f,
    val useEnlargerLut: Boolean = false,
    val useScannerLut: Boolean = false,
    val lutResolution: Int = 17,
    val previewMaxSize: Int = 640,
    val neutralPrintFiltersFromDatabase: Boolean = true,
    /**
     * GPU preview fast-path (GPU M1, #146). When true, interactive preview
     * renders (`simulate_preview` only) run the scan stage's spectral integral
     * on the GPU via Vulkan compute, after a one-time on-device self-check
     * against the CPU engine; any failure falls back to the CPU per frame.
     * Default false.
     */
    val gpuPreview: Boolean = false,
    /** Enable the numerically gated Vulkan stages, with CPU fallback. */
    val gpuExport: Boolean = false,
    /** Opt in to a faster, different grain and viewing-glare noise realization. */
    val fastExportNoise: Boolean = false,
)

/** Max control points per tone-curve channel; mirrors SPK_TONE_MAX_PTS in spektra.h. */
const val TONE_CURVE_MAX_POINTS = 16

/**
 * One tone-curve channel: control points (x, y in [0,1], x strictly increasing).
 * Fewer than 2 points is an identity (no-op) channel.
 */
data class ToneCurveChannel(val points: List<Pair<Float, Float>> = emptyList())

/**
 * Lightroom-style point tone curve on the final display-referred RGB. [active] gates
 * the whole stage (default off => the engine skips it and stays bit-exact). [master]
 * applies to all channels; [red]/[green]/[blue] apply per channel first.
 */
data class ToneCurveParams(
    val active: Boolean = false,
    val master: ToneCurveChannel = ToneCurveChannel(),
    val red: ToneCurveChannel = ToneCurveChannel(),
    val green: ToneCurveChannel = ToneCurveChannel(),
    val blue: ToneCurveChannel = ToneCurveChannel(),
)

/**
 * Top-level engine parameters, mirroring RuntimePhotoParams. `filmProfile` / `printProfile`
 * are profile ids resolved from bundled assets (see docs/ASSETS.md).
 */
data class SpektraParams(
    val filmProfile: String,
    val printProfile: String,
    val filmRender: FilmRenderingParams = FilmRenderingParams(),
    val printRender: PrintRenderingParams = PrintRenderingParams(),
    val camera: CameraParams = CameraParams(),
    val enlarger: EnlargerParams = EnlargerParams(),
    val scanner: ScannerParams = ScannerParams(),
    val io: IoParams = IoParams(),
    val settings: SettingsParams = SettingsParams(),
    val toneCurve: ToneCurveParams = ToneCurveParams(),
) {
    /**
     * Pack the tone curve into a flat float[] for the JNI bridge:
     *   [active, masterN, (x,y)*masterN, redN, (x,y)*redN, greenN, ..., blueN, ...]
     * Each channel is capped at [TONE_CURVE_MAX_POINTS]. Read by spektra_jni's
     * read_tone_curve(). Kept as one array so marshalling is a single JNI call.
     */
    fun toneCurvePacked(): FloatArray {
        val out = ArrayList<Float>(8)
        out.add(if (toneCurve.active) 1f else 0f)
        for (ch in listOf(toneCurve.master, toneCurve.red, toneCurve.green, toneCurve.blue)) {
            val pts = ch.points.take(TONE_CURVE_MAX_POINTS)
            out.add(pts.size.toFloat())
            for ((x, y) in pts) { out.add(x); out.add(y) }
        }
        return out.toFloatArray()
    }
}
