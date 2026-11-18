/*
 * Argentroom for Android — confirmed source identity memo (perf r5). GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * Every export and every idle pre-render keys its caches on the SHA-256 of the whole source
 * file (ExportCacheKey.digestSource). That is a full read of a 25-80 MB RAW per export, even when
 * the same source was hashed seconds earlier by the pre-render. This memo keeps the digest of a
 * source whose identity was CONFIRMED by a full hash, and serves it again only when a fresh
 * fingerprint of the file still matches the one taken around that full hash.
 *
 * The fingerprint is deliberately more than URI/name/mtime (which a re-used content URI or a
 * same-second rewrite can defeat): it is the file's length, device, inode and mtime from fstat
 * of the open descriptor, PLUS a SHA-256 of its first and last [sampleBytes] bytes read from
 * that same descriptor. A source without a stat (non-file-backed provider stream) is never
 * memoized: it is hashed in full every time, as before. On a miss the full digest is taken
 * between two fingerprints, and it is remembered only if both agree and the hashed byte count
 * equals the stat length, so a file modified while being hashed is never recorded.
 */
package com.argentroom.app.export

import java.io.InputStream
import java.security.MessageDigest

/** What fstat says about an open source; any field the platform cannot give is 0. */
internal data class SourceStat(
    val length: Long,
    val mtimeSeconds: Long,
    val device: Long,
    val inode: Long,
)

internal class SourceDigestMemo(
    private val capacity: Int = 4,
    private val sampleBytes: Int = 64 * 1024,
) {
    private data class Fingerprint(val stat: SourceStat, val sampleSha: String)
    private class Entry(val fingerprint: Fingerprint, val sha256: String, val bytes: Long)

    private val lock = Any()
    private val entries = LinkedHashMap<String, Entry>(capacity * 2, 0.75f, true)

    /** Full digests actually computed, for tests and diagnostics. */
    @Volatile var fullDigests: Int = 0
        private set

    /**
     * The source's (sha256, byte count), exactly as [ExportCacheKey.digestSource] would return
     * it, or null when the source cannot be read. [stat] and [open] each open the source anew.
     */
    fun digest(
        key: String?,
        stat: () -> SourceStat?,
        open: () -> InputStream?,
    ): Pair<String, Long>? {
        val before = key?.let { fingerprint(stat, open) }
        if (key != null && before != null) {
            synchronized(lock) { entries[key] }?.let { hit ->
                if (hit.fingerprint == before) return hit.sha256 to hit.bytes
            }
        }
        fullDigests++
        val full = ExportCacheKey.digestSource(open) ?: return null
        if (key == null || before == null) return full
        val after = fingerprint(stat, open)
        if (after == before && full.second == before.stat.length) {
            synchronized(lock) {
                entries[key] = Entry(before, full.first, full.second)
                while (entries.size > capacity) {
                    entries.remove(entries.keys.first())
                }
            }
        } else {
            synchronized(lock) { entries.remove(key) }
        }
        return full
    }

    fun forget(key: String) = synchronized(lock) { entries.remove(key); Unit }

    private fun fingerprint(stat: () -> SourceStat?, open: () -> InputStream?): Fingerprint? =
        runCatching {
            val s = stat() ?: return null
            if (s.length <= 0L) return null
            val md = MessageDigest.getInstance("SHA-256")
            md.update("len=${s.length};".toByteArray())
            val stream = open() ?: return null
            stream.use { input ->
                val buffer = ByteArray(sampleBytes)
                val head = readFully(input, buffer, minOf(sampleBytes.toLong(), s.length).toInt())
                md.update(buffer, 0, head)
                val tailStart = maxOf(head.toLong(), s.length - sampleBytes)
                if (tailStart < s.length) {
                    if (!skipFully(input, tailStart - head)) return null
                    val tail = readFully(input, buffer, (s.length - tailStart).toInt())
                    md.update("tail@$tailStart;".toByteArray())
                    md.update(buffer, 0, tail)
                }
            }
            Fingerprint(s, ExportCache.hex(md.digest()))
        }.getOrNull()

    private fun readFully(input: InputStream, buffer: ByteArray, want: Int): Int {
        var got = 0
        while (got < want) {
            val n = input.read(buffer, got, want - got)
            if (n <= 0) break
            got += n
        }
        return got
    }

    private fun skipFully(input: InputStream, count: Long): Boolean {
        var left = count
        while (left > 0) {
            val n = input.skip(left)
            if (n <= 0) {
                // skip() may legally return 0; one read tells EOF from a lazy stream.
                if (input.read() < 0) return false
                left--
            } else {
                left -= n
            }
        }
        return true
    }
}

/** Process-wide memo shared by the export and the idle pre-render. */
internal object SourceDigests {
    val memo = SourceDigestMemo()

    fun of(context: android.content.Context, uri: android.net.Uri): Pair<String, Long>? {
        val resolver = context.contentResolver
        return memo.digest(
            key = uri.toString(),
            stat = {
                resolver.openFileDescriptor(uri, "r")?.use { pfd ->
                    val st = android.system.Os.fstat(pfd.fileDescriptor)
                    SourceStat(st.st_size, st.st_mtime, st.st_dev, st.st_ino)
                }
            },
            open = { resolver.openInputStream(uri) },
        )
    }
}
