/*
 * Argentroom for Android — preset persistence. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * Presets are SpektraParams snapshots serialized to JSON with android's built-in
 * org.json (no kotlinx.serialization, so the build graph is unchanged). Saved
 * presets live in filesDir/presets/<name>.json. Import reads any .json Uri; export
 * writes the current preset to a CreateDocument/share Uri. Every SpektraParams field
 * is round-tripped.
 */
package com.argentroom.app.look

import com.argentroom.app.*
import com.argentroom.app.editor.*
import com.argentroom.app.export.*
import com.argentroom.app.settings.*
import android.content.Context
import android.net.Uri
import com.argentroom.app.masks.MaskJson
import com.argentroom.app.masks.isSemanticOnly
import com.argentroom.engine.ANTIHALATION_MODES
import com.argentroom.engine.AUTO_EXPOSURE_METHODS as ENGINE_AUTO_EXPOSURE_METHODS
import com.argentroom.engine.CAMERA_FILTERS
import com.argentroom.engine.ColorSpace
import com.argentroom.engine.InputGamutCompress
import com.argentroom.engine.OutputGamutCompress
import com.argentroom.engine.Rgb2Raw
import com.argentroom.libraw.WhiteBalance
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.math.BigDecimal
import java.math.BigInteger
import java.nio.charset.CharacterCodingException

internal const val PRESET_VERSION = 2

/** Silverphase v1.2.5 `film_render.halation.headroom_ev` for stills (params.hpp, SmokeChecks). */
internal const val SILVERPHASE_HEADROOM_EV = 4f

/** Written beside `halation.headroomEv` from 1.0.1 on; see Presets.applyJson. */
internal const val HEADROOM_REV = 1
internal const val PRESET_SCHEMA_ID = "org.argentroom.preset"

internal class UnsupportedPresetDocumentException(message: String) :
    IllegalArgumentException(message)

object Presets {

    private val INT_MIN_VALUE = BigInteger.valueOf(Int.MIN_VALUE.toLong())
    private val INT_MAX_VALUE = BigInteger.valueOf(Int.MAX_VALUE.toLong())

    private fun dir(ctx: Context): File =
        File(ctx.filesDir, "presets").apply { mkdirs() }

    /** Names (without .json) of saved presets, alphabetically. */
    fun list(ctx: Context): List<String> =
        dir(ctx).listFiles { f -> f.extension == "json" }
            ?.map { it.nameWithoutExtension }?.sorted() ?: emptyList()

    fun save(ctx: Context, name: String, state: ParamsState) =
        saveJson(ctx, name, toJsonString(state))

    /**
     * Persist a PRE-SERIALIZED preset [json] (from [toJsonString]) under [name].
     * [toJsonString] reads live Compose [ParamsState] field-by-field, so it must run on the
     * main thread; passing the string here lets the caller serialize on main and cross only
     * the file write to IO — avoiding a torn (partially-updated) off-thread snapshot.
     */
    fun saveJson(ctx: Context, name: String, json: String) {
        val normalized = parsePersistentJson(json).toString(2)
        AtomicJsonStore.writeUtf8(file(ctx, name), normalized, AtomicJsonStore.MAX_PRESET_BYTES)
    }

    fun load(ctx: Context, name: String, into: ParamsState) {
        fromJson(readPersistentJson(ctx, name), into)
    }

    fun delete(ctx: Context, name: String) {
        AtomicJsonStore.delete(file(ctx, name))
    }

    /** Import a preset JSON from a SAF [uri] into [into]. */
    fun import(ctx: Context, uri: Uri, into: ParamsState) {
        fromJson(parseImportJson(readUriText(ctx, uri)), into)
    }

    /**
     * Read a saved preset's raw JSON text (the IO half of [load]). Lets a caller do the
     * file read off the main thread and then [decode] the result on the main thread, so
     * the Compose-state write stays on main. Throws if the file is missing/unreadable.
     */
    fun read(ctx: Context, name: String): String = readPersistentJson(ctx, name).toString(2)

    /** Read a SAF preset's raw JSON text (the IO half of [import]); decode on main. */
    fun readUri(ctx: Context, uri: Uri): String = parseImportJson(readUriText(ctx, uri)).toString(2)

    /** Export the current [state] to a SAF [uri]. */
    fun export(ctx: Context, uri: Uri, state: ParamsState) {
        exportJson(ctx, uri, toJsonString(state))
    }

    /**
     * Export a PRE-SERIALIZED preset [json] (from [toJsonString]) to a SAF [uri] — the IO
     * half of [export]. Serialize on the main thread, cross only the write to IO (same
     * torn-snapshot rationale as [saveJson]).
     */
    fun exportJson(ctx: Context, uri: Uri, json: String) {
        val normalized = parsePersistentJson(json).toString(2).toByteArray(Charsets.UTF_8)
        writeVerifiedNewDocument(ctx, uri, normalized, AtomicJsonStore.MAX_PRESET_BYTES)
    }

    fun toJsonString(state: ParamsState): String {
        OperationalParamLimits.validate(state)
        return toJson(state).toString(2)
    }

    /**
     * Reusable serialization hooks so the recipe (sidecar) layer shares this exact
     * JSON schema instead of forking a parallel one. [encode] mirrors [toJson];
     * [decode] mirrors [fromJson]. The recipe layer wraps the result with its own
     * envelope (source key + metadata) but the params payload is byte-for-byte the
     * same format as a saved preset.
     */
    internal fun encode(state: ParamsState): JSONObject {
        OperationalParamLimits.validate(state)
        return toJson(state)
    }

    internal fun decode(o: JSONObject, into: ParamsState) = fromJson(o, into)

    /**
     * Apply a preset's LOOK to [into], leaving the photo's own framing alone.
     *
     * A user preset is a complete document encode — that is what stops it compounding the way
     * the sparse built-ins do. But a complete encode also carries input.crop/cropCenter/cropSize
     * and the masks block, and those belong to ONE photo: a crop saved off a landscape
     * re-frames the portrait you apply it to, and mask coordinates are normalized, so they
     * land somewhere unrelated. Applying a look should never move the frame.
     *
     * Implemented by dropping those keys rather than by restoring them afterwards, because
     * every field in [fromJson] already defaults to the live value when its key is absent —
     * so absence IS preserve, with no second list of fields to keep in sync.
     *
     * [decode] itself is deliberately unchanged: undo/redo and session restore go through it
     * and must bring framing back exactly.
     */
    internal fun decodeLook(o: JSONObject, into: ParamsState) {
        val look = JSONObject(o.toString())
        look.remove("masks")
        look.optJSONObject("input")?.apply {
            remove("crop")
            remove("cropCenter")
            remove("cropSize")
            remove("straighten")
        }
        fromJson(look, into)
    }

