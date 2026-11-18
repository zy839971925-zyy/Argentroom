# Argentroom R5 — performance round report / 性能第五轮报告

Branch `perf/r5-export-pipeline`, rebased onto `main` a70d7e2 (v1.0.2 + CI fix b8b2668 + package rename a70d7e2). Release target **v1.1.0 (versionCode 22)**.

> **Measurement labels / 数据口径。** Every number below comes from a **host aarch64 Linux sandbox**
> (2 vCPU Neoverse-V2, ~8 GB, shared with Gradle/NDK builds — *contended*), with clang 19 + libc++
> standing in for the NDK, or from the host JVM. **None of it is an Android cold-export time.**
> Device validation is still open (see §6). 所有数据来自主机沙箱（非安卓设备、非冷启动导出），仅作相对比较。

## 1. Branch structure

| Commit | What |
|---|---|
| 39769fa | perf(cache): reuse confirmed source SHA-256 across export and idle pre-render |
| 0d01058 | perf(cache): keep export's film render for post-param re-export; throttle idle pre-render |
| c993fd4 | perf(export): hash bitmap stage while writing; band-wise ColorGrade |
| 8f12685 | perf(memory): skip GPU context teardown when the budget shows no GPU bytes |
| 8729cdc | engine(gpu): device-resident filming expose chain on the Fast GPU route |
| 1569d75 | engine(export): exact band window for the print side of plain final bands (P0-B) |
| c173be2 | engine(grain): batched mt19937 behind StatsRng's Exact generator |
| f35b2b0 | rebase fixup: keep main's Argentroom temp-file prefixes and headers in perf-touched files |
| 891eae0 | ci: also install platforms;android-36 (library modules compile against 36); mark gradlew executable |
| (this commit) | release: 1.1.0 / 22, CHANGELOG, this report |

Commits are cherry-picked (`-x`) from the original sandbox branches; per-change commit IDs quoted below are the original ones.

**No rendering defaults or user-visible settings defaults were changed this round.** The engine
switches added for A/B (`SPK_BAND_WINDOW=0`, `SPK_GPU_RESIDENT=0`) are environment overrides only;
both new paths are on by default because they are exact.

## 2. Engine changes / 引擎

### 2.1 Exact band window (b915eee, P0-B)
A plain final band of a banded export keeps only its core rows, but print expose/develop, `scan()`
and the sublayer grain sampler ran over core + the film side's DIR/halation margin (~1824 rows for a
608-row core at 8192×6144). They now run on core ± their own FIR reach; whole 8192-px RNG blocks
outside the window are not drawn (each block is a pure function of seed+index). Falls back to the
full band whenever any frame/band-wide print dependency or IIR-class blur is present.

### 2.2 Batched mt19937 (81aa538)
`spk::BatchedMt19937` twists/tempers the whole 624-word block at once and serves from a buffer; same
`result_type/min/max`, so the library distributions see the identical word stream. Test
`test_batched_mt19937` (2M words × 6 seeds, 1M uniform+normal draws, StatsRng vs hand-held mt19937)
ALL OK under libc++ and libstdc++.

### 2.3 GPU-resident filming chain (f0e0901)
expose → [highlight boost] → [CineStill headroom, explicit CPU sync] → halation → log10 on the Fast
GPU route now does one source upload and one f32 readback instead of five round trips, and no
full-frame f64 irradiance plane (−24 B/px on that route). Thread-owned resident frames;
all-or-nothing fallback to the staged route (RAII, also on cancel). Strict CPU route untouched.

### 2.4 Measured effect (host, labels as above)
| Measurement | Before | After |
|---|---|---|
| 8192×6144 cinestill_800t / kodak_portra_endura, strict CPU, wall (run alone, no Gradle, 2026-11-18) | 258.8 s (580ccda) | **141.4 s** (R5 engine) |
| same, cpu_user | 384.6 s | 216.4 s |
| same, stages grain / print_expose / scan | 106.7 / 34.6 / 45.3 s | 41.4 / 13.1 / 18.8 s |
| same, VmHWM | 3.19 GB | 3.19 GB (peak unchanged; set by the film side) |
| 8192×6144 portra_400 wall, contended (Gradle on same cores, 2026-11-17) | 380 s | 266 s (window on) |
| uniform draw (mt19937 → batched) | 10.5 ns | 3.3 ns |
| isolated layer_particle_model, 12 MP plane cpu | 0.75 / 0.68 s | 0.67 / 0.60 s (−11 %) |
| whole 12 MP export cpu_user (two interleaved pairs) | 28.85 s | 28.3 s (~−2 %) |
| GPU-resident chain | — | correctness only (lavapipe under `qemu-aarch64 -cpu max`; timings meaningless) |

The 50 MP gain is almost entirely the band window (grain/print/scan stages); mt19937 batching is a
small contributor because the Knuth branch draws only ~mean+1 uniforms per pixel.

## 3. App changes / 应用 (from the perf/r5-app report)
- **Source identity:** full SHA-256 of a 37 MB source 36 ms warm (143 ms cold) vs fingerprint
  (fstat + 2×64 KiB) 0.25–0.5 ms on host JVM. One full source read+hash removed per export that reuses
  a confirmed digest; a miss costs one extra fingerprint.
- **Export render kept for re-export:** adds one payload write (12 B/px, ~150 MB at 12.5 MP) to a first
  export; a re-export that only changes post-engine settings skips decode + simulate, and the redundant
  idle pre-render after an export is suppressed. Not timed on device.
