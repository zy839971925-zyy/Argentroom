// Argentroom film-edge integration checks. SPDX-License-Identifier: GPL-3.0-or-later
#include "spektra.h"

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <vector>

namespace {
struct Image : spk_image {
    Image() : spk_image{} {}
    ~Image() { spk_image_free(this); }
};

void require(bool ok, const char* description) {
    std::printf("[%s] %s\n", ok ? "PASS" : "FAIL", description);
    if (!ok) std::exit(1);
}

bool equal(const Image& x, const Image& y) {
    return x.width == y.width && x.height == y.height &&
           std::memcmp(x.data, y.data, size_t(x.width) * x.height * 3 * sizeof(float)) == 0;
}

double max_pixel_error(const Image& a, const Image& b) {
    if (a.width != b.width || a.height != b.height) return INFINITY;
    double error = 0.0;
    for (size_t i = 0; i < size_t(a.width) * a.height * 3; ++i)
        error = std::max(error, std::abs(double(a.data[i]) - b.data[i]));
    return error;
}

void render(spk_engine* engine, const spk_image& src, const spk_params& p, Image& out,
            const char* tap = nullptr) {
    const auto status = tap ? spk_simulate_tap(engine, &src, &p, tap, &out)
                            : spk_simulate(engine, &src, &p, &out);
    if (status != SPK_OK) std::fprintf(stderr, "render status %d\n", int(status));
    require(status == SPK_OK && out.data, tap ? tap : "render");
    for (size_t i = 0; i < size_t(out.width) * out.height * 3; ++i)
        if (!std::isfinite(out.data[i])) require(false, "finite pixels");
}
}  // namespace