    /**
     * The look's skin (AI) masks: [decodeLook] drops masks because a radial or gradient belongs to
     * the photo it was placed on, but a mask made only of semantic parts stores intent and means
     * the same on every photo, so a batch or the second half-frame carries it over.
     */
    internal fun decodeSkinMasks(o: JSONObject): List<com.argentroom.app.masks.LocalAdjustment> {
        val scratch = ParamsState()
        fromJson(JSONObject(o.toString()), scratch)
        return scratch.localAdjustments.filter { it.mask.isSemanticOnly }
    }

    /** Strict import boundary: bounded caller reads this, rejects future/foreign schemas, then commits. */
    internal fun parseImportJson(text: String): JSONObject {
        val root = AtomicJsonStore.parseObject(text)
        val exactVersion = root.exactInteger("version", BigInteger.ONE)
        val currentVersion = BigInteger.valueOf(PRESET_VERSION.toLong())
        if (exactVersion < BigInteger.ONE || exactVersion > currentVersion) {
            throw UnsupportedPresetDocumentException("unsupported preset version: $exactVersion")
        }
        val version = exactVersion.toInt()
        val schema = root.opt("schema")
        if (schema !== null && schema !== JSONObject.NULL && schema != PRESET_SCHEMA_ID) {
            throw UnsupportedPresetDocumentException("unsupported preset schema: $schema")
        }
        if (version >= 2 && schema != PRESET_SCHEMA_ID) {
            throw UnsupportedPresetDocumentException("preset schema is required for version $version")
        }
        return validateCurrent(migrate(root))
    }

    private fun parsePersistentJson(text: String): JSONObject = parseImportJson(text)

    private fun readPersistentJson(ctx: Context, name: String): JSONObject {
        val target = file(ctx, name)
        return AtomicJsonStore.withPathLock(target) {
            val text = try {
                AtomicJsonStore.readUtf8(target, AtomicJsonStore.MAX_PRESET_BYTES)
            } catch (failure: Throwable) {
                // A bounded-size or malformed-UTF-8 failure proves corrupt content. Other I/O
                // failures may be transient and must not move the user's last good document.
                if (target.isFile &&
                    (failure is DocumentLimitException || failure is CharacterCodingException)
                ) {
                    runCatching { AtomicJsonStore.quarantine(target) }
                        .exceptionOrNull()
                        ?.let(failure::addSuppressed)
                }
                throw failure
            }
            try {
                parsePersistentJson(text)
            } catch (failure: UnsupportedPresetDocumentException) {
                // A newer/foreign document is not corrupt. Keep it byte-for-byte so a newer
                // app can still open it; callers surface the unsupported-version state.
                throw failure
            } catch (failure: Exception) {
                runCatching { AtomicJsonStore.quarantine(target) }
                    .exceptionOrNull()
                    ?.let(failure::addSuppressed)
                throw failure
            }
        }
    }

    private fun readUriText(ctx: Context, uri: Uri): String {
        require(uri.scheme == "content") { "preset source must be content://" }
        return ctx.contentResolver.openInputStream(uri)?.use {
            AtomicJsonStore.readUtf8(it, AtomicJsonStore.MAX_PRESET_BYTES)
        } ?: error("Could not open preset")
    }

    private fun file(ctx: Context, name: String): File = File(dir(ctx), "${safeName(name)}.json")

    /**
     * The name a save under [name] will ACTUALLY use on disk. [safeName] rewrites anything
     * outside `A-Za-z0-9_- ` to an underscore and truncates to 96 characters, so "Portra #2"
     * becomes "Portra _2" — which the UI used to hide, reporting the typed name back to the
     * user while storing something else.
     */
    fun resolveName(name: String): String = safeName(name)

    /** True if a preset already exists under the name [name] resolves to. */
    fun exists(ctx: Context, name: String): Boolean = file(ctx, name).isFile

    private fun safeName(name: String): String = name.trim()
        .take(96)
        .ifEmpty { "preset" }
        .replace(Regex("[^A-Za-z0-9_\\- ]"), "_")

    // --- JSON (de)serialization ---

    private fun JSONObject.tri(key: String, t: Triple<Float, Float, Float>): JSONObject =
        put(key, JSONArray().put(t.first.toDouble()).put(t.second.toDouble()).put(t.third.toDouble()))

    private fun JSONObject.pair(key: String, p: Pair<Float, Float>): JSONObject =
        put(key, JSONArray().put(p.first.toDouble()).put(p.second.toDouble()))

    private fun JSONObject.triOf(key: String, def: Triple<Float, Float, Float>): Triple<Float, Float, Float> {
        if (!has(key)) return def
        val a = opt(key)
        require(a is JSONArray) { "$key must be a JSON array" }
        require(a.length() == 3) { "$key must contain exactly 3 numbers" }
        return Triple(
            a.finiteFloat(0, key),
            a.finiteFloat(1, key),
            a.finiteFloat(2, key),
        )
    }

    private fun JSONObject.pairOf(key: String, def: Pair<Float, Float>): Pair<Float, Float> {
        if (!has(key)) return def
        val a = opt(key)
        require(a is JSONArray) { "$key must be a JSON array" }
        require(a.length() == 2) { "$key must contain exactly 2 numbers" }
        return a.finiteFloat(0, key) to a.finiteFloat(1, key)
    }

    private fun JSONObject.f(key: String, def: Float): Float {
        if (!has(key)) return def
        return finiteDouble(key).toFloat().also {
            require(it.isFinite()) { "$key is outside the finite Float range" }
        }
    }

    private fun JSONObject.i(key: String, def: Int): Int {
        if (!has(key)) return def
        val value = exactInteger(key, BigInteger.valueOf(def.toLong()))
        require(value >= INT_MIN_VALUE && value <= INT_MAX_VALUE) { "$key is outside the Int range" }
        return value.toInt()
    }

