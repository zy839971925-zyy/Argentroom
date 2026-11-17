#!/usr/bin/env python3
"""Argentroom 1.0.1 package-by-feature restructure (behaviour-preserving, mechanical).

Moves the flat com.argentroom.app files into feature packages (see docs/ARCHITECTURE.md),
rewrites each file's package line, rewrites explicit imports of moved top-level symbols, adds
star imports of the root and feature packages so cross-feature references keep resolving,
and rewrites class names in AndroidManifest.xml and proguard-rules.pro.

Usage: restructure.py [--apply]   (dry run prints the plan)
"""
import re, sys, pathlib, subprocess

ROOT = pathlib.Path(__file__).resolve().parents[2]
SRC = ROOT / "app/src/main/java/com/argentroom/app"
BASE = "com.argentroom.app"

# file stem -> feature package. Unlisted files stay in the root package (app shell: activity,
# application, container, navigation, routes, launcher shortcuts, persistence primitives).
GROUPS = {
    "ui": """Theme Widgets MaterialSymbols Dropdowns ConnectedChoice Sliders AppUiHelpers
        DesignSystemPreviews LargeTextPreviews FoldPosture AdaptivePanes CategoryIcons""",
    "diagnostics": """Diagnostics DiagnosticsRecorder DiagnosticsScreen DebugStrictMode EditorProbe
        Ticket139Contracts Ticket139EditorProbe Ticket139EditorTestBridge Ticket139Fixtures
        Ticket139MemorySignature Ticket139ParamsOracle Ticket139ProcessFiles""",
    "settings": """SettingsScreen SettingsPanes SettingsViewModel SettingsDataStore AppSettings
        AppLanguage AboutScreen HowToUseScreen ReleaseNotes Welcome Links QuickHelp""",
    "update": """AppUpdater UpdateController UpdateDialog UpdateDownloader UpdateInstaller
        UpdateVerifier UpdateViewModel""",
    "render": """EngineHelpers EngineHolder ImagePipeline DisplayBitmaps LutGpuPreview GpuMemoryRelease
        AppMemoryBudget RenderPayloadCache RenderPublicationGate PendingRenderOutcomes PrerenderFlight
        InteractiveRenderSnapshot PreviewLeases RoiPatch RoiTiming ZoomPrewarm GradeCache
        NativeCancellation SceneHighlightTap HdrSceneGlow ProxyDiskCache DecodedSourceCache
        AdaptivePreview LiteRtSkinSegmenter SkinModelStore""",
    "source": """SourceAccess SourceDecoding SourceDetect RawDecode RawDecodeAdmission RawCameraPreview
        McrawContainer SharedSourceCopies ImageDrop GpsMetadataAccess""",
    "export": """ExportCache ExportCacheKey ExportClock ExportDevelopTray ExportExif ExportForegroundService
        ExportOptions ExportPresets ExportProgress ExportProof ExportSharePill ExportSheet
        ExportTransaction ExportViewModel ExportWorkRuntime BatchExport GalleryExport HdrGainMap
        OutputCctf OutputDescriptor OutputSharpening ColorManagement ClfWriter VerifiedDocumentWriter""",
    "look": """ParamGroups ParamsState ParamHelp OperationalParamLimits ContrastCurve ToneCurve ColorGrade
        CreativeWhiteBalance LocalWhiteBalance AutoExposureControl SkinFields GamutCompress Oklab
        SilverPrint FilmStockBalance PresetAmount Presets BuiltInPresets PresetRepository
        PresetViewModel Recipes RecipeRepository RecipeDocumentCodec RecipeWorkRuntime LookSync
        StockCatalog StockPreviews StockBrowser FilmStockStrip PlaceholderLook ProfileCurvesScreen
        FilmBorder FilmBorderRenderer FilmEdgeCapture FrameCaptions DateStampText HalfFramePair""",
    "library": """Library LibraryExport LibraryFolderAndAlbums LibraryGrid LibraryLayout LibraryScreen
        LibrarySidecars LibraryThumbnailState LibraryViewModel Albums AlbumImport AlbumScreens
        GalleryScreen PhoneGallery PhotoBrowse PhotoScrub PhotoSwipe PhotoInfo PhotoInfoSheet
        Slideshow FolderStrip Viewer FullScreen PhotoVersions VersionsPanel OpenPlaceholder""",
    "editor": None,  # every remaining Editor*.kt plus the editor-only widgets below
}
EDITOR_EXTRA = """EditHistory EditHistoryLabels EditHistorySheet CategoryRail ToolRail CropTool Straighten
    StraightenRuler CompareSlider ClippingOverlay Histogram PixelSample PixelSampleOverlay
    MaskGeometryOverlay MaskPanel PreviousEdit Instruments InstrumentControls FrameTab Rotation"""

def plan():
    m = {}
    for pkg, names in GROUPS.items():
        if names:
            for n in names.split(): m[n] = pkg
    for n in EDITOR_EXTRA.split(): m[n] = "editor"
    for f in SRC.glob("Editor*.kt"):
        m.setdefault(f.stem, "editor")
    missing = [n for n in m if not (SRC / f"{n}.kt").exists()]
    if missing: sys.exit(f"unknown files in GROUPS: {missing}")
    return m

