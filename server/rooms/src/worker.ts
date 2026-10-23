// SPDX-License-Identifier: GPL-3.0-or-later
// Tryptify's rooms: Trypt Mania's game rooms and the listening parties, on
// Cloudflare. Everything but the health check goes to the one hub
// (src/hub.ts). The website (tryptify.com/party) calls the party routes from
// another origin with a song token, never a cookie, so they answer CORS for
// any origin; the phones do not need it.
//
//   GET  /health                         liveness
//   GET  /api/multiplayer/info           what this is; the app's probe
//   GET  /ws                             one socket per phone or browser tab
//   PUT  /api/multiplayer/song?hash=…    the host shares the picked chart's song
//   GET  /api/multiplayer/song?hash=…    a player in the room fetches it
//   PUT  /api/party/song?item=…&ext=…    party: whoever added a song shares it
//   GET  /api/party/song?item=…&token=…  party: a member fetches it (Range too)
//   GET  /api/party/cover|search|stream  party: the website's cover, Add songs, catalogue songs
//   GET  /party/<code>                   an old-style invite: sent on to tryptify.com
//   GET  /.well-known/assetlinks.json    lets the app open this host's invite links

import { Hub, type Env } from "./hub";
import { Ffmpeg } from "./ffmpeg";

export { Hub, Ffmpeg };

/**
 * The Android app invite links open in: its package and its release signing
 * certificate's SHA-256, which are in every copy of the app. tryptify.com
 * serves the same, for its own /party links.
 */
const ASSET_LINKS = [{
  relation: ["delegate_permission/common.handle_all_urls"],
  target: {
    namespace: "android_app",
    package_name: "tf.monotrypt.android",
    sha256_cert_fingerprints: ["70:96:05:04:EE:8A:2E:06:06:7F:7D:8E:72:9A:74:D5:F1:1B:F0:18:AB:80:3F:5C:C7:D6:80:87:CA:EF:02:DC"],
  },
}];

const CORS = {
  "access-control-allow-origin": "*",
  "access-control-allow-methods": "GET, HEAD, PUT, OPTIONS",
  "access-control-allow-headers": "Range, X-Room-Key, Content-Type",
  "access-control-expose-headers": "Content-Length, Content-Range, Accept-Ranges, ETag, X-Song-Ext",
  "access-control-max-age": "86400",
};

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);
    if (url.pathname === "/health") return Response.json({ ok: true });
    if (url.pathname === "/.well-known/assetlinks.json") return Response.json(ASSET_LINKS);
    // An invite made by an older app pointed here; the party page lives on the website now.
    const invite = url.pathname.match(/^\/party\/([A-Za-z0-9]{4,8})\/?$/);
    if (invite) return Response.redirect(`https://tryptify.com/party/${invite[1].toUpperCase()}`, 302);
    if (url.pathname === "/" || url.pathname === "/party") return Response.redirect("https://tryptify.com/party", 302);
    if (request.method === "OPTIONS") return new Response(null, { status: 204, headers: CORS });
    const hub = env.HUB.get(env.HUB.idFromName("hub"));
    const response = await hub.fetch(request);
    if (response.status === 101 || response.webSocket) return response;
    const headers = new Headers(response.headers);
    for (const [k, v] of Object.entries(CORS)) headers.set(k, v);
    return new Response(response.body, { status: response.status, statusText: response.statusText, headers });
  },
} satisfies ExportedHandler<Env>;
