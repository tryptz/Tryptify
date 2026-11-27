// Dynamically import manufacturer specific handlers for their unique devices
const {fiioUsbHID} = await import('./fiioUsbHidHandler.js');
const {walkplayUsbHID} = await import('./walkplayHidHandler.js');
const {moondropUsbHidHandler} = await import('./moondropUsbHidHandler.js');
const {moondropOldFashionedUsbHID} = await import('./moondropOldFashionedUsbHidHandler.js');
const {conexantUsbHidHandler} = await import('./conexantUsbHidHandler.js');
const {ktmicroUsbHidHandler} = await import('./ktmicroUsbHidHandler.js');
const {toppingUsbHidHandler} = await import('./toppingUsbHidHandler.js');
const {fosiAudioUsbHID} = await import('./fosiAudioUsbHidHandler.js');

// Main list of HID devices - each vendor has one or more vendorId, and a list of devices associated,
// each device has a model of how the slots are configured and a handler to handle reading / writing
// the raw USBHID reports to the device
export const usbHidDeviceHandlerConfig = ([
  {
    vendorIds: [0x2972,0x0A12],
    manufacturer: "FiiO",
    handler: fiioUsbHID,
    defaultModelConfig: { // Fallback if we haven't got specific details yet
      peqConstraintsRef: "peq5Band12dBFullShelves",
      firstWritableEQSlot: -1,
      maxWritableEQSlots: 0,
      disconnectOnSave: false,
      disabledPresetId: -1,
      experimental: false,
      defaultResetFiltersValues:[{gain:0, freq: 100, q:1, filterType: "PK"}],
      reportId: 7,
      availableSlots: [
        {id: 0, name: "Jazz"},
        {id: 1, name: "Pop"},
        {id: 2, name: "Rock"},
        {id: 3, name: "Dance"},
        {id: 4, name: "R&B"},
        {id: 5, name: "Classic"},
        {id: 6, name: "Hip-hop"},
        {id: 7, name: "Monitor"},
        {id: 160, name: "USER1"},
        {id: 161, name: "USER2"},
        {id: 162, name: "USER3"},
        {id: 163, name: "USER4"},
        {id: 164, name: "USER5"},
        {id: 165, name: "USER6"},
        {id: 166, name: "USER7"},
        {id: 167, name: "USER8"},
        {id: 168, name: "USER9"},
        {id: 169, name: "USER10"}
      ]
    },
    deviceGroups: {
      "WalkPlay-SchemeNo10": {
        productIds: [0x0104],
        manufacturer: "WalkPlay",
        handler: walkplayUsbHID,
        modelConfig: {
          peqConstraintsRef: "walkplayPeq8Band10dBPkOnly",
          schemeNo: 10,
          firstWritableEQSlot: 101,
          maxWritableEQSlots: 1,
          disconnectOnSave: false,
          defaultResetFiltersValues: [{gain: 0, freq: 100, q: 1, filterType: "PK"}],
          deviceHandlesPregain: false,
          experimental: false,
          availableSlots: [{id: 101, name: "Custom"}],
          dacWorkMode: { modes: [0, 1], modeLabels: ["Class H", "Class AB"] },
        }
      }
    },
    devices: {
      "FIIO QX13": {
        modelConfig: {
          peqConstraintsRef: "peq10Band12dBWideAllFilters",
          disconnectOnSave: false,
          qCompensation: { model: 'rbjGain' },
          shelfCompensation: { model: 'peakingAlpha' },
          // Provided device presets mapping
          disabledPresetId: 240,
          firstWritableEQSlot: 160,
          maxWritableEQSlots: 10,
          availableSlots: [
            {id: 0, name: "Jazz"},
            {id: 1, name: "Pop"},
            {id: 2, name: "Rock"},
            {id: 3, name: "Dance"},
            {id: 4, name: "R&B"},
            {id: 5, name: "Classic"},
            {id: 6, name: "Hip-hop"},
            {id: 8, name: "Retro"},
            {id: 160, name: "USER1"},
            {id: 161, name: "USER2"},
            {id: 162, name: "USER3"},
            {id: 163, name: "USER4"},
            {id: 164, name: "USER5"},
            {id: 165, name: "USER6"},
            {id: 166, name: "USER7"},
            {id: 167, name: "USER8"},
            {id: 168, name: "USER9"},
            {id: 169, name: "USER10"},
            {id: 240, name: "BYPASS"}
          ]
        }
      },
      "SNOWSKY Melody": {
        manufacturer: "FiiO",
        handler: fiioUsbHID,
        modelConfig: {
          peqConstraintsRef: "peq10Band12dBAllFilters7",
          firstWritableEQSlot: 160,
          disabledPresetId: 240,
          maxWritableEQSlots: 3,
          availableSlots: [
            {id: 0, name: "Jazz"}, {id: 1, name: "Pop"}, {id: 2, name: "Rock"},
            {id: 3, name: "Dance"}, {id: 4, name: "R&B"}, {id: 5, name: "Classic"},
            {id: 6, name: "Hip-hop"}, {id: 160, name: "USER1"}, {id: 161, name: "USER2"},
            {id: 162, name: "USER3"}, {id: 240, name: "Close EQ"}
          ]
        }
      },
      "JadeAudio JIEZI": {
        manufacturer: "FiiO",
        handler: fiioUsbHID,
          modelConfig: {
            peqConstraintsRef: "peq5Band12dBAllFilters7",
            firstWritableEQSlot: 160,
            maxWritableEQSlots: 3,
            disabledPresetId: 240,
            reportId: 2,
            availableSlots: [
              {id: 0, name: "Jazz"},
              {id: 1, name: "Pop"},
              {id: 2, name: "Rock"},
              {id: 3, name: "Dance"},
              {id: 4, name: "R&B"},
              {id: 5, name: "Classic"},
              {id: 6, name: "Hip-hop"},
              {id: 160, name: "USER1"},
              {id: 161, name: "USER2"},
              {id: 162, name: "USER3"},
              {id: 240, name: "Close EQ"}
            ]
          }
        },
      "JadeAudio JA11": {
        modelConfig: {
          peqConstraintsRef: "peq5Band12dBFullShelves",
          firstWritableEQSlot: 3,
          maxWritableEQSlots: 1,
          disconnectOnSave: true,
          disabledPresetId: 4,
          reportId: 2,
          availableSlots: [{id: 0, name: "Vocal"}, {id: 1, name: "Classic"}, {id: 2, name: "Bass"}, {
            id: 3,
            name: "USER1"
          }]
        }
      },
      "FIIO KA17": {
        modelConfig: {
          peqConstraintsRef: "peq10Band12dBAllFilters7",
          qCompensation: { model: 'rbjGain' },
          firstWritableEQSlot: 7,
          maxWritableEQSlots: 3,
          disconnectOnSave: false,
          disabledPresetId: 10,
          reportId: 1,
          availableSlots: [
            {id: 0, name: "Jazz"}, {id: 1, name: "Pop"}, {id: 2, name: "Rock"},
            {id: 3, name: "Dance"}, {id: 5, name: "R&B"}, {id: 6, name: "Classic"},
            {id: 7, name: "Hip-hop"}, {id: 4, name: "USER1"}, {id: 8, name: "USER2"},
            {id: 9, name: "USER3"}, {id: 10, name: "BYPASS"}
          ]
        }
      },
      "FIIO Q7": {  // legacy device, not in current FiiO app enum — use KA17-style config
        modelConfig: {
          peqConstraintsRef: "peq10Band12dBWideAllFilters",
          firstWritableEQSlot: 7,
          maxWritableEQSlots: 3,
          disconnectOnSave: false,
          disabledPresetId: 10,
          reportId: 7,
          availableSlots: [
            {id: 0, name: "Jazz"}, {id: 1, name: "Pop"}, {id: 2, name: "Rock"},
            {id: 3, name: "Dance"}, {id: 5, name: "R&B"}, {id: 6, name: "Classic"},
            {id: 7, name: "Hip-hop"}, {id: 4, name: "USER1"}, {id: 8, name: "USER2"},
            {id: 9, name: "USER3"}, {id: 10, name: "BYPASS"}
          ]
        }
      },
      "FIIO KA17 (MQA HID)": {  // KA17 variant — same reportId=1 as base KA17
        modelConfig: {
          peqConstraintsRef: "peq10Band12dBAllFilters7",
          qCompensation: { model: 'rbjGain' },
          firstWritableEQSlot: 7,
          maxWritableEQSlots: 3,
          disconnectOnSave: false,
          disabledPresetId: 10,
          reportId: 1,
          availableSlots: [
            {id: 0, name: "Jazz"}, {id: 1, name: "Pop"}, {id: 2, name: "Rock"},
            {id: 3, name: "Dance"}, {id: 5, name: "R&B"}, {id: 6, name: "Classic"},
            {id: 7, name: "Hip-hop"}, {id: 4, name: "USER1"}, {id: 8, name: "USER2"},
            {id: 9, name: "USER3"}, {id: 10, name: "BYPASS"}
          ]
        }
      },
      "FIIO BT11": {  // be.FIIO_BT11 — canonical USB product name from FiiO app enum
        modelConfig: {
          peqConstraintsRef: "peq10Band12dBWideAllFilters",
          firstWritableEQSlot: 7,
          maxWritableEQSlots: 3,
          disconnectOnSave: false,
          disabledPresetId: 11,
          availableSlots: [
            {id: 0, name: "Jazz"}, {id: 1, name: "Pop"}, {id: 2, name: "Rock"},
            {id: 3, name: "Dance"}, {id: 5, name: "R&B"}, {id: 6, name: "Classic"},
            {id: 7, name: "Hip-hop"}, {id: 4, name: "USER1"}, {id: 8, name: "USER2"},
            {id: 9, name: "USER3"}
          ]
        }
      },
      "FIIO BT11 (UAC1.0)": {  // legacy USB product name variant
        modelConfig: {
          peqConstraintsRef: "peq10Band12dBWideAllFilters",
          firstWritableEQSlot: 7,
          maxWritableEQSlots: 3,
          disconnectOnSave: false,
          disabledPresetId: 11,
          availableSlots: [
            {id: 0, name: "Jazz"}, {id: 1, name: "Pop"}, {id: 2, name: "Rock"},
            {id: 3, name: "Dance"}, {id: 5, name: "R&B"}, {id: 6, name: "Classic"},
            {id: 7, name: "Hip-hop"}, {id: 4, name: "USER1"}, {id: 8, name: "USER2"},
            {id: 9, name: "USER3"}
          ]
        }
      },
      "FIIO Air Link": {
        modelConfig: {
          peqConstraintsRef: "peq10Band12dBWideAllFilters",
          firstWritableEQSlot: 7,
          maxWritableEQSlots: 3,
          disconnectOnSave: false,
          disabledPresetId: 11,
          availableSlots: [
            {id: 0, name: "Jazz"}, {id: 1, name: "Pop"}, {id: 2, name: "Rock"},
            {id: 3, name: "Dance"}, {id: 5, name: "R&B"}, {id: 6, name: "Classic"},
            {id: 7, name: "Hip-hop"}, {id: 4, name: "USER1"}, {id: 8, name: "USER2"},
            {id: 9, name: "USER3"}
          ]
        }
      },
      "FIIO BTR13": {
        modelConfig: {
          peqConstraintsRef: "peq10Band12dBAllFilters7",
          firstWritableEQSlot: 7,
          maxWritableEQSlots: 3,
          disconnectOnSave: false,
          disabledPresetId: 11,
          availableSlots: [
            {id: 0, name: "Jazz"}, {id: 1, name: "Pop"}, {id: 2, name: "Rock"},
            {id: 3, name: "Dance"}, {id: 4, name: "R&B"}, {id: 5, name: "Classic"},
            {id: 6, name: "Hip-hop"}, {id: 7, name: "USER1"}, {id: 8, name: "USER2"},
            {id: 9, name: "USER3"}, {id: 11, name: "Close EQ"}
          ]
        }
      },
      "FIIO BTR17": {  // be.FIIO_BTR17 — canonical USB product name; saveCommandId=PEQ_SAVE_V2
        modelConfig: {
          peqConstraintsRef: "peq10Band12dBWideShelves",
          firstWritableEQSlot: 160,
          maxWritableEQSlots: 10,
          disconnectOnSave: false,
          disabledPresetId: 240,
          saveCommandId: 0x21,
          availableSlots: [
            {id: 0, name: "Jazz"}, {id: 1, name: "Pop"}, {id: 2, name: "Rock"},
            {id: 3, name: "Dance"}, {id: 4, name: "R&B"}, {id: 5, name: "Classic"},
            {id: 6, name: "Hip-hop"},
            {id: 160, name: "USER1"}, {id: 161, name: "USER2"}, {id: 162, name: "USER3"},
            {id: 163, name: "USER4"}, {id: 164, name: "USER5"}, {id: 165, name: "USER6"},
            {id: 166, name: "USER7"}, {id: 167, name: "USER8"}, {id: 168, name: "USER9"},
            {id: 169, name: "USER10"}, {id: 240, name: "BYPASS"}
          ]
        }
      },
      "BTR17": {  // legacy name fallback
        modelConfig: {
          peqConstraintsRef: "peq10Band12dBWideShelves",
          firstWritableEQSlot: 160,
          maxWritableEQSlots: 10,
          disconnectOnSave: false,
          disabledPresetId: 240,
          saveCommandId: 0x21,
          availableSlots: [
            {id: 0, name: "Jazz"}, {id: 1, name: "Pop"}, {id: 2, name: "Rock"},
            {id: 3, name: "Dance"}, {id: 4, name: "R&B"}, {id: 5, name: "Classic"},
            {id: 6, name: "Hip-hop"},
            {id: 160, name: "USER1"}, {id: 161, name: "USER2"}, {id: 162, name: "USER3"},
            {id: 163, name: "USER4"}, {id: 164, name: "USER5"}, {id: 165, name: "USER6"},
            {id: 166, name: "USER7"}, {id: 167, name: "USER8"}, {id: 168, name: "USER9"},
            {id: 169, name: "USER10"}, {id: 240, name: "BYPASS"}
          ]
        }
      },
      "FIIO K19": {  // be.FIIO_K19 — 31-band, PK/LS/HS only, -24/+12; USER1-10 at slots 7-16, closeEq=17
        modelConfig: {
          peqConstraintsRef: "peq31Band12dBWideShelves",
          firstWritableEQSlot: 7,
          maxWritableEQSlots: 10,
          disconnectOnSave: false,
          disabledPresetId: 17,
          saveCommandId: 0x21,
          availableSlots: [
            {id: 0, name: "Jazz"}, {id: 1, name: "Rock"}, {id: 2, name: "R&B"},
            {id: 3, name: "Hip-hop"}, {id: 4, name: "Pop"}, {id: 5, name: "Dance"},
            {id: 6, name: "Classic"},
            {id: 7, name: "USER1"}, {id: 8, name: "USER2"}, {id: 9, name: "USER3"},
            {id: 10, name: "USER4"}, {id: 11, name: "USER5"}, {id: 12, name: "USER6"},
            {id: 13, name: "USER7"}, {id: 14, name: "USER8"}, {id: 15, name: "USER9"},
            {id: 16, name: "USER10"}, {id: 17, name: "Close EQ"}
          ]
        }
      },
      "FIIO K17": {  // be.FIIO_K17 — 31-band, PK/LS/HS only, -24/+12; USER1-10 at slots 7-16, closeEq=17
        modelConfig: {
          peqConstraintsRef: "peq31Band12dBWideShelves",
          firstWritableEQSlot: 7,
          maxWritableEQSlots: 10,
          disconnectOnSave: false,
          disabledPresetId: 17,
          saveCommandId: 0x21,
          availableSlots: [
            {id: 0, name: "Jazz"}, {id: 1, name: "Rock"}, {id: 2, name: "R&B"},
            {id: 3, name: "Hip-hop"}, {id: 4, name: "Pop"}, {id: 5, name: "Dance"},
            {id: 6, name: "Classic"},
            {id: 7, name: "USER1"}, {id: 8, name: "USER2"}, {id: 9, name: "USER3"},
            {id: 10, name: "USER4"}, {id: 11, name: "USER5"}, {id: 12, name: "USER6"},
            {id: 13, name: "USER7"}, {id: 14, name: "USER8"}, {id: 15, name: "USER9"},
            {id: 16, name: "USER10"}, {id: 17, name: "Close EQ"}
          ]
        }
      },
      "FIIO K15": {  // be.FIIO_K15 — -24/+12 (default gain range, NOT ±12); USER1-10 at 7-16, BYPASS=240
        modelConfig: {
          peqConstraintsRef: "peq10Band12dBWideAllFilters",
          firstWritableEQSlot: 7,
          maxWritableEQSlots: 10,
          disconnectOnSave: false,
          disabledPresetId: 240,
          availableSlots: [
            {id: 0, name: "Jazz"}, {id: 1, name: "Pop"}, {id: 2, name: "Rock"},
            {id: 3, name: "Dance"}, {id: 4, name: "R&B"}, {id: 5, name: "Classic"},
            {id: 6, name: "Hip-hop"},
            {id: 7, name: "USER1"}, {id: 8, name: "USER2"}, {id: 9, name: "USER3"},
            {id: 10, name: "USER4"}, {id: 11, name: "USER5"}, {id: 12, name: "USER6"},
            {id: 13, name: "USER7"}, {id: 14, name: "USER8"}, {id: 15, name: "USER9"},
            {id: 16, name: "USER10"}, {id: 240, name: "BYPASS"}
          ]
        }
      },
      "FIIO KA15": {  // be.FIIO_KA15 — Z1 exception ±12; closeEq=10
        modelConfig: {
          peqConstraintsRef: "peq10Band12dBAllFilters7",
          firstWritableEQSlot: 7,
          maxWritableEQSlots: 3,
          disconnectOnSave: false,
          disabledPresetId: 10,
          availableSlots: [
            {id: 0, name: "Jazz"}, {id: 1, name: "Pop"}, {id: 2, name: "Rock"},
            {id: 3, name: "Dance"}, {id: 4, name: "R&B"}, {id: 5, name: "Classic"},
            {id: 6, name: "Hip-hop"}, {id: 7, name: "USER1"}, {id: 8, name: "USER2"},
            {id: 9, name: "USER3"}, {id: 10, name: "Close EQ"}
          ]
        }
      },
      "FIIO K13 R2R": {
        modelConfig: {
          peqConstraintsRef: "peq10Band12dBWideAllFilters",
          firstWritableEQSlot: 160,
          maxWritableEQSlots: 10,
          disconnectOnSave: false,
          disabledPresetId: 240,
          availableSlots: [
            {id: 240, name: "BYPASS"},
            {id: 0, name: "Jazz"},
            {id: 1, name: "Pop"},
            {id: 2, name: "Rock"},
            {id: 3, name: "Dance"},
            {id: 4, name: "R&B"},
            {id: 5, name: "Classic"},
            {id: 6, name: "Hip-hop"},
            {id: 8, name: "Retro"},
            {id: 9, name: "sDamp-1"},
            {id: 10, name: "sDamp-2"},
            {id: 160, name: "USER1"},
            {id: 161, name: "USER2"},
            {id: 162, name: "USER3"},
            {id: 163, name: "USER4"},
            {id: 164, name: "USER5"},
            {id: 165, name: "USER6"},
            {id: 166, name: "USER7"},
            {id: 167, name: "USER8"},
            {id: 168, name: "USER9"},
            {id: 169, name: "USER10"}
          ]
        }
      },
      "FIIO BR15 R2R": {
        modelConfig: {
          peqConstraintsRef: "peq10Band12dBWideLSHP",
          firstWritableEQSlot: 160,
          maxWritableEQSlots: 10,
          disconnectOnSave: false,
          disabledPresetId: 240,
          availableSlots: [
            {id: 240, name: "BYPASS"},
            {id: 0, name: "Jazz"},
            {id: 1, name: "Pop"},
            {id: 2, name: "Rock"},
            {id: 3, name: "Dance"},
            {id: 4, name: "R&B"},
            {id: 5, name: "Classic"},
            {id: 6, name: "Hip-hop"},
            {id: 8, name: "Retro"},
            {id: 9, name: "sDamp-1"},
            {id: 10, name: "sDamp-2"},
            {id: 160, name: "USER1"},
            {id: 161, name: "USER2"},
            {id: 162, name: "USER3"},
            {id: 163, name: "USER4"},
            {id: 164, name: "USER5"},
            {id: 165, name: "USER6"},
            {id: 166, name: "USER7"},
            {id: 167, name: "USER8"},
            {id: 168, name: "USER9"},
            {id: 169, name: "USER10"}
          ]
        }
      },
      "FIIO FP3": {
        modelConfig: {
          peqConstraintsRef: "peq10Band12dBWideShelves",
          firstWritableEQSlot: 160,
          maxWritableEQSlots: 1,
          disconnectOnSave: false,
          availableSlots: [
            {id: 0, name: "Jazz"},
            {id: 1, name: "Pop"},
            {id: 2, name: "Rock"},
            {id: 3, name: "Dance"},
            {id: 4, name: "R&B"},
            {id: 5, name: "Classic"},
            {id: 6, name: "Hip-hop"},
            {id: 160, name: "USER1"}
          ]
        }
      },
      "SNOWSKY TINY A": {
        manufacturer: "FiiO",
        handler: fiioUsbHID,
        modelConfig: {
          peqConstraintsRef: "peq5Band12dBAllFilters7",
          firstWritableEQSlot: 160,
          maxWritableEQSlots: 3,
          disabledPresetId: 240,
          availableSlots: [
            {id: 0, name: "Jazz"},
            {id: 1, name: "Pop"},
            {id: 2, name: "Rock"},
            {id: 3, name: "Dance"},
            {id: 4, name: "R&B"},
            {id: 5, name: "Classic"},
            {id: 6, name: "Hip-hop"},
            {id: 160, name: "USER1"},
            {id: 161, name: "USER2"},
            {id: 162, name: "USER3"},
            {id: 240, name: "Close EQ"}
          ]
        }
      },

      "SNOWSKY TINY B": {
        manufacturer: "FiiO",
        handler: fiioUsbHID,
        modelConfig: {
          peqConstraintsRef: "peq5Band12dBAllFilters7",
          firstWritableEQSlot: 160,
          maxWritableEQSlots: 3,
          disabledPresetId: 240,
          availableSlots: [
            {id: 0, name: "Jazz"},
            {id: 1, name: "Pop"},
            {id: 2, name: "Rock"},
            {id: 3, name: "Dance"},
            {id: 4, name: "R&B"},
            {id: 5, name: "Classic"},
            {id: 6, name: "Hip-hop"},
            {id: 160, name: "USER1"},
            {id: 161, name: "USER2"},
            {id: 162, name: "USER3"},
            {id: 240, name: "Close EQ"}
          ]
        }
      },

      "FIIO FG3": {
        modelConfig: {
          peqConstraintsRef: "peq10Band12dBAllFiltersBP",
          firstWritableEQSlot: 160,
          maxWritableEQSlots: 10,
          disconnectOnSave: false,
          availableSlots: [
            {id: 0, name: "Jazz"},
            {id: 1, name: "Pop"},
            {id: 2, name: "Rock"},
            {id: 3, name: "Dance"},
            {id: 4, name: "R&B"},
            {id: 5, name: "Classic"},
            {id: 6, name: "Hip-hop"},
            {id: 12, name: "Cinema"},
            {id: 13, name: "FPS"},
            {id: 14, name: "MOBA"},
            {id: 15, name: "ACT"},
            {id: 16, name: "MUG"},
            {id: 160, name: "USER1"},
            {id: 161, name: "USER2"},
            {id: 162, name: "USER3"},
            {id: 163, name: "USER4"},
            {id: 164, name: "USER5"},
            {id: 165, name: "USER6"},
            {id: 166, name: "USER7"},
            {id: 167, name: "USER8"},
            {id: 168, name: "USER9"},
            {id: 169, name: "USER10"}
          ]
        }
      },
      "FIIO LS-TC2": {
        modelConfig: {
          peqConstraintsRef: "peq5Band12dBFullShelves",
          firstWritableEQSlot: 160,
          maxWritableEQSlots: 1,
          experimental: true,
          availableSlots: [
            {id: 0, name: "Jazz"},
            {id: 1, name: "Pop"},
            {id: 2, name: "Rock"},
            {id: 3, name: "Dance"},
            {id: 4, name: "R&B"},
            {id: 5, name: "Classic"},
            {id: 6, name: "Hip-hop"},
            {id: 160, name: "USER1"}
          ]
        }
      },
      // ── New FiiO devices identified from official FiiO app code ──────────────
      // USB product names are best-guess — verify by connecting the physical device.

      "FIIO OAK NANO": {  // be.FIIO_OAK_NANO — M6 exceptions: PK/LSQ/HSQ only; Z1 default: -24/+12
        modelConfig: {
          peqConstraintsRef: "peq10Band12dBWideShelves",
          firstWritableEQSlot: 160,
          maxWritableEQSlots: 1,
          disconnectOnSave: false,
          reportId: 7,
          availableSlots: [
            {id: 0, name: "Jazz"}, {id: 1, name: "Pop"}, {id: 2, name: "Rock"},
            {id: 3, name: "Dance"}, {id: 4, name: "R&B"}, {id: 5, name: "Classic"},
            {id: 6, name: "Hip-hop"}, {id: 160, name: "USER1"}
          ]
        }
      },
      "Oak Nano": {  // alternate USB product name variant
        modelConfig: {
          peqConstraintsRef: "peq10Band12dBWideShelves",
          firstWritableEQSlot: 160,
          maxWritableEQSlots: 1,
          disconnectOnSave: false,
          reportId: 7,
          availableSlots: [
            {id: 0, name: "Jazz"}, {id: 1, name: "Pop"}, {id: 2, name: "Rock"},
            {id: 3, name: "Dance"}, {id: 4, name: "R&B"}, {id: 5, name: "Classic"},
            {id: 6, name: "Hip-hop"}, {id: 160, name: "USER1"}
          ]
        }
      },

      "RETRO NANO": {  // be.RETRO_NANO — Z1 exception: -12/+12; all 7 FiiO filter types; reportId=7 (not in i4e)
        modelConfig: {
          peqConstraintsRef: "peq10Band12dBAllFilters7",
          firstWritableEQSlot: 160,
          maxWritableEQSlots: 3,
          disconnectOnSave: false,
          disabledPresetId: 11,
          reportId: 7,
          availableSlots: [
            {id: 0, name: "Jazz"}, {id: 1, name: "Pop"}, {id: 2, name: "Rock"},
            {id: 3, name: "Dance"}, {id: 4, name: "R&B"}, {id: 5, name: "Classic"},
            {id: 6, name: "Hip-hop"}, {id: 8, name: "Retro"},
            {id: 9, name: "sDamp-1"}, {id: 10, name: "sDamp-2"},
            {id: 160, name: "USER1"}, {id: 161, name: "USER2"}, {id: 162, name: "USER3"},
            {id: 11, name: "Close EQ"}
          ]
        }
      },

      "FIIO QX11": {  // be.FIIO_QX11 — Z1 exception: -12/+12; all 7 FiiO filter types; reportId=7 (not in i4e)
        modelConfig: {
          peqConstraintsRef: "peq10Band12dBAllFilters7",
          firstWritableEQSlot: 160,
          maxWritableEQSlots: 10,
          disconnectOnSave: false,
          availableSlots: [
            {id: 0, name: "Jazz"}, {id: 1, name: "Pop"}, {id: 2, name: "Rock"},
            {id: 3, name: "Dance"}, {id: 4, name: "R&B"}, {id: 5, name: "Classic"},
            {id: 6, name: "Hip-hop"}, {id: 8, name: "Retro"},
            {id: 9, name: "sDamp-1"}, {id: 10, name: "sDamp-2"},
            {id: 160, name: "USER1"}, {id: 161, name: "USER2"}, {id: 162, name: "USER3"},
            {id: 163, name: "USER4"}, {id: 164, name: "USER5"}, {id: 165, name: "USER6"},
            {id: 166, name: "USER7"}, {id: 167, name: "USER8"}, {id: 168, name: "USER9"},
            {id: 169, name: "USER10"}
          ]
        }
      },

      "FIIO AIR AMP": {  // be.FIIO_AIR_AMP — Z1 default: -24/+12; all 7 FiiO filter types; reportId=7 (not in i4e)
        modelConfig: {
          peqConstraintsRef: "peq10Band12dBWideAllFilters",
          firstWritableEQSlot: 160,
          maxWritableEQSlots: 10,
          disconnectOnSave: false,
          disabledPresetId: 240,
          availableSlots: [
            {id: 0, name: "Jazz"}, {id: 1, name: "Pop"}, {id: 2, name: "Rock"},
            {id: 3, name: "Dance"}, {id: 4, name: "R&B"}, {id: 5, name: "Classic"},
            {id: 6, name: "Hip-hop"}, {id: 7, name: "Monitor"},
            {id: 9, name: "sDamp-1"}, {id: 10, name: "sDamp-2"},
            {id: 160, name: "USER1"}, {id: 161, name: "USER2"}, {id: 162, name: "USER3"},
            {id: 163, name: "USER4"}, {id: 164, name: "USER5"}, {id: 165, name: "USER6"},
            {id: 166, name: "USER7"}, {id: 167, name: "USER8"}, {id: 168, name: "USER9"},
            {id: 169, name: "USER10"}, {id: 240, name: "BYPASS"}
          ]
        }
      },

      "FIIO DM15 R2R": {  // be.FIIO_DM15_R2R — FZ=-1 (likely CDC/serial, not standard HID); experimental
        modelConfig: {
          peqConstraintsRef: "peq10Band12dBWideAllFilters",
          firstWritableEQSlot: 160,
          maxWritableEQSlots: 10,
          disconnectOnSave: false,
          disabledPresetId: 240,
          experimental: true,
          availableSlots: [
            {id: 240, name: "BYPASS"},
            {id: 0, name: "Jazz"}, {id: 1, name: "Pop"}, {id: 2, name: "Rock"},
            {id: 3, name: "Dance"}, {id: 4, name: "R&B"}, {id: 5, name: "Classic"},
            {id: 6, name: "Hip-hop"},
            {id: 160, name: "USER1"}, {id: 161, name: "USER2"}, {id: 162, name: "USER3"},
            {id: 163, name: "USER4"}, {id: 164, name: "USER5"}, {id: 165, name: "USER6"},
            {id: 166, name: "USER7"}, {id: 167, name: "USER8"}, {id: 168, name: "USER9"},
            {id: 169, name: "USER10"}
          ]
        }
      },

      // Qudelix 5K / 5K+ removed: USB HID interface is write-only on macOS via WebHID
      // (vendor HID collections exist in the descriptor but no inputreport events fire).
      // EQ reads require BLE — not currently implemented. Push was functional but
      // the device is excluded until BLE read support is added.
    }
  },
  {
    vendorIds: [0x0104, 0x011B, 0x011D, 0x0661, 0x0663, 0x0666, 0x0762, 0x0909, 0x0D8C, 0x2FC6, 0x3302, 0x34BE, 0x35D8, 0x36A7, 0x373B, 0x60C1, 0x60E1, 0xB445, 0xB44D], // multiple Walkplay vendorIds
    manufacturer: "WalkPlay",
    handler: walkplayUsbHID,
    defaultModelConfig: {
      peqConstraintsRef: "walkplayPeq8Band10dBPkOnly",
      schemeNo: 10,
      firstWritableEQSlot: 101,
      maxWritableEQSlots: 1,
      disconnectOnSave: false,
      defaultResetFiltersValues:[{gain:0, freq: 100, q:1, filterType: "PK"}],
      deviceHandlesPregain: false,
      experimental: false,
      availableSlots: [{id: 101, name: "Custom"}],
      // Display config for extras UI — handler-agnostic labels for dacWorkMode
      dacWorkMode: { modes: [0, 1], modeLabels: ["Class H", "Class AB"] }
    },
    deviceGroups: {
      "SchemeNo11": {
        // OLA II PID is absent from the downloaded catalog, but its C20x
        // neighbours are all SchemeNo11. Keep the inferred PID with this group.
        additionalProductIds: [0xC20C],
        productIds: [0x0004, 0x00C0, 0x0104, 0x0880, 0x1230, 0x1231, 0x1233, 0x1237, 0x123F, 0x1240, 0x1241, 0x1243, 0x1244, 0x1245, 0x1248, 0x1249, 0x124A, 0x124B, 0x124C, 0x124D, 0x124E, 0x1251, 0x1261, 0x1262, 0x1264, 0x1266, 0x1269, 0x126A, 0x126B, 0x126C, 0x126D, 0x126E, 0x126F, 0x1272, 0x1278, 0x127A, 0x127D, 0x127E, 0x1281, 0x1282, 0x1283, 0x1284, 0x1285, 0x1286, 0x1287, 0x1288, 0x1289, 0x128A, 0x128B, 0x128C, 0x128D, 0x128E, 0x128F, 0x1292, 0x1293, 0x1294, 0x1295, 0x1296, 0x1297, 0x1298, 0x1299, 0x129A, 0x129B, 0x129C, 0x129D, 0x129F, 0x12B3, 0x12C0, 0x12C1, 0x12C3, 0x12C4, 0x12C5, 0x12C6, 0x12C8, 0x12C9, 0x12CA, 0x12CB, 0x12CC, 0x12CD, 0x12CE, 0x12DB, 0x12E9, 0x132B, 0x13A3, 0x13A4, 0x13A5, 0x13AB, 0x13C0, 0x13C1, 0x13D3, 0x13D4, 0x13D7, 0x13D9, 0x13DC, 0x3302, 0x4302, 0x43C1, 0x43C3, 0x43D1, 0x43D5, 0x43DC, 0x43E7, 0x51C0, 0x60D2, 0x9121, 0x9123, 0x9124, 0x9125, 0x93C0, 0x93C1, 0x93D1, 0x98C0, 0x98C1, 0x98C2, 0x98D1, 0x98D2, 0x98D5, 0xA862, 0xC204, 0xC207, 0xC208, 0xC209, 0xC20A, 0xC20E, 0xC20F, 0xC211, 0xC212, 0xC213, 0xC214, 0xC215, 0xC217, 0xF806, 0xF807, 0xFF01],
        modelConfig: {
          peqConstraintsRef: "walkplayPeq8Band10dBLsLowpass",
          schemeNo: 11,
          freqCompensation: { model: 'ratio', factor: 0.9775 },
          qCompensation: { model: 'cosNyquist', designFs: 96000 },
        }
      },
      "SchemeNo16": {
        productIds: [0x011D, 0x39E0, 0x4301, 0x4302, 0x4304, 0x4305, 0x4306, 0x430D, 0x430E, 0x430F, 0x4311, 0x4312, 0x4313, 0x4316, 0x4318, 0x4319, 0x431D, 0x4321, 0x4322, 0x4323, 0x4324, 0x4325, 0x4326, 0x4327, 0x4329, 0x432A, 0x432C, 0x4351, 0x4352, 0x4355, 0x4358, 0x4359, 0x435A, 0x435C, 0x435D, 0x435E, 0x4360, 0x4361, 0x4363, 0x4364, 0x4366, 0x4367, 0x4380, 0x4381, 0x4382, 0x4383, 0x4386, 0x43B1, 0x43B6, 0x43B7, 0x43B8, 0x43BC, 0x43BE, 0x43BF, 0x43C0, 0x43C2, 0x43C5, 0x43C6, 0x43C7, 0x43C8, 0x43C9, 0x43CA, 0x43CC, 0x43CD, 0x43CF, 0x43D7, 0x43D8, 0x43DA, 0x43DB, 0x43DE, 0x43E1, 0x43E4, 0x43E6, 0x43E8, 0x43EC, 0x43EF, 0x9201, 0x98D4, 0xEE10, 0xEE20, 0xF808],
        modelConfig: {
          peqConstraintsRef: "peq10Band10dBFullShelves",
          schemeNo: 16,
          deviceHandlesPregain: false
        }
      },
      "SchemeNo15": {
        productIds: [0x012A, 0x35D8, 0x39C1, 0x4353, 0x4357, 0x4362, 0x4370, 0x43CB, 0x43D6, 0x43D9, 0x43DF, 0x43E2, 0x43E3, 0x43EA, 0x43EB],
        modelConfig: {
          peqConstraintsRef: "walkplayPeq8Band10dBFullShelves",
          schemeNo: 15
        }
      },
      "SchemeNo13": {
        productIds: [0x011B, 0x0123, 0x120C, 0x1320, 0x1321, 0x1326, 0x1327, 0x1328, 0x1329, 0x132A, 0x1330, 0x1332, 0x1333, 0x1334, 0x13A9, 0x13AC, 0x13AE, 0x13AF, 0x13B0, 0x13B1, 0x13B2, 0x13B4, 0x13B6, 0x13B9, 0x13BA, 0x13BB, 0x13BE, 0x13BF, 0x13DF, 0x23C0, 0x23C1, 0x60C0, 0x60C1, 0x60C3, 0x60C6, 0x60C7, 0x60C9, 0x60CA, 0x60CB, 0x60D1, 0x60E1, 0xB301, 0xB302],
        modelConfig: {
          peqConstraintsRef: "walkplayPeq10Band10dBFullShelves",
          schemeNo: 13
        }
      },
      "SchemeNo17": {
        productIds: [0x2010, 0x201D, 0x201E, 0x2030, 0x2036, 0x2038, 0x203A, 0x203E, 0x2040, 0x2043, 0x2044, 0x2048, 0x2049, 0x20E1, 0x20E2, 0x20E3, 0x20E5, 0x20E7, 0x20E8, 0x20EA, 0x20EC, 0x20EE, 0x20EF, 0x20FF, 0x2DC1],
        modelConfig: {
          peqConstraintsRef: "walkplayPeq5Band10dBFullShelves",
          schemeNo: 17  // Per scratch 116
        }
      },
      "SchemeNo18": {
        productIds: [0x39A0, 0x39A2, 0x39A3, 0x39C2, 0x39C3, 0x39C4, 0x39C6, 0x39C7, 0x39C8, 0x39C9, 0x39CD, 0x39CE, 0x39CF, 0x44D1, 0x44D2, 0x44D3, 0x44D6, 0x44D7, 0x44D9],
        modelConfig: {
          peqConstraintsRef: "walkplayPeq10Band10dBFullShelves",
          schemeNo: 18
        }
      },
      "SchemeNo10": {
        productIds: [0x0881, 0x0888],
        modelConfig: {
          peqConstraintsRef: "walkplayPeq8Band10dBPkOnly",
          schemeNo: 10,
          // SchemeNo10 remains uncompensated pending device measurements.
        }
      },
      "SchemeNo19": {
        productIds: [0x129F, 0x231E, 0x231F, 0x2320, 0x2321, 0x2323, 0x2326, 0x232B, 0x232C, 0x2334, 0x23E2, 0x23EE],
        modelConfig: {
          peqConstraintsRef: "walkplayPeq6Band10dBFullShelves",
          schemeNo: 19
        }
      },
      "SchemeNo20": {
        productIds: [0x0883, 0x1323, 0x13B7],
        modelConfig: {
          peqConstraintsRef: "walkplayPeq10Band10dBPkOnly",
          schemeNo: 20
        }
      },
      "SchemeNo21": {
        productIds: [0x0880, 0x08F2, 0x13F2, 0x3DB0, 0x3DC1, 0x3DC4, 0x3DC5, 0x3DC6, 0x3DC7, 0x3DC8, 0x3DC9, 0x3DCA, 0x3DCC, 0x3DCD],
        modelConfig: {
          peqConstraintsRef: "walkplayPeq8Band10dBPkOnly",
          schemeNo: 21
        }
      }
    },
    devices: {
      "TANCHJIM-SPACE PRO": {
        manufacturer: "Tanchjim",
        modelConfig: {
          peqConstraintsRef: "walkplayPeq10Band10dBPkOnly",
          schemeNo: 16,
          deviceHandlesPregain: false
        }
      },
      "TANCHJIM-OLA II DSP": {
        // Matches the walkplayPeq8Band10dBPkOnly SchemeNo10/21 productId group like other
        // devices on this chip, but the Tanchjim app itself caps Q at 5.00 (not seen in any
        // other WalkPlay app) — likely a hardware limit specific to this model, so it's
        // overridden here rather than lowering maxQ for the whole shared scheme.
        manufacturer: "Tanchjim",
        modelConfig: {
          peqConstraintsRef: "walkplayPeq8Band10dBPkOnly",
          peqConstraintsOverride: { maxQ: 5.0 },
          deviceHandlesPregain: false
        }
      },
      "Old Fashioned": {
        manufacturer: "Moondrop",
        handler: moondropOldFashionedUsbHID,
        modelConfig: {
          peqConstraintsRef: "peq5Band3dBCeilingPkOnly",
          firstWritableEQSlot: -1,
          maxWritableEQSlots: 0,
          disconnectOnSave: false,
          disabledPresetId: -1,
          experimental: false,
          defaultResetFiltersValues: [{gain: 0, freq: 100, q: 1, filterType: "PK"}],
          availableSlots: [{id: 0, name: "Custom"}]
        }
      },
      "FIIO FX17": {
        manufacturer: "FiiO",
        handler: fiioUsbHID,
        modelConfig: {
          peqConstraintsRef: "peq10Band12dBWideAllFilters",
          firstWritableEQSlot: 160,
          maxWritableEQSlots: 1,
          disconnectOnSave: false,
          disabledPresetId: -1,
          experimental: false,
          availableSlots: [
            {id: 0, name: "Jazz"},
            {id: 1, name: "Pop"},
            {id: 2, name: "Rock"},
            {id: 3, name: "Dance"},
            {id: 4, name: "R&B"},
            {id: 5, name: "Classic"},
            {id: 6, name: "Hip-hop"},
            {id: 160, name: "USER1"}
          ]
        }
      },
      "Rays": {
        manufacturer: "Moondrop",
        handler: moondropUsbHidHandler,
        modelConfig: {
          peqConstraintsRef: "peq8Band12dBFullShelves",
          peqConstraintsOverride: { deviceHandlesPregain: true, supportsLSFilter: false, supportsHSFilter: false }
        }
      },
      "Marigold": {
        manufacturer: "Moondrop",
        handler: moondropUsbHidHandler,
        modelConfig: {
          peqConstraintsRef: "peq8Band6dBPkOnly"
        }
      },
      "MOONDROP Marigold": {  // actual USB product name variant
        manufacturer: "Moondrop",
        handler: moondropUsbHidHandler,
        modelConfig: {
          peqConstraintsRef: "peq8Band6dBPkOnly"
        }
      },
      "FreeDSP Pro": {
        manufacturer: "Moondrop",
        handler: moondropUsbHidHandler,
        modelConfig: {
          peqConstraintsRef: "peq8Band12dBFullShelves",
          peqConstraintsOverride: { deviceHandlesPregain: true }
        }
      },
      "MOONRIVER 3": {
        manufacturer: "Moondrop",
        handler: moondropUsbHidHandler,
        modelConfig: {
          peqConstraintsRef: "peq8Band12dBFullShelves",
          peqConstraintsOverride: { deviceHandlesPregain: true }
        }
      },
      "FreeDSP Mini": {
        manufacturer: "Moondrop",
        handler: moondropUsbHidHandler,
        modelConfig: {
          peqConstraintsRef: "peq8Band12dBFullShelves"
        }
      },
      "FreeDSP": {
        manufacturer: "Moondrop",
        handler: conexantUsbHidHandler,
        modelConfig: {
          peqConstraintsRef: "peq9Band12dBFullShelves",
          supportsLSFilter: true,
          supportsHSFilter: true,
          deviceHandlesPregain: false,
          maxFilters: 9
        }
      },
      "DAWN PRO2": {
        manufacturer: "Moondrop",
        handler: moondropUsbHidHandler,
        modelConfig: {
          peqConstraintsRef: "peq8Band12dBFullShelves"
        }
      },
      "Echo A": {
        manufacturer: "Moondrop",
        handler: moondropUsbHidHandler,
        modelConfig: {
          peqConstraintsRef: "peq8Band12dBFullShelves",
          peqConstraintsOverride: { supportsLSFilter: false, supportsHSFilter: false }
        }
      },
      "ECHO-B": {
        manufacturer: "Moondrop",
        handler: conexantUsbHidHandler,
        modelConfig: {
          peqConstraintsRef: "peq9Band12dBFullShelves",
          supportsLSFilter: true,
          supportsHSFilter: true,
          deviceHandlesPregain: false,
          maxFilters: 9
        }
      },
      "AG Rays": {
        manufacturer: "Moondrop",
        handler: moondropUsbHidHandler,
        modelConfig: {
          peqConstraintsRef: "peq8Band12dBFullShelves",
          peqConstraintsOverride: { supportsLSFilter: false, supportsHSFilter: false }
        }
      },
      "DHA15": {
        manufacturer: "Moondrop",
        handler: moondropUsbHidHandler,
        modelConfig: {
          peqConstraintsRef: "peq8Band12dBFullShelves"
        }
      },
      "Deco Audio System": {
        manufacturer: "Moondrop",
        handler: moondropUsbHidHandler,
        modelConfig: {
          peqConstraintsRef: "peq8Band12dBFullShelves"
        }
      },
      "INN Deco75-DH Audio": {
        manufacturer: "Moondrop",
        handler: moondropUsbHidHandler,
        modelConfig: {
          peqConstraintsRef: "peq8Band12dBFullShelves"
        }
      },
      "ddHiFi DSP IEM - Memory": {
        manufacturer: "Moondrop",
        handler: moondropUsbHidHandler
      },
      "Protocol Max": {
        manufacturer: "CrinEar",
        modelConfig: {
          peqConstraintsRef: "peq10Band10dBFullShelves",
          schemeNo: 16
          // deviceHandlesPregain intentionally omitted — falls back to the SchemeNo16
          // group default (false). The official WalkPlay app writes CMD 0x03 (offset)
          // directly with no built-in hardware buffer (confirmed from walkplayJS/walkplay-online.js:
          // Q2.setDacOffset(h.value.offset) — a plain user/preset value, default 0, no auto-gain).
          // Host must compute and write pregain like every other WalkPlay CB-chip device.
        }
      },
      "Octave": {
        manufacturer: "NiceHCK",
        modelConfig: {
          peqConstraintsRef: "walkplayPeq10Band10dBFullShelves",
          schemeNo: 18
        }
      },
      "CS43131 HiFi Audio DSP": {
        modelConfig: {
          peqConstraintsRef: "walkplayPeq8Band10dBLsLowpass",
          schemeNo: 11,
          // Has mic input but no ENC circuit — vendor site confirms micGain only, no denoise
          denoise: { supported: false }
        }
      },
      "CS43198 HiFi DSP Audio": {
        manufacturer: "Walkplay",
        handler: walkplayUsbHID,
        modelConfig: {
          peqConstraintsRef: "walkplayPeq8Band10dBLsLowpass",
          schemeNo: 11,
          deviceHandlesPregain: true
        }
      },
      "BGVP MX1": {
        modelConfig: {
          peqConstraintsRef: "walkplayPeq8Band10dBFullShelves",
          schemeNo: 15,
          experimental: true
        }
      },
      "DT04": {
        manufacturer: "LETSHUOER",
        modelConfig: {
          peqConstraintsRef: "walkplayPeq8Band10dBFullShelves",
          schemeNo: 15,
          experimental: true
        }
      },
      "MD-QT-042": {
        manufacturer: "Moondrop",
        modelConfig: {
          peqConstraintsRef: "walkplayPeq8Band10dBFullShelves",
          schemeNo: 15,
          experimental: true
        }
      },
      "MOONDROP HiFi with PD": {
        manufacturer: "Moondrop",
        modelConfig: {
          peqConstraintsRef: "walkplayPeq8Band10dBFullShelves",
          schemeNo: 15,
          experimental: true
        }
      },
      "DAWN PRO 2": {
        manufacturer: "Moondrop",
        modelConfig: {
          peqConstraintsRef: "walkplayPeq8Band10dBFullShelves",
          schemeNo: 15,
          experimental: false
        }
      },
      "CS431XX": {
        modelConfig: {
          peqConstraintsRef: "walkplayPeq8Band10dBFullShelves",
          schemeNo: 15,
          experimental: true
        }
      },
      "ES9039 ": {
        modelConfig: {
          peqConstraintsRef: "walkplayPeq8Band10dBFullShelves",
          schemeNo: 15,
          experimental: true
        }
      },
      "TANCHJIM-STARGATE II": {
        manufacturer: "Tanchim",
        modelConfig: {
          peqConstraintsRef: "walkplayPeq8Band10dBFullShelves",
          peqConstraintsOverride: { supportsLSFilter: false, supportsHSFilter: false },
          schemeNo: 15
        }
      },
      "didiHiFi DSP Cable - Memory": {
        manufacturer: "ddHifi",
        modelConfig: {
          peqConstraintsRef: "walkplayPeq8Band10dBFullShelves",
          schemeNo: 15
        }
      },
      "ddHiFi DSP Cable - Memory": {  // actual USB product name (didiHiFi was a typo)
        manufacturer: "ddHifi",
        modelConfig: {
          peqConstraintsRef: "walkplayPeq8Band10dBFullShelves",
          schemeNo: 15
        }
      },
      "Dual CS43198": {
        modelConfig: {
          peqConstraintsRef: "walkplayPeq8Band10dBFullShelves",
          schemeNo: 15,
          experimental: true
        }
      },
      "ES9039 HiFi DSP Audio": {
        modelConfig: {
          peqConstraintsRef: "walkplayPeq8Band10dBFullShelves",
          schemeNo: 15,
          experimental: true
        }
      },
      "TRUTHEAR KEYX": {
        manufacturer: "Truthear",
        modelConfig: {
          peqConstraintsRef: "walkplayPeq8Band10dBFullShelves",
          schemeNo: 15
        }
      }
    }
  },
  {
    vendorIds: [0x31B2],
    manufacturer: "KT Micro",
    handler: ktmicroUsbHidHandler,
    defaultModelConfig: {
      peqConstraintsRef: "peq5Band12dBFullShelvesNoPregain",
      firstWritableEQSlot: -1,
      maxWritableEQSlots: 0,
      compensate2X: true,  // Lets compensate by default
      disconnectOnSave: false,
      disabledPresetId: 0x02,
      experimental: false,
      defaultResetFiltersValues:[{gain:0, freq: 100, q:1, filterType: "PK"}],
      availableSlots: [{id: 0x03, name: "Custom"}]
    },
    devices: {
      "Kiwi Ears-Allegro PRO": {
        manufacturer: "Kiwi Ears",
        modelConfig: {
          peqConstraintsRef: "peq5Band12dBFullShelvesNoPregain",
          disconnectOnSave: true,
          compensate2X: false,
          baseRegisterOffset: 0x26
        }
      },
      "Kiwi Ears Allegro Mini": {   // config alias (space variant)
        manufacturer: "Kiwi Ears",
        modelConfig: {
          peqConstraintsRef: "peq5Band12dBFullShelvesNoPregain",
          disconnectOnSave: true,
          compensate2X: false,
          baseRegisterOffset: 0x26  // corrected from 0x35 — actual USB capture shows 0x26
        }
      },
      "Kiwi Ears-Allegro Mini": {  // actual USB product name (hyphen variant)
        manufacturer: "Kiwi Ears",
        modelConfig: {
          peqConstraintsRef: "peq5Band12dBFullShelvesNoPregain",
          disconnectOnSave: true,
          compensate2X: false,
          baseRegisterOffset: 0x26
        }
      },
      // UNVERIFIED — "Space Gaming IEM" (0x31B2/0x0001) does not answer the KT Micro
      // register protocol (reportId 0x4B, "<reg> 00 00 00 <cmd>"); reads time out.
      // The AuraPEQ web tool drives all 0x31B2 devices with the FiiO/JadeAudio framed
      // protocol (reportId 2, "AA 0A 00 00 <reg> <len> ... EE"), but it writes
      // fire-and-forget: its capture on this device shows TX only, never a single RX,
      // and it prints "Sync Complete" unconditionally. So JA11 is a guess-by-vendor-ID,
      // not a confirmed protocol. Kept experimental until a device response is observed
      // or an audible EQ change confirms writes land. Note the FiiO handler scales
      // pre-gain x10; AuraPEQ's JA11 path uses x2560 — unresolved, reg 0x17 never replied.
      "Space Gaming IEM": {
        manufacturer: "KT Micro",
        handler: fiioUsbHID,
        modelConfig: {
          peqConstraintsRef: "peq5Band12dBFullShelves",
          firstWritableEQSlot: 3,
          maxWritableEQSlots: 1,
          disconnectOnSave: true,
          disabledPresetId: 4,
          reportId: 2,
          experimental: true,
          availableSlots: [
            {id: 0, name: "Vocal"}, {id: 1, name: "Classic"}, {id: 2, name: "Bass"}, {id: 3, name: "USER1"}
          ]
        }
      },
      "KT02H20 HIFI Audio": {
        manufacturer: "JCally",
        modelConfig: {
          peqConstraintsRef: "peq5Band12dBFullShelvesNoPregain",
          peqConstraintsOverride: { supportsLSFilter: false, supportsHSFilter: false }
        }
      },
      "TANCHJIM-ONE DSP": {
        manufacturer: "TANCHJIM",
        modelConfig: {
          peqConstraintsRef: "peq5Band12dBFullShelvesNoPregain",
          compensate2X: false,
          baseRegisterOffset: 0x26
        }
      },
      "TANCHJIM BUNNY DSP": {
        manufacturer: "TANCHJIM",
        modelConfig: {
          peqConstraintsRef: "peq5Band12dBFullShelvesNoPregain",
          peqConstraintsOverride: { deviceHandlesPregain: false },
          compensate2X: false
        }
      },
      "TANCHJIM FISSION": {
        manufacturer: "TANCHJIM",
        modelConfig: {
          peqConstraintsRef: "peq5Band12dBFullShelvesNoPregain",
          peqConstraintsOverride: { deviceHandlesPregain: false },
          compensate2X: false,
          baseRegisterOffset: 0x26
        }
      },
      "TANCHJIM-FISSION  DSP": {  // USB HID product name variant (hyphen + double space)
        manufacturer: "TANCHJIM",
        modelConfig: {
          peqConstraintsRef: "peq5Band12dBFullShelvesNoPregain",
          peqConstraintsOverride: { deviceHandlesPregain: false },
          compensate2X: false,
          baseRegisterOffset: 0x26
        }
      },
      "CDSP": {
        manufacturer: "Moondrop",
        modelConfig: {
          peqConstraintsRef: "peq5Band12dBFullShelvesNoPregain",
          compensate2X: false
        }
      },
      "Chu2 DSP": {
        manufacturer: "Moondrop",
        modelConfig: {
          peqConstraintsRef: "peq5Band12dBFullShelvesNoPregain",
          compensate2X: false
        }
      }
    },
    // Matched by productId when productName is too generic to be reliable.
    // Register layouts mirror the official Kiwi Ears WebHID config (At.KT_*):
    //   KT_0211L: enable 0x24, bands 0-4 consecutive from 0x26               (Allegro family)
    //   KT_1132L: enable 0x34, bands 0-3 from 0x35, band4 freq 0x3D          (Chorus family)
    //   KT_3016L: enable 0x34, bands stored out of order (see bandRegisters)
    deviceGroups: {
      // KT_1132L — Kiwi Ears Chorus (0x1132) and sibling (0x3006). Reports generic
      // productName "Kiwi Ears", so match by productId. Bands 0-3 are consecutive from
      // 0x35, but Band4_Q is at 0x3F (register 0x3E is skipped — it's a phantom that
      // always reads 0.7). Confirmed on-device: setting band4 Q changes 0x3F, not 0x3E.
      "Kiwi Ears KT_1132L": {
        productIds: [0x1132, 0x3006],
        manufacturer: "Kiwi Ears",
        modelConfig: {
          peqConstraintsRef: "peq5Band12dBFullShelvesNoPregain",
          disconnectOnSave: true,
          compensate2X: false,
          bandRegisters: [
            { freq: 0x35, q: 0x36 }, // band0
            { freq: 0x37, q: 0x38 }, // band1
            { freq: 0x39, q: 0x3A }, // band2
            { freq: 0x3B, q: 0x3C }, // band3
            { freq: 0x3D, q: 0x3F }  // band4 — Q at 0x3F, NOT 0x3E
          ]
        }
      },
      // KT_0211L variants reached only by productId (Allegro 0x0111 matches by productName).
      "Kiwi Ears KT_0211L": {
        productIds: [0x0113],
        manufacturer: "Kiwi Ears",
        modelConfig: {
          peqConstraintsRef: "peq5Band12dBFullShelvesNoPregain",
          disconnectOnSave: true,
          compensate2X: false,
          baseRegisterOffset: 0x26
        }
      },
      // KT_3016L — Kiwi Ears S-Link (0x3016). Bands are stored OUT OF ORDER, so an explicit
      // per-band map is required. Verified on-device: this mapping yields ascending band
      // frequencies, and a write-readback confirmed band2 Q lives at 0x3F (0x3E is phantom).
      // Physical regs: 53/54=band4, 55/56=band0, 57/58=band3, 59/60=band1, 61/63=band2.
      "Kiwi Ears KT_3016L": {
        productIds: [0x3016],
        manufacturer: "Kiwi Ears",
        modelConfig: {
          peqConstraintsRef: "peq5Band12dBFullShelvesNoPregain",
          disconnectOnSave: true,
          compensate2X: false,
          bandRegisters: [
            { freq: 0x37, q: 0x38 }, // band0
            { freq: 0x3B, q: 0x3C }, // band1
            { freq: 0x3D, q: 0x3F }, // band2  (Q at 0x3F, NOT 0x3E)
            { freq: 0x39, q: 0x3A }, // band3
            { freq: 0x35, q: 0x36 }  // band4
          ]
        }
      }
    }
  },
  {
    vendorIds: [0x152A], // 5418 in decimal = 0x152A in hex (shared with Topping)
    manufacturer: "Fosi Audio",
    handler: fosiAudioUsbHID,
    defaultModelConfig: {
      peqConstraintsRef: "peq8Band12dBAllFiltersNoPregain",
      firstWritableEQSlot: 7,
      maxWritableEQSlots: 5,
      disconnectOnSave: false,
      disabledPresetId: 0,
      experimental: true,
      defaultResetFiltersValues:[{gain:0, freq: 100, q:1, filterType: "PK"}],
      reportId: 1,
      availableSlots: [
        {id: 0, name: "Bypass"},
        {id: 7, name: "Custom 1"},
        {id: 8, name: "Custom 2"},
        {id: 9, name: "Custom 3"},
        {id: 10, name: "Custom 4"},
        {id: 11, name: "Custom 5"}
      ]
    },
    // Topping USB detection disabled for this release (vendorId 0x152A is shared with
    // Topping's 0x8750 DX1 II). The handler (toppingUsbHidHandler.js) is kept in the
    // codebase, but no deviceGroups entry matches it, so it will no longer be detected.
    devices: {
      "Fosi Audio DS3": {
        modelConfig: {
          peqConstraintsRef: "peq8Band12dBAllFiltersNoPregain",
          disconnectOnSave: false,
          firstWritableEQSlot: 7,
          maxWritableEQSlots: 5,
          experimental: false,
          deviceHandlesPregain: true,
          qCompensation: { model: 'rbjGain' },
          bandwidthValue: 0,
          freqCompensation: { model: 'shelfSqrtA', fs: 48000 },
          availableSlots: [
            {id: 0, name: "Bypass"},
            {id: 7, name: "Custom 1"},
            {id: 8, name: "Custom 2"},
            {id: 9, name: "Custom 3"},
            {id: 10, name: "Custom 4"},
            {id: 11, name: "Custom 5"}
          ]
        }
      }
    }
  }
])

