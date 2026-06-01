#include "bpm_dsp.h"

#include "fft.h"

#include <algorithm>
#include <cmath>

// ---------------------------------------------------------------------------
// Constants (identical to playground/pipeline/*.py)
// ---------------------------------------------------------------------------
static constexpr int    NFFT          = 2048;
static constexpr int    HOP           = 512;
static constexpr double LOW_TEMPO_HZ  = 500.0;   // kick/bass band for octave resolution
static constexpr double ACCENT_HZ     = 200.0;   // sub-bass band for downbeat accents
static constexpr double DETREND_S     = 1.5;     // onset-envelope detrend window

static constexpr double PRIOR_CENTER  = 120.0;   // log-Gaussian tempo prior center
static constexpr double PRIOR_SIGMA   = 0.9;     // prior width in octaves
static constexpr double BPM_MIN       = 40.0;
static constexpr double BPM_MAX       = 240.0;
static constexpr double GRID          = 0.5;     // coarse BPM search step
static constexpr double OCTAVE_TOL    = 0.2;

static constexpr double SNAP_WIN      = 0.25;    // beat-snap window (fraction of period)
static constexpr double SNAP_FLOOR    = 0.3;     // min onset to anchor the regression
static constexpr double REFINE_TOL    = 0.1;     // reject refined period beyond this fraction

static constexpr double GROOVE_FLOOR  = 0.05;    // skip leading beats below this * median onset

// ---------------------------------------------------------------------------
// Small helpers
// ---------------------------------------------------------------------------
static double median(std::vector<double> v) {
    if (v.empty()) return 0.0;
    std::sort(v.begin(), v.end());
    const size_t n = v.size();
    return (n & 1) ? v[n / 2] : 0.5 * (v[n / 2 - 1] + v[n / 2]);
}

// Centered moving average matching np.convolve(env, ones(w)/w, 'same'):
// edges are attenuated (divided by w over fewer terms).
static std::vector<double> movingAverageSame(const std::vector<double>& a, int w) {
    const int n = static_cast<int>(a.size());
    std::vector<double> out(n, 0.0);
    if (n == 0 || w < 1) return out;
    const int half = (w - 1) / 2;
    std::vector<double> prefix(n + 1, 0.0);
    for (int i = 0; i < n; ++i) prefix[i + 1] = prefix[i] + a[i];
    for (int i = 0; i < n; ++i) {
        const int lo = std::max(0, i - half);
        const int hi = std::min(n, i + half + 1);
        out[i] = (prefix[hi] - prefix[lo]) / static_cast<double>(w);
    }
    return out;
}

static std::vector<double> detrendRectify(const std::vector<double>& env, double fps) {
    int w = static_cast<int>(fps * DETREND_S) | 1;
    if (w < 1) w = 1;
    const std::vector<double> ma = movingAverageSame(env, w);
    const int n = static_cast<int>(env.size());
    std::vector<double> out(n);
    double sum = 0.0;
    for (int i = 0; i < n; ++i) {
        double v = env[i] - ma[i];
        if (v < 0.0) v = 0.0;
        out[i] = v;
        sum += v;
    }
    const double mean = n ? sum / n : 0.0;
    for (int i = 0; i < n; ++i) out[i] -= mean;
    return out;
}

static double lagAt(double bpm, double fps) { return 60.0 * fps / bpm; }

static double interp(const std::vector<double>& ac, double lag) {
    const int i = static_cast<int>(lag);
    if (i + 1 >= static_cast<int>(ac.size())) {
        return ac[std::min<int>(i, static_cast<int>(ac.size()) - 1)];
    }
    const double f = lag - i;
    return ac[i] * (1.0 - f) + ac[i + 1] * f;
}

static double interpArr(const std::vector<double>& env, double x) {
    int i = static_cast<int>(std::floor(x));
    i = std::max(0, std::min(i, static_cast<int>(env.size()) - 2));
    const double f = x - i;
    return env[i] * (1.0 - f) + env[i + 1] * f;
}

