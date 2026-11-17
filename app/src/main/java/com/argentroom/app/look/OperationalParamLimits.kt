/*
 * Argentroom for Android — operational parameter limits. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * The bounds every persisted parameter is validated against on decode. Split out of
 * Presets.kt (#273).
 */
package com.argentroom.app.look

import com.argentroom.app.editor.*
import com.argentroom.engine.ANTIHALATION_MODES
import com.argentroom.engine.AUTO_EXPOSURE_METHODS as ENGINE_AUTO_EXPOSURE_METHODS
import com.argentroom.engine.CAMERA_FILTERS

/**
 * Resource-amplifying parameter domains shared by the editor and every preset/recipe boundary.
 * Keeping these ranges in one place prevents imported JSON from bypassing the UI limits and
 * driving unbounded native image allocation or iteration counts.
 */
internal object OperationalParamLimits {
    val SPECTRAL_GAUSSIAN_BLUR = 0f..20f
    val UPSCALE_FACTOR = 0f..4f
    val UPSCALE_NON_ZERO_FACTOR = 0.5f..4f
    val FILM_FORMAT_MM = 8f..120f
    val GRAIN_PARTICLE_AREA_UM2 = 0.2f..2f
    val GRAIN_PARTICLE_SCALE = 0.1f..5f
    val GRAIN_PARTICLE_SCALE_LAYERS = 0.25f..5f
    val GRAIN_DENSITY_MIN = 0f..0.5f
    val GRAIN_SUBLAYERS = 1..5
    val HALATION_BOUNCES = 1..5
    val SCENE_LATITUDE_KNEE = -8f..8f
    val SCENE_LATITUDE_ROOM = 0f..12f
    val SCENE_LATITUDE_ROLLOFF = 1f..4f
    val SCENE_LATITUDE_MAX_LIFT = 0.25f..12f
    val DATE_STAMP_CORNER = 0..3
    val DATE_STAMP_HEIGHT = 0.01f..0.2f
    val DATE_STAMP_MARGIN = 0f..0.3f
    val DATE_STAMP_EV = -2f..8f
    val DATE_STAMP_COLOR = 0f..1f
    val CONTRAST_MASK_AMOUNT = 0f..3f
    val CONTRAST_MASK_CORE = 0f..3f
    val CONTRAST_MASK_SCALE = 0.002f..0.12f
    val CAMERA_TONE = -100f..100f
    val GRAIN_AMOUNT = 0f..2f
    val HALATION_HEADROOM_EV = 0f..8f
    const val DATE_STAMP_TEXT_MAX = 24
    /** Characters the engine's seven-segment font draws (model/date_stamp.cpp). */
    val DATE_STAMP_TEXT = Regex("[0-9 '\\-.:/]*")

    const val FILM_EDGE_DATE_TEXT_MAX = 48
    val FILM_EDGE_FORMATS = setOf("135", "135_half", "120_645", "120_6x6", "120_6x7",
        "120_6x8", "120_6x9", "135_xpan", "120_6x12", "120_6x17")
    val FILM_EDGE_GATES = setOf("auto", "square", "rounded", "eared", "shouldered", "kicked")
    val FILM_EDGE_AMOUNT = 0f..4f
    val FILM_EDGE_DATE_SIZE = 0.4f..3f
    val FILM_EDGE_DATE_INSET = 0f..30f

