# WalkPlay HID PEQ protocol - CrinEar Protocol Micro (3302:C20F, SchemeNo11)

Source: jeromeof/devicePEQ, commit `0617f382e76629792a5933e6933e4b396a756a93` (2026-08-26T13:32:14+01:00),
read 2026-09-25. License: `LICENSE.txt` = 0BSD-style ("Permission to use, copy, modify, and/or distribute
this software for any purpose with or without fee is hereby granted", Copyright 2024 Jerome O'Flaherty) -
compatible with an Apache-2.0 app; no attribution strictly required, courtesy credit recommended.
Files: `devicePEQ/walkplayHidHandler.js` (WH), `usbDeviceConfig.js` (UC), `compensation.js` (CO),
`usbHidConnector.js`, `peqConstraintsConfig.json` (PC). Line numbers refer to that commit.
Everything marked VERIFIED was observed on the owner's device on 2026-09-25 (firmware "0.2").

## Transport

- HID interface MI_03, single top-level collection Consumer Control (usage page 0x0C, usage 0x01).
  Report descriptor (92 bytes, VERIFIED, `report-descriptor.txt`):
  - ID 0x03 Input, 1 byte: consumer keys Vol+ (E9), Vol- (EA), Play/Pause (CD), CF - hardware buttons.
  - ID 0x4B Input 63 bytes (vendor page 0xFF01 usage 1) and ID 0x4B Output 63 bytes (usage 2) - **PEQ channel**.
  - ID 0x54 Input 63 bytes / Output 63 bytes (usages 3/4) - not used by devicePEQ. Do not send.
  - No Feature reports.
- Every command = Output report 0x4B, payload padded with zeros to 63 bytes (64 on the wire incl. ID).
  Replies = Input report 0x4B, 63 bytes. Below, byte offsets `d[n]` are WebHID-style (report ID stripped);
  hidapi / raw USB buffers are `d[n] == buf[n+1]`.
- Frame: `d[0]` = direction (0x80 READ, 0x01 WRITE), `d[1]` = command, `d[2]` = length or sub-arg, then args, 0x00 END.
  Replies echo `d[0]=0x80, d[1]=cmd`. Match replies on `d[1]` (WH L471); ignore report 0x03.
- All multi-byte integers little-endian.

## Commands

| Cmd | Name | Request payload (d[0..]) | Reply | Class |
|---|---|---|---|---|
| 0x0C | VERSION | `80 0C 00` | `80 0C 00 'x' '.' 'y'` ASCII at d[3..5] (VERIFIED "0.2") | READ-ONLY |
| 0x09 | PEQ read (bulk form) | `80 09 00` | filter-0 packet; devicePEQ reads slot at d[35] (WH L125). VERIFIED: identical to band-0 reply, d[35]=0 | READ-ONLY |
| 0x09 | PEQ read band i | `80 09 00 00 i 00` | filter packet (below) | READ-ONLY |
| 0x03 | global gain read | `80 03 00` | `80 03 02 00 g` g=int8 dB at d[4] (VERIFIED 0) | READ-ONLY |
| 0x09 | PEQ write band i | `01 09 18 00 i 00 00` + 20 B biquad + freq u16 + q u16 + gain s16 + type + `00` + slot + `00` (WH L153-163) | none awaited | MUTATING |
| 0x03 | global gain write | `01 03 02 00 g` (g = round(dB), int8) (WH L515) | none | MUTATING |
| 0x05 | commit step 1 | `01 05 00` (WH L182) | none | MUTATING |
| 0x17 | commit step 2 | `01 17 00` (WH L184) | none | MUTATING |
| 0x0A | TEMP_WRITE | `01 0A 04 00 00 FF FF 00` (WH L186) | none | MUTATING |
| 0x01 | FLASH_EQ / enable | `01 01 01 00` persist + PEQ on (WH L188); `01 01 en slot 00` = enablePEQ (WH L445), disable = `01 01 00 00 00` | none | MUTATING (flash) |
| 0x02 | mic gain | read `80 02 00`, write `01 02 02 lsb msb` | | read-only / mutating |
| 0x11,0x16,0x19,0x1B,0x1D | DAC filter, balance, gain mode, denoise, work mode | see WH L520-671 | | extras, not needed |

### Filter packet (read reply and write payload share the layout, VERIFIED)
| d[] | field |
|---|---|
| 0..3 | `80 09 00 00` (read) / `01 09 18 00` (write) |
| 4 | band index 0..7 |
| 5..6 | 00 00 |
| 7..26 | biquad: 5 x int32 LE, Q30 (x 2^30): b0, b1, b2, -a1, -a2 (normalised by a0), RBJ cookbook at fs = 96000 (WH L742-837) |
| 27..28 | freq u16, Hz (register value, already compensated) |
| 29..30 | Q u16 = round(Q x 256) (8.8) |
| 31..32 | gain s16 = round(dB x 256) (8.8) |
| 33 | type: 1 LSQ (low shelf), 2 PK, 3 HSQ, 4 LP, 5 HP (WH L249, L430) |
| 34 | 00 |
| 35 | slot / preset index (write: modelConfig.defaultIndex or slot from getCurrentSlot) |
| 36 | 00 END |

- Our Python port of computeIIRFilter reproduced all 8 stored biquads **bit-exact** from the stored
  freq/Q/gain registers, so the firmware stores what the host computed (devicePEQ comments suggest the
  chip may recompute from the metadata fields; unproven either way - always send both, consistent).
- LP/HP: devicePEQ computes the PK formula for the biquad even for LP/HP (only LSQ/HSQ have own branches).
  Likely wrong or ignored by firmware - unverified.
- Disabled band (devicePEQ): write all-zero biquad + freq 0, Q 0, gain 0, type PK (WH L144, L282).
  Read: band is "disabled" if freq is 0 or 0xFFFF, or Q 0; all-0xFF = never written (WH L403-418).
  There is no per-band enable bit. Gain 0 dB PK is acoustically neutral.

## Limits (SchemeNo11, PC L262-281, UC L690-700)
8 bands; gain -10..+10 dB; Q 0.1..10; types PK, LSQ, LP, HP. **No high shelf confirmed**
(`supportsHSFilter: false`), even though type code 3 exists in the shared handler. Pregain:
`deviceHandlesPregain: false` - the host computes it and writes CMD 0x03 as an integer dB (rounded!),
range int8 (UI practical range <= 0). No BP/notch/allpass.
Contour 1.6.0 offers LOW PASS (code 4) and HIGH PASS (code 5) on the Protocol Micro and the Moondrop Echo-A (same scheme), with the
coefficients designed for 93 750 Hz (see "Measured Q conventions" under the KA15). The Micro's DAC takes a positive preamp, but the level does not rise,
so Contour stays at 0 dB and below. Down to the floor the level follows the register 1:1: preamp -10, -20 and -30 dB measured -10.00, -20.00 and
-30.00 dB at a 48 kHz stream (dac-lab run 2026-11-25-crinear-protocol-micro-08-mac-max-volume), and a full send with the commit keeps the preamp
over an unplug (run -09: -23 dB written from a phone, read back on a Mac). MAX VOLUME (Contour 1.8.1, the Micro's DAC page) uses only this: it is a
fixed cut added to the preamp register of every send (`01 03 02 00 g`, the ordinary write sequence), clamped at -30 dB; no other frame is involved.

## Contour does NOT use the compensation below (decision 26.09.2026)
CrinEar's own tool - devicePEQ v0.20 on graph.hangout.audio/plugins/devicePEQ (walkplayHidHandler.js
Last-Modified 02.07.2026) - writes freq/Q raw (biquad and metadata) and reads them raw. With the compensation,
a Contour band at 6900 Hz showed as 7058 Hz there (Reddit user report, 26.09). The 2.2-2.5 % offset was measured
on another SchemeNo11 device (EPZ TP13), never on the Protocol Micro. Contour now writes and reads raw values.
v0.20 differences Contour keeps on purpose: v0.20 writes the PK biquad for every type (shelves become peaks in
the coefficients; Contour writes real shelf biquads) and writes the preamp as min(0, preamp + 5)
(`globalGainBuffer: -5`, removed in 0617f382 as not present in the official WalkPlay app); Contour writes the
preamp register as is. PK biquad bytes are identical to v0.20 (test `bands reach the wire as set...`).

## Compensation (SchemeNo11 only; `freqCompensation {ratio 0.9775}`, `qCompensation {cosNyquist, designFs 96000}`)
Applied on write AND inverted on read (CO; WH L281-303, L379-398). Order on write:
1. `f_sent = clamp(f_user / 0.9775, 20, 20000)`; written as `trunc(f_sent)` (JS `>>`), biquad uses the unrounded float.
2. `ratio = cos(pi * min(f_sent/96000, 0.5))`; for PK/LSQ/HSQ only: `q_sent = clamp(q_user / ratio, 0.1, 10)`.
   LP/HP: no Q compensation.
3. Read: `f_user = f_raw * 0.9775`; `q_user = round(q_raw/256, 2) * cos(pi * f_raw/96000)`.
- Rationale in WH L263-280: SchemeNo11 lands filters 2.2-2.5 % low (measured on EPZ TP13), Q realised lower
  towards Nyquist. It is a fixed factor, independent of the streamed sample rate ("96 kHz" is the firmware's
  design rate, not the stream rate). SchemeNo16 (Protocol Max) has no such offset (its DSP plays the registers at the stream rate, see the Protocol Max section).
- Compensation can be disabled globally in devicePEQ (verification bypass) - then raw values are shown.
  The app should store the user's intent and treat compensation as a per-scheme option.

## Sequences
- Connect: `open()` (no command) -> **getCurrentSlot**: VERSION (wait <= 2 s for d[1]=0x0C), bulk PEQ read
  (wait for d[1]=0x09, slot = d[35]).
- **pullFromDevice**: listener on; for i in 0..7: send `80 09 00 00 i 00`, sleep 50 ms; sleep 100 ms;
  poll until 8 bands or 10 s timeout (partial result allowed); then global gain read (100 ms timeout).
  VERIFIED: every reply arrives 4-8 ms after the request; whole read 520 ms, dominated by the 50 ms pacing.
- **pushToDevice** (WH L132-191): for each band: filter write, 20 ms; then 100 ms; global gain write, 50 ms;
  then `01 05`, 20 ms, `01 17`, 20 ms, `01 0A 04 00 00 FF FF`, 50 ms, `01 01 01`.
  No replies are awaited for writes. **The commit sequence persists to flash** (comment WH L178-181:
  "to persist the registers while leaving PEQ enabled"). devicePEQ has no volatile write path; whether
  band writes without the commit take effect live is unknown (write_test.py `--mode volatile` tests exactly that).
  After writing, devicePEQ does not re-read automatically (`disconnectOnSave: false`, device stays open).
- Slots: WalkPlay default config exposes one writable slot "Custom" id 101 (UC L676-684); the SchemeNo11
  group does not override it. Observed slot byte = 0 in every reply. Treat the slot byte as "echo what you read".

## CrinEar Protocol Max (3302:43CC, supported in Contour 1.3.0; unit measured on a Mac 10.11.2026)

Hardware facts are from one unit read and written from a Mac in dac-lab runs `2026-11-10-crinear-protocol-max-01-mac` to `-14-mac-settings` (the run ids below are those folders). A phone run (below) proved the EQ write and persistence and the filter and balance settings.

- Allow-list: **3302:43CC only** (devicePEQ `walkplayHidHandler.js` L9, `usbDeviceConfig.js` "Protocol Max"),
  recognised in every build type; the USB attach filter lists it next to the Micro. No VID-wide or name
  matching; no other SchemeNo16 PID.
- Identity (run 01-mac): manufacturer "CrinEar Technology", product string "Protocol Max", bcdDevice 0x0001, HID interface 0 (usage page 12, usage 1) with report 0x4B of 64 bytes (input and output) on the
  vendor usage page 0xFF01, a report descriptor of 74 bytes; USB audio output only (2 channels), no input channels (so the mic gain row is vendor parity only). Firmware read through the VERSION command: "1.4".
- SchemeNo16 / `peq10Band10dBFullShelves`: **10 bands** read strictly on the unit (the community report of 8 bands on a 1.2.2 beta build, 02.10.2026, does not match it), PK/LS/HS on their native codes
  2/1/3, raw frequency and Q (no compensation), no Micro HIGH SHELF -> LOW SHELF + preamp emulation, devicePEQ's write sequence and commit as on the Micro. Pregain is computed by the host and
  written with CMD 0x03 (`deviceHandlesPregain: false`); Contour keeps its -30..0 dB whole-dB policy (the register took -30..0, run 08-mac).
- Factory state (run 01-mac, 02-mac): slot byte 0, preamp 0 dB, ten PK bands at 0 dB with register Q 182/256 on 31 / 62 / 125 / 250 / 500 / 1000 / 2000 / 4000 / 8000 / 16000 Hz: the TRN Black Pearl's factory flat.
- **Preamp is whole dB on the unit** (dac-lab runs `2026-11-11-crinear-protocol-max-25-mac-preamp-fraction` and `-26-mac-preamp-fraction-timed`, firmware 1.4, asked by a user report of 11.11.2026). The
  write payload `01 03 02 lo hi` is a little-endian int16 of dB x 256 (devicePEQ's `00 g` is g whole dB) and the reply to `80 03 00` is `80 03 02` + an int32 LE of dB x 256 (-3 dB answers `00 fd ff ff`;
  devicePEQ reads only its second byte as int8). The unit takes the fractional byte but **rounds to the nearest whole dB, a half going up**: sent -3.5 (`80 fc`) read and played -3.0, -3.6 (`66 fc`) -4.0,
  -2.25 (`c0 fd`) -2.0, -0.5 (`80 ff`) 0.0, each separated by a whole -2 dB; read-backs at 0.1-3 s and the 1 kHz level through the UMC202HD agreed within 0.001 dB with the whole value (whole -2 / -3 and the
  -4 of `66 fc` played -2.0 / -3.0 / -4.0 dB against 0 dB). So Contour keeps whole dB and its own round-down: a fractional write gives no finer step and a half dB comes out 0.5 dB louder than asked.
- What the DSP plays (acoustic runs at a 48 kHz stream, 10-mac-eqtest to 13-mac-eqtest-shelf): the DAC plays the **registers** (frequency, gain, Q) at the **stream rate** and not the coefficient bytes. A shelf plays
  **Q = register / sqrt2**, exactly as the TRN: Contour stores intended Q x sqrt2 (`peq.shelfQScale: "sqrt2"`) and keeps designing the bytes for the intended Q at 96 kHz (`peq.coefficientRateHz`), which are not played.
  Four shelf cases fit within 0.07 dB with that convention and the biquad designed at the stream rate (the old scale-1 convention was off up to 2.2 dB, and a 96 kHz design 1.5 dB). Peaks, LOW PASS and HIGH PASS
  each play as their own type within 0.1 dB (run 12-mac-eqtest-max).
- Types and range: LOW_PASS and HIGH_PASS are native codes **4 and 5** (written and read back as such, run 07-mac; played, run 12). Gain registers at **+-12 dB** are kept (run 08-mac) and -12 dB cuts and +9 dB
  peak and shelf boosts play (runs 12, 13). Offered in every build since the phone run (`ProtocolMax.CANDIDATES_VERIFIED` true): LP and HP, +-12 dB (the record's `peq.types` and `peq.gainDb`), and shelf boosts whose coefficient bytes
  overflow signed Q30 (e.g. HS +9 dB Q 0.71 at 10 kHz), which are sent wrapped to int32 as devicePEQ's quantizer and byte split (`value >> (8 * i)`, `walkplayHidHandler.js` ~L820-837) send them instead of
  being refused: the unit plays the registers, so the bytes do not matter. The read-back still compares registers only. A shelf at the top of the Q range with a deep cut (Q 7.07 at -10 dB or lower) has no finite
  biquad at all and is blocked before anything is sent, wrapped or not, as on the TRN.
- Reads: a band is checked against the register format only (the TRN's tolerance: 1-48000 Hz, +-60 dB, any type 1-5); a Max holding +-12 dB bands or LP/HP loads, a band outside the offered ranges comes in with a
  warning and sending it back stays blocked (run 09-mac: the strict read failed with "Malformed registers" on a unit holding +-12 dB). VERSION, bulk slot, all ten bands and the preamp must still parse
  (64-byte, read direction, index, header) before the first write. A send writes all ten slots, echoes the bulk-read slot byte, commits as on the Micro, then reads everything back; any mismatch, including the
  slot byte, is failure, not ON DAC.
- Settings (the WalkPlay web tool's DAC Filter tab frames, same as the TRN, `research/trn-settings-walkplay-2026-10-27/FINDINGS.md`; reads `80 11`, `80 1D`, `80 19`, `80 16 02 00 00` / `80 16 02 01 00` all answer;
  run 14-mac-settings): FILTER `01 11 01 t` (t = index + 1; NON-OS lowers 20 kHz by 2.6 dB, SLOW-LL changes nothing below 20 kHz), BALANCE `01 16 04 c 00 a 00`
  (1.00 dB per step, channel 1 = the right one, the TRN's convention). The unit read filter 1, work mode 1, gain mode 1 (High) and balance 0 / 0. WORK MODE `01 1D 01 t` (0 = Class-H, 1 = Class-AB as on the TRN) was written
  1 -> 0 and back and read back each time (T14 run 03, run 14); the bench rig at line level showed no level or noise difference between the two modes, which does not prove the mode does nothing. All were restored after the runs.
  Owner rule 10.11.2026: Contour offers at least every setting the vendor's tool offers (devicePEQ extras for `peq10Band10dBFullShelves`: dacFilter, dacBalance, dacWorkMode, gainMode, micGain). On the phone (run 22-phone)
  FILTER and BALANCE were kept after an unplug and are verified (`writeVerified` true); WORK MODE is verified too (`WalkPlaySettings.MAX_WORK_MODE_VERIFIED` true: Class-H written from the app on the phone, read back and
  kept after an unplug at 23:42). **GAIN is not a setting of the Max**: the unit has a physical Eco / Boost switch that sets the gain mode and wins after every replug (the vendor's tool sends the same `01 19 01 m` with no save), so Contour has no
  GAIN row for it and never sends `01 19` to it. MIC GAIN is offered for vendor parity (devicePEQ extras list micGain for this profile; owner 10.11.2026): read `80 02 02 lsb msb` (the unit answered mic 0), write `01 02 02 lsb msb`, range -15..+15 whole dB (the vendor's): a signed 16-bit little-endian value, t = round(dB x 32767 / 15) clamped to +-32767, +15 = 32767,
  -15 = 32769 (0x8001), a negative value as two's complement - **but Contour clamps t to +-0x7F00 (+15 -> `00 7f`, -15 -> `00 81`)**: the unit stores the value in steps of 0x80 and rounds, and full scale wraps (Mac sweep, run 22-phone `raw/mic-sweep-mac.txt`: -14..+14 read back right, devicePEQ's +15 `ff 7f` read `00 82` = -15, its -15 `01 80` read `00 7e` = +15; the step-aligned 0x7F00 and 0x8100 read 15 and -15, twice each); read dB = value x 15 / 32767 rounded to the nearest whole dB (the unit may quantise). Source: devicePEQ master's `setMicGain` / `readMicGain` (`walkplayHidHandler.js`; its header
  confirms the mic gain on SchemeNo11 and SchemeNo16, the Protocol Max). In the full 64-byte reply `4b 80 02 02 lsb msb` the value is buf[4], buf[5] (the byte after `80 02` is a length 02; the two bytes after the value are stale sign
  extension). The WalkPlay-capture encoding of the TRN (`01 02 02 80 e`, e as int8) stored wrong values on the unit (phone, perf f18f8fee: 10 read back VERIFIED, -1 read 0, 2 read 3, 5 read 6, -3 answered `02 00 fd ff ff`, -11 answered `02 80 f5 ff ff`:
  the unit keeps a 16-bit value and quantises it), so the Max uses devicePEQ's encoding. The unit exposes no audio input, so the setting has no audible path on this DAC.
  **The write needs the flash save**: written alone, mic -7 was lost after a replug (read 0); followed by the WalkPlay web tool's `01 01 00` (200 ms later) it read -7 and was kept over an unplug and replug, and the EQ kept
  playing unchanged (a PK 1000 Hz -12 Q2 marker measured identical before and after the save and after the replug, within 0.002 dB) - dac-lab run `2026-11-11-crinear-protocol-max-23-mac-mic-save`. So on the Max a mic write is
  `01 02 02 lsb msb`, `01 01 00`, then the read-back. On a Max `01 01 x` is also devicePEQ's EQ enable / flash frame, so the guard lets `01 01 00` through only as that save after a mic write and refuses it everywhere else.
  The row is verified (`WalkPlaySettings.MAX_MIC_VERIFIED` true: offered in a release).
- Phone run (`2026-11-10-crinear-protocol-max-22-phone`, Nothing A065, perf APK built from the devices branch): cold read 626 ms (firmware 1.4); a full edge profile (LS 20 Hz +12 Q0.71, PK 100 Hz -12 Q10, PK 500 Hz +12 Q0.1,
  PK 1000 Hz -6 Q2, HS 20000 Hz -12 Q4, HS 8000 Hz +9 Q0.71 sent wrapped, LP 18000 Hz, HP 20 Hz, PK 20000 Hz +12 Q10, PK 3000 Hz +3 Q1, preamp -12) was sent from the app: "write: VERIFIED", ON DAC - SAFE TO UNPLUG; after
  an unplug and replug all ten band registers and the preamp read back byte-identical. The DAC page read its four rows in 169 ms; GAIN Low, FILTER SLOW-PC and BALANCE L4 were each written from the app and read back
  VERIFIED. After a replug FILTER and BALANCE were kept and **GAIN came back HIGH**: the unit's physical Eco / Boost switch sets the gain mode and wins after every replug, which is why the app has no GAIN row for the
  Max. The unit was restored to its factory state afterwards.
- Off on the Max: A/B (TEMP_WRITE is only proven RAM-only on the Micro) and the service screen's band-1 test write; its mic gain row is vendor parity only (no audio input on the unit).
- Behaviour to know: in one Mac run (03-mac) the first band frame of a write was dropped on 2 of about 19 writes and on none of about 60 later ones (identical frames passed in 07-mac); the strict read-back
  caught it each time. Right after a USB stream opens the output is about 26 dB quiet for the first sweep (10-mac-eqtest), so an acoustic test opens the stream before its first measurement.
- Hardware test of the community: a member connected a Protocol Max on 02.10.2026 (Max beta build, 1.2.2-maxbeta1): peak and shelf filters, no crashes (they saw eight bands, see above). A small pop when sending is
  normal - the dongle saves the EQ to its memory. Not checked: A/B (stays off). The Protocol Micro path is unchanged from 1.2.2.

## TRN Black Pearl (3302:43E8)

Hardware facts: `research/black-pearl-pc-2026-10-19/FINDINGS.md` (written and read back from a PC on 19.10.2026).

- Allow-list: **3302:43E8 only**, recognised in every build; the USB attach filter lists it. SchemeNo16 like the Max
  (devicePEQ `peq10Band10dBFullShelves`): 10 filters, +/-10 dB, Q 0.1..10, PK/LS/HS on native codes 2/1/3, raw
  frequency and Q (no compensation, no Micro HIGH SHELF emulation). Preamp is whole signed int8 dB (CMD 0x03);
  Contour keeps its -30..0 dB policy, not a proven limit of the unit.
- Enumeration: USB product string "TE-C". **The unit does not enumerate at all without headphones plugged in**
  (nothing on the bus, not even a failed device); with headphones it appears. Interface 0 is HID (one collection,
  usage page 0x0C usage 0x01, report 0x4B), interface 1 is audio. Without headphones Contour sees nothing and shows NO DAC.
- Factory state (firmware 0.6): bulk slot byte 0, preamp 0 dB, ten PK bands at 0 dB, register Q 182/256
  (0.711) on 31 / 62 / 125 / 250 / 500 / 1000 / 2000 / 4000 / 8000 / 16000 Hz. The stored biquads fit RBJ at
  **96 kHz** (Q 0.75 in that state; 44.1, 48 and 192 kHz do not fit), the design rate Contour uses. The firmware does
  not recompute coefficients: what is stored is what runs. Contour's spare slots are exactly this factory flat.
- **Write sequence** = `write_bank` of FreeDSP-Studio (`tools/app/src/dsp2.rs`, MIT, (c) 2026 EffectiveEquivalent, commit
  1b42f63; see THIRD_PARTY_NOTICES.md), scheme 16:
  1. every band `4B 01 09 21 00 i 00 00` + biquad (20) + freq u16 + Q*256 u16 + gain*256 s16 + type + `00` + slot,
     25 ms after each. **The length byte is 0x21**, not devicePEQ's 0x18. Slot byte = the bulk-read slot (0 here).
     Biquads are Contour's own (96 kHz, Q30); the unit stored those bytes unchanged.
  2. commit `01 0A 04 00 00 FF FF`, 50 ms
  3. preamp `01 03 02 00 g`, 50 ms
  4. `01 01 00` (EQ flash), 200 ms
  5. `01 04 00` (offset and preamp flash), 200 ms
  Not devicePEQ's commit (`01 05`, `01 17`, `01 01 01`). All ten bands are written on every send; unused ones are the
  factory flat above. Read-back compares every register of all ten bands, the preamp and the slot byte.
- Proven on the unit (registers and every biquad byte identical after the write): LOW SHELF, HIGH SHELF and peaks across the
  caps, PK 500 Hz **-10.4 dB Q 0.1 stored as sent** (no firmware clamp at +/-10 dB; the TRN's own tool stores -10.4 too),
  and the **pass filters: LOW PASS 18 kHz is code 4, HIGH PASS 30 Hz code 5, both kept as sent** (not coerced to PK).
  Pass filters are NOT offered for the TRN in 1.6.0 (they stay out until they are proven on the unit); a read still accepts codes 1 to 5 so a state written by the TRN's tool does not stop a send.
- Persistence: unplug and replug with the headphones kept in: the whole state came back identical, **the preamp
  (-12 dB) included**, after `01 04 00`. Not tested: whether the preamp also persists without `01 04 00`.
- Read: VERSION, bulk slot, ten band requests, preamp, as the Max. The preamp reply is read as the int8 at d[4], like devicePEQ and
  FreeDSP-Studio; the two bytes before it are not checked on the TRN (the Max keeps `02 00`). Firmware 0.6 answers `80 03 02 00 g`;
  a tester's unit on **firmware 0.5** answered `80 03 02 32 01` (09.11.2026, adb log), and until 1.6.5 the 0x32 stopped every read and
  every send with "Invalid preamp header". Why that byte was 0x32 (another tool's write or the firmware) is not known. A stored band is checked against the register format
  (frequency 1..48000, gain +/-60 dB), not the +/-10 dB caps; disabled or erased slots (freq or Q 0, type 0 or 0xFF) are
  no read failure. FROM DAC loads an out-of-cap band with its real value and a warning (sending it back stays blocked).
  A band that did not come back is asked for once more, alone, after draining the input (on 19.10.2026 a stale reply
  made band 1 go missing once).
- Off on the TRN: A/B (TEMP_WRITE is proven RAM-only on the Micro only) and the service screen's band-1 test write.
- DAC settings (WalkPlay's "DAC Filter" tab, captured from peq.szwalkplay.com on 27.10.2026, `research/trn-settings-walkplay-2026-10-27/FINDINGS.md`).
  Reads: filter `80 11` -> d[3] 1..5, work mode `80 1D` -> d[3] (0 Class-H, 1 Class-AB), output gain mode `80 19` -> d[3] (0 Low, 1 High),
  balance `80 16 02 00 00` / `80 16 02 01 00` -> d[3] channel, d[5] attenuation byte (0 or 256 - steps). Writes, no reply, no flash save:
  filter `01 11 01 t` (1 FAST-LL, 2 FAST-PC, 3 SLOW-LL, 4 SLOW-PC, 5 NON OS), work mode `01 1D 01 t`, gain `01 19 01 m`, balance two frames
  `01 16 04 01 00 a1 00` + `01 16 04 00 00 a0 00` (channel 1 then channel 0). **Channel 0 is the left one, channel 1 the right one** (owner's listening
  test: the vendor slider at L attenuates channel 1 and the right ear goes quiet). The vendor's slider has 15 steps each side, no unit.
  The vendor's own centre pair `01 16 04 00 01 00 00` + `01 16 04 00 00 00 00` does **not** clear channel 1 (read back 15 after it); Contour sends
  two explicit zero frames for the centre. All four rows survived an unplug without `01 01 00`. On every connect the vendor page writes
  `01 03 02 00 00` (preamp 0 dB) before its first read.
- Contour on a phone (Pixel 9 Pro, 19.10.2026): recognised, HID interface 0 with IN 0x86 / OUT 0x05, read in about
  400 ms; a five-band profile with preamp -6 dB sent VERIFIED, and a PC read afterwards showed the same state.
- Audible response: measured on the test rig on 25.10.2026, 12 of 12 cases (see "Measured Q conventions" under the KA15).

## Other WalkPlay groups (for extensibility)
Same handler/wire format for SchemeNo10-21 (WH L7). Differences are config only:
- SchemeNo10 (default): 8 bands, PK only.
- SchemeNo16 (incl. CrinEar Protocol Max, UC L940-950): 10 bands, +-10 dB, LS+HS, no freq/Q compensation, pregain via CMD 0x03 as well; extras gain mode 0x19.
- SchemeNo15: 8 bands full shelves; SchemeNo18 (NiceHCK Octave): 10 bands full shelves.
- Note PID 0x4302 appears in both SchemeNo11 and SchemeNo16 lists - first match (No11) wins in devicePEQ.
- Group match is by VID in the WalkPlay vendor list (incl. 0x3302) + PID in the group list; model-name entries override.

## Android mapping (UsbManager / UsbDeviceConnection)
- Claim only the HID interface (interface number 3, class 0x03); never touch the audio interfaces 0-2.
  `claimInterface(intf, true)` detaches the kernel usbhid driver from that interface only - volume
  buttons (report 0x03) stop working while claimed; release afterwards.
- Endpoints are NOT visible from Windows (hidapi exposes no endpoint descriptors). At runtime iterate
  `intf.getEndpoint(i)`: if an endpoint with type `USB_ENDPOINT_XFER_INT` and direction `USB_DIR_OUT`
  exists, send the 64-byte report (`4B` + 63 bytes) with `bulkTransfer`/`UsbRequest` on it; otherwise use
  SET_REPORT: `controlTransfer(0x21, 0x09, (0x02 << 8) | 0x4B, 3, buf64, 64, 1000)` (report type 2 = Output;
  include the report ID as the first data byte, as in a numbered report).
- Input: the interrupt IN endpoint (mandatory for HID). Read with a queued `UsbRequest` / `requestWait`
  or `bulkTransfer(epIn, buf, 64, timeout)`; buffer[0] = report ID (0x4B or 0x03 - filter on 0x4B and d[1]).
  Fallback GET_REPORT (0xA1, 0x01, (0x01<<8)|0x4B) is not what devicePEQ uses; avoid unless IN fails.
- Keep devicePEQ pacing (>= 20 ms between writes, 50 ms between band reads is generous; replies take ~8 ms,
  so request-reply lock-step without fixed sleeps should read all 8 bands in < 100 ms - untested).

## FiiO KA15 (2972:0104, supported in Contour 1.3.2)

Captured from FiiO's official web app (fiiocontrol.fiio.com, WebHID hook, 03.10.2026) and verified on a Pixel
and on Windows with a real KA15 (03-04.10.2026).

- Transport: HID interface 0, report ID 7, 64-byte reports. Exact match on `2972:0104`.
- Frame: `HDR1 HDR2 00 SEQ CMD LEN DATA... CRC EE`; `HDR` = `bb 0b` (get) or `aa 0a` (set). SEQ = host counter,
  +1 per frame. CRC = CRC-8/MAXIM (poly 0x31, reflected, init 0) over `HDR1`..last data byte. The KA15 also
  answers SEQ=0/CRC=0 frames (devicePEQ style). Replies arrive in a reused buffer: bytes after `EE` are stale,
  so parse by `LEN`, never to the end of the report.
- Commands: `0x15` band (index + 7 bytes), `0x16` active preset, `0x17` global gain, `0x18` filter count
  (always 10; a lower count is ignored, so unused bands are written as 0 dB peaks), `0x19` save,
  `0x30` USER slot name.
- Slots: USER1-3 = presets 7-9; stock presets 0-6 and "Close EQ" (10) are never written. `0x19` saves the
  *active* preset whatever its slot byte says, so writing another USER slot needs `aa 0a .. 16 01 <slot>`
  first. Contour switches only when the chosen slot is not active, like the official app on save.
- Save reply is malformed (`.. 00 ee 00 SEQ`, no `aa 0a` header); the KA15 is busy for about 2 s after a save.
  Band echoes round Q up by one or two steps, so read-back allows that slack.
- Names (missing upstream): read `bb 0b 00 SEQ 30 01 IDX CRC ee`, IDX 0-2 = USER1-3. The reply carries a
  9-byte field, but only the first 7 bytes are the name; bytes 8-9 can be the tail of the previous frame.
  Write `aa 0a 00 SEQ 30 08 IDX <name NUL-padded to 7> CRC ee` (a shorter, unpadded write leaves the old tail).
  Names are A-Z and 0-9. Renaming does not touch the EQ.
- On Android the KA15 answers HID only while a USB audio stream is open (on Windows the FiiO driver keeps it
  open). Contour plays a silent AudioTrack routed to the KA15 for each read or write session, without audio
  focus, so other playback continues.
- 04.10.2026 on the Pixel: ten saves with and without a preset switch, nine answered and read back; once
  the save got no reply and every later query timed out until a replug (the switch had stuck, the save had
  not). Not reproduced.

### Measured acoustic behaviour (04.10.2026)
Sweeps 10 Hz-22 kHz through the KA15 to a line input, each result minus Close EQ, median of 4 runs
(repeatability 0.06-0.08 dB), registers read back over HID, current firmware.
- LS/HS shelves play Q = register Q / sqrt(2) for every type, frequency, gain and Q tried (LS 80 Hz +4 dB 0.70 -> 0.501,
  HS 1 kHz -1.2 dB 1.40 -> 0.978, LS 200 Hz +10 dB 1.00 -> 0.708, HS 2 kHz -10 dB 2.00 -> 1.412): fit error 0.010-0.017
  dB rms, against 0.12-0.82 dB with the register Q. devicePEQ's shelf slope law is wrong here (0.588 dB at HS 2 kHz).
- PK bands play the register Q (factor 0.986), without gain-dependent bandwidth (Q/A law 0.107 dB, register Q 0.041 dB).
- With any USER preset active, output = input - 12 dB + preamp register (checked at -6, -4, -3, -1, 0, +12). Preamp
  +12 with flat bands at -1 dBFS equals Close EQ within 0.1 dB, without extra harmonics; a +12 dB shelf at preamp 0 does not clip.
- Contour writes shelf Q register = Q x sqrt(2) and preamp register = preamp + 12 dB (shown range -24..0 dB, shelf Q up
  to 7.07), so squig.link/AutoEQ profiles play as designed. FiiO's app and web app show the raw registers.

### Measured Q conventions and coefficient rate on other DACs (24-25.10.2026)
Same rule as the KA15 (owner precedent 04.10.2026): Contour corrects the measured behaviour at the register boundary (register = intended x scale, the editor and
every comparison stay in the intended domain); the vendor tools show the raw registers. UMC202HD rig, stream 44.1 kHz, runs in `dac-lab/runs`; models in
`dac-lab/contour-1.6/research/2026-10-25-shelf-law.md`, `2026-10-25-dsp-model-k13-allegro.md`, `2026-10-24-dsp-model-walkplay.md`. Records: `peq.shelfQScale`,
`peq.peakQScale`, `peq.coefficientRateHz` (devices/README.md).
- FiiO K13 R2R, 25.10.2026 (`2026-10-25-fiio-k13-r2r-02-mac-eqtest`): a PK plays RBJ with Q = register / sqrt(2) (6 of 6 cases within 0.136 dB; the register as Q: 0.55-0.98 dB).
  Shelves, LP, HP, BP and AP play the register as Q. Contour writes the PK register = Q x sqrt(2) (Q 2.00 -> 2.83; PK Q range 0.07-7.07).
- SnowSky Melody and Tiny A, 24.10.2026 (`2026-10-24-snowsky-melody-05-mac-eqtest`, `2026-10-24-snowsky-tiny-a-05-mac-eqtest`): shelves play Q = register / sqrt(2) like the
  KA15 (Melody 12 of 12 cases with it, 8 of 12 with the register as Q; Tiny A 7 of 7 and 6 of 7), PK plays the register as Q. Contour writes the shelf register = Q x sqrt(2)
  (Q 0.70 -> 0.99; shelf Q range 0.07-7.07).
- TRN Black Pearl, 25.10.2026 (`2026-10-25-trn-black-pearl-01-mac-eqtest`, 12 of 12): shelves play RBJ with Q = register / sqrt(2), computed by the DAC from the f / gain / Q
  registers at the stream rate; the coefficient bytes Contour sends in the same frame are not played (a fit with them at 96 kHz is 0.35 dB worse). PK plays the register as Q.
  Contour writes the shelf register = Q x sqrt(2) and still designs the coefficient bytes for the intended Q.
- CrinEar Protocol Micro and Moondrop Echo-A, 24.10.2026 (`2026-10-24-crinear-protocol-micro-04-mac-eqtest` and `-05-`, `2026-10-24-moondrop-echo-a-03-mac-eqtest` and `-04-`):
  the DSP plays the coefficient bytes, but at 93 750 Hz (96 000 x 125/128), while the vendor tool (`computeIIRFilter(..., a = 96e3)`) and Contour designed them for 96 000 Hz, so every
  filter landed 2.34 % low (PK Q5 up to 0.88 dB, LP 8000 0.56 dB). Coefficients designed for 93 750 Hz fit every valid case within 0.057 dB. Shelves play the slope form Contour uses
  (not rescaled). Contour designs the Micro's and the Echo-A's bytes for 93 750 Hz; the TRN, Max and Dawn Pro 2 stay at 96 000 Hz (not measured, or not played). Not checked at a 48 kHz stream.
- Kiwi Ears Allegro Mini, 25.10.2026 (`2026-10-25-kiwi-ears-allegro-mini-07-mac-eqtest-48k`, a 44.1 kHz stream despite the name, and `-08-` at 48 kHz): the DSP designs its RBJ biquads for a fixed 24 000 Hz and runs them at the stream rate, so a band plays at register f x stream / 24 000 (x1.84 at 44.1 kHz, x2.00 at 48 kHz; gain and Q as written; free fits 24 061 and 24 143 Hz). The vendor tool writes the UI frequency as it is. Contour assumes a 48 kHz stream (owner decision 25.10.2026) and writes register f = intended f x 24 000 / 48 000 (1000 Hz -> 500), converting a read back; the editor offers 40-20000 Hz (register 20-10000; a register below 20 was never written). A player that forces 44.1 kHz shifts every band about 8 % down. Open: a Q 5 band whose register sits at 6000 Hz plays wider than the model.
- Moondrop Dawn Pro 2, 24.10.2026 (MOONDROP Hub Beta 1.6.5 "Write Cfg" captured on the owner's unit, firmware 1.5, USB 35D8:011D; `2026-10-24-moondrop-dawn-pro-2-04-mac`, result `-05-mac`
  and `-06-mac`): the Hub does not check the EQ index. It sends `01 0F 01 07`, eight band frames (`01 09 21 00 i ...`, last byte the bank tag 07), `01 0A 04 00 00 FF FF`, `01 23 02 lo hi`,
  `01 03 02 lo hi`, `01 01 00`, 30-65 ms apart; the unit ignores the switch and stays on index 9, and the bands of bank 9 took the written values and kept them over a replug. Contour writes this
  sequence (global gain written back as read, the pre-gain from the profile) when the unit is on one of Contour's banks (EQ index 7 or 9; on a preset index nothing is sent) and verifies by reading the eight bands back, whatever index the unit reports. The unit's Global Gain register is ignored.

## FiiO K13 R2R (2972:0120, supported in Contour 1.4.1)

Measured on the owner's K13 R2R on a Pixel 9 Pro, 10.10.2026 (firmware 1.15, USB product "FIIO K13 R2R").

- Transport: the KA15 frame format and commands (`0x15` band, `0x16` active preset, `0x17` global gain,
  `0x18` filter count, `0x19` save), report ID 7 with 33-byte reports in and out, HID interface 3. The K13
  answers without a USB audio stream and does not echo writes, so Contour sends no keep-alive stream.
- USB: the device reports two identical configurations; Contour opens the first one only. Report 7 is declared
  in a top-level Application collection on Generic Desktop with Usage 0x00 (Undefined) instead of a vendor
  page; Contour accepts that layout on the FiiO paths only.
- Slots: USER1-USER10 = presets 160-169 (`a0`-`a9`), BYPASS = 240 (`f0`), stock presets 0-6 and 8-10 are never
  written. Writing a slot makes it the active one. The K13 stores no USER names: FiiO's web app shows USER1-USER10
  without rename and sends no `0x30`, and a KA15-style `30 01 00` query from Contour got no reply and broke the next
  output report (14.10.2026). Never send name commands to the K13; Contour remembers the names on the phone.
- Filter count: a count of 9 reads back as 10 and band 10 keeps its old registers, so a shorter profile would
  leave the slot's old band 10 audible. Contour always writes ten bands and pads with 0 dB peaks, as on the KA15.
- Persistence: writes 16 ms apart all read back correctly, but after a power cycle only the first one to three
  survived (USER10: band 1; USER9: preamp and bands 1-2). With 300 ms after every write, preamp and all ten bands
  survived a power cycle on USER9 and USER10, both on advBeta and on stable 1.4.1.
- Acoustic result: see "Measured Q conventions" under the KA15 (25.10.2026): a PK plays Q = register / sqrt(2), shelves play the register as Q. Preamp offset 0, measured 13.11.2026 (run
  `2026-11-13-fiio-k13-r2r-04-mac-eqoff`): a flat USER1 with register 0 plays +0.09 dB against BYPASS, so the preamp
  register is written as shown.

## SnowSky Melody and Tiny A (2972:0126 and 2972:0147, supported in Contour 1.5.0)

Probed on the owner's units on the Windows PC, 13.10.2026 (Melody bcdDevice 1.20, USB product "SNOWSKY Melody";
Tiny A bcdDevice 1.10, "SNOWSKY TINY A"), then read, written and re-selected from a Pixel. Both run the same
firmware family and behave the same way.

- Transport: the KA15 frame format and commands, report ID 7, one HID collection (interface 0). Every write except
  SAVE echoes in about 4-12 ms; SAVE (`0x19`) answers with a short `00 EE` frame instead of an AA echo. Reply
  frames can carry stale bytes after the `EE` terminator, so parse up to the first `EE` only.
- Slots: USER1-USER3 = presets 160-162, Close EQ = 240, stock presets 0-6 are never written. Writing a slot makes
  it the active one.
- The slot query (`0x16`) is unreliable on a USER slot: after any band or preamp query or write it returns stale
  data (for example the last band index), and it is right again only straight after a select or a SAVE. Stock
  presets and Close EQ always answer correctly. Contour therefore always selects the destination slot and checks
  the slot right after the select and right after SAVE, never at the end of a read.
- Filter count: every slot holds ten filters (devicePEQ lists the Tiny A with 5). A count of 2 or 5 reads back as
  10 with the slot's previous bands still active, so Contour always writes ten bands and pads with 0 dB peaks.
- Read-back: preamp, frequency, gain and type match; Q 3.00 reads back as 3.01, so Q is compared with a small
  tolerance.
- Cold plug (Tiny A on a Pixel, 14.10.2026): with a USER slot playing, the first slot reply is a boot value (`00`) and
  after the band reads it says `09`. A stock preset answers the same both times, so a reply that changes from 0-6 to
  7-9 during one read means "a USER slot, number unknown" (Contour 1.5.0 failed that read; fixed in 1.5.1).
- USER names are stored in the DAC (FiiO web app capture on the Tiny A, 14.10.2026; Melody confirmed from a Pixel):
  read `bb 0b 00 SEQ 30 01 <slot> CRC ee` with the slot id (`a0`-`a2`), not the KA15 index. Reply LEN 9: slot id, 8 name
  bytes NUL padded, one stale byte. Write `aa 0a 00 SEQ 30 <1+n> <slot> <name, n <= 8, unpadded> CRC ee`; the echo carries
  the stored field, upper-case, with the old tail cleared. The FiiO app allows A-Z and 0-9 only. Contour reads the names
  after the slot and band reads (never between a select and its slot check). Details:
  `research/snowsky-names-types-2026-10-14/FINDINGS.md`.
- Filter type byte over USB (Tiny A and K13 R2R, FiiO web app, 14.10.2026): see "FiiO filter type byte" below.
- Selecting the already active USER slot again did not hang either unit.
- Preamp offset (measured 13.11.2026 against Close EQ, runs `2026-11-13-snowsky-melody-09-mac-eqoff` and
  `2026-11-13-snowsky-tiny-a-08-mac-eqoff`): the two units differ. The **Melody** plays USER presets 12 dB below Close
  EQ (a flat USER1 with register 0 at -12.00 dB), the KA15's law: Contour writes preamp register = preamp + 12 dB
  (shown range -24..0 dB, whole dB) since 1.8.0. The **Tiny A** plays a flat USER1 with register 0 at +0.004 dB against
  Close EQ: offset 0, the register is written as shown. Shelf Q scaling: see "Measured Q conventions" under the KA15
  (both play shelf Q = register / sqrt(2); the Melody's shelves at Q 2 and 3 play the Q form within 0.084 dB).

## FiiO DAC page and factory reset (KA15, Melody, Tiny A; Contour 1.6.0)

- The DAC page reads and writes the FiiO settings commands that the maker's app offers for the model: KA15 VOLUME, VOLUME STEPS, BALANCE, FILTER, OUTPUT, DRE,
  BRIGHTNESS, SCREEN OFF, ROTATION, LANGUAGE; Melody VOLUME, VOLUME STEPS, BALANCE, FILTER, GAIN, OUTPUT, DRE, IN-LINE MIC, LIGHT, BUTTONS; Tiny A VOLUME, VOLUME STEPS, BALANCE,
  IN-LINE MIC, LIGHT. USB AUDIO (UAC, setting 0x20) is read-only on all of them: switching it re-enumerates the DAC, so a read-back cannot close the loop. The K13 R2R has no settings list.
- Factory reset (KA15 and Melody) is the one place where FiiO command 0x21 is ever sent, and only after Contour has read the USER slots, their names and the settings and saved them on the phone.
  After the frame the DAC is read again only once it has been unplugged and plugged back in (the command does not restart the controller). The change list is the difference between the saved copy and
  the read after the reset; WRITE THE SAVED COPY BACK writes the slots, names and settings again, steps before volume. The Tiny A (firmware 1.2.0) ignores the command, so it has no reset in the app.

## Moondrop Dawn Pro 2 (35D8:011D, supported in Contour 1.6.0)

- Matched by VID:PID and the exact USB name `DAWN PRO2` (no space). The name `DAWN PRO 2` with a space is another device, routed as WalkPlay SchemeNo15 in advBeta, and is not matched.
- HID interface 0, report ID `0x4B` (64 bytes, vendor page 0xFF01). Frame after the report id: `OP CMD LEN payload`, OP read `0x80`, write `0x01`; values Q8.8 little-endian; band biquads for 96 kHz.
- Write sequence: the one MOONDROP Hub "Write Cfg" sends (captured 24.10.2026, Hub Beta 1.6.5, firmware 1.5): `01 0F 01 07`, eight band frames (`01 09 21 00 i ...`, last byte the bank tag 07),
  `01 0A 04 00 00 FF FF`, pre-gain `01 23 02 lo hi`, global gain `01 03 02 lo hi` (written back as read), `01 01 00`, 30-65 ms apart. Contour writes only while the unit's EQ index (register `0x0F`) is 7 or 9,
  and sends nothing while the unit is on one of its own presets; it verifies by reading the eight bands back. The unit ignores the Global Gain write.
- Filter types: PEAK 2, LOW SHELF 1, HIGH SHELF 3, LOW PASS 4, HIGH PASS 5. Shelves play Q = register / sqrt(2); measured on the test rig on 25.10.2026 (shelves, LOW PASS, HIGH PASS).
  8 bands, gain -18..+12 dB (the Hub's class defaults), Q 0.1-10, 20-20000 Hz; pre-gain register `0x23` -128..0 dB in 0.1 dB steps.
- Never sent: `01 05 01 04` (flash clear) and `01 FF` (firmware upgrade). No factory reset, no slot names, no hardware volume.
- The vendor tuning config (custom EQ) is switched on and off by holding both volume buttons for 2 seconds and, according to Moondrop's product page, is off after every plug-in. Whether register `0x0F` carries that state is not known.

## Moondrop Echo-A (3302:12C5, supported in Contour 1.6.0)

- WalkPlay SchemeNo11 through the Protocol Micro's code: HID interface 3, report ID `0x4B`, firmware 0.1, USB maker string "TTGK Technology". No vendor software lists the Echo-A.
- 8 bands, gain -10..+10 dB, Q 0.1-10, 20-20000 Hz (the Micro's range; no source names one for the Echo-A). PEAK, LOW SHELF, LOW PASS and HIGH PASS are written natively; HIGH SHELF is emulated as on the
  Micro (a LOW SHELF with the negated gain and the HIGH SHELF gain in the preamp, so in whole dB; Contour can lower the preamp to fit it, for example -6 to -9). Preamp up to 0 dB.
- The DSP runs the coefficient bytes at 93 750 Hz whatever the stream rate (checked at a 48 kHz stream, 25.10.2026), so Contour designs them for 93 750 Hz; shelves play the slope form.
- The microphone setting takes only some values and stays read-only. A positive preamp, settings, hardware volume and reset are not offered.

## Kiwi Ears Allegro Mini (31B2:0111, supported in Contour 1.6.0)

- KT Micro register protocol, HID interface 3, report ID `0x4B` with 10 data bytes. Matched by VID, PID and the exact USB name `Kiwi Ears-Allegro Mini`.
- Five bands from register `0x26` (two registers per band: gain and frequency, Q and type), EQ switch register `0x24` (3 = on, 2 = off), SAVE `0x53`, Global Gain register `0x3B`. PEAK only, -12..+12 dB, Q 0.1-10.
  There is no preamp: Contour accepts only 0 dB and refuses a profile with more than five bands, other filter types or a preamp, naming the reason. A send does not touch the EQ switch; the DAC page does.
- The DSP designs its biquads for 24 kHz and runs them at the stream rate; Contour assumes a 48 kHz stream and writes the register frequency = intended frequency / 2 (see "Measured Q conventions" under the KA15).
  The unit re-enumerates on USB for about 1.3 s after each save; Contour waits for it. Global Gain: -18..+3 dB in 1.5 dB steps.

## JCally JM12 (chip KT02H20, 31B2:0111, the same VID:PID as the Allegro Mini; supported in Contour 1.7.0)

Runs: `2026-11-10-jcally-jm12-01-mac` to `-11-phone` (dac-lab); the frames are replayed in `android/core/src/test/resources/bench/proven/jm12/`.

- Identity: VID 0x31B2, PID 0x0111, USB product string `KT02H20 HIFI Audio`, manufacturer `KTMicro`, bcdDevice 0x0001, HID interface 3 (one collection, usage page 0x0C), report ID `0x4B` with 10 data bytes. The record matches by the exact product name; the Allegro Mini (`Kiwi Ears-Allegro Mini`) shares the VID:PID and is told apart by that name alone.
- Same register protocol as the Allegro Mini: byte 0 register, byte 4 operation (`0x52` read, `0x57` write, `0x53` SAVE), value in bytes 6-9; a write is acknowledged with 3 in byte 6.
- Registers used:
  - `0x24` EQ switch: byte 0 = 2 off (factory), 3 on. Bytes 1-3 are device status that changes by itself (03 00 00 02 and 03 00 00 00 after saves, 01 once): a switch write sends `[eq, 0, 0, 0]` and a read-back is compared on byte 0 only.
  - `0x26`-`0x2F` five bands, two registers each: gain (i16 LE, tenths of a dB) and register frequency (u16 LE); Q (u16 LE, thousandths) and type byte (0 peak, 3 low shelf, 4 high shelf). Factory: 0 dB at registers 1000, 2000, 5000, 8000 and 10000, Q 0.707.
  - `0x3B` Global Gain: `[step, 0, 0, 0]` for steps 1-15, acknowledged and read back; the 1 kHz level follows it by about 1.5 dB per step (the Allegro Mini's table: 1-15 = -18..+3 dB, 13 = 0 dB). The unit ships at step 9 (about -6 dB). Written with SAVE it passed 9 -> 8 -> 9 (about 5.1 s per write including the bus drop).
  - `0x18`-`0x23` a second copy of the EQ (0x18 = 02, 0x1A-0x23 the five bands), read only, probably the saved copy; never written and not extra bands.
  - `0x66` digital gain: byte 0 took 0, -2, -6, -12 and -24 and read them back, the level did not change (under 0.01 dB): not a preamp, never written. `0x01` channel flags 00 02 00 00 (one DAC).
  - String registers: `0x04` firmware string `1.0.1` (0x08 and 0x0C hold the build date), `0x48` the product name; a further string register holds a serial: it is never read by Contour and nothing from it is kept.
- Re-enumeration: the unit leaves the USB bus after every SAVE and is back after about 0.8-3 s (devicePEQ's "no disconnect on save" is wrong for it). Twice in about 45 SAVEs with an audio stream playing it failed to come back properly (once it stayed off the bus, once it came back but answered no HID read) until it was unplugged and plugged in.
- Never sent: `0x43` (CLEAR flash; no reset is offered), report `0x54`, a write of `0x66`, a write of `0x3B` outside the Global Gain row.
- DSP: biquads designed for 24 kHz and run at the stream rate (register frequency = intended x 24000 / 48000 for a 48 kHz stream), shelf Q = register Q (scale 1.0). Played within 0.5 dB of the model for peaks at +-12 dB with Q 0.2-10 and for shelves up to +-6 dB at Q 0.71 and 1.0; peaks at Q 0.1 and shelves at +-9 to 12 dB were 0.7-1.0 dB off. The register accepted the whole vendor range (register 10-10000 Hz, +-12 dB, Q 0.1-10, both shelf codes); Contour offers the part that plays as drawn.

## FiiO JadeAudio JA11 (2972:0102, a KT02H20 dongle with FiiO's JA11 firmware; supported in Contour 1.7.0)

Runs (dac-lab): `2026-11-11-fiio-ja11-01-mac` (read only), `-02-mac` to `-09-mac-volume`; the frames of run 03 are replayed in `android/core/src/test/resources/bench/proven/ja11/`. The owner's unit: firmware 2.20 (command 0x0B reads `02 14`).

- Identity: VID 0x2972, PID 0x0102, USB product string `JadeAudio JA11`, bcdDevice 0x0220 on the Mac (0x0200 on Android). The record finds it by VID, PID and the exact name (`findDevice`); the 1.5 list (`find`) is unchanged.
- Transport: HID interface 2 with four reports: `0x01` (consumer), `0x4B` (10 data bytes, the KT Micro registers: never written here), `0x54` (the bootloader unlock: **never sent**) and `0x02` (20 data bytes, the FiiO frame protocol, 21 bytes with the ID). Android sees interrupt IN 0x83 and OUT 0x03.
- Frames: the FiiO layer of the KA15 on report 2: write `aa 0a 00 SEQ cmd LEN data CRC ee`, read `bb 0b 00 SEQ cmd 00 CRC ee`; the reply is `bb 0b 00 SEQ cmd LEN data CRC ee`. **The CRC is not required**: EQ frames go out with sequence 0 and CRC 0 (`pacing.checksum` false) and the unit answers and obeys them; the DAC settings frames carry sequence and the header-inclusive CRC-8/MAXIM of the web app and are obeyed too. The replies carry CRC-8/MAXIM over the bytes after the `bb 0b` header (12 of the 13 replies of run 01; the first reply after a long idle, the slot, carried a stale `c6`, later reads give `3e`): replies are never checked. Reads and writes are paced 200 ms (FiiO Control's interval); the unit does not echo a write.
- Commands used: `0x16` slot (read; write = select), `0x18` band count (5), `0x17` master gain (the preamp register: i16 in tenths of a dB; the register holds the intended preamp + 12 dB, see the DSP line), `0x15` band (read `bb .. 15 01 idx`; write `aa .. 15 08 idx gain2 freq2 q2 type`: gain in tenths, Q in hundredths, register Q = intended Q for peaks, shelves and pass filters), `0x19` SAVE (data = the slot), `0x02` volume (0-60), `0x12` in-line mic (0/1), `0x20` USB audio version (read only: switching it would re-enumerate the unit), `0x09` sample rate (read), `0x0B` firmware (read, two data bytes major and minor then the CRC).
- Never sent: report `0x54`, `0x21` (factory reset; none is offered), `0x1A` (EQ switch: the unit does not answer it) and `0x30` (names: no answer).
- Presets (`0x16`): 0 Vocal, 1 Classic, 2 Bass (stock), 3 USER1 (the only writable slot), 4 Close EQ (the bypass, the factory state). A choice is the select frame `aa 0a 00 00 16 01 N 00 ee` and one slot query; it switches at once with no USB drop (run 08). The stock presets' band records are readable and parse with real values (Vocal: 40 Hz -1.9 dB, 180 Hz -4.9, 600 Hz +5.0, 2200 Hz +6.0, 7000 Hz -2.9); Contour never writes or saves one. A send selects USER1 first (the unit sits on Close EQ from the factory), writes the preamp, the count and the five bands, then SAVE. A choice made with the select frame alone is not kept over an unplug: the unit comes back on the last saved slot (phone run 10); Contour offers USER1 only.
- SAVE (`0x19`) takes the unit off the USB bus for about 2.3 s; it comes back by itself and the registers then read back as written (devicePEQ's `disconnectOnSave` confirmed). The send is pending at the SAVE frame and verified by the first read after the return.
- The in-line mic write (`0x12`) re-enumerates the unit too (the mic input interface toggles; about 2.4 s a write, the echo is lost); the value counts after a read once it is back (`reconnectsAfterWrite`). Around a mic write the unit also reports a KT event on report `0x4B` (`53 00 03`, the firmware's own register write). The volume write (`0x02`) stays on the bus (about 1.1 s a write); 59 plays 1.02 dB below 60, at once, no reboot (run 09), although FiiO marks the row `adjust_reboot_tip`.
- Limits measured (runs 03, 06, 07): 5 bands, 20-20000 Hz, +-12 dB for peaks and shelves, Q 0.1-10 for every type, preamp register -12..+12 dB in 0.1 dB steps (intended preamp -24..0 dB, register offset 12 dB); the types PEAK 0, LOW_SHELF 1, HIGH_SHELF 2, LOW_PASS 4 and HIGH_PASS 5 are offered. BAND_PASS 3 is written and read back but plays flat (BP 1000 Hz Q 1.4 and BP 2000 Hz Q 2, checked twice) and ALL_PASS 6 plays flat, which cannot be told from an ignored type: neither is offered.
- DSP: the unit designs its biquads at about 192 kHz whatever the stream rate (against a 48 kHz model the peaks and shelves were 0.5-1 dB off above 6 kHz; against 192 kHz: PK +9 and -12 dB at Q 1 and 10 and Q 0.1 within 0.04 dB, five bands 0.04 dB, LP 4 kHz 0.06 dB, LS 80 Hz -12 dB with HS 10 kHz +9 dB 0.32 dB at 27 Hz). A shelf and a peak play Q = register Q (scale 1.0); the preamp follows the register 1:1 (register -6 dB within 0.08 dB, +9 dB plays) and USER1 plays 12 dB below Close EQ, the same law as the KA15: output = input - 12 dB + preamp register, so the register offset is 12 dB (runs `2026-11-13-fiio-ja11-01-mac-preamp`, `-02-mac-preamp-offset` and `-04-mac-preamp-fullscale`: a flat USER1 with register 0 plays 12.00 dB below Close EQ, register +12 plays at the Close EQ level, 0.00 dB at -12 and at -3 dBFS with no compression, PK 1 kHz +9 dB with register +3 peaks at 0.00 dB); shelves at Q 2 and 3 (LS 100 Hz +6 dB, HS 6 kHz +6 dB) play the RBJ Q form within 0.14 dB and the slope form is 4.7 dB off (run `2026-11-13-fiio-ja11-05-mac-shelf-highq`); HP 60 and 150 Hz play as high passes, steeper than a second-order RBJ filter only deep in the stopband (HP 150: within 0.05 dB above 168 Hz, -0.27 dB at 10 dB attenuation, -0.67 at 16, -1.4 at 22, -5.9 at 34 dB). Contour's graph draws every DAC at one rate (`Dsp.FS`, 96 000 Hz): there is no per-device drawing rate, so the JA11's curve is the intended one at 96 kHz.

## Moondrop DUSK-SP (35D8:1499, "gen 1" of FreeDSP-Studio, "Conexant" of devicePEQ; supported in Contour 1.7.0)

Not the Dawn Pro 2's 0x4B protocol. Sources: FreeDSP-Studio `tools/app/src/dsp.rs` and `hid_hidapi.rs` (MIT, (c) 2026 EffectiveEquivalent, https://github.com/EffectiveEquivalent/FreeDSP-Studio; our copy is a snapshot of 16.10.2026 without git metadata, `dsp.rs` sha256 `10fec0160677193f722d50f4c30a4dc7b5a30e629f5057cd4ee8910adb4165f7`; its author tested it on a DUSK-SP) and, for the unit, run `2026-11-11-moondrop-dusk-01-mac` (read only, the owner's cable). The Moondrop MAY (35D8:1497), FreeDSP (35D8:1496) and Echo-B speak the same protocol and are not in Contour.

- Identity: VID 0x35D8, PID 0x1499, manufacturer `MOONDROP`, product string `DUSK-SP`, bcdDevice 0x0001 (read). The record finds it by VID, PID and the exact name (`findDevice`; the 1.5 list `find` is unchanged). No firmware version command is known: `tested.firmware` is `unknown`.
- HID: interface 3, two top-level collections on the Consumer page 0x0C (usage 0x01). Report descriptor (read): `05 0c 09 01 a1 01 85 01 15 00 26 ff 00 09 00 75 08 95 3d 91 02 09 00 81 02 c0 05 0c 09 01 a1 01 85 02 15 00 25 01 75 01 95 02 09 e9 09 ea 81 02 95 02 09 cd 09 cf 81 02 95 04 09 00 81 01 c0`: report 1 Output and Input 61 bytes each (62 with the ID), usage 0x00 - NOT a vendor page, so `NativeHidReports.parse` (vendor pages only) drops it; this family has its own rule, `NativeHidReports.parseGen1` + `Shape.qualifyGen1` (report 1 Input and Output exactly 62 raw bytes, one collection). Report 2 is the inline volume keys (read-only, nothing is sent).
- Transport: **control pipe only**. A request is SET_REPORT(Output, id 1) (`bmRequestType 0x21, bRequest 0x09, wValue 0x0201, wIndex = interface`, 62 bytes) and then GET_REPORT(Input, id 1) (`0xA1, 0x01, wValue 0x0101`, 62 bytes). The cable does not answer on its interrupt endpoints. On the Mac (hidapi) that is `dev.write(frame)` and `dev.get_input_report(0x01, 62)` (the read run shows it works). Every frame is a request, writes included. In the app `HidTransport.requestControl` does it (no interrupt endpoint is claimed or required for this family); in the bench every frame is a `request` with `reply.via` `get-report` and `reply.getAfterMs`.
- Frame (62 bytes): `01 00 0d 00 CMD FLAG 00 23 2d b3`, then int32 little-endian dwords from byte 10, zero padding. A reply echoes `01 00 0d 00 CMD FLAG|0x80 00 23 2d b3` (a band reply has byte 2 = 0x06).
- Read the active preset: CMD 0x5a FLAG 1 `[0x5a]`; the reply has byte 4 = 0x5a, byte 5 = 0x81 and the index at dword 14: 0 Custom EQ, 1 DUSK-Default, 2 DUSK-V, 3 DUSK-Harman, 4 DUSK-Bass+, 5 DUSK-Diffuse-Tilted (names from FreeDSP-Studio). The owner's cable read 1.
- Read band n (1-9): CMD 0xdd FLAG 1 `[n]`; the reply has byte 4 = 0xdd, byte 5 bit 7 set, dword@10 = 5 (meaning unknown, always seen), dword@14 = n, freq Hz int32@18, Q x 256 int32@22, type int32@26 (0 PK, 1 low shelf, 2 high shelf), gain signed int24@30..32 in **whole dB**, and nothing after byte 33: the coefficients cannot be read back. The owner's cable: all nine bands PK, 0 dB, Q 1.0 (raw 256), at 300, 1000, 1500, 2000, 2500, 3000, 3500, 4000 and 4500 Hz (the factory Custom bank). A band reply is matched by CMD, the flag bit and n, so a stale reply of another band is never taken (byte 2 differs between a preset reply, 0x0d, and a band reply, 0x06).
- Select preset idx: CMD 0x5a FLAG 0 `[0x5a, idx]` ALONE (12 ms), then read the preset. FreeDSP-Studio's `set_preset` follows it with CMD 0xdc FLAG 0 `[0xff]`; **Contour does not send that commit**: it stores the Custom bank and restarts the cable onto preset 1 (below), so a select followed by it never survives. Measured (run `2026-11-11-moondrop-dusk-04-mac`, `select-nocommit-0.json`): a select without the commit switches the cable at once, keeps it on the bus and it still reads the same after 3 s and 8 s - the choice is RAM only. Contour never selects another index than 0-5.
- Measured on the unit 11.11.2026 (runs `2026-11-11-moondrop-dusk-02-mac` = the first s2, `-04-mac` = diagnostics, `-05-mac-replug` = the owner's unplug and plug): (1) every frame of a write is acknowledged with an echo of the request, byte 5 with bit 7 set (`01 00 00 00 dc 80 ...`, a select `5a 80`), and the meta read right after the commit equals what was written; (2) **the commit `dc 00 [ff]` stores the Custom bank to flash AND restarts the cable**: it leaves the USB bus about 650 ms after the commit's reply (664 and 645 ms in run 04) and is back after 0.5-0.7 s (away 1.2 s in all); (3) after every restart and every physical plug-in the cable plays preset 1 (DUSK-Default); the Custom bank is kept (meta byte-equal after the restart and after the owner's replug, run 05); a select of 0 followed by the commit does not survive the restart (run 04, `03-select-slot0`); (4) the preset choice is RAM only, and nothing known makes the Custom EQ the power-up preset.
- Write the Custom bank (FreeDSP `write_bank`): CMD 0x5a FLAG 0 `[0x5a, 0]` (6 ms); for i = 1..9 CMD 0xdc FLAG 0 `[0, i, freqHz, round(Q x 256), type, gain24]` (6 ms); then for each band i and each (code, fs) of (4, 44100), (5, 48000), (6, 96000), (7, 192000), (8, 384000) CMD 0xdc FLAG 0 `[code, i, 3, b0, b1, b2, -a1, -a2]` (6 ms): the biquad divided by a0, times 2^22, rounded half away from zero; then CMD 0xdc FLAG 0 `[0xff]` (10 ms), the commit: the cable stores the bank and restarts (see below). 56 requests. Contour's write ENDS there; once the cable is back (the app waits for it, the rig reconnects: `reconnect` {goneWaitMs 3000, backWaitMs 15000}) it sends CMD 0x5a FLAG 0 `[0x5a, 0]` with no commit (a RAM select of the Custom EQ, because the cable came back on preset 1), then reads the preset and the nine bands: the write is verified when the preset is 0 and the meta records equal what was written. Until then it is pending, as for a KT Micro that leaves the bus after SAVE. gain24 = the whole-dB gain as 24-bit two's complement in the dword (`0x1000000 + g` when negative). A write's acknowledgement echoes the request (measured); Contour logs every reply and checks only that a whole report came back; the proof of a write is the read-back of the nine meta records and the preset after the restart.
- The sound comes from the coefficients only; the meta record (freq, Q, type, gain) is a label the tools read back, so the gain on it is whole dB and the gain in the coefficients is exact (FreeDSP-Studio proved on hardware: metadata 0 dB with the coefficients of a -12 dB shelf reads back flat and the bass is gone). Contour sends the exact gain in the coefficients and the gain rounded half away from zero (Rust `f64::round`) in the meta record; read-back and ON DAC compare the meta records (type, integer Hz, Q x 256, whole-dB gain) of the cable with the ones the profile encodes. FROM DAC shows the meta records.
- Maths: RBJ peak and shelves with the shelf slope S = Q (`sa = sin(w0)/2 x sqrt((A + 1/A)(1/Q - 1) + 2)`): the same as `Dsp.biquad`, so the five coefficient sets come from it and the curve Contour draws is the curve it sends; `MoondropGen1Test` pins literal vectors for a peak and two shelves derived with an independent port of `compute_biquad`. The shelf form has no real response above a Q that falls with the gain (about 5 at +-12 dB): FreeDSP-Studio's formula gives NaN there and `as i32` makes it 0, Contour refuses such a band before sending (record `peq.q` 0.2-10 is the peak range).
- Bands: 9, fixed; a write always sends all nine. A band that is missing or disabled is the factory Custom band of its position (PEAK, 0 dB, Q 1.0 at 300 / 1000 / 1500 / 2000 / 2500 / 3000 / 3500 / 4000 / 4500 Hz), so an empty profile restores the factory bank. Types PEAK, LOW_SHELF, HIGH_SHELF; no pass filter code is known. Range from the sources until the Mac edge test: 20-20000 Hz, -12..+12 dB (devicePEQ `peq9Band12dBFullShelves`; FreeDSP-Studio -12..+3 by default, up to +12 in its "+6 dB mode"), Q 0.2-10.
- Slots: Contour writes only the Custom EQ (index 0), selects it first and leaves the cable on it; 1-5 are firmware presets, never written. On a firmware preset the bands that read back are the Custom bank, not what plays (the presets live in firmware): what plays is the preset's table (record `slots.presetBands`: Crinacle's published values, https://crinacle.com/2024/04/10/moondrop-x-crinacle-dusk-eq-dsp-values/ of 10.04.2024, the same as FreeDSP-Studio app.rs `STARTING`; all PEAK, no preamp), so the app shows that curve as the current profile, ON DAC, as if it had been sent (it reuses the library profile whose bands equal the table, else makes one named after the preset), and the bank read is never shown as playing then. A tap on a preset on the DAC page (the same RAM select) does that; so does the first read of a connection (the cable plays preset 1). The DAC page has a PRESET row (`slots.stockPicker`) that selects the Custom EQ or a firmware preset (RAM only, no commit) and reads the index back. The cable plays preset 1 after every restart and plug-in (record `slots.powerUpPreset`), so the app shows no notice text for it: the foot button of the Tune screen names the cable's active preset in capitals (CUSTOM EQ, DUSK-DEFAULT, ...) until a profile is picked, imported or edited after the last read or send (a tap opens the DAC page; after a touch it is HOLD TO SEND, and a send selects the Custom EQ again after the restart), and the DAC page lists the six presets as full-width buttons (`slots.stockPickerInline`)
- No preamp register: FreeDSP-Studio folds a preamp into one band's numerator, which cannot be read back, and there is no acoustic test for this cable, so Contour offers none (`preamp.present` false).
- Phone (run `2026-11-11-moondrop-dusk-09-phone`, perf build on a Nothing phone; hardware verified): cold read 134 ms; a nine-band edge send was VERIFIED after the restart; a write from the phone takes about 1.3 s for the 56 frames; the PRESET row (DUSK-Harman, Custom EQ, DUSK-Default) reads back in about 190 ms each; the owner's unplug and plug kept the bank and brought the cable back on preset 1; the RAM select of the Custom EQ after the plug-in 186 ms. The restart re-enumerates the cable, so Android asks for the USB permission again after each send (not after a physical replug); the app runs the RAM select of the Custom EQ once the cable is back and only then reads, so the read that follows the permission dialog (which finds preset 1) does not settle the write. dword@10 of a band reply read 5 on the Mac and 8 on the phone (it looks like the active sample-rate code, 48 vs 384 kHz; not used). Once in about a dozen Mac writes (run `2026-11-11-moondrop-dusk-07-mac`) the band frame right after the select of the Custom EQ was refused and the cable was found back on preset 1 with nothing written; a resend passed (run `-08-mac`).
- Restart (record `driver.restartsAfterCommit`, `pacing.restartLeavesAfterMs` 650, `restartAwayMs` [500, 700]): see the measured facts above; the Android re-enumeration may ask the USB permission again, as the JA11 does after SAVE.
- Pacing (record `pacing`, never faster than FreeDSP-Studio's gaps): the transport waits 12 ms between SET_REPORT and GET_REPORT of every request (FreeDSP sleeps 10 and 12 ms before the GET of a read and none before the GET of an acknowledgement), 6 ms after each band and coefficient write, 10 ms after the write's commit, 12 ms after a preset select.
- Acoustic check: not run - the DUSK-SP ends in 2-pin IEM plugs, so there is no 3.5 mm output for the rig's line input.
- Never sent: any CMD other than 0x5a, 0xdd and 0xdc, a band number outside 1-9, a preset index outside 0-5, an 0xdc code other than 0 (band), 4-8 (coefficients) and 0xff (commit, only inside a write). The same list is the bench's deny-list (`Gen1Narrator`), DacLab's `forbidden_frame` and `MoondropGen1Codec.requireSendable`.

## SnowSky RETRO NANO (0A12:4007, FiiO protocol on report 7; supported in Contour 1.8.0)

Runs (dac-lab, 13.11.2026): `2026-11-13-snowsky-retro-nano-01-mac` (read only) to `-12-mac-eqoff`; the Mac frames of run 03 that the current record replays are in `android/core/src/test/resources/bench/proven/retro-nano/`. A phone check on a Nothing A065 (perf APK 1.7.1 / 41) followed; it has no run id.

- Identity: VID **0x0A12** (a generic Bluetooth-chip vendor id, not FiiO's 0x2972), PID 0x4007, USB product string `RETRO NANO`, manufacturer string `FiiO`, bcdDevice 0x1970. The record finds it by VID, PID and the exact name (`findDevice`); the 1.5 list is unchanged. The identity is the same in the unit's PC mode and its Bluetooth-pairing mode.
- Transport: HID interface 3 on vendor page 0xFF00 holds the FiiO frames on report `0x07` (Output 65 data bytes, Input 65, 66 with the ID), plus reports `0x01` and `0x05` (Output 65), `0x02` (Input 65), `0x06` (Input 13) and Feature `3` and `4`, which Contour never uses; interface 2 holds a consumer collection and report `0x02` on page 0xFFA0 (64 bytes), also unused. Interface 3 has **one interrupt IN endpoint (0x89, 64 bytes) and no OUT endpoint**: the 66-byte report arrives as two packets of one transfer, so the transport takes the only IN endpoint when none is as large as the report, asks for the whole report, and sends by SET_REPORT. The first read on the phone then took 72 ms.
- Frames: the FiiO layer of the KA15 (write `aa 0a 00 SEQ cmd LEN data CRC ee`, read `bb 0b 00 SEQ cmd 00 CRC ee`; EQ frames go out with sequence 0 and CRC 0). The plain FiiO path, not the SnowSky exchange: slots 8 and 9 are real voicings here and the exchange path reads slot values 7-9 as a stale last-band index. Writes are paced 150 ms with no echo read (FreeDSP-Studio's pacing), SAVE does not drop the bus (`disconnectOnSave` false).
- Commands used: `0x16` slot (read; write = select), `0x18` band count (10), `0x17` preamp (i16, tenths of a dB), `0x15` band (read `bb .. 15 01 idx`; write `aa .. 15 08 idx gain2 freq2 q2 type`), `0x19` SAVE (data = the slot).
- Slots: USER1-3 = 160-162 (the only writable ones), stock voicings 0-6 (Jazz ... Hip-hop), 8 Retro, 9 sDamp-1, 10 sDamp-2, **Close EQ = 11** (not 240); the unit ships on Close EQ. Names are kept in the app (no name command is known); the app offers no DAC page: FiiO Control's config for this model is the Bluetooth one (16-bit command ids) and lists no USB HID setting.
- Quirk, **Close EQ answers the preamp query with a count frame**: on slot 11 the query `bb 0b 00 00 17 00 00 ee` is answered once, after about 6 ms, with `bb 0b 00 00 18 01 00 00 ee` (command 0x18, one data byte 0) instead of a 0x17 reply, and nothing else follows within 1 s. The record's `driver.bypassPreampAnswersAsCount` makes the session read that one frame, on the bypass slot only, as "no EQ active": preamp register 0 and no band queries; every other slot still needs a real 0x17 reply. A read on Close EQ is therefore slot, count, preamp, closing slot (`07 bb 0b 00 00 16 00 00 ee`, `... 18 ...`, `... 17 ...`, `... 16 ...`). A USER slot is read by selecting it (`aa 0a 00 00 16 01 a0 00 ee`) and reading it normally; the bench `select` puts the unit back on slot 11.
- Limits measured (runs 03 and 04): frequency 20-20000 Hz and gain -12/+12 dB held at the edges; a peak holds **Q 0.25, 0.3, 7 and 8**, while Q 0.1, 0.15, 0.2, 9, 9.5 and 10 are refused and read back as 0.71 (FiiO Control's 0.25-8); all seven types are written and read back; the preamp -12, -11, 0 and +12 dB holds in whole dB with register offset 0.
- DSP (stepped acoustic runs 10, 11 and 12 at a 48 kHz stream): the unit designs its biquads at **96 kHz** whatever the stream (an LP 8000 Hz was 14 dB off against a 48 kHz model, within 0.04 dB at 96 kHz; 192 kHz 1.8 dB, 384 kHz 2.2 dB); peaks and shelves play Q = register (scale 1.0: register / sqrt2 is 1.44 dB off); USER presets play at the Close EQ level, so the preamp register offset is 0 (a flat USER1 -30.331 dBFS against Close EQ -30.332 dBFS). PK mix 0.12 dB, shelves 0.08 dB, preamp -6 dB 0.007 dB, LP 8 kHz 0.04 dB, HP 100 Hz 0.09 dB, repeatability 0.005 dB. BAND_PASS (type 3) plays flat (BP 1000 Hz Q 1.4: 0.00 dB everywhere) and ALL_PASS (type 6) is flat in amplitude, which cannot be told from an ignored type: neither is offered; the record offers PEAK, both shelves, LOW_PASS and HIGH_PASS.
- The unit drops about one sample every 10 s on its USB output (a click), which discards whole multitone blocks: the acoustic test needs the stepped method.
- Phone (13.11.2026): the first read failed on the 64-byte IN endpoint until the transport change above; then slot 11 read in 72 ms, an edge profile to USER1 (LS 20 Hz +12 dB Q 0.25, PK 20 kHz -12 dB Q 8, LP, HP, HS 20 kHz -12 dB Q 5 sent as register Q 5.00, preamp -12 dB) read back VERIFIED, and after a replug (Android asks for the USB permission again) the cold read gave slot 160 with the preamp at -12 dB.
- Settings: none over USB. PC mode: Contour's settings reads (`bb 0b 00 SEQ cmd 00 CRC ee`, and the plain form with sequence and CRC 0) for 0x02, 0x0C, 0x07, 0x12, 0x06, 0x20, 0x03, 0x04, 0x05, 0x32, 0x0D-0x11, 0x09 and 0x0B got no reply within 1 s, while 0x16, 0x18 and 0x17 answered in 6 ms; PH mode: the same reads with sequence and CRC (without 0x0E-0x11) got no reply either, while 0x16 and 0x18 answered; in the BT mode the unit leaves the USB bus (run `2026-11-13-snowsky-retro-nano-13-mac-settings-read`). No frame is guessed.
- Never sent: reports `0x01`, `0x05`, `0x02`, `0x06` and the Feature reports, `0x21` (factory reset), `0x1A` (EQ switch) and `0x30` (names).

## FiiO JadeAudio JIEZI (2972:0151; FiiO protocol on report 7; supported in Contour 1.8.0)

Runs (dac-lab, 13.11.2026): `2026-11-13-fiio-jiezi-01-mac` (read only), `-02-mac` (s2), `-07-mac-eqtest-stepped`, `-08-mac-eqoff`, `-10-mac-settings`; the frames of run 02 that the record replays are in `android/core/src/test/resources/bench/proven/jiezi/`. A phone check on a Nothing A065 (perf APK 1.7.1 / 41) has no run id.

- Identity: VID 0x2972, PID 0x0151, USB product string `JadeAudio JIEZI`, manufacturer string `FIIO`, bcdDevice 0x0130. Found by VID, PID and the exact name (`findDevice`).
- Transport: one HID interface (0) with one collection and three reports: `0x03` (consumer), `0x4B` (Input and Output 63 data bytes on vendor page 0xFF01: **never sent**) and `0x07` (Input and Output 63 data bytes, 64 with the ID: the FiiO frames). devicePEQ's report 2 does not exist in the descriptor.
- Frames: the FiiO layer of the SnowSky Tiny A on report 7: write `aa 0a 00 SEQ cmd LEN data CRC ee`, read `bb 0b 00 SEQ cmd 00 CRC ee`; replies carry a sequence byte and a checksum. Every write except SAVE echoes; SAVE is answered with a bare `00 ee`; the slot query goes stale on a USER slot, so the unit takes the SnowSky exchange path (echoes consumed, 500 ms reply timeout), and the first slot reply after a cold plug is a boot value (9 on the phone, the family's known quirk).
- Commands used: `0x16` slot, `0x18` band count (**10**: devicePEQ's 5 is wrong for this unit, FiiO Control and the FiiO web app say 10), `0x17` preamp, `0x15` band, `0x19` SAVE, `0x30` slot names by slot id (8 characters; read and rewritten on the phone), and the DAC page: `0x02` volume (0-60; read 60), `0x0C` volume steps, `0x07` balance, `0x12` in-line mic, `0x20` USB audio (read only), `0x09` sample rate (read). No LIGHT row (the Tiny A has one).
- Slots: USER1-3 = 160-162, stock 0-6, Close EQ 240 (the unit was found on it; the preamp register reads 20 there).
- Limits measured (run 02): 14 edge writes with Q 0.1 and 10 read back, all seven types, the preamp -12, -11, 0 and +12 dB in whole dB with register offset 0.
- DSP and acoustics (stepped, -12 dBFS, 48 kHz stream; runs 07 and 08): the DSP runs at the stream rate, shelves play Q = register / sqrt2 (7 of 7 cases; Q = register is 1.51 dB off) and peaks Q = register; PK mix 0.17 dB, shelf mix 0.02 dB, preamp -6 dB 0.017 dB, LP 8 kHz 0.08 dB, HP 100 Hz 0.08 dB, **BAND_PASS 1000 Hz Q 1.4 0.11 dB (the unit plays BAND_PASS)**, ALL_PASS flat (0.004 dB), repeatability 0.019 dB; a flat USER1 plays -35.874 dBFS against Close EQ -35.876 dBFS, so the register offset is 0 (unlike the Melody and the JA11).
- The unit drops samples on its USB output (the engine counted 18 and 84 gaps; at -12 dBFS the multitone fit residual was -7 dBc, at -24 dBFS the tones sat under the noise floor of about -84 dBFS): measure it stepped.
- Settings (run 10 and the phone): volume 60 -> 59 -> 60, balance 0 -> 1 -> 0 and mic 1 -> 0 -> 1 were written, read back and restored (about 1.05 s a write); the mic was also written on the phone. Volume steps was not written; USB audio is never written.
- Phone (13.11.2026): the DAC page shows the record's rows; an edge profile to USER3 (LS 20 Hz +12 dB, PK 20 kHz -12 dB Q 10, BP 1 kHz, LP, HP, HS 20 kHz -12 dB Q 5 sent as register Q 7.07, preamp -12 dB) read back VERIFIED; the slot name was rewritten and read back; after a replug the first slot reply was 9 and the read after it gave USER3 with the preamp at -12 dB.
- Never sent: report `0x4B`, `0x21` (factory reset) and `0x1A` (EQ switch).

## Kiwi Ears AD1 (3302:20E3, chip KT02H20, the KT Micro register map of the JM12; supported in Contour 1.8.0)

Runs (dac-lab, 13.11.2026): `2026-11-13-kiwi-ears-ad1-01-mac` (s2), `-02-mac-eqtest`, `-03-mac-max`; the frames that the record replays are in `android/core/src/test/resources/bench/proven/ad1/`. A phone check on a Nothing A065 (perf APK 1.7.1 / 41) has no run id.

- Identity: VID **0x3302** (the WalkPlay vendor id, but this unit is not WalkPlay), PID 0x20E3, USB product string `AD1`, manufacturer string `Kiwi Ears`, bcdDevice 0x0001. Found by VID, PID and the exact name (`findDevice`). devicePEQ and FreeDSP-Studio list PID 0x20E3 in WalkPlay's "SchemeNo17" (5 bands, +-10 dB, shelves) without a handler: that catalog name belongs to a KT-chip dongle, and Contour drives the unit with the KT Micro path.
- Transport: HID interface 3, one collection on the consumer page 0x0C: report `0x01` (consumer), `0x4B` (Input and Output 10 data bytes on vendor page 0xFF01: the KT registers, 11 bytes with the ID) and `0x54` (Input and Output 10 bytes: **never sent**).
- Registers (the JM12's map, read on the unit): `0x24` EQ switch (02 off at the factory; bytes 1-3 are device status), bands `0x26`-`0x2F` (five bands, gain i16 tenths + register frequency u16, Q u16 thousandths + type byte: 0 peak, 3 low shelf, 4 high shelf; factory 0 dB at 1000, 2000, 5000, 8000 and 10000, Q 0.707 = 0x02C3), the copy at `0x18`-`0x23`, `0x3B` Global Gain (13 = 0 dB on the Allegro table), `0x66` = 00 02 (never written), `0x01` = 00 02 00 00; string registers `0x04` firmware text (`1.0.` then cut), `0x08` and `0x0C` the build date, `0x48` the product text `AD1`.
- Re-enumeration: the unit leaves the USB bus for 0.75-0.97 s after every write and the read after it matches (Mac); on the phone Android asks for the USB permission again after each SAVE (as with the JM12). Global Gain 13 -> 12 -> 13 was written, saved, read back and restored (5.6 s a write, the unit left the bus twice).
- Behaviour = the JM12's (run 03, stepped, -24 dBFS, 48 kHz stream): peaks +-12 dB with Q 0.2-10 within 0.5 dB (PK Q 0.2 0.31 dB, +9 dB Q 1 and -12 dB Q 10 0.50 dB at 6.3 kHz), shelves +-6 dB at Q 0.7-1.0 within 0.37 dB below 15 kHz with shelf Q = register (register / sqrt2 1.16 dB, register x sqrt2 1.47 dB, slope 1.16 dB off), five bands 0.47 dB, repeatability 0.04 dB. PK Q 0.1 (0.98 dB) and shelves of +9 and -12 dB (about 1.0 dB under every convention) were measured and are not offered, so the record holds q 0.2-10 and shelf limits +-6 dB at Q 0.7-1.0. The register frequency (intended x 24000 / 48000) held from 10 Hz. The multitone method fails on this unit (18 gaps, sample drops on its USB output): measure it stepped.
- Phone (13.11.2026): the cold read showed EQ OFF and Global Gain 13; an edge profile within the narrowed limits read back VERIFIED after the reconnect, the send turned the EQ switch ON (JM12 rule), a replug kept it, and the EQ switch OFF from the DAC page read back VERIFIED.
- Never sent: report `0x54`, `0x43` (CLEAR flash; no reset is offered), a write of `0x66`.

## Tanchjim SPACE II (3302:4381, WalkPlay SchemeNo16; supported in Contour 1.8.0)

Runs (dac-lab, 13.11.2026): `2026-11-13-tanchjim-space-ii-01-mac` (s2), `-02-mac-eqtest`, `-03-mac-eqtest`, `-04-mac-max`; the frames that the record replays are in `android/core/src/test/resources/bench/proven/space-2/`. A phone check on a Nothing A065 (perf APK 1.7.1 / 41) has no run id.

- Identity: VID 0x3302, PID 0x4381, USB product string `TANCHJIM-SPACE II`, manufacturer string `TANCHJIM`, bcdDevice 0x0001. Found by VID, PID and the exact name (`findDevice`), so the 1.5 list is unchanged. devicePEQ's `SchemeNo16` group (10 bands, +-10 dB, full shelves) and FreeDSP-Studio's scheme 16 list PID 0x4381; devicePEQ's `TANCHJIM-SPACE PRO` entry (peaks only, +-10 dB) is the sibling model, and no source describes the SPACE II itself.
- Transport: HID interface 0, one collection on the consumer page 0x0C: report `0x03` (consumer), `0x4B` (Input and Output 63 data bytes on vendor page 0xFF01, 64 with the ID: the WalkPlay frames) and `0x54` (Input and Output 63 bytes: **never sent**).
- Protocol: the CrinEar Protocol Max's WalkPlay code and devicePEQ write sequence (24-byte band frames, then preamp `01 03 02 00 g`, commit `01 05`, `01 17`, TEMP_WRITE, FLASH_EQ `01 01 01`), strict read-back with the Max's read bounds. Read on the unit: version `80 0C 00` -> `1.2`, bulk PEQ slot byte 0, bands 0-9 all PEAK (type 2) at 0 dB with register Q 0x00B6 (0.711) at 31, 62, 125, 250, 500, 1000, 2000, 4000, 8000 and 16000 Hz (the Max's and the TRN's factory flat), global gain `80 03 00` = 0. The preamp reply header is read the TRN's lenient way (int8 at d[4]). One slot (0, Custom).
- Limits measured (run 01 and 04): PK, LS, HS, LP and HP written and read back (T07); nine edge writes verified (T06; the planner refuses a high shelf at the lowest frequency with the largest gain and lowest Q before any frame: its coefficients overflow signed Q30); preamp -30, -29 and 0 dB read back in whole dB with register offset 0 (T08); cuts to -12 dB, Q 0.1-10 and LP/HP play beyond the catalog's +-10 dB (the engine caps boosts at +9 dB; +12 dB boosts were written and read back, not played).
- DSP (48 kHz stream, runs 02, 03 and 04): the DSP follows the stream rate (it fits a 48 kHz model, not a 96 kHz one): shelves play **Q = register** (scale 1.0; the Max's register / sqrt2 fits 3 of 4 cases with 1.16 dB, the slope form 1.18 dB, any hypothesis at 96 kHz is 6.9 dB off) and peaks Q = register. Run 03 (volume at maximum): PK 0.013 dB, shelves 0.010 dB, preamp -6 dB 0.024 dB (a played -6 dB: offset 0), LP 0.015 dB, HP 0.08 dB, repeatability 0.007 dB. Run 04 (-24 dBFS): LP 4 kHz 0.06 dB, HP 300 Hz 0.23 dB, PK +9 dB Q 1 and -12 dB Q 10 0.06 dB, PK -6 dB Q 0.1 0.04 dB, LS -12 dB and HS -9 dB 0.03 dB, ten bands 0.03 dB.
- Phone (13.11.2026): cold read of the ten factory bands, then an edge profile (LS 20 Hz -12 dB Q 0.1, PK 1 kHz +12 dB Q 10, LP 20 kHz, HP 20 Hz, HS 20 kHz -12 dB Q 5, preamp -30 dB) read back VERIFIED (write 823 ms, read-back 616 ms); after a replug the cold read gave firmware 1.2, slot 0 and preamp -30 dB (413 ms).
- Settings (the Max's and the TRN's frames, `WalkPlaySettings.SPACE2_VERIFIED`; runs `2026-11-13-tanchjim-space-ii-05-mac-settings-read`, `-06-mac-settings`, `-07-mac-settings-effect`): the reads `80 11`, `80 1D`, `80 19`, `80 16 02 00 00` / `80 16 02 01 00` and `80 02 00` all answer (filter 1, work mode 0, gain mode 1, balance 0 / 0, mic 0 on the unit). FILTER `01 11 01 t` (NON-OS lowers 20 kHz by 2.6 dB, SLOW-LL changes nothing below 20 kHz), WORK MODE `01 1D 01 t` (Class-AB against Class-H: no level difference at line level; on this unit 0 is CLASS AB and 1 is CLASS H, as the Tanchjim app names them - the opposite of the WalkPlay web tool, see `WalkPlaySettings.space2WorkMode`), GAIN `01 19 01 m` (LOW plays 6.16 dB below HIGH across the band), BALANCE `01 16 04 c 00 a 00` (1.00 dB a step, channel 1 the right one) and MIC GAIN with the Max's 16-bit encoding and the flash save `01 01 00` (the unit has no audio input; `SPACE2_MIC_VERIFIED` true since run `2026-11-14-tanchjim-space-ii-08-mac-mic-save`: with a PK 1000 Hz -12 dB Q2 marker on the EQ the save left the marker unchanged (1 kHz +0.002 dB, the band within 0.018 dB), -15..+15 read back right without the save, +15 `00 7f`, +14, -14 and -15 `00 81` read back with it and no wrap, and -15 and the marker were kept over an unplug and replug). On a phone (Nothing A065, perf build 41) all five were written from the app, read back, kept after an unplug and replug (the gain mode too: unlike the Max, no physical switch wins), BALANCE R 15 and L 15 were written and read back, and the factory state was written back. The FILTER names are the WalkPlay web tool's, not read from the Tanchjim app; the OUTPUT names follow the Tanchjim app (a user report of 22.11.2026: Contour's CLASS H showed as CLASS AB there and the other way round). UAC1 / UAC2, which the Tanchjim app shows, has no known frame and no row. Since 16.11.2026 a SET whose read-back differs is sent once more (with the mic's save) and read again, because the unit drops an output frame now and then (A/B below); the other WalkPlay DACs send it once, as their proven logs do.
- Settings from the Tanchjim app (`WalkPlaySettings.SPACE2_DRE_VERIFIED` and `SPACE2_VOLUME_VERIFIED`, 25.11.2026; source: TANCHJIM Android app 2.5.8.1, `Space2Command`, read for interoperability, 25.11.2026; every frame is a 64-byte report with report id `0x4B`, zero padded; value byte = buf[4] of the full reply, as for the other settings above). **DRE** (the CS431xx chip's Dynamic Range Enhancement, the app's "DAC DRE optimization"), command `0x32`: read `4B 80 32`, reply `4B 80 32 00 XX` with XX `0x00` = ON and `0x01` = OFF (**inverted**), any other value = no valid value; write ON `4B 01 32 01 00`, OFF `4B 01 32 01 01`; the app sends no save frame after it and neither does Contour. **VOLUME** (the DAC's own volume register, the app's "Current volume", which it shows as raw x 100 / 60 percent; Contour shows the raw number), command `0x85`: read `4B 80 85`, reply `4B 80 85 00 VV`, VV = raw volume 0..60 (`0x00`..`0x3C`); write `4B 01 85 01 VV`, then at once the app's flash save `4B 01 04` (command `0x04`, **not** the `01 01 00` save of the mic), then the read-back. `0x85` is above `0x7F`: every byte is read unsigned. `WalkPlayGuard` lets `01 32` and `01 85` through for the Space II only and `01 04` only as the save right after a Space II volume write; the Micro, the Max and the Echo-A refuse all three, and the TRN refuses the first two (its own write sequence ends with a `01 04 00`, offset and preamp flash, which stays). Not yet written to a unit: the Mac and phone checks follow with the 1.8.1 candidate; firmware 1.2 may not answer `0x32` / `0x85`, and a read with no answer shows NO REPLY for that row and leaves the other rows working.
- A/B: **off on the Space II** (16.11.2026: no RAM path; tried in review builds that day and removed). The candidate was the Micro's path: the bands that differ from flat, 20 ms apart, then TEMP_WRITE `01 0A 04 00 00 FF FF`, without the preamp, `01 05`, `01 17` and FLASH_EQ `01 01 01` (FreeDSP-Studio `tools/app/src/dsp2.rs` L589-591 calls `01 0A` the latch of staged bands and L674 the flash frames the persist step, commit 1b42f63). Phone runs (Nothing A065): 16 switches read back equal at about 300 ms; the unit dropped a band frame in 3 of 16 switches (twice the first frame after the interface was claimed, once the last before TEMP_WRITE) and a read request twice, so any write path to it needs a read-back and one resend. **Two replugs by the owner decided it:** (1) 17:21, A (LS 105 Hz +4, PK 1 kHz -3 Q 2, HS 8 kHz -2, HP 25 Hz, preamp -6) sent and VERIFIED, B as band frames + TEMP_WRITE, unplug and plug: the cold read held the B bands and preamp -6; (2) 18:25, B as the band frames alone (no `01 0A`, a temporary review hook that was never committed): the cold read again held B. The Space II stores a band frame by itself (as the FiiO K13 R2R and the BTR17 do), so no documented frame writes its EQ to RAM only and A/B would overwrite the saved EQ. Logs: `_Projects/contour-refs/ab-ram-2026-11-16/space2-replug-2026-11-16.log` and `space2-replug-bands-only-2026-11-16.log` (outside the repo). The app leaves B before any page but EQ (a swipe to the DAC page kept B until 16.11) and writes no DAC setting while B plays, for the Micro.
- Never sent: report `0x54`, `01 01 00` outside a mic save, the factory reset and the firmware commands.

## EPZ TP65 (3302:4325, WalkPlay SchemeNo16; supported in Contour 1.8.2)

Source: a TP65 owner's evidence from their own unit (firmware 0.4) and Contour's tester versions run on it, in [issue #6](https://github.com/Chronosauros/contour/issues/6). Contour's developer has no TP65 and nothing was measured; Contour sends only the frames validated on that unit.

- Identity (the TP65 owner's Windows evidence, issue #6 attachment `EPZ-TP65-Contour-Contribution.zip`, 25.11.2026): VID 0x3302, PID 0x4325, manufacturer string `EPZ Technology`, product string `TP65`, bcdDevice 0x0001. Found by VID, PID and the exact USB product name `TP65` (`findDevice`); the same PID with any other name matches nothing, because another product may use PID 0x4325. devicePEQ's `usbDeviceConfig.js` lists PID 0x4325 in its `SchemeNo16` group (L702; `peq10Band10dBFullShelves`: ten bands, +-10 dB, PEAK and both shelves, Q 0.1-10, `deviceHandlesPregain` false) and FreeDSP-Studio's `tools/app/src/dsp2.rs` (MIT) in `Scheme { no: 16, bands: 10, gain: 10.0, types: SHELVES }` (L145); no source names the TP65 itself.
- Transport: HID interface 0 (MI_00 on Windows), usage page 0x0C, 64-byte input and output reports with report ID `0x4B`; on Android interface 0, IN endpoint 0x86, OUT 0x05 (1.8.0-tp65test2).
- Reads, on every connect (any reply that does not parse stops the read): version `80 0C 00` (`0.4`), the slot byte `80 09 00` (0), the ten bands `80 09 00 00 i 00` and the preamp `80 03 01`. The bands read on the unit: all PEAK at 0 dB with register Q 0x00B6 at 31, 62, 125, 250, 500, 1000, 2000, 4000, 8000 and 16000 Hz, the coefficients those of a 96 kHz RBJ peak at Q 0.75. The preamp is read with `80 03 01` (`DeviceProtocol.preampRequest`), the form read on the unit, not the `80 03 00` of the other WalkPlay DACs; its reply `80 03 02 00 00` is 0 dB, and its header is read the Space II's lenient way. On Windows the TP65 owner also read filter `0x11`, work mode `0x1D`, output `0x19` and balance `0x16`; Contour reads and writes none of them.
- Write (the TP65's own sequence since 1.8.1-tp65test3, `WalkPlayProfiles.TP65_TESTER_WRITE` true and `WalkPlay.tp65WriteSequence`; 1.8.2 writes it exactly as 1.8.1-tp65test4 did): the same full read first, then only the bands whose registers differ from it, each `01 09 18 00 i 00 00` + 20 coefficient bytes + freq, Q, gain, type, `00`, slot, with 20 ms after each band frame (120 ms after the last); then the RAM apply `01 0A 04 00 00 FF FF` and 250 ms; then the same full strict read-back, compared register by register with the plan. A band that reads back different ends the send NOT VERIFIED and is never sent again by itself. Driver: report ID `0x4B`, scheme 16, 24-byte band frames, coefficients designed for 96 kHz, strict parse and strict import, no A/B.
- Never sent: the preamp write `01 03`, `01 05`, `01 17` and the flash `01 01 01`; none of them was ever tried on a TP65. A send whose preamp differs from what the DAC holds (0 dB on the unit) is refused before the first write frame. The TP65 owner saw an edited band still read after a USB replug with no flash command (on Windows, and on Android with 1.8.1-tp65test3), as the Space II keeps band frames without one (A/B session, 16.11.2026).
- `WalkPlayGuard` holds the TP65 to an allow-list in every context: the reads `80 0C 00`, `80 09 00`, `80 09 00 00 i 00` and `80 03 01`, the band write `01 09 18 00 i 00 00` and the RAM apply `01 0A 04 00 00 FF FF`. Everything else (the preamp write, `01 05`, `01 17`, the flash `01 01 x`, `01 04`, settings, factory reset, firmware, probes) is refused. No DAC settings: the record has no settings rows, so Contour sends no gain, filter, work mode, balance or mic frame and never report `0x54` to this unit.
- Record: the catalog's range and types on the Space II's read code and conventions (a measured unit of the same scheme). 10 bands (fixed), PEAK (type 2), LOW SHELF (1) and HIGH SHELF (3), -10..+10 dB, Q 0.1-10, 20-20000 Hz, 96 kHz coefficient design; preamp -30..0 dB in whole dB with register offset 0, read only; one slot (0, Custom). LOW_PASS, HIGH_PASS and +-12 dB (taken by the Space II and the Max) stay candidates for a measured unit.
- Shelf Q: **not measured**. The record keeps the Space II's convention (the Q register is the shelf's Q, `peq.shelfQScale` 1.0, so the Q field holds the Q the editor shows and the coefficient bytes are designed with), while the Protocol Max and the TRN Black Pearl play a shelf at register / sqrt2. Which one the TP65 plays, and whether it plays the registers or the coefficient bytes, only a measurement of its output can tell.
- Tester versions on the unit (issue #6 comments): 1.8.0-tp65test1 offered HOLD TO SEND through the Space II's sequence and was withdrawn. 1.8.0-tp65test2 (read only) passed on 26.11.2026: all ten bands and the preamp equal to the Windows reads byte for byte, the same after a replug. 1.8.1-tp65test3 passed on 26.11.2026: five PEAK sends (1000 Hz at -1 and -6 dB, 250 / 1000 / 4000 Hz in one send, a change to +1 dB, back to flat), each VERIFIED with only the changed bands and one RAM apply sent; the Android read-back, the read after a replug and the Windows read equal byte for byte, coefficients included; two read timeouts recovered, no write retry; the -6 dB cut sounded as one to the TP65 owner. 1.8.1-tp65test4 added the two shelves and passed on 27.11.2026: LOW SHELF 100 Hz and HIGH SHELF 4000 Hz at -6 dB, Q 0.71, then flat, each VERIFIED, the band frames byte for byte as planned, no preamp, `01 05`, `01 17` or flash frame. Before them, on Windows, the TP65 owner wrote one PEAK band (`01 09 18`, -1 dB at 1000 Hz) with the RAM apply, read it back right and still read it after a replug with no flash command; two reads there timed out (1200 ms) with no known cause.
- Never written to the unit: gains outside -6..+1 dB and the edges of the record's ranges (-10..+10 dB, Q 0.1-10, 20-20000 Hz, the catalog's). Never measured: positive-gain playback and the DSP output after a Contour write, as nothing on this unit was measured.

## FiiO filter type byte (band write `aa 0a .. 15 08 <idx> <gain2> <freq2> <q2> <type>`)

| Type | Byte | KA15 | K13 R2R | SnowSky Tiny A | SnowSky Melody |
|---|---|---|---|---|---|
| PEAK (PK) | 0 | capture 03.10.2026 | capture 14.10.2026 | capture 14.10.2026 | Contour sends (hardware 13.10.2026) |
| LOW SHELF (LS) | 1 | capture 03.10.2026 | capture 14.10.2026 | capture 14.10.2026 | Contour sends (hardware 13.10.2026) |
| HIGH SHELF (HS) | 2 | capture 03.10.2026 | capture 14.10.2026 | capture 14.10.2026 | Contour sends (hardware 13.10.2026) |
| BAND PASS (BP) | 3 | Contour sends (hardware, 1.6.0) | capture 14.10.2026 | capture 14.10.2026 | Contour sends (hardware, 1.6.0) |
| LOW PASS (LP) | 4 | Contour sends (hardware, 1.6.0) | capture 14.10.2026 | capture 14.10.2026 | Contour sends (hardware, 1.6.0) |
| HIGH PASS (HP) | 5 | Contour sends (hardware, 1.6.0) | capture 14.10.2026; a read after HP returned `15 08 02 00 00 00 64 00 64 05` | capture 14.10.2026 | Contour sends (hardware, 1.6.0) |
| ALL PASS (AP) | 6 | Contour sends (hardware, 1.6.0) | capture 14.10.2026 | capture 14.10.2026 | Contour sends (hardware, 1.6.0) |

- Sources: FiiO's web app (fiiocontrol.fiio.com, WebHID hook) on the Tiny A (band 3, 0 dB, 100 Hz, Q 1.00, every write echoed) and on the K13 R2R fw 1.15 (USER10 band 3, same values; the K13 sends no write echo). Details: `research/snowsky-names-types-2026-10-14/FINDINGS.md`. The BLE handler's 3 = LP / 4 = HP does not apply over USB.
- Contour 1.6.0 sends BP/LP/HP/AP to all four FiiO-family DACs: the KA15, K13 R2R, Melody and Tiny A. FiiO's own app was captured offering them on the K13 R2R and Tiny A; for the KA15 and Melody the type bytes are the same family's and the types were checked on the DACs themselves.
- The gain register of a pass filter is written 0 and Contour keeps pass filters at 0 dB. A band read from a DAC with a pass type and a nonzero gain register is not loaded (the import names the band, type and gain). The test rig measures amplitude only, and ALL PASS changes just the phase, so for ALL PASS it shows only that the amplitude is left alone.

## FiiO roadmap note (config/handler only; written before the KA15 work above)
- FiiO is matched by VID 0x2972 (and 0x0A12) + **USB product name string**, not by PID (UC L16, L360-400).
  "FIIO KA15": supported, 10 bands, +-12 dB, 7 types (PK/LS/HS/LP/HP/BP/AP), writable user slots 7-9
  (USER1-3), "Close EQ" = 10. "FIIO K13 R2R": supported, 10 bands, -24..+12 dB, all FiiO types, user slots
  160-169 (USER1-10), BYPASS 240, stock presets 0-6, 8-10. PID 0x0120 is not referenced anywhere - the
  match depends on the device reporting product name exactly "FIIO K13 R2R" (not checked).
- Transport: WebHID, report ID 7 (default), frames `AA 0A ...` (set) / `BB 0B ...` (get), end `EE`;
  write = global gain, filter count, per-filter params (0x15), then save `AA 0A 00 00 19 01 slot 00 EE`
  (fiioUsbHidHandler.js L462-469). The handler does not itself block stock-preset slots; only the config's
  firstWritableEQSlot/maxWritableEQSlots describe the user slots - the app must enforce that.

## Verified on the device, 2026-09-25 (read-only so far)
- Fresh read identical to device-backup-2026-09-25.json byte for byte (step1-read.log, step1.json).
- Lock-step read (next request right after reply, 200 ms timeout, no 50 ms pause; timing_read.py, timing.log):
  3 runs, 56-78 ms for version + slot + 8 bands + preamp, max single reply 8-22 ms, no missing or garbled
  replies, band data identical to the backup. The Android port can drop devicePEQ's 50 ms pause.
- Write test NOT run: the session's permission classifier blocked the device-writing command
  ("Real-World Transactions"). Still open: whether volatile writes apply without the commit, whether
  writes get acks, write->read-back latency, and the commit round-trip.

## Ear test, 2026-09-29 (live_test.py, owner listening on the PC, music playing)
- The Micro's HID interface was MI_02 on this PC (MI_03 on 25.09): walkplay.py now matches usage page 0x0C.
- Band 1 toggled between the DAC value and PK 2000 Hz Q 0.7 -10 dB, 4 cycles x 4 s, restore verified by read-back.
- `--apply none` (band writes + preamp, no commit command): no audible change. Read-back echoes the registers
  anyway, so read-back does not prove the DSP applied a write.
- `--apply temp` (band writes + preamp + TEMP_WRITE `0x0A` only, no flash): the cut applied live on every switch,
  with light pops/clicks at the switches.
- Persistence: `--apply temp --leave-modified` left the cut on the DAC; after unplug/replug read_device.py
  showed the pre-test state (device-backup-2026-09-29-224718.json: same freq/gain/Q/type for all 8 bands,
  preamp -3 dB; biquad low bytes differ slightly, the DAC recomputes them). TEMP_WRITE is RAM-only.
- Click test (`--apply temp --cut gain`: only band 7's gain toggles 0 -> -10 dB, freq/Q unchanged), owner's
  ranking: best `--writes changed` (only the changed band + TEMP_WRITE, no preamp write); second `--writes all`
  (8 bands + preamp + TEMP_WRITE); worst `--writes changed --ramp 4` (gain stepped in 5 writes ~30 ms apart -
  each step is its own click). Softest live change: one band write + TEMP_WRITE, in one jump, no ramp.
- For the app (1.1.x always sends 8 bands + preamp + 0x05, 0x17, 0x0A, 0x01 = flash, only on Send): live
  preview, A/B and CLEAR EQ can send only the changed bands + TEMP_WRITE (RAM, no flash wear); Save keeps
  the full commit. After unplug the DAC falls back to the last saved EQ. Owner decision 29.09: build only
  A/B this way; live preview dropped, CLEAR EQ unchanged (ROADMAP.md).
