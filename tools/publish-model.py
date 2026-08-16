#!/usr/bin/env python3
"""Add a model to models/model-manifest.json from a real archive.

The three fields that must never be typed by hand are the url, the size and
the hash, because a wrong hash is indistinguishable from a corrupt download
once it is out in the world: every user who tries gets "checksum did not
match", the archive itself is fine, and nothing on the device can tell you the
manifest was wrong rather than the transfer. So this reads them off the file.

Usage:

    tools/publish-model.py \\
        --archive build/tryptify-stem-ai-qnn-arm64-v1.0.0.zip \\
        --id htdemucs-6s --name "HTDemucs 6s" --version 1.0.0 \\
        --tag tryptify-stem-ai-v1.0.0 \\
        --format ONNX --backend CPU \\
        --stems VOCALS DRUMS BASS GUITAR PIANO OTHER \\
        --segment-frames 343980 --license MIT --source-model htdemucs_6s

Then upload both the archive and models/model-manifest.json as assets of the
release named by --tag. The manifest has to be an asset of a release the app
can reach without authentication, which for a public repository is any release.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import pathlib
import sys

REPO = "tryptz/Tryptify"
MANIFEST = pathlib.Path(__file__).resolve().parent.parent / "models" / "model-manifest.json"

# Mirrors ModelLicense in StemModelManifest.kt. Anything not in this list is
# read by the app as UNKNOWN, which is fine but shows as "Unspecified".
KNOWN_LICENSES = {
    "MIT",
    "APACHE-2.0",
    "MIT-ATTRIBUTION",
    "TRYPTIFY",
    "CC-BY-NC-4.0",
    "CC-BY-NC-SA-4.0",
    "RESEARCH-ONLY",
    "MUSDB-ENCUMBERED",
    "UNKNOWN",
}

# Mirrors the Stem enum.
KNOWN_STEMS = {"VOCALS", "DRUMS", "BASS", "GUITAR", "PIANO", "OTHER"}


def sha256(path: pathlib.Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1 << 20), b""):
            digest.update(block)
    return digest.hexdigest()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--archive", required=True, type=pathlib.Path)
    parser.add_argument("--id", required=True)
    parser.add_argument("--name", required=True)
    parser.add_argument("--version", required=True)
    parser.add_argument("--tag", required=True, help="the git tag of the GitHub release")
    parser.add_argument("--format", default="ONNX", choices=["ONNX", "QNN"])
    parser.add_argument("--backend", default="CPU", choices=["CPU", "QNN"])
    parser.add_argument("--stems", nargs="+", default=["VOCALS", "DRUMS", "BASS", "OTHER"])
    parser.add_argument("--model-sample-rate", type=int, default=44100)
    parser.add_argument("--segment-frames", type=int, default=343980)
    parser.add_argument("--minimum-android", type=int, default=26)
    parser.add_argument("--abi", default="arm64-v8a")
    parser.add_argument("--license", default="UNKNOWN")
    parser.add_argument("--source-model", default="unknown")
    parser.add_argument("--attribution", default=None)
    parser.add_argument(
        "--htp-arch",
        nargs="*",
        default=[],
        help="QNN only. Context binaries are per-SoC; list every Hexagon arch this asset targets.",
    )
    args = parser.parse_args()

    if not args.archive.is_file():
        print(f"error: {args.archive} does not exist", file=sys.stderr)
        return 1

    unknown_stems = set(args.stems) - KNOWN_STEMS
    if unknown_stems:
        print(f"error: unknown stems {sorted(unknown_stems)}", file=sys.stderr)
        return 1

    if args.license not in KNOWN_LICENSES:
        # Not fatal — the app treats anything it does not recognise as
        # "Unspecified" — but a typo here is silent otherwise.
        print(f"warning: '{args.license}' is not a licence the app knows; it will read as Unspecified",
              file=sys.stderr)

    if args.backend == "QNN" and not args.htp_arch:
        print("warning: a QNN asset with no --htp-arch will only run on the SoC it was compiled for",
              file=sys.stderr)

    entry = {
        "id": args.id,
        "name": args.name,
        "version": args.version,
        "format": args.format,
        "backend": args.backend,
        "url": f"https://github.com/{REPO}/releases/download/{args.tag}/{args.archive.name}",
        "sha256": sha256(args.archive),
        "sizeBytes": args.archive.stat().st_size,
        "license": args.license,
        "sourceModel": args.source_model,
        "stems": args.stems,
        "modelSampleRate": args.model_sample_rate,
        "segmentFrames": args.segment_frames,
        "minimumAndroid": args.minimum_android,
        "abi": args.abi,
    }
    if args.attribution:
        entry["attribution"] = args.attribution
    if args.htp_arch:
        entry["htpArchs"] = args.htp_arch

    manifest = json.loads(MANIFEST.read_text())
    models = manifest.setdefault("models", [])

    # Replace rather than append when the same id and version comes back, so
    # re-running after rebuilding an archive updates the hash instead of
    # leaving two entries whose only difference is the checksum.
    models[:] = [m for m in models if not (m.get("id") == args.id and m.get("version") == args.version)]
    models.append(entry)
    models.sort(key=lambda m: (m.get("id", ""), m.get("version", "")))

    MANIFEST.write_text(json.dumps(manifest, indent=2) + "\n")

    print(f"{args.id} {args.version}")
    print(f"  {entry['sizeBytes']:,} bytes")
    print(f"  sha256 {entry['sha256']}")
    print(f"  {entry['url']}")
    print()
    print("Next: create the release and upload both files as assets.")
    print(f"  gh release create {args.tag} {args.archive} {MANIFEST} \\")
    print(f"     --repo {REPO} --title '{args.name} {args.version}'")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