// ---------------------------------------------------------------------------
// Stage 1 — onset / accent envelopes (spectral flux)
// ---------------------------------------------------------------------------
struct Envelopes {
    std::vector<double> oe_full, oe_low, ae;
    double fps = 0.0;
};

static Envelopes extractOnset(const std::vector<float>& audio, int sampleRate) {
    Envelopes e;
    e.fps = static_cast<double>(sampleRate) / HOP;
    const int n = static_cast<int>(audio.size());
    const int nFrames = (n >= NFFT) ? 1 + (n - NFFT) / HOP : 0;
    if (nFrames < 1) return e;

    const double binHz = static_cast<double>(sampleRate) / NFFT;
    const int kLow = static_cast<int>(LOW_TEMPO_HZ / binHz) + 1;
    const int kAcc = static_cast<int>(ACCENT_HZ / binHz) + 1;
    const int nBins = NFFT / 2 + 1;

    std::vector<double> hann(NFFT);
    for (int i = 0; i < NFFT; ++i) {
        hann[i] = 0.5 - 0.5 * std::cos(2.0 * M_PI * i / (NFFT - 1));
    }

    std::vector<double> rawFull(nFrames, 0.0), rawLow(nFrames, 0.0);
    e.ae.assign(nFrames, 0.0);
    std::vector<double> prevLog(nBins, 0.0), logmag(nBins), frame(NFFT), mag;

    for (int t = 0; t < nFrames; ++t) {
        const int start = t * HOP;
        for (int i = 0; i < NFFT; ++i) frame[i] = static_cast<double>(audio[start + i]) * hann[i];
        rfftMagnitude(frame, mag);
        for (int k = 0; k < nBins; ++k) logmag[k] = std::log1p(mag[k]);

        if (t > 0) {
            double ff = 0.0, fl = 0.0;
            for (int k = 0; k < nBins; ++k) {
                const double d = logmag[k] - prevLog[k];
                if (d > 0.0) {
                    ff += d;
                    if (k < kLow) fl += d;
                }
            }
            rawFull[t] = ff;
            rawLow[t] = fl;
        }
        double acc = 0.0;
        for (int k = 0; k < kAcc; ++k) acc += mag[k];
        e.ae[t] = acc;

        prevLog.swap(logmag);
    }

    e.oe_full = detrendRectify(rawFull, e.fps);
    e.oe_low = detrendRectify(rawLow, e.fps);
    return e;
}

// ---------------------------------------------------------------------------
// Stage 2 — tempo (cross-band weighted ACF + octave resolution)
// ---------------------------------------------------------------------------
static std::vector<double> autocorr(const std::vector<double>& x, int maxLag) {
    const int N = static_cast<int>(x.size());
    std::vector<double> out(maxLag + 1, 0.0);
    for (int lag = 1; lag <= maxLag; ++lag) {
        const int lim = N - lag;
        if (lim <= 0) continue;
        double dot = 0.0;
        for (int i = 0; i < lim; ++i) dot += x[i] * x[i + lag];
        out[lag] = dot / static_cast<double>(lim);
    }
    return out;
}

static void weightedBpm(const std::vector<double>& env, double fps,
                        double& bpmOut, std::vector<double>& acOut) {
    const int maxLag = static_cast<int>(lagAt(BPM_MIN, fps)) + 2;
    acOut = autocorr(env, maxLag);
    const int steps = static_cast<int>((BPM_MAX - BPM_MIN) / GRID + 0.5);
    double bestScore = -1e300, bestBpm = BPM_MIN;
    for (int i = 0; i <= steps; ++i) {
        const double b = BPM_MIN + GRID * i;
        const double lg = std::log2(b / PRIOR_CENTER) / PRIOR_SIGMA;
        const double prior = std::exp(-0.5 * lg * lg);
        const double score = interp(acOut, lagAt(b, fps)) * prior;
        if (score > bestScore) {
            bestScore = score;
            bestBpm = b;
        }
    }
    bpmOut = bestBpm;
}

