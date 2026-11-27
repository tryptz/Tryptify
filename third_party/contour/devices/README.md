# Device records

One strict-JSON file per supported or candidate device, named `<vendor>-<model>.json`. A record states what is
known about a device: how it is found on USB, which driver family talks to it, what the PEQ can do, which
settings the vendor software offers, and what was tested on the unit. Records are data only, and every device
fact the app uses lives here once: the USB identity of all eighteen devices, the capabilities, preamp policy, factory flats
and read bounds of the WalkPlay devices, the registers, ranges and delays of the Moondrop and KT Micro drivers, the ranges, presets, factory bank and pacing of the Moondrop gen-1 cable (DUSK-SP), and
the FiiO configuration are generated from the records at build time (below). Codecs, write sequences, parsers and the
Micro high-shelf emulation stay code. A golden test keeps the whole catalog from drifting.

## Devices

One row per record, generated from `devices/*.json` by `python3 android/tools/catalog/gen_capability_table.py` (run it after a record changes; `--check` exits 1 when the table is stale). Do not edit between the markers. Gain and Q are the ranges the editor offers (a different shelf or peak range is named; a Q scale divides the register range). `held` types are in the record's `heldTypes`. DAC settings are the record's rows plus the KT Micro EQ switch and Global Gain; read-only rows the code adds without a record entry (the WalkPlay mic row) are not listed. Tested is the `tested` block: its date and four results, or `not tested on the new track` when nothing was run.

<!-- capability-table:start -->

