//
// Copyright 2024 : Pragmatic Audio
//
// Define the shared logic for JadeAudio / SnowSky / FiiO devices - Each manufacturer will have slightly
// different code so best to each have a separate 'module'

import { logHidTx, logHidRx } from './deviceDebugLog.js';
import {
  compensateQForWrite as sharedCompensateQForWrite,
  decompensateQFromRead as sharedDecompensateQFromRead,
  shelfCompensationActive, shelfQToSend, shelfSRealised,
} from './compensation.js';

const PEQ_FILTER_COUNT = 0x18; // 24 in hex
const PEQ_GLOBAL_GAIN = 0x17; // 23 in hex
const PEQ_FILTER_PARAMS = 0x15; // 21 in hex
const PEQ_PRESET_SWITCH = 0x16; // 22 in hex
const PEQ_SAVE_TO_DEVICE = 0x19; // 25 in hex
const PEQ_SAVE_V2 = 0x21; // 33 in hex
const PEQ_RESET_DEVICE = 0x1B; // 27 in hex
const PEQ_RESET_ALL = 0x1C; // 28 in hex
const PEQ_CHANNEL_BALANCE = 0x1E; // 30 in hex
const PEQ_GLOBAL_GAIN_LR = 0x07; // 7 in hex

// Note these have different headers
const PEQ_FIRMWARE_VERSION = 0x0B; // 11 in hex
const PEQ_NAME_DEVICE = 0x30; // 48 in hex

const SET_HEADER1 = 0xAA;
const SET_HEADER2 = 0x0A;
const GET_HEADER1 = 0xBB;
const GET_HEADER2 = 0x0B;
const END_HEADERS = 0xEE;

