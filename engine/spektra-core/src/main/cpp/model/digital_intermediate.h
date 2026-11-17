/*
 * Spektrafilm for Android — native engine: Silverphase's Digital Intermediate.
 * Copyright (C) 2026 Spektrafilm Android contributors.
 *
 * This program is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation, either version 3 of the License, or (at your option) any later
 * version. See <https://www.gnu.org/licenses/>.
 *
 * Port of Silverphase v1.2.5 (GPL-3.0-or-later; RFC-028..030, after Hanze Qiu's
 * SpektraLab) engine/src/core/digital_intermediate.{hpp,cpp}, the per-pixel
 * chain of pipeline.cpp node_digital_intermediate (spk_spectral_epilogue with a
 * zero base and log out -> spk_curves -> spk_di_encode), and the canvas's "DI
 * view" (modern_UI Canvas/CineonLUT.swift, the table iOS's MobileColorPipeline
 * cineonView samples).
 *
 * The DI replaces the paper. It reads the developed negative the way the paper
 * would -- in printing density, through the film's own target paper's spectral
 * sensitivity under that paper's enlarger lamp -- but with the film base removed
 * wavelength by wavelength. Each channel is then reversed on the film's own
 * neutral curve (so a grey is exactly neutral at every exposure) on Cineon's
 * negative gamma 0.6, mapped to ProPhoto RGB with one 3x3 fitted on the
 * upsampler's spectra inside Pointer's gamut, and written as Kodak Cineon log.
 * Everything is derived from data at build time; nothing from the picture.
 *
 * Android differences (stated, not hidden):
 *  - the neutral wedge runs through THIS engine's own film chain (expose +
 *    develop with every spatial / stochastic effect off), and the film eye is
 *    this engine's (its tc_lut is always built against D55), exactly as
 *    Silverphase uses its own pipeline's tc_lut/tc_b -- so the wedge sees what a
 *    flat patch of the Android image sees;
 *  - the optional blue-sector compensation (digital_intermediate_blue_
 *    compensation; iOS preference "diBlueCompensation", default off) is ported
 *    with its fit; Oklab's D65 white is colour-science's nominal xy
 *    (0.3127, 0.3290) where Silverphase integrates its D65 spectrum (a ~1e-5
 *    difference in xy);
 *  - the working space is ProPhoto RGB, as Silverphase's app always sets
 *    output_color_space for its renders; the DI view then goes through this
 *    engine's own output encode (ProPhoto -> output space, CCTF) where iOS hands
 *    the ROMM-tagged canvas to ColorSync.
 */
#ifndef SPK_MODEL_DIGITAL_INTERMEDIATE_H
#define SPK_MODEL_DIGITAL_INTERMEDIATE_H

#include <functional>
#include <string>
#include <vector>

#include "io/npy_lut.h"
#include "kernels/tonecurve.h"
#include "profiles/profile.h"
#include "spektra.h"  // spk_color_space

