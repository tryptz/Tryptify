// The AutoEQ ranking's seven measures, explained: plain words and a small
// drawn diagram for each (SVG, coloured from the page's design). Used by
// js/autoeq.js for the card above the ranking and the cards below it.
(() => {
  "use strict";
  const NS = "http://www.w3.org/2000/svg";
  const W = 320, H = 150, L = 22, R = 300, TOP = 18, BOT = 128;

  const el = (tag, attrs = {}, parent) => {
    const e = document.createElementNS(NS, tag);
    for (const [k, v] of Object.entries(attrs)) e.setAttribute(k, v);
    if (parent) parent.append(e);
    return e;
  };
  const X = (x) => L + x * (R - L);
  const Y = (y) => BOT - y * (BOT - TOP);
  const curve = (fn, from = 0, to = 1, n = 90) => {
    let d = "";
    for (let i = 0; i <= n; i++) {
      const x = from + ((to - from) * i) / n;
      d += `${i ? "L" : "M"}${X(x).toFixed(1)},${Y(fn(x)).toFixed(1)}`;
    }
    return d;
  };
  const band = (top, bottom, from = 0, to = 1, n = 90) => {
    let d = curve(top, from, to, n);
    for (let i = n; i >= 0; i--) {
      const x = from + ((to - from) * i) / n;
      d += `L${X(x).toFixed(1)},${Y(bottom(x)).toFixed(1)}`;
    }
    return d + "Z";
  };
  const text = (svg, x, y, s, cls = "lbl", anchor = "start") => {
    const t = el("text", { x, y, class: cls, "text-anchor": anchor }, svg);
    t.textContent = s;
    return t;
  };
  const axis = (svg, left = "20 Hz", right = "20 kHz") => {
    el("line", { x1: L, y1: BOT, x2: R, y2: BOT, class: "axis" }, svg);
    text(svg, L, BOT + 16, left, "tick");
    text(svg, R, BOT + 16, right, "tick", "end");
  };

  // A target with a gentle bass shelf and a treble roll-off, in 0..1 units.
  const target = (x) => 0.55 + 0.16 * Math.exp(-((x - 0.08) ** 2) / 0.01) - 0.18 * Math.max(0, x - 0.72) ** 1.4 * 3 + 0.06 * Math.sin(x * 7);

  const DRAW = {
    rmse(svg) {
      axis(svg);
      const eq = (x) => target(x) + 0.05 * Math.sin(x * 23) + 0.03 * Math.sin(x * 41);
      el("path", { d: band(eq, target, 0, 0.82), class: "gap" }, svg);
      el("path", { d: curve(target), class: "line target" }, svg);
      el("path", { d: curve(eq, 0, 0.82), class: "line main draw" }, svg);
      text(svg, X(0.84), Y(target(0.84)) - 8, T("target"), "lbl muted");
      text(svg, X(0.22), Y(0.98), T("the shaded gap, averaged"), "lbl");
      el("line", { x1: X(0.82), y1: TOP + 20, x2: X(0.82), y2: BOT, class: "edge" }, svg);
      text(svg, X(0.82), TOP + 14, "16 kHz", "tick", "middle");
    },
    harman(svg) {
      const cx = 160, cy = 122, r = 92;
      const at = (v) => Math.PI * (1 - v / 100);
      const arc = (a, b, rr) => `M${cx + rr * Math.cos(at(a))},${cy - rr * Math.sin(at(a))} A${rr},${rr} 0 0 1 ${cx + rr * Math.cos(at(b))},${cy - rr * Math.sin(at(b))}`;
      el("path", { d: arc(0, 100, r), class: "track" }, svg);
      el("path", { d: arc(0, 97, r), class: "line main draw thick" }, svg);
      for (let v = 0; v <= 100; v += 25) {
        el("line", { x1: cx + (r - 12) * Math.cos(at(v)), y1: cy - (r - 12) * Math.sin(at(v)), x2: cx + (r - 4) * Math.cos(at(v)), y2: cy - (r - 4) * Math.sin(at(v)), class: "axis" }, svg);
      }
      const needle = el("g", { class: "needle" }, svg);
      el("line", { x1: cx, y1: cy, x2: cx - r + 22, y2: cy, class: "line main" }, needle);
      el("circle", { cx, cy, r: 5, class: "dot" }, svg);
      needle.dataset.turn = String((97 / 100) * 180);
      needle.dataset.pivot = `${cx} ${cy}`;
      text(svg, cx - r, cy + 18, "0", "tick", "middle");
      text(svg, cx + r, cy + 18, "100", "tick", "middle");
      text(svg, cx - 78, cy - 66, T("no EQ ≈ 76"), "lbl muted", "end");
      text(svg, cx + 70, cy - 76, T("good EQ ≈ 97"), "lbl");
    },
    maxerr(svg) {
      axis(svg);
      const bump = (x) => 0.2 * Math.exp(-((x - 0.5) ** 2) / 0.0012);
      const eq = (x) => target(x) + 0.03 * Math.sin(x * 29) + bump(x);
      el("rect", { x: X(0.72), y: TOP, width: R - X(0.72), height: BOT - TOP, class: "zone" }, svg);
      text(svg, X(0.86), TOP + 12, T("not counted"), "tick", "middle");
      text(svg, X(0.72) - 4, TOP + 12, "10 kHz", "tick", "end");
      el("path", { d: curve(target), class: "line target" }, svg);
      el("path", { d: curve(eq, 0, 0.72), class: "line main draw" }, svg);
      const x = X(0.5), y1 = Y(target(0.5)), y2 = Y(eq(0.5));
      el("line", { x1: x + 12, y1, x2: x + 12, y2, class: "arrow pop" }, svg);
      text(svg, x + 18, (y1 + y2) / 2 + 4, T("the single biggest miss"), "lbl pop");
    },
    headroom(svg) {
      el("line", { x1: L, y1: Y(0.62), x2: R, y2: Y(0.62), class: "ceiling" }, svg);
      text(svg, R, Y(0.62) + 13, T("0 dB: louder clips"), "tick", "end");
      const boosted = (x) => 0.48 + 0.3 * Math.exp(-((x - 0.3) ** 2) / 0.006) + 0.04 * Math.sin(x * 12);
      const lowered = (x) => boosted(x) - 0.2;
      el("path", { d: curve(boosted), class: "line target" }, svg);
      el("path", { d: curve(lowered), class: "line main draw" }, svg);
      const x = X(0.3);
      el("line", { x1: x, y1: Y(boosted(0.3)), x2: x, y2: Y(lowered(0.3)), class: "arrow pop" }, svg);
      text(svg, x + 16, Y(0.95), T("an EQ boost over the top"), "lbl muted");
      const [down, lose] = T("preamp turns it all down\n= volume you lose").split("\n");
      text(svg, X(0.44), Y(0.13), down, "lbl pop");
      text(svg, X(0.44), Y(0.13) + 14, lose || "", "lbl pop");
    },
    drift(svg) {
      axis(svg);
      const at48 = (x) => 0.45 + 0.22 * Math.exp(-((x - 0.82) ** 2) / 0.004) + 0.05 * Math.sin(x * 6);
      const at44 = (x) => at48(x) + (x > 0.6 ? -0.18 * Math.exp(-((x - 0.86) ** 2) / 0.003) + 0.12 * Math.exp(-((x - 0.78) ** 2) / 0.002) : 0);
      el("path", { d: band(at48, at44, 0.6, 1), class: "gap" }, svg);
      el("path", { d: curve(at48), class: "line main" }, svg);
      el("path", { d: curve(at44), class: "line alt draw" }, svg);
      text(svg, X(0.05), Y(0.62), T("fitted at 48 kHz"), "lbl");
      text(svg, X(0.05), Y(0.5) + 14, T("played at 44.1 kHz"), "lbl muted");
      text(svg, X(0.62), TOP + 6, T("the treble shifts"), "lbl pop");
    },
    ring(svg) {
      el("line", { x1: L, y1: Y(0.5), x2: R, y2: Y(0.5), class: "axis" }, svg);
      text(svg, L, BOT + 16, T("a click"), "tick");
      text(svg, R, BOT + 16, T("time →"), "tick", "end");
      const short = (x) => 0.5 + 0.42 * Math.exp(-x / 0.05) * Math.sin(x * 140);
      const long = (x) => 0.5 + 0.42 * Math.exp(-x / 0.28) * Math.sin(x * 140);
      el("path", { d: curve(long, 0, 1, 400), class: "line target" }, svg);
      el("path", { d: curve(short, 0, 1, 400), class: "line main draw" }, svg);
      text(svg, X(0.18), TOP + 4, T("short ring: crisp"), "lbl");
      text(svg, X(0.55), TOP + 4, T("long ring: smeared"), "lbl muted");
    },
    stack(svg) {
      axis(svg);
      const bell = (c, h, w) => (x) => h * Math.exp(-((x - c) ** 2) / w);
      const bells = [bell(0.47, 0.22, 0.004), bell(0.5, 0.26, 0.003), bell(0.53, 0.2, 0.004)];
      const base = 0.12;
      bells.forEach((b) => el("path", { d: curve((x) => base + b(x)), class: "line target" }, svg));
      const sum = (x) => base + bells.reduce((a, b) => a + b(x), 0);
      el("path", { d: curve(sum), class: "line main draw" }, svg);
      text(svg, X(0.5) + 14, Y(sum(0.5)) + 6, T("three boosts, one note"), "lbl pop");
      const [within, addUp] = T("bands within\n1/12 octave add up").split("\n");
      text(svg, X(0.06), Y(0.42), within, "lbl muted");
      text(svg, X(0.06), Y(0.42) + 14, addUp || "", "lbl muted");
    },
  };

  const TEXT = {
    rmse: {
      tryptify: T("Tryptify doesn't stop at its first guess. It places bands one at a time on the biggest remaining error, then goes back over all of them several times: each band is re-fitted against what the others leave, moving its frequency by up to half an octave, changing its width, and solving its gain exactly. The bands end up working together instead of each fixing its own spot, and that's what closes the gap. It also weighs bass and mids above the top octave, where measurements are least reliable."),
      loses: T("With the limit at 10 kHz it can't reach this headphone's dip at 10–13 kHz, and squig.link comes out closer there."),
      title: T("Fit error (RMSE)"), better: T("Lower is better"),
      what: T("How far, on average, the headphone with EQ lands from the target curve, from 20 Hz to 16 kHz. Picture the gap between the two lines, averaged out."),
      why: T("The smaller the gap, the closer the headphone sounds to the tuning you chose."),
      good: T("Under 1 dB is a very close match. This headphone with no EQ is 2.92 dB off."),
    },
    harman: {
      tryptify: T("The preference model punishes broad tilts in the bass and in the overall balance far more than narrow wiggles. Tryptify weighs bass errors 1.5 times as much as the rest, and its Shelf ends method fixes the broad tilts first, with a low shelf at 105 Hz and a high shelf at 10 kHz, one smooth filter each. That's where the model looks, so that's where the points come from."),
      loses: T("With only 5 bands it trails squig.link and AutoEq here: its weighting spends a short band budget on the bass and the biggest misses first, not on the overall balance this model scores. At the 10 kHz limit squig.link leads too."),
      title: T("Preference score"), better: T("Higher is better"),
      what: T("A prediction of how much listeners would like the sound, from Harman's research into what people prefer (AutoEq's version of the in-ear model), scored against this target."),
      why: T("An average hides where the errors are. This weighs the ones people actually notice, like a tilted bass or treble."),
      good: T("About 76 with no EQ; the mid-90s and up is excellent."),
    },
    maxerr: {
      tryptify: T("Tryptify's band search always goes after the largest deviation first, averaged over three neighbouring points so one noisy point can't win. Its refinement then minimises squared error, which punishes one big miss far more than many small ones. So the biggest peak below 10 kHz is the first thing fixed and never the one left standing."),
      loses: T("At the 10 kHz limit squig.link gets the worst miss lower."),
      title: T("Worst error, 20 Hz–10 kHz"), better: T("Lower is better"),
      what: T("The single biggest miss below 10 kHz: the one frequency where the headphone with EQ is furthest from the target."),
      why: T("A good average can hide one sharp peak, and one peak is enough to sound harsh or boomy. Above 10 kHz isn't counted: measurements there change from ear to ear."),
      good: T("Under 2 dB keeps every note in place. With no EQ it's 8 dB here."),
    },
    headroom: {
      tryptify: T("This is the one Tryptify trades away. It fixes dips as well as peaks, with boosts of up to 12 dB in the bass, 6 dB up to 6 kHz and 3 dB above, because filling a dip is part of matching the target. A fit that leaves dips alone, or caps its boosts lower, needs less preamp: AutoEq's fits need about 3–5 dB less here. Nothing clips either way, because the app sets its preamp from the real summed response; you just turn the volume up a little."),
      loses: T("AutoEq leads here at every limit."),
      title: T("Headroom lost"), better: T("Lower is better"),
      what: T("EQ boosts would push loud music past 0 dB, where it clips and distorts. So a preamp turns everything down by about the biggest boost, and that's volume you lose."),
      why: T("Less headroom lost means more volume left, which matters with a weak phone output or a small dongle."),
      good: T("A few dB is normal. Fits that chase every detail usually cost more."),
    },
    drift: {
      tryptify: T("Narrow treble filters are the ones that change shape most when the same settings play at 44.1 or 96 kHz. Above 5 kHz Tryptify never uses a band narrower than Q 3, and never narrower than Q 2 for a boost, so its treble stays put. In the app its filters are also built with matched-Z coefficients (Vicanek), which keep their intended shape near the top of the band at any sample rate."),
      loses: "",
      title: T("Sample-rate drift"), better: T("Lower is better"),
      what: T("EQ filters are worked out for one sample rate, here 48 kHz. Play a CD-quality (44.1 kHz) or hi-res (96 kHz) file and the same filters land slightly differently, mostly in the treble. Drift is the worst change."),
      why: T("Low drift means the same sound whatever the file's sample rate."),
      good: T("A few tenths of a dB or less is very small."),
    },
    ring: {
      tryptify: T("A band rings longer the narrower it is (the higher its Q) and the lower its frequency. Tryptify caps Q at 5 everywhere, at 3 above 5 kHz and at 2 for every boost, and its Shelf ends method handles the broad ends with shelves instead of stacks of narrow bells. Fewer narrow bands means bands that stop vibrating sooner."),
      loses: "",
      title: T("Ringing above 300 Hz"), better: T("Lower is better"),
      what: T("Every EQ band keeps vibrating for a moment after a sound stops, like a struck bell. This is how long bands from 300 Hz up take to fade away (by 60 dB). Narrow, sharp bands ring longest."),
      why: T("Long ringing smears transients: drum hits and plucked strings lose their snap."),
      good: T("Measured in milliseconds; shorter keeps attacks crisp."),
    },
    stack: {
      tryptify: T("When Tryptify re-fits a band, it first takes that band's own effect out, then fits it to what all the others leave. That makes two bands less likely to chase the same peak. A band that ends up under 0.2 dB is dropped instead of piling on, and boosts are capped by frequency."),
      loses: "",
      title: T("Largest stacked boost"), better: T("Lower is better"),
      what: T("When several bands boost nearly the same frequency (within 1/12 octave), their boosts add up. This is the biggest pile-up."),
      why: T("Stacked boosts are a sign the fit is fighting itself. They cost headroom and can make one note stick out."),
      good: T("A few dB is fine; big stacks are a warning sign."),
    },
  };

  /** Draws measure [key] into [box]; animates it in when [M] (Motion) is given. */
  function draw(key, box, M) {
    box.textContent = "";
    const svg = el("svg", { viewBox: `0 0 ${W} ${H}`, class: "mviz", role: "img", "aria-label": T("{title} diagram", { title: TEXT[key].title }) });
    DRAW[key](svg);
    box.append(svg);
    if (!M) {
      svg.querySelectorAll(".needle").forEach((n) => n.setAttribute("transform", `rotate(${n.dataset.turn} ${n.dataset.pivot})`));
      return;
    }
    svg.querySelectorAll(".draw").forEach((p) => M.animate(p, { pathLength: [0, 1] }, { duration: 1.1, ease: [0.22, 1, 0.36, 1] }));
    M.animate(svg.querySelectorAll(".gap, .zone"), { opacity: [0, 1] }, { duration: 0.8, delay: 0.4 });
    M.animate(svg.querySelectorAll(".pop"), { opacity: [0, 1] }, { duration: 0.5, delay: M.stagger(0.12, { startDelay: 0.7 }) });
    // Rotated about its pivot by hand: a CSS rotate on SVG turns it about its own middle.
    svg.querySelectorAll(".needle").forEach((n) =>
      M.animate(0, Number(n.dataset.turn), {
        type: "spring", stiffness: 60, damping: 9, delay: 0.2,
        onUpdate: (v) => n.setAttribute("transform", `rotate(${v} ${n.dataset.pivot})`),
      }));
  }

  window.TryptifyMeasures = { draw, TEXT };
})();