export const fiioUsbHID = (function () {

  const getCurrentSlot = async (deviceDetails) => {
    var device = deviceDetails.rawDevice;
    var reportId = getFiioReportId(deviceDetails);
    try {
      let currentSlot = -99;

      device.oninputreport = async (event) => {
        const data = new Uint8Array(event.data.buffer);
        logHidRx('FiiO', data);
        console.log(`USB Device PEQ: getCurrentSlot() onInputReport received data:`, data);
        if (data[0] === GET_HEADER1 && data[1] === GET_HEADER2) {
          switch (data[4]) {
            case PEQ_PRESET_SWITCH:
              currentSlot = handleEqPreset(data, deviceDetails);
              break;
            default:
              console.log("USB Device PEQ: Unhandled data type:", data[4], data);
          }
        }
      };

      await getPresetPeq(device, reportId);

      // Wait at most 10 seconds for filters to be populated
      const result = await waitForFilters(() => {
        return currentSlot > -99
      }, device, 10000, (device) => (
        currentSlot
      ));

      return result;
    } catch (error) {
      console.error("Failed to pull data from FiiO Device:", error);
      throw error;
    }
  };

  const pushToDevice = async (deviceDetails, phoneObj, slot, preamp_gain, filters) => {
    try {
      var device = deviceDetails.rawDevice;
      var reportId = getFiioReportId(deviceDetails);

      await setGlobalGain(device, clampGlobalGain(preamp_gain, deviceDetails.modelConfig), reportId);
      const maxFilters = deviceDetails.modelConfig.maxFilters;
      const maxFiltersToUse = Math.min(filters.length, maxFilters);
      await setPeqCounter(device, maxFiltersToUse, reportId);
      await new Promise(resolve => setTimeout(resolve, 100)); // Added 100ms delay

      for (let filterIdx = 0; filterIdx < maxFiltersToUse; filterIdx++) {
        const filter = filters[filterIdx];
        var gain = 0;   // If disabled we still need to reset to 0 gain as previous gain value will
        // still be active
        if (!filter.disabled) {
          gain = filter.gain;
        }
        const qToWrite = compensateQForWrite(filter.q, gain, filter.type, deviceDetails.modelConfig);
        await setPeqParams(device, filterIdx, filter.freq, gain, qToWrite, convertFromFilterType(filter.type), reportId);
      }
      await new Promise(resolve => setTimeout(resolve, 100)); // Added 100ms delay

      saveToDevice(device, slot, reportId, deviceDetails.modelConfig.saveCommandId);

      console.log("PEQ filters pushed successfully.");

      if (deviceDetails.modelConfig.disconnectOnSave) {
        return true;    // Disconnect
      }
      return false;

    } catch (error) {
      console.error("Failed to push data to FiiO Device:", error);
      throw error;
    }
  };

  const pullFromDevice = async (deviceDetails, slot) => {
    try {
      const filters = [];
      let peqCount = 0;
      let globalGain = 0;
      let currentSlot = 0;
      var device = deviceDetails.rawDevice;
      var reportId = getFiioReportId(deviceDetails);

      device.oninputreport = async (event) => {
        const data = new Uint8Array(event.data.buffer);
        logHidRx('FiiO', data);
        console.log(`USB Device PEQ: pullFromDevice() onInputReport received data:`, data);
        if (data[0] === GET_HEADER1 && data[1] === GET_HEADER2) {
          switch (data[4]) {
            case PEQ_FILTER_COUNT:
              peqCount = handlePeqCounter(data, device, reportId);
              break;
            case PEQ_FILTER_PARAMS:
              handlePeqParams(data, device, filters, deviceDetails.modelConfig);
              break;
            case PEQ_GLOBAL_GAIN:
              globalGain = handleGain(data[6], data[7]);
              console.log(`USB Device PEQ: Global gain received: ${globalGain}dB`);
              break;
            case PEQ_PRESET_SWITCH:
              currentSlot = handleEqPreset(data, deviceDetails);
              break;
            case PEQ_SAVE_TO_DEVICE:
              savedEQ(data, device);
              break;
            default:
              console.log("USB Device PEQ: Unhandled data type:", data[4], data);
          }
        }
      };

      await getPresetPeq(device, reportId);
      await getPeqCounter(device, reportId);
      await getGlobalGain(device, reportId);

      // Wait at most 10 seconds for filters to be populated
      const result = await waitForFilters(() => {
        return filters.length == peqCount
      }, device, 10000, (device) => ({
        filters: filters,
        globalGain: globalGain
      }));

      return result;
    } catch (error) {
      console.error("Failed to pull data from FiiO Device:", error);
      throw error;
    }
  }

  const enablePEQ = async (deviceDetails, enable, slotId) => {

    var device = deviceDetails.rawDevice
    var reportId = getFiioReportId(deviceDetails);

    if (enable) {   // take the slotId we are given and switch to it
      await setPresetPeq(device, slotId, reportId);
    } else {
      await setPresetPeq(device, deviceDetails.modelConfig.maxFilters, reportId);
    }
  }
  return {
    pushToDevice,
    pullFromDevice,
    getCurrentSlot,
    enablePEQ
  };
})();


// Private Helper Functions

/**
 * Gets the appropriate reportId for a FiiO device based on its product name or modelConfig.
 * @param {Object} device - The device object.
 * @param {Object} [deviceDetails] - Optional deviceDetails object containing modelConfig.
 * @returns {number} - The reportId to use for the device.
 */
function getFiioReportId(deviceDetails) {
  // If deviceDetails is provided and has a modelConfig with reportId, use that
  if (deviceDetails && deviceDetails.modelConfig && deviceDetails.modelConfig.reportId !== undefined) {
    console.log(`Using reportId ${deviceDetails.modelConfig.reportId} from modelConfig for ${deviceDetails.model || "unknown device"}`);
    return deviceDetails.modelConfig.reportId;
  }

  // Default reportId for FiiO devices is 7
  console.log(`Using default reportId 7 for ${deviceDetails.model || "unknown device"}`);
  return 7;
}

