// The site's motion, on Motion (motion.dev, MIT, js/motion-12.23.12.js).
// Decoration only: the pages read the same without it. The head's inline
// script adds .motion to <html> when movement is welcome; everything that
// starts hidden in styles.css is shown again here, or by that script's
// timeout if this file never runs.
(() => {
  "use strict";
  const root = document.documentElement;
  const M = window.Motion;
  const moving = root.classList.contains("motion") && !!M;
  window.__tryptifyMotion = true;

  const $ = (sel, el = document) => el.querySelector(sel);
  const $$ = (sel, el = document) => Array.from(el.querySelectorAll(sel));
  const finePointer = matchMedia("(pointer: fine)").matches;

  // ── Monthly / yearly (works with or without movement) ────────────────
  const period = $(".period");
  if (period) {
    period.hidden = false;
    const pill = $(".period-pill", period);
    const buttons = $$("button", period);
    const placePill = (btn, instant) => {
      const box = { x: btn.offsetLeft - 4, width: btn.offsetWidth };
      if (M) M.animate(pill, box, moving && !instant ? { type: "spring", stiffness: 420, damping: 32 } : { duration: 0 });
      else { pill.style.transform = `translateX(${box.x}px)`; pill.style.width = box.width + "px"; }
    };
    const show = (which, instant) => {
      buttons.forEach((b) => b.setAttribute("aria-pressed", String(b.dataset.period === which)));
      placePill(buttons.find((b) => b.dataset.period === which), instant);
      $$(".plan").forEach((plan, i) => {
        const amount = $(".amount", plan);
        const to = Number(amount.dataset[which]);
        const from = Number(amount.textContent.replace(/[^0-9.]/g, ""));
        const write = (v) => { amount.textContent = "$" + v.toFixed(2); };
        if (moving && !instant) {
          M.animate(from, to, { duration: 0.7, delay: i * 0.06, ease: [0.22, 1, 0.36, 1], onUpdate: write });
          M.animate(amount, { scale: [1, 1.12, 1] }, { duration: 0.5, delay: i * 0.06 });
        } else write(to);
        $$("[data-monthly]", plan).forEach((el) => { if (el !== amount) el.textContent = el.dataset[which]; });
      });
    };
    buttons.forEach((b) => b.addEventListener("click", () => show(b.dataset.period, false)));
    // Fonts change the buttons' widths: place the pill once they are in.
    const settle = () => placePill(buttons.find((b) => b.getAttribute("aria-pressed") === "true"), true);
    settle();
    document.fonts && document.fonts.ready.then(settle);
    addEventListener("resize", settle);
  }

  // ── Liquid glass navigation ─────────────────────────────────────────
  // The lens: an SVG filter per piece of glass, used through backdrop-filter
  // where Chromium allows url() there (styles.css, .lens). Its displacement
  // map is drawn for the glass's own size and rounded outline: still in the
  // middle, bending harder toward the rim along the edge's normal, so a pill's
  // round ends bend round. Red, green and blue bend by slightly different
  // amounts, the little colour split real glass shows at its edge.
  const svgNS = "http://www.w3.org/2000/svg";
  const brands = (navigator.userAgentData && navigator.userAgentData.brands) || [];
  const lensOn = brands.some((b) => /Chromium/.test(b.brand));
  const defs = document.createElementNS(svgNS, "svg");
  defs.setAttribute("aria-hidden", "true");
  defs.setAttribute("width", "0");
  defs.setAttribute("height", "0");
  defs.style.position = "absolute";
  document.body.append(defs);
  if (lensOn) root.classList.add("lens");

  const lensMap = (w, h, r) => {
    const canvas = document.createElement("canvas");
    canvas.width = w;
    canvas.height = h;
    const ctx = canvas.getContext("2d");
    const img = ctx.createImageData(w, h);
    const px = img.data;
    const bezel = Math.max(6, Math.min(r, h / 2, 24));
    for (let y = 0; y < h; y++) {
      for (let x = 0; x < w; x++) {
        const cx = x + 0.5 - w / 2, cy = y + 0.5 - h / 2;
        const qx = Math.abs(cx) - (w / 2 - r), qy = Math.abs(cy) - (h / 2 - r);
        let nx, ny, dist;
        if (qx > 0 && qy > 0) {
          // In a rounded corner: the normal points out from the corner's centre.
          const l = Math.hypot(qx, qy) || 1;
          nx = qx / l; ny = qy / l; dist = r - l;
        } else if (w / 2 - Math.abs(cx) < h / 2 - Math.abs(cy)) {
          nx = 1; ny = 0; dist = w / 2 - Math.abs(cx);
        } else {
          nx = 0; ny = 1; dist = h / 2 - Math.abs(cy);
        }
        nx *= Math.sign(cx) || 1;
        ny *= Math.sign(cy) || 1;
        const t = Math.min(1, Math.max(0, dist / bezel));
        const bend = Math.pow(1 - t, 2.4);
        const i = (y * w + x) * 4;
        // Sample from further in: the rim magnifies what is just inside it.
        px[i] = 128 - nx * bend * 127;
        px[i + 1] = 128 - ny * bend * 127;
        px[i + 2] = 128;
        px[i + 3] = 255;
      }
    }
    ctx.putImageData(img, 0, 0);
    return canvas.toDataURL();
  };

  let lenses = 0;
  const giveLens = (el) => {
    if (!lensOn || el.dataset.lens) return;
    const id = `glass-lens-${lenses++}`;
    const filter = document.createElementNS(svgNS, "filter");
    filter.id = id;
    // A filter area larger than the glass: Chrome placed one the glass's own
    // size a little low on the fixed tab bar, and the strip above it showed
    // the page through sharp.
    for (const [k, v] of Object.entries({ x: "-10%", y: "-50%", width: "120%", height: "200%", "color-interpolation-filters": "sRGB" })) filter.setAttribute(k, v);
    defs.append(filter);
    const channel = (c, scale) => {
      const m = { R: "1 0 0 0 0 0 0 0 0 0 0 0 0 0 0", G: "0 0 0 0 0 0 1 0 0 0 0 0 0 0 0", B: "0 0 0 0 0 0 0 0 0 0 0 0 1 0 0" }[c];
      return `<feDisplacementMap in="soft" in2="map" scale="${scale}" xChannelSelector="R" yChannelSelector="G" result="d${c}"/>` +
        `<feColorMatrix in="d${c}" type="matrix" values="${m} 0 0 0 1 0" result="${c}"/>`;
    };
    let size = "";
    const draw = () => {
      const w = Math.round(el.offsetWidth), h = Math.round(el.offsetHeight);
      if (!w || !h || `${w}x${h}` === size) return;
      size = `${w}x${h}`;
      const r = Math.min(parseFloat(getComputedStyle(el).borderTopLeftRadius) || 0, h / 2, w / 2);
      const s = Math.round(Math.min(40, h * 0.62));
      // The blur is part of the lens, so the bend and the blur always go together.
      const blur = parseFloat(getComputedStyle(el).getPropertyValue("--glass-blur")) || 6;
      filter.innerHTML =
        `<feGaussianBlur in="SourceGraphic" stdDeviation="${blur / 2}" edgeMode="duplicate" result="soft"/>` +
        `<feImage href="${lensMap(w, h, r)}" x="0" y="0" width="100%" height="100%" preserveAspectRatio="none" result="map"/>` +
        channel("R", s) + channel("G", Math.round(s * 0.9)) + channel("B", Math.round(s * 0.8)) +
        `<feBlend in="R" in2="G" mode="screen" result="RG"/><feBlend in="RG" in2="B" mode="screen"/>`;
    };
    draw();
    el.style.setProperty("--lens", `url(#${id})`);
    el.dataset.lens = id;
    let queued = false;
    new ResizeObserver(() => {
      if (queued) return;
      queued = true;
      requestAnimationFrame(() => { queued = false; draw(); });
    }).observe(el);
  };

  // The glint follows the pointer over each piece of glass.
  const glint = (el) => el.addEventListener("pointermove", (e) => {
    const r = el.getBoundingClientRect();
    el.style.setProperty("--gx", `${((e.clientX - r.left) / r.width) * 100}%`);
    el.style.setProperty("--gy", `${((e.clientY - r.top) / r.height) * 100}%`);
  });

  // ── Black or white ──────────────────────────────────────────────────
  // The head script picked one: the button's last choice, else the device's.
  // Until the button is used, the page follows the device as it changes.
  const SUN = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" aria-hidden="true"><circle cx="12" cy="12" r="4.2"/><path d="M12 2.5v2.2M12 19.3v2.2M2.5 12h2.2M19.3 12h2.2M5.3 5.3l1.6 1.6M17.1 17.1l1.6 1.6M5.3 18.7l1.6-1.6M17.1 6.9l1.6-1.6"/></svg>';
  const MOON = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linejoin="round" aria-hidden="true"><path d="M20 14.6A8.2 8.2 0 0 1 9.4 4a8.2 8.2 0 1 0 10.6 10.6Z"/></svg>';
  const headerGlass = $(".top .glass");
  const toggle = document.createElement("button");
  toggle.type = "button";
  toggle.className = "theme-toggle";
  const showTheme = () => {
    const dark = root.dataset.design !== "white";
    toggle.innerHTML = dark ? SUN : MOON;
    toggle.title = dark ? T("Switch to white") : T("Switch to black");
    toggle.setAttribute("aria-label", toggle.title);
  };
  const setTheme = (name) => {
    root.classList.add("theme-fade");
    root.dataset.design = name;
    showTheme();
    setTimeout(() => root.classList.remove("theme-fade"), 400);
  };
  toggle.addEventListener("click", () => {
    const next = root.dataset.design === "white" ? "dark" : "white";
    try { localStorage.setItem("tryptify-theme", next); } catch (e) {}
    setTheme(next);
    if (M && moving) M.animate($("svg", toggle), { rotate: [-90, 0], scale: [0.4, 1] }, { type: "spring", stiffness: 420, damping: 18 });
  });
  const light = matchMedia("(prefers-color-scheme: light)");
  light.addEventListener("change", () => {
    let chosen = null;
    try { chosen = localStorage.getItem("tryptify-theme"); } catch (e) {}
    if (!chosen) setTheme(light.matches ? "white" : "dark");
  });
  showTheme();
  if (headerGlass) headerGlass.append(toggle);

  const nav = $(".nav");
  if (headerGlass) { glint(headerGlass); giveLens(headerGlass); }
  if (nav) {
    glint(nav);
    const bubble = document.createElement("span");
    bubble.className = "nav-bubble";
    bubble.setAttribute("aria-hidden", "true");
    nav.prepend(bubble);
    const links = $$("a", nav);
    const here = links.find((a) => a.getAttribute("aria-current") === "page");
    // The pill is two edges on springs of their own. Moving on, the edge in
    // front is quick and the one behind follows, so the pill stretches toward
    // the tab and settles onto it, a drop of liquid rather than a box sliding.
    // Its height and round ends never change, so the glass keeps its shape.
    const lead = { type: "spring", stiffness: 560, damping: 42, mass: 0.9 };
    const trail = { type: "spring", stiffness: 260, damping: 30, mass: 0.9 };
    const left = M ? M.motionValue(0) : null;
    const right = M ? M.motionValue(0) : null;
    let top = 0, tall = 0, shown = false, at = null;
    const draw = () => {
      const l = left.get(), r = right.get();
      bubble.style.translate = `${l}px ${top}px`;  // translate, so the press's scale (transform) stays its own
      bubble.style.width = Math.max(0, r - l) + "px";
      bubble.style.height = tall + "px";
    };
    if (M) { left.on("change", draw); right.on("change", draw); }
    const edgesOf = (a) => ({ l: a.offsetLeft, r: a.offsetLeft + a.offsetWidth });
    const moveTo = (a, instant) => {
      if (!a) { if (M) M.animate(bubble, { opacity: 0 }, { duration: 0.2 }); else bubble.style.opacity = "0"; shown = false; at = null; return; }
      const { l, r } = edgesOf(a);
      top = a.offsetTop; tall = a.offsetHeight;
      if (!M) {
        Object.assign(bubble.style, { translate: `${l}px ${top}px`, width: r - l + "px", height: tall + "px", opacity: "1" });
      } else if (!moving || instant || !shown) {
        left.jump(l); right.jump(r); draw();
        M.animate(bubble, { opacity: 1 }, { duration: moving && !instant ? 0.2 : 0 });
      } else if (a !== at) {
        const forward = l > left.get();
        M.animate(left, l, forward ? trail : lead);
        M.animate(right, r, forward ? lead : trail);
      }
      shown = true; at = a;
    };
    links.forEach((a) => {
      a.addEventListener("pointerenter", () => moveTo(a));
      a.addEventListener("focus", () => moveTo(a));
    });
    nav.addEventListener("pointerleave", () => moveTo(here));
    if (M && moving) M.press(nav, () => {
      M.animate(bubble, { scale: 0.94 }, { type: "spring", stiffness: 600, damping: 26 });
      return () => M.animate(bubble, { scale: 1 }, { type: "spring", stiffness: 500, damping: 18 });
    });
    // On phones the menu is its own floating tab bar at the bottom, so it
    // leaves the header: a fixed bar inside glass would be pinned to the glass.
    const phone = matchMedia("(max-width: 560px)");
    const place = () => {
      if (phone.matches && nav.parentElement !== document.body) { document.body.append(nav); nav.classList.add("glass"); giveLens(nav); }
      if (!phone.matches && headerGlass && nav.parentElement !== headerGlass) { headerGlass.insertBefore(nav, toggle); nav.classList.remove("glass"); }
      moveTo(here, true);
    };
    // A tap on a tab leaves where the pill stood, so the next page can slide
    // it on from there instead of showing it already sitting on its tab.
    const KEY = "tryptify-pill";
    const mode = () => (phone.matches ? "phone" : "wide") + ":" + root.lang;
    links.forEach((a) => a.addEventListener("click", (e) => {
      if (e.defaultPrevented || e.button !== 0 || e.metaKey || e.ctrlKey || e.shiftKey || e.altKey || !M) return;
      try { sessionStorage.setItem(KEY, JSON.stringify({ mode: mode(), l: left.get(), r: right.get(), t: Date.now() })); } catch (_) {}
    }));
    let came = null;
    try { came = JSON.parse(sessionStorage.getItem(KEY) || "null"); sessionStorage.removeItem(KEY); } catch (_) {}
    if (!came || came.mode !== mode() || Date.now() - came.t > 5000 || !here) came = null;
    const arrived = !!came;
    place();
    phone.addEventListener("change", place);
    addEventListener("resize", () => moveTo(here, true));
    const settle = () => {
      if (!came || !moving) { moveTo(here, true); return; }
      const from = came; came = null;
      left.jump(from.l); right.jump(from.r); draw(); shown = true; at = null;
      requestAnimationFrame(() => moveTo(here));
    };
    if (document.fonts) document.fonts.ready.then(settle); else settle();
    // The tab bar rises in on arrival, not again on every page it leads to.
    if (moving && phone.matches && !arrived) M.animate(nav, { y: [80, 0], opacity: [0, 1] }, { type: "spring", stiffness: 220, damping: 22, delay: 0.3 });
  }

  if (!moving) {
    root.classList.remove("motion");
    return;
  }
  const { animate, inView, scroll, hover, press, stagger } = M;
  const easeOut = [0.22, 1, 0.36, 1];
  const rise = (els, opts = {}) =>
    animate(els, { opacity: [0, 1], y: [opts.y ?? 28, 0] }, { duration: 0.7, ease: easeOut, delay: stagger(opts.gap ?? 0.08, { startDelay: opts.start ?? 0 }) });

  // ── Scroll progress along the top ───────────────────────────────────
  const bar = document.createElement("div");
  bar.className = "progress";
  bar.setAttribute("aria-hidden", "true");
  document.body.prepend(bar);
  scroll(animate(bar, { scaleX: [0, 1] }, { ease: "linear" }));

  const once = (el, fn, amount = 0.25) => inView(el, () => { fn(); }, { amount });

  // ── The hero: words rise into place ─────────────────────────────────
  const hero = $(".hero");
  if (hero) {
    const h1 = $("h1", hero);
    if (h1) {
      // Words as the page's language divides them: Chinese and Japanese have
      // no spaces, and one span for a whole title would run off the screen.
      // Spaces and punctuation stay on the word before them, opening marks
      // (¿ 「 “ «) on the word after, so no line ends on one.
      const text = h1.textContent.trim().replace(/\s+/g, " ");
      const words = [];
      if (window.Intl && Intl.Segmenter) {
        let open = "";
        for (const g of new Intl.Segmenter(root.lang || "en", { granularity: "word" }).segment(text)) {
          if (g.isWordLike) { words.push(open + g.segment); open = ""; }
          else if (/^[¿¡「『（(“‘«„[]+$/.test(g.segment) || !words.length) open += g.segment;
          else words[words.length - 1] += g.segment;
        }
        if (open) words.push(open);
      } else words.push(...text.split(" ").map((w, i, all) => w + (i < all.length - 1 ? " " : "")));
      h1.textContent = "";
      words.forEach((w) => {
        const span = document.createElement("span");
        span.className = "word";
        span.textContent = w;
        h1.append(span);
      });
      h1.style.opacity = "1";
      animate($$(".word", h1), { opacity: [0, 1], y: [18, 0], filter: ["blur(4px)", "blur(0px)"] },
        { duration: 0.7, ease: easeOut, delay: stagger(0.045, { startDelay: 0.1 }) });
    }
    rise($$(":scope > :not(h1)", hero), { start: 0.3, gap: 0.08, y: 14 });
    hero.addEventListener("pointermove", (e) => {
      const r = hero.getBoundingClientRect();
      hero.style.setProperty("--mx", `${e.clientX - r.left}px`);
      hero.style.setProperty("--my", `${e.clientY - r.top}px`);
    });
  }

  // ── The showcase: a live spectrum glowing under the app ─────────────
  // Each bar loops at its own pace; the ones near the pointer jump up, like a
  // visualizer reacting to the music.
  const showcase = $(".showcase");
  const slot = $(".spectrum-slot");
  if (showcase && slot) {
    const spectrum = document.createElement("div");
    spectrum.className = "spectrum";
    const count = innerWidth < 560 ? 30 : 64;
    const bars = [];
    for (let i = 0; i < count; i++) {
      const wrap = document.createElement("span");
      wrap.className = "bar";
      const fill = document.createElement("i");
      wrap.append(fill);
      spectrum.append(wrap);
      bars.push(wrap);
      // A rough "music" shape: more energy low and mid, less at the top.
      const t = i / count;
      const energy = 0.3 + 0.6 * Math.exp(-((t - 0.32) ** 2) / 0.07);
      const keys = Array.from({ length: 5 }, () => Math.max(0.06, Math.min(1, energy * (0.35 + Math.random() * 0.75))));
      animate(fill, { scaleY: keys }, {
        duration: 1.6 + Math.random() * 1.4, repeat: Infinity, repeatType: "mirror", ease: "easeInOut", delay: Math.random() * -2,
      });
    }
    slot.append(spectrum);
    once(showcase, () => animate(showcase, { opacity: [0, 1], y: [40, 0], scale: [0.98, 1] }, { duration: 1, ease: easeOut }), 0.1);

    let queued = null;
    const follow = (e) => {
      const r = showcase.getBoundingClientRect();
      showcase.style.setProperty("--mx", `${e.clientX - r.left}px`);
      showcase.style.setProperty("--my", `${e.clientY - r.top}px`);
      const s = spectrum.getBoundingClientRect();
      bars.forEach((b, i) => {
        const x = s.left + (i + 0.5) * (s.width / bars.length);
        const near = Math.max(0, 1 - Math.abs(e.clientX - x) / 140);
        animate(b, { scaleY: 1 + near * 0.7 }, { type: "spring", stiffness: 300, damping: 18 });
      });
    };
    showcase.addEventListener("pointermove", (e) => {
      // One update per frame, however fast the pointer moves.
      if (queued) { queued = e; return; }
      queued = e;
      requestAnimationFrame(() => { const last = queued; queued = null; follow(last); });
    });
    showcase.addEventListener("pointerleave", () => animate(bars, { scaleY: 1 }, { type: "spring", stiffness: 200, damping: 20 }));
  }

  // ── Sections arrive as they scroll in ───────────────────────────────

  $$("section > h2, section > p, .trial, .ticks, .period, section > .card").forEach((el) =>
    once(el, () => rise(el, { y: 22 })));

  $$(".grid").forEach((grid) => once(grid, () => rise(Array.from(grid.children), { gap: 0.09 }), 0.15));

  $$(".steps").forEach((list) => once(list, () => {
    const items = Array.from(list.children);
    rise(items, { gap: 0.12, y: 16 });
    // The numbers (drawn by CSS) pop in after their lines, one beat each.
    items.forEach((li, i) => setTimeout(() => li.classList.add("pop"), 150 + 120 * i));
  }, 0.3));

  // Screenshots fan in like cards dealt onto a table, then tilt under the pointer.
  $$(".shots").forEach((shots) => {
    const figs = Array.from(shots.children);
    once(shots, () => {
      figs.forEach((fig, i) => {
        const side = i - (figs.length - 1) / 2;
        animate(fig, { opacity: [0, 1], y: [90, 0], rotate: [side * 7, 0], scale: [0.92, 1] },
          { type: "spring", stiffness: 140, damping: 18, delay: 0.1 + i * 0.09 });
      });
    }, 0.15);
    if (finePointer) figs.forEach((fig) => {
      const img = $("img", fig);
      fig.addEventListener("pointermove", (e) => {
        const r = fig.getBoundingClientRect();
        const px = (e.clientX - r.left) / r.width - 0.5;
        const py = (e.clientY - r.top) / r.height - 0.5;
        animate(img, { rotateY: px * 14, rotateX: -py * 14, scale: 1.04 }, { type: "spring", stiffness: 260, damping: 20 });
      });
      fig.addEventListener("pointerleave", () =>
        animate(img, { rotateY: 0, rotateX: 0, scale: 1 }, { type: "spring", stiffness: 200, damping: 18 }));
    });
  });

  // Plans: dealt in, prices counted up from zero, the featured one breathing.
  $$(".plans").forEach((plans) => once(plans, () => {
    const cards = Array.from(plans.children);
    cards.forEach((card, i) => {
      animate(card, { opacity: [0, 1], y: [40, 0], scale: [0.96, 1] },
        { type: "spring", stiffness: 160, damping: 20, delay: i * 0.1 });
      const amount = $(".amount", card);
      if (amount) {
        const to = Number(amount.textContent.replace(/[^0-9.]/g, ""));
        animate(0, to, { duration: 1.1, delay: 0.15 + i * 0.1, ease: easeOut, onUpdate: (v) => { amount.textContent = "$" + v.toFixed(2); } });
      }
    });
    const featured = $(".plan.featured", plans);
    if (featured) featured.classList.add("breathing");
  }, 0.2));

  // Legal pages: each paragraph slides in as it is reached.
  $$(".doc > *").forEach((el) => once(el, () => rise(el, { y: 14 }), 0.1));

  // The embedded ranking eases in.
  $$(".embed").forEach((el) => once(el, () => animate(el, { opacity: [0, 1], y: [30, 0] }, { duration: 0.8, ease: easeOut }), 0.05));

  // The numbers count up from zero as they come in.
  $$(".stats").forEach((stats) => once(stats, () => {
    const items = Array.from(stats.children);
    rise(items, { gap: 0.08, y: 18 });
    items.forEach((item, i) => {
      const b = $("b[data-count]", item);
      if (!b) return;
      const to = Number(b.dataset.count);
      animate(0, to, { duration: 1.4, delay: 0.1 + i * 0.08, ease: easeOut, onUpdate: (v) => { b.textContent = Math.round(v).toLocaleString("en-US"); } });
    });
  }, 0.3));

  // The signal path: each stage lights up in turn, then a pulse runs along the line.
  $$(".flow").forEach((flow) => once(flow, () => {
    const stages = Array.from(flow.children);
    animate(stages, { opacity: [0, 1], y: [16, 0], scale: [0.96, 1] },
      { type: "spring", stiffness: 220, damping: 22, delay: stagger(0.11) });
    setTimeout(() => flow.classList.add("live"), 110 * stages.length + 200);
  }, 0.25));

  // The tours: words rise, screens slide in from their side, then drift a little with the scroll.
  $$(".tour").forEach((tour) => {
    const figs = $$(".tour-media figure", tour);
    const from = tour.classList.contains("flip") ? -1 : 1;
    once(tour, () => {
      rise($$(".tour-text > *", tour), { gap: 0.07, y: 18 });
      figs.forEach((fig, i) => animate(fig, { opacity: [0, 1], x: [from * 40, 0], y: [30, 0] },
        { type: "spring", stiffness: 120, damping: 20, delay: 0.12 + i * 0.1 }));
    }, 0.2);
    figs.forEach((fig, i) => {
      const img = $("img", fig);
      if (!img) return; // a drawn diagram stays put
      const depth = 14 + i * 10;
      scroll(animate(img, { y: [depth, -depth] }, { ease: "linear" }), { target: tour, offset: ["start end", "end start"] });
    });
  });

  // ── Touch and pointer feel ──────────────────────────────────────────
  // Buttons and switches give a springy push.
  press(".btn, .period button", (el) => {
    animate(el, { scale: 0.93 }, { type: "spring", stiffness: 600, damping: 20 });
    return () => animate(el, { scale: 1 }, { type: "spring", stiffness: 500, damping: 12 });
  });
  if (finePointer) {
    // Buttons lean toward the pointer a little.
    $$(".btn").forEach((btn) => {
      btn.addEventListener("pointermove", (e) => {
        const r = btn.getBoundingClientRect();
        animate(btn, { x: (e.clientX - r.left - r.width / 2) * 0.18, y: (e.clientY - r.top - r.height / 2) * 0.3 },
          { type: "spring", stiffness: 350, damping: 15 });
      });
      btn.addEventListener("pointerleave", () => animate(btn, { x: 0, y: 0 }, { type: "spring", stiffness: 300, damping: 12 }));
    });
    // Cards lift, and a light follows the pointer across them.
    hover(".card, .plan", (el) => {
      animate(el, { y: -6 }, { type: "spring", stiffness: 300, damping: 18 });
      return () => animate(el, { y: 0 }, { type: "spring", stiffness: 300, damping: 18 });
    });
    $$(".card, .plan").forEach((el) => el.addEventListener("pointermove", (e) => {
      const r = el.getBoundingClientRect();
      el.style.setProperty("--mx", `${e.clientX - r.left}px`);
      el.style.setProperty("--my", `${e.clientY - r.top}px`);
    }));
  }
})();
