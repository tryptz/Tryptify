// SPDX-License-Identifier: GPL-3.0-or-later

package tf.monochrome.android.audio.sampler.stems

/**
 * Models Tryptify knows how to drive.
 *
 * A separator file on its own is not enough to run it. Three things have to be
 * known and cannot be guessed from the bytes: the segment length the weights
 * were trained on, the sample rate they expect, and — the one that silently
 * ruins everything — **which output channel is which stem**.
 *
 * That last one is not hypothetical. HTDemucs emits its stems in the order
 * `drums, bass, other, vocals`, which is not alphabetical, not the order the
 * [Stem] enum declares, and not the order anyone would guess. Get it wrong and
 * separation still "works": every file is produced, every level looks right,
 * and the vocal stem contains the drums. Nothing downstream can detect that,
 * which is exactly why the order is written down here per model rather than
 * inferred anywhere.
 *
 * A file that matches nothing in this list is refused rather than imported on
 * a guess. Refusing is recoverable — the user finds the right file. Importing
 * a model we cannot drive produces mislabelled stems and looks like the app is
 * broken.
 */
data class KnownModel(
    val id: String,
    val name: String,
    val fileName: String,
    val sizeBytes: Long,
    /** The published SHA-256. Used to recognise a file, never to gate it. */
    val sha256: String,
    /** Channel order the model emits, which is not the enum's order. */
    val outputOrder: List<Stem>,
    val segmentFrames: Int,
    val modelSampleRate: Int,
    val inputTensor: String,
    val outputTensor: String,
    val sourceUrl: String,
    val license: ModelLicense,
    val sourceModel: String,
    val note: String,
) {
    val stems: Set<Stem> get() = outputOrder.toSet()

    /** Turns a recognised file into something the installer can record. */
    fun toStemModel(url: String, sha: String): StemModel = StemModel(
        id = id,
        name = name,
        version = "1.0.0",
        format = ModelFormat.ONNX,
        backend = BackendKind.CPU,
        url = url,
        sha256 = sha,
        sizeBytes = sizeBytes,
        license = license,
        sourceModel = sourceModel,
        attribution = null,
        stems = stems,
        outputOrder = outputOrder,
        modelSampleRate = modelSampleRate,
        segmentFrames = segmentFrames,
        minimumAndroid = 26,
        abi = "arm64-v8a",
    )
}

object KnownModels {

    /** Demucs' own source order, shared by every variant in the family. */
    private val DEMUCS_4 = listOf(Stem.DRUMS, Stem.BASS, Stem.OTHER, Stem.VOCALS)
    private val DEMUCS_6 = DEMUCS_4 + listOf(Stem.GUITAR, Stem.PIANO)

    /**
     * The HTDemucs ONNX exports published by StemSplitio, which are the first
     * parity-verified ones on the Hub and the only off-the-shelf six-stem
     * export. Both variants of each model are listed because the fp16-weight
     * files are half the download for the same runtime cost, which on a phone
     * is the difference between a reasonable download and an unreasonable one.
     *
     * Sizes were read from the repository listing. The hashes are the files'
     * Git LFS object ids, which are SHA-256 by definition — but see
     * [recognise]: they are used to identify a file, never to reject one, so a
     * stale hash degrades to "unrecognised" rather than to "refuses a perfectly
     * good model".
     */
    val ALL: List<KnownModel> = listOf(
        KnownModel(
            id = "htdemucs-6s",
            name = "HTDemucs 6-stem",
            fileName = "htdemucs_6s_fp16weights.onnx",
            sizeBytes = 136_428_532L,
            sha256 = "7ce55792e2231c93fbf92de95f5fd5b3a5e6c89f7db690dfd693e8f1dce56869",
            outputOrder = DEMUCS_6,
            segmentFrames = 343_980,
            modelSampleRate = 44_100,
            inputTensor = "mix",
            outputTensor = "stems",
            sourceUrl = "https://huggingface.co/StemSplitio/htdemucs-6s-onnx",
            license = ModelLicense.MIT,
            sourceModel = "htdemucs_6s",
            note = "Adds guitar and piano. Its `other` holds less than the " +
                "4-stem model's, because guitar and piano were taken out of it.",
        ),
        KnownModel(
            id = "htdemucs-6s-fp32",
            name = "HTDemucs 6-stem (fp32)",
            fileName = "htdemucs_6s.onnx",
            sizeBytes = 258_159_781L,
            sha256 = "48f8e84945579f8ab340e083339e9221e03785dbe733a52c388200b6d3ca779a",
            outputOrder = DEMUCS_6,
            segmentFrames = 343_980,
            modelSampleRate = 44_100,
            inputTensor = "mix",
            outputTensor = "stems",
            sourceUrl = "https://huggingface.co/StemSplitio/htdemucs-6s-onnx",
            license = ModelLicense.MIT,
            sourceModel = "htdemucs_6s",
            note = "Same weights as the fp16 variant at twice the download.",
        ),
        KnownModel(
            id = "htdemucs",
            name = "HTDemucs 4-stem",
            fileName = "htdemucs_fp16weights.onnx",
            sizeBytes = 165_612_636L,
            sha256 = "d05c269d0178d2a72ad484b10b11dd370193fc923201c3b27a99f848745db70a",
            outputOrder = DEMUCS_4,
            segmentFrames = 343_980,
            modelSampleRate = 44_100,
            inputTensor = "mix",
            outputTensor = "stems",
            sourceUrl = "https://huggingface.co/StemSplitio/htdemucs-onnx",
            license = ModelLicense.MIT,
            sourceModel = "htdemucs",
            note = "Stronger on drums and vocals than the 6-stem model.",
        ),
        KnownModel(
            id = "htdemucs-fp32",
            name = "HTDemucs 4-stem (fp32)",
            fileName = "htdemucs.onnx",
            sizeBytes = 316_446_953L,
            sha256 = "68d0bf16428ef66e692cdff8a9ccf28f1ef3f69440d57e58605a4cc55fcc5e74",
            outputOrder = DEMUCS_4,
            segmentFrames = 343_980,
            modelSampleRate = 44_100,
            inputTensor = "mix",
            outputTensor = "stems",
            sourceUrl = "https://huggingface.co/StemSplitio/htdemucs-onnx",
            license = ModelLicense.MIT,
            sourceModel = "htdemucs",
            note = "Same weights as the fp16 variant at twice the download.",
        ),
    )

    /**
     * Identifies an imported file.
     *
     * Hash first, since that is exact. Size second, because a published hash
     * can go stale — a re-export changes the bytes while the model stays the
     * one we know how to drive — and refusing a good file over a hash we
     * recorded months ago would be worse than accepting it. Size alone is a
     * weak signature, but the failure it admits is "the user imported some
     * other file that happens to be exactly 136,428,532 bytes", which is not a
     * realistic accident.
     *
     * Returns null when nothing matches, and null means refuse rather than
     * guess: without the output order the stems come out mislabelled and
     * nothing downstream can tell.
     */
    fun recognise(sha256: String, sizeBytes: Long): Match? {
        ALL.firstOrNull { it.sha256.equals(sha256, ignoreCase = true) }
            ?.let { return Match(it, exact = true) }
        ALL.firstOrNull { it.sizeBytes == sizeBytes }
            ?.let { return Match(it, exact = false) }
        return null
    }

    /** [exact] false means the size matched but the hash did not. */
    data class Match(val model: KnownModel, val exact: Boolean)

    fun byId(id: String): KnownModel? = ALL.firstOrNull { it.id == id }

    /** For the "supported files" list when an import is refused. */
    fun supportedFileNames(): List<String> = ALL.map { it.fileName }
}