    private fun JSONObject.exactInteger(key: String, def: BigInteger): BigInteger {
        if (!has(key)) return def
        val raw = opt(key)
        return try {
            when (raw) {
                is Byte, is Short, is Int, is Long -> BigInteger.valueOf((raw as Number).toLong())
                is BigInteger -> raw
                is BigDecimal -> raw.toBigIntegerExact()
                is Float -> {
                    require(raw.isFinite()) { "$key must be finite" }
                    BigDecimal.valueOf(raw.toDouble()).toBigIntegerExact()
                }
                is Double -> {
                    require(raw.isFinite()) { "$key must be finite" }
                    BigDecimal.valueOf(raw).toBigIntegerExact()
                }
                else -> throw IllegalArgumentException("$key must be a JSON integer")
            }
        } catch (failure: ArithmeticException) {
            throw IllegalArgumentException("$key must be an exact integer", failure)
        }
    }

    private fun JSONObject.finiteDouble(key: String): Double {
        val raw = opt(key)
        require(raw is Number) { "$key must be a JSON number" }
        return raw.toDouble().also { require(it.isFinite()) { "$key must be finite" } }
    }

    private fun JSONArray.finiteFloat(index: Int, field: String): Float {
        val raw = opt(index)
        require(raw is Number) { "$field[$index] must be a JSON number" }
        val value = raw.toDouble()
        require(value.isFinite()) { "$field[$index] must be finite" }
        return value.toFloat().also {
            require(it.isFinite()) { "$field[$index] is outside the finite Float range" }
        }
    }

    private fun pointsToJson(pts: List<Pair<Float, Float>>): JSONArray {
        val arr = JSONArray()
        for ((x, y) in pts) arr.put(JSONArray().put(x.toDouble()).put(y.toDouble()))
        return arr
    }

    private fun JSONObject.pointsOf(key: String): List<Pair<Float, Float>> {
        if (!has(key)) return emptyList()
        val arr = opt(key)
        require(arr is JSONArray) { "$key must be a JSON array" }
        val out = ArrayList<Pair<Float, Float>>(arr.length())
        for (i in 0 until arr.length()) {
            val p = arr.opt(i)
            require(p is JSONArray) { "$key[$i] must be a JSON array" }
            require(p.length() == 2) { "$key[$i] must contain exactly 2 numbers" }
            out.add(p.finiteFloat(0, "$key[$i]") to p.finiteFloat(1, "$key[$i]"))
        }
        return out
    }

    private fun diffJson(d: DiffusionState) = JSONObject()
        .put("active", d.active).put("family", d.family)
        .put("strength", d.strength.toDouble()).put("spatialScale", d.spatialScale.toDouble())
        .put("haloWarmth", d.haloWarmth.toDouble()).put("coreIntensity", d.coreIntensity.toDouble())
        .put("coreSize", d.coreSize.toDouble()).put("haloIntensity", d.haloIntensity.toDouble())
        .put("haloSize", d.haloSize.toDouble()).put("bloomIntensity", d.bloomIntensity.toDouble())
        .put("bloomSize", d.bloomSize.toDouble())

    private fun readDiff(o: JSONObject, d: DiffusionState) {
        d.active = o.optBoolean("active", d.active)
        d.family = o.optString("family", d.family)
        d.strength = o.f("strength", d.strength); d.spatialScale = o.f("spatialScale", d.spatialScale)
        d.haloWarmth = o.f("haloWarmth", d.haloWarmth); d.coreIntensity = o.f("coreIntensity", d.coreIntensity)
        d.coreSize = o.f("coreSize", d.coreSize); d.haloIntensity = o.f("haloIntensity", d.haloIntensity)
        d.haloSize = o.f("haloSize", d.haloSize); d.bloomIntensity = o.f("bloomIntensity", d.bloomIntensity)
        d.bloomSize = o.f("bloomSize", d.bloomSize)
    }

    /**
     * 1.0.1: builds up to C05 shipped argentroom c1d0e44's grain (particle scale 0.8/1/2,
     * layers 2.5/1/0.5, density_min 0.07/0.08/0.12, uniformity 0.97/0.97/0.99) and saved all
     * four into every recipe. Those particles are half the size of Silverphase v1.2.5's
     * (params.hpp GrainParams 1.6/1.6/3.2, 2/1/0.5, 0.03, 0.97/0.99/0.97), so every edit begun
     * before C06 rendered grain far finer than Silverphase (host check, Portra 400 at 4.3 um/px:
     * red RMS 0.052 vs 0.067). A recipe carrying exactly that legacy set takes Silverphase's;
     * any other value is the user's own and is kept.
     */
    private fun migrateLegacyGrain(s: ParamsState) {
        val legacy = s.grainParticleScale == Triple(0.8f, 1f, 2f) &&
            s.grainParticleScaleLayers == Triple(2.5f, 1f, 0.5f) &&
            s.grainDensityMin == Triple(0.07f, 0.08f, 0.12f) &&
            s.grainUniformity == Triple(0.97f, 0.97f, 0.99f)
        if (!legacy) return
        val d = GrainUiParams()
        s.grainParticleScale = d.grainParticleScale
        s.grainParticleScaleLayers = d.grainParticleScaleLayers
        s.grainDensityMin = d.grainDensityMin
        s.grainUniformity = d.grainUniformity
    }