static double parabolicLag(const std::vector<double>& ac, double lag0) {
    const int l = static_cast<int>(std::lround(lag0));
    if (l <= 1 || l + 1 >= static_cast<int>(ac.size())) return lag0;
    const double a = ac[l - 1], b = ac[l], c = ac[l + 1];
    const double denom = a - 2.0 * b + c;
    if (denom == 0.0) return static_cast<double>(l);
    double delta = 0.5 * (a - c) / denom;
    if (std::fabs(delta) > 1.0) delta = 0.0;
    return static_cast<double>(l) + delta;
}

// Returns the coarse beat period in frames.
static double estimateTempoPeriod(const std::vector<double>& oe_full,
                                  const std::vector<double>& oe_low, double fps) {
    double tFull, tLow;
    std::vector<double> acFull, acLow;
    weightedBpm(oe_full, fps, tFull, acFull);
    weightedBpm(oe_low, fps, tLow, acLow);
    const bool octave = std::fabs(std::fabs(std::log2(tFull / tLow)) - 1.0) < OCTAVE_TOL;
    const double bpmCoarse = octave ? tLow : tFull;
    const std::vector<double>& ac = octave ? acLow : acFull;
    return parabolicLag(ac, lagAt(bpmCoarse, fps));
}

// ---------------------------------------------------------------------------
// Stage 3 — beat phase (comb + beat-snap regression refinement)
// ---------------------------------------------------------------------------
static void refinePeriod(const std::vector<double>& oe, double phi, double period,
                         double& phiOut, double& periodOut) {
    phiOut = phi;
    periodOut = period;
    const int N = static_cast<int>(oe.size());
    int win = static_cast<int>(period * SNAP_WIN);
    if (win < 1) win = 1;
    const int nBeats = static_cast<int>((N - 1 - phi) / period) + 1;

    std::vector<double> pos;
    pos.reserve(oe.size());
    for (double v : oe) if (v > 0.0) pos.push_back(v);
    const double med = median(pos);

    std::vector<double> ks, xs;
    for (int k = 0; k < nBeats; ++k) {
        const int c = static_cast<int>(std::lround(phi + k * period));
        const int lo = std::max(0, c - win);
        const int hi = std::min(N, c + win + 1);
        if (hi <= lo) continue;
        int jmax = lo;
        double vmax = oe[lo];
        for (int j = lo + 1; j < hi; ++j) if (oe[j] > vmax) { vmax = oe[j]; jmax = j; }
        if (vmax > SNAP_FLOOR * med) {
            ks.push_back(static_cast<double>(k));
            xs.push_back(static_cast<double>(jmax));
        }
    }
    if (ks.size() < 8) return;

    const int m = static_cast<int>(ks.size());
    double sk = 0.0, sx = 0.0;
    for (int i = 0; i < m; ++i) { sk += ks[i]; sx += xs[i]; }
    const double mk = sk / m, mx = sx / m;
    double num = 0.0, den = 0.0;
    for (int i = 0; i < m; ++i) {
        const double dk = ks[i] - mk;
        num += dk * (xs[i] - mx);
        den += dk * dk;
    }
    if (den == 0.0) return;
    const double slope = num / den;
    if (slope <= 0.0 || std::fabs(slope - period) > REFINE_TOL * period) return;
    phiOut = mx - slope * mk;
    periodOut = slope;
}

struct PhaseOut {
    double period;
    std::vector<double> beats;
};

