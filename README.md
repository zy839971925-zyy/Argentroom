<div align="center">

<img src="docs/brand/argentroom-icon.png" width="112" alt="Argentroom app icon">

# Argentroom

**A darkroom in your pocket. Shoot digital, develop it on film.**

English · [简体中文](README.zh-CN.md)

[![Download APK](https://img.shields.io/badge/Download_APK-v1.0.2-c9b38a?style=for-the-badge&logo=android&logoColor=1b1813&labelColor=c9b38a&color=1b1813)](../../releases/latest)

[![Android 7.0+](https://img.shields.io/badge/Android-7.0%2B-c9b38a?labelColor=2a2620)](#install)
[![arm64-v8a](https://img.shields.io/badge/ABI-arm64--v8a-c9b38a?labelColor=2a2620)](#install)
[![License: GPL-3.0-or-later](https://img.shields.io/badge/license-GPL--3.0--or--later-c9b38a?labelColor=2a2620)](LICENSE)
[![Engine](https://img.shields.io/badge/engine-C%2B%2B_%C2%B7_Vulkan-c9b38a?labelColor=2a2620)](#how-it-works)

<sub>An unofficial, independent derivative of <a href="https://github.com/thetechgeekko/Spektrafilm-android">Spektrafilm for Android</a>, with the darkroom interface of <a href="https://github.com/akinal5765-byte/Silverphase">Silverphase</a>.<br>
Film modeling powered by <a href="https://github.com/andreavolpato/spektrafilm">spektrafilm</a>. No account, no ads, no tracking.</sub>

</div>

---

## Why Argentroom

Most film filters are a colour LUT that nudges pixels toward a mood. Argentroom runs the
darkroom itself: every pixel becomes a light spectrum, exposes a measured film stock, is
projected through an enlarger onto a measured paper, and is scanned back out. The result keeps
the things a LUT cannot: how a stock rolls off its highlights, how halation blooms red around
a street lamp, how grain sits in the shadows.

*Argentroom* means "the room of silver": the darkroom where the image is made.

## Highlights

<table>
  <tr>
    <td width="33%" valign="top">
      <h4>🎞️ 25 films · 9 papers</h4>
      Portra, Ektar, Gold, Vision3, Velvia, Provia, Kodachrome, Tri-X, HP5, CineStill 800T and
      more, each simulated from measured spectral data. Any colour film prints on any paper.
    </td>
    <td width="33%" valign="top">
      <h4>🖤 A real darkroom UI</h4>
      The Silverphase v1.2.5 interface ported to Android: film-strip looks, a quiet dark
      workspace, continuous panel motion, and a physical film rebate around the frame.
    </td>
    <td width="33%" valign="top">
      <h4>✨ Halation & grain, faithfully</h4>
      Silverphase's grain parameters and highlight headroom for halation, so CineStill's red
      glow appears where it should. See <a href="#fidelity-notes">fidelity notes</a>.
    </td>
  </tr>
  <tr>
    <td width="33%" valign="top">
      <h4>📷 Built-in camera</h4>
      Shoot straight into the darkroom and develop each frame with the look you picked.
    </td>
    <td width="33%" valign="top">
      <h4>🗂️ Library & full editor</h4>
      Exposure, white balance, tone curves, masks (radial, gradient, colour, on-device skin),
      crop, frames, history. RAW and DNG through LibRaw; originals are never modified.
    </td>
    <td width="33%" valign="top">
      <h4>🛠️ Diagnostics you can share</h4>
      Settings → Diagnostics & error logs exports a single text report (device, GPU, process
      exits, recent log) to attach to an issue. Nothing leaves the phone unless you send it.
    </td>
  </tr>
</table>

## Install

1. Download **`Argentroom-v1.0.2.apk`** from the [latest release](../../releases/latest).
2. Open it and allow installs from your browser or file manager when Android asks.
3. Pick a photo, tap a film, export.

Requirements: Android 7.0 (API 24) or later, a 64-bit ARM phone (`arm64-v8a`), Vulkan 1.1 GPU
recommended (Qualcomm Adreno and Arm Mali / Immortalis are both supported).

> [!IMPORTANT]
> Release builds are signed with Argentroom's own release key. If you installed an earlier
> **preview / test build**, uninstall it first: Android refuses to update an app across
> different signing keys. Uninstalling removes that build's local edits.

Verify the download against `SHA256SUMS.txt` attached to each release:

```bash
sha256sum -c SHA256SUMS.txt
```

## How it works

```
 Your photo ─▶ Spectral upsampling ─▶ Film exposure ─▶ Development ─▶ Enlarger ─▶ Paper ─▶ Scan ─▶ Export
               (RGB → spectrum)       (measured        (couplers,      (CMY        (measured  (to your
                                       sensitivities)   grain,          filtration) dyes)      colour space)
                                                        halation)
```

The engine is a native C++ port of Andrea Volpato's [spektrafilm](https://github.com/andreavolpato/spektrafilm),
inherited from Spektrafilm for Android and checked stage by stage against the Python original.
Heavy stages run on the GPU through Vulkan compute, with the CPU engine as the reference.

## Fidelity notes

Argentroom follows spektrafilm and Silverphase v1.2.5 as its references and says so where it
departs from them:

| Area | Behaviour | Source |
|---|---|---|
| Film and paper profiles | 34 profiles, redistributed unmodified | spektrafilm / Silverphase |
| Grain | Silverphase v1.2.5 `GrainParams` | Silverphase |
| Halation headroom | 4 EV default for stills | Silverphase |
| Headroom edge feathering | **Argentroom-specific.** The headroom gain is feathered at its edge so bright areas bloom smoothly instead of showing a step. Labelled in the in-app help. | Argentroom |

## Build from source

```bash
git clone https://github.com/zy839971925-zyy/Argentroom.git
cd Argentroom
./gradlew :app:assembleOptimized -Pspk.abis=arm64-v8a   # everyday build, public debug key
./gradlew :app:assembleRelease  -Pspk.abis=arm64-v8a    # unsigned unless keystore.properties exists
```

JDK 21, Android SDK platform 37, build-tools 36.0.0, NDK 28.2.13676358 and CMake 3.22.1 are required. To sign a release locally, create
`keystore.properties` in the project root (it is git-ignored, never commit it):

```properties
storeFile=/absolute/path/argentroom-release.p12
storePassword=…
keyAlias=argentroom
keyPassword=…
```

More in [CONTRIBUTING.md](CONTRIBUTING.md) and [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Reporting a bug

Open an [issue](../../issues/new/choose) and attach the report from
**Settings → Diagnostics & error logs → Export**. Please include your phone model and what you
did just before the problem.

## Credits

Argentroom stands on the work of others. Please star them.

- **[spektrafilm](https://github.com/andreavolpato/spektrafilm)** by **Andrea Volpato**: the
  spectral film science, profiles and LUTs.
- **[Spektrafilm for Android](https://github.com/thetechgeekko/Spektrafilm-android)** by
  **Akshay Sharma**: the Android app and C++/Vulkan engine Argentroom is built from.
- **[Silverphase](https://github.com/akinal5765-byte/Silverphase)** by its contributors
  (akinal5765-byte): the darkroom interface, film rebate, grain and halation behaviour, and
  film artwork (CC BY-SA 4.0).
- **[SpektraLab](https://github.com/JamesQiu2005/SpektraLab)** and
  **[SpektraLab-Android](https://github.com/SAINTTaiYi/SpektraLab-Android)**: Vulkan reference work.
- **[colour-science](https://www.colour-science.org/)**, **[LibRaw](https://www.libraw.org/)**,
  Google **Material Symbols**, and the **[pixls.us](https://pixls.us)** community.

Full notices: [NOTICE.md](NOTICE.md) and the in-app About screen.

## License

Code: **GPL-3.0-or-later** ([LICENSE](LICENSE)). spektrafilm profile data and LUTs, and the
Silverphase artwork: **CC BY-SA 4.0**. Bundled fonts: SIL OFL. Details in [NOTICE.md](NOTICE.md).

Argentroom is an **unofficial derivative**. It is not affiliated with or endorsed by the
spektrafilm, Spektrafilm for Android or Silverphase authors. Film stock names are trademarks of
their owners and are used only to describe what is simulated.

Under GPLv3 §7(b), any work based on this one must keep this attribution visible:

> **Spektrafilm for Android** by **Akshay Sharma** — <https://github.com/thetechgeekko/Spektrafilm-android>
> Film modeling powered by **spektrafilm** (Andrea Volpato) — <https://github.com/andreavolpato/spektrafilm>

<div align="center"><sub>Made with patience, silver and light.</sub></div>
