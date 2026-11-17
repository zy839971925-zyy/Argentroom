#!/usr/bin/env python3
"""Argentroom 1.0.1: write app/src/main/assets/legal/oss_components.json, the About screen's
open-source component list, from the optimized runtime classpath in app/gradle.lockfile plus
the native libraries and data sets the build bundles. Rerun after any dependency change."""
import json, pathlib, re
ROOT = pathlib.Path(__file__).resolve().parents[2]
APACHE = ("Apache-2.0", "https://www.apache.org/licenses/LICENSE-2.0")
def maven_license(group):
    # Every artifact on the shipped classpath is Apache-2.0 (checked group by group, 1.0.1).
    return APACHE
def maven_url(g, a):
    if g.startswith("androidx."): return "https://developer.android.com/jetpack/androidx/releases/" + g.split(".")[1]
    if g.startswith("org.jetbrains.kotlinx"): return "https://github.com/Kotlin/" + a.split("-")[0].replace("kotlinx","kotlinx.")
    if g.startswith("org.jetbrains.kotlin"): return "https://github.com/JetBrains/kotlin"
    if g == "org.jetbrains": return "https://github.com/JetBrains/java-annotations"
    if g.startswith("com.google.ai.edge.litert"): return "https://github.com/google-ai-edge/LiteRT"
    if g == "com.google.guava": return "https://github.com/google/guava"
    if g == "com.squareup.okio": return "https://github.com/square/okio"
    if g == "com.google.dagger": return "https://github.com/google/dagger"
    if g == "com.google.errorprone": return "https://github.com/google/error-prone"
    if g == "com.google.j2objc": return "https://github.com/google/j2objc"
    if g == "com.google.auto.value": return "https://github.com/google/auto"
    if g == "org.jspecify": return "https://github.com/jspecify/jspecify"
    if g in ("javax.inject",): return "https://github.com/javax-inject/javax-inject"
    if g == "jakarta.inject": return "https://github.com/jakartaee/inject"
    return ""
maven = []
for line in (ROOT / "app/gradle.lockfile").read_text().splitlines():
    if "=" not in line or "optimizedRuntimeClasspath" not in line.split("=", 1)[1]: continue
    g, a, v = line.split("=", 1)[0].split(":")
    lic, lurl = maven_license(g)
    maven.append({"name": f"{g}:{a}", "version": v, "license": lic, "url": maven_url(g, a), "kind": "library"})
native_and_data = [
    {"name": "spektrafilm (film model, engine port)", "version": "3bb2c2d / ad5c8d2 profiles", "license": "GPL-3.0-or-later (code), CC BY-SA 4.0 (profiles, LUTs)", "url": "https://github.com/andreavolpato/spektrafilm", "kind": "engine"},
    {"name": "Spektrafilm for Android (base app)", "version": "1.0.0", "license": "GPL-3.0 + attribution term (GPLv3 7(b))", "url": "https://github.com/thetechgeekko/Spektrafilm-android", "kind": "app"},
    {"name": "Silverphase (UI, film rebate, halation PSF, CineStill 800T alias, B&W profiles)", "version": "1.2.5", "license": "GPL-3.0-or-later; artwork CC BY-SA 4.0", "url": "https://github.com/JamesQiu2005", "kind": "app"},
    {"name": "SpektraLab (B&W profiles, paper grades)", "version": "445e314", "license": "GPL-3.0", "url": "https://github.com/JamesQiu2005", "kind": "data"},
    {"name": "spektrafilm OFX (Vulkan orchestration reference)", "version": "", "license": "GPL-3.0", "url": "https://github.com/andreavolpato/spektrafilm", "kind": "engine"},
    {"name": "colour-science (reference colour maths)", "version": "", "license": "BSD-3-Clause", "url": "https://github.com/colour-science/colour", "kind": "reference"},
    {"name": "LibRaw", "version": "0.22.2", "license": "LGPL-2.1-only (chosen route; CDDL-1.0 also offered)", "url": "https://github.com/LibRaw/LibRaw", "kind": "native"},
    {"name": "libjpeg-turbo", "version": "3.1.4", "license": "IJG, BSD-3-Clause, zlib", "url": "https://github.com/libjpeg-turbo/libjpeg-turbo", "kind": "native"},
    {"name": "Vulkan headers / loader (system)", "version": "1.1+", "license": "Apache-2.0 OR MIT", "url": "https://github.com/KhronosGroup/Vulkan-Headers", "kind": "native"},
    {"name": "MediaPipe Selfie Multiclass 256x256 (float32 v1)", "version": "1", "license": "Apache-2.0", "url": "https://ai.google.dev/edge/mediapipe/solutions/vision/image_segmenter", "kind": "model"},
    {"name": "Material Symbols", "version": "", "license": "Apache-2.0", "url": "https://github.com/google/material-design-icons", "kind": "assets"},
    {"name": "Elle Stone's ICC profiles", "version": "", "license": "CC BY-SA 3.0 Unported", "url": "https://github.com/ellelstone/elles_icc_profiles", "kind": "data"},
    {"name": "Compact ICC Profiles (Clinton Ingram)", "version": "", "license": "MIT", "url": "https://github.com/saucecontrol/Compact-ICC-Profiles", "kind": "data"},
    {"name": "Liberation Fonts (film-rebate edge glyphs)", "version": "", "license": "SIL OFL 1.1", "url": "https://github.com/liberationfonts", "kind": "assets"},
]
out = {"schema": "argentroom.oss_components.v1", "components": native_and_data + sorted(maven, key=lambda x: x["name"])}
p = ROOT / "app/src/main/assets/legal/oss_components.json"
p.write_text(json.dumps(out, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
print(p, len(out["components"]))
