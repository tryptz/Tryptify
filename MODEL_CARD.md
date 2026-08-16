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
| **Current blocker** | Model conversion and hosting, not inference |

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

Nothing has been converted and hosted yet. The slot is plumbing waiting for an
asset, not a decision against one.

The shortlist, in the order worth attempting:

| Model | Stems | Why |
|---|---|---|
| **Mel-Band RoFormer (vocals)** | vocals + residual | Highest vocal quality of the practical options, and the smallest export job |
| **HTDemucs (base, not the FT bag)** | 4 | ONNX export is solved — two independent parity-verified efforts in 2025–26 |
| **HTDemucs 6s** | 6 | The only practical route to guitar and piano stems |
| **SCNet-small** | 4 | 10M params, roughly half HTDemucs' CPU time; the low-end tier |
| **MDX-Net** | per-stem | Already ships as ONNX, so it needs no export work at all |

HTDemucs is the reference because its export path is proven and it is genuinely
strong on all four stems.

### Six stems

`htdemucs_6s` adds guitar and piano, and it is the same architecture as the
four-stem model with two extra outputs — so the export work carries over and
the chunking, windowing and validation paths need no change at all.

Two things about it are worth knowing before it is the default. Its guitar and
piano stems are noticeably weaker than its other four, piano especially; and
its `other` is not the same signal the four-stem model calls `other`, because
guitar and piano have been carved out of it. A project built against four-stem
`other` will sound different through the six-stem model, which is a surprise
worth not causing silently.

So the stem set is a per-model property in the manifest rather than a constant,
and `availableStems` is a per-backend question. A four-stem model advertises
four, the DSP fallback advertises four, and only a model that was trained for
six claims six. Asking any of them for a piano stem returns what they have
rather than an error. RoFormer is the quality ceiling but the hardest
export — rotary embeddings, fused attention and dynamic sequence length all
need rework, and no widely-used parity-verified ONNX export exists yet.

`ModelLicense` records what each checkpoint is under so the model details can
show it and a saved stem can be traced back to what produced it. It is
metadata; it does not gate installation.

### Vocals first, then four stems

Worth doing in that order for a technical reason, not just a scheduling one.
`mixture − vocals` reuses the original mixture's phase for everything it keeps,
so the backing carries the record's own stereo image rather than an estimate of
it. On the brief's second priority that beats direct 4-stem synthesis. It is
also why the highest-scoring community "instrumental" models are vocal models
run in residual mode.

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
  headroom, and `SpatialMetrics.reconstruction` compares the summed stems
  against the original.
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

Each output sample is divided by the weight actually applied to it, which makes
the overlap-add unity gain by construction rather than approximately so — and
is why the first and last windows, which have no partner to overlap with, need
no special case. `ChunkedSeparationTest` pins it down by running an identity
separator through the whole path and requiring the input back sample for
sample.

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

When third-party weights are added, their licence text and any required
attribution ship inside the model archive as `LICENSES.txt` and are shown in
the model details.