namespace spk {

// Kodak's Cineon convention (digital_intermediate.hpp).
constexpr double kDiCineonWhite = 685.0;
constexpr double kDiCineonBlack = 95.0;
constexpr double kDiCineonPerDecade = 300.0;
constexpr double kDiCineonMax = 1023.0;
constexpr double kDiCineonNegativeGamma = 0.002 * kDiCineonPerDecade;  // 0.6
double di_cineon_black_offset();
double di_cineon_decode(double code01);

struct DiConstants {
    std::string paper;                 // the stock whose sensitivity defines printing density
    std::vector<double> chd;           // (81,3) film dye spectra, invalid wavelengths zeroed
    std::vector<double> ixs;           // (81,3) printing-density responsivity, columns sum to 1
    size_t k = 0;                      // wedge length
    std::vector<double> curve_x;       // (k,3) log10 T, ascending per channel
    std::vector<double> curve_y;       // (k)   log10 positive (the same for every channel)
    double matrix[9] = {1, 0, 0, 0, 1, 0, 0, 0, 1};  // film RGB -> ProPhoto RGB, x @ M
    double matrix_fit_rms = 0.0;
    double positive_floor = 0.0;       // the positive of clear base (subtracted: base is black)
    double gamma_green = 0.0;          // the film's printing-density gamma (diagnostic)
    double density_green_mid = 0.0;
    // Oklab for the optional blue-sector compensation: ProPhoto (working) ->
    // LMS (Ottosson M1 . RGB->XYZ adapted to D65 with CAT02), and back.
    double to_lms[9] = {0, 0, 0, 0, 0, 0, 0, 0, 0};
    double from_lms[9] = {0, 0, 0, 0, 0, 0, 0, 0, 0};
    double blue_delta_deg = 0.0;       // hue rotation at the window's centre
    double blue_kappa = 1.0;           // chroma scale at the window's centre
};

// The blue-sector window (digital_intermediate.hpp), in Oklab hue degrees.
constexpr double kDiBlueHueCentre = 280.0;
constexpr double kDiBlueHueHalfWidth = 55.0;
constexpr double kDiBlueChromaLo = 0.02;
constexpr double kDiBlueChromaHi = 0.08;

struct DiInputs {
    const Profile* film = nullptr;
    const Profile* paper = nullptr;           // the film's target_print, else the print profile
    const double* paper_lamp = nullptr;       // (81) the paper's reference (enlarger) illuminant
    const double* film_reference = nullptr;   // (81) the film eye's reference illuminant
    const std::vector<double>* weights = nullptr;  // (81,3) filming_sensitivity_weights
    const NdArray* spectra_lut = nullptr;     // (L,L,81) the Hanatos upsampling spectra
    // The film chain on flat fields: `rgb` linear ProPhoto patches (n*3) in,
    // their developed layer density (n*3, the scan's density_cmy) out.
    std::function<bool(const std::vector<double>& rgb, std::vector<float>* cmy)> wedge;
};

// Silverphase di_constants. False (with `error`) when a profile cannot carry a
// DI (no paper sensitivity where the film is defined, no neutral contrast, too
// few spectra for the colour fit).
bool di_constants(const DiInputs& in, DiConstants* out, std::string* error);

// Silverphase di_live_offsets: the per-channel log10 offsets the live print
// controls give the DI positive (print_exposure as true stops; +1 M = +0.03
// stop of green, +1 Y = +0.05 stop of blue; none on a black-and-white film).
void di_live_offsets(double print_exposure, double m_filter_shift, double y_filter_shift,
                     bool monochrome, double out[3]);

// The per-pixel DI on `npix` density_cmy triples. blue_compensation: the
// fitted Oklab hue/chroma correction inside the blue window. master: write the Cineon
// codes (0..1) as is. Otherwise: the DI view (Cineon -> ProPhoto RGB, ROMM
// encoded, as Silverphase's canvas shows it), then this engine's output encode
// into `cs` (ProPhoto -> XYZ D50 -> output RGB with the viewing CAT, the
// near-identity round trip + CCTF when `cctf`, clip to [0,1], tone curve).
void di_render(const DiConstants& k, const double live[3], const float* density_cmy,
               int npix, bool master, bool blue_compensation, spk_color_space cs, bool cctf,
               const ToneCurveSet* tone_curve, float* rgb_out);

// The DI view on one Cineon code triple -> ROMM-encoded ProPhoto RGB, exactly
// CineonLUT.view(_, to: .proPhoto) (the generator of the shipped .cube), and the
// texel-centred trilinear lookup of its 65^3 table (what the canvas samples).
// Exposed for verification.
void di_view_exact(const double code[3], double out[3]);
void di_view_lut(const double code[3], double out[3]);
constexpr int kDiViewLutSize = 65;

}  // namespace spk

#endif  // SPK_MODEL_DIGITAL_INTERMEDIATE_H
