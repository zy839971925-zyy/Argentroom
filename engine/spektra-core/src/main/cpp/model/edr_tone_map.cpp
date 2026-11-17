/*
 * Spektrafilm for Android — native engine: print-side Highlight Expansion (EDR).
 * Copyright (C) 2026 Spektrafilm Android contributors. GPLv3.
 * Port of Silverphase v1.2.5 spk_edr; see edr_tone_map.h.
 */
#include "model/edr_tone_map.h"

#include <algorithm>
#include <cmath>

namespace spk {
namespace {

// The Y rows of model/gamut_compression.cpp's kRgbToXyz (colour.RGB_to_XYZ with
// the space's own whitepoint), bit-exact.
constexpr double kYRow[6][3] = {
    {0x1.b367a0f9096bcp-3, 0x1.6e2eb1c432ca5p-1, 0x1.27bb2fec56d5dp-4},   // sRGB
    {0x1.3079e59f2ba9dp-2, 0x1.41355475a31a5p-1, 0x1.3463497b7414ap-4},   // Adobe RGB
    {0x1.26e978d4fdf3bp-2, 0x1.6c7e28240b780p-1, 0x1.a36e2eb1c4333p-14},  // ProPhoto
    {0x1.0d0148ccf66f1p-2, 0x1.5b22902fd967ep-1, 0x1.e5ccb69ab60a3p-5},   // BT.2020
    {0x1.6038bdb33fb82p-2, 0x1.74d22fc5e7ec9p-1, -0x1.277474fc3e450p-4},  // ACES2065-1
    {0x1.b367a0f9096bcp-3, 0x1.6e2eb1c432ca5p-1, 0x1.27bb2fec56d5dp-4},   // linear sRGB
};

}  // namespace

const double* edr_luminance_row(spk_color_space cs) {
    const int i = static_cast<int>(cs);
    return kYRow[(i >= 0 && i < 6) ? i : 0];
}

void edr_apply(const EdrToneMap& map, const double yrow[3], double rgb[3]) {
    const double y = std::max(yrow[0] * rgb[0] + yrow[1] * rgb[1] + yrow[2] * rgb[2], 0.0);
    double mapped = y;
    if (y > 0.0 && (y < map.toe_y || y > map.shoulder_y)) {
        const size_t count = std::max<size_t>(map.target_log2_y.size(), 2);
        const double span = map.domain_log2_y[1] - map.domain_log2_y[0];
        double u = (std::log2(y) - map.domain_log2_y[0]) * (1.0 / span);
        u = std::clamp(u, 0.0, 1.0) * static_cast<double>(count - 1);
        const size_t i0 = std::min(static_cast<size_t>(std::floor(u)), count - 2);
        const double t = u - static_cast<double>(i0);
        const double a = map.target_log2_y[i0], b = map.target_log2_y[i0 + 1];
        mapped = std::exp2(a + (b - a) * t);
    }
    const double scale = mapped / (y + 1e-10);
    rgb[0] *= scale;
    rgb[1] *= scale;
    rgb[2] *= scale;
}

}  // namespace spk