// ── Implementation identity ────────────────────────────────────────────────
// Which protocol implementation a device is actually driven by. This matters
// for diagnosis far more than the product name does: a PEQ defect almost always
// belongs to the handler and its scheme/variant, so every device sharing that
// combination tends to share the defect. Reporting "WalkPlay schemeNo 16"
// alongside a failure lets a result be generalised to its siblings instead of
// being filed against one model.
//
// A Map keyed on the handler objects themselves — they are module-level
// singletons, so identity comparison is exact and nothing has to be kept in
// sync with a string in each device entry.
const HANDLER_NAMES = new Map([
  [fiioUsbHID, 'fiioUsbHID'],
  [walkplayUsbHID, 'walkplayUsbHID'],
  [moondropUsbHidHandler, 'moondropUsbHidHandler'],
  [moondropOldFashionedUsbHID, 'moondropOldFashionedUsbHID'],
  [conexantUsbHidHandler, 'conexantUsbHidHandler'],
  [ktmicroUsbHidHandler, 'ktmicroUsbHidHandler'],
  [toppingUsbHidHandler, 'toppingUsbHidHandler'],
  [fosiAudioUsbHID, 'fosiAudioUsbHID'],
]);

export function handlerNameFor(handler) {
  return HANDLER_NAMES.get(handler) ?? null;
}
