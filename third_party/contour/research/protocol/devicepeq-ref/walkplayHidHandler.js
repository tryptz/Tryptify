//
// Copyright 2024 : Pragmatic Audio
//
// Walkplay USB HID Handler
// ────────────────────────
// Shared protocol implementation for all Walkplay-chipset USB DAC/dongle devices.
// Used by SchemeNo10, 11, 13, 15, 16, 17, 18, 19, 20, 21 device groups.
//
// Example device: CrinEar Protocol Max (vendorId=0x3302, productId=0x43CC, SchemeNo16)
//   — 10-band PEQ (±10 dB, LS+HS), DAC filter, DAC balance, DAC work mode, gain mode
//   — Capture: tests/captures/walkplay_schemeno16_protocol_max.json
//   — Reference: tests/captures/walkplay_schemeno16_protocol_max_walkplay_site.json
//
// Protocol: reportId=0x4B (75), READ=0x80, WRITE=0x01, END=0x00
// Many thanks to ma0shu for providing a dump
//
// ── Core PEQ API (required by all connectors) ─────────────────────────────────
//   getCurrentSlot(deviceDetails)                       → slot id
//   pullFromDevice(deviceDetails, slot)                 → { filters, globalGain, currentSlot }
//   pushToDevice(deviceDetails, phoneObj, slot, gain, filters)
//   enablePEQ(deviceDetails, enable, slotId)
//
// ── Extras API (exposed via deviceExtras.js / plugin showExtras:true) ─────────
// Extras are scheme-gated in peqConstraintsConfig.json. The plugin renders a
// collapsible "Show Extras" panel that reads current values on first open and
// applies changes via Apply buttons.
//
//   CMD  Name            Schemes      Description
//   0x02 micGain         ALL          ADC/input gain ±15 dB, 16-bit signed (CB1300D hardware).
//                                     Confirmed on SchemeNo11 and SchemeNo16 (Protocol Max)
//                                     via walkplayPreprocessor/walkplay.js analysis.
//                                     setMicGain(deviceDetails, dB) / readMicGain(deviceDetails)
//
//   0x03 outputGain      ALL          DAC output gain register, 1 byte signed.
//                                     Written during pushToDevice when deviceHandlesPregain=false.
//                                     setOutputGain(deviceDetails, gainDb)
//
//   0x11 dacFilter       ALL          DSP interpolation filter algorithm:
//                                       1=FAST-LL  2=FAST-PC  3=SLOW-LL  4=SLOW-PC  5=NON-OS
//                                     setDacFilter(deviceDetails, type) / readDacFilter(deviceDetails)
//
//   0x16 dacBalance      ALL          Left/right channel amplitude trim.
//                                     Pass leftDelta>0 to boost left, rightDelta>0 to boost right,
//                                     both 0 to centre. Units are device-native (0–127).
//                                     setDacBalance(deviceDetails, leftDelta, rightDelta)
//
//   0x19 gainMode        SchemeNo16+  Alternative gain-processing mode (Low Gain / High Gain).
//                                     Boolean: false=Low Gain, true=High Gain.
//                                     setGainMode(deviceDetails, bool) / readGainMode(deviceDetails)
//
//   0x1B denoise         SchemeNo11   ENC/noise-cancellation circuit toggle.
//                                     setDenoiseEnabled(deviceDetails, bool) / readDenoiseEnabled(deviceDetails)
//
//   0x1D dacWorkMode     ALL          DAC operational mode: 0=Class AB, 1=Class H.
//                                     setDacWorkMode(deviceDetails, mode) / readDacWorkMode(deviceDetails)
//
// ── Global gain (CMD 0x03 / "offset") ──────────────────────────────────────────
// Written directly as the computed preamp value — no hardware buffer/offset is
// applied on top of it. Confirmed against the official WalkPlay web app source
// (walkplayJS/walkplay-online.js): the "offset" register is a plain user/preset
// value, default 0 in every stock preset, with no auto-gain or fixed attenuation.
//

import { logHidTx, logHidRx } from './deviceDebugLog.js';

import { compensateFreqForWrite, decompensateFreqFromRead,
         compensateQForWrite, decompensateQFromRead } from './compensation.js';