static PhaseOut phaseTrack(const std::vector<double>& oe, double period) {
    double P = period;
    const int N = static_cast<int>(oe.size());
    const int nPhase = static_cast<int>(std::ceil(P));

    std::vector<double> energy(nPhase, 0.0);
    for (int phi = 0; phi < nPhase; ++phi) {
        const int nBeats = static_cast<int>((N - 1 - phi) / P) + 1;
        double s = 0.0;
        for (int k = 0; k < nBeats; ++k) s += interpArr(oe, phi + k * P);
        energy[phi] = s;
    }
    int best = 0;
    double bestv = -1e300;
    for (int i = 0; i < nPhase; ++i) if (energy[i] > bestv) { bestv = energy[i]; best = i; }

    const double a = energy[(best - 1 + nPhase) % nPhase];
    const double b = energy[best];
    const double c = energy[(best + 1) % nPhase];
    const double denom = a - 2.0 * b + c;
    double delta = (denom != 0.0) ? 0.5 * (a - c) / denom : 0.0;
    if (std::fabs(delta) > 1.0) delta = 0.0;
    double phi = best + delta;

    double rphi, rP;
    refinePeriod(oe, phi, P, rphi, rP);
    phi = rphi;
    P = rP;

    PhaseOut out;
    out.period = P;
    for (double x = phi; x < N; x += P) out.beats.push_back(x);
    return out;
}

// ---------------------------------------------------------------------------
// Stage 4 — downbeat / groove onset / beats-per-bar
// ---------------------------------------------------------------------------
static std::vector<double> beatAccents(const std::vector<double>& env,
                                       const std::vector<double>& beats) {
    std::vector<double> out(beats.size(), 0.0);
    const int n = static_cast<int>(env.size());
    for (size_t j = 0; j < beats.size(); ++j) {
        const int c = static_cast<int>(std::lround(beats[j]));
        const int lo = std::max(0, c - 2);
        const int hi = std::min(n, c + 3);
        if (hi <= lo) continue;
        double mx = env[lo];
        for (int k = lo + 1; k < hi; ++k) if (env[k] > mx) mx = env[k];
        out[j] = mx;
    }
    return out;
}

static int detectGroup(const std::vector<double>& accents) {
    const int K = static_cast<int>(accents.size());
    double overall = 0.0;
    for (double v : accents) overall += v;
    overall /= (K ? K : 1);
    int bestG = 4;
    double bestContrast = 0.0;
    for (int g : {2, 3, 4}) {
        for (int b = 0; b < g; ++b) {
            double sum = 0.0;
            int cnt = 0;
            for (int k = b; k < K; k += g) { sum += accents[k]; ++cnt; }
            if (cnt == 0) continue;
            const double contrast = overall > 0.0 ? (sum / cnt) / overall : 0.0;
            if (contrast > bestContrast) { bestContrast = contrast; bestG = g; }
        }
    }
    return bestG;
}

static double grooveOnset(const std::vector<double>& oe, const std::vector<double>& beats) {
    if (beats.empty()) return 0.0;
    const std::vector<double> s = beatAccents(oe, beats);
    std::vector<double> pos;
    for (double v : s) if (v > 0.0) pos.push_back(v);
    if (pos.empty()) return beats[0];
    const double floor = GROOVE_FLOOR * median(pos);
    for (size_t i = 0; i < s.size(); ++i) if (s[i] > floor) return beats[i];
    return beats[0];
}

// ---------------------------------------------------------------------------
// Orchestration
// ---------------------------------------------------------------------------
DspResult analyzeBpmDsp(const std::vector<float>& mono, int sampleRate) {
    const Envelopes e = extractOnset(mono, sampleRate);
    if (e.oe_full.size() < 200) return {0.0, 0, 4};

    const double period = estimateTempoPeriod(e.oe_full, e.oe_low, e.fps);
    if (!(period > 0.0)) return {0.0, 0, 4};

    const PhaseOut p = phaseTrack(e.oe_full, period);
    if (p.beats.empty() || !(p.period > 0.0)) return {0.0, 0, 4};

    const double bpm = 60.0 * e.fps / p.period;
    const int beatsPerBar = detectGroup(beatAccents(e.ae, p.beats));

    const double tGroove = grooveOnset(e.oe_full, p.beats);
    int grooveIdx = 0;
    double bestd = 1e300;
    for (size_t i = 0; i < p.beats.size(); ++i) {
        const double d = std::fabs(p.beats[i] - tGroove);
        if (d < bestd) { bestd = d; grooveIdx = static_cast<int>(i); }
    }
    const int64_t firstSample = std::llround(p.beats[grooveIdx] * HOP);

    return {bpm, firstSample, beatsPerBar};
}
