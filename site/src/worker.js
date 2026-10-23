// The website is static files (public/), served by Workers static assets.
// This script answers /download/tryptify.apk and /video/*. The APK is too big for a
// static asset (25 MiB at most), so it lives in the R2 bucket
// tryptify-downloads, uploaded with its file name (and so its version) as its
// Content-Disposition; its version, size and SHA-256 are also published in
// public/data/apk.json (see README). Videos are static files, but static
// assets answer a Range request with the whole file, and Safari will not
// play a video served that way, so their ranges are cut here. Everything
// else is a static file.

const APK = "tryptify.apk";

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (url.pathname.startsWith("/video/")) return video(request, env);
    if (url.pathname !== "/download/tryptify.apk") return page(await env.ASSETS.fetch(request));
    if (request.method !== "GET" && request.method !== "HEAD") {
      return new Response("Method not allowed", { status: 405, headers: { allow: "GET, HEAD" } });
    }
    // Range requests let download managers resume a 90 MB file.
    const object = request.method === "HEAD"
      ? await env.DOWNLOADS.head(APK)
      : await env.DOWNLOADS.get(APK, { range: request.headers, onlyIf: request.headers });
    if (!object) return new Response("The download is being updated. Try again in a minute.", { status: 503 });
    const headers = new Headers({
      "content-type": "application/vnd.android.package-archive",
      "content-disposition": object.httpMetadata?.contentDisposition ?? 'attachment; filename="Tryptify.apk"',
      "accept-ranges": "bytes",
      etag: object.httpEtag,
      "cache-control": "public, max-age=300",
    });
    if (!("body" in object) || !object.body) {
      headers.set("content-length", String(object.size));
      return new Response(null, { status: request.method === "HEAD" ? 200 : 304, headers });
    }
    if (object.range) {
      const { offset = 0, length = object.size - offset } = object.range;
      headers.set("content-range", `bytes ${offset}-${offset + length - 1}/${object.size}`);
      headers.set("content-length", String(length));
      return new Response(object.body, { status: 206, headers });
    }
    headers.set("content-length", String(object.size));
    return new Response(object.body, { headers });
  },
};

// A page with a nonce of its own in its Content-Security-Policy (from
// public/_headers). The site has no inline script; the nonce is for the ones
// Cloudflare adds to every page (its bot check), which it marks with the
// nonce it finds in this header. A fresh one each time, or it proves nothing.
function page(res) {
  const csp = res.headers.get("content-security-policy");
  if (!csp || !(res.headers.get("content-type") || "").startsWith("text/html")) return res;
  const nonce = btoa(String.fromCharCode(...crypto.getRandomValues(new Uint8Array(16))));
  const headers = new Headers(res.headers);
  headers.set("content-security-policy", csp.replace(/script-src 'self'/, `script-src 'self' 'nonce-${nonce}'`));
  return new Response(res.body, { status: res.status, statusText: res.statusText, headers });
}

// A video from the static files, with byte ranges: "bytes=a-b", "bytes=a-"
// or "bytes=-n". The clips are a megabyte or two, so the whole file is read
// and the range cut from it.
async function video(request, env) {
  const asset = await env.ASSETS.fetch(new Request(request.url, { method: "GET" }));
  if (!asset.ok) return asset;
  const range = request.headers.get("range");
  const headers = new Headers(asset.headers);
  headers.set("accept-ranges", "bytes");
  headers.set("cache-control", "public, max-age=3600");
  if (!range) {
    if (request.method === "HEAD") return new Response(null, { headers });
    return new Response(asset.body, { headers });
  }
  const body = await asset.arrayBuffer();
  const size = body.byteLength;
  const m = /^bytes=(\d*)-(\d*)$/.exec(range.trim());
  let start, end;
  if (m && m[1] !== "") { start = Number(m[1]); end = m[2] !== "" ? Math.min(Number(m[2]), size - 1) : size - 1; }
  else if (m && m[2] !== "") { start = Math.max(0, size - Number(m[2])); end = size - 1; }
  if (start === undefined || start > end || start >= size) {
    headers.delete("content-length");
    headers.set("content-range", `bytes */${size}`);
    return new Response(null, { status: 416, headers });
  }
  headers.set("content-range", `bytes ${start}-${end}/${size}`);
  headers.set("content-length", String(end - start + 1));
  return new Response(request.method === "HEAD" ? null : body.slice(start, end + 1), { status: 206, headers });
}
