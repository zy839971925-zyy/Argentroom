# HANDOFF — R5 (v1.1.0 / versionCode 22)

Branch `perf/r5-export-pipeline`, rebased onto `main` a70d7e2 (fast-forward from `main`). Not released.
Full report: [`docs/PERF_R5_REPORT.md`](docs/PERF_R5_REPORT.md).

## State
- App: source-digest reuse, re-export reuse of the film render, hash-while-write, band-wise ColorGrade,
  pre-render thermal/battery gate, GPU teardown skip, 64-bit payload sizes.
- Engine: exact band window, batched mt19937, GPU-resident filming chain (switches `SPK_BAND_WINDOW=0`,
  `SPK_GPU_RESIDENT=0`).
- CI: + `platforms;android-36`, executable `gradlew`.
- Version 1.1.0 / 22, CHANGELOG entry ("unreleased"). No rendering or settings defaults changed.

## Evidence (host aarch64 sandbox — not Android numbers)
- Strict CPU 50 MP cinestill_800t/endura byte-identical 580ccda vs R5 engine (sha256 f3fec076…72a12);
  258.8 s → 141.4 s wall. 50 MP portra_400 identical (80305ad6…).
- Non-Robolectric app unit tests and GPU gates (qemu + lavapipe): see report §4.

## To release
1. Let CI build the pushed branch / PR; merge to `main`.
2. Device-validate a cold 12 MP and 50 MP export (strict and Fast GPU), memory peak and thermals.
3. Sign with the release key (outside the repo); the committed `debug.keystore` is the public debug key.
4. Release notes lead with: new application ID, no in-place upgrade from `com.spectrafilm.app`.

## Open
P0-C GPU scratch pooling; native free-buffers-keep-pipelines; RAW copy reduction; native TIFF/PNG16
hash-while-write; possible wide-gamut `setPixels` bug; GPU band seam sweep; device validation.