    fun validate(state: ParamsState) {
        val edge = state.filmEdge
        require(edge.format in FILM_EDGE_FORMATS && edge.gate in FILM_EDGE_GATES &&
            edge.mode in setOf("strip", "filed") && edge.holes in setOf("black", "white") &&
            edge.carrier in setOf("none", "black", "open")) { "Invalid film edge selector" }
        require(edge.cameraSeed in 1..999 && edge.frameSeed in 1..999999 &&
            edge.frameNumber in 0..99 && edge.fog in FILM_EDGE_AMOUNT && edge.leaks in FILM_EDGE_AMOUNT) {
            "Invalid film edge seed, frame number or exposure"
        }
        require(edge.dateStyle in setOf("lcd", "dots", "data") && edge.dateOrder in setOf("japan", "us") &&
            edge.datePlacement in setOf("frame", "rebate") && edge.dateCorner in setOf("br", "bl", "tr", "tl") &&
            edge.dateText.length <= FILM_EDGE_DATE_TEXT_MAX && edge.dateExposureEv in DATE_STAMP_EV &&
            edge.dateSize in FILM_EDGE_DATE_SIZE && edge.dateInsetX in FILM_EDGE_DATE_INSET &&
            edge.dateInsetY in FILM_EDGE_DATE_INSET) { "Invalid film edge date imprint" }
        require(state.spectralGaussianBlur in SPECTRAL_GAUSSIAN_BLUR) {
            "input.spectralGaussianBlur must be in $SPECTRAL_GAUSSIAN_BLUR"
        }
        require(state.upscaleFactor == 0f || state.upscaleFactor in UPSCALE_NON_ZERO_FACTOR) {
            "input.upscaleFactor must be 0 or in $UPSCALE_NON_ZERO_FACTOR"
        }
        require(state.filmFormatMm in FILM_FORMAT_MM) {
            "camera.filmFormatMm must be in $FILM_FORMAT_MM"
        }
        require(
            state.sceneLatitudeHighlightKnee in SCENE_LATITUDE_KNEE &&
                state.sceneLatitudeShadowKnee in SCENE_LATITUDE_KNEE
        ) { "camera.sceneLatitude knees must be in $SCENE_LATITUDE_KNEE" }
        require(
            state.sceneLatitudeHighlightRoom in SCENE_LATITUDE_ROOM &&
                state.sceneLatitudeShadowRoom in SCENE_LATITUDE_ROOM
        ) { "camera.sceneLatitude rooms must be in $SCENE_LATITUDE_ROOM" }
        require(state.sceneLatitudeRolloff in SCENE_LATITUDE_ROLLOFF) {
            "camera.sceneLatitude.rolloff must be in $SCENE_LATITUDE_ROLLOFF"
        }
        require(state.sceneLatitudeMaxLift in SCENE_LATITUDE_MAX_LIFT) {
            "camera.sceneLatitude.maxLift must be in $SCENE_LATITUDE_MAX_LIFT"
        }
        require(
            state.dateStampText.length <= DATE_STAMP_TEXT_MAX &&
                DATE_STAMP_TEXT.matches(state.dateStampText)
        ) { "camera.dateStamp.text must be at most $DATE_STAMP_TEXT_MAX of 0-9 space ' - . : /" }
        require(state.dateStampCorner in DATE_STAMP_CORNER) {
            "camera.dateStamp.corner must be in $DATE_STAMP_CORNER"
        }
        require(state.dateStampHeight in DATE_STAMP_HEIGHT) {
            "camera.dateStamp.height must be in $DATE_STAMP_HEIGHT"
        }
        require(state.dateStampMargin in DATE_STAMP_MARGIN) {
            "camera.dateStamp.margin must be in $DATE_STAMP_MARGIN"
        }
        require(state.dateStampExposureEv in DATE_STAMP_EV) {
            "camera.dateStamp.exposureEv must be in $DATE_STAMP_EV"
        }
        require(state.dateStampColor.allIn(DATE_STAMP_COLOR)) {
            "camera.dateStamp.color components must be in $DATE_STAMP_COLOR"
        }
        require(state.grainParticleAreaUm2 in GRAIN_PARTICLE_AREA_UM2) {
            "grain.particleAreaUm2 must be in $GRAIN_PARTICLE_AREA_UM2"
        }
        require(state.grainParticleScale.allIn(GRAIN_PARTICLE_SCALE)) {
            "grain.particleScale components must be in $GRAIN_PARTICLE_SCALE"
        }
        require(state.grainParticleScaleLayers.allIn(GRAIN_PARTICLE_SCALE_LAYERS)) {
            "grain.particleScaleLayers components must be in $GRAIN_PARTICLE_SCALE_LAYERS"
        }
        require(state.grainDensityMin.allIn(GRAIN_DENSITY_MIN)) {
            "grain.densityMin components must be in $GRAIN_DENSITY_MIN"
        }
        require(state.grainNSubLayers in GRAIN_SUBLAYERS) {
            "grain.nSubLayers must be in $GRAIN_SUBLAYERS"
        }
        require(state.halNBounces in HALATION_BOUNCES) {
            "halation.nBounces must be in $HALATION_BOUNCES"
        }
        require(state.autoExposureMethod in ENGINE_AUTO_EXPOSURE_METHODS) {
            "camera.autoExposureMethod must be one of $ENGINE_AUTO_EXPOSURE_METHODS"
        }
        require(state.cameraFilter.isEmpty() || state.cameraFilter in CAMERA_FILTERS) {
            "camera.colorFilter must be empty or one of $CAMERA_FILTERS"
        }
        require(
            state.cameraToneHighlights in CAMERA_TONE && state.cameraToneShadows in CAMERA_TONE &&
                state.cameraToneWhites in CAMERA_TONE && state.cameraToneBlacks in CAMERA_TONE
        ) { "camera tone values must be in $CAMERA_TONE" }
        require(state.grainAmount in GRAIN_AMOUNT) { "grain.amount must be in $GRAIN_AMOUNT" }
        require(state.halationHeadroomEv in HALATION_HEADROOM_EV) {
            "halation.headroomEv must be in $HALATION_HEADROOM_EV"
        }
        require(state.antihalationMode in ANTIHALATION_MODES) {
            "halation.antihalationMode must be one of $ANTIHALATION_MODES"
        }
        require(state.contrastMaskHighlights in CONTRAST_MASK_AMOUNT) {
            "contrastMask.highlights must be in $CONTRAST_MASK_AMOUNT"
        }
        require(state.contrastMaskShadows in CONTRAST_MASK_AMOUNT) {
            "contrastMask.shadows must be in $CONTRAST_MASK_AMOUNT"
        }
        require(state.contrastMaskCore in CONTRAST_MASK_CORE) {
            "contrastMask.core must be in $CONTRAST_MASK_CORE"
        }
        require(state.contrastMaskScale in CONTRAST_MASK_SCALE) {
            "contrastMask.scale must be in $CONTRAST_MASK_SCALE"
        }
    }

    private fun Triple<Float, Float, Float>.allIn(range: ClosedFloatingPointRange<Float>): Boolean =
        first in range && second in range && third in range
}