- **Bitmap stage hash-while-write:** removes one full re-read of the encoded stage for PNG-8 and JPEG
  without source EXIF. JPEG with EXIF (default) still re-reads (ExifInterface rewrites). Not timed.
- **Band-wise ColorGrade:** 3 MP HotSpot 2466 vs 2495 ms → **no measurable host gain** (compute-bound in
  CCTF/Oklab). Kept because output is bit-identical; ART gain unverified.
- **Memory:** pre-render gated on thermal headroom and battery; GPU context teardown skipped when the
  budget holds 0 GPU bytes. A native "free buffers, keep pipelines" API is the real fix (open).

## 4. Identity and test evidence / 一致性与测试
- **50 MP strict CPU byte-identity (2026-11-18):** 8192×6144 cinestill_800t on kodak_portra_endura,
  bench_export synthetic frame, clang 19 libc++ `-O3 -ffast-math -fno-finite-math-only`, Vulkan built in
  but strict mode 0, each run alone: 580ccda **f3fec07692a6176362389e3460cbd7f62f27ed4310b7ecbe4f2959da76e72a12**
  = merged HEAD (window on, default) **f3fec076…72a12**. 5 bands.
  Note: an earlier session recorded `a0d1cc18…` for a window-on run of this case; its build script was
  lost with /tmp, so that hash could not be reproduced or explained. Today's A/B used one toolchain and
  one flag set for both sides and is the evidence of record.
- Earlier sessions (same toolchain family): 8192×6144 portra_400 80305ad6… identical across 580ccda,
  window off and window on; 4000×3000 portra_400 and cinestill_800t identical (window inactive);
  12 MP portra d87fd786…, cinestill 8c644da7… unchanged with batched mt19937.
- `test_band_render` ALL PASS; `test_band_seam_sweep` (CPU-route build) ALL OK (earlier session).
- **GPU gates at merged HEAD under qemu-aarch64 -cpu max + lavapipe (libc++, ship flags):**
  `test_pointwise_chain` ALL OK (vulkan_available=1, 154 s), `test_halation_gpu` ALL OK (engaged=1, 345 s),
  `test_filming_stage_gpu` ALL OK (engaged=1, resident vs non-resident worst |Δ| = 0, 15 s). Run 2026-11-18,
  one ship-flag leg only (CI's `run_gpu_gates.sh` also runs an `-O2` g++/libstdc++ leg — not run here).
- GPU-resident chain output byte-identical to the staged Fast GPU route for portra_400 and cinestill_800t
  with boost + headroom (earlier session, lavapipe).
- **App unit tests (non-Robolectric, after the rename, JVM):** SourceDigestMemo 7, PerfR5 6,
  RenderPayloadCache 12, ExportCache 17, ExportCacheKey 13, ExportTransaction 22, ColorGrade 7,
  PrerenderMemoryGate 6, PrerenderFlight 6, MemoryReclaimRetry 5, AppMemoryBudget 5, GamutCompress 8 =
  **114 tests, 0 failures**. Robolectric/Compose UI suites compile but cannot run on aarch64 (not run).

## 5. Rejected / 未采纳
- **Rust grain RNG:** a Rust staticlib of the same refill (rustc 1.100, FFI per 624 words) measured
  7.7–10.8 ns vs 3.3 ns per uniform for the inline C++ → not adopted.
- **ColorGrade banding** gives no host gain (kept only because it is bit-identical; see §3).
- **ExportCache skip-rehash on hit:** reverted — breaks fail-closed tests (adopt with wrong digest,
  same-length corruption), an integrity guarantee.
- `lastTrimLevel` in the pre-render gate: rejected (sticky after backgrounding → pre-render disabled forever).
- Lossless compact payload (deflate of float planes): CPU cost > disk saving on phones; payload stays f32.
- Grade/mask/pack fusion: masks need cross-row blur streams; not done.

## 6. CI fix
`main` b8b2668 already moved to `setup-android@v4` / `setup-java@v5` / ubuntu-24.04. R5 adds on top: `platforms;android-36` in the `sdkmanager` list (engine, lib/* and baselineprofile use `compileSdk 36`; only 37.0 was installed) and the executable bit on `gradlew` (stored as 100644, so `./gradlew` cannot run on a fresh checkout). Not yet observed green on GitHub.

## 7. Package rename / 包名变更
The rename to `com.argentroom.*` and the English-only product name were done on `main` (a70d7e2, by the maintainer); R5 is rebased on it and its new/changed files follow the same names (packages, JNI, `argentroom-export-` temp prefixes). **The application ID `com.argentroom.app` cannot upgrade an existing `com.spectrafilm.app` install**; release notes must say so (CHANGELOG does).

## 8. Open items / 待办
- **P0-C GPU scratch pooling** on the Fast GPU route (not started).
- **Native "free buffers, keep pipelines"** API so trimming does not force pipeline rebuilds.
- **RAW copy reduction** (fewer full-frame copies of decoded RAW data; not started).
- **Native TIFF/PNG16 hash-while-write** (needs SHA-256 in the C++ writers; stage still hashed once).
- **Possible bug in the wide-gamut `setPixels` path** — flagged during R5, not investigated or confirmed.
- **GPU band seam sweep** (`test_band_seam_sweep` with gpu_export=1 under qemu) not run this round (>40 min).
- **Device validation:** cold export times, thermals, memory peaks and GPU-resident chain on real
  Android hardware; none of the numbers above substitute for it.
