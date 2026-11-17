/*
 * Spektrafilm for Android — native engine: filming stage (rgb -> raw -> density).
 * Copyright (C) 2026 Spektrafilm Android contributors.
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version. See <https://www.gnu.org/licenses/>.
 *
 * Port of spektrafilm (GPLv3) by Andrea Volpato — film modeling powered by
 * spektrafilm. Implements runtime/stages/filming.h.
 */
#include "runtime/stages/filming.h"

#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <memory>
#include <new>
#include <type_traits>
#include <vector>

#include "gpu/vulkan_compute.h"
#include "kernels/exponential_filter.h"
#include "kernels/parallel.h"
#include "kernels/spectral_upsampling.h"
#include "runtime/stage_timer.h"
#include "model/film_reference.h"
#include "model/camera_filters.h"
#include "model/couplers.h"
#include "model/density_curves.h"
#include "model/diffusion.h"
#include "model/grain.h"
#include "model/halation_psf.h"
#include "model/date_stamp.h"
#include "model/overscan.h"
#include "model/scene_latitude.h"
#include "model/spectral.h"

namespace spk {

namespace {
std::atomic<long long> g_gpu_halation_frames{0};

// Runs model/diffusion.cpp::apply_halation_um's model on the GPU. Returns true
// only when the whole pass succeeded and `raw` now holds its result; false
// leaves `raw` untouched for the CPU pass. A no-op parameter set is reported
// as success without engaging the device (the CPU pass would not change the
// image either).
// The camera diffusion filter on the GPU (#213). Same contract as
// gpu_halation_pass below: true only when the whole pass succeeded and `raw` holds
// its result, false leaves `raw` untouched for the CPU pass.
//
// It runs on the SAME kernel. The diffusion PSF decomposes into a weighted set of
// Gaussians (model/diffusion.cpp, build_diffusion_mixture) and its resolve,
// (1 - p_s) * E_in + p_s * (K_s * E_in), is the expression the scatter resolve
// already computes -- so the halation pass's mixture mode runs it unchanged.
//
// THIS IS AN APPROXIMATION, and a coarser one than the halation port. Fitting an
// exponential with Gaussians is worst at r = 0, where the exponential has a cusp,
// and that cusp is the bright core of the bloom. Measured against the exact PSF at
// 12.5 MP, Black Pro-Mist: max_abs 1.4e-03 with the 7-term fit -- 13x outside the
// 1e-4 oracle band (tests/bench_diffusion_mixture.cpp). It is therefore gated to the
// Fast GPU route only; Strict Exact keeps the FFT.
bool gpu_diffusion_pass(double* raw, int width, int height,
                        const DiffusionFilterParams& p, double pixel_size_um) {
    if (!gpu::available()) return false;

    std::vector<DiffusionGaussian> comps;
    double p_s = 0.0;
    int radius = 0;
    // kDiffusionMixtureTerms: Gaussians per exponential term. 7 measured 2.5x tighter
    // than the upstream 3-term OFX fit for ~2x the components; the components are
    // separable blurs whose cost is O(1) in sigma, so they are cheap to add.
    constexpr int kDiffusionMixtureTerms = 7;
    if (!build_diffusion_mixture(p, pixel_size_um, width, height,
                                 kDiffusionMixtureTerms, &comps, &p_s, &radius))
        return false;   // a no-op parameter set; let the CPU path's own gate decide
    if (comps.empty()) return false;

    std::vector<double> sigma(comps.size());
    std::vector<double> weight(comps.size() * 3u);
    for (size_t i = 0; i < comps.size(); ++i) {
        sigma[i] = comps[i].sigma_px;
        for (int c = 0; c < 3; ++c) weight[i * 3u + static_cast<size_t>(c)] = comps[i].weight[c];
    }

    gpu::HalationScatterRequest request{};
    request.raw_rgb = raw;
    request.width = width;
    request.height = height;
    // The component sigmas are already in pixels, so the pass must not scale them
    // again: pixel_size_um is unused for them and the spatial scale is unity.
    request.pixel_size_um = pixel_size_um > 0.0 ? pixel_size_um : 1.0;
    request.scatter_spatial_scale = 1.0;
    request.scatter_amount = p_s;          // the convex-combination fraction
    request.mixture_sigma_px = sigma.data();
    request.mixture_weight = weight.data();
    request.mixture_count = static_cast<int>(comps.size());
    // Halation is a separate stage and must not run here.
    request.halation_amount = 0.0;
    request.halation_n_bounces = 0;

    const size_t total = static_cast<size_t>(width) * static_cast<size_t>(height) * 3u;
    // Read back the device's packed floats, then widen directly into raw once
    // the operation has succeeded. The private output also holds any partial
    // banded result on failure.
    std::unique_ptr<float[]> out(new (std::nothrow) float[total]);
    if (!out) return false;
    gpu::HalationScatterDiagnostics diagnostics{};
    const bool ok = gpu::halation_scatter_to_float(request, out.get(), &diagnostics);
    stage_timing_note_gpu_halation(diagnostics.attempted, diagnostics.engaged,
                                   diagnostics.reason, diagnostics.bands,
                                   diagnostics.dispatches, diagnostics.upload_ms,
                                   diagnostics.gpu_ms, diagnostics.readback_ms);
    if (!ok || !diagnostics.engaged) return false;
    parallel_for(0, static_cast<int>(total), [&](int lo, int hi) {
        for (int i = lo; i < hi; ++i) raw[i] = out[i];
    });
    return true;
}

// Observability for the pointwise filming route, on the same SPK_GPU_DEBUG
// switch the scanning and diffusion stages use. A refusal here is otherwise
// invisible: the CPU loop runs, the picture is right, and the only symptom is a
// stage timing that did not move.
static void note_gpu_filming(const char* what, const gpu::FilmingStageDiagnostics& d) {
    const char* dbg = std::getenv("SPK_GPU_DEBUG");
    if (!dbg || dbg[0] != '1') return;
    std::fprintf(stderr,
                 "[gpu-debug] filming_%s engaged=%d reason=%s up=%.1f gpu=%.1f down=%.1f\n",
                 what, d.engaged ? 1 : 0, d.reason && *d.reason ? d.reason : "-",
                 d.upload_ms, d.gpu_ms, d.readback_ms);
}

bool gpu_halation_pass(double* raw, int width, int height,
                       const HalationParams& p, double pixel_size_um) {
    if (!(pixel_size_um > 0.0) || !std::isfinite(pixel_size_um) || !gpu::available())
        return false;
    gpu::HalationScatterRequest request{};
    request.raw_rgb = raw;
    request.width = width;
    request.height = height;
    request.pixel_size_um = pixel_size_um;
    request.scatter_amount = p.scatter_amount;
    request.scatter_spatial_scale = p.scatter_spatial_scale;
    request.halation_amount = p.halation_amount;
    request.halation_spatial_scale = p.halation_spatial_scale;
    request.halation_n_bounces = p.halation_n_bounces;
    request.halation_bounce_decay = p.halation_bounce_decay;
    request.halation_renormalize = p.halation_renormalize;
    for (int c = 0; c < 3; ++c) {
        request.scatter_core_um[c] = p.scatter_core_um[c];
        request.scatter_tail_um[c] = p.scatter_tail_um[c];
        request.scatter_tail_weight[c] = p.scatter_tail_weight[c];
        request.halation_strength[c] = p.halation_strength[c];
        request.halation_first_sigma_um[c] = p.halation_first_sigma_um[c];
    }
    // Back-reflection halo (a film with no anti-halation layer): the fitted
    // four-Gaussian PSF of model/halation_psf.h in place of the bounces, in
    // pixels exactly as apply_halation_um's back_reflection branch derives them
    // (d_px = base_thickness * spatial_scale * sqrt(amount) / pixel_size, each
    // sigma through max_eps). With d_px <= 0 that branch does nothing, so the
    // pass is asked for the scatter alone.
    double psf_sigma[kBackReflectionComponents * 3];
    double psf_weight[kBackReflectionComponents * 3];
    if (p.back_reflection) {
        request.halation_n_bounces = 0;
        const double d_px = p.base_thickness_um * p.halation_spatial_scale *
                            std::sqrt(std::max(0.0, p.halation_amount)) / pixel_size_um;
        if (d_px > 0.0) {
            for (int k = 0; k < kBackReflectionComponents; ++k)
                for (int c = 0; c < 3; ++c) {
                    psf_sigma[k * 3 + c] =
                        std::max(kBackReflectionSigmaOverBase[k][c] * d_px, 1e-6);
                    psf_weight[k * 3 + c] = kBackReflectionWeight[k][c];
                }
            request.halation_psf_sigma_px = psf_sigma;
            request.halation_psf_weight = psf_weight;
            request.halation_psf_count = kBackReflectionComponents;
        }
    }
    const size_t total = static_cast<size_t>(width) * static_cast<size_t>(height) * 3u;
    // Keep the GPU result packed during readback and any between-pass band
    // staging. Copy back only after success, including on devices which cannot
    // hold an entire frame in their four Vulkan work buffers.
    std::unique_ptr<float[]> out(new (std::nothrow) float[total]);
    if (!out) return false;
    gpu::HalationScatterDiagnostics diagnostics{};
    const bool ok = gpu::halation_scatter_to_float(request, out.get(), &diagnostics);
    stage_timing_note_gpu_halation(diagnostics.attempted, diagnostics.engaged,
                                   diagnostics.reason, diagnostics.bands,
                                   diagnostics.dispatches, diagnostics.upload_ms,
                                   diagnostics.gpu_ms, diagnostics.readback_ms);
    if (!ok) return false;
    if (diagnostics.engaged) {
        parallel_for(0, static_cast<int>(total), [&](int lo, int hi) {
            for (int i = lo; i < hi; ++i) raw[i] = out[i];
        });
        g_gpu_halation_frames.fetch_add(1, std::memory_order_relaxed);
    }
    return true;
}
}  // namespace

unsigned long long gpu_halation_frames_rendered() {
    return static_cast<unsigned long long>(
        g_gpu_halation_frames.load(std::memory_order_relaxed));
}

namespace {

// ProPhoto RGB (linear) -> XYZ with CAT02 chromatic adaptation to the film's
// reference illuminant (D55), apply_cctf_decoding=False. Baked from
// colour.RGB_to_XYZ at full double precision (see oracle dump). Matches
// _rgb_to_tc_b's fixed linear transform for color_space='ProPhoto RGB'.
// Row-major 3x3: xyz[r] = sum_c M[r][c] * rgb[c].
constexpr double kProPhotoToXyzD55[3][3] = {
    {0.7815775876144749, 0.12427353211547089, 0.05084064074531416},
    {0.28106991658512925, 0.7111246050020191, 0.0078043503519031375},
    {0.0008785229438793953, 0.0012166783269637077, 0.9190442562432091},
};

inline double clip01(double v) { return v < 0.0 ? 0.0 : (v > 1.0 ? 1.0 : v); }

// _rgb_to_tc_b for color_space='ProPhoto RGB', apply_cctf_decoding=False,
// adapted to the film's reference illuminant.
void prophoto_rgb_to_tc_b(const double rgb[3], bool tungsten, Vec2* out_tc, double* out_b) {
    const auto& matrix = tungsten ? kProPhotoToXyzTungsten : kProPhotoToXyzD55;
    double xyz[3];
    for (int r = 0; r < 3; ++r) {
        xyz[r] = matrix[r][0] * rgb[0] + matrix[r][1] * rgb[1] + matrix[r][2] * rgb[2];
    }
    double b = xyz[0] + xyz[1] + xyz[2];
    double denom = std::fmax(b, 1e-10);
    Vec2 xy{clip01(xyz[0] / denom), clip01(xyz[1] / denom)};
    *out_tc = tri2quad(xy);
    *out_b = std::isnan(b) ? 0.0 : b;
}

// compute_band_pass_filter(filter_uv, filter_ir): the camera UV/IR cut filter
// (model/color_filters.py). filter_uv/ir = (amp, wavelength_nm, width_nm). Each is
//   sigmoid_erf(x, center, width) = 0.5*(1 + erf((x - center)/width))
//   filter_uv = 1-amp_uv + amp_uv*sigmoid_erf(wl, wl_uv, +width_uv)
//   filter_ir = 1-amp_ir + amp_ir*sigmoid_erf(wl, wl_ir, -width_ir)
//   band_pass = filter_uv * filter_ir            (common across channels)
// amp is clipped to [0,1] (np.clip), exactly as the oracle. Note width enters the
// erf argument as a plain divisor (NOT scaled by sqrt(2)) — unlike the erf4 window
// above — so this matches scipy.special.erf((x-center)/width) verbatim.
void eval_camera_band_pass(const float* filter_uv, const float* filter_ir,
                           const std::vector<float>& wavelengths,
                           std::vector<double>* band_pass /* (S,) common */) {
    double amp_uv = static_cast<double>(filter_uv[0]);
    double wl_uv = static_cast<double>(filter_uv[1]);
    double width_uv = static_cast<double>(filter_uv[2]);
    double amp_ir = static_cast<double>(filter_ir[0]);
    double wl_ir = static_cast<double>(filter_ir[1]);
    double width_ir = static_cast<double>(filter_ir[2]);
    if (amp_uv < 0.0) amp_uv = 0.0; else if (amp_uv > 1.0) amp_uv = 1.0;  // np.clip
    if (amp_ir < 0.0) amp_ir = 0.0; else if (amp_ir > 1.0) amp_ir = 1.0;
    int S = static_cast<int>(wavelengths.size());
    band_pass->resize(S);
    for (int s = 0; s < S; ++s) {
        double wl = static_cast<double>(wavelengths[s]);
        double sig_uv = 0.5 * (1.0 + std::erf((wl - wl_uv) / width_uv));
        double sig_ir = 0.5 * (1.0 + std::erf((wl - wl_ir) / (-width_ir)));
        double f_uv = 1.0 - amp_uv + amp_uv * sig_uv;
        double f_ir = 1.0 - amp_ir + amp_ir * sig_ir;
        (*band_pass)[s] = f_uv * f_ir;
    }
}

// eval_erf4_spectral_bandpass(window_params): common band-pass replicated across
// the 3 channels. params = [c_uv, sigma_uv, c_ir, sigma_ir].
//   edge_uv = 0.5*(1 + erf((wl - c_uv)/(sigma_uv*sqrt2)))
//   edge_ir = 0.5*(1 - erf((wl - c_ir)/(sigma_ir*sqrt2)))
//   window  = edge_uv * edge_ir
void eval_erf4_window(const double* window_params, const std::vector<float>& wavelengths,
                      std::vector<double>* window /* (S,) common */) {
    const double sqrt2 = std::sqrt(2.0);
    double c_uv = window_params[0], sigma_uv = window_params[1];
    double c_ir = window_params[2], sigma_ir = window_params[3];
    int S = static_cast<int>(wavelengths.size());
    window->resize(S);
    for (int s = 0; s < S; ++s) {
        double wl = static_cast<double>(wavelengths[s]);
        double edge_uv = 0.5 * (1.0 + std::erf((wl - c_uv) / (sigma_uv * sqrt2)));
        double edge_ir = 0.5 * (1.0 - std::erf((wl - c_ir) / (sigma_ir * sqrt2)));
        (*window)[s] = edge_uv * edge_ir;
    }
}

// ---- hanatos2025 log-exposure-correction surface (poly4 model) ----
// Mirrors spectral_upsampling.py::eval_poly4_log_exposure_surface +
// compute_hanatos2025_tc_lut's `raw_lut *= 2**surface`. The surface is a per-LUT-
// cell, per-channel log-exposure correction evaluated over the same (L, L) tc grid
// as the spectra LUT's first two axes, multiplied into the tc_lut as 2**surface.

constexpr double kHanatos2025MaxCorrectionStops = 2.0;  // _HANATOS2025_MAX_CORRECTION_STOPS

// _tri2quad: triangular chromaticity -> square coords (scalar).
inline void tri2quad(double tx, double ty, double* qx, double* qy) {
    double y = ty / std::fmax(1.0 - tx, 1e-10);
    double x = (1.0 - tx) * (1.0 - tx);
    *qx = x < 0.0 ? 0.0 : (x > 1.0 ? 1.0 : x);
    *qy = y < 0.0 ? 0.0 : (y > 1.0 ? 1.0 : y);
}

// hanika_sigmoid: algebraic sigmoid bounded to [-max_val, max_val].
inline double hanika_sigmoid(double z, double max_val) {
    return z / std::sqrt(1.0 + (z / max_val) * (z / max_val));
}

// poly2d_deg4(x, y; params, center): degree-4 2D polynomial. `params` is the
// per-channel coefficient row [c0..c14]; c0 is unused (the centre is the zero-
// correction point). Mirrors spectral_upsampling.py::poly2d_deg4.
inline double poly2d_deg4(double tcx, double tcy, const double* params, double cx,
                          double cy) {
    double x = tcx - cx;
    double y = tcy - cy;
    double x2 = x * x, y2 = y * y;
    double xy_term = x * y;
    double x3 = x2 * x, y3 = y2 * y;
    double c1 = params[1], c2 = params[2], c3 = params[3], c4 = params[4],
           c5 = params[5], c6 = params[6], c7 = params[7], c8 = params[8],
           c9 = params[9], c10 = params[10], c11 = params[11], c12 = params[12],
           c13 = params[13], c14 = params[14];
    return c1 * x + c2 * y + c3 * x2 + c4 * y2 + c5 * xy_term + c6 * x3 + c7 * y3 +
           c8 * (x2 * y) + c9 * (x * y2) + c10 * (x2 * x2) + c11 * (y2 * y2) +
           c12 * (x3 * y) + c13 * (x2 * y2) + c14 * (x * y3);
}

// eval_poly4_log_exposure_surface over the (L, L) tc grid (tc_base = linspace(0,1,L),
// tc[i,j] = (tc_base[i], tc_base[j]) with indexing='ij'). Writes surface[(i*L+j)*3+c].
// center_tc = tri2quad(illuminant_xy). Stores the per-cell log-exposure correction
// (in stops) — the caller multiplies the tc_lut by 2**surface.
void eval_poly4_surface(const double* surface_params, int cols, int L,
                        double illu_xy_x, double illu_xy_y,
                        std::vector<double>* surface /* (L*L*3,) */) {
    double cx, cy;
    tri2quad(illu_xy_x, illu_xy_y, &cx, &cy);
    surface->assign(static_cast<size_t>(L) * L * 3, 0.0);
    for (int i = 0; i < L; ++i) {
        double tcx = (L > 1) ? static_cast<double>(i) / (L - 1) : 0.0;
        for (int j = 0; j < L; ++j) {
            double tcy = (L > 1) ? static_cast<double>(j) / (L - 1) : 0.0;
            double* o = &(*surface)[(static_cast<size_t>(i) * L + j) * 3];
            for (int c = 0; c < 3; ++c) {
                const double* pp = &surface_params[static_cast<size_t>(c) * cols];
                double raw = poly2d_deg4(tcx, tcy, pp, cx, cy);
                o[c] = hanika_sigmoid(raw, kHanatos2025MaxCorrectionStops);
            }
        }
    }
}

}  // namespace

namespace {

// 1-D Gaussian blur of the spectra LUT along its spectral axis (axis 2), in
// float64, mirroring scipy.ndimage.gaussian_filter(lut, sigma=(0,0,sigma)) with
// the SciPy defaults order=0, mode='reflect', truncate=4.0. The first two LUT
// axes get sigma 0 (no blur). Ports compute_hanatos2025_tc_lut's optional blur.
//
// SciPy's gaussian_filter1d: radius lw = int(truncate*sigma + 0.5); the symmetric
// weights w[k] = exp(-0.5*(k/sigma)^2) for k in [-lw,lw], normalised; the result
// is correlate1d with mode='reflect' (d c b a | a b c d | d c b a). The kernel is
// symmetric so correlate == convolve. All arithmetic stays in double to match
// the oracle's np.double LUT path.
NdArray blur_spectra_lut_spectral(const NdArray& lut, double sigma) {
    const int ni = lut.shape[0];
    const int nj = lut.shape[1];
    const int ns = lut.shape[2];  // spectral axis length

    // SciPy: lw = int(truncate*sd + 0.5), truncate=4.0.
    int lw = static_cast<int>(4.0 * sigma + 0.5);
    if (lw < 0) lw = 0;
    if (lw == 0) return lut;  // degenerate kernel {1.0} -> identity

    // Weights: exp(-0.5/sigma^2 * x^2) over x in [-lw, lw], then normalise.
    const int ksize = 2 * lw + 1;
    std::vector<double> w(ksize);
    double wsum = 0.0;
    const double inv2s2 = 0.5 / (sigma * sigma);
    for (int k = 0; k < ksize; ++k) {
        double x = static_cast<double>(k - lw);
        double v = std::exp(-inv2s2 * x * x);
        w[k] = v;
        wsum += v;
    }
    for (int k = 0; k < ksize; ++k) w[k] /= wsum;

    NdArray out;
    out.shape = lut.shape;
    out.data.assign(lut.data.size(), 0.0);

    // reflect(i, n): scipy.ndimage mode='reflect'.
    auto reflect = [](int i, int n) -> int {
        if (i >= 0 && i < n) return i;
        if (i >= -n && i < 0) return -i - 1;
        if (i >= n && i < 2 * n) return 2 * n - 1 - i;
        int period = 2 * n;
        i = i % period;
        if (i < 0) i += period;
        if (i >= n) i = period - 1 - i;
        return i;
    };

    for (int i = 0; i < ni; ++i) {
        for (int j = 0; j < nj; ++j) {
            const double* in = &lut.data[(static_cast<size_t>(i) * nj + j) * ns];
            double* o = &out.data[(static_cast<size_t>(i) * nj + j) * ns];
            for (int s = 0; s < ns; ++s) {
                double acc = 0.0;
                for (int k = -lw; k <= lw; ++k) {
                    acc += w[k + lw] * in[reflect(s + k, ns)];
                }
                o[s] = acc;
            }
        }
    }
    return out;
}

}  // namespace

void filming_sensitivity_weights(const Profile& film, const double* illuminant,
                                 bool apply_window, const float* filter_uv,
                                 const float* filter_ir, const char* camera_filter,
                                 std::vector<double>* out_sw) {
    int S = film.n_samples;  // 81
    // sensitivity = nan_to_num(10**log_sensitivity)  (S,3)
    std::vector<double> sens(static_cast<size_t>(S) * 3);
    for (int s = 0; s < S; ++s)
        for (int c = 0; c < 3; ++c) {
            double ls = static_cast<double>(film.log_sensitivity[s * 3 + c]);
            double v = std::pow(10.0, ls);
            if (std::isnan(v) || std::isinf(v)) v = 0.0;  // np.nan_to_num
            sens[s * 3 + c] = v;
        }

    // Camera colour (Wratten) filter, Silverphase core/hanatos.cpp::
    // film_sensitivity: one factor for the whole film, the green-sensitive
    // layer's, against the film reference illuminant -- so a colour film keeps
    // the filter's cast and, on a one-emulsion film (three equal layers), 18%
    // grey keeps its exposure: the filter factor is given. Applied before the
    // UV/IR band-pass below, exactly as there. No filter -> untouched.
    if (const CameraFilter* f = find_camera_filter(camera_filter)) {
        if (S == 81) {   // the tables are on the 380..780 @5nm working shape
            double num = 0.0, den = 0.0;
            for (int s = 0; s < S; ++s) {
                num += sens[s * 3 + 1] * f->t[s] * illuminant[s];
                den += sens[s * 3 + 1] * illuminant[s];
            }
            const double factor = (den > 0.0 && num > 0.0) ? num / den : 1.0;
            for (int s = 0; s < S; ++s)
                for (int c = 0; c < 3; ++c) sens[s * 3 + c] *= f->t[s] / factor;
        }
    }

    // Camera UV/IR band-pass cut filter (filming.py::_rgb_to_film_raw, applied to
    // `sensitivity` BEFORE it is handed to compute_hanatos2025_tc_lut). Gated on
    // `filter_uv[0] > 0 OR filter_ir[0] > 0` exactly like the oracle, so the
    // default (amp 0/0) is a strict no-op. The band-pass is common across channels;
    // per-channel white-balance normalisation preserves the channel response to the
    // film reference illuminant:
    //   norm[c] = sum_s sens[s,c]*band[s]*illu[s] / sum_s sens[s,c]*illu[s]
    //   sens[s,c] *= band[s] / norm[c]
    // `illuminant` is the film reference illuminant (the same SPD the window
    // normalisation uses), matching standard_illuminant(film.reference_illuminant).
    if (filter_uv != nullptr && filter_ir != nullptr &&
        (filter_uv[0] > 0.0f || filter_ir[0] > 0.0f)) {
        std::vector<double> band;
        eval_camera_band_pass(filter_uv, filter_ir, film.wavelengths, &band);
        double bnum[3] = {0, 0, 0}, bden[3] = {0, 0, 0};
        for (int s = 0; s < S; ++s) {
            double ill = illuminant[s];
            for (int c = 0; c < 3; ++c) {
                double se = sens[s * 3 + c];
                bnum[c] += se * band[s] * ill;
                bden[c] += se * ill;
            }
        }
        double bnorm[3];
        for (int c = 0; c < 3; ++c) bnorm[c] = bnum[c] / bden[c];
        for (int s = 0; s < S; ++s)
            for (int c = 0; c < 3; ++c)
                sens[s * 3 + c] *= band[s] / bnorm[c];
    }

    // Per-channel sensitivity weights folded into the spectra contraction. When
    // apply_window is on, this is sens[s,c]*window[s]/norm[c] (white-balance-
    // preserving erf4 band-pass); otherwise it is the bare sensitivity. Mirrors
    // compute_hanatos2025_tc_lut's apply_window branch.
    std::vector<double>& sw = *out_sw;
    sw.assign(static_cast<size_t>(S) * 3, 0.0);
    // A profile with no fitted window (the black-and-white stocks ship none:
    // their sensitivities are the datasheet's, with no Hanatos fit behind them)
    // takes the bare-sensitivity branch, as if the window were switched off.
    // Every colour profile carries the 4 erf4 parameters, so their path is
    // unchanged.
    if (apply_window && film.window_params.size() == 4) {
        // window (common across channels) via erf4, then per-channel white-balance
        // normalisation: window[s,c] /= norm[c].
        std::vector<double> window;
        eval_erf4_window(film.window_params.data(), film.wavelengths, &window);

        double num[3] = {0, 0, 0}, den[3] = {0, 0, 0};
        for (int s = 0; s < S; ++s) {
            double ill = illuminant[s];
            for (int c = 0; c < 3; ++c) {
                double se = sens[s * 3 + c];
                num[c] += se * ill * window[s];
                den[c] += se * ill;
            }
        }
        double norm[3];
        for (int c = 0; c < 3; ++c) norm[c] = num[c] / den[c];

        for (int s = 0; s < S; ++s)
            for (int c = 0; c < 3; ++c)
                sw[s * 3 + c] = sens[s * 3 + c] * window[s] / norm[c];
    } else {
        for (int s = 0; s < S; ++s)
            for (int c = 0; c < 3; ++c) sw[s * 3 + c] = sens[s * 3 + c];
    }

}

NdArray build_filming_tc_lut(const Profile& film, const NdArray& spectra_lut_in,
                             const double* illuminant,
                             float spectral_gaussian_blur, bool apply_window,
                             bool apply_surface, const float* filter_uv,
                             const float* filter_ir,
                             InputGamutCompress input_gamut_compress,
                             double in_gamut_knee_threshold,
                             double in_gamut_knee_limit,
                             double in_gamut_knee_power,
                             const char* camera_filter) {
    // Optional spectral-domain blur of the spectra LUT (default 0 -> no-op). Done
    // up front, before the sensitivity contraction, matching upstream
    // compute_hanatos2025_tc_lut.
    NdArray spectra_lut_blurred;
    const bool do_blur = spectral_gaussian_blur > 0.0f;
    if (do_blur) {
        spectra_lut_blurred =
            blur_spectra_lut_spectral(spectra_lut_in,
                                      static_cast<double>(spectral_gaussian_blur));
    }
    const NdArray& spectra_lut = do_blur ? spectra_lut_blurred : spectra_lut_in;

    int S = film.n_samples;  // 81
    // The film eye: the per-channel sensitivity weights folded into the spectra
    // contraction (camera colour filter, UV/IR band-pass, adaptation window).
    std::vector<double> sw;
    filming_sensitivity_weights(film, illuminant, apply_window, filter_uv, filter_ir,
                                camera_filter, &sw);

    // tc_lut[i,j,c] = sum_s spectra_lut[i,j,s] * sens_window[s,c]
    int L = spectra_lut.shape[0];
    int Ssl = spectra_lut.shape[2];
    NdArray tc_lut;
    tc_lut.shape = {L, L, 3};
    tc_lut.data.assign(static_cast<size_t>(L) * L * 3, 0.0);
    for (int i = 0; i < L; ++i) {
        for (int j = 0; j < L; ++j) {
            const double* spec = &spectra_lut.data[(static_cast<size_t>(i) * L + j) * Ssl];
            double acc[3] = {0, 0, 0};
            for (int s = 0; s < Ssl; ++s) {
                double sp = spec[s];
                acc[0] += sp * sw[s * 3 + 0];
                acc[1] += sp * sw[s * 3 + 1];
                acc[2] += sp * sw[s * 3 + 2];
            }
            double* o = &tc_lut.data[(static_cast<size_t>(i) * L + j) * 3];
            o[0] = acc[0];
            o[1] = acc[1];
            o[2] = acc[2];
        }
    }

    // Optional log-exposure-correction surface: raw_lut *= 2**surface, where
    // surface is the per-cell, per-channel poly4 correction over the (L, L) tc grid
    // evaluated at the film's reference illuminant chromaticity. Mirrors
    // compute_hanatos2025_tc_lut's apply_surface branch (model='poly4'). The
    // reference illuminant chromaticity is computed from the SAME illuminant SPD
    // used for the window normalisation, integrated against the CIE 1931 2deg CMFs
    // (xy is scale-invariant, so the SPD's normalisation is irrelevant).
    if (apply_surface && film.surface_params_cols > 0 &&
        static_cast<int>(film.surface_params.size()) == 3 * film.surface_params_cols) {
        double xyz[3] = {0, 0, 0};
        for (int s = 0; s < S; ++s) {
            double ill = illuminant[s];
            xyz[0] += ill * static_cast<double>(kCieCmf1931[s][0]);
            xyz[1] += ill * static_cast<double>(kCieCmf1931[s][1]);
            xyz[2] += ill * static_cast<double>(kCieCmf1931[s][2]);
        }
        double sum_xyz = xyz[0] + xyz[1] + xyz[2];
        double illu_xy_x = xyz[0] / sum_xyz;
        double illu_xy_y = xyz[1] / sum_xyz;

        std::vector<double> surface;
        eval_poly4_surface(film.surface_params.data(), film.surface_params_cols, L,
                           illu_xy_x, illu_xy_y, &surface);
        size_t ncell = static_cast<size_t>(L) * L * 3;
        for (size_t k = 0; k < ncell; ++k) {
            tc_lut.data[k] *= std::pow(2.0, surface[k]);
        }
    }

    // Optional INPUT gamut compression bake (opt-in; default kOff -> skipped, so the
    // tc_lut is byte-identical to the pre-feature build and every golden stays
    // bit-exact). Mirrors compute_hanatos2025_tc_lut appending
    // remap_tc_lut_for_compression when InputGamutCompressSpec.active: radially
    // compress each LUT cell's chromaticity toward the visible spectral locus around
    // the film reference illuminant, then resample the LUT. The reference illuminant
    // chromaticity is computed from the SAME illuminant SPD integrated against the CIE
    // 1931 2deg CMFs (xy is scale-invariant), matching the surface branch above and
    // the oracle's _illuminant_to_xy(reference_illuminant). kOklch is reserved (not
    // ported) and falls through as a no-op.
    if (input_gamut_compress == InputGamutCompress::kXy) {
        double xyz[3] = {0, 0, 0};
        for (int s = 0; s < S; ++s) {
            double ill = illuminant[s];
            xyz[0] += ill * static_cast<double>(kCieCmf1931[s][0]);
            xyz[1] += ill * static_cast<double>(kCieCmf1931[s][1]);
            xyz[2] += ill * static_cast<double>(kCieCmf1931[s][2]);
        }
        double sum_xyz = xyz[0] + xyz[1] + xyz[2];
        double white_xy[2] = {xyz[0] / sum_xyz, xyz[1] / sum_xyz};
        remap_tc_lut_for_compression(tc_lut, white_xy, in_gamut_knee_threshold,
                                     in_gamut_knee_limit, in_gamut_knee_power);
    }
    return tc_lut;
}

namespace {

// Metal's smoothstep(e0, e1, x): Hermite on clamp((x - e0) / (e1 - e0), 0, 1).
inline double msl_smoothstep(double e0, double e1, double x) {
    double t = (x - e0) / (e1 - e0);
    t = t < 0.0 ? 0.0 : (t > 1.0 ? 1.0 : t);
    return t * t * (3.0 - 2.0 * t);
}

// spk_smoothstep01 (shaders/nodes.metal): Hermite on clamp(t, 0, 1).
inline double smoothstep01(double t) {
    t = t < 0.0 ? 0.0 : (t > 1.0 ? 1.0 : t);
    return t * t * (3.0 - 2.0 * t);
}

constexpr double kToneMidgray = 0.184;  // slm::kMidgray
// ProPhoto RGB (D50) -> XYZ, the Y row, with no illuminant adaptation: the
// luminance Silverphase reads from rgb_to_xyz_ae_ (RGB_to_XYZ of the input
// space, autoexposure._luminance_y) for the engine's only input space, linear
// ProPhoto RGB. Same constants as model/scene_latitude.cpp.
constexpr double kToneYr = 0.2880402;
constexpr double kToneYg = 0.7118741;
constexpr double kToneYb = 0.0000857;

// Silverphase pipeline.cpp kHeadroomClipFromEv / kHeadroomClipToEv
// (core/halation_psf.hpp).
constexpr double kHeadroomClipFromEv = -1.0;
constexpr double kHeadroomClipToEv = -0.25;

bool headroom_wanted(const HalationParams& hal) {
    return hal.active && hal.back_reflection && hal.headroom_ev > 0.0 &&
           hal.halation_amount > 0.0 && hal.headroom_to_ev > hal.headroom_from_ev;
}

// Silverphase node_headroom + spk_highlight_headroom, on the float64 post-boost
// irradiance (w*h*3, interleaved). `clip` is the frame's maximum element.
// Argentroom 1.0.1 deviation from Silverphase (user-chosen, 2026-11-16, "B"):
// the per-pixel gain exponent is feathered with a Gaussian of half the base
// thickness before it is applied. Silverphase gates each pixel on its own
// value, which is smooth for its RAW decode's rounded lamps but leaves a hard
// x16 step around a phone JPEG's flat clipped plateau. The exponent is built
// on a coarse grid (block mean, then blur, then bilinear), so the feather costs
// ~1/f^2 of a plane of memory. sigma_px <= 0 keeps the upstream per-pixel gate.
constexpr double kHeadroomFeatherOverBase = 0.5;

void apply_highlight_headroom(double* raw, int w, int h, const HalationParams& hal,
                              double clip, double pixel_size_um) {
    if (!(clip > 0.0)) return;
    const double p0 = 1.0 / clip;
    const double p1 = kHeadroomClipFromEv;
    const double p2 = 1.0 / (kHeadroomClipToEv - kHeadroomClipFromEv);
    const double p3 = hal.headroom_ev;
    const double p4 = 1.0 / kToneMidgray;
    const double p5 = hal.headroom_from_ev;
    const double p6 = 1.0 / (hal.headroom_to_ev - hal.headroom_from_ev);
    const double lp0 = std::log2(p0), lp4 = std::log2(p4);
    const int npix = w * h;
    auto exponent_at = [&](const double* v) -> double {
        const double m = std::max(v[0], std::max(v[1], v[2]));
        if (!(m > 0.0)) return 0.0;
        const double lm = std::log2(m);
        const double near_clip = smoothstep01((lm + lp0 - p1) * p2);
        const double over_floor = smoothstep01((lm + lp4 - p5) * p6);
        return p3 * near_clip * over_floor;
    };
    const double sigma_px = (pixel_size_um > 0.0 && std::isfinite(pixel_size_um))
        ? kHeadroomFeatherOverBase * kBackReflectionBaseThicknessUm / pixel_size_um
        : 0.0;
    if (!(sigma_px >= 0.75) || w < 2 || h < 2) {
        parallel_for(0, npix, [&](int lo, int hi) {
            for (int i = lo; i < hi; ++i) {
                double* v = raw + static_cast<size_t>(i) * 3;
                const double e = exponent_at(v);
                if (e > 0.0) {
                    const double g = std::exp2(e);
                    v[0] *= g; v[1] *= g; v[2] *= g;
                }
            }
        });
        return;
    }
    const int f = std::max(1, static_cast<int>(std::floor(sigma_px / 2.0)));
    const int cw = (w + f - 1) / f, ch = (h + f - 1) / f;
    std::vector<double> coarse(static_cast<size_t>(cw) * ch, 0.0);
    parallel_for(0, ch, [&](int lo, int hi) {
        for (int cy = lo; cy < hi; ++cy) {
            const int y0 = cy * f, y1 = std::min(h, y0 + f);
            for (int cx = 0; cx < cw; ++cx) {
                const int x0 = cx * f, x1 = std::min(w, x0 + f);
                double s = 0.0;
                for (int y = y0; y < y1; ++y)
                    for (int x = x0; x < x1; ++x)
                        s += exponent_at(raw + (static_cast<size_t>(y) * w + x) * 3);
                coarse[static_cast<size_t>(cy) * cw + cx] = s / double((y1 - y0) * (x1 - x0));
            }
        }
    });
    gaussian_blur_plane_d(coarse.data(), cw, ch, sigma_px / f);
    parallel_for(0, h, [&](int lo, int hi) {
        for (int y = lo; y < hi; ++y) {
            const double fy = std::clamp((y + 0.5) / f - 0.5, 0.0, double(ch - 1));
            const int yA = static_cast<int>(fy), yB = std::min(ch - 1, yA + 1);
            const double ty = fy - yA;
            for (int x = 0; x < w; ++x) {
                const double fx = std::clamp((x + 0.5) / f - 0.5, 0.0, double(cw - 1));
                const int xA = static_cast<int>(fx), xB = std::min(cw - 1, xA + 1);
                const double tx = fx - xA;
                const double* r0 = coarse.data() + static_cast<size_t>(yA) * cw;
                const double* r1 = coarse.data() + static_cast<size_t>(yB) * cw;
                const double e = (r0[xA] * (1 - tx) + r0[xB] * tx) * (1 - ty) +
                                 (r1[xA] * (1 - tx) + r1[xB] * tx) * ty;
                if (e > 1e-6) {
                    double* v = raw + (static_cast<size_t>(y) * w + x) * 3;
                    const double g = std::exp2(e);
                    v[0] *= g; v[1] *= g; v[2] *= g;
                }
            }
        }
    });
}

}  // namespace

// spk_camera_tone (shaders/scene_latitude.metal), per triple, in double. The
// luminance is of max(rgb, 0); a non-positive or non-finite luminance passes
// through unchanged.
void camera_tone_apply(const CameraToneParams& tp, double c[3]) {
    const double y = kToneYr * std::max(c[0], 0.0) + kToneYg * std::max(c[1], 0.0) +
                     kToneYb * std::max(c[2], 0.0);
    if (!(y > 0.0 && std::isfinite(y))) return;
    const double stops = std::log2(y) - std::log2(kToneMidgray);
    const double distance = std::fabs(stops);
    const bool up = stops >= 0.0;
    const double region = (up ? tp.highlights : tp.shadows) / 100.0;
    const double end = (up ? tp.whites : tp.blacks) / 100.0;
    const double region_gain = region * msl_smoothstep(0.0, 2.25, distance);
    // Whites have a wider useful adjustment on the shoulder of the film. Keep
    // the black endpoint at its existing response.
    const double end_weight = up ? msl_smoothstep(0.2, 4.2, distance)
                                 : msl_smoothstep(0.5, 4.0, distance);
    const double end_gain = (up ? 1.55 : 1.25) * end;
    const double gain = region_gain + (end_gain - region_gain) * end_weight;  // mix()
    const double k = std::exp2(gain);
    c[0] *= k;
    c[1] *= k;
    c[2] *= k;
}

namespace {

// Pixel sources for expose(). Each returns the float64 RGB triple for pixel p.
//   - SrcF64: the materialized float64 image (the historical path).
//   - SrcF32Gain: the caller's float32 frame with the auto-exposure gain folded
//     into the load. Value-identical to materializing: float->double widening
//     is exact and `* gain` is the same double multiply apply_auto_exposure
//     performed in place, in the same order (EXPORT_FASTPATH item 4 — a 12 MP
//     export no longer materializes the ~288 MB float64 input at all).
struct SrcF64 {
    const double* rgb;
    inline void load(int p, double out[3]) const {
        out[0] = rgb[p * 3 + 0];
        out[1] = rgb[p * 3 + 1];
        out[2] = rgb[p * 3 + 2];
    }
};
struct SrcF32Gain {
    const float* rgb;
    double gain;
    inline void load(int p, double out[3]) const {
        out[0] = static_cast<double>(rgb[p * 3 + 0]) * gain;
        out[1] = static_cast<double>(rgb[p * 3 + 1]) * gain;
        out[2] = static_cast<double>(rgb[p * 3 + 2]) * gain;
    }
};

// Scene latitude (opt-in): wraps any source and maps each loaded triple with
// scene_latitude_apply. Only constructed when the stage is engaged.
template <typename Src>
struct SrcLatitude {
    const Src& src;
    const SceneLatitudeParams& sl;
    inline void load(int p, double out[3]) const {
        src.load(p, out);
        scene_latitude_apply(sl, out);
    }
};

// Camera tone (opt-in): wraps the latitude-mapped source and scales each loaded
// triple by camera_tone_apply's gain. Only constructed when the stage is engaged.
template <typename Src>
struct SrcTone {
    const Src& src;
    const CameraToneParams& tone;
    inline void load(int p, double out[3]) const {
        src.load(p, out);
        camera_tone_apply(tone, out);
    }
};

// Date-back imprint (opt-in): adds the LED's light, coverage * light, to each
// loaded triple. Wraps the latitude-mapped source, because the LED is not part
// of the scene. Only constructed when the mask is non-empty.
template <typename Src>
struct SrcDate {
    const Src& src;
    const DateStampMask& m;
    int width;
    inline void load(int p, double out[3]) const {
        src.load(p, out);
        const int y = p / width;
        const float c = m.at(p - y * width, y);
        if (c > 0.0f) {
            out[0] += static_cast<double>(c) * m.light[0];
            out[1] += static_cast<double>(c) * m.light[1];
            out[2] += static_cast<double>(c) * m.light[2];
        }
    }
};

template <typename Src>
void expose_impl(const Src& src, int width, int height,
                 const FilmingParams& params, const NdArray& tc_lut,
                 float* log_raw_out,
                 // The same source the Src adapter wraps, handed to the GPU pass
                 // directly: exactly one is non-null, and `gpu_gain` is the
                 // expose_f32_gain auto-exposure factor (1.0 on the f64 path).
                 const double* gpu_rgb_f64, const float* gpu_rgb_f32,
                 double gpu_gain) {
    int npix = width * height;
    int L = tc_lut.shape[0];
    double scale = static_cast<double>(L - 1);
    double exp_mult = std::pow(2.0, params.exposure_compensation_ev);  // 1.0 under goldens

    // When every effect between the irradiance computation and the log10 is
    // inert — each gate below mirrors that effect's own self-gate exactly —
    // the two passes fuse: per pixel, raw is a local and goes straight through
    // the log10 into the float32 output. Identical arithmetic on identical
    // operands (the ops between the passes were no-ops), so the output is
    // byte-identical; and no full-resolution float64 `raw` exists at all
    // (~288 MB at 12 MP, EXPORT_FASTPATH item 4). Any active effect takes the
    // materialized path below, unchanged.
    const bool pointwise_fused =
        !params.overscan &&
        !(params.halation.boost_ev > 0.0) &&        // apply_highlight_boost gate
        !params.diffusion_filter.active &&           // diffusion filter gate
        !(params.lens_blur_um > 0.0 && params.pixel_size_um > 0.0 &&
          params.lens_blur_um / params.pixel_size_um > 0.0) &&  // lens blur gate
        !params.halation.active &&                   // halation gate (and headroom,
                                                     // which needs halation active)
        params.halation.boost_max_out == nullptr &&  // a measuring pre-pass
        params.bw_exposure_correction == 1.0;        // b/w correction gate
    // GPU EXPOSE (#218). Tried first in both branches; on refusal nothing has
    // been written and the CPU loop below runs over the same memory. The latch
    // is off unless the caller set it, so the default route is unchanged.
    // The banded pre-pass measures the CPU irradiance, so it stays on the CPU.
    const bool measure_boost_max = params.halation.boost_max_out != nullptr;
    const bool banded_diffusion = params.diffusion_filter.active &&
                                  params.diffusion_filter.band_far != nullptr;
    const bool gpu_expose_ok =
        params.allow_gpu_filming && !measure_boost_max && !banded_diffusion &&
        // tungsten_reference: the GPU expose now selects the same CAT02 3200 K
        // matrix by flag (C06), so T-balanced stocks no longer fall back.
        !params.scene_latitude.engaged() &&  // the GPU expose has no latitude stage
        !params.camera_tone.engaged() &&     // ...no camera tone (CPU fallback)
        !params.date_stamp.engaged() &&      // ...and no date-back imprint
        !params.overscan &&
        tc_lut.shape.size() == 3 &&
        tc_lut.shape[0] >= 2 && tc_lut.shape[0] == tc_lut.shape[1] &&
        tc_lut.shape[2] == 3;

    if (pointwise_fused) {
        ScopedStage _t(STG_FILMING_EXPOSE);
        if (gpu_expose_ok) {
            gpu::FilmingStageDiagnostics gd{};
            const bool ok = gpu::filming_expose(
                gpu_rgb_f64, gpu_rgb_f32, gpu_gain,
                static_cast<uint32_t>(npix), width, height, tc_lut.data.data(),
                static_cast<uint32_t>(tc_lut.shape[0]), exp_mult,
                /*out_raw=*/nullptr, log_raw_out, &gd, params.tungsten_reference);
            note_gpu_filming("expose_fused", gd);
            if (ok) return;
        }
        parallel_for(0, npix, [&](int lo, int hi) {
            for (int p = lo; p < hi; ++p) {
                double in[3];
                src.load(p, in);
                Vec2 tc;
                double b;
                prophoto_rgb_to_tc_b(in, params.tungsten_reference, &tc, &b);
                double rr[3];
                cubic_interp_lut_at_2d(tc_lut, tc.x * scale, tc.y * scale, rr);
                for (int c = 0; c < 3; ++c) {
                    double rawv = rr[c] * b * exp_mult;
                    log_raw_out[p * 3 + c] = static_cast<float>(
                        std::log10(std::fmax(rawv, 0.0) + 1e-10));
                }
            }
        });
        return;
    }

    // Compute the float64 pre-log irradiance `raw` for every pixel.
    // raw = rgb_to_raw_hanatos2025(rgb) * brightness * 2^exposure_comp_ev.
    // Allocated UNINITIALIZED (not a zero-filled vector): the loop below writes
    // every element before anything reads it, so the old value-initialization
    // only cost a ~288 MB memset at 12 MP (EXPORT_FASTPATH item 4).
    std::unique_ptr<double[]> raw_buf(new double[static_cast<size_t>(npix) * 3]);
    double* raw = raw_buf.get();
    { ScopedStage _t(STG_FILMING_EXPOSE);
    bool done = false;
    if (gpu_expose_ok) {
        gpu::FilmingStageDiagnostics gd{};
        done = gpu::filming_expose(
            gpu_rgb_f64, gpu_rgb_f32, gpu_gain, static_cast<uint32_t>(npix),
            width, height, tc_lut.data.data(),
            static_cast<uint32_t>(tc_lut.shape[0]), exp_mult,
            raw, /*out_log_raw=*/nullptr, &gd, params.tungsten_reference);
        note_gpu_filming("expose", gd);
    }
    if (!done)
    parallel_for(0, npix, [&](int lo, int hi) {
        for (int p = lo; p < hi; ++p) {
            double in[3];
            src.load(p, in);
            Vec2 tc;
            double b;
            prophoto_rgb_to_tc_b(in, params.tungsten_reference, &tc, &b);
            double rr[3];
            cubic_interp_lut_at_2d(tc_lut, tc.x * scale, tc.y * scale, rr);
            for (int c = 0; c < 3; ++c) raw[p * 3 + c] = rr[c] * b * exp_mult;
        }
    }); }

    // Highlight boost (numba_boost_hightlights.boost_highlights), applied on the
    // float64 irradiance AFTER exposure compensation and BEFORE the diffusion filter
    // / lens blur / halation — matching filming.py::expose, which calls
    // boost_highlights(raw, halation.boost_ev, halation.boost_range,
    // halation.protect_ev) right after `raw *= 2**exposure_compensation_ev`. It is
    // NOT gated by params.spatial_effects: the oracle's digest_params zeroes only the
    // scatter/halation sigmas under deactivate_spatial_effects (params_builder.py),
    // never boost_ev, so the boost fires whenever boost_ev > 0. boost_ev == 0
    // (schema/UI default) is a strict identity -> default goldens stay bit-exact.
    if (measure_boost_max) {
        // Banded pre-pass (spektra.cpp run_banded): report this band's share of
        // the frame maximum the boost scales by, and stop. Nothing downstream
        // runs on a measuring render, so log_raw_out is deliberately unwritten.
        *params.halation.boost_max_out = highlight_boost_max(raw, width, height);
        return;
    }
    { ScopedStage _t(STG_HIGHLIGHT_BOOST);
      apply_highlight_boost(raw, width, height, params.halation,
                            params.allow_gpu_filming); }

    // CineStill highlight headroom (Silverphase node_headroom, run right after
    // node_boost in film_boost and before the overscan / diffusion / lens blur /
    // halation): the energy a digital frame clipped off, given back to a film
    // with no anti-halation layer so its halo is that of the scene's light.
    // Scaled by the frame's own post-boost maximum (whole-frame statistic; a band
    // takes the frame's from run_banded). Every other film is untouched.
    if (headroom_wanted(params.halation)) {
        ScopedStage _t(STG_HIGHLIGHT_BOOST);
        const double clip = params.halation.headroom_frame_max > 0.0
                                ? params.halation.headroom_frame_max
                                : highlight_boost_max(raw, width, height);
        apply_highlight_headroom(raw, width, height, params.halation, clip, params.pixel_size_um);
    }

    if (params.overscan) {
        apply_overscan_exposure(*params.overscan, raw_buf, width, height, params.overscan_band);
        raw = raw_buf.get();
        npix = width * height;
    }

    // Camera optical diffusion filter (Black Pro-Mist family), applied on the
    // float64 irradiance AFTER the highlight boost and BEFORE lens blur /
    // halation — matching filming.py::expose, which calls
    // apply_diffusion_filter_um(raw, camera.diffusion_filter, pixel_size_um)
    // before apply_gaussian_blur_um / apply_halation_um. Self-gated on
    // diffusion_filter.active — the oracle's ONLY gate (deactivate_spatial_effects
    // is expressed by the digest zeroing .active, params_builder.py). No-op unless
    // active (schema default false), so default params stay bit-exact.
    if (banded_diffusion) {
        // One band of a frame above the whole-frame capacity (spektra.cpp
        // run_banded): the near/far split of diffusion.h. The accumulating
        // pre-pass only feeds this band's rows to the coarse frame and stops;
        // like the boost pre-pass it leaves log_raw_out unwritten.
        const DiffusionFilterParams& d = params.diffusion_filter;
        if (d.band_accumulate) {
            diffusion_far_accumulate(raw, width, height, d.band_frame_y0, d.band_far);
            return;
        }
        ScopedStage _t(STG_DIFFUSION);
        apply_diffusion_filter_banded(raw, width, height, d.band_frame_y0, *d.band_far,
                                      params.allow_gpu_diffusion_fft);
    } else if (params.diffusion_filter.active) {
        ScopedStage _t(STG_DIFFUSION);
        // Off by default and nothing sets the latch -- see params.h for the device
        // numbers that took it off the Fast GPU route. The GPU pass returns false
        // without touching `raw` on any refusal, so this falls back rather than
        // half-applying, and with the latch clear it is the FFT unconditionally.
        if (!(params.allow_gpu_diffusion_filter &&
              gpu_diffusion_pass(raw, width, height, params.diffusion_filter,
                                 params.pixel_size_um))) {
            apply_diffusion_filter_um(raw, width, height,
                                      params.diffusion_filter, params.pixel_size_um,
                                      params.allow_gpu_diffusion_fft);
        }
    }

    // Camera lens blur (camera.lens_blur_um), applied on the float64 irradiance
    // AFTER the diffusion filter and BEFORE halation — matching filming.py::expose,
    // which calls apply_gaussian_blur_um(raw, camera.lens_blur_um, pixel_size_um)
    // between apply_diffusion_filter_um and apply_halation_um. apply_gaussian_blur_um
    // gates on sigma = lens_blur_um / pixel_size_um > 0 and blurs every channel with
    // the same scalar sigma (fast_gaussian_filter broadcasts the scalar across the
    // 3 channels). Self-gated on lens_blur_um > 0 — the oracle's only gate
    // (deactivate_spatial_effects is expressed by the digest zeroing lens_blur_um).
    // Default lens_blur_um == 0 => no-op (bit-exact).
    if (params.lens_blur_um > 0.0 &&
        params.pixel_size_um > 0.0) {
        double sigma = params.lens_blur_um / params.pixel_size_um;
        if (sigma > 0.0) {
            ScopedStage _t(STG_LENS_BLUR);
            double sg[3] = {sigma, sigma, sigma};
            // CPU, for the same measured reason as the scanner blurs (see
            // runtime/stages/scanning.cpp): routing a small FIR through the
            // mixture kernel costs a whole-frame f64 staging vector and two
            // conversions, which is more than the blur itself.
            // Same knob as the scanner blurs (runtime/stages/scanning.cpp):
            // the GPU route is correct and gated, and its last export
            // measurement predates the work buffers becoming GPU-private.
            // Same override as the scanner blurs (runtime/stages/scanning.cpp).
            const char* gv = std::getenv("SPK_GPU_BLUR");
            const bool want_gpu = (gv && gv[0] == '0')   ? false
                                  : (gv && gv[0] == '1') ? true
                                                         : params.allow_gpu_filming;
            if (!(want_gpu && gpu::gaussian_blur_rgb(raw, width, height, sg)))
                gaussian_blur_per_channel_d(raw, width, height, 3, sg);
        }
    }

    // In-emulsion scatter + back-reflection halation, applied to the float64
    // irradiance before the log10 (matching filming.py::expose, which calls
    // apply_halation_um on `raw`). Self-gated on halation.active (set by
    // digest_halation_params only when the spatial digest is on and the preset is
    // known), so the spatial-OFF goldens still skip it.
    if (params.halation.active) {
        ScopedStage _t(STG_HALATION);
        // Fast GPU export (#206): the same scatter + halation model on the
        // GPU (gpu/halation_scatter.comp, adapted from spektrafilm OFX, f32).
        // The result lands in a private buffer and replaces `raw` only when
        // the whole pass succeeded; any failure or refusal falls through to the
        // f64 CPU pass, which is the ground truth and stays byte-identical.
        if (!(params.allow_gpu_halation &&
              gpu_halation_pass(raw, width, height, params.halation,
                                params.pixel_size_um))) {
            apply_halation_um(raw, width, height, params.halation,
                              params.pixel_size_um);
        }
    }

    // Scanner BLACK/WHITE filming exposure correction (color_reference.py::
    // black_white_filming_exposure_correction), applied here as filming.py does
    // (`raw *= black_white_filming_exposure_correction()`, after halation, before
    // log10). 1.0 (a strict no-op) on every route except scan_film + positive film,
    // so the default goldens (negative film) stay bit-exact.
    if (params.bw_exposure_correction != 1.0) {
        const double c = params.bw_exposure_correction;
        parallel_for(0, npix * 3, [&](int lo, int hi) {
            for (int i = lo; i < hi; ++i) raw[i] *= c;
        });
    }

    // log_raw = log10(fmax(raw, 0) + 1e-10). Element-wise -> deterministic
    // parallel chunks (byte-identical to the serial loop for any thread count).
    {
        gpu::FilmingStageDiagnostics gd{};
        if (gpu_expose_ok &&
            gpu::exposure_log10(raw, log_raw_out, width, height, &gd)) {
            note_gpu_filming("log10", gd);
            return;
        }
        note_gpu_filming("log10", gd);
    }
    parallel_for(0, npix * 3, [&](int lo, int hi) {
        for (int i = lo; i < hi; ++i) {
            double lr = std::log10(std::fmax(raw[i], 0.0) + 1e-10);
            log_raw_out[i] = static_cast<float>(lr);
        }
    });
}

// Composes the opt-in pre-tc stages around `src`: scene latitude first (it
// belongs to the scene), then the date-back light. With both off this is the
// bare source, so the default route is unchanged.
template <typename Src>
void expose_staged(const Src& src, int width, int height, const FilmingParams& params,
                   const NdArray& tc_lut, float* log_raw_out, const double* gpu_rgb_f64,
                   const float* gpu_rgb_f32, double gpu_gain) {
    DateStampMask date;
    if (params.date_stamp.engaged()) {
        // A band of a larger frame places the imprint in FRAME coordinates and
        // shifts it into the band's rows; at() returns 0 outside the box.
        const bool banded = params.date_stamp.frame_width > 0;
        date = build_date_stamp_mask(
            params.date_stamp, banded ? params.date_stamp.frame_width : width,
            banded ? params.date_stamp.frame_height : height,
            params.exposure_compensation_ev);
        if (banded) date.y0 -= params.date_stamp.frame_y0;
    }
    const bool latitude = params.scene_latitude.engaged();
    const bool tone = params.camera_tone.engaged();
    // Camera tone sits after scene latitude (Silverphase film_scale_and_expose:
    // latitude, then tone, then the spectral upsample); the date-back light is
    // not part of the scene, so it goes on after both.
    auto with_date = [&](const auto& s) {
        if (!date.empty()) {
            using S = std::decay_t<decltype(s)>;
            expose_impl(SrcDate<S>{s, date, width}, width, height, params, tc_lut,
                        log_raw_out, gpu_rgb_f64, gpu_rgb_f32, gpu_gain);
        } else {
            expose_impl(s, width, height, params, tc_lut, log_raw_out, gpu_rgb_f64,
                        gpu_rgb_f32, gpu_gain);
        }
    };
    auto with_tone = [&](const auto& s) {
        if (tone) {
            using S = std::decay_t<decltype(s)>;
            with_date(SrcTone<S>{s, params.camera_tone});
        } else {
            with_date(s);
        }
    };
    if (latitude) {
        with_tone(SrcLatitude<Src>{src, params.scene_latitude});
    } else {
        with_tone(src);
    }
}

}  // namespace

void expose(const double* rgb, int width, int height, const FilmingParams& params,
            const NdArray& tc_lut, float* log_raw_out) {
    expose_staged(SrcF64{rgb}, width, height, params, tc_lut, log_raw_out, rgb, nullptr,
                  1.0);
}

void expose_f32_gain(const float* rgb, double gain, int width, int height,
                     const FilmingParams& params, const NdArray& tc_lut,
                     float* log_raw_out) {
    expose_staged(SrcF32Gain{rgb, gain}, width, height, params, tc_lut, log_raw_out,
                  nullptr, rgb, gain);
}

void develop(const float* log_raw, int width, int height, const Profile& film,
             const FilmingParams& params, float* density_cmy_out,
             FloatBuffer* release_log_raw) {
    int n = film.n_density_pts;
    const int npix = width * height;

    // normalized_density_curves = density_curves - nanmin(density_curves, axis=0)
    std::vector<float> ndc(static_cast<size_t>(n) * 3);
    normalize_density_curves(film.density_curves.data(), n, ndc.data());

    // density_cmy = interpolate_exposure_to_density(log_raw, ndc, le, gamma)
    { ScopedStage _t(STG_DEVELOP);
      bool done = false;
      if (params.allow_gpu_filming && n >= 2) {
          // The GPU kernel owns no parameter, so the per-channel axis
          // interpolate_exposure_to_density builds internally --
          // xp[k,c] = log_exposure[k] / gamma[c] -- is built here instead.
          const float* g = params.density_curve_gamma;
          std::vector<float> xp(static_cast<size_t>(n) * 3);
          for (int k = 0; k < n; ++k)
              for (int c = 0; c < 3; ++c)
                  xp[static_cast<size_t>(k) * 3 + c] =
                      (g[c] != 0.0f) ? (film.log_exposure[k] / g[c])
                                     : film.log_exposure[k];
          gpu::FilmingStageDiagnostics gd{};
          done = gpu::filming_develop(log_raw, static_cast<uint32_t>(npix),
                                      width, height, xp.data(), ndc.data(),
                                      static_cast<uint32_t>(n),
                                      density_cmy_out, &gd);
          note_gpu_filming("develop", gd);
      }
      if (!done)
      interpolate_exposure_to_density(log_raw, npix, ndc.data(),
                                      film.log_exposure.data(), n,
                                      params.density_curve_gamma, density_cmy_out); }

    // apply_density_correction_dir_couplers. The spatial variant diffuses the
    // inhibitor correction (Gaussian + exponential tail); it self-gates and
    // delegates to the pointwise path when diffusion_size_um == 0 (the digest
    // zeroes the size when the spatial digest is off, mirroring the oracle's
    // deactivate_spatial_effects).
    { ScopedStage _t(STG_DIR_COUPLERS);
      apply_density_correction_dir_couplers_spatial(
          density_cmy_out, width, height, log_raw, film.log_exposure.data(),
          ndc.data(), n, params.dir_couplers, film.is_positive(),
          params.density_curve_gamma,
          params.pixel_size_um, density_cmy_out); }
    // log_raw's last reader was the coupler step above.
    if (release_log_raw) {
        log_raw = nullptr;
        FloatBuffer().swap(*release_log_raw);
    }

    // Stochastic grain (AgX particle model). Identity unless grain.active (set by
    // digest_grain_params when grain_active && stochastic effects are on). The
    // model operates on the post-coupler density_cmy, mirroring emulsion.py::
    // develop, which calls apply_grain after apply_density_correction_dir_couplers.
    //
    // Grain amount (Silverphase pipeline.cpp node_grain, RFC-025): a mix in
    // density of the grained image over the one that went in,
    // (1 - a) * in + a * grained. a == 1 does not run the mix (byte-identical to
    // the grain path as it always was) and a <= 0 skips the model entirely.
    const double grain_amount = params.grain.amount;
    if (params.grain.active && grain_amount > 0.0) {
        ScopedStage _t(STG_GRAIN);
        stage_timing_note_grain_sampler(params.grain.fast_sampler);
        // Pre-grain density, kept only when the mix will run.
        std::unique_ptr<float[]> grain_in;
        if (grain_amount != 1.0) {
            const size_t ne = static_cast<size_t>(npix) * 3;
            grain_in.reset(new float[ne]);
            float* dst = grain_in.get();
            parallel_for(0, static_cast<int>(ne), [&](int lo, int hi) {
                for (int i = lo; i < hi; ++i) dst[i] = density_cmy_out[i];
            });
        }
        GrainParams grain = params.grain;
        // The sublayer path is selected when sublayers_active AND the profile
        // actually carries density_curves_layers; otherwise fall back to the
        // non-sublayer path (mirrors apply_grain's structure, where the layers
        // are required for the sublayer branch).
        const bool have_layers =
            static_cast<int>(film.density_curves_layers.size()) == n * 9;
        if (grain.sublayers_active && have_layers) {
            // interp_density_cmy_layers(density_cmy, normalized_density_curves,
            //                           density_curves_layers_RAW, positive_film).
            // NOTE: apply_grain passes the RAW (un-normalized) density_curves_layers
            // here, while density_curves is the normalized curve axis.
            // 450 MB at 12.5 MP, left uninitialised: the GPU or the CPU interp
            // below writes every element. Timed with the fill as one cost.
            std::unique_ptr<float[]> layers;
            {
                ScopedGrainPhase _p(GrainPhase::Layers);
                layers.reset(new float[static_cast<size_t>(npix) * 9]);
                // Nine interpolations per pixel -- 112M at 12.5 MP, and the
                // largest CPU item left in this stage. Its output is the GPU
                // grain sampler's input, so the 450 MB buffer is on the wrong
                // side of the bus while this runs on the host (#223).
                gpu::FilmingStageDiagnostics gd{};
                const bool on_gpu =
                    params.allow_gpu_filming &&
                    gpu::grain_layers(density_cmy_out, static_cast<uint32_t>(npix),
                                      width, height, ndc.data(),
                                      film.density_curves_layers.data(),
                                      static_cast<uint32_t>(n), film.is_positive(),
                                      layers.get(), &gd);
                note_gpu_filming("grain_layers", gd);
                if (!on_gpu)
                    interp_density_cmy_layers(density_cmy_out, npix, ndc.data(),
                                              film.density_curves_layers.data(), n,
                                              film.is_positive(), layers.get());
            }
            // density_max_layers[sl,c] = nanmax over the log-exposure axis of the
            // RAW density_curves_layers (NOT normalized).
            double density_max_layers[9];
            for (int sl = 0; sl < 3; ++sl) {
                for (int c = 0; c < 3; ++c) {
                    double mx = -1.0;
                    bool any = false;
                    for (int k = 0; k < n; ++k) {
                        float v = film.density_curves_layers[
                            static_cast<size_t>(k) * 9 + sl * 3 + c];
                        if (std::isnan(v)) continue;
                        if (!any || v > mx) { mx = v; any = true; }
                    }
                    density_max_layers[sl * 3 + c] = any ? mx : 2.2;
                }
            }
            apply_grain_to_density_layers(layers.get(), npix, width, height,
                                          density_max_layers, params.pixel_size_um,
                                          grain, density_cmy_out, &layers);
        } else {
            // Non-sublayer path: density_max_curves[c] = nanmax(ndc[:,c]).
            for (int c = 0; c < 3; ++c) {
                double mx = -1.0;
                bool any = false;
                for (int k = 0; k < n; ++k) {
                    float v = ndc[static_cast<size_t>(k) * 3 + c];
                    if (std::isnan(v)) continue;
                    if (!any || v > mx) { mx = v; any = true; }
                }
                grain.density_max_curves[c] = any ? mx : 2.2;
            }
            apply_grain_to_density(density_cmy_out, npix, width, height,
                                   params.pixel_size_um, grain, density_cmy_out);
        }
        if (grain_in) {
            // blur_.lincomb(in, grained, a = 1 - k, b = k): out = a*in + b*grained,
            // with Silverphase's float32 coefficients.
            const float ka = static_cast<float>(1.0 - grain_amount);
            const float kb = static_cast<float>(grain_amount);
            const float* in0 = grain_in.get();
            const int ne = npix * 3;
            parallel_for(0, ne, [&](int lo, int hi) {
                for (int i = lo; i < hi; ++i)
                    density_cmy_out[i] = ka * in0[i] + kb * density_cmy_out[i];
            });
        }
    }
}

}  // namespace spk
