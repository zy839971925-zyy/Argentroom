// r5: BatchedMt19937 must reproduce std::mt19937 word for word, and the libc++
// distributions the Strict route draws through StatsRng must return the same
// doubles from it. Any mismatch breaks the strict-CPU byte identity.
#include <cstdio>
#include <cstring>
#include <random>
#include "kernels/stats.h"

int main() {
    int fails = 0;
    const uint32_t seeds[] = {0u, 1u, 5489u, 12345u, 0xdeadbeefu, 0xffffffffu};
    for (uint32_t s : seeds) {
        std::mt19937 ref(s);
        spk::BatchedMt19937 got(s);
        for (long i = 0; i < 2000000; ++i) {
            if (ref() != got()) { std::printf("[FAIL] word %ld seed %u\n", i, s); ++fails; break; }
        }
        std::mt19937 r2(s);
        spk::BatchedMt19937 g2(s);
        std::uniform_real_distribution<double> ua(0.0, 1.0), ub(0.0, 1.0);
        std::normal_distribution<double> na(0.0, 1.0), nb(0.0, 1.0);
        for (long i = 0; i < 1000000; ++i) {
            double a = ua(r2), b = ub(g2);
            if (std::memcmp(&a, &b, sizeof a)) { std::printf("[FAIL] uniform %ld seed %u\n", i, s); ++fails; break; }
            a = na(r2); b = nb(g2);
            if (std::memcmp(&a, &b, sizeof a)) { std::printf("[FAIL] normal %ld seed %u\n", i, s); ++fails; break; }
        }
    }
    // StatsRng (Exact) vs a hand-held std::mt19937 + the same distributions.
    for (uint64_t seed : {uint64_t{7}, uint64_t{0x123456789abcdefull}}) {
        spk::StatsRng rng(seed, spk::StatsRng::Generator::Exact);
        std::mt19937 ref(static_cast<uint32_t>(seed));
        std::uniform_real_distribution<double> u(0.0, 1.0);
        std::normal_distribution<double> n(0.0, 1.0);
        for (long i = 0; i < 1000000; ++i) {
            double a = (i % 3) ? rng.uniform() : rng.normal();
            double b = (i % 3) ? u(ref) : n(ref);
            if (std::memcmp(&a, &b, sizeof a)) { std::printf("[FAIL] StatsRng draw %ld\n", i); ++fails; break; }
        }
    }
    std::printf(fails ? "test_batched_mt19937: FAILED\n" : "test_batched_mt19937: ALL OK\n");
    return fails ? 1 : 0;
}
