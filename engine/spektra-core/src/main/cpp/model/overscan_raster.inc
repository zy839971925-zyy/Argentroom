// Portable coverage rasteriser for the Silverphase film-space drawing ops.
// Included inside overscan.cpp's internal namespace.
struct GlyphSpec { int begin, end, advance; };
struct GlyphPoint { int16_t x, y; };
#include "overscan_glyphs.inc"

struct Point {
    double x, y;
};
using Contours = std::vector<std::vector<Point>>;

bool regular_face(const char* font) {
    return std::strcmp(font, "HelveticaNeue") == 0;
}

double text_width(const char* font, double size_mm, const std::string& text,
                  double tracking_mm, double hscale) {
    const auto* glyphs = regular_face(font) ? kEdgeRegularGlyphs : kEdgeBoldGlyphs;
    double width = 0;
    for (unsigned char c : text) {
        if (c >= 32 && c < 127) width += glyphs[c - 32].advance * size_mm / 2048 + tracking_mm;
    }
    return width * hscale;
}

Contours make_contours(const Op& op) {
    Contours contours;
    if (op.kind == Op::Poly) {
        auto& contour = contours.emplace_back();
        for (size_t i = 0; i + 1 < op.pts.size(); i += 2)
            contour.push_back({op.pts[i], op.pts[i + 1]});
        return contours;
    }
    if (op.kind == Op::Circle) {
        auto& contour = contours.emplace_back();
        constexpr int n = 20;
        for (int i = 0; i < n; ++i) {
            const double angle = (2 * M_PI / n) * i;
            contour.push_back({op.pts[0] + op.size_mm * std::cos(angle),
                               op.pts[1] + op.size_mm * std::sin(angle)});
        }
        return contours;
    }
    const auto* glyphs = regular_face(op.font) ? kEdgeRegularGlyphs : kEdgeBoldGlyphs;
    const auto* points = regular_face(op.font) ? kEdgeRegularPoints : kEdgeBoldPoints;
    const double angle = op.rot_deg * M_PI / 180;
    const double c = std::cos(angle), s = std::sin(angle);
    const double scale = op.size_mm / 2048;
    double pen = 0;
    for (unsigned char ch : op.text) {
        if (ch < 32 || ch > 126) continue;
        const auto& glyph = glyphs[ch - 32];
        bool new_contour = true;
        for (int i = glyph.begin; i < glyph.end; ++i) {
            if (points[i].x == 32767) {
                new_contour = true;
                continue;
            }
            if (new_contour) contours.emplace_back();
            new_contour = false;
            const double x = pen + points[i].x * scale * op.hscale;
            const double y = -points[i].y * scale;
            contours.back().push_back({op.s + c * x - s * y, op.t + s * x + c * y});
        }
        const double advance = glyph.advance * scale + op.tracking_mm;
        pen += advance * (op.scale_advances ? op.hscale : 1.0);
    }
    return contours;
}

struct CoverageShape {
    double gain;
    Contours contours;
    double left = 1e30, top = 1e30, right = -1e30, bottom = -1e30;
};

bool rasterise(const OverscanLayout& L, const Group& g, std::vector<float>& cov,
               int& x0, int& y0, int& w, int& h) {
    double left = 1e30, top = 1e30, right = -1e30, bottom = -1e30;
    std::vector<CoverageShape> shapes;
    for (const Op& op : g.ops) {
        CoverageShape shape{std::clamp(op.gain, 0.0, 1.0), make_contours(op)};
        for (auto& contour : shape.contours) {
            for (auto& p : contour) {
                film_to_canvas_px(L, p.x, p.y, p.x, p.y);
                shape.left = std::min(shape.left, p.x);
                shape.top = std::min(shape.top, p.y);
                shape.right = std::max(shape.right, p.x);
                shape.bottom = std::max(shape.bottom, p.y);
            }
        }
        if (shape.left > shape.right) continue;
        left = std::min(left, shape.left);
        top = std::min(top, shape.top);
        right = std::max(right, shape.right);
        bottom = std::max(bottom, shape.bottom);
        shapes.push_back(std::move(shape));
    }
    if (shapes.empty()) return false;
    const double pad = 3 + 3 * g.blur_mm / L.px;
    x0 = std::clamp(int(std::floor(left - pad)), 0, int(L.canvas_w));
    y0 = std::clamp(int(std::floor(top - pad)), 0, int(L.canvas_h));
    w = std::clamp(int(std::ceil(right + pad)), 0, int(L.canvas_w)) - x0;
    h = std::clamp(int(std::ceil(bottom + pad)), 0, int(L.canvas_h)) - y0;
    if (w <= 0 || h <= 0) return false;
    cov.assign(size_t(w) * h, 0);
    // Scanline even-odd fill. Four subrows and exact horizontal interval
    // coverage are enough to retain subpixel holes and narrow DX marks.
    std::vector<double> hits;
    std::vector<float> coverage(w);
    for (const auto& shape : shapes) {
        for (int y = std::max(y0, int(std::floor(shape.top)));
             y < std::min(y0 + h, int(std::ceil(shape.bottom))); ++y) {
            const int lo = std::max(x0, int(std::floor(shape.left)));
            const int hi = std::min(x0 + w, int(std::ceil(shape.right)));
            if (hi <= lo) continue;
            std::fill(coverage.begin() + (lo - x0), coverage.begin() + (hi - x0), 0);
            for (int sub = 0; sub < 4; ++sub) {
                const double sy = y + (sub + 0.5) / 4;
                hits.clear();
                for (const auto& contour : shape.contours) {
                    if (contour.size() < 3) continue;
                    auto a = contour.back();
                    for (const auto b : contour) {
                        if ((a.y <= sy) != (b.y <= sy))
                            hits.push_back(a.x + (b.x - a.x) * (sy - a.y) / (b.y - a.y));
                        a = b;
                    }
                }
                std::sort(hits.begin(), hits.end());
                for (size_t j = 1; j < hits.size(); j += 2) {
                    const double a = hits[j - 1], b = hits[j];
                    for (int x = std::max(lo, int(std::floor(a))); x < std::min(hi, int(std::ceil(b))); ++x)
                        coverage[x - x0] += float(std::min(b, x + 1.0) - std::max(a, double(x))) * 0.25f;
                }
            }
            for (int x = lo; x < hi; ++x) {
                float& dst = cov[size_t(y - y0) * w + (x - x0)];
                const float a = coverage[x - x0];
                dst += a * (float(shape.gain) - dst);
            }
        }
    }
    if (g.blur_mm / L.px > 0.3) {
        gaussian_blur_plane(cov.data(), w, h, float(g.blur_mm / L.px), 3.0f);
    }
    return true;
}
