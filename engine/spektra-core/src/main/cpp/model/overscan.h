// Silverphase v1.2.5 physical film-edge port. SPDX-License-Identifier: GPL-3.0-or-later
#pragma once

#include "spektra.h"
#include <cstdint>
#include <memory>
#include <string>
#include <vector>

namespace spk {
struct Profile;
struct OverscanLayout {
    bool valid = false;
    std::string format;
    double px = 0.0;                      // mm per pixel (the frame's pitch)
    uint32_t frame_w = 0, frame_h = 0;
    uint32_t canvas_w = 0, canvas_h = 0;
    bool vertical = false;                // the film runs along the image's y axis
    double gate_s0 = 0, gate_t0 = 0, gate_along = 0, gate_across = 0;
    double pair_adv = 0;                  // > 0: a half-frame pair, the same gate again this far along
    double corner = 0, penumbra = 0;
    double wob_a[4][3] = {{0}}, wob_ph[4][3] = {{0}};
    double film_w = 0;
    double A[4] = {1, 0, 0, 1};           // canvas mm (about its centre) -> film mm
    double cu = 0, cv = 0, cs = 0, ct = 0;
    double scan_rot = 0;
    bool perforated = false;
    double perf_pitch = 0, perf_w = 0, perf_h = 0, perf_edge = 0, perf_r = 0, perf_phase = 0;
    double fog_amp = 0, fog_width = 0.5, fog_period = 8.0;
    uint32_t fog_seed = 0;
    struct Leak { double s = 0; int side = 0; double amp = 0, sig_s = 1, sig_t = 1; };
    std::vector<Leak> leaks;
    double fog_rgb[3] = {1, 1, 1}, date_rgb[3] = {1, 1, 0}, edge_rgb[3] = {1, 1, 0};
    double hole_cmy[3] = {0, 0, 0};
    double flare_amp = 0, flare_width = 0.05;
    // RFC-032 §29: the gate's own shape -- corner radii (s-,t-), (s+,t-),
    // (s+,t+), (s-,t+); convex quads added to (+1) or cut from (-1) the
    // opening, film mm; the machined edge's roughness.
    std::string gate_family;
    double gate_r[4] = {0, 0, 0, 0};
    struct Quad { double pts[8] = {0}; double round = 0; double sign = 1; };
    std::vector<Quad> quads;
    double rough_amp = 0, rough_period = 0.15;
    uint32_t rough_seed = 0;
    double top_recess = 0;                // how far the t- side's middle sits back from its ears (shouldered)
    bool holes_light = false;
    bool carrier_open = false;            // past the film's long edges: the scan's light, not a black carrier
    bool carrier_none = false;            // no carrier: the canvas ends at the film's edge
    double light_rgb[3] = {1, 1, 1};      // the backlight through the scan, linear output RGB
    double light_fall = 0, light_dir = 0; // the light panel's falloff across the canvas
    uint32_t perf_seed = 0;               // per-hole punch tolerances, burrs, wall slant
    // RFC-032 §31: the base's thickness at a perforation's cut wall
    double wall_t = 0.13;                 // base thickness, mm
    double wall_tilt[2] = {0, 0};         // the scan's viewing tilt, tan(angle) along (s, t)
    double wall_parallax = 0;             // 1 / the scan's viewing distance, 1/mm
    double base_rgb[3] = {1, 1, 1};       // the base's transmittance colour, linear output RGB
    double wall_glow = 0;                 // black holes: the wall's rim brightness
    double wall_scatter = 0.02;           // light scattered in the base widens the wall, mm
};

struct OverscanMask {
    int x = 0, y = 0, width = 0, height = 0;
    float exposure[3]{};
    std::vector<float> coverage;
};

// Empty (inactive) layouts never enter the exposure kernels.
struct OverscanPlan {
    OverscanLayout layout;
    const spk_params* params = nullptr;
    const Profile* film = nullptr;
    std::vector<float> kernel;
    std::vector<OverscanMask> masks;
};

// A strip of one global negative, in physical canvas coordinates. The source
// rows are a bounded window of the metered, gate-cropped scene.
struct OverscanBand {
    const OverscanPlan* plan = nullptr;
    int y = 0, rows = 0;
    int source_y = 0;
};

bool date_imprint_wanted(const spk_params& p);
bool prepare_overscan(const spk_params& p, const Profile& film, const Profile& scanned,
                      const double* reference, int w, int h, double pitch_um, OverscanPlan& out, std::string& error);
void apply_overscan_exposure(const OverscanPlan& plan, std::unique_ptr<double[]>& raw,
                             int& width, int& height, const OverscanBand* band = nullptr);
void prepare_overscan_masks(OverscanPlan& plan);
void overscan_source_rows(const OverscanPlan& plan, int y0, int y1,
                          int& source_y0, int& source_y1);
void apply_overscan_negative(const OverscanPlan& plan, float* density, int y = 0, int rows = 0);
void apply_overscan_scan_light(const OverscanPlan& plan, float* rgb, bool encode,
                              int y = 0, int rows = 0);
void overscan_crop(const spk_params& p, int& width, int& height);
}  // namespace spk
