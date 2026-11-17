/*
 * Argentroom for Android -- the back-reflection halo of a film with no
 * anti-halation layer (CineStill 800T), as a fixed Gaussian mixture per
 * sensitive layer in units of the base thickness. GPLv3.
 *
 * Transcribed verbatim from Silverphase v1.2.5 engine/src/core/halation_psf.hpp
 * (commit 061fc495; fitted by engine/tests/halation_psf_fit.py there -- rerun
 * that fit rather than editing these numbers by hand). Physics, in short: light
 * the emulsion scatters into the base comes back off the base's rear face
 * (Fresnel inside the critical angle, total internal reflection past it, plus
 * 10 % from the pressure plate) and re-exposes the emulsion in a disc with a
 * brighter rim at r_c = 2 d tan(theta_c) = 1.83 d and a cos^4 tail; green and
 * blue lose more on their slant path back, so the far halo is red's alone. Red
 * keeps half the halo's energy within 3.1 d and 90 % within 8.2 d -- about a
 * millimetre on a 120 um base, which a 65 um Gaussian never reproduces.
 *
 * The four Gaussians per layer have signed weights, constrained to stay
 * non-negative everywhere, to sum to 1 and to sum(|w|) <= 2 with sigmas at least
 * 1.35x apart, so the IIR Gaussian's approximation error is not amplified.
 */
#ifndef SPK_MODEL_HALATION_PSF_H
#define SPK_MODEL_HALATION_PSF_H

namespace spk {

constexpr int kBackReflectionComponents = 4;
// [component][red, green, blue]
constexpr double kBackReflectionSigmaOverBase[kBackReflectionComponents][3] = {
    {0.184586, 0.178469, 0.170237},
    {1.121207, 1.000078, 0.546805},
    {1.651048, 1.581389, 0.808738},
    {4.677567, 9.448921, 1.340598},
};
constexpr double kBackReflectionWeight[kBackReflectionComponents][3] = {
    {+0.041510, +0.106789, +0.202791},
    {-0.500000, -0.500000, +0.156578},
    {+1.034500, +1.384389, -0.500000},
    {+0.423990, +0.008822, +1.140631},
};

// The halo's total, per layer, as a fraction of the layer's own exposure (the
// kernels above are unit mass, so the slant-path loss of green and blue lives
// here). Silverphase apply_halation_preset uses these for a film whose OWN tag
// is antihalation "no"; an override to "none" keeps argentroom's no-backing
// preset (0.30, 0.10, 0.015) and its Gaussian bounces.
constexpr double kBackReflectionStrength[3] = {0.30, 0.03, 0.003};

// Silverphase HalationParams::base_thickness_um: a 35 mm acetate base, what a
// CineStill 800T scan measures (the flat orange band beside a tube light ends at
// r_c = 1.83 d = 0.21 mm).
constexpr double kBackReflectionBaseThicknessUm = 120.0;

}  // namespace spk

#endif  // SPK_MODEL_HALATION_PSF_H
