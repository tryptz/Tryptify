package tf.monochrome.android.ui.settings

import android.graphics.Paint
import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import tf.monochrome.android.domain.model.RoomShape
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The room the headphone render plays in, drawn by the GPU: one AGSL pass in
 * which every pixel is a ray from a camera that orbits the room.
 *
 * The sound is grain: the room's air is a grid of cells with one speck in
 * each, at a place and with a rank that are the cell's own. A speck shows
 * when there is more sound where it is than its rank, so a speaker has a
 * cloud of specks round it that is denser and reaches further the harder it
 * is driven, the spots where its sound turns on a wall towards the listener
 * ([RoomScene.sources]) have a smaller one, and what the room still holds is
 * a thin grain through all of it. The specks stand in the room, so they turn
 * with it. The ray goes through the grid cell by cell and meets the specks
 * it passes.
 *
 * Where the ray leaves the room is the wall that is seen: its edges and the
 * floor's metre grid; the wall it came in through is drawn faintly, so the
 * room is looked into and not at. Speakers, objects, the measurement's
 * loudspeakers and the rays to the listener are lights the ray passes; the
 * listener is a head that hides what is behind it. Those marks are a size on
 * the page, not in the room (`uUnit`): a head its true size in a hall would
 * be one pixel.
 *
 * The room is a distance function ([RoomShape]'s geometry again, in the
 * shader's `room`): a floor plan with rounded corners, drawn in above ear
 * height as the walls lean and curve. So any room the app can describe is
 * drawn by the same few lines, and a new shape is a change to that function.
 *
 * Colours are the theme's; everything is laid front to back as tinted cover
 * (`under`), so it reads on a light theme as on a dark one.
 */
internal const val ROOM_SRC = """
uniform float2 uRes;       // the picture, in pixels
uniform float3 uEye;       // the camera, in metres
uniform float3 uRight;     // its basis: right, up, forward
uniform float3 uUp;
uniform float3 uFwd;
uniform float uFocal;      // focal length over the picture's height
uniform float3 uRoom;      // width, depth, height
uniform float uLean;       // the height the walls lean from
uniform float3 uCeil;      // ceiling width and depth as shares of the floor's; the walls' curve
uniform float uCorner;     // corner radius
uniform float3 uListener;
uniform float4 uSpk[32];   // each speaker: where, and how hard it is driven (0..1)
uniform float uSpkKind[32];  // 0 ear level, 1 a height speaker, 2 the LFE
uniform float uSpkAir[32];   // its level averaged over a second or so: the grain round it
uniform float uSpkCount;
uniform float4 uObj[32];   // each object: where, and its level; under zero, a height object
uniform float uObjCount;
uniform float uMirror;     // how much the floor mirrors what plays above it
uniform float uObjGrain;   // how much grain an object at full level gathers: 0 with the sound not shown
uniform float4 uSrc[12];   // where the loudest speakers' sound turns on a surface, and how strong
uniform float uSrcCount;
uniform float4 uRay[3];    // the loudest speakers, for their ray to the listener
uniform float4 uMark[8];   // the loudspeakers the room was measured with
uniform float uMarkCount;
uniform float3 uGrain;     // the grain: a cell's side, how far a loud speaker's cloud reaches, a speck's cover
uniform float3 uCloud;     // of that reach: a silent speaker's, a turning spot's; and the grain a full room holds
uniform float uGlow;       // what the room still holds, times the Room amount: the grain through all of it
uniform float uConvex;     // 1: no wall bends outwards, so a ray is inside the room once at most
uniform float uUnit;       // how many times their size in the built-in room the marks are drawn
uniform float3 uPrimary;   // the theme: speakers and waves,
uniform float3 uTertiary;  // heights,
uniform float3 uInk;       // the room's lines and the listener,
uniform float uGrid;       // and whether the floor has a metre grid

const int STEPS = 36;    // along a ray, looking for the walls
const int CELLS = 138;   // the most grain cells a ray goes through

// Three numbers in 0..1 that are a cell's own.
float3 hash3(float3 p) {
    p = fract(p * float3(0.1031, 0.1030, 0.0973));
    p += dot(p, p.yxz + 33.33);
    return fract((p.xxy + p.yxx) * p.zyx);
}

// e to the minus x. Never asked for the vanishingly small: a renderer's quick
// exp is not to be trusted there, and one of them drew a far speaker over the
// listener's head.
float fade(float x) {
    return exp(-min(x, 30.0));
}

// How far in the walls have leaned at height z: 0 up to where they start, 1 at the ceiling.
float rise(float z) {
    float r = clamp((z - uLean) / max(uRoom.z - uLean, 0.001), 0.0, 1.0);
    return uCeil.z == 1.0 ? r : pow(r, uCeil.z);
}

// Half the room's width and depth at height z.
float2 halfSize(float z) {
    float r = rise(z);
    return 0.5 * uRoom.xy * float2(1.0 - (1.0 - uCeil.x) * r, 1.0 - (1.0 - uCeil.y) * r);
}

// The floor plan at height z: under zero inside, the distance to the wall.
float plan(float3 p) {
    float2 h = halfSize(p.z);
    float rc = min(uCorner, min(h.x, h.y));
    float2 q = abs(p.xy - 0.5 * uRoom.xy) - h + rc;
    return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - rc;
}

// The room: under zero inside.
float room(float3 p) {
    return max(plan(p), max(-p.z, p.z - uRoom.z));
}

// The sound at p: whose colour it has, and how much of it there is (0..1).
// A speaker's falls away round it, further the harder it is driven; what the
// room holds is the same everywhere.
float4 sound(float3 p) {
    float sum = uGlow * uCloud.z;
    float3 tint = uPrimary * sum;
    for (int i = 0; i < 32; i++) {
        if (float(i) >= uSpkCount) break;
        float level = uSpkAir[i];
        if (level <= 0.0) continue;
        float reach = uGrain.y * (uCloud.x + level);
        float3 to = p - uSpk[i].xyz;
        float w = level * sqrt(level) * fade(dot(to, to) / (reach * reach));
        sum += w;
        tint += (uSpkKind[i] == 1.0 ? uTertiary : uPrimary) * w;
    }
    // The objects: where the mix puts its sounds, so where the grain gathers too.
    for (int i = 0; i < 32; i++) {
        if (float(i) >= uObjCount) break;
        float level = abs(uObj[i].w);
        float reach = uGrain.y * uCloud.y * (0.5 + level);
        float3 to = p - uObj[i].xyz;
        float w = uObjGrain * level * fade(dot(to, to) / (reach * reach));
        sum += w;
        tint += (uObj[i].w < 0.0 ? uTertiary : uPrimary) * w;
    }
    for (int i = 0; i < 12; i++) {
        if (float(i) >= uSrcCount) break;
        float reach = uGrain.y * uCloud.y;
        float3 to = p - uSrc[i].xyz;
        float w = uSrc[i].w * fade(dot(to, to) / (reach * reach));
        sum += w;
        tint += uPrimary * w;
    }
    return float4(tint / max(sum, 1e-4), min(sum, 1.0));
}

// A point of light seen along the ray: the square of how far the ray passes from it.
float passes(float3 ro, float3 rd, float3 p, float limit) {
    float3 to = p - ro;
    float along = dot(to, rd);
    if (along < 0.0 || along > limit) return 1e6;
    return dot(to, to) - along * along;
}

// How far the ray passes from the line a..b, squared.
float passesLine(float3 ro, float3 rd, float3 a, float3 b) {
    float3 u = b - a;
    float3 w = ro - a;
    float uu = dot(u, u);
    float ud = dot(u, rd);
    float uw = dot(u, w);
    float dw = dot(rd, w);
    float den = uu - ud * ud;
    float s = den > 1e-5 ? clamp((uw - ud * dw) / den, 0.0, 1.0) : 0.0;
    float t = max(ud * s - dw, 0.0);
    float3 gap = (a + u * s) - (ro + rd * t);
    return dot(gap, gap);
}

// Lays one layer over what is there: colour and cover, front to back.
float4 under(float4 acc, float3 tint, float cover) {
    float a = clamp(cover, 0.0, 1.0) * (1.0 - acc.a);
    return float4(acc.rgb + tint * a, acc.a + a);
}

// What the floor gives back at p, for a ray coming down along rd: the
// speakers that play and the objects, as a glossy floor mirrors them,
// blurred and fainter the further they are from it.
float4 mirrored(float3 p, float3 rd) {
    float3 rr = float3(rd.xy, -rd.z);
    float4 back = float4(0.0);
    for (int i = 0; i < 32; i++) {
        if (float(i) >= uSpkCount) break;
        float level = uSpk[i].w;
        if (level < 0.02) continue;
        float3 to = uSpk[i].xyz - p;
        float along = dot(to, rr);
        if (along <= 0.0) continue;
        float blur = (0.05 + 0.025 * along) * uUnit;
        float d2 = dot(to, to) - along * along;
        if (d2 > 9.0 * blur * blur) continue;
        float3 tint = uSpkKind[i] == 1.0 ? uTertiary : uPrimary;
        back = under(back, tint, level * 0.85 * fade(d2 / (blur * blur)));
    }
    for (int i = 0; i < 32; i++) {
        if (float(i) >= uObjCount) break;
        float level = abs(uObj[i].w);
        float3 to = uObj[i].xyz - p;
        float along = dot(to, rr);
        if (along <= 0.0) continue;
        float blur = (0.04 + 0.02 * along) * uUnit;
        float d2 = dot(to, to) - along * along;
        if (d2 > 9.0 * blur * blur) continue;
        back = under(back, uObj[i].w < 0.0 ? uTertiary : uInk, level * 0.35 * fade(d2 / (blur * blur)));
    }
    return back;
}


half4 main(float2 fragCoord) {
    float2 uv = (fragCoord - 0.5 * uRes) / uRes.y;
    float3 ro = uEye;
    float3 rd = normalize(uFwd * uFocal + uRight * uv.x - uUp * uv.y);
    float4 acc = float4(0.0);

    // Where the ray is inside the room's box.
    float3 inv = 1.0 / (rd + 1e-6 * (1.0 - abs(sign(rd))));
    float3 t0 = (float3(0.0) - ro) * inv;
    float3 t1 = (uRoom - ro) * inv;
    float3 tmin = min(t0, t1);
    float3 tmax = max(t0, t1);
    float tNear = max(max(tmin.x, tmin.y), max(tmin.z, 0.0));
    float tFar = min(tmax.x, min(tmax.y, tmax.z));
    // The same box a little bigger, to look for the walls in: a ray that just
    // misses the room is still asked how nearly, so the room's outline is soft.
    float3 pad = float3(0.03 * max(uRoom.x, uRoom.y));
    t0 = (-pad - ro) * inv;
    t1 = (uRoom + pad - ro) * inv;
    tmin = min(t0, t1);
    tmax = max(t0, t1);
    float wNear = max(max(tmin.x, tmin.y), max(tmin.z, 0.0));
    float wFar = min(tmax.x, min(tmax.y, tmax.z));
    // Nothing of the room is outside that box: most of the picture's corners stop here.
    if (wFar <= wNear) return half4(0.0);
    float pixel = 1.0 / (uFocal * uRes.y);  // a pixel's width, a metre off

    // The listener: a head, which hides what is behind it. Its edge is soft
    // over a pixel, as the room's outline is.
    float headT = -1.0;
    float headCover = 0.0;
    {
        float3 to = uListener - ro;
        float along = dot(to, rd);
        float d2 = dot(to, to) - along * along;
        float r = 0.12 * uUnit;
        if (along > 0.0) headCover = clamp(0.5 + (r - sqrt(d2)) / (pixel * along), 0.0, 1.0);
        if (headCover > 0.0) headT = along - sqrt(max(r * r - d2, 0.0));
    }

    float exitT = -1.0;   // where the ray last leaves the room: the wall that is seen
    float enterT = -1.0;  // where it first comes in: the wall that is looked through
    float cover = 0.0;    // how much of the pixel the room takes: all of it inside, less across its outline
    {
        float lastIn = -1.0;
        float firstIn = -1.0;
        float least = 1e6;      // the nearest the ray comes to being inside (under zero: it is), and where
        float tLeast = 0.5 * (wNear + wFar);
        // Where to look for that: all of the ray, in a room no wall of which
        // bends outwards, since how far outside it is then falls and rises
        // once along it; else round the best of even steps, which also say
        // where it first and last is inside.
        float a = wNear;
        float b = wFar;
        float span = wFar - wNear;
        if (uConvex < 0.5) {
            span /= float(STEPS);
            for (int i = 0; i < STEPS; i++) {
                float t = wNear + (float(i) + 0.5) * span;
                float d = room(ro + rd * t);
                if (d < least) {
                    least = d;
                    tLeast = t;
                }
                if (d < 0.0) {
                    if (firstIn < 0.0) firstIn = t;
                    lastIn = t;
                }
            }
            a = tLeast - span;
            b = tLeast + span;
        }
        // Nearer than steps can say: a ray that clips a corner between two of
        // them is in the room all the same. Without this the outline came out
        // in stairs, a step of the march to each.
        for (int i = 0; i < 12; i++) {
            float m1 = a + (b - a) / 3.0;
            float m2 = b - (b - a) / 3.0;
            if (room(ro + rd * m1) < room(ro + rd * m2)) b = m2; else a = m1;
        }
        float tMid = 0.5 * (a + b);
        float dMid = room(ro + rd * tMid);
        if (dMid < least) {
            least = dMid;
            tLeast = tMid;
        }
        cover = clamp(0.5 - least / (pixel * tLeast), 0.0, 1.0);
        if (firstIn < 0.0 && cover > 0.0) {
            firstIn = tLeast;
            lastIn = tLeast;
        }
        if (lastIn > 0.0) {
            // The wall itself: between the last place known to be inside and
            // the next step, or the box's end.
            a = lastIn;
            b = min(lastIn + span, wFar + 0.01);
            for (int i = 0; i < 11; i++) {
                float m = 0.5 * (a + b);
                if (room(ro + rd * m) < 0.0) a = m; else b = m;
            }
            exitT = 0.5 * (a + b);
            a = max(firstIn - span, wNear - 0.01);
            b = firstIn;
            for (int i = 0; i < 11; i++) {
                float m = 0.5 * (a + b);
                if (room(ro + rd * m) < 0.0) b = m; else a = m;
            }
            enterT = 0.5 * (a + b);
        }
    }
    // The grain, when there is any sound to show.
    if (tFar > tNear && uGrain.z > 0.0) {
        float stop = headT > 0.0 ? min(tFar, headT) : tFar;
        // The grain, cell by cell from the near wall to the far one. A speck
        // keeps clear of its cell's sides by more than its own size, so the
        // ray that meets it is in its cell.
        float side = uGrain.x;
        float3 stepTo = step(0.0, rd) * 2.0 - 1.0;
        float3 cell = floor((ro + rd * (tNear + 1e-3)) / side);
        float3 tNext = ((cell + max(stepTo, 0.0)) * side - ro) * inv;
        float3 tCell = abs(side * inv);
        for (int i = 0; i < CELLS; i++) {
            float3 own = hash3(cell);
            float3 at = (cell + 0.16 + 0.68 * own) * side;
            float3 to = at - ro;
            float along = dot(to, rd);
            // Fine, but never so fine that it is one pixel on and the next off:
            // it is a soft dot a pixel or two across, however far off it is.
            float size = clamp(1.5 * pixel * along, 0.06 * side, 0.15 * side);
            float d2 = dot(to, to) - along * along;
            if (along > 0.0 && along < stop && d2 < size * size && room(at) < 0.0) {
                float4 here = sound(at);
                // Its rank; it comes and goes over a tenth of the way, not at once.
                float rank = fract(own.x + own.y * 7.0 + own.z * 13.0);
                float shown = smoothstep(0.0, 0.1, here.a - 0.9 * rank);
                float disc = fade(3.5 * d2 / (size * size));
                acc = under(acc, here.rgb, shown * disc * uGrain.z);
            }
            float tOut = min(tNext.x, min(tNext.y, tNext.z));
            if (tOut >= tFar || acc.a > 0.97) break;
            float3 go = step(tNext.xyz, tNext.yzx) * step(tNext.xyz, tNext.zxy);
            cell += go * stepTo;
            tNext += go * tCell;
        }
    }
    float limit = headT > 0.0 ? headT : 1e5;

    // The lights, laid over the waves: speakers, objects, the rays between.
    float4 lights = float4(0.0);
    for (int i = 0; i < 32; i++) {
        if (float(i) >= uSpkCount) break;
        float d2 = passes(ro, rd, uSpk[i].xyz, limit);
        float level = uSpk[i].w;
        float kind = uSpkKind[i];
        float3 tint = kind == 1.0 ? uTertiary : uPrimary;
        float size = (kind == 2.0 ? 0.085 : 0.062) * uUnit;
        if (d2 > 4.0 * size * size) continue;  // too far from the ray to show: most of them, at any pixel
        // A ring that is always there, filled as the speaker plays: its sound is the grain round it.
        float off = (sqrt(d2) - size) / (0.012 * uUnit);
        float ring = fade(off * off);
        float core = fade(d2 / (size * size * 0.55)) * level;
        lights = under(lights, tint, ring * 0.6 + core);
    }
    for (int i = 0; i < 32; i++) {
        if (float(i) >= uObjCount) break;
        float level = abs(uObj[i].w);
        float3 tint = uObj[i].w < 0.0 ? uTertiary : uInk;
        float size = (0.05 + 0.06 * level) * uUnit;
        float d2 = passes(ro, rd, uObj[i].xyz, limit);
        float footD2 = passes(ro, rd, float3(uObj[i].xy, 0.01), limit);
        if (min(d2, footD2) > 60.0 * size * size) continue;
        float core = fade(d2 / (size * size)) * (0.45 + 0.55 * level);
        float halo = fade(d2 / (size * size * 9.0)) * level * 0.35;
        // Its place on the floor, so its height can be read.
        float foot = fade(footD2 / (0.0012 * uUnit * uUnit)) * 0.35;
        lights = under(lights, tint, core + halo + foot);
    }
    for (int i = 0; i < 3; i++) {
        if (uRay[i].w <= 0.0) continue;
        float d2 = passesLine(ro, rd, uRay[i].xyz, uListener) / (uUnit * uUnit);
        lights = under(lights, uPrimary, (fade(d2 / 0.00012) * 0.8 + fade(d2 / 0.004) * 0.18) * uRay[i].w);
    }
    for (int i = 0; i < 8; i++) {
        if (float(i) >= uMarkCount) break;
        float d = sqrt(passes(ro, rd, uMark[i].xyz, limit)) / uUnit;
        float off = (d - 0.09) / 0.014;
        lights = under(lights, uInk, fade(off * off) * 0.7 + fade(d * d / 0.0006) * 0.7);
    }
    acc = under(acc, lights.rgb / max(lights.a, 1e-4), lights.a);

    // The head.
    if (headT > 0.0 && (exitT < 0.0 || headT < exitT)) {
        float3 n = normalize(ro + rd * headT - uListener);
        float lit = 0.35 + 0.65 * clamp(dot(n, normalize(float3(-0.3, -0.5, 0.8))), 0.0, 1.0);
        float graze = 1.0 - clamp(dot(n, -rd), 0.0, 1.0);
        float rim = graze * graze * graze;
        acc = under(acc, uInk * lit + uPrimary * rim * 0.6, 0.95 * headCover);
    }

    // The room's surfaces: the one seen, and faintly the one looked through.
    for (int pass = 0; pass < 2; pass++) {
        float t = pass == 0 ? exitT : enterT;
        float weight = (pass == 0 ? 1.0 : 0.35) * cover;
        if (t < 0.0) continue;
        float3 p = ro + rd * t;
        float2 h = halfSize(p.z);
        float2 side = abs(p.xy - 0.5 * uRoom.xy) - h;      // under zero inside, per axis
        float onFloor = 1.0 - smoothstep(0.0, 0.03, p.z);
        float onCeiling = smoothstep(uRoom.z - 0.03, uRoom.z, p.z);
        float onWall = 1.0 - max(onFloor, onCeiling);
        float line = 0.006 * t;                             // a line's width, wider with distance
        // Edges: where the floor or the ceiling meets a wall, and a box's corners.
        float toWall = -plan(p);
        float edge = (onFloor + onCeiling) * (1.0 - smoothstep(0.0, line * 1.6, toWall));
        float corner = uCorner < 0.05 ? 1.0 - smoothstep(0.0, line * 1.6, -min(side.x, side.y)) : 0.0;
        edge = max(edge, onWall * max(corner, max(1.0 - smoothstep(0.0, line * 1.6, p.z),
                                                1.0 - smoothstep(0.0, line * 1.6, uRoom.z - p.z))));
        // The floor's grid: a line every metre, and a stronger one every five
        // in a room big enough to have them. Both fade away from the listener,
        // so a hall's floor is a floor and not a mesh.
        float2 seat = p.xy - uListener.xy;
        float near = fade(dot(seat, seat) / (0.35 * dot(uRoom.xy, uRoom.xy)));
        float2 cell = abs(fract(p.xy + 0.5) - 0.5);
        float2 cell5 = abs(fract(p.xy / 5.0 + 0.5) - 0.5) * 5.0;
        float minor = 1.0 - smoothstep(0.0, line, min(cell.x, cell.y));
        float major = max(uRoom.x, uRoom.y) > 8.0 ? 1.0 - smoothstep(0.0, line * 1.3, min(cell5.x, cell5.y)) : 0.0;
        float grid = uGrid * onFloor * max(minor * (0.12 + 0.3 * near), major * 0.75);
        // Light from above, ahead and to the left: a wall facing it is lighter,
        // one turned away darker, and a wall is lighter towards its top, where
        // the glass catches it. The floor is lit round the listener and darker
        // against the walls, where light does not reach.
        float wallLight = 0.0;
        if (onWall > 0.0) {  // which way it faces is four more looks at the plan: not for the floor
            float2 nWall = normalize(float2(
                plan(p + float3(0.02, 0.0, 0.0)) - plan(p - float3(0.02, 0.0, 0.0)),
                plan(p + float3(0.0, 0.02, 0.0)) - plan(p - float3(0.0, 0.02, 0.0))) + 1e-6);
            float facing = 0.5 + 0.5 * dot(nWall, normalize(float2(0.55, 0.8)));
            float upWall = clamp(p.z / uRoom.z, 0.0, 1.0);
            wallLight = onWall * (0.02 + 0.05 * facing + 0.06 * upWall * upWall);
        }
        float tucked = onFloor * (1.0 - smoothstep(0.0, 0.12 * min(uRoom.x, uRoom.y), toWall));
        float floorLight = onFloor * (0.05 + 0.05 * near);
        float fill = floorLight + wallLight + onCeiling * 0.02;
        // The listener's shadow on the floor.
        float2 fromSeat = seat / uUnit;
        float shadow = onFloor * fade(dot(fromSeat, fromSeat) / 0.05);
        acc = under(acc, uInk, (edge * 0.55 + grid * 0.22) * weight);
        // The floor is glossy: what plays above it shows in it.
        if (pass == 0 && onFloor > 0.5) {
            float4 back = mirrored(p, rd);
            acc = under(acc, back.rgb / max(back.a, 1e-4), back.a * uMirror * weight);
        }
        acc = under(acc, uInk, (fill - shadow * 0.04 - tucked * 0.03) * weight);
    }
    return half4(acc);
}
"""

/**
 * The camera: it looks at the room's middle from behind and above, turned
 * [yaw] about it, from as far as it takes for the whole room to be in view.
 * The same numbers go to the shader and place the labels drawn over it.
 */
internal class RoomCamera(yaw: Float, shape: RoomShape, private val width: Float, private val height: Float) {
    val eye = FloatArray(3)
    val right = FloatArray(3)
    val up = FloatArray(3)
    val forward = FloatArray(3)

    init {
        // Forward: towards the wall ahead (y falls) and down by the pitch.
        forward[0] = sin(yaw) * cos(PITCH)
        forward[1] = -cos(yaw) * cos(PITCH)
        forward[2] = -sin(PITCH)
        // Right is level, to the right of forward; the room's x runs left to right.
        val rx = -forward[1]
        val ry = forward[0]
        val rn = sqrt(rx * rx + ry * ry)
        right[0] = rx / rn
        right[1] = ry / rn
        right[2] = 0f
        // Up: forward x right, which has the ceiling's side.
        up[0] = forward[1] * right[2] - forward[2] * right[1]
        up[1] = forward[2] * right[0] - forward[0] * right[2]
        up[2] = forward[0] * right[1] - forward[1] * right[0]
        // Far enough for the ball round the room to fit the narrower way.
        val ball = 0.5f * sqrt(shape.width * shape.width + shape.depth * shape.depth + shape.height * shape.height)
        val aspect = if (height > 0f) width / height else 1f
        val half = atan(0.5f * minOf(1f, aspect) / FOCAL)
        val distance = FIT * ball / sin(half)
        eye[0] = shape.width / 2f - forward[0] * distance
        eye[1] = shape.depth / 2f - forward[1] * distance
        eye[2] = shape.height * LOOK_AT - forward[2] * distance
    }

    /** Where a point of the room (metres) is on the page, or null behind the camera. */
    fun project(x: Float, y: Float, z: Float): Offset? {
        val vx = x - eye[0]
        val vy = y - eye[1]
        val vz = z - eye[2]
        val ahead = vx * forward[0] + vy * forward[1] + vz * forward[2]
        if (ahead <= 0.05f) return null
        val across = vx * right[0] + vy * right[1] + vz * right[2]
        val above = vx * up[0] + vy * up[1] + vz * up[2]
        return Offset(
            width / 2f + FOCAL * across / ahead * height,
            height / 2f - FOCAL * above / ahead * height,
        )
    }

    /** How far the camera is from a point, for choosing the nearer of two edges. */
    fun distanceTo(x: Float, y: Float, z: Float): Float {
        val vx = x - eye[0]
        val vy = y - eye[1]
        val vz = z - eye[2]
        return sqrt(vx * vx + vy * vy + vz * vz)
    }

    companion object {
        const val FOCAL = 1.35f     // focal length over the picture's height
        const val PITCH = 0.42f     // 24 degrees down
        const val LOOK_AT = 0.42f   // of the room's height
        const val FIT = 0.92f       // of the ball: a box's corners are not all at its edge
        const val START_YAW = -0.5f
    }
}

/**
 * What is in the room this frame, as the shader takes it: filled by the page
 * each time it draws, in arrays that are made once.
 */
internal class RoomScene {
    val speakers = FloatArray(MAX_SPEAKERS * 4)      // x, y, z, how hard it is driven (0..1)
    val speakerKinds = FloatArray(MAX_SPEAKERS)      // KIND_EAR, KIND_HEIGHT, KIND_LFE
    val speakerAir = FloatArray(MAX_SPEAKERS)        // that level, averaged: the grain round it
    var speakerCount = 0
    val objects = FloatArray(MAX_OBJECTS * 4)        // x, y, z, level; under zero for a height object
    var objectCount = 0
    var objectGrain = true                           // whether the objects gather grain (the sound is shown)
    val sources = FloatArray(MAX_SOURCES * 4)        // where sound turns on a surface: x, y, z, strength
    var sourceCount = 0
    val rays = FloatArray(MAX_RAYS * 4)              // the loudest speakers: x, y, z, level
    val marks = FloatArray(MAX_MARKS * 4)            // the measurement's loudspeakers
    var markCount = 0

    fun clear() {
        speakerCount = 0
        objectCount = 0
        sourceCount = 0
        markCount = 0
        rays.fill(0f)
    }

    fun addSpeaker(x: Float, y: Float, z: Float, level: Float, air: Float, kind: Float) {
        if (speakerCount == MAX_SPEAKERS) return
        put(speakers, speakerCount, x, y, z, level)
        speakerAir[speakerCount] = air
        speakerKinds[speakerCount++] = kind
    }

    fun addObject(x: Float, y: Float, z: Float, level: Float, high: Boolean) {
        if (objectCount == MAX_OBJECTS) return
        put(objects, objectCount++, x, y, z, if (high) -level.coerceAtLeast(0.01f) else level.coerceAtLeast(0.01f))
    }

    /** A spot where sound turns on a surface; when there are more than the shader takes, the weakest goes. */
    fun addSource(x: Float, y: Float, z: Float, strength: Float) {
        if (strength <= 0f) return
        if (sourceCount < MAX_SOURCES) {
            put(sources, sourceCount++, x, y, z, strength)
            return
        }
        var weakest = 0
        for (i in 1 until MAX_SOURCES) if (sources[i * 4 + 3] < sources[weakest * 4 + 3]) weakest = i
        if (sources[weakest * 4 + 3] < strength) put(sources, weakest, x, y, z, strength)
    }

    /** Whether there is any sound to draw as grain, with [held] of the room's own. */
    fun sounds(held: Float): Boolean {
        if (held > 0.004f || sourceCount > 0 || (objectGrain && objectCount > 0)) return true
        for (i in 0 until speakerCount) if (speakerAir[i] > 0.01f) return true
        return false
    }

    fun setRay(i: Int, x: Float, y: Float, z: Float, level: Float) {
        if (i in 0 until MAX_RAYS) put(rays, i, x, y, z, level)
    }

    fun addMark(x: Float, y: Float, z: Float) {
        if (markCount < MAX_MARKS) put(marks, markCount++, x, y, z, 1f)
    }

    private fun put(into: FloatArray, i: Int, x: Float, y: Float, z: Float, w: Float) {
        into[i * 4] = x
        into[i * 4 + 1] = y
        into[i * 4 + 2] = z
        into[i * 4 + 3] = w
    }

    companion object {
        // The shader's array sizes.
        const val MAX_SPEAKERS = 32
        const val MAX_OBJECTS = 32
        const val MAX_SOURCES = 12
        const val MAX_RAYS = 3
        const val MAX_MARKS = 8
        const val KIND_EAR = 0f
        const val KIND_HEIGHT = 1f
        const val KIND_LFE = 2f
    }
}

/** The shader, with this frame's room, camera and colours set on it. */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal class RoomShader {
    private val shader = RuntimeShader(ROOM_SRC)
    private val paint = Paint().apply { shader = this@RoomShader.shader }

    fun draw(
        canvas: android.graphics.Canvas,
        width: Float,
        height: Float,
        camera: RoomCamera,
        shape: RoomShape,
        scene: RoomScene,
        glow: Float,
        primary: Color,
        tertiary: Color,
        ink: Color,
    ) {
        shader.setFloatUniform("uRes", width, height)
        shader.setFloatUniform("uEye", camera.eye)
        shader.setFloatUniform("uRight", camera.right)
        shader.setFloatUniform("uUp", camera.up)
        shader.setFloatUniform("uFwd", camera.forward)
        shader.setFloatUniform("uFocal", RoomCamera.FOCAL)
        shader.setFloatUniform("uRoom", shape.width, shape.depth, shape.height)
        shader.setFloatUniform("uLean", shape.leanStart)
        shader.setFloatUniform("uCeil", shape.ceilingWidth, shape.ceilingDepth, shape.curve)
        shader.setFloatUniform("uCorner", shape.cornerRadius)
        shader.setFloatUniform("uListener", shape.listenerX, shape.listenerY, shape.earHeight)
        shader.setFloatUniform("uSpk", scene.speakers)
        shader.setFloatUniform("uSpkKind", scene.speakerKinds)
        shader.setFloatUniform("uSpkAir", scene.speakerAir)
        shader.setFloatUniform("uSpkCount", scene.speakerCount.toFloat())
        shader.setFloatUniform("uObj", scene.objects)
        shader.setFloatUniform("uObjCount", scene.objectCount.toFloat())
        shader.setFloatUniform("uMirror", RoomGrain.MIRROR)
        shader.setFloatUniform("uObjGrain", if (scene.objectGrain) RoomGrain.OBJECT else 0f)
        shader.setFloatUniform("uSrc", scene.sources)
        shader.setFloatUniform("uSrcCount", scene.sourceCount.toFloat())
        shader.setFloatUniform("uRay", scene.rays)
        shader.setFloatUniform("uMark", scene.marks)
        shader.setFloatUniform("uMarkCount", scene.markCount.toFloat())
        shader.setFloatUniform("uGrain", RoomGrain.cell(shape), RoomGrain.reach(shape), if (scene.sounds(glow)) RoomGrain.COVER else 0f)
        shader.setFloatUniform("uConvex", if (shape.convex) 1f else 0f)
        shader.setFloatUniform("uCloud", RoomGrain.NEAR, RoomGrain.TURNED, RoomGrain.HELD)
        shader.setFloatUniform("uGlow", glow)
        shader.setFloatUniform("uUnit", RoomGrain.markScale(shape))
        shader.setFloatUniform("uPrimary", primary.red, primary.green, primary.blue)
        shader.setFloatUniform("uTertiary", tertiary.red, tertiary.green, tertiary.blue)
        shader.setFloatUniform("uInk", ink.red, ink.green, ink.blue)
        shader.setFloatUniform("uGrid", 1f)
        canvas.drawRect(0f, 0f, width, height, paint)
    }
}

/**
 * The grain's measures, which follow the room's size: the same picture of a
 * booth and of a hall. The shader and the canvas drawing both take them from
 * here.
 */
internal object RoomGrain {
    /** A grain cell's side, in metres: [ACROSS] of them over the room's longest way. */
    fun cell(shape: RoomShape): Float = maxOf(shape.width, shape.depth, 2f * shape.height) / ACROSS

    /** How far a speaker's cloud reaches at full level, in metres (a speaker driven less reaches less). */
    fun reach(shape: RoomShape): Float = REACH * minOf(shape.width, shape.depth)

    /** How many times their size in the built-in room the marks are drawn, so they are that size on the page. */
    fun markScale(shape: RoomShape): Float = (cell(shape) / cell(RoomShape.BUILT_IN)).coerceAtLeast(1f)

    const val ACROSS = 52f      // cells; the shader's CELLS is the most a ray can cross of them
    const val REACH = 0.2f      // of the room's shorter side
    const val COVER = 1f        // a speck's cover at its middle; it is soft from there out
    const val NEAR = 0.35f      // a speaker's reach as it falls silent, of its reach at full level
    const val TURNED = 0.4f     // the reach of a spot where sound turns, of the same
    const val MIRROR = 0.6f     // how much of what plays above it the floor shows
    const val OBJECT = 0.55f    // the grain round an object at full level, of a speaker's
    const val HELD = 0.22f      // the grain through the whole room when it holds all it can, at Room 100%
}

/**
 * The room's shader on Android 13 and up, or null: on an older phone, or on
 * one whose driver will not take the shader, the room is drawn on the canvas.
 */
@Composable
internal fun rememberRoomShader(): RoomShader? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        remember {
            runCatching { RoomShader() }
                .onFailure { android.util.Log.e("AtmosRoom", "the room shader was refused; drawing on the canvas", it) }
                .getOrNull()
        }
    } else {
        null
    }