    private fun toJson(s: ParamsState): JSONObject = JSONObject().apply {
        put("schema", PRESET_SCHEMA_ID)
        put("version", PRESET_VERSION)
        put("filmProfile", s.filmProfile)
        put("printProfile", s.printProfile)

        put("input", JSONObject().apply {
            put("inputColorSpace", s.inputColorSpace)
            put("inputCctfDecoding", s.inputCctfDecoding)
            put("spectralUpsampling", s.spectralUpsampling.name)
            put("adaptationWindow", s.adaptationWindow)
            put("adaptationSurface", s.adaptationSurface)
            put("spectralGaussianBlur", s.spectralGaussianBlur.toDouble())
            tri("filterUv", s.filterUv)
            tri("filterIr", s.filterIr)
            put("upscaleFactor", s.upscaleFactor.toDouble())
            put("crop", s.crop)
            pair("cropCenter", s.cropCenter)
            pair("cropSize", s.cropSize)
            put("straighten", s.straightenDegrees.toDouble())
        })

        put("raw", JSONObject().apply {
            put("whiteBalance", s.rawWhiteBalance.name)
            put("temperature", s.rawTemperature.toDouble())
            put("tint", s.rawTint.toDouble())
            put("hotPixels", s.rawHotPixels)
            put("chromaNoise", s.rawChromaNoise.toDouble())
        })

        put("creativeWb", JSONObject().apply {
            put("temp", s.creativeWbTemp.toDouble())
            put("tint", s.creativeWbTint.toDouble())
            put("balanceToFilmStock", s.balanceToFilmStock)
        })

        put("grade", JSONObject().apply {
            put("contrast", s.contrast.toDouble())
            put("saturation", s.saturation.toDouble())
            put("vibrance", s.vibrance.toDouble())
            put("gamutCompress", s.gamutCompress.toDouble())
        })

        put("masks", MaskJson.toJson(s.localAdjustments))

        put("camera", JSONObject().apply {
            put("exposureCompensationEv", s.exposureCompensationEv.toDouble())
            put("autoExposure", s.autoExposure)
            put("autoExposureMethod", s.autoExposureMethod)
            put("filmFormatMm", s.filmFormatMm.toDouble())
            put("lensBlurUm", s.cameraLensBlurUm.toDouble())
            // Silverphase camera tone + lens colour filter (absent in older documents:
            // decode keeps the defaults 0 / "").
            put("toneHighlights", s.cameraToneHighlights.toDouble())
            put("toneShadows", s.cameraToneShadows.toDouble())
            put("toneWhites", s.cameraToneWhites.toDouble())
            put("toneBlacks", s.cameraToneBlacks.toDouble())
            put("colorFilter", s.cameraFilter)
            put("diffusion", diffJson(s.cameraDiffusionState))
            put("sceneLatitude", JSONObject().apply {
                put("active", s.sceneLatitudeActive)
                put("highlightKnee", s.sceneLatitudeHighlightKnee.toDouble())
                put("highlightRoom", s.sceneLatitudeHighlightRoom.toDouble())
                put("shadowKnee", s.sceneLatitudeShadowKnee.toDouble())
                put("shadowRoom", s.sceneLatitudeShadowRoom.toDouble())
                put("rolloff", s.sceneLatitudeRolloff.toDouble())
                put("maxLift", s.sceneLatitudeMaxLift.toDouble())
            })
            put("filmEdge", JSONObject().apply {
                val edge = s.filmEdge
                put("active", edge.active)
                put("format", edge.format)
                put("mode", edge.mode)
                put("gate", edge.gate)
                put("holes", edge.holes)
                put("carrier", edge.carrier)
                put("cameraSeed", edge.cameraSeed)
                put("frameSeed", edge.frameSeed)
                put("frameNumber", edge.frameNumber)
                put("fog", edge.fog.toDouble())
                put("leaks", edge.leaks.toDouble())
                put("dateActive", edge.dateActive)
                put("dateText", edge.dateText)
                put("dateStyle", edge.dateStyle)
                put("dateExposureEv", edge.dateExposureEv.toDouble())
                put("dateSize", edge.dateSize.toDouble())
                put("dateOrder", edge.dateOrder)
                put("dateWithData", edge.dateWithData)
                put("datePlacement", edge.datePlacement)
                put("dateCorner", edge.dateCorner)
                put("dateInsetX", edge.dateInsetX.toDouble())
                put("dateInsetY", edge.dateInsetY.toDouble())
            })
            put("dateStamp", JSONObject().apply {
                put("active", s.dateStampActive)
                put("text", s.dateStampText)
                put("corner", s.dateStampCorner)
                put("height", s.dateStampHeight.toDouble())
                put("margin", s.dateStampMargin.toDouble())
                put("exposureEv", s.dateStampExposureEv.toDouble())
                tri("color", s.dateStampColor)
            })
        })

        put("enlarger", JSONObject().apply {
            put("illuminant", s.printIlluminant)
            put("printExposure", s.printExposure.toDouble())
            put("printExposureCompensation", s.printExposureCompensation)
            put("yFilterShift", s.printYFilterShift.toDouble())
            put("mFilterShift", s.printMFilterShift.toDouble())
            put("lensBlur", s.enlargerLensBlur.toDouble())
            put("diffusion", diffJson(s.printDiffusionState))
            put("preflashExposure", s.preflashExposure.toDouble())
            put("preflashYFilterShift", s.preflashYFilterShift.toDouble())
            put("preflashMFilterShift", s.preflashMFilterShift.toDouble())
            put("paperGrade", s.paperGrade)
        })

        put("scanner", JSONObject().apply {
            put("lensBlur", s.scanLensBlur.toDouble())
            put("whiteCorrection", s.scanWhiteCorrection)
            put("whiteLevel", s.scanWhiteLevel.toDouble())
            put("blackCorrection", s.scanBlackCorrection)
            put("blackLevel", s.scanBlackLevel.toDouble())
            pair("unsharpMask", s.scanUnsharpMask)
        })

        put("output", JSONObject().apply {
            put("outputColorSpace", s.outputColorSpace.name)
            put("savingCctfEncoding", s.savingCctfEncoding)
            put("outputGamutCompress", s.outputGamutCompress.name)
            put("inputGamutCompress", s.inputGamutCompress.name)
            put("scanFilm", s.scanFilm)
            // Silverphase Digital Intermediate + blue compensation (absent => off).
            put("digitalIntermediate", s.digitalIntermediate)
            put("diBlue", s.digitalIntermediateBlueCompensation)
        })

        // Silverphase print-route options (absent in older documents => defaults).
        put("print", JSONObject().apply {
            put("extendedDynamicRange", s.extendedDynamicRange)
        })

        put("grain", JSONObject().apply {
            put("active", s.grainActive)
            put("sublayersActive", s.grainSublayersActive)
            put("particleAreaUm2", s.grainParticleAreaUm2.toDouble())
            tri("particleScale", s.grainParticleScale)
            tri("particleScaleLayers", s.grainParticleScaleLayers)
            tri("densityMin", s.grainDensityMin)
            tri("uniformity", s.grainUniformity)
            put("blur", s.grainBlur.toDouble())
            put("blurDyeCloudsUm", s.grainBlurDyeCloudsUm.toDouble())
            pair("microStructure", s.grainMicroStructure)
            put("nSubLayers", s.grainNSubLayers)
            put("amount", s.grainAmount.toDouble())
        })

        put("halation", JSONObject().apply {
            put("active", s.halationActive)
            put("scatterAmount", s.halScatterAmount.toDouble())
            put("scatterSpatialScale", s.halScatterSpatialScale.toDouble())
            put("halationAmount", s.halHalationAmount.toDouble())
            put("halationSpatialScale", s.halHalationSpatialScale.toDouble())
            put("boostEv", s.halBoostEv.toDouble())
            put("protectEv", s.halProtectEv.toDouble())
            put("boostRange", s.halBoostRange.toDouble())
            tri("scatterCoreUm", s.halScatterCoreUm)
            tri("scatterTailUm", s.halScatterTailUm)
            tri("scatterTailWeightPct", s.halScatterTailWeightPct)
            tri("halationStrengthPct", s.halHalationStrengthPct)
            tri("firstSigmaUm", s.halFirstSigmaUm)
            put("nBounces", s.halNBounces)
            put("bounceDecay", s.halBounceDecay.toDouble())
            put("renormalize", s.halRenormalize)
            put("headroomEv", s.halationHeadroomEv.toDouble())
            // 1.0.1: marks a headroom the user saw and kept (see the read side).
            put("headroomRev", HEADROOM_REV)
            put("antihalationMode", s.antihalationMode)
        })

        put("couplers", JSONObject().apply {
            put("active", s.couplersActive)
            put("amount", s.couplersAmount.toDouble())
            put("inhibitionSamelayer", s.couplersInhibitionSamelayer.toDouble())
            put("inhibitionInterlayer", s.couplersInhibitionInterlayer.toDouble())
            tri("gammaSamelayer", s.couplersGammaSamelayer)
            pair("gammaRtoGb", s.couplersGammaRtoGb)
            pair("gammaGtoRb", s.couplersGammaGtoRb)
            pair("gammaBtoRg", s.couplersGammaBtoRg)
            put("diffusionSizeUm", s.couplersDiffusionSizeUm.toDouble())
            put("diffusionTailUm", s.couplersDiffusionTailUm.toDouble())
            put("diffusionTailWeight", s.couplersDiffusionTailWeight.toDouble())
        })

        put("glare", JSONObject().apply {
            put("active", s.glareActive)
            put("percent", s.glarePercent.toDouble())
            put("roughness", s.glareRoughness.toDouble())
            put("blur", s.glareBlur.toDouble())
        })

        put("contrastMask", JSONObject().apply {
            put("active", s.contrastMaskActive)
            put("highlights", s.contrastMaskHighlights.toDouble())
            put("shadows", s.contrastMaskShadows.toDouble())
            put("core", s.contrastMaskCore.toDouble())
            put("scale", s.contrastMaskScale.toDouble())
        })

        put("experimental", JSONObject().apply {
            put("filmGammaFactor", s.filmGammaFactor.toDouble())
            put("printGammaFactor", s.printGammaFactor.toDouble())
            put("morphActive", s.morphActive)
            put("morphGammaFactor", s.morphGammaFactor.toDouble())
            put("morphGammaFactorFast", s.morphGammaFactorFast.toDouble())
            put("morphGammaFactorSlow", s.morphGammaFactorSlow.toDouble())
            put("morphGammaFactorRed", s.morphGammaFactorRed.toDouble())
            put("morphGammaFactorGreen", s.morphGammaFactorGreen.toDouble())
            put("morphGammaFactorBlue", s.morphGammaFactorBlue.toDouble())
            put("morphDeveloperExhaustion", s.morphDeveloperExhaustion.toDouble())
        })

        put("toneCurve", JSONObject().apply {
            put("active", s.toneCurveActive)
            put("master", pointsToJson(s.toneCurveMaster))
            put("red", pointsToJson(s.toneCurveRed))
            put("green", pointsToJson(s.toneCurveGreen))
            put("blue", pointsToJson(s.toneCurveBlue))
        })

        // NO "display" block. previewMaxSize is an app-level device setting owned by
        // AppSettings, not a property of a look — see ParamsState.previewMaxSize.
    }

