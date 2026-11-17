/*
 * Argentroom 1.0.1 -- persistent, on-device-only error log. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * Diagnostics.kt only kept the last Java crash and the CURRENT process's logcat, so a native
 * crash (SIGSEGV in the engine or a driver), a low-memory kill or an ANR during an export left
 * nothing behind once the app restarted. This file adds three bounded, app-private records:
 *
 *  - DiagLog: every Diag breadcrumb (engine lifecycle, export stages, timings, memory) appended
 *    to diag/events.log with a timestamp, the thread and the process id, rotated at 512 KB
 *    (one previous generation kept). Writes go through one background thread.
 *  - ExitHistory: Android's own record of why earlier processes ended
 *    (ActivityManager.getHistoricalProcessExitReasons, API 30+): crash, native crash, ANR,
 *    low memory, killed by the system or the user, with the process's PSS/RSS at death and,
 *    for an ANR, the first 24 KB of its thread dump.
 *  - Device/GPU summary: SoC, RAM, heap limits, the Vulkan device the engine renders on.
 *
 * Nothing leaves the phone unless the user exports it from Settings > Diagnostics, and the
 * export runs through Diagnostics.sanitizeForExport (paths, URIs, file names, EXIF/GPS keys
 * redacted).
 */
package com.spectrafilm.app.diagnostics

import com.spectrafilm.app.*
import com.spectrafilm.app.editor.*
import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.Process
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

object DiagLog {
    private const val DIR = "diag"
    private const val FILE = "events.log"
    private const val OLD = "events.1.log"
    internal const val MAX_BYTES = 512 * 1024L
    private const val MAX_LINE_CHARS = 2_000

    @Volatile private var dir: File? = null
    private val writer = Executors.newSingleThreadExecutor { r ->
        Thread(r, "argentroom-diaglog").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
    }
    private val stamp = ThreadLocal.withInitial { SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US) }

    fun init(context: Context) {
        if (dir != null) return
        dir = File(context.applicationContext.filesDir, DIR).apply { mkdirs() }
        log('I', "process start pid=${Process.myPid()} app=${Diagnostics.appVersion(context)} " +
            "api=${Build.VERSION.SDK_INT}")
    }

    fun log(level: Char, msg: String) {
        val d = dir ?: return
        val line = buildString {
            append(stamp.get()!!.format(Date())).append(' ').append(level).append(' ')
            append(Process.myPid()).append(' ').append(Thread.currentThread().name).append(": ")
            append(if (msg.length > MAX_LINE_CHARS) msg.substring(0, MAX_LINE_CHARS) + "…" else msg)
            append('\n')
        }
        runCatching {
            writer.execute {
                runCatching {
                    val f = File(d, FILE)
                    if (f.length() > MAX_BYTES) {
                        val old = File(d, OLD)
                        old.delete()
                        f.renameTo(old)
                    }
                    f.appendText(line, Charsets.UTF_8)
                }
            }
        }
    }

    /** One memory line: RSS and its high-water mark, native heap, Java heap, free system RAM. */
    fun memory(tag: String, context: Context? = null) {
        val rt = Runtime.getRuntime()
        val avail = context?.let { ctx ->
            runCatching {
                val mi = ActivityManager.MemoryInfo()
                (ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(mi)
                " sysAvail=${mi.availMem shr 20}MB lowMemory=${mi.lowMemory}"
            }.getOrNull()
        }.orEmpty()
        log('M', "mem[$tag] rss=${procStatusKb("VmRSS") / 1024}MB hwm=${procStatusKb("VmHWM") / 1024}MB " +
            "native=${Debug.getNativeHeapAllocatedSize() shr 20}MB " +
            "java=${(rt.totalMemory() - rt.freeMemory()) shr 20}/${rt.maxMemory() shr 20}MB$avail")
    }

    private fun procStatusKb(key: String): Long = runCatching {
        File("/proc/self/status").useLines { lines ->
            lines.firstOrNull { it.startsWith("$key:") }?.substringAfter(':')?.trim()
                ?.substringBefore(' ')?.toLongOrNull()
        }
    }.getOrNull() ?: 0L

    /** The previous generation then the current one, newest last, at most [maxBytes]. */
    fun read(context: Context, maxBytes: Int): String {
        val d = File(context.filesDir, DIR)
        val text = listOf(File(d, OLD), File(d, FILE)).filter { it.isFile }
            .joinToString("") { runCatching { it.readText(Charsets.UTF_8) }.getOrDefault("") }
        return if (text.length > maxBytes) "[older events truncated]\n" + text.takeLast(maxBytes) else text
    }

    fun clear(context: Context) {
        val d = File(context.filesDir, DIR)
        File(d, FILE).delete(); File(d, OLD).delete()
        log('I', "event log cleared by the user")
    }
}

object ExitHistory {
    private const val FILE = "exits.log"
    private const val SEEN = "exits.seen"
    private const val MAX_BYTES = 256 * 1024L
    private const val ANR_TRACE_BYTES = 24 * 1024

