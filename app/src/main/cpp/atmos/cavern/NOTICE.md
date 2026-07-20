# Third-party attribution — Cavern

The C++ sources in this directory (`cpp/atmos/cavern/`) are a **port of code from
the Cavern project** by Bence Sgánetz (VoidXH), translated from C# to C++ for
Tryptify's Atmos renderer.

- **Upstream / source repository:** https://github.com/VoidXH/Cavern
- **Creator:** Bence Sgánetz — http://en.sbence.hu

Cavern is **not** released under a permissive (MIT/BSD/Apache) license. It ships
under a custom license, reproduced verbatim below. **These terms travel with the
ported code and with any project that includes it, including Tryptify.**

## ⚠️ Obligations this port imposes

Read these before shipping. They are the license author's terms, not ours:

1. **This repository must link Cavern as its source** (done here + in each file
   header).
2. **No selling any part** of the original or modified version.
3. **No advertisements** in the modified software.
4. **Public or commercial use** (the license explicitly names *"as an API in
   another software"*) **requires the original creator's written permission** and
   a visible link to the creator.
5. The terms apply virally to *any* project that includes any part of this code.

> **TODO (owner action, not a code task):** if Tryptify is distributed publicly
> or monetized in any form, obtain written permission from the creator before
> release. Until then, this port is present under the "release a modified version
> for free under this licence" allowance only.

## Cavern licence (verbatim)

```
# Cavern licence
By downloading, using, copying, modifying, or compiling the source code or a
build, you are accepting these terms. The source code, just like the compiled
software, is given to you for free, but without any warranty. It is not
guaranteed to work, and the developer is not responsible for any damages from
the use of the software. You are allowed to make any modifications, and release
them for free under this licence. If you release a modified version, you have to
link this repository as its source. You are not allowed to sell any part of the
original or the modified version. You are also not allowed to show
advertisements in the modified software. The software must be named with a link
to the creator (http://en.sbence.hu) when used in public (e.g. for screenings)
or commercially (e.g. as an API in another software), also, the original
creator's permission is required for public use (e.g. screening). If you include
these code or any part of the original version in any other project, these terms
still apply.
```

## Ported files & their upstream origin

| This file | Ported from (Cavern.Format/Decoders/EnhancedAC3/…) |
|---|---|
| `qmath.h` | `Cavern.Utilities.QMath` (multiply/accumulate primitives) |
| `quadrature_mirror_filterbank.h` | `QuadratureMirrorFilterBank.cs`, `.Process.cs`, `.Amp.cs` |
| `joc_tables.h` | `JointObjectCodingTables.cs` (+ `SetupStaticCache` from `…Cache.cs`) |
| `joint_object_coding.h` | `JointObjectCoding.cs`, `…Cache.cs`, `…Decoder.cs` |
| `joint_object_coding_applier.h` | `JointObjectCodingApplier.cs` |
| `object_info_block.h` | `ObjectInfoBlock.cs`, `ObjectAudioMetadataEnums.cs` |
