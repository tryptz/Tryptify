// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.glyph.style

import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A StepMania character posed by a dance: MilkShape's rigid skinning, as
 * StepMania does it. Each bone's rest transform is its position then its
 * rotation, under its parent's; a dance's keys add to the rest, by bone name,
 * so one set of dances fits every character on the same skeleton. A vertex
 * moves with its bone: from the rest pose, undone, into the danced one.
 *
 * Matrices are 3×4, row-major, for column vectors. Rotations are MilkShape's
 * Euler angles, z·y·x. Keys between frames are blended, rotations along the
 * shorter arc. Pure Kotlin; [pose] allocates nothing once warm.
 */
class CharacterPose(private val model: Milkshape.Model) {

    private val boneCount = model.bones.size
    private val parents = IntArray(boneCount) { index ->
        val parent = model.bones[index].parent
        if (parent.isEmpty()) -1 else model.bones.indexOfFirst { it.name.equals(parent, ignoreCase = true) }
    }
    /** Bones parent first, so each is posed after the one it hangs from. */
    private val order: IntArray = run {
        val done = BooleanArray(boneCount)
        val out = ArrayList<Int>(boneCount)
        fun visit(b: Int, depth: Int) {
            if (done[b] || depth > boneCount) return
            parents[b].takeIf { it >= 0 }?.let { visit(it, depth + 1) }
            if (!done[b]) { done[b] = true; out += b }
        }
        for (b in 0 until boneCount) visit(b, 0)
        out.toIntArray()
    }

    private val restLocal = Array(boneCount) { rigid(model.bones[it].rotation[0], model.bones[it].rotation[1], model.bones[it].rotation[2], model.bones[it].position) }
    private val restAbsolute = Array(boneCount) { FloatArray(12) }
    private val restInverse = Array(boneCount) { FloatArray(12) }
    private val posed = Array(boneCount) { FloatArray(12) }
    private val skin = Array(boneCount) { FloatArray(12) }
    private val scratch = FloatArray(12)
    private val keyMatrix = FloatArray(12)
    private val keyPos = FloatArray(3)
    private val keyRot = FloatArray(4)

    init {
        for (b in order) {
            val p = parents[b]
            if (p >= 0) multiply(restAbsolute[p], restLocal[b], restAbsolute[b]) else restLocal[b].copyInto(restAbsolute[b])
            invert(restAbsolute[b], restInverse[b])
        }
    }

    /** Every vertex, every mesh in turn, x y z. */
    val vertexCount: Int = model.meshes.sumOf { it.vertexCount }

    /** A dance matched to this skeleton by bone name: for each bone, the dance's bone, or null to rest. */
    fun bind(dance: Milkshape.Model): Array<Milkshape.Bone?> =
        Array(boneCount) { b -> dance.bones.firstOrNull { it.name.equals(model.bones[b].name, ignoreCase = true) } }

    /**
     * The character at [frame] of [dance] (from [bind]); null rests. Writes
     * every vertex's posed position into [out] (3 × [vertexCount]).
     */
    fun pose(dance: Array<Milkshape.Bone?>?, frame: Float, out: FloatArray) {
        for (b in order) {
            val bone = dance?.getOrNull(b)
            val local = if (bone == null) {
                restLocal[b]
            } else {
                // The rest, then the dance's key on top of it.
                sample(bone.positionKeys, frame, keyPos)
                sampleRotation(bone.rotationKeys, frame, keyRot)
                rigidFromQuaternion(keyRot, keyPos, keyMatrix)
                multiply(restLocal[b], keyMatrix, scratch)
                scratch
            }
            val p = parents[b]
            if (p >= 0) multiply(posed[p], local, posed[b]) else local.copyInto(posed[b])
            multiply(posed[b], restInverse[b], skin[b])
        }
        var o = 0
        for (mesh in model.meshes) {
            val positions = mesh.positions
            for (v in 0 until mesh.vertexCount) {
                val x = positions[v * 3]; val y = positions[v * 3 + 1]; val z = positions[v * 3 + 2]
                val b = mesh.bones[v]
                if (b in 0 until boneCount) {
                    val m = skin[b]
                    out[o] = m[0] * x + m[1] * y + m[2] * z + m[3]
                    out[o + 1] = m[4] * x + m[5] * y + m[6] * z + m[7]
                    out[o + 2] = m[8] * x + m[9] * y + m[10] * z + m[11]
                } else {
                    out[o] = x; out[o + 1] = y; out[o + 2] = z
                }
                o += 3
            }
        }
    }

    // ── keys ────────────────────────────────────────────────────────────

    private fun sample(keys: List<Milkshape.Key>, frame: Float, out: FloatArray) {
        if (keys.isEmpty()) { out.fill(0f); return }
        val (a, b, t) = span(keys, frame)
        out[0] = a.x + (b.x - a.x) * t
        out[1] = a.y + (b.y - a.y) * t
        out[2] = a.z + (b.z - a.z) * t
    }

    private val qa = FloatArray(4)
    private val qb = FloatArray(4)

