// Liquid glass, shared by the site's menu (js/site.js) and the web player's
// bar (js/wp-app.js): window.TryptifyGlass.giveLens(el) bends the page behind
// a .glass element along its rim, glint(el) makes its glint follow the pointer.
// Loaded before either, after <body> has begun (the lenses' SVG goes in it).
(() => {
  "use strict";
  const root = document.documentElement;
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
      // How far the rim bends: --glass-bend if the glass sets one.
      const s = Math.round(parseFloat(getComputedStyle(el).getPropertyValue("--glass-bend")) || Math.min(40, h * 0.62));
      // The blur is part of the lens, so the bend and the blur always go together.
      const blur = parseFloat(getComputedStyle(el).getPropertyValue("--glass-blur")) || 6;
      filter.innerHTML =
        `<feGaussianBlur in="SourceGraphic" stdDeviation="${blur / 2}" edgeMode="duplicate" result="soft"/>` +
        // The map in the glass's own pixels: as a percentage it took the size of the
        // filter's larger area, and the rim fell off the right and bottom edges.
        `<feImage href="${lensMap(w, h, r)}" x="0" y="0" width="${w}" height="${h}" preserveAspectRatio="none" result="map"/>` +
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

  // The cover's own colour, as the app tints its glass with the album's vibrant
  // swatch: the most colourful bright pixel of a small copy. Null for a grey
  // cover, or one from a server that sends no CORS (it cannot be read).
  const vibrants = new Map();
  const vibrant = (url) => {
    if (!url) return Promise.resolve(null);
    if (!vibrants.has(url)) vibrants.set(url, new Promise((done) => {
      const img = new Image();
      img.crossOrigin = "anonymous";
      img.onload = () => {
        try {
          const n = 24, cv = document.createElement("canvas");
          cv.width = n; cv.height = n;
          const x = cv.getContext("2d", { willReadFrequently: true });
          x.drawImage(img, 0, 0, n, n);
          const d = x.getImageData(0, 0, n, n).data;
          let best = null, score = -1;
          for (let i = 0; i < d.length; i += 4) {
            const R = d[i], G = d[i + 1], B = d[i + 2], max = Math.max(R, G, B), min = Math.min(R, G, B);
            const sat = max ? (max - min) / max : 0, val = max / 255;
            const sc = sat * 0.7 + val * 0.3 - (val < 0.35 ? 1 : 0);
            if (sc > score) { score = sc; best = [R, G, B]; }
          }
          done(best && score > 0.35 ? best : null);
        } catch (_) { done(null); }
      };
      img.onerror = () => done(null);
      img.src = url;
    }));
    return vibrants.get(url);
  };

  window.TryptifyGlass = { giveLens, glint, lensOn, vibrant };
})();