// ── Gain-dependent Q correction (PEAKING) ──────────────────────────────────
// Measured against REW with testing/html-tools/devicepeq-rew-verification.html:
// FiiO devices deliver the requested GAIN accurately, but the realised bandwidth
// of a peaking filter widens with gain by exactly the RBJ peaking amplitude
// A = 10^(|gain|/40):
//
//     Q_realised = Q_requested / A
//
// Fitting gain-multiplier and Q-multiplier jointly (so a gain error cannot
// masquerade as a Q error) over 17 failing QX13 cases gave Q_mult x A =
// 1.0013 +/- 0.015, across gains of +/-6, +/-12, +/-24 dB, frequencies
// 100/1000/8000 Hz and requested Q from 0.1 to 10. Applied as a zero-free-
// parameter prediction it cut per-case shape RMSE from 0.17-4.22 dB to
// 0.018-0.216 dB, and a re-measurement afterwards fitted at Q_mult = 1.006.
//
// Reproduced on a second model: a FIIO KA17 gave Q_mult x A = 1.009 at
// 1kHz/+6dB/Q1 — so this looks like a FiiO firmware characteristic rather than
// a per-unit quirk. Still gated per model, because "two models agree" is not
// "all models agree".
//
// Since reproduced on a Fosi Audio DS3 as well (see fosiAudioUsbHidHandler.js),
// which shares no code or vendor with these — measured 0.714 at 6dB and 0.537
// at 12dB against 1/A predictions of 0.708 and 0.501. Three devices across two
// vendors makes this look like a common DSP convention rather than one firm's
// bug, so 'rbjGain' is now the first hypothesis to test when a new device reads
// wide — but it stays opt-in per model regardless.
// The BLE/serial FiiO handlers share none of this code.
//
// SHELVES follow a different relationship, handled separately below.
// Peaking only: the gain law was measured on peaks, and shelves take the
// slope<->Q transform below instead.
const Q_GAIN_COMP_TYPES = ['PK'];

// The peaking ratio law (and rbjA itself) is shared with the Fosi handler —
// see compensation.js. It is configured as
//     qCompensation: { model: 'rbjGain' }
// which resolves to a realised/requested ratio of 1/A, i.e. exactly the factor
// this handler used to compute inline. The legacy `compensateQForGain: true`
// flag still resolves to the same model, so out-of-tree configs keep working.
//
// Shelves are NOT a ratio law and stay here: they need a slope <-> Q transform,
// handled below.

// ── Shelf slope correction ─────────────────────────────────────────────────
// Shelves measured at an effective slope S ~= 1.89x the requested Q (steeper,
// not wider — the opposite direction to peaking). That number is not arbitrary:
// a proper RBJ shelf uses
//     alpha_shelf = sin(w0)/2 * sqrt((A + 1/A)(1/S - 1) + 2)
// whereas the device appears to reuse the PEAKING alpha for shelves,
//     alpha_peak  = sin(w0)/(2Q)
// The same simplification is already documented in walkplayHidHandler.js's
// computeIIRFilter() (see the note in filter-response.mjs) — that is a DIFFERENT
// codebase, for devices we send raw biquad coefficients to, so it is a precedent
// for the slip and not FiiO's source: FiiO firmware computes its own biquad from
// the freq/gain/Q/type we send. Equating the two alphas gives the slope a FiiO
// shelf actually realises for a requested Q:
//
//     S_realised = 1 / (1 + (1/Q^2 - 2)/(A + 1/A))
//
// At Q=1, gain 6dB that predicts S = 1.8925; four independent measurements
// (LSQ and HSQ, across two separate REW runs) gave 1.885, 1.887, 1.891, 1.897.
// A 0.1% match — but ALL FOUR sit at the same operating point (gain 6dB, Q 1),
// so the gain and Q dependence of this formula is so far a PREDICTION, not a
// measurement. It is falsifiable: at Q=1 it predicts S = 1.89 / 1.67 / 1.31 for
// gains of 6 / 12 / 24 dB, and at gain 6dB it predicts S = 0.51 / 1.89 / 5.72
// for Q = 0.5 / 1 / 2. Run a shelf sweep over those before trusting it.
//
// Hence its own flag, separate from the (thoroughly measured) peaking one.
// Exported so the round-trip stability of write-then-read can be tested
// directly, without standing up a mock read for every gain/slope combination.
export { compensateQForWrite, decompensateQFromRead };

