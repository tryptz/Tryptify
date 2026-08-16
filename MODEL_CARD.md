# Tryptify Stem AI — Model Card

Status: **no neural weights ship with Tryptify today.** This card records the
model decision, the evidence behind it, and the reason the download slot is
currently empty. It is written to be updated when weights land, not rewritten.

---

## The decision

| | |
|---|---|
| **Shipping backend today** | `DspStemSeparator` — HPSS + centre extraction, no model, no download |
| **Chosen neural architecture** | Mel-Band RoFormer (vocals) + residual, then a 4-stem RoFormer |
| **Reference fallback** | HTDemucs (base, single model — not the FT bag) |
| **First execution target** | CPU (ONNX Runtime + XNNPACK) |
| **NPU (Qualcomm QNN)** | R&D spike behind a feature flag, not a committed deliverable |
| **Blocking issue** | Weights licensing, not inference |

### Why CPU first and not the NPU

The brief asks for Snapdragon NPU acceleration, and the honest finding is that
**no one has publicly run any 4-stem music separator on a Hexagon NPU.** The
closest published work on Qualcomm AI Hub is a speech denoiser. Whisper runs on
the HTP only after Qualcomm replaced multi-head attention with single-head
attention and linear layers with 1×1 convolutions.

Four independent risks stack on that path:

1. **No precedent** for this model class on HTP.
2. **STFT/iSTFT run off-NPU.** Native STFT arrived in the QNN SDK only
   recently; iSTFT is unconfirmed. Standard practice is CPU-side transforms
   with only the mask network on the NPU.
3. **Operator support.** RoPE and band-split operations need per-op HTP
   verification, and QNN has no dynamic shapes, loops, or ifs. An unsupported
   op silently costs a CPU round trip.
4. **Quantization fights priority #1.** The HTP is an integer device. Published
   results on a neural speech separator report that full INT8 with
   quantization-aware training still "produces a noticeable performance
   degradation"; the fix was keeping I/O in higher precision and using INT16
   activations for sensitive blocks. W8A16 + QAT is the realistic target, and
   it is a quality risk on the axis the brief ranks first.

