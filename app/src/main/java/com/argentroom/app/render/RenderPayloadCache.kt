/*
 * Argentroom for Android — idle pre-render payload store (issue #179). GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * The export cache in ExportCache.kt keys FINISHED CONTAINER BYTES, so it only pays off on an
 * export the user has already performed once. This store covers the other half of #179: the
 * expensive part of a first export is decode (~0.98 s) plus the engine (~5 s on BASE, ~13.5 s
 * on HEAVY), and both are already done while the editor sits idle. Keeping the engine's float
 * output means the export still runs the real encoder, so the published bytes are produced by
 * exactly the same code as an uncached export — the pre-render can never make the file differ.
 *
 * One entry, deliberately. The user exports what is on screen; a second slot would cost another
 * ~150 MB of storage to serve an edit state they have already navigated away from.
 */
package com.argentroom.app.render

import com.argentroom.app.*
import com.argentroom.app.diagnostics.*
import com.argentroom.app.export.*
import com.argentroom.engine.ColorSpace
import com.argentroom.engine.NativeBufferOwner
import com.argentroom.engine.SimResult
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import org.json.JSONObject

/** A payload buffer and the one call that gives its memory back. */
internal class OwnedPayload(val data: ByteBuffer, val release: () -> Unit)

/**
 * The production payload buffer: coordinator-admitted native memory (#176), so a 150 MB
 * restore is counted against the process ceiling and freed exactly when the restored
 * [SimResult]'s last lease closes, instead of whenever a mapping happens to be collected.
 */
internal fun nativeOwnedPayload(bytes: Long): OwnedPayload {
    val owner = NativeBufferOwner.allocate(bytes)
    val lease = try {
        owner.acquireDataLease()
    } finally {
        // The lease is the only remaining authority over the allocation.
        owner.close()
    }
    return OwnedPayload(lease.data) { lease.close() }
}

