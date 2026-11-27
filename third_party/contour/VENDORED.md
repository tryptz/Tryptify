# Contour's core, vendored

A copy of the core of [Contour](https://github.com/Chronosauros/contour), the
app that writes a parametric EQ into a USB DAC's own memory. Apache-2.0: see
`LICENSE`, `NOTICE` and `THIRD_PARTY_NOTICES.md` here, which are Contour's own.

- Commit: `5674bbe01db1b06db6ab71ed90cbfa85452660d8` (2026-11-27)
- Copied by `tools/contour/vendor.sh`, which says what is copied. Nothing in
  this folder is edited by hand: a change belongs upstream, or in Tryptify's
  own code beside it. Run the script again to move to a newer Contour.
- Built as the Gradle module `:contour-core` (`contour-core/build.gradle.kts`),
  where Contour's own tests run: `./gradlew :contour-core:test`.
