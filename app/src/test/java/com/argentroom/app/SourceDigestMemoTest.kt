/*
 * Argentroom for Android — confirmed source identity memo tests (perf r5). GPLv3.
 *
 * The memo may only ever return the digest a full hash would return right now. Every test that
 * changes the file in some way must therefore see a fresh full hash and the new digest.
 */
package com.argentroom.app

import com.argentroom.app.export.*
import java.io.File
import java.io.RandomAccessFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SourceDigestMemoTest {
    @get:Rule val temp = TemporaryFolder()

    private fun statOf(f: File, mtime: Long = f.lastModified() / 1000) =
        SourceStat(f.length(), mtime, 1L, 42L)

    private fun file(size: Int, seed: Int = 7): File = temp.newFile().apply {
        writeBytes(ByteArray(size) { ((it * 31 + seed) and 0xFF).toByte() })
    }

    private fun SourceDigestMemo.of(f: File, mtime: Long = f.lastModified() / 1000) =
        digest(f.path, { statOf(f, mtime) }, { f.inputStream() })

    @Test fun memoMatchesFullDigestAndSkipsRehash() {
        val memo = SourceDigestMemo(sampleBytes = 1024)
        val f = file(300_000)
        val expected = ExportCacheKey.digestFile(f)
        assertEquals(expected, memo.of(f))
        assertEquals(expected, memo.of(f))
        assertEquals(expected, memo.of(f))
        assertEquals(1, memo.fullDigests)
    }

    @Test fun headChangeWithSameStatIsDetected() {
        val memo = SourceDigestMemo(sampleBytes = 1024)
        val f = file(300_000)
        memo.of(f, mtime = 5)
        RandomAccessFile(f, "rw").use { it.seek(10); it.write(0x55) }
        assertEquals(ExportCacheKey.digestFile(f), memo.of(f, mtime = 5))
        assertEquals(2, memo.fullDigests)
    }

    @Test fun tailChangeWithSameStatIsDetected() {
        val memo = SourceDigestMemo(sampleBytes = 1024)
        val f = file(300_000)
        val first = memo.of(f, mtime = 5)
        RandomAccessFile(f, "rw").use { it.seek(f.length() - 3); it.write(0x55) }
        val second = memo.of(f, mtime = 5)
        assertNotEquals(first, second)
        assertEquals(ExportCacheKey.digestFile(f), second)
    }

    @Test fun mtimeOrLengthChangeForcesFullHash() {
        val memo = SourceDigestMemo(sampleBytes = 1024)
        val f = file(300_000)
        memo.of(f, mtime = 5)
        memo.of(f, mtime = 6)
        assertEquals(2, memo.fullDigests)
        f.appendBytes(byteArrayOf(1))
        assertEquals(ExportCacheKey.digestFile(f), memo.of(f, mtime = 6))
        assertEquals(3, memo.fullDigests)
    }

    @Test fun noStatMeansNoMemo() {
        val memo = SourceDigestMemo(sampleBytes = 1024)
        val f = file(5_000)
        repeat(3) { assertEquals(ExportCacheKey.digestFile(f), memo.digest(f.path, { null }, { f.inputStream() })) }
        assertEquals(3, memo.fullDigests)
    }

    @Test fun smallFileShorterThanSamples() {
        val memo = SourceDigestMemo(sampleBytes = 1024)
        val f = file(1_500)
        assertEquals(ExportCacheKey.digestFile(f), memo.of(f))
        assertEquals(ExportCacheKey.digestFile(f), memo.of(f))
        assertEquals(1, memo.fullDigests)
    }

    @Test fun differentUrisDoNotShare() {
        val memo = SourceDigestMemo(sampleBytes = 1024)
        val a = file(10_000, seed = 1)
        val b = file(10_000, seed = 2)
        assertEquals(ExportCacheKey.digestFile(a), memo.of(a))
        assertEquals(ExportCacheKey.digestFile(b), memo.of(b))
    }
}