internal class RenderPayloadCache(
    private val root: File,
    private val contractVersion: String,
    private val maxAgeMillis: Long = 24L * 60 * 60 * 1000,
    private val allocate: (Long) -> OwnedPayload = ::nativeOwnedPayload,
    private val log: (String, Throwable?) -> Unit = { message, failure ->
        if (failure == null) Diag.i(message) else Diag.w("$message: $failure")
    },
) {
    private val lock = Any()

    private val payloadFile get() = File(root, "payload.bin")
    private val metaFile get() = File(root, "payload.json")
    private val sceneFile get() = File(root, "scene.bin")

    /**
     * perf r5: the parsed metadata, kept so the idle pass's [holds] probe, the export's [get]
     * and [getScene] do not each re-read and re-parse payload.json. Every write and discard of
     * the metadata goes through this class (it lives in the app-private cache dir), and both
     * clear this; the file's length and mtime are still compared so an outside change re-reads.
     */
    private var metaMemo: Triple<Long, Long, JSONObject>? = null

    private fun readMeta(): JSONObject? {
        if (!metaFile.isFile) { metaMemo = null; return null }
        val length = metaFile.length()
        val modified = metaFile.lastModified()
        metaMemo?.let { (l, m, json) -> if (l == length && m == modified) return json }
        val json = JSONObject(metaFile.readText())
        metaMemo = Triple(length, modified, json)
        return json
    }

    /**
     * The stored render for [key], or null for any doubt at all. Never throws: a miss costs a
     * re-render, while a wrong hit publishes someone else's image.
     *
     * The payload is read into a private buffer of its own (never mapped), so the export can
     * grade it in place for the native writers without those writes reaching the file, and the
     * buffer is released through the returned [SimResult]'s close, not by the collector.
     */
    fun get(key: String): SimResult? = synchronized(lock) {
        runCatching { read(key) }.getOrElse { failure ->
            log("pre-render payload unreadable; re-rendering", failure)
            discard()
            null
        }
    }

    private fun read(key: String): SimResult? {
        if (!metaFile.isFile || !payloadFile.isFile) return null
        val meta = readMeta() ?: return null
        if (meta.optString("key") != key) return null
        if (meta.optString("contract") != contractVersion) return null
        if (System.currentTimeMillis() - metaFile.lastModified() > maxAgeMillis) {
            discard()
            return null
        }
        val width = meta.optInt("width")
        val height = meta.optInt("height")
        val bytes = meta.optLong("bytes")
        if (width <= 0 || height <= 0) return null
        // A payload truncated by a killed write or a full disk is a miss, not a crash.
        if (bytes != payloadFile.length() || bytes != width.toLong() * height * 3L * 4L) {
            discard()
            return null
        }
        // A java.nio buffer cannot exceed 2 GiB; such a payload is never written (see write).
        if (bytes > Int.MAX_VALUE) return null
        val colorSpace = runCatching { ColorSpace.valueOf(meta.optString("color_space")) }
            .getOrNull() ?: return null
        // #176: a plain read into an explicitly owned buffer replaced a PRIVATE mapping whose
        // release callback was empty. The export grades in place, so a mapping paid the
        // copy-on-write for every page anyway; this pays one copy and gets a real release.
        // RandomAccessFile rather than FileChannel.open because minSdk is 24.
        val owned = allocate(bytes)
        try {
            val target = owned.data.duplicate().order(ByteOrder.nativeOrder())
            target.position(0)
            target.limit(bytes.toInt())
            java.io.RandomAccessFile(payloadFile, "r").use { file ->
                val channel: FileChannel = file.channel
                while (target.hasRemaining()) {
                    if (channel.read(target) < 0) break
                }
            }
            if (target.hasRemaining()) {
                // Shorter than its own metadata claimed a moment ago: treat as truncated.
                owned.release()
                discard()
                return null
            }
            return SimResult.fromExternalBuffer(owned.data, width, height, colorSpace) {
                owned.release()
            }
        } catch (failure: Throwable) {
            owned.release()
            throw failure
        }
    }

    /**
     * Store [result] under [key], replacing whatever was there. Metadata is written LAST, so a
     * process killed mid-write leaves a payload no reader will accept rather than a valid-looking
     * entry holding a partial image.
     */
    fun put(key: String, result: SimResult, scene: SceneLuma? = null): Boolean = synchronized(lock) {
        runCatching { write(key, result, scene) }.getOrElse { failure ->
            log("pre-render payload not stored", failure)
            discard()
            false
        }
    }

    private fun write(key: String, result: SimResult, scene: SceneLuma?): Boolean {
        // Long arithmetic: at the 16384 px export edge, w*h*12 overflows Int (> ~178 MP),
        // which used to fail the store with a negative limit. Beyond 2 GiB no nio buffer can
        // hold the payload on restore, so it is refused here rather than written for nothing.
        val payloadBytes = result.width.toLong() * result.height * 3L * 4L
        if (payloadBytes <= 0L || payloadBytes > Int.MAX_VALUE) {
            log("pre-render payload of $payloadBytes bytes is beyond a buffer; not stored", null)
            return false
        }
        root.mkdirs()
        metaFile.delete()
        metaMemo = null
        sceneFile.delete()
        val staging = File(root, "payload.tmp")
        val written = result.acquireDataLease().use { lease ->
            val source = lease.data.duplicate().order(ByteOrder.nativeOrder())
            source.position(0)
            source.limit(payloadBytes.toInt())
            // FileOutputStream truncates on open and needs no java.nio.file (API 26).
            java.io.FileOutputStream(staging).use { out ->
                var total = 0L
                while (source.hasRemaining()) total += out.channel.write(source)
                total
            }
        }
        if (!staging.renameTo(payloadFile)) {
            staging.delete()
            return false
        }
        val meta = JSONObject()
            .put("key", key)
            .put("contract", contractVersion)
            .put("width", result.width)
            .put("height", result.height)
            .put("bytes", written)
            .put("color_space", result.colorSpace.name)
        // The Ultra HDR scene tap needs the DECODE, which a restored payload no longer has, so it
        // is stored with the render it belongs to. Optional: a payload without it is still valid.
        if (scene != null && runCatching { writeScene(scene) }.getOrDefault(false)) {
            meta.put("scene_width", scene.width).put("scene_height", scene.height)
        }
        metaFile.writeText(meta.toString())
        metaMemo = null
        return true
    }

    private fun writeScene(scene: SceneLuma): Boolean {
        val buf = ByteBuffer.allocate(scene.y.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        buf.asFloatBuffer().put(scene.y)
        java.io.FileOutputStream(sceneFile).use { it.write(buf.array()) }
        return true
    }

    /**
     * The scene tap stored with [key]'s payload (see [put]), or null when there is none or any
     * doubt. Never throws: a miss only means the export re-decodes for it.
     */
    fun getScene(key: String): SceneLuma? = synchronized(lock) {
        runCatching {
            if (!metaFile.isFile || !sceneFile.isFile) return@runCatching null
            val meta = readMeta() ?: return@runCatching null
            if (meta.optString("key") != key || meta.optString("contract") != contractVersion) {
                return@runCatching null
            }
            val w = meta.optInt("scene_width")
            val h = meta.optInt("scene_height")
            if (w <= 0 || h <= 0 || sceneFile.length() != w.toLong() * h * 4L) return@runCatching null
            val y = FloatArray(w * h)
            ByteBuffer.wrap(sceneFile.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(y)
            SceneLuma(w, h, y)
        }.getOrNull()
    }

    fun discard() = synchronized(lock) {
        metaMemo = null
        metaFile.delete()
        payloadFile.delete()
        sceneFile.delete()
        File(root, "payload.tmp").delete()
        Unit
    }

    /**
     * Whether [key] is already stored, without mapping it. The idle pre-render asks this before
     * spending ~6 s of engine time, so it must not pay for a 150 MB mapping to find out.
     */
    fun holds(key: String): Boolean = synchronized(lock) {
        runCatching {
            metaFile.isFile && payloadFile.isFile &&
                readMeta()?.let {
                    it.optString("key") == key && it.optString("contract") == contractVersion
                } == true
        }.getOrDefault(false)
    }

    /** Bytes currently held, for diagnostics. */
    fun sizeBytes(): Long = if (payloadFile.isFile) payloadFile.length() else 0L
}

internal object RenderPayloads {
    private const val DIR = "prerender"
    @Volatile private var instance: RenderPayloadCache? = null

    /** The pre-render in flight, which an export joins or cancels (#270). */
    val flight = PrerenderFlight()

    /** How long the editor must sit still before speculating a full-resolution render. */
    const val IDLE_DEBOUNCE_MS = 5_000L

    /**
     * Whether speculative work is appropriate right now. A pre-render costs ~6 s of all-core
     * engine time and ~1.5 GB of peak RSS for an export the user may never ask for, so it is
     * skipped on a hot, memory-pressured or power-saving device — where the cost is real and the
     * saving is hypothetical.
     */
    fun shouldPrerender(context: android.content.Context, sourcePixels: Long? = null): Boolean {
        val power = context.getSystemService(android.os.PowerManager::class.java)
        val battery = context.getSystemService(android.os.BatteryManager::class.java)
        val conditions = PrerenderConditions(
            powerSave = power?.isPowerSaveMode == true,
            thermalStatus = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                power?.currentThermalStatus ?: 0
            } else 0,
            // Forecast 10 s ahead: about one pre-render. NaN when unsupported.
            thermalHeadroom = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                runCatching { power?.getThermalHeadroom(10) ?: Float.NaN }.getOrDefault(Float.NaN)
            } else Float.NaN,
            batteryPercent = battery?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
                ?: -1,
            charging = battery?.isCharging == true,
        )
        conditions.refusal()?.let { reason ->
            Diag.i("pre-render skipped: $reason")
            return false
        }
        val activity = context.getSystemService(android.app.ActivityManager::class.java)
            ?: return true
        val info = android.app.ActivityManager.MemoryInfo()
        activity.getMemoryInfo(info)
        if (info.lowMemory) return false
        val allowed = prerenderMemoryAllows(info.availMem, info.threshold, sourcePixels ?: PRERENDER_UNKNOWN_PIXELS)
        if (!allowed) {
            Diag.i(
                "pre-render skipped for memory: avail=${info.availMem shr 20} MiB " +
                    "threshold=${info.threshold shr 20} MiB pixels=${sourcePixels ?: -1}",
            )
        }
        return allowed
    }

    /**
     * Whether a speculative full-resolution render fits: what it is expected to take
     * ([PRERENDER_PEAK_BYTES_PER_PIXEL] per source pixel) must leave the system's
     * low-memory threshold plus [PRERENDER_MEMORY_MARGIN_BYTES] available, or the
     * low-memory killer starts taking background apps for work the user may never use.
     */
    fun prerenderMemoryAllows(availMem: Long, lowMemoryThreshold: Long, sourcePixels: Long): Boolean {
        val need = sourcePixels.coerceAtLeast(0L) * PRERENDER_PEAK_BYTES_PER_PIXEL
        return availMem - need >= lowMemoryThreshold + PRERENDER_MEMORY_MARGIN_BYTES
    }

    /**
     * System memory a full-resolution render takes per source pixel, GPU included. Defined
     * once in [EngineMemoryModel] (AppMemoryBudget.kt) so a new engine measurement is a
     * one-line change.
     */
    const val PRERENDER_PEAK_BYTES_PER_PIXEL = EngineMemoryModel.FULL_RES_PEAK_BYTES_PER_PIXEL

    /** Gap between the two MemAvailable readings that must both pass. */
    const val PRERENDER_RECHECK_MS = 3_000L
    const val PRERENDER_MEMORY_MARGIN_BYTES = 512L shl 20

    /** Pixels assumed when the source's size is unknown: a 48 MP phone RAW. */
    const val PRERENDER_UNKNOWN_PIXELS = 48_000_000L

    /** How long the preview must have been idle before a pre-render starts. */
    const val PREVIEW_SETTLE_MS = 2_000L

    /** Longest wait for the first preview; past it the pre-render is skipped. */
    const val PREVIEW_SETTLE_TIMEOUT_MS = 60_000L

    /**
     * perf r5: largest export render kept for reuse ([storeExportRender]): 64 MP of float RGB.
     * Beyond it the disk write costs more export time than a re-export is likely to save.
     */
    const val EXPORT_REUSE_MAX_BYTES = 768L shl 20

    /**
     * Keep the engine output a just-finished export rendered (before its in-place grade), so
     * re-exporting the same edit with only container, JPEG quality, name, resize, sharpening,
     * border, colour grade or local masks changed restores it instead of running the film
     * simulation again, and the idle pre-render finds it held instead of rendering it a second
     * time. Keyed by the same [key] as the pre-render, which covers every engine input, so any
     * physical edit (stock, exposure, halation, decode settings, source bytes) misses.
     * Returns whether it was stored; never throws.
     */
    fun storeExportRender(
        context: android.content.Context,
        key: String,
        result: SimResult,
        scene: SceneLuma?,
    ): Boolean = runCatching {
        val bytes = result.width.toLong() * result.height * 3L * 4L
        if (bytes > EXPORT_REUSE_MAX_BYTES) return@runCatching false
        val store = of(context)
        if (store.holds(key)) return@runCatching false
        val cacheDir = context.applicationContext.cacheDir
        // Never crowd the export's own staging file out of the cache partition.
        if (cacheDir.usableSpace < bytes * 2 + (256L shl 20)) return@runCatching false
        val t0 = System.currentTimeMillis()
        store.put(key, result, scene).also { stored ->
            if (stored) Diag.i("export render kept for reuse in ${System.currentTimeMillis() - t0} ms")
        }
    }.getOrDefault(false)

    fun of(context: android.content.Context): RenderPayloadCache {
        val app = context.applicationContext
        return instance ?: synchronized(this) {
            instance ?: RenderPayloadCache(
                root = File(app.cacheDir, DIR),
                contractVersion = ExportCaches.contractVersionOf(app),
            ).also { instance = it }
        }
    }

    /**
     * The pre-render key. Deliberately NOT the export cache key: this payload is pre-encode, so
     * it is independent of container, quality, resize and the post-engine grade — the same
     * payload serves a JPEG and a TIFF of the same edit.
     */
    fun key(
        source: ExportCacheKey.Source,
        params: com.argentroom.engine.SpektraParams,
        engineColorSpace: ColorSpace?,
        contractVersion: String,
    ): String = ExportCacheKey.sha256Hex(
        buildString {
            append("prerender-v1\n")
            append("source=").append(source.contentSha256).append('\n')
            append("bytes=").append(source.bytes).append('\n')
            append("edge=").append(source.decodeMaxEdge).append('\n')
            append("decode=").append(source.decodeIdentity).append('\n')
            append("params=").append(params.toString()).append('\n')
            append("engine_cs=").append(engineColorSpace?.name ?: "null").append('\n')
            append("contract=").append(contractVersion).append('\n')
        }.toByteArray(Charsets.UTF_8)
    )
}

