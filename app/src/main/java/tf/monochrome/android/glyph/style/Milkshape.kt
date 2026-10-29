// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.glyph.style

/**
 * MilkShape 3D's ASCII format, as StepMania's characters use it: a model
 * (`model.txt`: meshes, materials, a skeleton) and its dances (`*.bones.txt`:
 * the same skeleton's keyframes, bone by bone). Every vertex hangs from one
 * bone, rigidly, as in StepMania. Read, not run: plain text. Pure Kotlin.
 */
object Milkshape {

    class Mesh(
        val name: String,
        val material: Int,
        /** x, y, z per vertex. */
        val positions: FloatArray,
        /** u, v per vertex, in the texture's 0..1. */
        val uvs: FloatArray,
        /** The bone each vertex hangs from; -1 for none. */
        val bones: IntArray,
        /** Three vertex indices per triangle. */
        val triangles: IntArray,
    ) {
        val vertexCount: Int get() = bones.size
    }

    class Material(val name: String, val texture: String, val alpha: Float)

    /** A keyframe: its time in frames, and a position or Euler rotation (radians). */
    class Key(val time: Float, val x: Float, val y: Float, val z: Float)

    class Bone(
        val name: String,
        val parent: String,
        val position: FloatArray,
        val rotation: FloatArray,
        val positionKeys: List<Key>,
        val rotationKeys: List<Key>,
    )

    class Model(val meshes: List<Mesh>, val materials: List<Material>, val bones: List<Bone>) {
        /** The last keyframe's time: how long one pass of a dance is, in frames. */
        val length: Float
            get() = bones.maxOfOrNull { b -> maxOf(b.positionKeys.lastOrNull()?.time ?: 0f, b.rotationKeys.lastOrNull()?.time ?: 0f) } ?: 0f
    }

    class FormatException(message: String) : IllegalArgumentException(message)

    /** A model or a dance from its text; a dance is a model with bones only. */
    fun parse(text: String): Model {
        val lines = text.lineSequence()
            .map { it.substringBefore("//").trim() }
            .filter { it.isNotEmpty() }
            .toList()
        var i = 0
        fun next(): String = lines.getOrNull(i++) ?: throw FormatException("the file ends early")
        fun numbers(line: String): List<Float> = line.split(WHITESPACE).mapNotNull { it.toFloatOrNull() }
        fun quoted(line: String): String = line.substringAfter('"', "").substringBefore('"')
        fun count(line: String, max: Int): Int =
            (line.substringAfter(':').trim().toIntOrNull() ?: line.trim().toIntOrNull() ?: throw FormatException("not a count: $line"))
                .also { if (it < 0 || it > max) throw FormatException("too many: $line") }

        val meshes = ArrayList<Mesh>()
        val materials = ArrayList<Material>()
        val bones = ArrayList<Bone>()
        while (i < lines.size) {
            val line = next()
            when {
                line.startsWith("Meshes:") -> repeat(count(line, MAX_MESHES)) {
                    val head = next()
                    val name = quoted(head)
                    val material = numbers(head.substringAfterLast('"')).getOrNull(1)?.toInt() ?: -1
                    val vertexCount = count(next(), MAX_VERTICES)
                    val positions = FloatArray(vertexCount * 3)
                    val uvs = FloatArray(vertexCount * 2)
                    val vertexBones = IntArray(vertexCount)
                    for (v in 0 until vertexCount) {
                        val n = numbers(next())
                        if (n.size < 7) throw FormatException("a vertex needs seven numbers")
                        positions[v * 3] = n[1]; positions[v * 3 + 1] = n[2]; positions[v * 3 + 2] = n[3]
                        uvs[v * 2] = n[4]; uvs[v * 2 + 1] = n[5]
                        vertexBones[v] = n[6].toInt()
                    }
                    repeat(count(next(), MAX_VERTICES)) { next() } // normals: flat shading needs none
                    val triangleCount = count(next(), MAX_TRIANGLES)
                    val triangles = IntArray(triangleCount * 3)
                    for (t in 0 until triangleCount) {
                        val n = numbers(next())
                        if (n.size < 4) throw FormatException("a triangle needs its three vertices")
                        for (k in 0..2) {
                            val index = n[1 + k].toInt()
                            if (index !in 0 until vertexCount) throw FormatException("a triangle names a vertex that is not there")
                            triangles[t * 3 + k] = index
                        }
                    }
                    meshes += Mesh(name, material, positions, uvs, vertexBones, triangles)
                }
                line.startsWith("Materials:") -> repeat(count(line, MAX_MATERIALS)) {
                    val name = quoted(next())
                    repeat(4) { next() } // ambient, diffuse, specular, emissive
                    next() // shininess
                    val alpha = numbers(next()).firstOrNull() ?: 1f
                    val texture = quoted(next())
                    next() // alpha map
                    materials += Material(name, texture.replace('\\', '/'), alpha.coerceIn(0f, 1f))
                }
                line.startsWith("Bones:") -> repeat(count(line, MAX_BONES)) {
                    val name = quoted(next())
                    val parent = quoted(next())
                    val n = numbers(next())
                    if (n.size < 7) throw FormatException("a bone needs its rest position and rotation")
                    val position = floatArrayOf(n[1], n[2], n[3])
                    val rotation = floatArrayOf(n[4], n[5], n[6])
                    fun keys(): List<Key> = List(count(next(), MAX_KEYS)) {
                        val k = numbers(next())
                        if (k.size < 4) throw FormatException("a key needs a time and three numbers")
                        Key(k[0], k[1], k[2], k[3])
                    }.sortedBy { it.time }
                    val positionKeys = keys()
                    val rotationKeys = keys()
                    bones += Bone(name, parent, position, rotation, positionKeys, rotationKeys)
                }
                else -> Unit // Frames:, Frame: and anything newer
            }
        }
        return Model(meshes, materials, bones)
    }

    private val WHITESPACE = Regex("\\s+")
    private const val MAX_MESHES = 512
    private const val MAX_VERTICES = 200_000
    private const val MAX_TRIANGLES = 200_000
    private const val MAX_MATERIALS = 512
    private const val MAX_BONES = 256
    private const val MAX_KEYS = 100_000
}