DECL = re.compile(r"^(?:@[\w.]+(?:\([^)]*\))?\s+)*(?:(?:public|internal|private|protected|inline|value|data|sealed|enum|"
                  r"annotation|abstract|open|fun\s+interface|const|lateinit|operator|infix|tailrec|suspend|external|"
                  r"actual|expect)\s+)*(class|interface|object|fun|val|var|typealias)\s+(?:<[^>]*>\s*)?"
                  r"(?:[\w.<>?, *]+\.)?(`?\w+`?)", re.M)

def top_level_symbols(text):
    syms = set()
    for line in text.splitlines():
        if not line or line[0].isspace(): continue
        if line.startswith("private "): continue
        mm = DECL.match(line)
        if mm: syms.add(mm.group(2).strip("`"))
    return syms

def main(apply):
    mapping = plan()
    feature_pkgs = sorted(set(mapping.values()))
    # symbol -> new package, and facade (FileKt) -> new package
    sym_pkg, facade_pkg = {}, {}
    for stem, pkg in mapping.items():
        text = (SRC / f"{stem}.kt").read_text()
        jvm = re.search(r'@file:JvmName\("(\w+)"\)', text)
        facade_pkg[jvm.group(1) if jvm else stem + "Kt"] = pkg
        for s in top_level_symbols(text):
            sym_pkg.setdefault(s, set()).add(pkg)
    print(f"{len(mapping)} files into {feature_pkgs}; {len(sym_pkg)} symbols")
    if not apply: return

    star = [f"import {BASE}.*"] + [f"import {BASE}.{p}.*" for p in feature_pkgs]
    imp_re = re.compile(rf"^import {re.escape(BASE)}\.(\w+)(\s+as\s+\w+)?\s*$", re.M)

    def fix_imports(text, own_pkg):
        def repl(mm):
            name = mm.group(1)
            pk = sym_pkg.get(name)
            if pk and len(pk) == 1:
                p = next(iter(pk))
                return f"import {BASE}.{p}.{name}{mm.group(2) or ''}"
            return mm.group(0)
        text = imp_re.sub(repl, text)
        want = [s for s in star if s != (f"import {BASE}.*" if own_pkg == BASE else f"import {BASE}.{own_pkg}.*")]
        add = "\n".join(s for s in want if s not in text)
        if not add: return text
        # after the package line (and any existing imports block start)
        mm = re.search(r"^package [\w.]+\s*$", text, re.M)
        pos = mm.end()
        return text[:pos] + "\n\n" + add + text[pos:]

    # 1) move with git mv and rewrite package line
    for stem, pkg in mapping.items():
        d = SRC / pkg; d.mkdir(exist_ok=True)
        subprocess.check_call(["git", "mv", str(SRC / f"{stem}.kt"), str(d / f"{stem}.kt")], cwd=ROOT)
        p = d / f"{stem}.kt"
        t = p.read_text()
        t, n = re.subn(rf"^package {re.escape(BASE)}\s*$", f"package {BASE}.{pkg}", t, count=1, flags=re.M)
        if n != 1: sys.exit(f"no package line in {p}")
        p.write_text(t)
    # 2) every Kotlin source in the module (main, test, androidTest, screenshotTest, debug)
    for p in (ROOT / "app/src").rglob("*.kt"):
        t = p.read_text()
        mm = re.search(r"^package ([\w.]+)", t, re.M)
        if not mm or not mm.group(1).startswith(BASE): continue
        own = mm.group(1)
        own_pkg = BASE if own == BASE else own[len(BASE) + 1:]
        if own_pkg not in feature_pkgs and own != BASE:
            # pre-existing sub-packages (camera, masks, silverphase): rewrite explicit imports only,
            # then add root + feature stars so former root symbols still resolve
            pass
        p.write_text(fix_imports(t, own_pkg if own_pkg in feature_pkgs else (BASE if own == BASE else "__none__")))
    # 3) manifest + proguard class names
    def rewrite_names(path, dot_relative):
        t = path.read_text()
        def cls(mm):
            name = mm.group(1)
            outer = name.split("$")[0]
            pk = facade_pkg.get(outer) or (next(iter(sym_pkg[outer])) if outer in sym_pkg and len(sym_pkg[outer]) == 1 else None)
            return f"{BASE}.{pk}.{name}" if pk else mm.group(0)
        t = re.sub(rf"{re.escape(BASE)}\.([A-Z][\w$]*)", cls, t)
        if dot_relative:
            def rel(mm):
                name = mm.group(1)
                pk = next(iter(sym_pkg[name])) if name in sym_pkg and len(sym_pkg[name]) == 1 else None
                return f'android:name=".{pk}.{name}"' if pk else mm.group(0)
            t = re.sub(r'android:name="\.([A-Z]\w*)"', rel, t)
        path.write_text(t)
    rewrite_names(ROOT / "app/src/main/AndroidManifest.xml", True)
    rewrite_names(ROOT / "app/proguard-rules.pro", False)
    for x in (ROOT / "app/src/main/res").rglob("*.xml"):
        if BASE + "." in x.read_text(): rewrite_names(x, False)

if __name__ == "__main__":
    main("--apply" in sys.argv)