    /**
     * Bring an older preset/recipe JSON up to the current [PRESET_VERSION] before decoding. The
     * version was previously written by [toJson] but never read; reading it here gives one tested
     * place to handle field renames or changed defaults when the schema next breaks. Today every
     * shipped preset was v1. v2 wraps masks in their independently versioned interoperable document.
     * Newer-than-current objects passed directly to [decode] still decode known fields best-effort
     * (unknown keys are ignored and missing keys keep the current default; present numeric fields
     * must still be correctly typed and finite). When you add a field whose value for an *old*
     * preset should differ from a fresh [ParamsState] default, apply that here for `version < N`
     * rather than relying on the
     * constructor default.
     */
    private fun migrate(json: JSONObject): JSONObject {
        val version = json.i("version", 1)
        if (version >= PRESET_VERSION) return json
        // Preserve BigInteger/BigDecimal numeric lexemes across migration on Android;
        // JSONObject(String) would narrow decimals back through platform JSONTokener/Double.
        val migrated = AtomicJsonStore.parseObject(json.toString())
        if (version < 2) {
            migrated.optJSONArray("masks")?.let {
                migrated.put("masks", MaskJson.migrateLegacy(it))
            }
            migrated.put("schema", PRESET_SCHEMA_ID)
            migrated.put("version", 2)
        }
        return migrated
    }

    private fun validateCurrent(json: JSONObject): JSONObject {
        require(json.i("version", -1) == PRESET_VERSION) { "invalid preset version" }
        require(json.opt("schema") == PRESET_SCHEMA_ID) { "unsupported preset schema" }
        json.opt("masks")?.takeUnless { it === JSONObject.NULL }?.let { MaskJson.fromJson(it) }
        AtomicJsonStore.validate(json)
        validatePayload(json)
        return json
    }

    private fun fromJson(json: JSONObject, s: ParamsState) {
        AtomicJsonStore.validate(json)
        val o = migrate(json)
        AtomicJsonStore.validate(o)
        validatePayload(o)
        applyJson(o, s)
    }

