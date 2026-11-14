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
 * The ray is walked through the room in even steps. Along it the waves from
 * the loudest speakers and from their reflections ([RoomScene.sources]) add
 * up, and where they are in step the air glows: faintly through the whole
 * room, and sharply on a sheet at ear height, where they are heard. Where the
 * ray leaves the room is the wall that is seen: its edges, the floor's metre
 * grid, the light each speaker throws on the surface near it, the glow of
 * what the room still holds; the wall it came in through is drawn faintly, so
 * the room is looked into and not at. Speakers, objects, the measurement's
 * loudspeakers and the rays to the listener are lights the ray passes; the
 * listener is a head that hides what is behind it.
 *
 * The room is a distance function ([RoomShape]'s geometry again, in the
 * shader's `room`): a floor plan with rounded corners, drawn in above ear
 * height as the walls lean and curve. So any room the app can describe is
 * drawn by the same few lines, and a new shape is a change to that function.
 *
 * Colours are the theme's; everything is laid front to back as tinted cover
 * (`under`), so it reads on a light theme as on a dark one.
 */
private const val ROOM_SRC = """
uniform float2 uRes;       // the picture, in pixels
uniform float uTime;       // seconds, for the grain only
uniform float3 uEye;       // the camera, in metres
uniform float3 uRight;     // its basis: right, up, forward
uniform float3 uUp;
uniform float3 uFwd;
uniform float uFocal;      // focal length over the picture's height
uniform float3 uRoom;      // width, depth, height
uniform float uEar;        // ear height: where the waves' sheet is
uniform float uLean;       // the height the walls lean from
uniform float3 uCeil;      // ceiling width and depth as shares of the floor's; the walls' curve
uniform float uCorner;     // corner radius
uniform float3 uListener;
uniform float4 uSpk[32];   // each speaker: where, and how hard it is driven (0..1)
uniform float uSpkKind[32];  // 0 ear level, 1 a height speaker, 2 the LFE
uniform float uSpkCount;
uniform float4 uObj[32];   // each object: where, and its level; under zero, a height object
uniform float uObjCount;
uniform float4 uSrc[12];   // the wave field's sources: where, and how strong
uniform float uSrcCount;
uniform float4 uRay[3];    // the loudest speakers, for their ray to the listener
uniform float4 uMark[8];   // the loudspeakers the room was measured with
uniform float uMarkCount;
uniform float2 uWave;      // the field's wavelength, and its phase
uniform float uField;      // the field at its brightest
uniform float uGlow;       // what the room still holds, times the Room amount: the walls' glow
uniform float3 uPrimary;   // the theme: speakers and waves,
uniform float3 uTertiary;  // heights,
uniform float3 uInk;       // the room's lines and the listener,
uniform float uGrid;       // and whether the floor has a metre grid

const int STEPS = 36;

float hash(float2 p) {
    float3 q = fract(float3(p.xyx) * 0.1031);
    q += dot(q, q.yzx + 33.33);
    return fract((q.x + q.y) * q.z);
}

// How far in the walls have leaned at height z: 0 up to where they start, 1 at the ceiling.
float rise(float z) {
    return pow(clamp((z - uLean) / max(uRoom.z - uLean, 0.001), 0.0, 1.0), uCeil.z);
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

// The waves at p, -1..1: every source's wave as it is there, weaker the
// further it has come, over the most they could add up to.
float waves(float3 p) {
    float sum = 0.0;
    float most = 0.0;
    for (int i = 0; i < 12; i++) {
        if (float(i) >= uSrcCount) break;
        float tFar = length(p - uSrc[i].xyz);
        float reach = uSrc[i].w / (0.6 + tFar);
        sum += reach * cos(6.2831853 * tFar / uWave.x - uWave.y);
        most += reach;
    }
    return most > 0.0 ? sum / most : 0.0;
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

    // The listener: a head, which hides what is behind it.
    float headT = -1.0;
    {
        float3 to = uListener - ro;
        float along = dot(to, rd);
        float d2 = dot(to, to) - along * along;
        float r = 0.12;
        if (along > 0.0 && d2 < r * r) headT = along - sqrt(r * r - d2);
    }

    float exitT = -1.0;   // where the ray last leaves the room: the wall that is seen
    float enterT = -1.0;  // where it first comes in: the wall that is looked through
    if (tFar > tNear) {
        float stop = headT > 0.0 ? min(tFar, headT) : tFar;
        float dt = (tFar - tNear) / float(STEPS);
        // The walls are looked for at even steps, so the room's outline holds
        // still; the waves are sampled a grain's worth off them, which turns
        // the steps' banding into a fine noise.
        float grain = hash(fragCoord + fract(uTime) * 61.0) - 0.5;
        float lastIn = -1.0;
        float firstIn = -1.0;
        for (int i = 0; i < STEPS; i++) {
            float t = tNear + (float(i) + 0.5) * dt;
            if (room(ro + rd * t) < 0.0) {
                if (firstIn < 0.0) firstIn = t;
                lastIn = t;
                float ts = t + grain * dt;
                if (ts < stop && uSrcCount > 0.0) {
                    float crest = clamp(waves(ro + rd * ts) * 2.6, 0.0, 1.0);
                    float dense = crest * crest * (3.0 - 2.0 * crest);
                    acc = under(acc, uPrimary, dense * uField * dt * 0.30);
                }
            }
        }
        // The waves again where they are heard: a sheet at ear height, on
        // which their crests are sharp enough to count.
        if (uSrcCount > 0.0 && abs(rd.z) > 1e-4) {
            float ts = (uEar - ro.z) / rd.z;
            float3 p = ro + rd * ts;
            if (ts > 0.0 && ts < stop && room(p) < 0.0) {
                float w = waves(p) * 2.6;
                float crest = smoothstep(0.15, 0.75, w);
                // Fainter near the walls, so the sheet has no edge.
                float inside = smoothstep(0.0, 0.5, -plan(p));
                acc = under(acc, uPrimary, crest * uField * 0.9 * inside);
            }
        }
        if (lastIn > 0.0) {
            // The wall itself, between the last step inside and the next one.
            float a = lastIn;
            float b = min(lastIn + dt, tFar + 0.01);
            for (int i = 0; i < 6; i++) {
                float m = 0.5 * (a + b);
                if (room(ro + rd * m) < 0.0) a = m; else b = m;
            }
            exitT = 0.5 * (a + b);
            a = max(firstIn - dt, tNear - 0.01);
            b = firstIn;
            for (int i = 0; i < 6; i++) {
                float m = 0.5 * (a + b);
                if (room(ro + rd * m) < 0.0) b = m; else a = m;
            }
            enterT = 0.5 * (a + b);
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
        float size = kind == 2.0 ? 0.085 : 0.062;
        float d = sqrt(d2);
        // A ring that is always there; lit, a core and a halo as wide as it is loud.
        float off = (d - size) / 0.012;
        float wide = size * (1.8 + 3.2 * level);
        float ring = exp(-off * off);
        float core = exp(-d2 / (size * size * 0.55)) * level;
        float halo = exp(-d2 / (wide * wide)) * level * 0.55;
        lights = under(lights, tint, ring * 0.6 + core + halo);
    }
    for (int i = 0; i < 32; i++) {
        if (float(i) >= uObjCount) break;
        float level = abs(uObj[i].w);
        float3 tint = uObj[i].w < 0.0 ? uTertiary : uInk;
        float size = 0.05 + 0.06 * level;
        float d2 = passes(ro, rd, uObj[i].xyz, limit);
        float core = exp(-d2 / (size * size)) * (0.45 + 0.55 * level);
        float halo = exp(-d2 / (size * size * 9.0)) * level * 0.35;
        // Its place on the floor, so its height can be read.
        float foot = exp(-passes(ro, rd, float3(uObj[i].xy, 0.01), limit) / 0.0012) * 0.35;
        lights = under(lights, tint, core + halo + foot);
    }
    for (int i = 0; i < 3; i++) {
        if (uRay[i].w <= 0.0) continue;
        float d2 = passesLine(ro, rd, uRay[i].xyz, uListener);
        lights = under(lights, uPrimary, (exp(-d2 / 0.00012) * 0.8 + exp(-d2 / 0.004) * 0.18) * uRay[i].w);
    }
    for (int i = 0; i < 8; i++) {
        if (float(i) >= uMarkCount) break;
        float d = sqrt(passes(ro, rd, uMark[i].xyz, limit));
        float off = (d - 0.09) / 0.014;
        lights = under(lights, uInk, exp(-off * off) * 0.7 + exp(-d * d / 0.0006) * 0.7);
    }
    acc = under(acc, lights.rgb / max(lights.a, 1e-4), lights.a);

    // The head.
    if (headT > 0.0 && (exitT < 0.0 || headT < exitT)) {
        float3 n = normalize(ro + rd * headT - uListener);
        float lit = 0.35 + 0.65 * clamp(dot(n, normalize(float3(-0.3, -0.5, 0.8))), 0.0, 1.0);
        float graze = 1.0 - clamp(dot(n, -rd), 0.0, 1.0);
        float rim = graze * graze * graze;
        acc = under(acc, uInk * lit + uPrimary * rim * 0.6, 0.95);
    }

    // The room's surfaces: the one seen, and faintly the one looked through.
    for (int pass = 0; pass < 2; pass++) {
        float t = pass == 0 ? exitT : enterT;
        float weight = pass == 0 ? 1.0 : 0.35;
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
        // The floor's metre grid.
        float2 cell = abs(fract(p.xy + 0.5) - 0.5);
        float grid = uGrid * onFloor * (1.0 - smoothstep(0.0, line, min(cell.x, cell.y))) * 0.5;
        // The speakers' light on the surface near them.
        float pool = 0.0;
        float3 poolTint = float3(0.0);
        for (int i = 0; i < 32; i++) {
            if (float(i) >= uSpkCount) break;
            float3 to = uSpk[i].xyz - p;
            float fall = uSpk[i].w / (1.0 + 5.0 * dot(to, to));
            pool += fall;
            poolTint += (uSpkKind[i] == 1.0 ? uTertiary : uPrimary) * fall;
        }
        float fill = onFloor * 0.07 + onWall * 0.035 + onCeiling * 0.02;
        // The listener's shadow on the floor.
        float shadow = onFloor * exp(-dot(p.xy - uListener.xy, p.xy - uListener.xy) / 0.05);
        acc = under(acc, uInk, (edge * 0.55 + grid * 0.22) * weight);
        acc = under(acc, poolTint / max(pool, 1e-4), clamp(pool * 0.55, 0.0, 0.6) * weight);
        acc = under(acc, uPrimary, onWall * uGlow * 0.22 * weight);
        acc = under(acc, uInk, (fill - shadow * 0.04) * weight);
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
    var speakerCount = 0
    val objects = FloatArray(MAX_OBJECTS * 4)        // x, y, z, level; under zero for a height object
    var objectCount = 0
    val sources = FloatArray(MAX_SOURCES * 4)        // the wave field's: x, y, z, strength
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

    fun addSpeaker(x: Float, y: Float, z: Float, level: Float, kind: Float) {
        if (speakerCount == MAX_SPEAKERS) return
        put(speakers, speakerCount, x, y, z, level)
        speakerKinds[speakerCount++] = kind
    }

    fun addObject(x: Float, y: Float, z: Float, level: Float, high: Boolean) {
        if (objectCount == MAX_OBJECTS) return
        put(objects, objectCount++, x, y, z, if (high) -level.coerceAtLeast(0.01f) else level.coerceAtLeast(0.01f))
    }

    /** A wave source; when there are more than the shader takes, the weakest goes. */
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

    fun setRay(i: Int, x: Float, y: Float, z: Float, level: Float) {
        if (i in 0 until MAX_RAYS) put(rays, i, x, y, z, level)
    }

    fun addMark(x: Float, y: Float, z: Float) {
        if (markCount < MAX_MARKS) put(marks, markCount++, x, y, z, 1f)
    }

    /** The strongest wave source, 0..1: how bright the field is. */
    fun strongest(): Float {
        var top = 0f
        for (i in 0 until sourceCount) top = maxOf(top, sources[i * 4 + 3])
        return top.coerceIn(0f, 1f)
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
        seconds: Float,
        phase: Float,
        field: Float,
        glow: Float,
        primary: Color,
        tertiary: Color,
        ink: Color,
    ) {
        shader.setFloatUniform("uRes", width, height)
        shader.setFloatUniform("uTime", seconds)
        shader.setFloatUniform("uEye", camera.eye)
        shader.setFloatUniform("uRight", camera.right)
        shader.setFloatUniform("uUp", camera.up)
        shader.setFloatUniform("uFwd", camera.forward)
        shader.setFloatUniform("uFocal", RoomCamera.FOCAL)
        shader.setFloatUniform("uRoom", shape.width, shape.depth, shape.height)
        shader.setFloatUniform("uEar", shape.earHeight)
        shader.setFloatUniform("uLean", shape.leanStart)
        shader.setFloatUniform("uCeil", shape.ceilingWidth, shape.ceilingDepth, shape.curve)
        shader.setFloatUniform("uCorner", shape.cornerRadius)
        shader.setFloatUniform("uListener", shape.listenerX, shape.listenerY, shape.earHeight)
        shader.setFloatUniform("uSpk", scene.speakers)
        shader.setFloatUniform("uSpkKind", scene.speakerKinds)
        shader.setFloatUniform("uSpkCount", scene.speakerCount.toFloat())
        shader.setFloatUniform("uObj", scene.objects)
        shader.setFloatUniform("uObjCount", scene.objectCount.toFloat())
        shader.setFloatUniform("uSrc", scene.sources)
        shader.setFloatUniform("uSrcCount", scene.sourceCount.toFloat())
        shader.setFloatUniform("uRay", scene.rays)
        shader.setFloatUniform("uMark", scene.marks)
        shader.setFloatUniform("uMarkCount", scene.markCount.toFloat())
        shader.setFloatUniform("uWave", WAVELENGTH, phase)
        shader.setFloatUniform("uField", field)
        shader.setFloatUniform("uGlow", glow)
        shader.setFloatUniform("uPrimary", primary.red, primary.green, primary.blue)
        shader.setFloatUniform("uTertiary", tertiary.red, tertiary.green, tertiary.blue)
        shader.setFloatUniform("uInk", ink.red, ink.green, ink.blue)
        shader.setFloatUniform("uGrid", 1f)
        canvas.drawRect(0f, 0f, width, height, paint)
    }

    companion object {
        /** Metres between the wave field's fronts, as it is drawn. */
        const val WAVELENGTH = 1.3f
    }
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
