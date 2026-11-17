/*
 * Spektrafilm for Android — native engine: print-side Highlight Expansion (EDR).
 * Copyright (C) 2026 Spektrafilm Android contributors.
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version. See <https://www.gnu.org/licenses/>.
 *
 * Port of Silverphase v1.2.5 (GPL-3.0-or-later) engine/src/shaders/nodes.metal
 * spk_edr + pipeline.cpp node_edr / its baked parameters. Silverphase calls it
 * "Highlight Expansion" (wire key extended_dynamic_range). Each colour print
 * profile carries a measured `edr_tone_map`: native linear output Y -> the EDR
 * master's linear Y, identity between the paper's toe and shoulder, a log2 table
 * outside. The table's top lands below 1 (Portra Endura: 2^-0.059), so it is an
 * SDR re-expansion of the print's compressed ends and needs no HDR display.
 *
 * Applied to the completed linear print scan (after output gamut compression,
 * scanner blur and unsharp) immediately before the output CCTF, as Silverphase
 * applies it before node_cctf. Per pixel: y = max(Yrow . rgb, 0); outside
 * [toe, shoulder] mapped = 2^lerp(table, u); rgb *= mapped / (y + 1e-10) -- the
 * scale is applied to every pixel, exactly as the kernel does.
 */
#ifndef SPK_MODEL_EDR_TONE_MAP_H
#define SPK_MODEL_EDR_TONE_MAP_H

#include "profiles/profile.h"
#include "spektra.h"  // spk_color_space

namespace spk {

// The Y row of the output space's own RGB -> XYZ (no adaptation; Silverphase
// matrix_RGB_to_XYZ(output_color_space, nullptr)), indexed by spk_color_space.
const double* edr_luminance_row(spk_color_space cs);

// Apply the tone map in place to one linear RGB triple in `cs`.
void edr_apply(const EdrToneMap& map, const double yrow[3], double rgb[3]);

}  // namespace spk

#endif  // SPK_MODEL_EDR_TONE_MAP_H
