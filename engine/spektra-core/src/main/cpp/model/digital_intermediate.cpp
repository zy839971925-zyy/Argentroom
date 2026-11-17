/*
 * Argentroom for Android — native engine: Silverphase's Digital Intermediate.
 * Copyright (C) 2026 Argentroom Android contributors. GPLv3.
 *
 * Port of Silverphase v1.2.5 core/digital_intermediate.cpp, shaders/nodes.metal
 * spk_di_encode and modern_UI Canvas/CineonLUT.swift. See the header.
 */
#include "model/digital_intermediate.h"

#include <algorithm>
#include <cmath>
#include <cstring>
#include <mutex>

#include "kernels/parallel.h"
#include "model/color_output.h"
#include "model/spectral.h"

namespace spk {
namespace {

constexpr double kMidgray = 0.184;   // slm::kMidgray
constexpr int kN = kSpectralSamples;  // 81

// Pointer's gamut of real surface colours (Pointer 1980), xy, as colour-science
// ships it (`CCS_POINTER_GAMUT_BOUNDARY`) -- digital_intermediate.cpp kPointer.
const double kPointer[32][2] = {
    {0.6590, 0.3160}, {0.6340, 0.3510}, {0.5940, 0.3910}, {0.5570, 0.4270}, {0.5230, 0.4620},
    {0.4820, 0.4910}, {0.4440, 0.5150}, {0.4090, 0.5460}, {0.3710, 0.5580}, {0.3320, 0.5730},
    {0.2880, 0.5840}, {0.2420, 0.5760}, {0.2020, 0.5300}, {0.1770, 0.4540}, {0.1510, 0.3890},
    {0.1510, 0.3300}, {0.1620, 0.2950}, {0.1570, 0.2660}, {0.1590, 0.2450}, {0.1420, 0.2140},
    {0.1410, 0.1950}, {0.1290, 0.1680}, {0.1380, 0.1410}, {0.1450, 0.1290}, {0.1450, 0.1060},
    {0.1610, 0.0940}, {0.1880, 0.0840}, {0.2520, 0.1040}, {0.3240, 0.1270}, {0.3930, 0.1650},
    {0.4510, 0.1990}, {0.5080, 0.2260}};

bool inside_pointer(double x, double y) {
    bool in = false;
    for (int i = 0, j = 31; i < 32; j = i++) {
        const double xi = kPointer[i][0], yi = kPointer[i][1];
        const double xj = kPointer[j][0], yj = kPointer[j][1];
        if (((yi > y) != (yj > y)) && (x < (xj - xi) * (y - yi) / (yj - yi) + xi)) in = !in;
    }
    return in;
}

// --- 3x3 helpers (row-major, out = M . v) ------------------------------------
void m3_apply(const double* m, const double* v, double* out) {
    for (int i = 0; i < 3; ++i) out[i] = m[3 * i] * v[0] + m[3 * i + 1] * v[1] + m[3 * i + 2] * v[2];
}
void m3_mul(const double* a, const double* b, double* out) {
    double r[9];
    for (int i = 0; i < 3; ++i)
        for (int j = 0; j < 3; ++j)
            r[3 * i + j] = a[3 * i] * b[j] + a[3 * i + 1] * b[3 + j] + a[3 * i + 2] * b[6 + j];
    std::memcpy(out, r, sizeof r);
}
bool m3_inverse(const double* m, double* out) {
    const double a = m[0], b = m[1], c = m[2], d = m[3], e = m[4], f = m[5], g = m[6], h = m[7], i = m[8];
    const double A = e * i - f * h, B = -(d * i - f * g), C = d * h - e * g;
    const double det = a * A + b * B + c * C;
    if (!(std::fabs(det) > 0.0) || !std::isfinite(det)) return false;
    const double r[9] = {A / det, -(b * i - c * h) / det, (b * f - c * e) / det,
                         B / det, (a * i - c * g) / det, -(a * f - c * d) / det,
                         C / det, -(a * h - b * g) / det, (a * e - b * d) / det};
    std::memcpy(out, r, sizeof r);
    return true;
}

// ProPhoto RGB (ROMM, D50) <-> XYZ, the space's own white, no adaptation:
// colour-science's matrices, bit-exact (model/gamut_compression.cpp kRgbToXyz[2]
// / kXyzToRgb[2]).
constexpr double kProPhotoToXyz[9] = {
    0x1.986c226809d49p-1,  0x1.14e3bcd35a857p-3,  0x1.0068db8bac70fp-5,
    0x1.26e978d4fdf3bp-2,  0x1.6c7e28240b780p-1,  0x1.a36e2eb1c4333p-14,
    -0x1.529d8bce9dd8dp-60, -0x1.7d26869097f3bp-61, 0x1.a6594af4f0d84p-1};
constexpr double kXyzToProPhoto[9] = {
    0x1.589374bc6a7f0p+0, -0x1.05bc01a36e2ecp-2, -0x1.a29c779a6b50fp-5,
    -0x1.16d5cfaacd9e8p-1, 0x1.8219652bd3c36p+0,  0x1.4fdf3b645a1cep-6,
    -0x1.aab28f12ea173p-60, -0x1.e6fdffe5e01c0p-61, 0x1.36594af4f0d84p+0};
// colour-science CCS_ILLUMINANTS 1931 2deg "D50" (ProPhoto's whitepoint).
constexpr double kProPhotoWhiteXy[2] = {0.3457, 0.3585};
// CAT02 (colour.adaptation.CAT_CAT02).
constexpr double kCat02[9] = {0.7328, 0.4296, -0.1624, -0.7036, 1.6975, 0.0061,
                              0.0030, 0.0136, 0.9834};

void xy_to_XYZ(const double xy[2], double out[3]) {
    out[0] = xy[0] / xy[1];
    out[1] = 1.0;
    out[2] = (1.0 - xy[0] - xy[1]) / xy[1];
}

// Silverphase Colour::matrix_chromatic_adaptation (von Kries in CAT02 space):
// M = CAT^-1 . diag(LMS_target / LMS_source) . CAT.
bool cat02(const double src_xy[2], const double dst_xy[2], double out[9]) {
    double ws[3], wt[3], ls[3], lt[3];
    xy_to_XYZ(src_xy, ws);
    xy_to_XYZ(dst_xy, wt);
    m3_apply(kCat02, ws, ls);
    m3_apply(kCat02, wt, lt);
    double inv[9];
    if (!m3_inverse(kCat02, inv)) return false;
    const double d[9] = {lt[0] / ls[0], 0, 0, 0, lt[1] / ls[1], 0, 0, 0, lt[2] / ls[2]};
    double t[9];
    m3_mul(d, kCat02, t);
    m3_mul(inv, t, out);
    return true;
}

// np.interp on an ascending table (clamping ends).
double interp_asc(double v, const double* xs, const double* ys, size_t n, size_t stride_x,
                  size_t stride_y) {
    if (v <= xs[0]) return ys[0];
    if (v >= xs[(n - 1) * stride_x]) return ys[(n - 1) * stride_y];
    size_t lo = 0, hi = n - 1;
    while (hi - lo > 1) {
        const size_t mid = (lo + hi) / 2;
        if (xs[mid * stride_x] <= v) lo = mid; else hi = mid;
    }
    const double x0 = xs[lo * stride_x], x1 = xs[hi * stride_x];
    const double t = (v - x0) / (x1 - x0);
    return ys[lo * stride_y] + t * (ys[hi * stride_y] - ys[lo * stride_y]);
}

// log10 of the base-free transmittance, per channel, through `ixs`
// (spk_spectral_epilogue, base 0, gain 1, mode log: log10(max(v,0)+1e-10)).
inline void read_log_t(const DiConstants& k, const double cmy[3], double out[3]) {
    double acc[3] = {0, 0, 0};
    for (int l = 0; l < kN; ++l) {
        const size_t L = static_cast<size_t>(l) * 3;
        const double d = cmy[0] * k.chd[L] + cmy[1] * k.chd[L + 1] + cmy[2] * k.chd[L + 2];
        const double t = std::pow(10.0, -d);
        acc[0] += t * k.ixs[L];
        acc[1] += t * k.ixs[L + 1];
        acc[2] += t * k.ixs[L + 2];
    }
    for (int c = 0; c < 3; ++c) out[c] = std::log10(std::fmax(acc[c], 0.0) + 1e-10);
}

constexpr double kPi = 3.14159265358979323846;
constexpr double kOkM1[9] = {0.8189330101, 0.3618667424, -0.1288597137,
                             0.0329845436, 0.9293118715, 0.0361456387,
                             0.0482003018, 0.2643662691, 0.6338517070};
constexpr double kOkM2[9] = {0.2104542553, 0.7936177850, -0.0040720468,
                             1.9779984951, -2.4285922050, 0.4505937099,
                             0.0259040371, 0.7827717662, -0.8086757660};
constexpr double kOkM2Inv[9] = {1.0, 0.3963377774, 0.2158037573, 1.0, -0.1055613458, -0.0638541728,
                                1.0, -0.0894841775, -1.2914855480};
// colour-science CCS_ILLUMINANTS 1931 2deg "D65".
constexpr double kD65Xy[2] = {0.3127, 0.3290};

void oklab(const double* to_lms, const double rgb[3], double lab[3]) {
    double lms[3];
    m3_apply(to_lms, rgb, lms);
    for (double& v : lms) v = std::cbrt(std::fmax(v, 0.0));
    m3_apply(kOkM2, lms, lab);
}
double wrap180(double d) {
    d = std::fmod(d + 180.0, 360.0);
    if (d < 0.0) d += 360.0;
    return d - 180.0;
}
// The window the kernel applies, on the *output* hue and chroma.
double blue_weight(double h_deg, double chroma) {
    const double d = wrap180(h_deg - kDiBlueHueCentre);
    if (std::fabs(d) >= kDiBlueHueHalfWidth) return 0.0;
    const double w = 0.5 * (1.0 + std::cos(kPi * d / kDiBlueHueHalfWidth));
    double g = (chroma - kDiBlueChromaLo) / (kDiBlueChromaHi - kDiBlueChromaLo);
    g = std::clamp(g, 0.0, 1.0);
    return w * g * g * (3.0 - 2.0 * g);
}
// digital_intermediate.cpp di_linear: log10 T -> the DI's linear ProPhoto
// (no live offsets).
void di_linear(const DiConstants& k, const double log_t[3], double lin[3]) {
    double pos[3];
    for (int c = 0; c < 3; ++c)
        pos[c] = std::pow(10.0, interp_asc(log_t[c], k.curve_x.data() + c, k.curve_y.data(), k.k, 3, 1)) -
                 k.positive_floor;
    for (int j = 0; j < 3; ++j)
        lin[j] = pos[0] * k.matrix[j] + pos[1] * k.matrix[3 + j] + pos[2] * k.matrix[6 + j];
}

// ---- the DI view (CineonLUT.swift) ------------------------------------------
namespace view {

constexpr double kGrey = 0.18;
double decode(double code) {
    const double b = std::pow(10.0, (kDiCineonBlack - kDiCineonWhite) / kDiCineonPerDecade);
    return (std::pow(10.0, (code * kDiCineonMax - kDiCineonWhite) / kDiCineonPerDecade) - b) / (1 - b);
}
double romm_encode(double l) {
    const double x = std::min(std::max(l, 0.0), 1.0);
    return x < 1.0 / 512 ? 16 * x : std::pow(x, 1 / 1.8);
}
double shoulder(double delta, double room) {
    return delta * room / std::sqrt(room * room + delta * delta);
}
const double kWhiteStops = std::log2(1 / kGrey);
const double kCeilingStops = std::log2(decode(1) / kGrey);
constexpr double kCeilingMargin = 1.0 / 16;
double compute_knee() {
    auto landing = [](double k) { return k + shoulder(kCeilingStops - k, kWhiteStops - k); };
    const double target = kWhiteStops - kCeilingMargin;
    if (landing(0) >= target) return 0;
    double lo = 0.0, hi = kWhiteStops - 1e-9;
    for (int i = 0; i < 200; ++i) {
        const double mid = (lo + hi) / 2;
        if (landing(mid) < target) lo = mid; else hi = mid;
    }
    return (lo + hi) / 2;
}
const double kKnee = compute_knee();
double tone(double y) {
    if (!(y > 0)) return 0;
    const double u = std::log2(y / kGrey);
    if (!(u > kKnee)) return y;
    return kGrey * std::pow(2.0, kKnee + shoulder(u - kKnee, kWhiteStops - kKnee));
}
constexpr double kProPhotoY[3] = {0.2880, 0.7119, 0.0001};
constexpr double kOklabM1[9] = {0.8189330101, 0.3618667424, -0.1288597137,
                                0.0329845436, 0.9293118715, 0.0361456387,
                                0.0482003018, 0.2643662691, 0.6338517070};
constexpr double kOklabM2[9] = {0.2104542553, 0.7936177850, -0.0040720468,
                                1.9779984951, -2.4285922050, 0.4505937099,
                                0.0259040371, 0.7827717662, -0.8086757660};
constexpr double kProPhotoToXyz65[9] = {0.7555287933, 0.1127362165, 0.0820865580,
                                        0.2682481575, 0.7151801392, 0.0165701125,
                                        0.0039166867, -0.0129345407, 1.0978022305};
struct Mats {
    double to_lms[9], from_lms[9], m2inv[9];
    Mats() {
        m3_mul(kOklabM1, kProPhotoToXyz65, to_lms);
        m3_inverse(to_lms, from_lms);
        m3_inverse(kOklabM2, m2inv);
    }
};
const Mats& mats() {
    static const Mats m;
    return m;
}
bool inside(const double v[3]) {
    const double mn = std::min(v[0], std::min(v[1], v[2]));
    const double mx = std::max(v[0], std::max(v[1], v[2]));
    return mn >= -1e-9 && mx <= 1 + 1e-9;
}
void clamp01(double v[3]) {
    for (int c = 0; c < 3; ++c) v[c] = std::min(std::max(v[c], 0.0), 1.0);
}
// CineonLUT.pathToWhite for the ProPhoto destination.
void path_to_white(double rgb[3]) {
    if (inside(rgb)) return;
    const Mats& M = mats();
    double lms[3], cb[3], lab[3];
    m3_apply(M.to_lms, rgb, lms);
    for (int c = 0; c < 3; ++c) cb[c] = std::cbrt(std::max(lms[c], 0.0));
    m3_apply(kOklabM2, cb, lab);
    auto back = [&](double s, double out[3]) {
        const double v[3] = {lab[0], lab[1] * s, lab[2] * s};
        double l_[3], l3[3];
        m3_apply(M.m2inv, v, l_);
        for (int c = 0; c < 3; ++c) l3[c] = l_[c] * l_[c] * l_[c];
        m3_apply(M.from_lms, l3, out);
    };
    double b0[3];
    back(0, b0);
    if (!inside(b0)) { clamp01(b0); std::memcpy(rgb, b0, sizeof b0); return; }
    double lo = 0.0, hi = 1.0;
    for (int i = 0; i < 40; ++i) {
        const double mid = (lo + hi) / 2;
        double t[3];
        back(mid, t);
        if (inside(t)) lo = mid; else hi = mid;
    }
    back(lo, rgb);
    clamp01(rgb);
}

// The shipped table: CineonLUT.view at the 65^3 grid points, written "%.6f"
// into the .cube the canvas parses back as float32.
struct Table {
    std::vector<float> v;  // (65^3, 3), red fastest
    Table() {
        const int n = kDiViewLutSize;
        v.resize(static_cast<size_t>(n) * n * n * 3);
        const double d = static_cast<double>(n - 1);
        parallel_for(0, n, [&](int lo, int hi) {
            for (int b = lo; b < hi; ++b)
                for (int g = 0; g < n; ++g)
                    for (int r = 0; r < n; ++r) {
                        const double code[3] = {r / d, g / d, b / d};
                        double out[3];
                        di_view_exact(code, out);
                        const size_t i = ((static_cast<size_t>(b) * n + g) * n + r) * 3;
                        for (int c = 0; c < 3; ++c) {
                            // "%.6f" then Float(): round to six decimals.
                            v[i + c] = static_cast<float>(std::nearbyint(out[c] * 1e6) / 1e6);
                        }
                    }
        });
    }
};
const Table& table() {
    static const Table t;  // built once, thread-safe (C++11 magic static)
    return t;
}

}  // namespace view

double romm_decode(double v) {
    // Inverse of ROMM's encode: linear below 16/512, then ^1.8.
    return v < 16.0 / 512.0 ? v / 16.0 : std::pow(v, 1.8);
}

}  // namespace

double di_cineon_black_offset() {
    return std::pow(10.0, (kDiCineonBlack - kDiCineonWhite) / kDiCineonPerDecade);
}
double di_cineon_decode(double code01) {
    const double b = di_cineon_black_offset();
    return (std::pow(10.0, (code01 * kDiCineonMax - kDiCineonWhite) / kDiCineonPerDecade) - b) / (1.0 - b);
}

void di_view_exact(const double code[3], double out[3]) {
    using namespace view;
    double lin[3];
    for (int c = 0; c < 3; ++c) lin[c] = std::max(decode(code[c]), 0.0);
    const double y = kProPhotoY[0] * lin[0] + kProPhotoY[1] * lin[1] + kProPhotoY[2] * lin[2];
    double toned[3] = {0, 0, 0};
    if (y > 0) {
        const double s = tone(y) / y;
        for (int c = 0; c < 3; ++c) toned[c] = lin[c] * s;
    }
    path_to_white(toned);
    for (int c = 0; c < 3; ++c) out[c] = romm_encode(toned[c]);
}

void di_view_lut(const double code_in[3], double out[3]) {
    const view::Table& t = view::table();
    const int n = kDiViewLutSize;
    double f[3];
    int i0[3], i1[3];
    for (int c = 0; c < 3; ++c) {
        // Texel-centred: (clamp(code) * (n - 1) + 0.5) / n on a clamp-to-edge
        // linear sampler is a plain trilinear on the grid code * (n - 1).
        const double u = std::min(std::max(code_in[c], 0.0), 1.0) * (n - 1);
        int a = static_cast<int>(std::floor(u));
        if (a > n - 2) a = n - 2;
        i0[c] = a;
        i1[c] = a + 1;
        f[c] = u - a;
    }
    auto at = [&](int r, int g, int b, int c) {
        return static_cast<double>(t.v[((static_cast<size_t>(b) * n + g) * n + r) * 3 + c]);
    };
    for (int c = 0; c < 3; ++c) {
        double acc = 0.0;
        for (int db = 0; db < 2; ++db)
            for (int dg = 0; dg < 2; ++dg)
                for (int dr = 0; dr < 2; ++dr) {
                    const double w = (dr ? f[0] : 1 - f[0]) * (dg ? f[1] : 1 - f[1]) *
                                     (db ? f[2] : 1 - f[2]);
                    if (w == 0.0) continue;
                    acc += w * at(dr ? i1[0] : i0[0], dg ? i1[1] : i0[1], db ? i1[2] : i0[2], c);
                }
        out[c] = acc;
    }
}

bool di_constants(const DiInputs& in, DiConstants* outp, std::string* error) {
    DiConstants& out = *outp;
    out = DiConstants{};
    const Profile& film = *in.film;
    const Profile& paper = *in.paper;
    out.paper = paper.stock;
    if (film.n_samples != kN || paper.n_samples != kN || !in.paper_lamp || !in.film_reference ||
        !in.weights || in.weights->size() != static_cast<size_t>(kN) * 3 || !in.spectra_lut ||
        !in.wedge) {
        if (error) *error = "digital intermediate: incomplete inputs";
        return false;
    }

    // --- the printing-density read, base removed ---------------------------
    out.chd.assign(static_cast<size_t>(kN) * 3, 0.0);
    out.ixs.assign(static_cast<size_t>(kN) * 3, 0.0);
    double colsum[3] = {0, 0, 0};
    for (int l = 0; l < kN; ++l) {
        bool valid = !std::isnan(film.base_density[static_cast<size_t>(l)]);
        for (int c = 0; c < 3 && valid; ++c)
            valid = !std::isnan(film.channel_density[static_cast<size_t>(l) * 3 + c]);
        if (!valid) continue;
        for (int c = 0; c < 3; ++c) {
            const size_t i = static_cast<size_t>(l) * 3 + c;
            out.chd[i] = static_cast<double>(film.channel_density[i]);
            const double s = std::pow(10.0, static_cast<double>(paper.log_sensitivity[i]));
            const double w = std::isnan(s) ? 0.0 : s * in.paper_lamp[l];
            out.ixs[i] = w;
            colsum[c] += w;
        }
    }
    for (int c = 0; c < 3; ++c) {
        if (!(colsum[c] > 0.0)) {
            if (error)
                *error = "digital intermediate: paper '" + paper.stock +
                         "' has no sensitivity where '" + film.stock + "' is defined";
            return false;
        }
    }
    for (int l = 0; l < kN; ++l)
        for (int c = 0; c < 3; ++c) out.ixs[static_cast<size_t>(l) * 3 + c] /= colsum[c];

    // --- the neutral wedge, and the reversal on the film's own curve --------
    const double lo = -14.0, step = 0.05;
    const size_t n = static_cast<size_t>(std::lround((14.0 - lo) / step)) + 1;
    std::vector<double> stops(n), greys(n * 3), dens(n * 3);
    for (size_t i = 0; i < n; ++i) {
        stops[i] = lo + step * static_cast<double>(i);
        greys[3 * i] = greys[3 * i + 1] = greys[3 * i + 2] = kMidgray * std::pow(2.0, stops[i]);
    }
    std::vector<float> cmy;
    if (!in.wedge(greys, &cmy) || cmy.size() != n * 3) {
        if (error) *error = "digital intermediate: the neutral wedge failed";
        return false;
    }
    for (size_t i = 0; i < n; ++i) {
        const double c3[3] = {cmy[i * 3], cmy[i * 3 + 1], cmy[i * 3 + 2]};
        double lt[3];
        read_log_t(out, c3, lt);
        for (int c = 0; c < 3; ++c) dens[3 * i + c] = -lt[c];
    }
    {
        double sx = 0, sy = 0, sxx = 0, sxy = 0, m = 0;
        for (size_t i = 0; i < n; ++i) {
            if (stops[i] < -2.0 - 1e-9 || stops[i] > 2.0 + 1e-9) continue;
            const double xv = stops[i] * std::log10(2.0), yv = dens[3 * i + 1];
            sx += xv; sy += yv; sxx += xv * xv; sxy += xv * yv; m += 1.0;
        }
        out.gamma_green = (m * sxy - sx * sy) / (m * sxx - sx * sx);
        out.density_green_mid = dens[3 * static_cast<size_t>(std::lround(-lo / step)) + 1];
    }
    if (!(out.gamma_green > 0.05)) {
        if (error) *error = "digital intermediate: '" + film.stock + "' has no usable neutral contrast";
        return false;
    }
    out.k = n;
    out.curve_x.assign(n * 3, 0.0);
    out.curve_y.assign(n, 0.0);
    for (size_t r = 0; r < n; ++r) {
        const size_t i = n - 1 - r;  // descending stops: dense -> clear
        out.curve_y[r] = std::log10(kMidgray) +
                         (dens[3 * i + 1] - out.density_green_mid) / kDiCineonNegativeGamma;
        for (int c = 0; c < 3; ++c) {
            double xv = -dens[3 * i + c];
            if (r > 0) xv = std::fmax(xv, out.curve_x[3 * (r - 1) + c] + 1e-9);
            out.curve_x[3 * r + c] = xv;
        }
    }
    out.positive_floor = std::pow(10.0, out.curve_y[n - 1]);

    // --- the colour step: film RGB -> ProPhoto RGB ---------------------------
    // (Oklab for the blue-sector compensation first: Silverphase builds it for
    // every film, and a monochrome film then stops before the fit.)
    {
        double adapt65[9], work_to_xyz65[9], lms[9];
        if (!cat02(kProPhotoWhiteXy, kD65Xy, adapt65) ) {
            if (error) *error = "digital intermediate: chromatic adaptation failed";
            return false;
        }
        m3_mul(adapt65, kProPhotoToXyz, work_to_xyz65);
        m3_mul(kOkM1, work_to_xyz65, lms);
        std::memcpy(out.to_lms, lms, sizeof lms);
        if (!m3_inverse(lms, out.from_lms)) {
            if (error) *error = "digital intermediate: singular Oklab matrix";
            return false;
        }
    }
    if (film.is_monochrome()) {
        for (double& v : out.matrix) v = 1.0 / 3.0;
        out.matrix_fit_rms = 0.0;
        return true;  // no colour fit, no blue compensation
    }
    const NdArray& lut = *in.spectra_lut;
    if (lut.shape.size() != 3 || lut.shape[2] != kN) {
        if (error) *error = "digital intermediate: spectra LUT has the wrong shape";
        return false;
    }
    const std::vector<double>& wts = *in.weights;
    double e_white[3] = {0, 0, 0}, x_white[3] = {0, 0, 0};
    for (int l = 0; l < kN; ++l)
        for (int c = 0; c < 3; ++c) {
            e_white[c] += in.film_reference[l] * wts[static_cast<size_t>(l) * 3 + c];
            x_white[c] += in.film_reference[l] * static_cast<double>(kCieCmf1931[l][c]);
        }
    const double xs = x_white[0] + x_white[1] + x_white[2];
    const double ref_xy[2] = {x_white[0] / xs, x_white[1] / xs};
    // matrix_XYZ_to_RGB(ProPhoto, ref_xy, CAT02) = XYZ->ProPhoto . CAT02(ref -> D50).
    double adapt[9], xyz_to_work[9];
    if (!cat02(ref_xy, kProPhotoWhiteXy, adapt)) {
        if (error) *error = "digital intermediate: chromatic adaptation failed";
        return false;
    }
    m3_mul(kXyzToProPhoto, adapt, xyz_to_work);

    double G[3][3] = {{0, 0, 0}, {0, 0, 0}, {0, 0, 0}}, B[3][3] = {{0, 0, 0}, {0, 0, 0}, {0, 0, 0}};
    double rr = 0.0;
    size_t used = 0;
    const size_t cells = static_cast<size_t>(lut.shape[0]) * static_cast<size_t>(lut.shape[1]);
    std::vector<double> fitE, fitR;
    fitE.reserve(cells * 3);
    fitR.reserve(cells * 3);
    for (size_t cell = 0; cell < cells; ++cell) {
        const double* sp = &lut.data[cell * kN];
        double e[3] = {0, 0, 0}, X[3] = {0, 0, 0};
        for (int l = 0; l < kN; ++l)
            for (int c = 0; c < 3; ++c) {
                e[c] += sp[l] * wts[static_cast<size_t>(l) * 3 + c];
                X[c] += sp[l] * static_cast<double>(kCieCmf1931[l][c]);
            }
        const double sum = X[0] + X[1] + X[2];
        if (!(X[1] > 1e-9) || !(sum > 0.0)) continue;
        if (!(e[0] > 0.0 && e[1] > 0.0 && e[2] > 0.0)) continue;
        if (!inside_pointer(X[0] / sum, X[1] / sum)) continue;
        double en[3], xn[3], r[3];
        for (int c = 0; c < 3; ++c) {
            en[c] = e[c] / e_white[c] / (X[1] / x_white[1]);
            xn[c] = X[c] / X[1];
        }
        m3_apply(xyz_to_work, xn, r);
        for (int a = 0; a < 3; ++a)
            for (int b = 0; b < 3; ++b) { G[a][b] += en[a] * en[b]; B[a][b] += en[a] * r[b]; }
        for (int c = 0; c < 3; ++c) { fitE.push_back(en[c]); fitR.push_back(r[c]); rr += r[c] * r[c]; }
        ++used;
    }
    if (used < 100) {
        if (error) *error = "digital intermediate: too few spectra to fit the colour step";
        return false;
    }
    double Gi[9];
    if (!m3_inverse(&G[0][0], Gi)) {
        if (error) *error = "digital intermediate: singular colour fit";
        return false;
    }
    double gi1[3];
    const double one[3] = {1, 1, 1};
    m3_apply(Gi, one, gi1);
    const double denom = gi1[0] + gi1[1] + gi1[2];
    double M[3][3];  // M[j][i]: working channel j from film channel i
    for (int j = 0; j < 3; ++j) {
        const double b[3] = {B[0][j], B[1][j], B[2][j]};
        double m0[3];
        m3_apply(Gi, b, m0);
        const double lambda = (1.0 - (m0[0] + m0[1] + m0[2])) / denom;
        for (int i = 0; i < 3; ++i) M[j][i] = m0[i] + lambda * gi1[i];
    }
    double ee = 0.0;
    for (size_t s = 0; s < used; ++s)
        for (int j = 0; j < 3; ++j) {
            const double pred = M[j][0] * fitE[3 * s] + M[j][1] * fitE[3 * s + 1] + M[j][2] * fitE[3 * s + 2];
            const double d = pred - fitR[3 * s + j];
            ee += d * d;
        }
    out.matrix_fit_rms = std::sqrt(ee / rr);
    for (int i = 0; i < 3; ++i)
        for (int j = 0; j < 3; ++j) out.matrix[3 * i + j] = M[j][i];  // x @ M

    // --- the blue-sector fit: this film's own chain against its input ------
    // Synthetic blues at 0 EV, generated in Oklab around the window, through
    // the film and the DI above; one hue rotation and one chroma scale,
    // weighted by the window on the output. The working space IS the input
    // space here (ProPhoto RGB), so Silverphase's work -> input step is the
    // identity.
    std::vector<double> patches, labs_in;
    for (double L : {0.50, 0.62, 0.74})
        for (double C : {0.06, 0.10, 0.14, 0.18})
            for (double h = kDiBlueHueCentre - kDiBlueHueHalfWidth - 20.0;
                 h <= kDiBlueHueCentre + kDiBlueHueHalfWidth + 20.0 + 1e-9; h += 7.5) {
                const double lab[3] = {L, C * std::cos(h * kPi / 180.0), C * std::sin(h * kPi / 180.0)};
                double l_[3], lms[3], work[3];
                m3_apply(kOkM2Inv, lab, l_);
                for (int c = 0; c < 3; ++c) lms[c] = l_[c] * l_[c] * l_[c];
                m3_apply(out.from_lms, lms, work);
                if (!(work[0] > 0.0 && work[1] > 0.0 && work[2] > 0.0)) continue;
                patches.insert(patches.end(), work, work + 3);
            }
    if (!patches.empty()) {
        std::vector<float> pc;
        if (!in.wedge(patches, &pc) || pc.size() != patches.size()) {
            if (error) *error = "digital intermediate: the blue patches failed";
            return false;
        }
        double sw = 0.0, sdh = 0.0, slogc = 0.0;
        for (size_t i = 0; i < patches.size() / 3; ++i) {
            const double c3[3] = {pc[3 * i], pc[3 * i + 1], pc[3 * i + 2]};
            double lt[3], lin[3], lab_in[3], lab_out[3];
            read_log_t(out, c3, lt);
            di_linear(out, lt, lin);
            oklab(out.to_lms, &patches[3 * i], lab_in);
            oklab(out.to_lms, lin, lab_out);
            const double c_in = std::hypot(lab_in[1], lab_in[2]);
            const double c_out = std::hypot(lab_out[1], lab_out[2]);
            const double h_in = std::atan2(lab_in[2], lab_in[1]) * 180.0 / kPi;
            const double h_out = std::atan2(lab_out[2], lab_out[1]) * 180.0 / kPi;
            const double w = blue_weight(h_out, c_out) * c_out;
            if (!(w > 0.0) || !(c_in > 0.0)) continue;
            sw += w;
            sdh += w * wrap180(h_in - h_out);
            slogc += w * std::log(c_in / c_out);
        }
        if (sw > 0.0) {
            out.blue_delta_deg = std::clamp(sdh / sw, -30.0, 30.0);
            out.blue_kappa = std::clamp(std::exp(slogc / sw), 0.5, 1.5);
        }
    }
    return true;
}

void di_live_offsets(double print_exposure, double m_filter_shift, double y_filter_shift,
                     bool monochrome, double out[3]) {
    const double k = -std::log10(std::fmax(print_exposure, 1e-6));
    const double stop = std::log10(2.0);
    out[0] = k;
    out[1] = k + (monochrome ? 0.0 : m_filter_shift * 0.03 * stop);
    out[2] = k + (monochrome ? 0.0 : y_filter_shift * 0.05 * stop);
}

void di_render(const DiConstants& k, const double live[3], const float* density_cmy,
               int npix, bool master, bool blue_compensation, spk_color_space cs, bool cctf,
               const ToneCurveSet* tone_curve, float* rgb_out) {
    const double b = di_cineon_black_offset();
    const double gain[3] = {std::pow(10.0, live[0]), std::pow(10.0, live[1]), std::pow(10.0, live[2])};
    if (!master) (void)view::table();  // build the view table before the threads fan out
    // ProPhoto (D50) -> XYZ (D50-relative) -> output RGB with the engine's
    // viewing-white CAT baked in (the D50 row of kXYZ_to_RGB), as the scan does
    // for a D50-viewed print.
    double to_out[9];
    m3_mul(kXYZ_to_RGB[static_cast<int>(cs)], kProPhotoToXyz, to_out);
    const double* Mc = kRGB_to_RGB_CCTF[static_cast<int>(cs)];
    parallel_for(0, npix, [&](int lo, int hi) {
        for (int p = lo; p < hi; ++p) {
            const float* in = density_cmy + static_cast<size_t>(p) * 3;
            const double cmy[3] = {in[0], in[1], in[2]};
            double lt[3], pos[3];
            read_log_t(k, cmy, lt);
            for (int c = 0; c < 3; ++c) {
                // spk_curves: the positive, log10, on the channel's own table.
                const double y = interp_asc(lt[c], k.curve_x.data() + c, k.curve_y.data(), k.k, 3, 1);
                // spk_di_encode: live offsets, the base made black.
                pos[c] = std::pow(10.0, y + live[c]) - k.positive_floor * gain[c];
            }
            double lin[3];
            for (int j = 0; j < 3; ++j)
                lin[j] = pos[0] * k.matrix[j] + pos[1] * k.matrix[3 + j] + pos[2] * k.matrix[6 + j];
            if (blue_compensation) {
                // spk_di_encode's Oklab blue sector; greys and every other
                // hue are exact (the window fades out below a chroma floor).
                double lms[3];
                m3_apply(k.to_lms, lin, lms);
                for (double& v : lms) v = std::cbrt(std::max(v, 0.0));
                const double L = kOkM2[0] * lms[0] + kOkM2[1] * lms[1] + kOkM2[2] * lms[2];
                double a = kOkM2[3] * lms[0] + kOkM2[4] * lms[1] + kOkM2[5] * lms[2];
                double bb = kOkM2[6] * lms[0] + kOkM2[7] * lms[1] + kOkM2[8] * lms[2];
                const double C = std::sqrt(a * a + bb * bb);
                const double h = std::atan2(bb, a) * 57.29577951308232;
                const double d = std::fmod(h - kDiBlueHueCentre + 540.0, 360.0) - 180.0;
                double w = std::fabs(d) < kDiBlueHueHalfWidth
                               ? 0.5 * (1.0 + std::cos(kPi * d / kDiBlueHueHalfWidth)) : 0.0;
                const double g = std::clamp((C - kDiBlueChromaLo) / (kDiBlueChromaHi - kDiBlueChromaLo), 0.0, 1.0);
                w *= g * g * (3.0 - 2.0 * g);
                if (w > 0.0) {
                    const double h2 = (h + w * k.blue_delta_deg) * 0.017453292519943295;
                    const double C2 = C * (1.0 + w * (k.blue_kappa - 1.0));
                    a = C2 * std::cos(h2);
                    bb = C2 * std::sin(h2);
                    const double l_[3] = {L + 0.3963377774 * a + 0.2158037573 * bb,
                                          L - 0.1055613458 * a - 0.0638541728 * bb,
                                          L - 0.0894841775 * a - 1.2914855480 * bb};
                    const double l3[3] = {l_[0] * l_[0] * l_[0], l_[1] * l_[1] * l_[1], l_[2] * l_[2] * l_[2]};
                    m3_apply(k.from_lms, l3, lin);
                }
            }
            double code[3];
            for (int c = 0; c < 3; ++c) {
                double v = (kDiCineonWhite +
                            kDiCineonPerDecade * std::log10(std::max(lin[c], 0.0) * (1.0 - b) + b)) /
                           kDiCineonMax;
                code[c] = std::min(std::max(v, 0.0), 1.0);
            }
            float* o = rgb_out + static_cast<size_t>(p) * 3;
            if (master) {
                for (int c = 0; c < 3; ++c) o[c] = static_cast<float>(code[c]);
                continue;
            }
            double romm[3], pp[3], rgb[3];
            di_view_lut(code, romm);
            for (int c = 0; c < 3; ++c) pp[c] = romm_decode(romm[c]);
            m3_apply(to_out, pp, rgb);
            for (int c = 0; c < 3; ++c) {
                double v;
                if (cctf) {
                    const double adapted = Mc[c * 3] * rgb[0] + Mc[c * 3 + 1] * rgb[1] + Mc[c * 3 + 2] * rgb[2];
                    v = output_cctf_encode(cs, adapted);
                } else {
                    v = rgb[c];
                }
                if (v < 0.0) v = 0.0;
                else if (v > 1.0) v = 1.0;
                o[c] = tone_curve ? tone_curve->apply(c, static_cast<float>(v)) : static_cast<float>(v);
            }
        }
    });
}

}  // namespace spk
