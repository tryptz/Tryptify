// One account's Tryptify Connect room, a Durable Object: its devices' open
// connections, and what is playing where. It uses the WebSocket Hibernation
// API, so between messages it is not running at all: the connections stay
// open at Cloudflare's edge, and the devices' "ping" keep-alives are answered
// there ("pong") without waking it. It keeps only `now` (what plays, where,
// and since when); everything else lives on the devices.

import { DurableObject } from "cloudflare:workers";
import {
  MAX_DEVICES, MAX_MESSAGE, cleanCommand, cleanQueue, cleanState, deviceFrom, positionAt,
  type Device, type Now,
} from "./protocol";

type Attachment = Device & { since: number };
type Socket = WebSocket & { serializeAttachment(v: unknown): void; deserializeAttachment(): unknown };

export class Room extends DurableObject {
  private budget = new WeakMap<WebSocket, { tokens: number; last: number }>();

  constructor(ctx: DurableObjectState, env: unknown) {
    super(ctx, env as never);
    (ctx as any).setWebSocketAutoResponse(new (globalThis as any).WebSocketRequestResponsePair("ping", "pong"));
  }

  private sockets(): Socket[] { return (this.ctx as any).getWebSockets() as Socket[]; }
  private me(ws: WebSocket): Attachment { return (ws as Socket).deserializeAttachment() as Attachment; }
  private send(ws: WebSocket, msg: Record<string, unknown>) {
    try { ws.send(JSON.stringify({ ...msg, serverTime: Date.now() })); } catch { /* closing */ }
  }
  private to(id: string, msg: Record<string, unknown>) {
    for (const ws of (this.ctx as any).getWebSockets(id) as WebSocket[]) this.send(ws, msg);
  }
  private async now(): Promise<(Now & { name?: string }) | null> { return (await this.ctx.storage.get("now")) ?? null; }

  /** The devices online now; the one playing is marked. */
  private devices(except?: WebSocket, playing?: string | null) {
    const seen = new Map<string, Attachment & { active: boolean }>();
    for (const ws of this.sockets()) {
      if (ws === except || ws.readyState !== 1) continue;
      const a = this.me(ws);
      if (a && !seen.has(a.id)) seen.set(a.id, { ...a, active: a.id === playing });
    }
    return [...seen.values()];
  }
  /** Tells everyone but [skip] who is online; [gone] is a connection on its way out, left off the list. */
  private async broadcastDevices(skip?: WebSocket, gone?: WebSocket) {
    const now = await this.now();
    const devices = this.devices(gone, now && now.playing ? now.device : null);
    for (const ws of this.sockets()) if (ws !== skip && ws !== gone) this.send(ws, { type: "devices", devices });
  }

  async fetch(request: Request): Promise<Response> {
    const url = new URL(request.url);
    const device = deviceFrom(url.searchParams);
    if (!device) return new Response("A device id is needed.", { status: 400 });
    // One connection per device: a new one (a reload, a second tab) replaces the old.
    for (const old of (this.ctx as any).getWebSockets(device.id) as WebSocket[]) old.close(4000, "replaced");
    if (this.devices().length >= MAX_DEVICES) return new Response("Too many devices.", { status: 429 });

    const pair = new WebSocketPair();
    const [client, server] = [pair[0], pair[1]];
    (this.ctx as any).acceptWebSocket(server, [device.id]);
    (server as Socket).serializeAttachment({ ...device, since: Date.now() } satisfies Attachment);
    const now = await this.now();
    this.send(server, {
      type: "welcome", you: device.id, now: now ? { ...now, position: positionAt(now, Date.now()), at: Date.now() } : null,
      devices: this.devices(undefined, now && now.playing ? now.device : null),
    });
    await this.broadcastDevices(server); // it has the list already, in its welcome
    const headers = new Headers();
    if (request.headers.get("x-connect-protocol") === "1") headers.set("Sec-WebSocket-Protocol", "tryptify-connect");
    return new Response(null, { status: 101, webSocket: client, headers });
  }

  async webSocketMessage(ws: WebSocket, raw: string | ArrayBuffer) {
    if (typeof raw !== "string" || raw.length > MAX_MESSAGE) return;
    // At most 10 messages a second, bursts of 20: a stuck client cannot keep the room busy.
    const t = Date.now(), b = this.budget.get(ws) ?? { tokens: 20, last: t };
    b.tokens = Math.min(20, b.tokens + ((t - b.last) / 1000) * 10); b.last = t;
    if (b.tokens < 1) return;
    b.tokens -= 1; this.budget.set(ws, b);

    let m: Record<string, unknown>;
    try { m = JSON.parse(raw); } catch { return; }
    if (!m || typeof m !== "object") return;
    const me = this.me(ws);

    if (m.type === "state") {
      // What plays here changed (a song, play, pause, a seek): kept, and shown everywhere else.
      const prev = await this.now();
      const now = { ...cleanState(m, me.id, t), name: me.name };
      if (!now.playing && prev && prev.device !== me.id && prev.playing) return; // a paused device does not take over
      await this.ctx.storage.put("now", now);
      for (const other of this.sockets()) if (other !== ws) this.send(other, { type: "now", now });
      if (!prev || prev.device !== now.device || prev.playing !== now.playing) await this.broadcastDevices();
    } else if (m.type === "command") {
      // Remote control: play, pause, next, previous or seek, sent to the device playing.
      const c = cleanCommand(m), target = typeof m.to === "string" ? m.to : "";
      if (c && target && target !== me.id) this.to(target, { type: "command", from: me.id, ...c });
    } else if (m.type === "transfer") {
      // "Play on [to]": the device playing hands over its exact place and its queue.
      const target = typeof m.to === "string" ? m.to : "";
      if (!(this.ctx as any).getWebSockets(target).length) return this.send(ws, { type: "error", message: "That device is not online." });
      const now = await this.now();
      const holder = now ? (this.ctx as any).getWebSockets(now.device) as WebSocket[] : [];
      if (now && now.device === target) return;
      if (now && holder.length && m.force !== true) {
        this.to(now.device, { type: "release", to: target, toName: this.me(((this.ctx as any).getWebSockets(target) as WebSocket[])[0]).name });
      } else {
        // Nobody to ask (or no answer came): start from what the room last heard.
        this.to(target, { type: "takeover", from: now ? now.device : null, now: now ? { ...now, position: positionAt(now, t), at: t } : null, queue: [] });
      }
    } else if (m.type === "handoff") {
      // The releasing device's answer: its place to the second, and what comes next.
      const target = typeof m.to === "string" ? m.to : "";
      if (!target || target === me.id) return;
      const now = { ...cleanState((m.now as Record<string, unknown>) ?? {}, me.id, t), name: me.name };
      this.to(target, { type: "takeover", from: me.id, now, queue: cleanQueue(m.queue) });
    }
  }

  async webSocketClose(ws: WebSocket, code: number) {
    try { ws.close(code === 1005 || code === 1006 ? 1000 : code, "bye"); } catch { /* already closed */ }
    await this.broadcastDevices(ws, ws);
  }
  async webSocketError(ws: WebSocket) { await this.broadcastDevices(ws, ws); }
}