// Q the device must be TOLD to use so that it realises `q`. Clamped to the
// device's accepted Q range: at -24 dB the factor is ~4x, so a requested Q of 10
// would need 39.8 sent, which the device cannot store — warn rather than
// silently deliver a filter several times wider than asked for.
function compensateQForWrite(q, gainDb, filterType, modelConfig) {
  const maxQ = modelConfig?.maxQ ?? 10;
  const minQ = modelConfig?.minQ ?? 0.1;

  if (shelfCompensationActive(filterType, modelConfig)) {
    const send = shelfQToSend(q, gainDb);
    if (send == null) {
      console.warn(
        `USB Device PEQ: FiiO shelf slope ${q} at ${gainDb}dB is steeper than this ` +
        `device can produce — sending minimum Q ${minQ} instead.`);
      return minQ;
    }
    const clamped = Math.min(maxQ, Math.max(minQ, send));
    if (Math.abs(clamped - send) > 1e-6) {
      console.warn(
        `USB Device PEQ: FiiO shelf compensation clamped — slope ${q} at ${gainDb}dB ` +
        `needs Q ${send.toFixed(3)} sent, outside the device range [${minQ}, ${maxQ}]. ` +
        `Sending ${clamped}.`);
    }
    return clamped;
  }

  return sharedCompensateQForWrite(q, gainDb, filterType, modelConfig,
    { label: 'FiiO', types: Q_GAIN_COMP_TYPES });
}

// Inverse, so a pull reports the Q the user will actually hear and a
// pull->push round trip is stable rather than compounding the factor each time.
function decompensateQFromRead(q, gainDb, filterType, modelConfig) {
  if (shelfCompensationActive(filterType, modelConfig)) {
    const s = shelfSRealised(q, gainDb);
    return s == null ? q : s;
  }
  return sharedDecompensateQFromRead(q, gainDb, filterType, modelConfig,
    { types: Q_GAIN_COMP_TYPES });
}

async function setPeqParams(device, filterIndex, fc, gain, q, filterType, reportId) {
  const [frequencyLow, frequencyHigh] = splitUnsignedValue(fc);
  const [gainLow, gainHigh] = fiioGainBytesFromValue(gain);
  const qFactorValue = Math.round(q * 100);
  const [qFactorLow, qFactorHigh] = splitUnsignedValue(qFactorValue);

  const packet = [
    SET_HEADER1, SET_HEADER2, 0, 0, PEQ_FILTER_PARAMS, 8,
    filterIndex, gainLow, gainHigh,
    frequencyLow, frequencyHigh,
    qFactorLow, qFactorHigh,
    filterType, 0, END_HEADERS
  ];

  const data = new Uint8Array(packet);
  console.log(`USB Device PEQ: setPeqParams() sending filter ${filterIndex} - Freq: ${fc}Hz, Gain: ${gain}dB, Q: ${q}, Type: ${filterType}`, data);
  logHidTx('FiiO', reportId, data);
  await device.sendReport(reportId, data);
}

async function setPresetPeq(device, presetId, reportId) { // Default to 0 if not specified
  const packet = [
    SET_HEADER1, SET_HEADER2, 0, 0, PEQ_PRESET_SWITCH, 1,
    presetId, 0, END_HEADERS
  ];

  const data = new Uint8Array(packet);
  console.log(`USB Device PEQ: setPresetPeq() switching to preset ${presetId}`, data);
  logHidTx('FiiO', reportId, data);
  await device.sendReport(reportId, data);
}