export const walkplayUsbHID = (function () {
  const REPORT_ID = 0x4B;
  const ALT_REPORT_ID = 0x3C;
  const READ = 0x80;
  const WRITE = 0x01;
  const END = 0x00;
  const CMD = {
    FLASH_EQ:    0x01,
    MIC_GAIN:    0x02,  // ADC/input gain, 16-bit signed ±32767 = ±15 dB (available on all schemes)
    GLOBAL_GAIN: 0x03,  // DAC output gain / global EQ offset, 1 byte
    PEQ_VALUES:  0x09,
    TEMP_WRITE:  0x0A,
    VERSION:     0x0C,
    GET_SLOT:    0x0F,
    DAC_FILTER:  0x11,  // DSP filter algorithm: 1=FAST-LL, 2=FAST-PC, 3=SLOW-LL, 4=SLOW-PC, 5=NON-OS
    DAC_BALANCE:  0x16,  // Left/right channel balance
    GAIN_MODE:    0x19,  // Alternative gain-processing mode toggle — boolean (0=off, 1=on). SchemeNo16/Protocol Max confirmed.
    DENOISE:      0x1B,  // ENC/noise-cancellation toggle
    DAC_WORK_MODE: 0x1D, // DAC operational mode (0=normal, 1=alternate)
  };

  const DEFAULT_FILTER_COUNT = 8;

  const getCurrentSlot = async (deviceDetails) => {
    const device = deviceDetails.rawDevice;
    if (!device) throw new Error("Device not connected.");

    // Register listeners BEFORE sending so responses are never missed.
    // (On real hardware latency is long enough that send-then-listen works,
    // but registering first is the correct pattern and required for testing.)

    // Get the version number first
    const versionResponsePromise = waitForResponse(device, CMD.VERSION);
    await sendReport(device, REPORT_ID, [READ, CMD.VERSION, END]);
    var response = await versionResponsePromise;
    const versionBytes = response.slice(3, 6);
    const version = String.fromCharCode(...versionBytes);

    console.log("USB Device PEQ: Walkplay firmware version:", version);
    const versionNumber = parseFloat(version);

    if (isNaN(versionNumber)) {
      console.warn("Could not parse firmware version:", versionNumber);
      deviceDetails.version = null;
    }

    // Save version number to deviceDetails
    deviceDetails.version = versionNumber;

    console.log("Fetching current EQ slot...");

    const slotResponsePromise = waitForResponse(device, CMD.PEQ_VALUES);
      await sendReport(device, REPORT_ID, [READ, CMD.PEQ_VALUES, END]);
    response = await slotResponsePromise;
    // Slot is at byte 36 in the full HID packet (including report ID).
    // Web HID strips the report ID, so it's at index 35 here.
    const slot = response ? response[35] : -1;

    console.log("Walkplay current EQ slot:", slot);
    return slot;
  };

  // Push PEQ settings to Walkplay device
  const pushToDevice = async (deviceDetails, phoneObj, slot, globalGain, filtersToWrite) => {
    const device = deviceDetails.rawDevice;
    if (!device) throw new Error("Device not connected.");
    console.log("Pushing PEQ settings...");
    if (typeof slot === "string" )  // Convert from string
      slot = parseInt(slot, 10);

    const useAltReport = false;

    for (let i = 0; i < filtersToWrite.length; i++) {
      const filter = filtersToWrite[i] || {};
      const filterToWrite = normalizeFilterForWrite(filter, deviceDetails.modelConfig);
      const bArr = filter.disabled
        ? new Array(20).fill(0)
        : computeIIRFilter(i, filterToWrite.freq, filterToWrite.gain, filterToWrite.q, filterToWrite.type);

      // Debug: log what we're writing
      const hexBytes = Array.from(bArr).map(b => b.toString(16).padStart(2, '0')).join(' ');
      console.log(`[write-debug] Filter ${i}: freq=${filterToWrite.freq} gain=${filterToWrite.gain} q=${filterToWrite.q}`);
      console.log(`[write-debug] Biquad bytes: ${hexBytes}`);

      const packet = [
        WRITE, CMD.PEQ_VALUES, 0x18, 0x00, i, 0x00, 0x00,
        ...bArr,
        ...convertToByteArray(filterToWrite.freq, 2),
        ...convertToByteArray(Math.round(filterToWrite.q * 256), 2),
        ...convertToByteArray(Math.round(filterToWrite.gain * 256), 2),
        convertFromFilterType(filterToWrite.type),
        0x00,
        (deviceDetails.modelConfig && typeof deviceDetails.modelConfig.defaultIndex !== 'undefined') ? deviceDetails.modelConfig.defaultIndex : slot,
        END
      ];

      await sendReport(device, useAltReport ? ALT_REPORT_ID : REPORT_ID, packet);
      await delay(20); // Add delay between filter writes to prevent overwhelming the device
    }

    // Wait for device to process all filter writes
    await delay(100);

    if (deviceDetails.modelConfig.deviceHandlesPregain === false) {
      await writeGlobalGain(device, globalGain);
      console.log(`USB Device PEQ: Walkplay set global gain register to ${globalGain} dB`);
      await delay(50);
    }

    // Commit sequence matching Walkplay app order:
    // [1, 5, 0] and [1, 23, 0] before TEMP_WRITE, then [1, 1, 1, 0]
    // to persist the registers while leaving PEQ enabled. Sending [1, 1, 0]
    // is the same command shape used for PEQ disable on some firmware.
    await sendReport(device, REPORT_ID, [WRITE, 0x05, END]);
    await delay(20);
    await sendReport(device, REPORT_ID, [WRITE, 0x17, END]);
    await delay(20);
    await sendReport(device, REPORT_ID, [WRITE, CMD.TEMP_WRITE, 0x04, 0x00, 0x00, 0xFF, 0xFF, END]);
    await delay(50);
    await sendReport(device, REPORT_ID, [WRITE, CMD.FLASH_EQ, 0x01, END]);

    console.log("PEQ filters successfully pushed to Walkplay device.");
  };

  // Mic gain range: -15..+15 dB, encoded as 16-bit signed scaled by 32767/15.
  // Special cases from website source: +15 → 32767, -15 → 32769.
  const setMicGain = async (deviceDetails, value) => {
    const device = deviceDetails.rawDevice;
    if (!device) throw new Error("Device not connected.");
    let t;
    if (value === 15) {
      t = 32767;
    } else if (value === -15) {
      t = 32769;
    } else {
      let r = Math.round(value * (32767 / 15));
      r = Math.max(-32767, Math.min(32767, r));
      t = r < 0 ? r + 65536 : r;
    }
    const lsb = t & 0xFF;
    const msb = (t >> 8) & 0xFF;
    const request = [WRITE, CMD.MIC_GAIN, 0x02, lsb, msb];
    console.log(`USB Device PEQ: Walkplay set mic gain to ${value}dB (encoded: ${t})`);
    await sendReport(device, REPORT_ID, request);
  };

  const readMicGain = async (deviceDetails) => {
    const device = deviceDetails.rawDevice;
    if (!device) throw new Error("Device not connected.");

    return new Promise((resolve, reject) => {
      const request = [READ, CMD.MIC_GAIN, 0x00];

      const timeout = setTimeout(() => {
        device.removeEventListener("inputreport", onReport);
        reject("Timeout reading mic gain");
      }, 1000);

      const onReport = (event) => {
        const data = new Uint8Array(event.data.buffer);
        logHidRx('Walkplay', data);
        if (data[0] !== READ || data[1] !== CMD.MIC_GAIN) return;

        clearTimeout(timeout);
        device.removeEventListener("inputreport", onReport);

        // 16-bit unsigned little-endian → signed → dB
        const raw = data[2] | (data[3] << 8);
        const signed = raw > 32767 ? raw - 65536 : raw;
        const micGain = Math.round((signed * 15 / 32767) * 100) / 100;
        console.log(`USB Device PEQ: Walkplay mic gain value: ${micGain}dB (raw: ${raw})`);
        resolve(micGain);
      };

      device.addEventListener("inputreport", onReport);
      console.log(`USB Device PEQ: Walkplay sending readMicGain command:`, request);
      sendReport(device, REPORT_ID, request).catch(reject);
    });
  };

  function convertFromFilterType(filterType) {
    const mapping = {"PK": 2, "LSQ": 1, "HSQ": 3, "LP": 4, "HP": 5};
    return mapping[filterType] !== undefined ? mapping[filterType] : 2;
  }

  // modelConfig is threaded in so any centre-frequency compensation is applied
  // ONCE, here — this handler writes the frequency twice (into the biquad
  // coefficients via computeIIRFilter, and again as raw metadata), and the two
  // must agree or a pull would disagree with what is actually being filtered.
  //
  // freqCompensation.factor is the realised/requested ratio a measurement
  // reports: a device landing on 103 Hz when told 100 is 1.03, and we then
  // write freq/1.03. See compensation.js for the shared convention.
  //
  // SchemeNo11 lands filters 2.2-2.5% low. Measured on an EPZ TP13 AI ENC with
  // clean fits at 100 Hz (rmse 0.013-0.021) and 1000 Hz (0.034-0.069), and
  // reported by the maintainer as present on every SchemeNo11 device they own —
  // so it is configured on the GROUP, as a scheme-level firmware trait, not on
  // one product ID.
  //
  // The TP35 Pro (SchemeNo16) shows no such offset — its frequency sweep passed
  // at 100/1000/5000/10000 Hz — so this is not a WalkPlay-wide trait.
  //
  // The same parts also realise a Q lower than requested, by a factor that
  // deepens towards the device's design Nyquist (qCompensation 'cosNyquist').
  //
  // Order matters: the Q law describes what the device's firmware does with the
  // number it RECEIVES, so it is keyed off the frequency-compensated value, not
  // the one the user asked for. Doing it in this order means neither correction
  // has to know the stream's sample rate — the frequency compensation absorbs
  // the clock-vs-design-rate difference, and the Q law then works purely in the
  // device's own digital domain.
  function normalizeFilterForWrite(filter = {}, modelConfig) {
    if (filter.disabled) {
      return { freq: 0, q: 0, gain: 0, type: "PK" };
    }

    const requestedFreq = Number.isFinite(filter.freq) ? filter.freq : 0;
    const gain = Number.isFinite(filter.gain) ? filter.gain : 0;
    const q = Number.isFinite(filter.q) ? filter.q : 0;
    const type = filter.type || filter.filterType || "PK";

    const freqToSend = requestedFreq > 0
      ? compensateFreqForWrite(requestedFreq, modelConfig, { label: 'WalkPlay' })
      : requestedFreq;

    return {
      freq: freqToSend,
      q: q > 0
        ? compensateQForWrite(q, gain, type, modelConfig, { label: 'WalkPlay', freq: freqToSend })
        : q,
      gain,
      type
    };
  }

  const pullFromDevice = async (deviceDetails, slot = -1) => {
    const device = deviceDetails.rawDevice;
    if (!device) throw new Error("Device not connected.");

    const filters = [];
    // Use the slot passed in from getCurrentSlot — per-filter responses don't
    // reliably carry the slot at offset 35 (that offset is from the bulk ReadEQ
    // response format, not the per-filter variant).
    const currentSlot = slot;

    const onFilterReport = (event) => {
      const data = new Uint8Array(event.data.buffer);
        logHidRx('Walkplay', data);
      console.log(`USB Device PEQ: Walkplay pullFromDevice onInputReport received data:`, data);
      if (data[1] !== CMD.PEQ_VALUES) return; // ignore unrelated reports
      if (data.length >= 32) {
        const filter = parseFilterPacket(data, deviceDetails.modelConfig);
        console.log(`USB Device PEQ: Walkplay parsed filter ${filter.filterIndex}:`, filter);
        filters[filter.filterIndex] = filter;
      }
    };

    device.addEventListener('inputreport', onFilterReport);

    // Send requests for each filter with increased delay
    for (let i = 0; i < deviceDetails.modelConfig.maxFilters; i++) {
      await sendReport(device, REPORT_ID, [READ, CMD.PEQ_VALUES, 0x00, 0x00, i, END]);
      await delay(50); // Increased delay between requests
    }

    // Check for missing filters after initial requests
    await delay(100); // Wait a bit after sending all requests

    // Wait for filters with increased timeout
    const result = await waitForFilters(() => {
      const count = filters.filter(f => f !== undefined).length;
      const max = deviceDetails.modelConfig.maxFilters;
      console.log(`USB Device PEQ: Walkplay condition check - received ${count} filters, expecting ${max}`);
      return count === max;
    }, device, 10000, () => ({  // Increased timeout to 15 seconds
      filters,
      globalGain: 0, // Will be updated after waiting for filters
      currentSlot,
      deviceDetails: deviceDetails.modelConfig,
    }));

    device.removeEventListener('inputreport', onFilterReport);


    // Read global gain after waiting for filters
    let globalGain = 0;
    try {
      globalGain = await readGlobalGain(device);
      console.log(`USB Device PEQ: Walkplay read global gain: ${globalGain}dB`);
      // Update the result with the global gain
      result.globalGain = globalGain;
    } catch (error) {
      console.warn(`USB Device PEQ: Walkplay failed to read global gain: ${error}`);
    }

    console.log("Pulled PEQ filters from Walkplay:", result);
    return result;
  };

  function parseFilterPacket(packet, modelConfig) {
    if (packet.length < 32) {
      throw new Error("Packet too short to contain filter data.");
    }

    const filterIndex = packet[4];

    // Try to read metadata (freq, Q, gain, type from packet bytes)
    // Frequency (little-endian 16-bit). Undone through the same compensation so
    // a pull reports where the band really is, and pull -> push is stable.
    const freqRaw = packet[27] | (packet[28] << 8);
    const freq = freqRaw > 0 ? decompensateFreqFromRead(freqRaw, modelConfig) : freqRaw;

    // Gain (8.8 fixed-point signed)
    let gainRaw = packet[31] | (packet[32] << 8);
    if (gainRaw > 32767) gainRaw -= 65536;
    const gain = Math.round((gainRaw / 256) * 100) / 100;

    // Filter type
    const type = convertToFilterType(packet[33]);

    // Q factor (8.8 fixed-point), then undone through the same law as the write
    // so a pull reports the Q that will actually be heard. Keyed off freqRaw,
    // the value the device is actually holding — that is the number its firmware
    // designed the biquad from, and it mirrors the write exactly.
    const qRaw = packet[29] | (packet[30] << 8);
    const qStored = Math.round((qRaw / 256) * 100) / 100;
    const q = qStored > 0
      ? decompensateQFromRead(qStored, gain, type, modelConfig, { freq: freqRaw })
      : qStored;

    // Check if metadata is corrupted (all 0xFF bytes = unset marker)
    // When metadata is 0xff, the filter slot has never been written to (device uninitialized memory)
    // Don't try to extract from biquad coefficients in this case—they're also garbage
    const metadataCorrupted = freq === 65535 && qRaw === 65535 && gainRaw === -1;

    if (metadataCorrupted) {
      console.log(`USB Device PEQ: Walkplay filter ${filterIndex} metadata is all 0xff (unset/uninitialized)`);
      return {
        filterIndex,
        freq: 0,
        q: 0,
        gain: 0,
        type,
        disabled: true
      };
    }

    // Mark as disabled if: freq is 0 or 0xFFFF (unset marker), or all values are 0
    const valid = !(freq === 65535 || freq === 0 || q === 0 || (freq === 0 && q === 0 && gain === 0));

    return {
      filterIndex,
      freq: valid ? freq : 0,
      q: valid ? q : 0,
      gain: valid ? gain : 0,
      type,
      disabled: !valid
    };
  }

  function convertToFilterType(byte) {
    switch (byte) {
      case 1: return "LSQ";
      case 2: return "PK";
      case 3: return "HSQ";
      case 4: return "LP";
      case 5: return "HP";
      default: return "PK";
    }
  }
  const enablePEQ = async (deviceDetails, enable, slotId) => {
    const device = deviceDetails.rawDevice;
    if (!enable) {
      slotId = 0x00;
    }
    const packet = [WRITE, CMD.FLASH_EQ, enable ? 1:0, slotId, END];
    await sendReport(device, REPORT_ID, packet);
  };


// Internal functions
  async function sendReport(device, reportId, packet) {
    if (!device) throw new Error("Device not connected.");
    const data = new Uint8Array(packet);
    console.log(`USB Device PEQ: Walkplay sending report (ID: ${reportId}):`, data);
    logHidTx('Walkplay', reportId, data);
    await device.sendReport(reportId, data);
  }

// Wait for response matching a specific command byte (data[1] without report ID)
  async function waitForResponse(device, expectedCmd = null, timeout = 2000) {
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        device.removeEventListener("inputreport", onReport);
        console.log(`USB Device PEQ: Walkplay timeout waiting for response after ${timeout}ms`);
        reject("Timeout waiting for HID response");
      }, timeout);

      const onReport = (event) => {
        const data = new Uint8Array(event.data.buffer);
        logHidRx('Walkplay', data);
        if (expectedCmd !== null && data[1] !== expectedCmd) return; // skip unrelated reports
        clearTimeout(timer);
        device.removeEventListener("inputreport", onReport);
        console.log(`USB Device PEQ: Walkplay received response:`, data);
        resolve(data);
      };

      device.addEventListener("inputreport", onReport);
    });
  }

  // Read global gain from device
  async function readGlobalGain(device) {
    return new Promise(async (resolve, reject) => {
      const request = new Uint8Array([READ, CMD.GLOBAL_GAIN, 0x00]);

      const timeout = setTimeout(() => {
        device.removeEventListener("inputreport", onReport);
        reject("Timeout reading global gain");
      }, 100);

      const onReport = (event) => {
        const data = new Uint8Array(event.data.buffer);
        logHidRx('Walkplay', data);
        console.log(`USB Device PEQ: Walkplay onInputReport received global gain data:`, data);
        clearTimeout(timeout);
        device.removeEventListener("inputreport", onReport);
        if (data[0] !== READ || data[1] !== CMD.GLOBAL_GAIN) return;
        const int8 = new Int8Array([data[4]])[0];
        const globalGain = int8;
        console.log(`USB Device PEQ: Walkplay global gain value: ${globalGain}`);
        resolve(globalGain);
      };

      device.addEventListener("inputreport", onReport);
      console.log(`USB Device PEQ: Walkplay sending readGlobalGain command:`, request);
      await sendReport(device, REPORT_ID, request);
    });
  }

