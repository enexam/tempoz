#pragma once

#include <vector>

/**
 * Self-contained radix-2 FFT (double precision, to match numpy's float64 FFT).
 *
 * Only power-of-two transform sizes are supported, which is all the analyzer
 * needs (n_fft = 2048). No external dependency.
 */

/**
 * In-place iterative radix-2 forward DFT (sign = -1).
 * re and im must be the same length, a power of two.
 */
void fftRadix2(std::vector<double>& re, std::vector<double>& im);

/**
 * Magnitude spectrum of a real frame.
 *
 * @param frame  windowed real samples, length n (power of two)
 * @param magOut filled with n/2 + 1 magnitudes (DC .. Nyquist)
 */
void rfftMagnitude(const std::vector<double>& frame, std::vector<double>& magOut);