/**
 * perf r5: the device-state half of the idle pre-render gate, as plain values so it is JVM
 * testable. The pre-render is speculative (~6 s of all-core engine time and a large peak RSS),
 * so it stands down whenever the device is already paying for something: power saving, any
 * thermal throttling above LIGHT or a forecast close to it, or a low battery that is not
 * charging. Memory pressure / lmkd risk stays with the two MemAvailable readings in
 * [RenderPayloads.shouldPrerender] (lastTrimLevel was considered and rejected: it is sticky
 * after the app was backgrounded, which would stand the pre-render down for good).
 */
internal data class PrerenderConditions(
    val powerSave: Boolean,
    /** PowerManager.THERMAL_STATUS_*; 0 when unknown. */
    val thermalStatus: Int,
    /** PowerManager.getThermalHeadroom: 1.0 = severe throttling; NaN when unsupported. */
    val thermalHeadroom: Float,
    /** 0..100, or negative when unknown. */
    val batteryPercent: Int,
    val charging: Boolean,
) {
    fun refusal(): String? = when {
        powerSave -> "power save"
        thermalStatus > THERMAL_STATUS_LIGHT -> "thermal status $thermalStatus"
        !thermalHeadroom.isNaN() && thermalHeadroom >= MAX_THERMAL_HEADROOM -> "thermal headroom $thermalHeadroom"
        !charging && batteryPercent in 0 until MIN_BATTERY_PERCENT -> "battery $batteryPercent%"
        else -> null
    }

    companion object {
        /** PowerManager.THERMAL_STATUS_LIGHT. */
        const val THERMAL_STATUS_LIGHT = 1
        /** Forecast headroom at which the pre-render would likely push the SoC into throttling. */
        const val MAX_THERMAL_HEADROOM = 0.85f
        const val MIN_BATTERY_PERCENT = 20
    }
}
