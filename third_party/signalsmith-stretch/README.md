# signalsmith-stretch (vendored)

Header-only polyphonic pitch/time library, used by the transport's
independent pitch shift. Vendored rather than added as a submodule because
it is headers only, and because `assembleDebug` already needs two
submodules checked out before it will configure — a third would be a third
way for a fresh clone to fail.

| part | upstream | commit | licence |
| --- | --- | --- | --- |
| signalsmith-stretch | https://github.com/Signalsmith-Audio/signalsmith-stretch | `57b93f4e9206a089a45387eaa39bdc9f310d3308` | MIT (LICENSE-stretch.txt) |
| signalsmith-linear | https://github.com/Signalsmith-Audio/linear | `8be69c57b7064822076c2cfc55a522e5f5867cc1` | MIT (LICENSE-linear.txt) |

Only the headers are taken. Upstream's `cmd/` (which carries its own
submodule) and `web/` are not vendored.

`signalsmith-stretch.h` includes `signalsmith-linear/stft.h` relative to
itself, which is why `linear`'s headers sit in a subdirectory of that exact
name rather than beside it.
