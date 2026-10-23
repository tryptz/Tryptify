// The download page: version, size and SHA-256 from /data/apk.json, which is
// published with each APK (see README).
(() => {
  "use strict";
  const info = document.getElementById("apk-info");
  const sha = document.getElementById("apk-sha");
  fetch("/data/apk.json", { cache: "no-cache" })
    .then((r) => (r.ok ? r.json() : Promise.reject(new Error(String(r.status)))))
    .then((a) => {
      info.textContent = T("Version {version} · {size} MB · Android {android} and newer.", { version: a.version, size: Math.round(a.size / 1e6), android: a.minAndroid });
      sha.textContent = a.sha256;
    })
    .catch(() => { sha.textContent = T("Unavailable right now."); });
})();
