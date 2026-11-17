# Argentroom architecture (1.0.1)

Argentroom is one Android app module (`app/`) on top of a native film engine
(`engine/spektra-core`) and three small native libraries (`lib/libraw`, `lib/pngwriter`,
`lib/tiffwriter`). This page is the map: where each concern lives and which way dependencies
point. When you add a file, put it in the package whose one-line job it shares.

## Layers

```
app shell (com.argentroom.app)          MainActivity, AppContainer, AppRoot/AppHost, routes
   |
features   editor  camera  library  settings  diagnostics  update
   |          \       |       /
domain     look (film, print, params, presets, recipes, rebate)   export (encode, files, gain map)
   |                         \                     /
render     render (engine bridge, previews, caches, GPU memory)   source (decode, RAW, access)
   |
ui         ui (theme, widgets, symbols) and silverphase (Silverphase design system, motion)
   |
engine     engine/spektra-core: SpektraEngine (Kotlin JNI facade) -> C++ pipeline -> Vulkan
```

Arrows point down: a feature may use the layers below it, never a sibling feature's screens.
`editor` and `camera` both reach the engine only through `render` and `look`.

## Packages

| Package | Job |
|---|---|
| `com.argentroom.app` | App shell only: activity, DI container, navigation routes, launcher shortcuts, the atomic JSON store. |
| `.ui` | App-wide design primitives: theme, widgets, Material Symbols, dropdowns, sliders, adaptive panes. |
| `.silverphase` | The Silverphase v1.2.5 design system and motion port (cards, panels, SpMotion, haptics). |
| `.look` | What a picture looks like: parameter state and groups, film/print catalog and stock previews, Silverphase print pairing (`SilverPrint`), curves, colour grade, white balance, presets, recipes, film rebate and captions. |
| `.render` | Talking to the engine: `EngineHolder`, the image pipeline, display packing, GPU LUT preview, render caches and publication gates, prerender, ROI/zoom, memory budget, skin segmenter. |
| `.source` | Getting pixels in: source access and grants, decoding, RAW (LibRaw), MCRAW, shared-file copies, GPS access. |
| `.export` | Getting pixels out: export runtime and foreground service, progress model, transaction and cache, EXIF, Ultra HDR gain map, output colour/CCTF/sharpening, batch and gallery export, CLF/LUT writer. |
| `.editor` | The editor screen and its session: view models, panels, tools, history, overlays, the editor's export glue. |
| `.camera` | The in-app film camera (CameraX), its develop step and storage. |
| `.masks` | Local masks and their compositor. |
| `.library` | Library, albums, phone gallery, viewer, slideshow, versions. |
| `.settings` | Settings, About (with the open-source component list), help, language, welcome. |
| `.diagnostics` | Crash capture, the persistent event log, Android exit history, device/GPU summary, the report export, release test probes (Ticket139*). |
| `.update` | The (currently disabled) self-update path, kept for the GitHub release channel. |

## Engine

`engine/spektra-core/src/main/cpp` is organised by pipeline stage, mirroring upstream
spektrafilm: `runtime/stages/{filming,printing,scanning}.cpp` drive the stages, `model/`
holds the physical models (couplers, diffusion, grain), `kernels/` the CPU kernels, `gpu/`
the Vulkan compute path (`vulkan_compute.cpp` + `*.comp` shaders) and `runtime/` timing and
memory budget. Every GPU kernel has a CPU fallback with the same numerics; a fallback is logged
with its reason and shows in the stage-timing line in the diagnostics report.

## Rules that keep it clean

1. One file, one job; a file past ~800 lines is a split candidate.
2. Film behaviour follows upstream spektrafilm / Silverphase exactly; app-side additions are
   named as such in the code and never alter the upstream numerics.
3. Diagnostics go through `Diag` (logcat + the persistent event log), never `Log` directly.
4. Package moves are mechanical (`tools/arch/restructure.py`); class names in the manifest and
   `proguard-rules.pro` are rewritten by the same script.
