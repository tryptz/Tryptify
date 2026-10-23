// Black or white: the one last picked on any page, else the device's own.
// Loaded in <head>, before the page is drawn, so it never flashes the other
// one. A file and not an inline script, so the site's Content-Security-Policy
// (_headers) can refuse every inline script.
(function () {
  var t = null;
  try { t = localStorage.getItem("tryptify-theme"); } catch (e) {}
  if (t !== "dark" && t !== "white") t = matchMedia("(prefers-color-scheme: light)").matches ? "white" : "dark";
  document.documentElement.dataset.design = t;
  // Motion only when it is welcome (site.js waits for .motion); if site.js has
  // not started in 3 s, show everything as is. The web player has no site.js.
  if (!matchMedia("(prefers-reduced-motion: reduce)").matches && !/\/player(\.html)?$/.test(location.pathname)) {
    var root = document.documentElement;
    root.classList.add("motion");
    setTimeout(function () { if (!window.__tryptifyMotion) root.classList.remove("motion"); }, 3000);
  }
})();

// The language: the site is in the app's eight (tools/site_l10n). An English
// page sends a visitor to their own language, the one last picked from the
// footer's list, else the browser's first language the site speaks; a
// translated page stays as it is, so a link to one is always kept. Picking
// English from the list stops it. A sign-in coming back (?code=) is left alone.
(function () {
  var LANGS = ["zh", "ja", "fr", "es", "tr", "de", "it"];
  var PAGES = ["/", "/cloud", "/autoeq", "/player", "/account", "/contact", "/download", "/terms", "/privacy", "/refunds", "/changelog", "/party"];
  document.addEventListener("click", function (e) {
    var a = e.target && e.target.closest ? e.target.closest("a[data-lang]") : null;
    if (!a) return;
    try { localStorage.setItem("tryptify-lang", a.getAttribute("data-lang")); } catch (_) {}
    if (location.hash && a.href.indexOf("#") < 0) a.href += location.hash;  // the player keeps its view
  });
  var path = location.pathname.replace(/\.html$/, "").replace(/(.)\/$/, "$1");
  // A party invite (/party/<code>) goes to its language with its code.
  if (/^\/(zh|ja|fr|es|tr|de|it)(\/|$)/.test(path) || PAGES.indexOf(path.replace(/^\/party\/[A-Za-z0-9]+$/, "/party")) < 0) return;
  if (/[?&#](code|error|access_token)=/.test(location.search + location.hash)) return;
  var want = null;
  try { want = localStorage.getItem("tryptify-lang"); } catch (_) {}
  if (!want) {
    var list = navigator.languages || [navigator.language || ""];
    for (var i = 0; i < list.length; i++) {
      var l = String(list[i]).toLowerCase().slice(0, 2);
      if (l === "en" || LANGS.indexOf(l) >= 0) { want = l; break; }
    }
  }
  if (!want || want === "en" || LANGS.indexOf(want) < 0) return;
  location.replace("/" + want + (path === "/" ? "" : path) + location.search + location.hash);
})();

// Text the pages' scripts write, in the page's language: T("Playing on {name}", { name })
// gives the translation from /js/l10n/<lang>.js (loaded ahead of the scripts on a
// translated page, see tools/site_l10n), else the English, with {name} filled in.
window.T = function (s, vars) {
  var d = window.TRYPTIFY_L10N || {};
  var t = Object.prototype.hasOwnProperty.call(d, s) ? d[s] : s;
  return vars ? t.replace(/\{(\w+)\}/g, function (m, k) { return k in vars ? String(vars[k]) : m; }) : t;
};