    /** Decode once into detached state so a malformed late field cannot partly mutate live state. */
    private fun validatePayload(json: JSONObject) {
        val detached = ParamsState()
        applyJson(json, detached)
        OperationalParamLimits.validate(detached)
    }

    private fun applyJson(o: JSONObject, s: ParamsState) {
        s.filmProfile = o.optString("filmProfile", s.filmProfile)
        s.printProfile = o.optString("printProfile", s.printProfile)

        o.optJSONObject("input")?.let { i ->
            s.inputColorSpace = i.optString("inputColorSpace", s.inputColorSpace)
            s.inputCctfDecoding = i.optBoolean("inputCctfDecoding", s.inputCctfDecoding)
            s.spectralUpsampling = enumOf(i.optString("spectralUpsampling"), Rgb2Raw.entries, s.spectralUpsampling)
            s.adaptationWindow = i.optBoolean("adaptationWindow", s.adaptationWindow)
            s.adaptationSurface = i.optBoolean("adaptationSurface", s.adaptationSurface)
            s.spectralGaussianBlur = i.f("spectralGaussianBlur", s.spectralGaussianBlur)
            s.filterUv = i.triOf("filterUv", s.filterUv)
            s.filterIr = i.triOf("filterIr", s.filterIr)
            s.upscaleFactor = i.f("upscaleFactor", s.upscaleFactor)
            s.crop = i.optBoolean("crop", s.crop)
            s.cropCenter = i.pairOf("cropCenter", s.cropCenter)
            s.cropSize = i.pairOf("cropSize", s.cropSize)
            s.straightenDegrees = clampStraighten(i.f("straighten", s.straightenDegrees))
        }

        o.optJSONObject("raw")?.let { r ->
            s.rawWhiteBalance = enumOf(r.optString("whiteBalance"), WhiteBalance.entries, s.rawWhiteBalance)
            s.rawTemperature = r.f("temperature", s.rawTemperature)
            s.rawTint = r.f("tint", s.rawTint)
            s.rawHotPixels = r.optBoolean("hotPixels", s.rawHotPixels)
            s.rawChromaNoise = r.f("chromaNoise", s.rawChromaNoise).coerceIn(0f, 100f)
        }

        o.optJSONObject("creativeWb")?.let { c ->
            s.creativeWbTemp = c.f("temp", s.creativeWbTemp)
            s.creativeWbTint = c.f("tint", s.creativeWbTint)
            s.balanceToFilmStock = c.optBoolean("balanceToFilmStock", s.balanceToFilmStock)
        }

        o.optJSONObject("grade")?.let { g ->
            s.contrast = g.f("contrast", s.contrast)
            s.saturation = g.f("saturation", s.saturation)
            s.vibrance = g.f("vibrance", s.vibrance)
            s.gamutCompress = g.f("gamutCompress", s.gamutCompress)
        }

        o.opt("masks")?.takeUnless { it === JSONObject.NULL }?.let {
            s.localAdjustments = MaskJson.fromJson(it)
        }

        o.optJSONObject("camera")?.let { c ->
            s.exposureCompensationEv = c.f("exposureCompensationEv", s.exposureCompensationEv)
            s.autoExposure = c.optBoolean("autoExposure", s.autoExposure)
            // An id the engine does not know reads as its schema default rather than failing
            // the document on validation.
            s.autoExposureMethod = c.optString("autoExposureMethod", s.autoExposureMethod)
                .let { if (it in ENGINE_AUTO_EXPOSURE_METHODS) it else "center_weighted" }
            s.filmFormatMm = c.f("filmFormatMm", s.filmFormatMm)
            s.cameraLensBlurUm = c.f("lensBlurUm", s.cameraLensBlurUm)
            s.cameraToneHighlights = c.f("toneHighlights", s.cameraToneHighlights).coerceIn(-100f, 100f)
            s.cameraToneShadows = c.f("toneShadows", s.cameraToneShadows).coerceIn(-100f, 100f)
            s.cameraToneWhites = c.f("toneWhites", s.cameraToneWhites).coerceIn(-100f, 100f)
            s.cameraToneBlacks = c.f("toneBlacks", s.cameraToneBlacks).coerceIn(-100f, 100f)
            // An id this build does not know reads as no filter rather than failing the document.
            s.cameraFilter = c.optString("colorFilter", s.cameraFilter)
                .let { if (it.isEmpty() || it in CAMERA_FILTERS) it else "" }
            c.optJSONObject("diffusion")?.let { readDiff(it, s.cameraDiffusionState) }
            c.optJSONObject("sceneLatitude")?.let { sl ->
                s.sceneLatitudeActive = sl.optBoolean("active", s.sceneLatitudeActive)
                s.sceneLatitudeHighlightKnee = sl.f("highlightKnee", s.sceneLatitudeHighlightKnee)
                s.sceneLatitudeHighlightRoom = sl.f("highlightRoom", s.sceneLatitudeHighlightRoom)
                s.sceneLatitudeShadowKnee = sl.f("shadowKnee", s.sceneLatitudeShadowKnee)
                s.sceneLatitudeShadowRoom = sl.f("shadowRoom", s.sceneLatitudeShadowRoom)
                s.sceneLatitudeRolloff = sl.f("rolloff", s.sceneLatitudeRolloff)
                s.sceneLatitudeMaxLift = sl.f("maxLift", s.sceneLatitudeMaxLift)
            }
            c.optJSONObject("filmEdge")?.let { json ->
                val edge = s.filmEdge
                s.filmEdge = edge.copy(
                    active = json.optBoolean("active", edge.active),
                    format = json.optString("format", edge.format),
                    mode = json.optString("mode", edge.mode),
                    gate = json.optString("gate", edge.gate),
                    holes = json.optString("holes", edge.holes),
                    carrier = json.optString("carrier", edge.carrier),
                    cameraSeed = json.i("cameraSeed", edge.cameraSeed),
                    frameSeed = json.i("frameSeed", edge.frameSeed),
                    frameNumber = json.i("frameNumber", edge.frameNumber),
                    fog = json.f("fog", edge.fog),
                    leaks = json.f("leaks", edge.leaks),
                    dateActive = json.optBoolean("dateActive", edge.dateActive),
                    dateText = json.optString("dateText", edge.dateText),
                    dateStyle = json.optString("dateStyle", edge.dateStyle),
                    dateExposureEv = json.f("dateExposureEv", edge.dateExposureEv),
                    dateSize = json.f("dateSize", edge.dateSize),
                    dateOrder = json.optString("dateOrder", edge.dateOrder),
                    dateWithData = json.optBoolean("dateWithData", edge.dateWithData),
                    datePlacement = json.optString("datePlacement", edge.datePlacement),
                    dateCorner = json.optString("dateCorner", edge.dateCorner),
                    dateInsetX = json.f("dateInsetX", edge.dateInsetX),
                    dateInsetY = json.f("dateInsetY", edge.dateInsetY),
                )
            }
            c.optJSONObject("dateStamp")?.let { ds ->
                s.dateStampActive = ds.optBoolean("active", s.dateStampActive)
                s.dateStampText = ds.optString("text", s.dateStampText)
                s.dateStampCorner = ds.i("corner", s.dateStampCorner)
                s.dateStampHeight = ds.f("height", s.dateStampHeight)
                s.dateStampMargin = ds.f("margin", s.dateStampMargin)
                s.dateStampExposureEv = ds.f("exposureEv", s.dateStampExposureEv)
                s.dateStampColor = ds.triOf("color", s.dateStampColor)
            }
        }

        o.optJSONObject("enlarger")?.let { e ->
            s.printIlluminant = e.optString("illuminant", s.printIlluminant)
            s.printExposure = e.f("printExposure", s.printExposure)
            s.printExposureCompensation = e.optBoolean("printExposureCompensation", s.printExposureCompensation)
            s.printYFilterShift = e.f("yFilterShift", s.printYFilterShift)
            s.printMFilterShift = e.f("mFilterShift", s.printMFilterShift)
            s.enlargerLensBlur = e.f("lensBlur", s.enlargerLensBlur)
            e.optJSONObject("diffusion")?.let { readDiff(it, s.printDiffusionState) }
            s.preflashExposure = e.f("preflashExposure", s.preflashExposure)
            s.preflashYFilterShift = e.f("preflashYFilterShift", s.preflashYFilterShift)
            s.preflashMFilterShift = e.f("preflashMFilterShift", s.preflashMFilterShift)
            s.paperGrade = e.optString("paperGrade", s.paperGrade)
        }

        o.optJSONObject("scanner")?.let { sc ->
            s.scanLensBlur = sc.f("lensBlur", s.scanLensBlur)
            s.scanWhiteCorrection = sc.optBoolean("whiteCorrection", s.scanWhiteCorrection)
            s.scanWhiteLevel = sc.f("whiteLevel", s.scanWhiteLevel)
            s.scanBlackCorrection = sc.optBoolean("blackCorrection", s.scanBlackCorrection)
            s.scanBlackLevel = sc.f("blackLevel", s.scanBlackLevel)
            s.scanUnsharpMask = sc.pairOf("unsharpMask", s.scanUnsharpMask)
        }

        o.optJSONObject("output")?.let { ou ->
            s.outputColorSpace = enumOf(ou.optString("outputColorSpace"), ColorSpace.entries, s.outputColorSpace)
            s.savingCctfEncoding = ou.optBoolean("savingCctfEncoding", s.savingCctfEncoding)
            s.outputGamutCompress =
                enumOf(ou.optString("outputGamutCompress"), OutputGamutCompress.entries, s.outputGamutCompress)
            s.inputGamutCompress =
                enumOf(ou.optString("inputGamutCompress"), InputGamutCompress.entries, s.inputGamutCompress)
            s.scanFilm = ou.optBoolean("scanFilm", s.scanFilm)
        }
        // Silverphase wave-2 keys. A whole document (it always carries filmProfile) that
        // predates them takes their defaults rather than inheriting whatever the target state
        // held; a partial recipe (a per-category reset, which never carries filmProfile)
        // keeps the current value of any key it leaves out, like every other key here.
        val whole = o.has("filmProfile")
        fun flag(section: String, key: String, current: Boolean): Boolean {
            val obj = o.optJSONObject(section)
            return when {
                obj != null && obj.has(key) -> obj.optBoolean(key, false)
                whole -> false
                else -> current
            }
        }
        s.digitalIntermediate = flag("output", "digitalIntermediate", s.digitalIntermediate)
        s.digitalIntermediateBlueCompensation = flag("output", "diBlue", s.digitalIntermediateBlueCompensation)
        s.extendedDynamicRange = flag("print", "extendedDynamicRange", s.extendedDynamicRange)
        val hal = o.optJSONObject("halation")
        if (whole || hal?.has("antihalationMode") == true) {
            // An id this build does not know reads as "profile" (the film's own tag).
            s.antihalationMode = (hal?.optString("antihalationMode", "profile") ?: "profile")
                .let { if (it in ANTIHALATION_MODES) it else "profile" }
        }

        o.optJSONObject("grain")?.let { g ->
            s.grainActive = g.optBoolean("active", s.grainActive)
            s.grainSublayersActive = g.optBoolean("sublayersActive", s.grainSublayersActive)
            s.grainParticleAreaUm2 = g.f("particleAreaUm2", s.grainParticleAreaUm2)
            s.grainParticleScale = g.triOf("particleScale", s.grainParticleScale)
            s.grainParticleScaleLayers = g.triOf("particleScaleLayers", s.grainParticleScaleLayers)
            s.grainDensityMin = g.triOf("densityMin", s.grainDensityMin)
            s.grainUniformity = g.triOf("uniformity", s.grainUniformity)
            s.grainBlur = g.f("blur", s.grainBlur)
            s.grainBlurDyeCloudsUm = g.f("blurDyeCloudsUm", s.grainBlurDyeCloudsUm)
            s.grainMicroStructure = g.pairOf("microStructure", s.grainMicroStructure)
            s.grainNSubLayers = g.i("nSubLayers", s.grainNSubLayers)
            s.grainAmount = g.f("amount", s.grainAmount).coerceIn(0f, 2f)
            migrateLegacyGrain(s)
        }

        o.optJSONObject("halation")?.let { h ->
            s.halationActive = h.optBoolean("active", s.halationActive)
            s.halScatterAmount = h.f("scatterAmount", s.halScatterAmount)
            s.halScatterSpatialScale = h.f("scatterSpatialScale", s.halScatterSpatialScale)
            s.halHalationAmount = h.f("halationAmount", s.halHalationAmount)
            s.halHalationSpatialScale = h.f("halationSpatialScale", s.halHalationSpatialScale)
            s.halBoostEv = h.f("boostEv", s.halBoostEv)
            s.halProtectEv = h.f("protectEv", s.halProtectEv)
            s.halBoostRange = h.f("boostRange", s.halBoostRange)
            s.halScatterCoreUm = h.triOf("scatterCoreUm", s.halScatterCoreUm)
            s.halScatterTailUm = h.triOf("scatterTailUm", s.halScatterTailUm)
            s.halScatterTailWeightPct = h.triOf("scatterTailWeightPct", s.halScatterTailWeightPct)
            s.halHalationStrengthPct = h.triOf("halationStrengthPct", s.halHalationStrengthPct)
            s.halFirstSigmaUm = h.triOf("firstSigmaUm", s.halFirstSigmaUm)
            s.halNBounces = h.i("nBounces", s.halNBounces)
            s.halBounceDecay = h.f("bounceDecay", s.halBounceDecay)
            s.halRenormalize = h.optBoolean("renormalize", s.halRenormalize)
            s.halationHeadroomEv = h.f("headroomEv", s.halationHeadroomEv).coerceIn(0f, 8f)
        }
        val savedHal = o.optJSONObject("halation")
        if (whole && savedHal?.has("headroomRev") != true &&
            (savedHal?.has("headroomEv") != true || s.halationHeadroomEv == 0f)
        ) {
            // 1.0.1: every build before it either had no headroom key (C02 and earlier) or
            // wrote C06's mistaken default of 0, which removed CineStill's red halo from every
            // clipped highlight. Neither was a choice the user made in Silverphase terms, so such
            // a recipe takes Silverphase v1.2.5's still-photo default of 4 EV. A headroom saved
            // by 1.0.1 or later (headroomRev) is kept exactly, 0 included.
            s.halationHeadroomEv = SILVERPHASE_HEADROOM_EV
        }

        o.optJSONObject("couplers")?.let { c ->
            s.couplersActive = c.optBoolean("active", s.couplersActive)
            s.couplersAmount = c.f("amount", s.couplersAmount)
            s.couplersInhibitionSamelayer = c.f("inhibitionSamelayer", s.couplersInhibitionSamelayer)
            s.couplersInhibitionInterlayer = c.f("inhibitionInterlayer", s.couplersInhibitionInterlayer)
            s.couplersGammaSamelayer = c.triOf("gammaSamelayer", s.couplersGammaSamelayer)
            s.couplersGammaRtoGb = c.pairOf("gammaRtoGb", s.couplersGammaRtoGb)
            s.couplersGammaGtoRb = c.pairOf("gammaGtoRb", s.couplersGammaGtoRb)
            s.couplersGammaBtoRg = c.pairOf("gammaBtoRg", s.couplersGammaBtoRg)
            s.couplersDiffusionSizeUm = c.f("diffusionSizeUm", s.couplersDiffusionSizeUm)
            s.couplersDiffusionTailUm = c.f("diffusionTailUm", s.couplersDiffusionTailUm)
            s.couplersDiffusionTailWeight = c.f("diffusionTailWeight", s.couplersDiffusionTailWeight)
        }

        o.optJSONObject("glare")?.let { gl ->
            s.glareActive = gl.optBoolean("active", s.glareActive)
            s.glarePercent = gl.f("percent", s.glarePercent)
            s.glareRoughness = gl.f("roughness", s.glareRoughness)
            s.glareBlur = gl.f("blur", s.glareBlur)
        }

        o.optJSONObject("contrastMask")?.let { cm ->
            s.contrastMaskActive = cm.optBoolean("active", s.contrastMaskActive)
            s.contrastMaskHighlights = cm.f("highlights", s.contrastMaskHighlights)
            s.contrastMaskShadows = cm.f("shadows", s.contrastMaskShadows)
            s.contrastMaskCore = cm.f("core", s.contrastMaskCore)
            s.contrastMaskScale = cm.f("scale", s.contrastMaskScale)
        }

        o.optJSONObject("experimental")?.let { ex ->
            s.filmGammaFactor = ex.f("filmGammaFactor", s.filmGammaFactor)
            s.printGammaFactor = ex.f("printGammaFactor", s.printGammaFactor)
            s.morphActive = ex.optBoolean("morphActive", s.morphActive)
            s.morphGammaFactor = ex.f("morphGammaFactor", s.morphGammaFactor)
            s.morphGammaFactorFast = ex.f("morphGammaFactorFast", s.morphGammaFactorFast)
            s.morphGammaFactorSlow = ex.f("morphGammaFactorSlow", s.morphGammaFactorSlow)
            s.morphGammaFactorRed = ex.f("morphGammaFactorRed", s.morphGammaFactorRed)
            s.morphGammaFactorGreen = ex.f("morphGammaFactorGreen", s.morphGammaFactorGreen)
            s.morphGammaFactorBlue = ex.f("morphGammaFactorBlue", s.morphGammaFactorBlue)
            s.morphDeveloperExhaustion = ex.f("morphDeveloperExhaustion", s.morphDeveloperExhaustion)
        }

        o.optJSONObject("toneCurve")?.let { t ->
            s.toneCurveActive = t.optBoolean("active", s.toneCurveActive)
            s.toneCurveMaster = t.pointsOf("master")
            s.toneCurveRed = t.pointsOf("red")
            s.toneCurveGreen = t.pointsOf("green")
            s.toneCurveBlue = t.pointsOf("blue")
        }

        // "display".previewMaxSize is deliberately NOT read back. Recipes are keyed by
        // source uri and auto-applied on open, so this line silently replayed a stale
        // preview size over the user's Settings value on every real photo — the demo
        // image (which has no recipe) honoured the setting, which is exactly why the
        // bug looked like "inert for RAW only". Every already-saved recipe still
        // carries the old field; dropping the READ is what disarms them.
    }

    private fun <E : Enum<E>> enumOf(name: String, values: List<E>, def: E): E =
        values.firstOrNull { it.name == name } ?: def
}