int main(int argc, char** argv) {
    if (argc < 2) return 2;
    spk_engine* engine = nullptr;
    require(spk_engine_create(argv[1], &engine) == SPK_OK, "open engine");
    constexpr int w = 480, h = 320;
    std::vector<float> scene(size_t(w) * h * 3);
    for (int y = 0; y < h; ++y)
        for (int x = 0; x < w; ++x)
            for (int c = 0; c < 3; ++c)
                scene[(size_t(y) * w + x) * 3 + c] =
                    0.02f + 0.2f * (float(x) / w + float(y) / h + float(c) / 4);
    spk_image src{scene.data(), w, h, SPK_CS_PROPHOTO};
    spk_params p{};
    spk_default_params(&p);
    p.film_profile = "kodak_portra_400";
    p.print_profile = "kodak_portra_endura";
    p.auto_exposure = 0;
    p.grain_active = 0;
    p.halation_active = 0;

    for (int scan : {0, 1}) {
        p.scan_film = scan;
        Image plain, ignored;
        render(engine, src, p, plain);
        p.overscan_fog = 4;
        p.overscan_format = "120_6x17";
        p.date_imprint_text = "'26 11 15";
        render(engine, src, p, ignored);
        require(equal(plain, ignored), "inactive fields leave pixels byte-identical");

        p.overscan_format = "135";
        p.overscan_active = 1;
        Image raw, density, white, black, again;
        render(engine, src, p, raw, "film_log_raw");
        render(engine, src, p, density, "film_density_cmy");
        render(engine, src, p, white);
        require(raw.width == white.width && density.height == white.height &&
                white.width > w && white.height > h, "film look is already present in negative geometry");
        p.overscan_holes = "black";
        render(engine, src, p, black);
        require(!equal(white, black), "scanner sees white and black perforations differently");
        p.overscan_holes = "white";
        render(engine, src, p, again);
        require(equal(white, again), "same seeds return the same film strip after edits");

        p.overscan_active = 0;
        Image no_date, lcd, dots;
        render(engine, src, p, no_date, "film_log_raw");
        p.date_imprint_active = 1;
        render(engine, src, p, lcd, "film_log_raw");
        p.date_imprint_style = "dots";
        render(engine, src, p, dots, "film_log_raw");
        require(lcd.width == no_date.width && lcd.height == no_date.height &&
                !equal(lcd, no_date) && !equal(lcd, dots), "LCD and dot date are distinct negative exposures");
        p.date_imprint_active = 0;
        p.date_imprint_style = "lcd";
    }
    const char* formats[] = {"135", "135_half", "120_645", "120_6x6", "120_6x7",
                             "120_6x8", "120_6x9", "135_xpan", "120_6x12", "120_6x17"};
    p.overscan_active = 1;
    p.scan_film = 0;
    p.camera_diffusion_active = 1;
    p.halation_active = 1;
    p.grain_active = 1;
    for (const char* format : formats) {
        p.overscan_format = format;
        p.overscan_mode = "strip";
        p.date_imprint_active = 0;
        Image normal;
        render(engine, src, p, normal);
        p.date_imprint_active = 1;
        p.date_imprint_style = "data";
        p.date_imprint_placement = "rebate";
        p.date_imprint_text = "1/125 F4.5";
        Image with_data;
        render(engine, src, p, with_data);
        const bool panorama = std::strstr(format, "xpan") || std::strstr(format, "6x12") ||
                              std::strstr(format, "6x17");
        require(equal(normal, with_data) == panorama, format);
    }
    p.overscan_format = "135";
    p.date_imprint_active = 0;
    Image base, fog, leak, frame, body, filed, carrier;
    p.overscan_fog = 0;
    render(engine, src, p, base);
    p.overscan_fog = 4;
    render(engine, src, p, fog);
    require(!equal(base, fog), "edge fog modifies exposure");
    p.overscan_leaks = 4;
    render(engine, src, p, leak);
    require(!equal(fog, leak), "spool leak modifies exposure");
    p.overscan_frame_seed = 12345;
    render(engine, src, p, frame);
    require(!equal(leak, frame), "film advance seed changes the strip");
    p.overscan_camera_seed = 543;
    render(engine, src, p, body);
    require(!equal(frame, body), "camera seed changes the strip");
    p.overscan_carrier = "open";
    render(engine, src, p, carrier);
    require(!equal(body, carrier), "open carrier changes the scan boundary");
    p.overscan_mode = "filed";
    render(engine, src, p, filed);
    require(filed.height < carrier.height, "filed carrier shows a narrower rebate");
    p = {};
    spk_default_params(&p);
    p.film_profile = "kodak_portra_400";
    p.print_profile = "kodak_portra_endura";
    p.disable_buffer_memos = 1;
    p.glare_active = p.print_glare_active = 0;
    p.halation_active = p.grain_active = 0;
    p.date_imprint_active = 1;
    p.date_imprint_text = "'26 11 15";
    for (int scan : {0, 1}) {
        p.scan_film = scan;
        for (const char* format : {"date_only", "135", "120_6x7", "135_xpan"}) {
            p.overscan_active = std::strcmp(format, "date_only") != 0;
            p.overscan_format = p.overscan_active ? format : "135";
            p.overscan_mode = "strip";
            p.overscan_fog = p.overscan_leaks = 2.f;
            for (bool portrait : {false, true}) {
                spk_image src2{scene.data(), portrait ? h : w, portrait ? w : h, SPK_CS_PROPHOTO};
                Image whole, bands;
                render(engine, src2, p, whole);
                // Force the same route a 50 MP export takes. Whole-frame processing
                // would return an OOM error once the cap is below the source area.
                spk_set_whole_frame_pixel_limit(int64_t(w) * h - 1);
                spk_set_band_render_limits(1, 70000);
                const auto before = spk_band_render_bands();
                render(engine, src2, p, bands);
                require(spk_band_render_bands() > before + 1, "rendered in multiple bands");
                spk_set_band_render_limits(0, 0);
                spk_set_whole_frame_pixel_limit(0);
                const double error = max_pixel_error(whole, bands);
                std::printf("  %s %s %s max RGB error %.6g: ", scan ? "scan" : "print",
                            format, portrait ? "portrait" : "landscape", error);
                // Native banding has a bounded Gaussian/IIR overlap tolerance.
                require(error <= 1e-4, "banded gate, rebates and date match whole frame");
            }
        }
    }
    constexpr int neutral_size = 16;
    std::vector<float> neutral(neutral_size * neutral_size * 3, 0.18f);
    spk_image gray{neutral.data(), neutral_size, neutral_size, SPK_CS_PROPHOTO};
    p.auto_exposure = p.overscan_active = p.date_imprint_active = p.scan_film = 0;
    for (const char* film : {"cinestill_800t", "kodak_vision3_500t", "kodak_vision3_200t",
                             "kodak_portra_400"}) {
        p.film_profile = film;
        Image result;
        render(engine, gray, p, result);
        const float* rgb = result.data + (neutral_size / 2 * (neutral_size + 1)) * 3;
        const float delta = std::max({rgb[0], rgb[1], rgb[2]}) - std::min({rgb[0], rgb[1], rgb[2]});
        std::printf("  %s midgray %.6f %.6f %.6f: ", film, rgb[0], rgb[1], rgb[2]);
        require(delta < 0.02f && rgb[0] > 0.2f && rgb[0] < 0.7f,
                "neutral midgray survives film and color-balanced print");
    }
    spk_engine_destroy(engine);
}