async function setGlobalGain(device, gain, reportId) {
  const globalGain = Math.round(gain * 10);
  const gainBytes = toBytePair(globalGain);

  const packet = [
    SET_HEADER1, SET_HEADER2, 0, 0, PEQ_GLOBAL_GAIN, 2,
    gainBytes[1], gainBytes[0], 0, END_HEADERS
  ];

  const data = new Uint8Array(packet);
  console.log(`USB Device PEQ: setGlobalGain() setting global gain to ${gain}dB`, data);
  logHidTx('FiiO', reportId, data);
  await device.sendReport(reportId, data);
}

function clampGlobalGain(gain, modelConfig = {}) {
  const minGain = typeof modelConfig.minGain === "number" ? modelConfig.minGain : -12;
  const maxGain = typeof modelConfig.maxGain === "number" ? modelConfig.maxGain : 12;
  return Math.max(minGain, Math.min(maxGain, gain));
}

async function setPeqCounter(device, counter, reportId) {
  const packet = [
    SET_HEADER1, SET_HEADER2, 0, 0, PEQ_FILTER_COUNT, 1,
    counter, 0, END_HEADERS
  ];

  const data = new Uint8Array(packet);
  console.log(`USB Device PEQ: setPeqCounter() setting filter count to ${counter}`, data);
  logHidTx('FiiO', reportId, data);
  await device.sendReport(reportId, data);
}

function convertFromFilterType(filterType) {
  const mapping = {"PK": 0, "LSQ": 1, "HSQ": 2};
  return mapping[filterType] !== undefined ? mapping[filterType] : 0;
}

function convertToFilterType(datum) {
  switch (datum) {
    case 0:
      return "PK";
    case 1:
      return "LSQ";
    case 2:
      return "HSQ";
    default:
      return "PK";
  }
}

function toBytePair(value) {
  return [
    value & 0xFF,
    (value & 0xFF00) >> 8
  ];
}

function splitSignedValue(value) {
  const signedValue = value < 0 ? value + 65536 : value;
  return [
    (signedValue >> 8) & 0xFF,
    signedValue & 0xFF
  ];
}

function splitUnsignedValue(value) {
  return [
    (value >> 8) & 0xFF,
    value & 0xFF
  ];
}

function combineBytes(lowByte, highByte) {
  return (lowByte << 8) | highByte;
}

function getGlobalGain(device, reportId) {
  const packet = [GET_HEADER1, GET_HEADER2, 0, 0, PEQ_GLOBAL_GAIN, 0, 0, END_HEADERS];
  const data = new Uint8Array(packet);
  console.log("getGlobalGain() Send data:", data);
  logHidTx('FiiO', reportId, data);
  device.sendReport(reportId, data);
}

function getPeqCounter(device, reportId) {
  const packet = [GET_HEADER1, GET_HEADER2, 0, 0, PEQ_FILTER_COUNT, 0, 0, END_HEADERS];
  const data = new Uint8Array(packet);
  console.log("getPeqCounter() Send data:", data);
  logHidTx('FiiO', reportId, data);
  device.sendReport(reportId, data);
}

function getPeqParams(device, filterIndex, reportId) {
  const packet = [GET_HEADER1, GET_HEADER2, 0, 0, PEQ_FILTER_PARAMS, 1, filterIndex, 0, END_HEADERS];
  const data = new Uint8Array(packet);
  console.log("getPeqParams() Send data:", data);
  logHidTx('FiiO', reportId, data);
  device.sendReport(reportId, data);
}

function getPresetPeq(device, reportId) {
  const packet = [GET_HEADER1, GET_HEADER2, 0, 0, PEQ_PRESET_SWITCH, 0, 0, END_HEADERS];
  const data = new Uint8Array(packet);
  console.log("getPresetPeq() Send data:", data);
  logHidTx('FiiO', reportId, data);
  device.sendReport(reportId, data);
}