// Write global gain to device
  async function writeGlobalGain(device, value) {
    const gainValue = Math.round(value);
    // Match attached KeyX JS format: [WRITE, GLOBAL_GAIN, 0x02, 0x00, gain]
    const request = new Uint8Array([WRITE, CMD.GLOBAL_GAIN, 0x02, 0x00, gainValue]);
    console.log(`USB Device PEQ: Walkplay sending writeGlobalGain command:`, request);
    await sendReport(device, REPORT_ID, request);
  }

  // DAC_FILTER (0x11): select the DAC's DSP filter algorithm.
  // filterType: 'FAST-LL' | 'FAST-PC' | 'SLOW-LL' | 'SLOW-PC' | 'NON-OS'
  const setDacFilter = async (deviceDetails, filterType) => {
    const device = deviceDetails.rawDevice;
    if (!device) throw new Error("Device not connected.");
    const filterMap = { 'FAST-LL': 1, 'FAST-PC': 2, 'SLOW-LL': 3, 'SLOW-PC': 4, 'NON-OS': 5 };
    const filterByte = filterMap[filterType] ?? 1;
    console.log(`USB Device PEQ: Walkplay set DAC filter to ${filterType} (${filterByte})`);
    await sendReport(device, REPORT_ID, [WRITE, CMD.DAC_FILTER, 0x01, filterByte]);
  };

  // DAC_BALANCE (0x16): left/right channel trim in device units (typically 0..127).
  // Pass leftDelta > 0 to boost left, rightDelta > 0 to boost right, both 0 to center.
  const setDacBalance = async (deviceDetails, leftDelta, rightDelta) => {
    const device = deviceDetails.rawDevice;
    if (!device) throw new Error("Device not connected.");
    if (leftDelta > 0) {
      await sendReport(device, REPORT_ID, [WRITE, CMD.DAC_BALANCE, 0x04, 0x01, 0x00, leftDelta & 0xFF, 0x00]);
      await sendReport(device, REPORT_ID, [WRITE, CMD.DAC_BALANCE, 0x04, 0x00, 0x00, 0x00, 0x00]);
    } else if (rightDelta > 0) {
      await sendReport(device, REPORT_ID, [WRITE, CMD.DAC_BALANCE, 0x04, 0x01, 0x00, 0x00, 0x00]);
      await sendReport(device, REPORT_ID, [WRITE, CMD.DAC_BALANCE, 0x04, 0x00, 0x00, rightDelta & 0xFF, 0x00]);
    } else {
      await sendReport(device, REPORT_ID, [WRITE, CMD.DAC_BALANCE, 0x04, 0x00, 0x01, 0x00, 0x00]);
      await sendReport(device, REPORT_ID, [WRITE, CMD.DAC_BALANCE, 0x04, 0x00, 0x00, 0x00, 0x00]);
    }
  };

  // DENOISE (0x1B): enable or disable the ENC/noise-reduction circuit.
  const setDenoiseEnabled = async (deviceDetails, enabled) => {
    const device = deviceDetails.rawDevice;
    if (!device) throw new Error("Device not connected.");
    console.log(`USB Device PEQ: Walkplay set denoise ${enabled ? 'on' : 'off'}`);
    await sendReport(device, REPORT_ID, [WRITE, CMD.DENOISE, 0x01, enabled ? 0x01 : 0x00]);
  };

  // Read current ENC/denoise state. Returns true if enabled, false if disabled.
  const readDenoiseEnabled = async (deviceDetails) => {
    const device = deviceDetails.rawDevice;
    if (!device) throw new Error("Device not connected.");
    return new Promise((resolve, reject) => {
      const timeout = setTimeout(() => {
        device.removeEventListener("inputreport", onReport);
        reject("Timeout reading denoise state");
      }, 2000);
      const onReport = (event) => {
        const data = new Uint8Array(event.data.buffer);
        logHidRx('Walkplay', data);
        if (data[0] !== READ || data[1] !== CMD.DENOISE) return;
        clearTimeout(timeout);
        device.removeEventListener("inputreport", onReport);
        resolve(data[3] === 0x01);
      };
      device.addEventListener("inputreport", onReport);
      sendReport(device, REPORT_ID, [READ, CMD.DENOISE, 0x00]).catch(reject);
    });
  };

  // Read the current DAC filter algorithm. Returns the filter name string or null.
  const readDacFilter = async (deviceDetails) => {
    const device = deviceDetails.rawDevice;
    if (!device) throw new Error("Device not connected.");
    const filterNames = { 1: 'FAST-LL', 2: 'FAST-PC', 3: 'SLOW-LL', 4: 'SLOW-PC', 5: 'NON-OS' };
    return new Promise((resolve, reject) => {
      const timeout = setTimeout(() => {
        device.removeEventListener("inputreport", onReport);
        reject("Timeout reading DAC filter");
      }, 2000);
      const onReport = (event) => {
        const data = new Uint8Array(event.data.buffer);
        logHidRx('Walkplay', data);
        if (data[0] !== READ || data[1] !== CMD.DAC_FILTER) return;
        clearTimeout(timeout);
        device.removeEventListener("inputreport", onReport);
        // Response format: [READ, CMD, len, value] — value is at data[3]
        resolve(filterNames[data[3]] ?? null);
      };
      device.addEventListener("inputreport", onReport);
      sendReport(device, REPORT_ID, [READ, CMD.DAC_FILTER]).catch(reject);
    });
  };

  // DAC_WORK_MODE (0x1D): set DAC operational mode. mode: 0 = normal, 1 = alternate.
  const setDacWorkMode = async (deviceDetails, mode) => {
    const device = deviceDetails.rawDevice;
    if (!device) throw new Error("Device not connected.");
    console.log(`USB Device PEQ: Walkplay set DAC work mode to ${mode}`);
    await sendReport(device, REPORT_ID, [WRITE, CMD.DAC_WORK_MODE, 0x01, mode & 0xFF]);
  };

  // Read current DAC work mode. Returns 0 or 1.
  const readDacWorkMode = async (deviceDetails) => {
    const device = deviceDetails.rawDevice;
    if (!device) throw new Error("Device not connected.");
    return new Promise((resolve, reject) => {
      const timeout = setTimeout(() => {
        device.removeEventListener("inputreport", onReport);
        reject("Timeout reading DAC work mode");
      }, 2000);
      const onReport = (event) => {
        const data = new Uint8Array(event.data.buffer);
        logHidRx('Walkplay', data);
        if (data[0] !== READ || data[1] !== CMD.DAC_WORK_MODE) return;
        clearTimeout(timeout);
        device.removeEventListener("inputreport", onReport);
        // Response format: [READ, CMD, len, value] — value is at data[3]
        console.log('[walkplay] readDacWorkMode bytes:', Array.from(data.slice(0, 8)).map(b => '0x' + b.toString(16)));
        resolve(data[3]);
      };
      device.addEventListener("inputreport", onReport);
      sendReport(device, REPORT_ID, [READ, CMD.DAC_WORK_MODE]).catch(reject);
    });
  };

  // Public alias for writeGlobalGain — sets the DAC output/EQ offset gain in dB.
  const setOutputGain = async (deviceDetails, gainDb) => {
    const device = deviceDetails.rawDevice;
    if (!device) throw new Error("Device not connected.");
    console.log(`USB Device PEQ: Walkplay set output gain to ${gainDb}dB`);
    await writeGlobalGain(device, gainDb);
  };

  // GAIN_MODE (0x19): alternative gain-processing mode — boolean toggle.
  // Confirmed present on SchemeNo16 devices (e.g. Protocol Max).
  // Exact DSP behaviour TBD; treated as a boolean on/off switch.
  const setGainMode = async (deviceDetails, enabled) => {
    const device = deviceDetails.rawDevice;
    if (!device) throw new Error("Device not connected.");
    console.log(`USB Device PEQ: Walkplay set gain mode ${enabled ? 'on' : 'off'}`);
    await sendReport(device, REPORT_ID, [WRITE, CMD.GAIN_MODE, 0x01, enabled ? 0x01 : 0x00]);
  };

  const readGainMode = async (deviceDetails) => {
    const device = deviceDetails.rawDevice;
    if (!device) throw new Error("Device not connected.");
    return new Promise((resolve, reject) => {
      const timeout = setTimeout(() => {
        device.removeEventListener("inputreport", onReport);
        reject("Timeout reading gain mode");
      }, 2000);
      const onReport = (event) => {
        const data = new Uint8Array(event.data.buffer);
        logHidRx('Walkplay', data);
        if (data[0] !== READ || data[1] !== CMD.GAIN_MODE) return;
        clearTimeout(timeout);
        device.removeEventListener("inputreport", onReport);
        resolve(data[3] === 0x01);
      };
      device.addEventListener("inputreport", onReport);
      sendReport(device, REPORT_ID, [READ, CMD.GAIN_MODE, 0x00]).catch(reject);
    });
  };

  return {
    pushToDevice,
    pullFromDevice,
    getCurrentSlot,
    enablePEQ,
    setMicGain,
    readMicGain,
    setDacFilter,
    readDacFilter,
    setDacBalance,
    setDenoiseEnabled,
    readDenoiseEnabled,
    setDacWorkMode,
    readDacWorkMode,
    setOutputGain,
    setGainMode,
    readGainMode,
  };
})();

