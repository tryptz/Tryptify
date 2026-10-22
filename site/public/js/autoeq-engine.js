// Tryptify's AutoEQ engine for the browser: a line-for-line port of the app's
// AutoEqEngine.kt, EqDspKernels.kt (the matched-Z coefficients it uses),
// EqDataParser.parseRawData and AutoPreamp.peakBoostDb. Kotlin keeps curve
// points, band settings and some sums in 32-bit floats; Math.fround marks
// each of those places so results match the app's (site/tests checks this
// against fixtures the Kotlin engine wrote). Change both together.
(() => {
  "use strict";
  const f32 = Math.fround;
  const MAX_BOOST = 12.0, MAX_CUT = 12.0, MIN_Q = 0.6, MAX_Q = 5.0, SR = 48000;

  // ── EqDspKernels.kt ─────────────────────────────────────────────────
  const finite = Number.isFinite;
  const clamp = (v, lo, hi) => Math.min(Math.max(v, lo), hi);

  function matchedPeaking(sr, freq, q, gainDb) {
    if (sr <= 0 || !finite(freq) || !finite(q) || !finite(gainDb)) return null;
    const f = clamp(freq, 1.0, sr * 0.499);
    const qq = Math.max(q, 0.05);
    const g = Math.pow(10, gainDb / 20);
    const w0 = 2 * Math.PI * f / sr;
    const qp = 1 / (2 * Math.sqrt(g) * qq);
    const a2 = Math.exp(-2 * qp * w0);
    const a1 = qp <= 1
      ? -2 * Math.exp(-qp * w0) * Math.cos(Math.sqrt(1 - qp * qp) * w0)
      : -2 * Math.exp(-qp * w0) * Math.cosh(Math.sqrt(qp * qp - 1) * w0);
    const s = Math.sin(w0 / 2), p1 = s * s, p0 = 1 - p1, p2 = 4 * p0 * p1;
    const s0 = 1 + a1 + a2, s1 = 1 - a1 + a2, A0 = s0 * s0, A1 = s1 * s1, A2 = -4 * a2;
    const B0 = A0;
    const r1 = (A0 * p0 + A1 * p1 + A2 * p2) * g * g;
    const r2 = (-A0 + A1 + 4 * (p0 - p1) * A2) * g * g;
    const B2 = (r1 - r2 * p1 - B0) / (4 * p1 * p1);
    const B1 = r2 + B0 + 4 * (p1 - p0) * B2;
    if (B0 < 0 || B1 < 0) return null;
    const w = 0.5 * (Math.sqrt(B0) + Math.sqrt(B1));
    const disc = w * w + B2;
    if (disc < 0) return null;
    const b0 = 0.5 * (w + Math.sqrt(disc));
    if (b0 === 0) return null;
    const out = [b0, 0.5 * (Math.sqrt(B0) - Math.sqrt(B1)), -B2 / (4 * b0), a1, a2];
    return out.every(finite) ? out : null;
  }

  function matchedShelf(sr, freq, q, gainDb, high) {
    if (sr <= 0 || !finite(freq) || !finite(q) || !finite(gainDb)) return null;
    const f0 = clamp(freq, 1.0, sr * 0.499);
    const qq = Math.max(q, 0.05);
    const a = Math.pow(10, gainDb / 40);
    const w0 = 2 * Math.PI * f0 / sr;
    const analogDb = (f) => {
      const x = f / f0, im = Math.sqrt(a) / qq * x;
      const num = high ? Math.hypot(1 - a * x * x, im) : Math.hypot(a - x * x, im);
      const den = high ? Math.hypot(a - x * x, im) : Math.hypot(1 - a * x * x, im);
      return 20 * Math.log10(a * num / den);
    };
    const rbj = () => {
      const cw = Math.cos(w0), sw = Math.sin(w0), al = sw / (2 * qq), sq = 2 * Math.sqrt(a) * al;
      let b, d;
      if (high) {
        b = [a * ((a + 1) + (a - 1) * cw + sq), -2 * a * ((a - 1) + (a + 1) * cw), a * ((a + 1) + (a - 1) * cw - sq)];
        d = [(a + 1) - (a - 1) * cw + sq, 2 * ((a - 1) - (a + 1) * cw), (a + 1) - (a - 1) * cw - sq];
      } else {
        b = [a * ((a + 1) - (a - 1) * cw + sq), 2 * a * ((a - 1) - (a + 1) * cw), a * ((a + 1) - (a - 1) * cw - sq)];
        d = [(a + 1) + (a - 1) * cw + sq, -2 * ((a - 1) + (a + 1) * cw), (a + 1) + (a - 1) * cw - sq];
      }
      return [b[0] / d[0], b[1] / d[0], b[2] / d[0], d[1] / d[0], d[2] / d[0]];
    };
    const numeratorFor = (a1, a2) => {
      const s0 = 1 + a1 + a2, s1 = 1 - a1 + a2, A0 = s0 * s0, A1 = s1 * s1, A2 = -4 * a2;
      const gdc = Math.pow(10, analogDb(1e-3) / 20), gny = Math.pow(10, analogDb(sr / 2) / 20);
      const B0 = A0 * gdc * gdc, B1 = A1 * gny * gny;
      const s = Math.sin(w0 / 2), p1 = s * s, p0 = 1 - p1, p2 = 4 * p0 * p1;
      if (p2 === 0 || B0 < 0 || B1 < 0) return null;
      const B2 = ((A0 * p0 + A1 * p1 + A2 * p2) * a * a - B0 * p0 - B1 * p1) / p2;
      const w = 0.5 * (Math.sqrt(B0) + Math.sqrt(B1));
      const disc = w * w + B2;
      if (disc < 0) return null;
      const b0 = 0.5 * (w + Math.sqrt(disc));
      if (b0 === 0) return null;
      return [b0, 0.5 * (Math.sqrt(B0) - Math.sqrt(B1)), -B2 / (4 * b0), a1, a2];
    };
    const ii = () => {
      const wd = high ? w0 * Math.sqrt(a) : w0 / Math.sqrt(a);
      if (wd >= Math.PI) return null;
      const z = 1 / (2 * qq);
      const a2 = Math.exp(-2 * z * wd);
      const a1 = z <= 1 ? -2 * Math.exp(-z * wd) * Math.cos(Math.sqrt(1 - z * z) * wd)
        : -2 * Math.exp(-z * wd) * Math.cosh(Math.sqrt(z * z - 1) * wd);
      return numeratorFor(a1, a2);
    };
    const digitalDb = (c, f) => {
      const w = 2 * Math.PI * f / sr;
      const cw = Math.cos(w), c2w = Math.cos(2 * w), sw = Math.sin(w), s2w = Math.sin(2 * w);
      const nr = c[0] + c[1] * cw + c[2] * c2w, ni = c[1] * sw + c[2] * s2w;
      const dr = 1 + c[3] * cw + c[4] * c2w, di = c[3] * sw + c[4] * s2w;
      return 10 * Math.log10((nr * nr + ni * ni) / (dr * dr + di * di));
    };
    const stable = (c) => c.every(finite) && Math.abs(c[4]) < 1 && Math.abs(c[3]) < 1 + c[4];
    const r = rbj();
    const candidates = [r, ii(), numeratorFor(r[3], r[4])].filter(Boolean);
    let best = null, bestErr = Number.MAX_VALUE;
    const fLo = 40, fHi = sr * 0.475;
    for (const c of candidates) {
      if (!stable(c)) continue;
      let worst = 0;
      for (let i = 0; i <= 11; i++) {
        const p = fLo * Math.pow(fHi / fLo, i / 11);
        const e = Math.abs(digitalDb(c, p) - analogDb(p));
        if (e > worst) worst = e;
      }
      if (worst < bestErr) { bestErr = worst; best = c; }
    }
    return best;
  }

  // ── AutoEqEngine.kt ─────────────────────────────────────────────────
  // A band: { type: "PEAKING" | "LOWSHELF" | "HIGHSHELF", freq, gain, q } (floats, as in EqBand).
  function coeffsFor(band, sampleRate) {
    const m = band.type === "PEAKING" ? matchedPeaking(sampleRate, band.freq, band.q, band.gain)
      : matchedShelf(sampleRate, band.freq, band.q, band.gain, band.type === "HIGHSHELF");
    if (m) return m;
    const w0 = 2 * Math.PI * band.freq / sampleRate;
    const alpha = Math.sin(w0) / (2 * band.q);
    const A = Math.pow(10, band.gain / 40);
    const cw = Math.cos(w0);
    let b0, b1, b2, a0, a1, a2;
    if (band.type === "LOWSHELF") {
      const sq = 2 * Math.sqrt(A) * alpha;
      b0 = A * ((A + 1) - (A - 1) * cw + sq); b1 = 2 * A * ((A - 1) - (A + 1) * cw); b2 = A * ((A + 1) - (A - 1) * cw - sq);
      a0 = (A + 1) + (A - 1) * cw + sq; a1 = -2 * ((A - 1) + (A + 1) * cw); a2 = (A + 1) + (A - 1) * cw - sq;
    } else if (band.type === "HIGHSHELF") {
      const sq = 2 * Math.sqrt(A) * alpha;
      b0 = A * ((A + 1) + (A - 1) * cw + sq); b1 = -2 * A * ((A - 1) + (A + 1) * cw); b2 = A * ((A + 1) + (A - 1) * cw - sq);
      a0 = (A + 1) - (A - 1) * cw + sq; a1 = 2 * ((A - 1) - (A + 1) * cw); a2 = (A + 1) - (A - 1) * cw - sq;
    } else {
      b0 = 1 + alpha * A; b1 = -2 * cw; b2 = 1 - alpha * A; a0 = 1 + alpha / A; a1 = -2 * cw; a2 = 1 - alpha / A;
    }
    const inv = 1 / a0;
    return [b0 * inv, b1 * inv, b2 * inv, a1 * inv, a2 * inv];
  }

  function magnitudeDb(c, cp, c2p) {
    const num = c[0] * c[0] + c[1] * c[1] + c[2] * c[2] + 2 * (c[0] * c[1] + c[1] * c[2]) * cp + 2 * c[0] * c[2] * c2p;
    const den = 1 + c[3] * c[3] + c[4] * c[4] + 2 * (c[3] + c[3] * c[4]) * cp + 2 * c[4] * c2p;
    return 10 * Math.log10(num / den);
  }

  /** One band's response in dB at [freqHz], as a float (calculateBiquadResponse). */
  function response(freqHz, band, sampleRate = SR) {
    const c = coeffsFor(band, sampleRate);
    const phi = 2 * Math.PI * freqHz / sampleRate;
    return f32(magnitudeDb(c, Math.cos(phi), Math.cos(2 * phi)));
  }

  function interpolate(freq, data) {
    if (!data.length) return 0;
    if (freq <= data[0].freq) return data[0].gain;
    if (freq >= data[data.length - 1].freq) return data[data.length - 1].gain;
    for (let i = 0; i < data.length - 1; i++) {
      if (freq >= data[i].freq && freq <= data[i + 1].freq) {
        const t = f32(f32(freq - data[i].freq) / f32(data[i + 1].freq - data[i].freq));
        return f32(data[i].gain + f32(t * f32(data[i + 1].gain - data[i].gain)));
      }
    }
    return 0;
  }

  function normalizationOffset(data) {
    let sum = 0, count = 0;
    for (const p of data) if (p.freq >= 250 && p.freq <= 2500) { sum = f32(sum + p.gain); count++; }
    return count > 0 ? f32(sum / count) : interpolate(1000, data);
  }

  function smoothCurve(points, percent) {
    if (percent <= 0 || points.length < 3) return points;
    const radius = Math.max(1, Math.trunc(f32(percent / 2.5)));
    return points.map((p, i) => {
      let sum = 0, wSum = 0;
      for (let c = -radius; c <= radius; c++) {
        const j = i + c;
        if (j >= 0 && j < points.length) {
          const w = f32(1 - f32(Math.abs(c) / (radius + 1)));
          sum = f32(sum + f32(points[j].gain * w));
          wSum = f32(wSum + w);
        }
      }
      return { freq: p.freq, gain: f32(sum / wSum) };
    });
  }

  const priorityWeight = (f) => (f < 300 ? 1.5 : f < 4000 ? 1.0 : f < 8000 ? 0.5 : 0.25);

  function clampGain(g, type, freq) {
    const cap = type === "LOWSHELF" ? MAX_BOOST : type === "HIGHSHELF" ? 4.0 : freq > 6000 ? 3.0 : freq > 3000 ? 6.0 : MAX_BOOST;
    return clamp(g, -MAX_CUT, cap);
  }

  function fitEndShelf(error, type, corner, lo, hi, maxBoost, sampleRate) {
    let sum = 0, count = 0;
    for (const p of error) if (p.freq >= lo && p.freq <= hi) { sum += p.gain; count++; }
    if (!count) return null;
    let gain = -(sum / count);
    if (gain > maxBoost) gain = maxBoost;
    if (gain < -MAX_CUT) gain = -MAX_CUT;
    if (Math.abs(gain) < 1.0) return null;
    const band = { type, freq: f32(corner), gain: f32(gain), q: f32(0.707) };
    for (let j = 0; j < error.length; j++) error[j] = { freq: error[j].freq, gain: f32(error[j].gain + response(error[j].freq, band, sampleRate)) };
    return band;
  }

  function refineBands(bands, residual, minF, maxF, sampleRate, sweeps = 4) {
    if (!bands.length) return bands;
    const n = residual.length;
    const cp = new Float64Array(n), c2p = new Float64Array(n), weights = new Float64Array(n);
    for (let j = 0; j < n; j++) {
      const phi = 2 * Math.PI * residual[j].freq / sampleRate;
      cp[j] = Math.cos(phi); c2p[j] = Math.cos(2 * phi);
      const f = residual[j].freq;
      weights[j] = f < minF || f > maxF ? 0 : priorityWeight(f);
    }
    const res = Float64Array.from(residual, (p) => p.gain);
    let prevTotal = Number.MAX_VALUE;
    for (let sweep = 0; sweep < sweeps; sweep++) {
      for (let k = 0; k < bands.length; k++) {
        const band = bands[k];
        const ck = coeffsFor(band, sampleRate);
        const eWo = new Float64Array(n);
        for (let j = 0; j < n; j++) eWo[j] = res[j] - magnitudeDb(ck, cp[j], c2p[j]);
        let freqCands, qCands;
        if (band.type === "PEAKING") {
          freqCands = [...new Set([0.71, 0.84, 1, 1.19, 1.41].map((m) => clamp(f32(band.freq * f32(m)), f32(minF), f32(maxF))))];
          qCands = [...new Set([0.5, 0.7, 1, 1.4, 2].map((m) => clamp(f32(band.q * f32(m)), f32(MIN_Q), f32(MAX_Q))))];
        } else { freqCands = [band.freq]; qCands = [band.q]; }
        let best = band, bestScore = Number.MAX_VALUE;
        for (const fc of freqCands) for (const qc0 of qCands) {
          let qc = qc0;
          if (band.type === "PEAKING" && fc > 5000 && qc > 3) qc = f32(3);
          const cProbe = coeffsFor({ ...band, freq: fc, q: qc, gain: f32(1) }, sampleRate);
          let num = 0, den = 1e-9;
          for (let j = 0; j < n; j++) {
            const sj = magnitudeDb(cProbe, cp[j], c2p[j]);
            num += weights[j] * eWo[j] * sj;
            den += weights[j] * sj * sj;
          }
          const g = clampGain(-num / den, band.type, fc);
          if (band.type === "PEAKING" && g > 0 && qc > 2) qc = f32(2);
          const cand = { ...band, freq: fc, q: qc, gain: f32(g) };
          const cc = coeffsFor(cand, sampleRate);
          let score = 0;
          for (let j = 0; j < n; j++) { const r = eWo[j] + magnitudeDb(cc, cp[j], c2p[j]); score += weights[j] * r * r; }
          if (score < bestScore) { bestScore = score; best = cand; }
        }
        bands[k] = best;
        const cb = coeffsFor(best, sampleRate);
        for (let j = 0; j < n; j++) res[j] = eWo[j] + magnitudeDb(cb, cp[j], c2p[j]);
      }
      let total = 0;
      for (let j = 0; j < n; j++) total += weights[j] * res[j] * res[j];
      if (prevTotal - total < prevTotal * 0.005) break;
      prevTotal = total;
    }
    for (let j = 0; j < n; j++) residual[j] = { freq: residual[j].freq, gain: f32(res[j]) };
    return bands.filter((b) => Math.abs(b.gain) >= f32(0.2));
  }

  /**
   * The app's runAutoEqAlgorithm: measurement and target as [{freq, gain}],
   * returns bands sorted by frequency.
   */
  function run(measurement, target, { bandCount = 10, maxFrequency = 16000, minFrequency = 20, sampleRate = SR, algorithm = "SHELF_ENDS" } = {}) {
    const meas = measurement.map((p) => ({ freq: f32(p.freq), gain: f32(p.gain) }));
    const tgt = target.map((p) => ({ freq: f32(p.freq), gain: f32(p.gain) }));
    maxFrequency = f32(maxFrequency); minFrequency = f32(minFrequency);
    const offset = f32(normalizationOffset(tgt) - normalizationOffset(meas));
    const error = meas.map((p) => ({ freq: p.freq, gain: f32(f32(p.gain + offset) - interpolate(p.freq, tgt)) }));
    let bands = [];
    if (algorithm === "SHELF_ENDS") {
      const lo = fitEndShelf(error, "LOWSHELF", 105, minFrequency, 105, MAX_BOOST, sampleRate);
      if (lo) bands.push(lo);
      const hiCorner = Math.min(f32(10000), f32(maxFrequency * f32(0.75)));
      const hi = fitEndShelf(error, "HIGHSHELF", hiCorner, hiCorner, maxFrequency, 4.0, sampleRate);
      if (hi) bands.push(hi);
    }
    while (bands.length < bandCount) {
      let maxDev = 0, maxWeighted = 0, peakFreq = 1000, peakIdx = 0;
      for (let j = 0; j < error.length; j++) {
        const freq = error[j].freq;
        if (freq < minFrequency || freq > maxFrequency) continue;
        let v = error[j].gain;
        if (j > 0 && j < error.length - 1) v = (error[j - 1].gain + v + error[j + 1].gain) / 3.0;
        const wa = Math.abs(v * priorityWeight(freq));
        if (wa > Math.abs(maxWeighted)) { maxWeighted = wa; maxDev = v; peakFreq = freq; peakIdx = j; }
      }
      let gain = -maxDev;
      let safeBoost = MAX_BOOST;
      if (peakFreq > 3000) safeBoost = 6.0;
      if (peakFreq > 6000) safeBoost = 3.0;
      if (gain > safeBoost) gain = safeBoost;
      if (gain < -MAX_CUT) gain = -MAX_CUT;
      if (Math.abs(gain) < 0.2) break;
      const targetEnergy = maxDev / 2;
      let lower = peakFreq, upper = peakFreq;
      for (let k = peakIdx; k >= 0; k--) if (Math.abs(error[k].gain) < Math.abs(targetEnergy)) { lower = error[k].freq; break; }
      for (let k = peakIdx; k < error.length; k++) if (Math.abs(error[k].gain) < Math.abs(targetEnergy)) { upper = error[k].freq; break; }
      let bw = Math.log2(upper / Math.max(1, lower));
      if (bw < 0.1) bw = 0.1;
      let q = Math.sqrt(Math.pow(2, bw)) / (Math.pow(2, bw) - 1);
      if (q < MIN_Q) q = MIN_Q;
      if (q > MAX_Q) q = MAX_Q;
      if (peakFreq > 5000 && q > 3) q = 3;
      if (gain > 0 && q > 2) q = 2;
      const band = { type: "PEAKING", freq: f32(peakFreq), gain: f32(gain), q: f32(q) };
      bands.push(band);
      for (let j = 0; j < error.length; j++) error[j] = { freq: error[j].freq, gain: f32(error[j].gain + response(error[j].freq, band, sampleRate)) };
    }
    bands = refineBands(bands, error, minFrequency, maxFrequency, sampleRate);
    return bands.slice().sort((a, b) => a.freq - b.freq);
  }

  /** AutoPreamp.peakBoostDb: the summed boost's peak, never negative. */
  function peakBoostDb(bands, sampleRate = SR) {
    if (!bands.length) return 0;
    const lo = Math.log10(20), hi = Math.log10(20000), N = 96;
    const freqs = [];
    for (let i = 0; i < N; i++) freqs.push(f32(Math.pow(10, lo + i * (hi - lo) / (N - 1))));
    for (const b of bands) freqs.push(b.freq);
    let peak = 0;
    for (const f of freqs) {
      let sum = 0;
      for (const b of bands) sum = f32(sum + response(f, b, sampleRate));
      if (sum > peak) peak = sum;
    }
    return peak;
  }

  /** The summed EQ response in dB at each frequency in [freqs] (doubles; for drawing). */
  function eqCurve(bands, freqs, sampleRate = SR) {
    const coeffs = bands.map((b) => coeffsFor(b, sampleRate));
    return freqs.map((f) => {
      const phi = 2 * Math.PI * f / sampleRate, cp = Math.cos(phi), c2p = Math.cos(2 * phi);
      let s = 0;
      for (const c of coeffs) s += magnitudeDb(c, cp, c2p);
      return s;
    });
  }

  // ── EqDataParser.parseRawData ───────────────────────────────────────
  function parse(text) {
    if (!text) return [];
    const lines = text.trim().split("\n");
    if (!lines.length) return [];
    const first = lines[0].trim();
    const delim = first.includes(";") ? ";" : first.includes(",") ? "," : first.includes("\t") ? "\t" : null;
    const split = (s) => (delim ? s.split(delim) : s.split(/\s+/));
    let fi = 0, gi = 1;
    const hasHeader = /[a-zA-Z]/.test(first);
    if (hasHeader) {
      const h = split(first).map((x) => x.trim().toLowerCase().replace(/['"]/g, ""));
      const f = h.findIndex((x) => x.includes("freq") || x === "f");
      if (f >= 0) fi = f;
      const r = h.findIndex((x) => x === "raw");
      if (r >= 0) gi = r;
      else {
        const s = h.findIndex((x) => x.includes("spl") || x.includes("gain") || x.includes("db") || x.includes("mag"));
        if (s >= 0 && s !== fi) gi = s;
      }
    }
    const out = [];
    for (const line of hasHeader ? lines.slice(1) : lines) {
      const l = line.trim();
      if (!l || !(/[0-9]/.test(l[0]) || l[0] === "-" || l[0] === ".")) continue;
      const parts = split(l);
      if (parts.length <= Math.max(fi, gi)) continue;
      let fs = parts[fi].trim(), gs = parts[gi].trim();
      if (delim !== ",") { fs = fs.replace(/,/g, "."); gs = gs.replace(/,/g, "."); }
      const f = Number(fs), g = Number(gs);
      if (fs !== "" && gs !== "" && finite(f) && finite(g)) out.push({ freq: f32(f), gain: f32(g) });
    }
    return out.sort((a, b) => a.freq - b.freq);
  }

  // coeffs: one band's [b0, b1, b2, a1, a2] at a sample rate, for the web player's filters (js/wp-engine.js).
  window.TryptifyAutoEq = { run, peakBoostDb, eqCurve, response, smoothCurve, parse, normalizationOffset, interpolate, coeffs: coeffsFor };
})();
