// Black or white: the one last picked on any page, else the device's own.
// Loaded in <head>, before the page is drawn, so it never flashes the other
// one. A file and not an inline script, so the site's Content-Security-Policy
// (_headers) can refuse every inline script.
(function () {
  var t = null;
  try { t = localStorage.getItem("tryptify-theme"); } catch (e) {}
  if (t !== "dark" && t !== "white") t = matchMedia("(prefers-color-scheme: light)").matches ? "white" : "dark";
  document.documentElement.dataset.design = t;
})();