The stated priority order is **audio quality > spatial fidelity > NPU
performance > model size**. The NPU is third of four and carries all the risk,
so the product is the CPU tier and the NPU is an experiment measured against
it. Go/no-go thresholds are in [Appendix A](#appendix-a--npu-gono-go).

### Why the model slot is empty

**Every high-quality open 4-stem checkpoint has a non-commercial cloud on its
weights.** Code licence and weights licence are separate, and it is the weights
that fail.

| Model | Code | Weights | Redistributable |
|---|---|---|---|
| Demucs / HTDemucs (Meta) | MIT | Research-only | **No** — maintainer: "The model weights are not covered by the MIT license, and are provided only for scientific purposes." |
| BS-RoFormer (lucidrains) | MIT | Per checkpoint | Code clean; the strong 4-stem checkpoints are MUSDB18-HQ trained |
| SCNet | MIT | Per checkpoint | Same |
| ZFTurbo MSST | MIT | Per checkpoint, often ambiguous | Same |
| MDX-Net / UVR team | MIT | MIT **with required credit** | Yes, with attribution — verify each checkpoint's training data |
| **Mel-Band RoFormer vocals (KimberleyJSN)** | MIT | **MIT** | **Yes** — vocals only, not 4-stem |
| Open-Unmix UMX / UMXHQ | MIT | MIT | Yes — lower quality |
| Open-Unmix UMXL | MIT | CC BY-NC-SA 4.0 | No |

The decisive constraint is **MUSDB18-HQ contamination**. Its licence reads:
"MUSDB18HQ is provided for educational purposes only and the material contained
in them should not be used for any commercial purpose without the express
permission of the copyright holders." It contains MedleyDB tracks under
CC BY-NC-SA 4.0. Any model trained on it inherits that restriction regardless
of what the code is licensed under — which covers the great majority of
high-quality open 4-stem checkpoints.

So Tryptify does not ship them. The manifest format carries a `license` and a
`commercialUse` field, and `StemModelCatalog` refuses to offer a model whose
licence does not permit redistribution in the current build. That check exists
so this decision cannot be quietly undone by editing a JSON file.

### The route to shipping weights

1. **Vocals first.** The KimberleyJSN Mel-Band RoFormer vocal checkpoint is MIT
   on both code and weights and is genuinely shippable today. Vocals from the
   model, backing as `mixture − vocals`.
2. **Then 4-stem, trained in-house.** Take an MIT architecture (BS-RoFormer,
   SCNet) and train on a commercially cleared dataset that excludes
   MUSDB18-HQ. That is the only defensible route to redistributing 4-stem
   weights in a paid build.

The residual trick in step 1 is not a compromise. `mixture − vocals` reuses the
original mixture's phase for everything it keeps, so the backing carries the
record's own stereo image rather than an estimate of it. On the brief's second
priority that is better than direct 4-stem synthesis, not worse.

---

## Spatial fidelity — the finding that shaped the test suite

The brief requires stereo width, phase relationships, ambience and image to
survive separation. The published position is that **no current model fully
manages this.** Namballa et al. (ISMIR 2025, arXiv:2507.00155) measured
HTDemucs FT, Open-Unmix and Spleeter on binaural material and concluded that
"stereo MSS models fail to preserve the spatial information critical for
maintaining the immersive quality of binaural audio, and that the degradation
depends on model architecture as well as the target instrument."

Three consequences, all of which are implemented rather than noted:

- **Never process L and R independently.** BSRNN is the cautionary case: it
  treats the channels separately by design, and its own authors note that "not
  accounting for the cross-channel information might limit the system's
  performance." Every backend in this codebase takes stereo in and returns
  stereo out, and `SpatialMetricsTest` fails a backend that collapses the image.
- **Measure it, don't assume it.** `SpatialMetrics` computes inter-channel
  correlation, ΔILD, ΔITD, width, L/R energy balance, DC offset and true-peak
  headroom, and `StemValidation` compares the summed stems against the original.
- **Prefer the residual** wherever a stem can be obtained by subtraction.

---

## Audio path

| Stage | Format |
|---|---|
| Decode | 32-bit float PCM, native sample rate |
| Internal processing | 32-bit float, stereo preserved end to end |
| Model input | Model's native rate (44.1 kHz for every candidate here) |
| Resampling | Only when the source rate differs from the model rate |
| Output | 32-bit float stereo per stem, at the input's rate |

No mono downmix, no lossy intermediates, no temporary MP3, and no per-stem
normalisation — stems keep their relative levels so that summing them
reconstructs the mix. Headroom is handled in float rather than by clipping.

## Chunked inference

Long tracks are processed in overlapping windows and recombined with a
triangular weighted overlap-add, mirroring Demucs' `apply_model`:

| Parameter | Value | Why |
|---|---|---|
| Segment | model's native window (≈7.8 s for HTDemucs) | Shortening it lowers RAM and hurts quality |
| Overlap | 0.25 | Demucs default; 0.1 available as a fast mode |
| Weighting | triangular ramp, `transition_power = 1.0` | Tapers each window to its edges so seams cancel |
| Shifts | 1 on mobile | `shifts=N` averages N randomly offset passes for ~0.2 dB SDR at N× the cost |

`ChunkedSeparationTest` asserts the property that matters: the weights at every
output sample sum to 1, so overlap-add is unity-gain and a seam cannot appear
as a level bump.

## Known limitations

- The shipping DSP backend is not a neural separator. It does HPSS and centre
  extraction, which is genuinely useful for drums and for pulling a centred
  vocal, and is not competitive with HTDemucs on `other`.
- Separation is an offline background job, not a live effect. A four-minute
  track on a mobile CPU takes minutes, not seconds. Nothing here claims
  real-time.
- Every model degrades the spatial image to some degree (see above).
- Separating a user's copyrighted track creates a derivative work. Separation
  is on-device and user-initiated, and audio never leaves the device.

## Privacy

Once a model is installed, separation works with no network access. Audio is
never uploaded, there is no API key, and no telemetry contains audio content.

---

## Appendix A — NPU go/no-go

The spike is cut unless all four hold:

1. The neural core stays on the HTP with no silent CPU fallback of attention or
   RoPE blocks.
2. Quantized SDR loss against fp32 CPU is under ~0.3 dB **and** passes blind
   listening.
3. NPU wall-clock beats the CPU tier by ≥2× after CPU-side STFT/iSTFT.
4. There is a viable multi-SoC delivery story.

Point 4 contradicts an assumption in the original brief: QNN context binaries
are **SoC-specific**, so a single universal asset on a GitHub Release is not how
the NPU path works. The options are several per-SoC binaries, ONNX Runtime's
flexible context binary (`htp_arch` as a list), LiteRT's QNN accelerator with a
Play AI Pack, or on-device compilation with a slow first run. The
GitHub-Releases-plus-SHA-256 model in the brief works fine for the **CPU** ONNX
assets, which is what `StemModelManager` implements.

## Appendix B — measured, not assumed

Numbers in this card come from published sources. They are starting points for
a decision, not a substitute for benchmarking the chosen model on a held-out
set and on real hardware. Re-measure before committing.

## Attribution

No third-party weights are distributed at this time. When they are, their
licence text and required attribution ship alongside them in `LICENSES.txt`
inside the model archive, and `StemModelManager` refuses to install an archive
whose licence file is missing.