    /** Append every process exit Android recorded since the last call (API 30+). Background. */
    fun record(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val app = context.applicationContext
        Thread({ runCatching { recordNow(app) } }, "argentroom-exits").apply { isDaemon = true }.start()
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.R)
    private fun recordNow(context: Context) {
        val dir = File(context.filesDir, "diag").apply { mkdirs() }
        val seenFile = File(dir, SEEN)
        val seen = runCatching { seenFile.readText().trim().toLong() }.getOrDefault(0L)
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val infos = am.getHistoricalProcessExitReasons(context.packageName, 0, 16)
            .filter { it.timestamp > seen }.sortedBy { it.timestamp }
        if (infos.isEmpty()) return
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val out = File(dir, FILE)
        if (out.length() > MAX_BYTES) out.writeText("[older exits dropped]\n")
        for (i in infos) {
            val s = buildString {
                append("=== ").append(fmt.format(Date(i.timestamp))).append(" pid=").append(i.pid)
                append(" reason=").append(reasonName(i.reason)).append(" status=").append(i.status)
                append(" importance=").append(i.importance)
                append(" pss=").append(i.pss / 1024).append("MB rss=").append(i.rss / 1024).append("MB\n")
                i.description?.let { append("description: ").append(it).append('\n') }
                if (i.reason == ApplicationExitInfo.REASON_ANR) {
                    runCatching {
                        i.traceInputStream?.use { s ->
                            val buf = ByteArray(ANR_TRACE_BYTES)
                            val n = s.read(buf)
                            if (n > 0) append("anr trace (first ${n / 1024} KB):\n")
                                .append(String(buf, 0, n, Charsets.UTF_8)).append('\n')
                        }
                    }
                }
                if (i.reason == ApplicationExitInfo.REASON_CRASH_NATIVE) {
                    append("native crash: Android keeps the tombstone; its summary is in the description above.\n")
                }
            }
            out.appendText(s, Charsets.UTF_8)
            DiagLog.log('E', "previous process exit: ${reasonName(i.reason)} ${i.description.orEmpty()}")
        }
        seenFile.writeText(infos.last().timestamp.toString())
    }

    fun read(context: Context): String =
        runCatching { File(File(context.filesDir, "diag"), FILE).readText(Charsets.UTF_8) }.getOrDefault("")

    fun clear(context: Context) { runCatching { File(File(context.filesDir, "diag"), FILE).delete() } }

    internal fun reasonName(r: Int): String = when (r) {
        1 -> "EXIT_SELF"
        2 -> "SIGNALED"
        3 -> "LOW_MEMORY"
        4 -> "CRASH (Java)"
        5 -> "CRASH_NATIVE"
        6 -> "ANR"
        7 -> "INITIALIZATION_FAILURE"
        8 -> "PERMISSION_CHANGE"
        9 -> "EXCESSIVE_RESOURCE_USAGE"
        10 -> "USER_REQUESTED"
        11 -> "USER_STOPPED"
        12 -> "DEPENDENCY_DIED"
        13 -> "OTHER"
        14 -> "FREEZER"
        15 -> "PACKAGE_STATE_CHANGE"
        16 -> "PACKAGE_UPDATED"
        else -> "UNKNOWN($r)"
    }
}

object DeviceSummary {
    fun text(context: Context): String = buildString {
        append("manufacturer/model: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
            .append(" (").append(Build.DEVICE).append(")\n")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            append("SoC: ").append(Build.SOC_MANUFACTURER).append(' ').append(Build.SOC_MODEL).append('\n')
        }
        append("hardware: ").append(Build.HARDWARE).append("; ABIs: ")
            .append(Build.SUPPORTED_ABIS.joinToString()).append('\n')
        append("Android ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT)
            .append("), build ").append(Build.DISPLAY).append('\n')
        runCatching {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val mi = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
            append("RAM total ").append(mi.totalMem shr 20).append(" MB, available ")
                .append(mi.availMem shr 20).append(" MB; heap class ").append(am.memoryClass)
                .append(" MB, large heap ").append(am.largeMemoryClass).append(" MB\n")
        }
        append("Java heap max: ").append(Runtime.getRuntime().maxMemory() shr 20).append(" MB; CPU cores: ")
            .append(Runtime.getRuntime().availableProcessors()).append('\n')
        val cfg = context.resources.configuration
        append("font scale ").append(cfg.fontScale).append(", density ")
            .append(context.resources.displayMetrics.densityDpi).append(" dpi, locale ")
            .append(cfg.locales[0]).append('\n')
        append("GPU (Vulkan): ").append(com.spectrafilm.engine.SpektraEngine.gpuDeviceInfo()).append('\n')
    }
}

/** FileProvider for the exported diagnostics report only (see AndroidManifest.xml). */
class DiagnosticsFileProvider : androidx.core.content.FileProvider()
