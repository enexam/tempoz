#include "fft.h"

#include <cmath>
#include <utility>

void fftRadix2(std::vector<double>& re, std::vector<double>& im) {
    const size_t n = re.size();
    if (n < 2) return;

    // Bit-reversal permutation.
    for (size_t i = 1, j = 0; i < n; ++i) {
        size_t bit = n >> 1;
        for (; j & bit; bit >>= 1) {
            j ^= bit;
        }
        j ^= bit;
        if (i < j) {
            std::swap(re[i], re[j]);
            std::swap(im[i], im[j]);
        }
    }

    // Butterflies. Twiddle factors advance by a complex multiply (recurrence);
    // for n <= 2048 the accumulated error is ~1e-13, negligible for onsets.
    for (size_t len = 2; len <= n; len <<= 1) {
        const double ang = -2.0 * M_PI / static_cast<double>(len);
        const double wr = std::cos(ang);
        const double wi = std::sin(ang);
        const size_t half = len >> 1;
        for (size_t i = 0; i < n; i += len) {
            double cwr = 1.0, cwi = 0.0;
            for (size_t k = 0; k < half; ++k) {
                const double xr = re[i + k + half];
                const double xi = im[i + k + half];
                const double vr = xr * cwr - xi * cwi;
                const double vi = xr * cwi + xi * cwr;
                const double ur = re[i + k];
                const double ui = im[i + k];
                re[i + k] = ur + vr;
                im[i + k] = ui + vi;
                re[i + k + half] = ur - vr;
                im[i + k + half] = ui - vi;
                const double ncwr = cwr * wr - cwi * wi;
                cwi = cwr * wi + cwi * wr;
                cwr = ncwr;
            }
        }
    }
}

void rfftMagnitude(const std::vector<double>& frame, std::vector<double>& magOut) {
    const size_t n = frame.size();
    std::vector<double> re(frame);
    std::vector<double> im(n, 0.0);
    fftRadix2(re, im);
    const size_t half = n / 2;
    magOut.resize(half + 1);
    for (size_t k = 0; k <= half; ++k) {
        magOut[k] = std::hypot(re[k], im[k]);
    }
}