    private fun sampleRotation(keys: List<Milkshape.Key>, frame: Float, out: FloatArray) {
        if (keys.isEmpty()) { out[0] = 0f; out[1] = 0f; out[2] = 0f; out[3] = 1f; return }
        val (a, b, t) = span(keys, frame)
        quaternion(a.x, a.y, a.z, qa)
        quaternion(b.x, b.y, b.z, qb)
        slerp(qa, qb, t, out)
    }

    private fun span(keys: List<Milkshape.Key>, frame: Float): Triple<Milkshape.Key, Milkshape.Key, Float> {
        if (frame <= keys.first().time) return Triple(keys.first(), keys.first(), 0f)
        if (frame >= keys.last().time) return Triple(keys.last(), keys.last(), 0f)
        var lo = 0
        var hi = keys.size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) ushr 1
            if (keys[mid].time <= frame) lo = mid else hi = mid
        }
        val a = keys[lo]
        val b = keys[hi]
        val t = if (b.time > a.time) (frame - a.time) / (b.time - a.time) else 0f
        return Triple(a, b, t)
    }

    companion object {
        /** MilkShape's rotation, z·y·x, then the translation. */
        fun rigid(x: Float, y: Float, z: Float, position: FloatArray): FloatArray {
            val q = FloatArray(4)
            quaternion(x, y, z, q)
            val m = FloatArray(12)
            rigidFromQuaternion(q, position, m)
            return m
        }

        /** Euler x, y, z (radians, applied x first) as a quaternion w last. */
        fun quaternion(x: Float, y: Float, z: Float, out: FloatArray) {
            val cx = cos(x / 2); val sx = sin(x / 2)
            val cy = cos(y / 2); val sy = sin(y / 2)
            val cz = cos(z / 2); val sz = sin(z / 2)
            // q = qz · qy · qx
            out[0] = sx * cy * cz - cx * sy * sz
            out[1] = cx * sy * cz + sx * cy * sz
            out[2] = cx * cy * sz - sx * sy * cz
            out[3] = cx * cy * cz + sx * sy * sz
        }

        fun rigidFromQuaternion(q: FloatArray, t: FloatArray, out: FloatArray) {
            val x = q[0]; val y = q[1]; val z = q[2]; val w = q[3]
            out[0] = 1 - 2 * (y * y + z * z); out[1] = 2 * (x * y - z * w); out[2] = 2 * (x * z + y * w); out[3] = t[0]
            out[4] = 2 * (x * y + z * w); out[5] = 1 - 2 * (x * x + z * z); out[6] = 2 * (y * z - x * w); out[7] = t[1]
            out[8] = 2 * (x * z - y * w); out[9] = 2 * (y * z + x * w); out[10] = 1 - 2 * (x * x + y * y); out[11] = t[2]
        }

        fun slerp(a: FloatArray, b: FloatArray, t: Float, out: FloatArray) {
            var dot = a[0] * b[0] + a[1] * b[1] + a[2] * b[2] + a[3] * b[3]
            val sign = if (dot < 0) -1f else 1f
            dot *= sign
            val (wa, wb) = if (dot > 0.9995f) {
                (1 - t) to t
            } else {
                val angle = acos(dot.coerceIn(-1f, 1f))
                val s = sin(angle)
                (sin((1 - t) * angle) / s) to (sin(t * angle) / s)
            }
            for (i in 0..3) out[i] = wa * a[i] + wb * sign * b[i]
            val n = sqrt(out[0] * out[0] + out[1] * out[1] + out[2] * out[2] + out[3] * out[3]).takeIf { it > 0f } ?: 1f
            for (i in 0..3) out[i] /= n
        }

        /** out = a · b, both 3×4 rigid. [out] may not be [a] or [b]. */
        fun multiply(a: FloatArray, b: FloatArray, out: FloatArray) {
            for (r in 0..2) {
                val a0 = a[r * 4]; val a1 = a[r * 4 + 1]; val a2 = a[r * 4 + 2]
                out[r * 4] = a0 * b[0] + a1 * b[4] + a2 * b[8]
                out[r * 4 + 1] = a0 * b[1] + a1 * b[5] + a2 * b[9]
                out[r * 4 + 2] = a0 * b[2] + a1 * b[6] + a2 * b[10]
                out[r * 4 + 3] = a0 * b[3] + a1 * b[7] + a2 * b[11] + a[r * 4 + 3]
            }
        }

        /** The inverse of a rigid transform: the rotation transposed, the translation undone. */
        fun invert(m: FloatArray, out: FloatArray) {
            out[0] = m[0]; out[1] = m[4]; out[2] = m[8]
            out[4] = m[1]; out[5] = m[5]; out[6] = m[9]
            out[8] = m[2]; out[9] = m[6]; out[10] = m[10]
            out[3] = -(out[0] * m[3] + out[1] * m[7] + out[2] * m[11])
            out[7] = -(out[4] * m[3] + out[5] * m[7] + out[6] * m[11])
            out[11] = -(out[8] * m[3] + out[9] * m[7] + out[10] * m[11])
        }
    }
}
