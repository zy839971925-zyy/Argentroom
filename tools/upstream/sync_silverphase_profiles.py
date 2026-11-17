#!/usr/bin/env python3
"""Rebaseline the bundled argentroom profiles + neutral print filters to Silverphase v1.2.5.

Argentroom for Android, GPLv3. Film modeling powered by spektrafilm.

Silverphase v1.2.5 (commit 061fc495, engine/resources/) ships upstream spektrafilm's refit
profiles and neutral print filter database (andreavolpato/spektrafilm ad5c8d2 "chore: refit
and update license of profiles", 2026-05-28; unchanged to 3bb2c2d), with its own measured
``data.edr_tone_map`` tables added to the colour papers. Android shipped the earlier oracle
c1d0e44 fit (2026-05-23): density curves up to 0.43 density apart and neutral filters up to
83 CC apart, i.e. a different colour and tone on every film/paper pair than the iOS app.

This copies, for every profile Android bundles that Silverphase ships in engine/resources/
profiles, the Silverphase JSON verbatim (json.dumps(indent=4), as the bundle stores it), and
replaces every neutral-filter value Silverphase has with its value. Profiles only Silverphase's
product layer carries (B&W films, Multigrade) are already identical and are left alone; the
derived CineStill 800T is regenerated afterwards by derive_profiles.py. The profile data are
CC BY-SA 4.0 upstream (see their metadata.license); credit and licence are kept in the files.

    python3 tools/upstream/sync_silverphase_profiles.py <silverphase-checkout> [--check]
"""
from __future__ import annotations

import argparse
import json
import pathlib
import sys

REPO = pathlib.Path(__file__).resolve().parents[2]
ASSETS = REPO / "engine/spektra-core/src/main/assets/spektra"
PROFILES = ASSETS / "profiles"
FILTERS = ASSETS / "filters/neutral_print_filters.json"


def planned(sp_root: pathlib.Path) -> dict[pathlib.Path, str]:
    src = sp_root / "engine/resources"
    out: dict[pathlib.Path, str] = {}
    for dst in sorted(PROFILES.glob("*.json")):
        s = src / "profiles" / dst.name
        if s.exists():
            text = s.read_text(encoding="utf-8")
            json.loads(text)  # must parse
            out[dst] = text
    ours = json.loads(FILTERS.read_text(encoding="utf-8"))
    theirs = json.loads((src / "neutral_print_filters.json").read_text(encoding="utf-8"))
    for paper, by_ill in ours.items():
        for ill, by_film in by_ill.items():
            for film in by_film:
                v = theirs.get(paper, {}).get(ill, {}).get(film)
                if v is not None:
                    by_film[film] = v
    out[FILTERS] = json.dumps(ours, indent=4)
    return out


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("silverphase", type=pathlib.Path)
    ap.add_argument("--check", action="store_true")
    a = ap.parse_args()
    plan = planned(a.silverphase)
    drift = [p for p, t in plan.items() if p.read_text(encoding="utf-8") != t]
    if a.check:
        for p in drift:
            print(f"drift: {p.relative_to(REPO)}")
        return 1 if drift else 0
    for p in drift:
        p.write_text(plan[p], encoding="utf-8")
        print(f"wrote {p.relative_to(REPO)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