| Device | Family | Bands | Filter types | Gain dB | Q | Frequency Hz | Preamp | User slots | DAC settings | Tested |
|---|---|---|---|---|---|---|---|---|---|---|
| CrinEar Protocol Micro (`crinear-protocol-micro`, stable) | walkplay | 8 | PK, LS, HS; held: LP, HP | -10 to +10 | 0.1-10 | 20-20000 | -30 to 0 dB, whole dB | 1 (Custom) | none | 2026-11-25: ranges pass, acoustic pass, persistence pass, settings partial |
| CrinEar Protocol Max (`crinear-protocol-max`, stable) | walkplay | 10 | PK, LS, HS, LP, HP | -12 to +12 | PK LP HP 0.1-10; LS HS 0.0707-7.07 | 20-20000 | -30 to 0 dB, whole dB | 1 (Custom) | none | 2026-11-10: ranges pass, acoustic pass, persistence pass, settings pass |
| TRN Black Pearl (`trn-black-pearl`, stable) | walkplay | 10 | PK, LS, HS; held: LP, HP | -10 to +10 | PK 0.1-10; LS HS 0.0707-7.07 | 20-20000 | -30 to 0 dB, whole dB | 1 (Custom) | none | 2026-10-28: ranges pass, acoustic partial, persistence pass, settings pass |
| FiiO KA15 (`fiio-ka15`, stable) | fiio | 10 | PK, LS, HS; held: BP, LP, HP, AP | -12 to +12 | PK 0.1-10; LS HS 0.0707-7.07 | 20-20000 | -24 to 0 dB, whole dB | 3 (USER1 to USER3) | volume, volume-steps, balance, filter, output, dre, uac (read only), brightness, screen-off, rotation, language | 2026-10-26: ranges pass, acoustic pass, persistence pass, settings pass |
| FiiO K13 R2R (`fiio-k13-r2r`, stable) | fiio | 10 | PK, LS, HS, BP, LP, HP, AP | -24 to +12 | PK 0.0707-7.07; LS HS BP LP HP AP 0.1-10 | 20-20000 | -24 to +12 dB | 10 (USER1 to USER10) | none | 2026-10-25: ranges pass, acoustic pass, persistence pass, settings not-run |
| SnowSky Melody (`snowsky-melody`, stable) | fiio | 10 | PK, LS, HS; held: BP, LP, HP, AP | -12 to +12 | PK 0.1-10; LS HS 0.0707-7.07 | 20-20000 | -24 to 0 dB, whole dB | 3 (USER1 to USER3) | volume, volume-steps, balance, filter, gain, output, dre, mic, light, buttons, uac (read only) | 2026-10-25: ranges pass, acoustic partial, persistence not-run, settings pass |
| SnowSky Tiny A (`snowsky-tiny-a`, stable) | fiio | 10 | PK, LS, HS, BP, LP, HP, AP | -12 to +12 | PK BP LP HP AP 0.1-10; LS HS 0.0707-7.07 | 20-20000 | -12 to +12 dB, whole dB | 3 (USER1 to USER3) | volume, volume-steps, balance, mic, light, uac (read only) | 2026-10-24: ranges pass, acoustic partial, persistence not-run, settings pass |
| Moondrop Dawn Pro 2 (`moondrop-dawn-pro-2`, stable) | moondrop | 8 | PK; held: LS, HS, LP, HP | -18 to +12 | 0.1-10 | 20-20000 | -30 to 0 dB (register to -128); Global Gain -18 to 0 dB, 0.1 dB steps (read only: the unit ignores a write) | PEQ, EQ index 7 or 9 | global-gain (read only) | 2026-10-25: ranges partial, acoustic pass, persistence partial, settings partial |
| Kiwi Ears Allegro Mini (`kiwi-ears-allegro-mini`, stable) | ktmicro | 5 | PK; held: LS, HS | -12 to +12 | 0.1-10 | 40-20000 | none; Global Gain -18 to +3 dB, 1.5 dB steps | unknown | eq-switch, global-gain | 2026-10-25: ranges partial, acoustic partial, persistence not-run, settings pass |
| Moondrop Echo-A (`moondrop-echo-a`, records-only) | walkplay | 8 | PK, LS, HS, LP, HP | -10 to +10 | 0.1-10 | 20-20000 | -30 to 0 dB, whole dB | unknown | none | 2026-10-25: ranges partial, acoustic pass, persistence not-run, settings partial |
| JCally JM12 (`jcally-jm12`, stable) | ktmicro | 5 | PK, LS, HS | PK -12 to +12; LS HS -6 to +6 | PK 0.2-10; LS HS 0.7-1 | 20-20000 | none; Global Gain -18 to +3 dB, 1.5 dB steps | 1 (Custom) | eq-switch, global-gain | 2026-11-10: ranges pass, acoustic pass, persistence pass, settings pass |
| FiiO JadeAudio JA11 (`fiio-ja11`, stable) | fiio | 5 | PK, LS, HS, LP, HP | -12 to +12 | 0.1-10 | 20-20000 | -24 to 0 dB | 1 (USER1) | volume, mic, uac (read only) | 2026-11-11: ranges pass, acoustic pass, persistence pass, settings pass |
| Moondrop DUSK-SP (`moondrop-dusk`, stable) | moondrop-gen1 | 9 | PK, LS, HS | -12 to +12 | 0.2-10 | 20-20000 | none | 1 (Custom EQ) | preset | 2026-11-11: ranges pass, acoustic not-run, persistence pass, settings pass |
| SnowSky RETRO NANO (`snowsky-retro-nano`, stable) | fiio | 10 | PK, LS, HS, LP, HP | -12 to +12 | 0.25-8 | 20-20000 | -12 to +12 dB, whole dB | 3 (USER1 to USER3) | none | 2026-11-13: ranges pass, acoustic pass, persistence pass, settings not-run |
| FiiO JadeAudio JIEZI (`fiio-jiezi`, stable) | fiio | 10 | PK, LS, HS, BP, LP, HP, AP | -12 to +12 | PK BP LP HP AP 0.1-10; LS HS 0.0707-7.07 | 20-20000 | -12 to +12 dB, whole dB | 3 (USER1 to USER3) | volume, volume-steps (read only), balance, mic, uac (read only) | 2026-11-13: ranges pass, acoustic pass, persistence pass, settings partial |
| Kiwi Ears AD1 (`kiwi-ears-ad1`, stable) | ktmicro | 5 | PK, LS, HS | PK -12 to +12; LS HS -6 to +6 | PK 0.2-10; LS HS 0.7-1 | 20-20000 | none; Global Gain -18 to +3 dB, 1.5 dB steps | 1 (Custom) | eq-switch, global-gain | 2026-11-13: ranges pass, acoustic partial, persistence pass, settings pass |
| TANCHJIM SPACE II (`tanchjim-space-ii`, stable) | walkplay | 10 | PK, LS, HS, LP, HP | -12 to +12 | 0.1-10 | 20-20000 | -30 to 0 dB, whole dB | 1 (Custom) | none | 2026-11-13: ranges partial, acoustic pass, persistence pass, settings pass |
| EPZ TP65 (`epz-tp65`, stable) | walkplay | 10 | PK, LS, HS | -10 to +10 | 0.1-10 | 20-20000 | read only (the DAC's 0 dB; a send needs 0 dB) | 1 (Custom) | none | 2026-11-27: by a community tester on their unit, no bench run |

<!-- capability-table:end -->

`stable` means Contour already writes to the device on the 1.6 line; the record is derived from the code. `records-only` means no write path ships; the record is derived from the
vendor software research and the PC bench runs, and only fixes the driver family.

The Echo-A is the one exception: its EQ write ships since 25.10.2026 (`WalkPlayProfiles.ECHO_A_HARDWARE_VERIFIED`, run 2026-10-25-moondrop-echo-a-05-mac-eqtest-48k), through the Protocol Micro's code, but
its record still holds `"unknown"` for the fields a `stable` walkplay record needs (band length, write sequence, slots, pacing); its `peq.freqHz` and `preamp` hold what the code applies (the Micro's 20-20000 Hz and -30..0 dB whole-dB preamp; the generator does not read them for a read-only record), so it stays `records-only` and the generator builds the
`ReadOnlyWalkPlayData` for it. It becomes `stable` when those fields are measured. The Dawn Pro 2 and the Allegro Mini write in 1.6 and are `stable`.

The JCally JM12 (chip KT02H20) shares 31B2:0111 with the Allegro Mini and was the first record in the new shape; all eighteen records have it now (no `evidence`, `conflicts` or top-level `src` blocks; a `tested` block and short `usb.notes` lines instead). Its record states what the unit was measured to play (peaks +-12 dB with Q 0.2-10, shelves +-6 dB with Q 0.7-1.0). A phone wrote and read back its EQ on 10.11.2026 (run 2026-11-10-jcally-jm12-11-phone), so `hardwareVerified` is true in code and it writes in every build.

## Schema summary

- Identity: `id`, `name`, `shortName`, `productName`, `vendor`, `status`, `family`.
- `usb`: `vid`, `pid`, `productName`, `hidInterface`, `matchByName`, `nameAliases` (further exact product names a
  name-matched device reports), `recognisedBy` (`find`: VID:PID only, the 1.5 list; `findDevice`: the 1.6 lookup, which
  needs the product name for a record with `matchByName`), `listOrder` (position in the generated Android attach filter;
  append new devices at the end; records that share a VID:PID share the filter line of the first), free-text `notes`. Two records may share one VID:PID only when every record of that VID:PID has `matchByName` true and their `productName` and `nameAliases` sets are disjoint (the Allegro Mini and the JM12, told apart by the USB name); the generator refuses every other duplicate.
- `driver`: family-specific protocol values (report id, `schemeNo`, band length, write sequence, strict parse, import
  mode, A/B, design rate; for FiiO also `hardwareVerified` and `capture`; for Moondrop the register commands, delays
  and `reportBytes`; for KT Micro the payload length, read/save commands, `bandRegisters`, `writeAck`, the EQ and
  gain registers, `reconnectAfterSave`: true or false, or "unknown" to keep the code's value in `PendingRecords`, and the optional `eqEnableStatusBytes`: true when bytes 1-3 of the EQ switch register 0x24 are device status that changes by itself (JCally JM12): a switch write then sends `[eq, 0, 0, 0]` and every read-back check compares byte 0 only; absent or false = the Allegro Mini, whose bytes are echoed and compared whole), and the optional `sendTurnsEqOn`: true when the app's send turns the EQ switch ON if it reads OFF (JCally JM12, owner decision 10.11.2026: a factory unit ships with it OFF, so a send would be silent): one extra `[3, 0, 0, 0]` write to 0x24 before the bands, verification expects byte 0 = 3, nothing extra when it already reads ON; absent or false = the switch is left as found (Allegro Mini). The EQ switch row on the DAC page still turns it OFF; the next send turns it ON again.
- `peq`: `bands`, `fixedBandCount`, `freqHz`, `gainDb`, `q`, `types` (offered, with their code), `heldTypes`
  (known to the code but not offered), `shelfQScale`, `peakQScale` (FiiO records; a KT Micro record that offers a shelf in `types` needs `shelfQScale`), `shelfForm` (every record), `coefficientRateHz` (WalkPlay records), `emulation`.
- Family `moondrop-gen1` (the DUSK-SP; FreeDSP-Studio's gen 1, devicePEQ's "Conexant"; report 1, 62 bytes, control pipe): `driver` has `reportId` 1, `reportBytes` 62, `transport` "control-pipe" (every request is SET_REPORT then GET_REPORT; no interrupt endpoint), the command bytes, `customIndex` (the one bank Contour writes: 0), `coefficientScaleBits` and `hardwareVerified` (false keeps the app from writing the cable in a release; a review build writes it); `peq` has 9 fixed bands, `types` PEAK 0 / LOW_SHELF 1 / HIGH_SHELF 2, `shelfQScale` 1.0 (the shelf slope is Q) and `coefficientRatesHz`; `preamp.present` is false (the cable has no preamp register, `src` says why); `slots.labels` names the Custom EQ (0) and the firmware presets, `slots.stock` lists the presets, `slots.presetBands` has the bands each firmware preset plays (`bands`: preset index -> [Hz, dB, Q] PEAK rows, no preamp; `src` names Crinacle's publication and FreeDSP-Studio; the cable cannot report them, so the app shows this table as what plays while the cable is on that preset), `slots.stockPicker` true gives the DAC page a PRESET row that selects one and reads the index back (a send always goes to the Custom EQ and leaves the cable on it), `slots.factoryBank` is the Custom bank from the factory ([frequency, Q, "PEAK"] per band position: what a band Contour does not use is written as); `pacing` has `replyWaitMs` (SET to GET), `writeGapMs`, `afterCommitMs`, `afterSelectMs` and the restart the commit causes (`driver.restartsAfterCommit`, `restartLeavesAfterMs`, `restartAwayMs`, `reconnectGoneWaitMs`, `reconnectBackWaitMs`: the cable stores the bank and restarts onto preset 1; `slots.powerUpPreset` names that preset). The gain on the cable's meta record is whole dB while the coefficients carry the exact gain.
- `preamp`: `present` (a KT Micro record without a `globalGain` block offers no Global Gain row and the code never writes register 0x3B for it; the JM12 has the block of the Allegro Mini (its register 0x66 is not a preamp: `present` stays false)), `db` (the range the DAC's register takes; **Contour never offers a preamp below -30 dB**: when `db` goes lower, the app, the DAC page, a send, this table and the device pages use -30 as the floor - core `Preamp.FLOOR_DB`, owner 11.11.2026; a read still keeps what the DAC holds), `step`, `wholeDb` (the DAC keeps whole dB of the preamp only, so the plan rounds it down to a whole dB: WalkPlay Micro, Max, TRN and, since 1.6, the SnowSky Melody and Tiny A; since 1.6.1 the FiiO KA15, which resets during a send with a fractional preamp), `registerOffsetDb`.
- `slots`: `user`, `declaredWritable`, `stock`, `bypass`, `labels`, `picker`, `names`; for WalkPlay also
  `slotByte` and `factoryFlat`; for Moondrop also `writableIndexes` (every EQ index whose bank Contour writes; `driver.peqBank` is the one the Hub's first frame
  asks for and must be in the set; on any other index the unit is on one of its own presets and nothing is written).
- `peq.designRateHz` / `peq.assumedStreamHz` (KT Micro records, positive numbers of Hz): the DSP designs its biquads for `designRateHz` (24 000 Hz, measured) and runs them at the stream rate, so a band plays at register f x stream / designRateHz. Contour assumes a stream of `assumedStreamHz` (48 000 Hz) and writes register f = intended f x designRateHz / assumedStreamHz; a read is converted back. `peq.freqHz` is the REGISTER range (the vendor tool's 20-20000 Hz); the editor offers the intended range, that range x assumedStreamHz / designRateHz capped at the upper edge (40-20000 Hz).
- `peq.shelfGainDb` / `peq.shelfQ` (KT Micro records that offer a shelf; both or neither): a shelf's own gain range in dB and INTENDED Q range, inside `peq.gainDb` and the Q range: what a unit was measured to play within tolerance (JM12: peaks +-12 dB, Q 0.2-10; shelves +-6 dB, Q 0.7-1.0). They reach `DeviceCapabilities.shelfLimits`; the planner, the issue texts, the importer and the editor (sliders, typed values, the graph drag) use `gainRange(type)` and `qRange(type)`; a record without them gives a shelf the device-wide limits.
- `peq.shelfQScale` / `peq.peakQScale`: the Q convention measured on the DAC for that type, a positive number or `"sqrt2"`: the register holds intended Q x the scale (the DAC plays
  Q = register / scale), so the editor offers the register range divided by it. FiiO records have both; a WalkPlay record has `shelfQScale` (the TRN: `"sqrt2"`). 1.0 = the register is the Q.
- `peq.shelfForm` (every record, required): how the DAC builds a LOW or HIGH SHELF from the band's Q. `"q"`: the DAC builds the shelf from its registers, RBJ with `alpha = sin(w0) / (2*Q)`, Q = register / `shelfQScale`
  (the DAC plays the Q the user typed; the same filter family as the slope form, 3-4 dB higher at Q 2-3). `"slope"`: the DAC plays the biquad coefficients Contour computes (`WalkPlay.scaledCoefficients`, the Moondrop gen 1 bytes), RBJ shelf slope S = Q.
  `"unknown"`: allowed only where `peq.types` offers no shelf (the Allegro Mini); the code then uses the slope form. The two forms coincide only at S = 1 (Q 0.7071). The field changes only what the editor draws and what AUTO preamp computes
  (`DeviceCapabilities.shelfForm`, `DeviceTarget.shelfForm`); band registers, coefficient bytes and Q scales never follow it. The generator refuses a record without the field, `"unknown"` next to a shelf in `peq.types`, and a
  `moondrop-gen1` record that does not say `"slope"`.
- `peq.coefficientRateHz` (WalkPlay records, a positive number of Hz): the rate the DSP runs the coefficient bytes Contour sends at; they are designed for it (`driver.designRateHz` stays the
  vendor tool's 96 000 Hz). The Protocol Micro and the Echo-A measure 93 750 Hz; the Max, the TRN (which computes its shelves from the registers) and the Dawn Pro 2 are 96 000 Hz. The `tested.runs` of the
  record hold the runs.
- `pacing`: delays and flags of the transport (write gap, echoes, checksum, audio stream, probe gap, save wait;
  WalkPlay also `sequence`). `readback`: Q slack and, for WalkPlay, read bounds.
- `settings`, `info`, `preview`: the non-PEQ controls the vendor software offers and how they are shown.
- `reset.offered`: the reset is on offer for the device, by the vendor software or by Contour although the vendor hides it (KA15; a KT Micro record: the generator reads it; false = Contour never builds CLEAR 0x43 for that model). Contour 1.6.0 ships FACTORY RESET for the FiiO KA15 and the SnowSky Melody, with a saved copy first. The Tiny A ignores the command, so it has none in the app although its record says `true`; the resets of the other DACs stay off in the release build until they are proven on hardware.
- `hwVolume.inScope`: whether a hardware volume control belongs to the vendor scope.
- `tested` (every record; the generator does not read it, it is the one place for what was checked on the unit): `date` (ISO date or null), `firmware` (the unit's firmware string), `contour` (the Contour build the runs used, or null), then four verdict objects `rangesAccepted` (does the unit take the ranges the record offers), `acoustic` (does the EQ it plays match the request), `persistence` (does the EQ survive a power cycle), `settings` (the DAC Settings rows), each `{ "result": "not-run" | "pass" | "fail" | "partial", "note": "" }`; `beyondVendor` (free text: what the record offers beyond the vendor tool or devicePEQ, and what the unit did with it) and `runs` (run ids, the evidence). Optional `by`: `"community"` when the checks were run by someone else on their own unit and not by the maintainer (the EPZ TP65, tested by a TP65 owner with tester versions, issue #6): every verdict is then `"not-run"`, its note says what that person saw, and the README's Tested column says "community tester".
- `reset.src` and `hwVolume.src` (optional): one short free-text line on why that answer is what it is.

## Rules

- A value that is not known is the literal string `"unknown"`. It is never guessed, never defaulted and never
  left out.
- When two sources disagree, the record keeps the value the code uses (or `"unknown"`); a disagreement that still
  matters is one short line in `usb.notes`. A check nobody did on the unit is `"not-run"` in `tested`; a device
  confirmed only by the community says so in the note and never gets `"pass"` for a check someone else did on their unit.
- A pass filter or shelf that the code knows but does not offer is listed in `heldTypes`, not in `types`. The Micro, the KA15, the Melody and the Dawn Pro 2 still list in `heldTypes` the types that the app offers today behind a verification flag (all true): the generator emits `types` as the plain catalog, so moving them would change the generated catalog and the golden, and waits for a deliberate catalog change.
- References in notes are public and stable: repository paths with a line, public URLs, commit hashes, `run:<id>`
  for a bench run (the runs of a unit are listed in `tested.runs`). No serial numbers, no local paths and no
  personal names. Notes are short public facts in plain English.

## How generation works

`:core:generateDeviceCatalog` (`android/core/devices.gradle.kts`, applied from `android/core/build.gradle.kts`) reads
`devices/*.json` and writes `GeneratedDevices.kt` into the build directory (`core/build/generated/devices/kotlin`, or under
`contour.buildRoot`). That directory is a Kotlin source dir of `:core`, so `compileKotlin` runs the task first; its inputs
(the records) and output are declared, so it is skipped when nothing changed. No plugin and no network: the script parses
the records with `groovy.json.JsonSlurper`, which ships with Gradle, so offline builds (F-Droid) work. The generator lives
in `android/core`, not in `android/tools`, because the public export drops `android/tools`.

What is generated today (ids are upper-cased with dashes as underscores, e.g. `FIIO_KA15`, `MOONDROP_DAWN_PRO_2`):

- Every record: `<ID>_USB` (a `UsbIdentity`: vid, pid, product name, name match, aliases, `recognisedBy`), the list
  `USB_IDS` in `usb.listOrder`, and the `<ID>_VENDOR_ID` and `<ID>_PRODUCT_ID` constants. `DeviceTarget.find` and
  `findDevice`, `MoondropCatalog.find` and `KtMicroCatalog.find` read these; no VID or PID is typed in Kotlin.
- Family `fiio`: `GeneratedDevices.<ID>` as a `FiioConfig` with every field set from the record, the capture identity, the DAC
  Settings placement (`SettingPlacement` rows: id, section, group, writeVerified), header info ids and preview values.
  `shelfQScale` and `peakQScale` `"sqrt2"` are emitted as `sqrt(2.0)`, never a rounded literal; `shelfForm` is emitted as `ShelfForm.Q` or `ShelfForm.SLOPE` (every family, `"unknown"` as slope).
- Family `walkplay`, status `stable` (Micro, Max, TRN): `WalkPlayData` (name, ids, `schemeNo`, band count, gain and Q range,
  frequency range, offered types, shelf Q scale, coefficient rate, preamp policy, factory flat of the spare slots, TRN read bounds) and the constants
  `<ID>_REPORT_ID`, `_BANDS`, `_BAND_LEN`, `_DESIGN_RATE_HZ`, `_PREAMP_MIN_DB`, `_PREAMP_MAX_DB`. Status `records-only` with a
  hypothesised family (Echo-A): `ReadOnlyWalkPlayData` (with `coefficientRateHz`), which the read-only Echo-A profile is built from.
- Family `moondrop` (Dawn Pro 2): `MoondropData` (bands, ranges, native and offered types, pre-gain and global gain ranges,
  writable bank, report size) and the register, delay and design-rate constants of `MoondropCodec`.
- Family `ktmicro` (Allegro Mini, JCally JM12; any number of records): one `KtMicroData` per record (bands, ranges, types, band register layout, write ack, rates, shelf Q scale, global gain steps or none, `reconnectAfterSave`, `reset.offered`) and the
  register and command constants of each. The protocol constants all KT records share (report id, payload bytes, read and save commands, EQ switch registers and values, digital and Global Gain registers) are asserted equal across the records by the generator; `KtMicroCodec` takes them from the Allegro Mini's constants. Types may be `PEAK` 0, `LOW_SHELF` 3 and `HIGH_SHELF` 4 (the KT codes); the Allegro Mini holds the shelves in `heldTypes`, so its output is unchanged.
- Family `moondrop-gen1` (DUSK-SP): `MoondropGen1Data` (bands, ranges, types, preset labels, the Custom index, the stock picker flag, the factory bank, the four pacing delays, `hardwareVerified`); the frames, coefficients, replies and session are hand-written (`MoondropGen1Codec`, `MoondropGen1Session`), the HID report qualification is `NativeHidReports.parseGen1` / `qualifyGen1` (report 1 on the Consumer page, exactly 62 bytes).
- `:app:generateUsbDeviceFilter` (`android/app/build.gradle.kts`): the Android USB attach filter `res/xml/device_filter.xml`
  (decimal ids, one `<usb-device>` per distinct VID:PID in `usb.listOrder`). It is a generated resource directory of every `:app`
  variant, so there is no hand-written filter and no second activity alias. `:core:test` runs it first and compares it with
  the app's recognition.

What stays hand-written: the choice and order of the FiiO rules (`FiioCatalog.rules`), the setting templates with their
commands and decoders (`DacSettings`), codecs, write sequences, parsers, the Micro high-shelf emulation, the never-send lists
and the session logic. A record picks a known template by id; it cannot add behaviour. `core/devices/PendingRecords.kt` holds
the few values a driver needs while the record still says `"unknown"` (each with the reason); the day a record gets a sourced
value, it moves into the record and out of that file.

The gate is the generator itself: it throws, and the build fails, when a record misses a key, holds the placeholder
`"unknown"` in any field its driver needs (the fiio driver: driver, peq, preamp, slots, pacing, readback, settings, info,
preview, upstream, usb id and name; the other drivers: the fields listed above), has a wrong type, or has `preamp.db` that
differs from `peq.gainDb` minus `preamp.registerOffsetDb`. It also refuses two records with one `usb.listOrder`, or with one VID:PID unless the shared-VID:PID rule above holds,
a `find` record that matches by name, and records of one WalkPlay protocol that disagree on report id or band length. Free text
(notes, tested, reset, hwVolume) is not read. Values in `"unknown"` form stay legal wherever no driver reads them.

## Add a FiiO-family device

1. Copy the closest FiiO record to `devices/<vendor>-<model>.json` and fill every field from evidence; keep `"family": "fiio"`.
   Mapping to `FiioConfig`: `slots.names` is `device-by-index` (names by position, KA15), `device-by-slot` (by slot id, SnowSky),
   `app` or `none` (names kept in the app); `readback.qSlack` is `fiio-1+q/250` or `none`; `pacing.checksum` is `checksumFrames`;
   `pacing.consumeEchoes` is `consumeWriteEchoes`; `driver.disconnectOnSave` true (the FiiO JA11, per devicePEQ) ends a send at the SAVE frame: `NativeSession.write` returns
   a pending result with the expected registers, the app verifies it with the first read after the DAC is back (as the KT Micro saves), the bench call ends with `dacDisconnects`;
   `driver.bypassPreampAnswersAsCount` (optional, default false; the RETRO NANO) lets the session read a preamp query answered on the bypass slot with the one 0x18 "no EQ" frame (data byte 0) as no EQ active: preamp register 0, no band queries;
   `driver.hardwareVerified` false keeps the app from writing that DAC in a release (`DeviceTarget.readOnly`; a review build writes it);
   a settings row with `reconnectsAfterWrite` true (optional; the JA11 mic) is a write that re-enumerates the DAC: the SET is the last frame of
   the session and the value counts after a settings read once the DAC is back (the bench marks the frame `dacDisconnects`);
   a FiiO-family unit that reports another vendor id (the SnowSky RETRO NANO, 0A12:4007, a generic Bluetooth-chip id) or shares the FiiO id with other products is found like the JA11: `usb.matchByName` true, `usb.recognisedBy` "findDevice", the exact USB product name in `usb.productName`;
   `upstream.status` is the `FiioConfig.status` label; `driver.capture` is
   `{ "fixturePath": ... }` or `null` (a device with a capture is routed by VID, PID and name; the others by product id in `DeviceTarget`).
2. `settings` rows may only use ids that have a template in `DacSettings` (volume, volume-steps, balance, mic, light, uac, filter,
   gain, output, dre, buttons, brightness, screen-off, rotation, language); an unknown id fails at class load, which the golden test
   and `:core:test` catch. A setting with a new command needs a template first (code), then the record may list it.
3. Add `GeneratedDevices.<ID>` to `FiioCatalog.rules` (order matters to the tests), a `DeviceTarget` for it and its entry in
   `DeviceTarget.byRecord`. The USB filter and the VID:PID lookup come from the record's `usb` section (`recognisedBy`,
   `listOrder`); nothing else to type.
4. A deliberate catalog change also changes the golden: regenerate it as below and review the diff.

## Golden file

`android/core/src/test/resources/catalog/golden.json` is the dump of the catalog as the code sees it (all values now come from the generated catalog): every
device, its capabilities and the encoded frames of a fixed set of profiles. The Dawn Pro 2, Allegro Mini and Echo-A are in
the `families` section (config, capabilities, the replayed bench reads and the planned frames); `identity` holds the generated
filter and the `findDevice` answers. `CatalogGoldenTest` compares a fresh
dump from `CatalogDump` with the committed file byte for byte, so any change in the catalog or the codecs fails
the test until the change is deliberate.

Regenerate the golden only when the catalog is meant to change, and review the diff:

    CONTOUR_REGENERATE_GOLDEN=1 android/wsl-build.sh :core:cleanTest :core:test --tests '*CatalogGoldenTest*'

A normal test run never writes the golden.

A change that adds a device may only ADD entries. Check that after regenerating (Python 3 standard library, from the repository root; exit 0 = only new device ids and new list items, otherwise a diff of what changed for an existing device):

    python3 android/tools/catalog/check_golden_append.py [--ref origin/release-1.3]