function saveToDevice(device, slotId, reportId, customSaveCommandId) {
  const saveCmd = customSaveCommandId || PEQ_SAVE_TO_DEVICE;
  const packet = [SET_HEADER1, SET_HEADER2, 0, 0, saveCmd, 1, slotId, 0, END_HEADERS];
  const data = new Uint8Array(packet);
  console.log(`USB Device PEQ: saveToDevice() using command ${saveCmd}, reportId ${reportId} for slot ${slotId}`, data);
  logHidTx('FiiO', reportId, data);
  device.sendReport(reportId, data);
}

function handlePeqCounter(data, device, reportId) {
  let peqCount = data[6];
  console.log("***********oninputreport peq counter=", peqCount);
  if (peqCount > 0) {
    processPeqCount(device, peqCount, reportId);
  }
  return peqCount;
}

function processPeqCount(device, peqCount, reportId) {
  console.log("PEQ Counter:", peqCount);

  // Fetch individual PEQ settings based on count
  for (let i = 0; i < peqCount; i++) {
    getPeqParams(device, i, reportId);
  }
}

function handlePeqParams(data, device, filters, modelConfig) {
  const filter = data[6];
  const gain = handleGain(data[7], data[8]);
  const frequency = combineBytes(data[9], data[10]);
  const qFactor = (combineBytes(data[11], data[12])) / 100 || 1;
  const filterType = convertToFilterType(data[13]);
  // Report the Q the filter actually realises, not the pre-compensated value
  // that was stored — otherwise a pull followed by a push would apply the
  // factor a second time.
  const reportedQ = decompensateQFromRead(qFactor, gain, filterType, modelConfig);

  console.log(`Filter ${filter}: Gain=${gain}, Frequency=${frequency}, Q=${reportedQ}` +
    (reportedQ !== qFactor ? ` (stored ${qFactor})` : '') + `, Type=${filterType}`);

  filters[filter] = {
    type: filterType,
    freq: frequency,
    q: reportedQ,
    gain: gain,
    disabled: (gain || frequency || qFactor) ? false : true // Disable filter if 0 value found
  };
}


function handleGain(lowByte, highByte) {
  let r = combineBytes(lowByte, highByte);
  const gain = r & 32768 ? (r = (r ^ 65535) + 1, -r / 10) : r / 10;
  return gain;
}

function fiioGainBytesFromValue(e) {
  let t = e * 10;
  t < 0 && (t = (Math.abs(t) ^ 65535) + 1);
  const r = t >> 8 & 255,
    n = t & 255;
  return [r, n]
}

function handleEqPreset(data, deviceDetails) {
  const presetId = data[6];
  console.log("EQ Preset ID:", presetId);

  if (presetId === deviceDetails.modelConfig.disabledPresetId) {
    return -1;      // with JA11 slot 4 == Off
  }
  // Handle preset switch if necessary
  return presetId;
}

function savedEQ(data, device) {
  const slotId = data[6];
  console.log("EQ Slot ID:", slotId);
  // Handle slot enablement if necessary
}


// Utility function to wait for a condition or timeout
function waitForFilters(condition, device, timeout, callback) {
  return new Promise((resolve, reject) => {
    let interval;
    const timer = setTimeout(() => {
      // Must stop the poller here too. Without this, every pull that times out
      // leaves a 100ms interval running for the life of the page, each one
      // holding its filter array and device object alive — they accumulate
      // across a long bench session and never stop.
      clearInterval(interval);
      if (!condition()) {
        console.warn("Timeout reached before data returned?");
        reject(callback(device));
      } else {
        resolve(callback(device));
      }
    }, timeout);

    // Check every 100 milliseconds if everything is ready based on condition method !!
    interval = setInterval(() => {
      if (condition()) {
        clearTimeout(timer);
        clearInterval(interval);
        resolve(callback(device));
      }
    }, 100);
  });
}