function delay(ms) {
  return new Promise(resolve => setTimeout(resolve, ms));
}

async function waitForFilters(condition, device, timeout, callback) {
  return new Promise((resolve, reject) => {
    let interval;
    const timer = setTimeout(() => {
      // Stop the poller on the timeout path as well. It runs every 10ms, so a
      // single timed-out pull that leaves it running costs 100 wake-ups a second
      // for the rest of the session, and they accumulate one per timeout.
      clearInterval(interval);
      if (!condition()) {
        console.warn("Timeout: Filters not fully received.");
        // Instead of rejecting with the callback result, create a proper result with partial data
        const result = callback(device);
        // Add information about the timeout to help with debugging
        result.complete = false;
        result.receivedCount = result.filters.filter(f => f !== undefined).length;
        result.expectedCount = device.max;
        // Resolve with partial data instead of rejecting
        resolve(result);
      } else {
        const result = callback(device);
        result.complete = true;
        resolve(result);
      }
    }, timeout);

    interval = setInterval(() => {
      if (condition()) {
        clearTimeout(timer);
        clearInterval(interval);
        const result = callback(device);
        result.complete = true;
        resolve(result);
      }
    }, 10); // Check more frequently to catch completion sooner
  });
}



// Compute IIR filter
// Standard RBJ Audio EQ Cookbook biquads, Q-parametrized. PK path is the
// original formula unchanged (A=10^(gain/40), alpha=sin(w0)/(2Q)); LSQ/HSQ
// previously fell through to this same peaking formula regardless of type
// (the caller passed no type at all) — that was a bug: shelf filters need
// their own coefficients, not a peaking filter tagged with a shelf type byte.
function computeIIRFilter(i, freq, gain, q, type = 'PK') {
  let bArr = new Array(20).fill(0);
  const A = Math.sqrt(Math.pow(10, gain / 20)); // 10^(gain/40)
  // WalkPlay coefficient domain confirmed by the OLA II verification path.
  // Keep this coefficient-generation rate separate from any model-specific
  // Q compensation, which is configured in usbDeviceConfig.js.
  const w0 = (freq * 6.283185307179586) / 96000;
  const sinw0 = Math.sin(w0);
  const alpha = sinw0 / (2 * q); // peaking-EQ alpha — correct for PK, NOT for LSQ/HSQ (see below)
  const cosw0 = Math.cos(w0);

  let a0, a1, a2, b0, b1, b2;

  if (type === 'LSQ' || type === 'HSQ') {
    // Proper RBJ/WebAudio shelf-Q alpha — NOT the peaking-EQ alpha above.
    // Correctness fix with LIKELY NO AUDIBLE EFFECT — read this before
    // assuming it changes device behavior:
    //
    // A real Protocol Max run's LSQ/HSQ acoustic response was measured
    // against the theoretical models in filter-response.js and initially
    // looked like a ~70-80%-of-requested-Q firmware quirk. Cross-checking
    // against a real WebAudio BiquadFilterNode showed the ROOT CAUSE was
    // that filter-response.js's shelf model used this same peaking-style
    // alpha instead of the proper shelf alpha — fixing the model there made
    // the real captures match at effective Q≈1.0.
    //
    // That means the device's ACTUAL Q behavior was already correct — the
    // bug was in the JS-side verification model, not here. And this
    // function's own bArr almost certainly isn't what drives that behavior
    // in the first place: pushToDevice() sends freq/Q/gain/type as their own
    // fields in the same packet (see below), and parseFilterPacket() reads
    // freq/Q/gain back from those raw fields, never from bArr — strong
    // evidence the firmware computes its own coefficients on-chip from
    // freq/Q/gain/type and bArr is unused (possibly legacy/vestigial).
    //
    // Fixed anyway, for internal correctness/consistency and in case any
    // firmware path does consult bArr — but don't expect measurements to
    // change. See testing/rew-peq-capability-test/filter-response.real.test.js
    // for the actual measurements this whole investigation is based on.
    const shelfAlpha = (sinw0 / 2) * Math.sqrt((A + 1 / A) * (1 / q - 1) + 2);
    const sqrtA2alpha = 2 * Math.sqrt(A) * shelfAlpha;
    if (type === 'LSQ') {
      b0 =    A * ((A + 1) - (A - 1) * cosw0 + sqrtA2alpha);
      b1 =  2*A * ((A - 1) - (A + 1) * cosw0);
      b2 =    A * ((A + 1) - (A - 1) * cosw0 - sqrtA2alpha);
      a0 =         (A + 1) + (A - 1) * cosw0 + sqrtA2alpha;
      a1 =    -2 * ((A - 1) + (A + 1) * cosw0);
      a2 =         (A + 1) + (A - 1) * cosw0 - sqrtA2alpha;
    } else { // HSQ
      b0 =     A * ((A + 1) + (A - 1) * cosw0 + sqrtA2alpha);
      b1 =  -2*A * ((A - 1) + (A + 1) * cosw0);
      b2 =     A * ((A + 1) + (A - 1) * cosw0 - sqrtA2alpha);
      a0 =          (A + 1) - (A - 1) * cosw0 + sqrtA2alpha;
      a1 =      2 * ((A - 1) - (A + 1) * cosw0);
      a2 =          (A + 1) - (A - 1) * cosw0 - sqrtA2alpha;
    }
  } else { // PK (default) — original formula, unchanged
    b0 = 1 + alpha * A;
    b1 = -2 * cosw0;
    b2 = 1 - alpha * A;
    a0 = 1 + alpha / A;
    a1 = -2 * cosw0;
    a2 = 1 - alpha / A;
  }

  let quantizerData = quantizer(
    [1, a1 / a0, a2 / a0],
    [b0 / a0, b1 / a0, b2 / a0]
  );

  let index = 0;
  for (let value of quantizerData) {
    bArr[index] = value & 0xFF;
    bArr[index + 1] = (value >> 8) & 0xFF;
    bArr[index + 2] = (value >> 16) & 0xFF;
    bArr[index + 3] = (value >> 24) & 0xFF;
    index += 4;
  }

  return bArr;
}

// Convert values to byte array
function convertToByteArray(value, length) {
  let arr = [];
  for (let i = 0; i < length; i++) {
    arr.push((value >> (8 * i)) & 0xFF);
  }
  return arr;
}

// Quantizer function for IIR filter
function quantizer(dArr, dArr2) {
  let iArr = dArr.map(d => Math.round(d * 1073741824));
  let iArr2 = dArr2.map(d => Math.round(d * 1073741824));
  return [iArr2[0], iArr2[1], iArr2[2], -iArr[1], -iArr[2]];
}
