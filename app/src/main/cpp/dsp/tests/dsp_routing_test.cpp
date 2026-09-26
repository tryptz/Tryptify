// Host test for the 48-strip mixer's routing. Build and run:
//
//   c++ -std=c++17 -O2 -Ihost -I.. -I../util -I../snapins dsp_routing_test.cpp ../dsp_engine.cpp -o dsp_routing_test && ./dsp_routing_test
//
// Checks the send matrix end to end through DspEngine::process: default
// strip -> master, strip -> strip routes in dependency order (no block of
// delay), send levels, loop refusal, FL-style solo, and that saves (old
// 5-bus ones included) load onto the right buses with their routes.

#include <cmath>
#include <cstdio>
#include <string>
#include <vector>

#include "../dsp_engine.h"

namespace {

int failures = 0;

void check(bool ok, const char* what) {
    std::printf("%s  %s\n", ok ? "ok  " : "FAIL", what);
    if (!ok) ++failures;
}

constexpr int kRate = 48000;
constexpr int kBlock = 256;
// Equal-power pan law at centre, applied once per strip and once on master.
const float kCentre = std::cos(0.25f * 3.14159265f);

// Runs `blocks` blocks of constant 0.5 input; returns the last block's left sample.
float run(DspEngine& e, int blocks = 40) {
    std::vector<float> l(kBlock), r(kBlock);
    for (int b = 0; b < blocks; b++) {
        std::fill(l.begin(), l.end(), 0.5f);
        std::fill(r.begin(), r.end(), 0.5f);
        e.process(l.data(), r.data(), kBlock);
    }
    return l[kBlock - 1];
}

bool near(float a, float b) { return std::fabs(a - b) < 1e-3f; }

}  // namespace

int main() {
    {
        DspEngine e(kRate, kBlock);
        check(near(run(e), 0.5f * kCentre * kCentre), "default: strip 1 -> master");
        check(e.getSend(0, MASTER_BUS) == 1.0f && e.getSend(47, MASTER_BUS) == 1.0f,
              "every strip starts routed to master");
    }
    {
        DspEngine e(kRate, kBlock);
        check(e.setSend(0, 5, 1.0f), "route strip 1 -> strip 6");
        check(e.setSend(0, MASTER_BUS, 0.0f), "unroute strip 1 from master");
        check(near(run(e), 0.5f * kCentre * kCentre * kCentre), "strip 1 -> 6 -> master");
        check(!e.setSend(5, 0, 1.0f), "loop 6 -> 1 refused");
        check(e.setSend(5, 9, 1.0f) && !e.setSend(9, 0, 1.0f), "longer loop 10 -> 1 refused");
        check(!e.setSend(3, 3, 1.0f), "self-send refused");
    }
    {
        // Strip 8 feeds strip 4: 8 must run first even though its index is higher.
        DspEngine e(kRate, kBlock);
        e.setBusInputEnabled(0, false);
        e.setBusInputEnabled(7, true);
        e.setSend(7, MASTER_BUS, 0.0f);
        e.setSend(7, 3, 1.0f);
        std::vector<float> l(kBlock, 0.5f), r(kBlock, 0.5f);
        e.process(l.data(), r.data(), kBlock);
        check(std::fabs(l[kBlock - 1]) > 0.1f, "reverse-index route arrives in the same block");
    }
    {
        DspEngine e(kRate, kBlock);
        e.setSend(0, 5, 0.5f);  // keeps its master route too
        check(near(run(e), 0.5f * kCentre * kCentre * (1.0f + 0.5f * kCentre)), "send level 0.5 alongside master");
    }
    {
        DspEngine e(kRate, kBlock);
        e.setSend(0, 5, 1.0f);
        e.setSend(0, MASTER_BUS, 0.0f);
        e.setBusSolo(5, true);
        check(run(e) > 0.1f, "solo keeps the strip feeding the soloed one");
        e.setBusSolo(5, false);
        e.setBusSolo(20, true);
        check(near(run(e), 0.0f), "solo elsewhere silences the chain");
        e.setBusSolo(20, false);
        e.setBusMute(0, true);
        check(near(run(e), 0.0f), "muting the source silences its routes");
    }
    {
        DspEngine e(kRate, kBlock);
        e.setSend(0, 5, 0.25f);
        e.setSend(0, MASTER_BUS, 0.0f);
        e.setBusGain(MASTER_BUS, -6.0f);
        const std::string json = e.getStateJson();
        DspEngine f(kRate, kBlock);
        f.loadStateJson(json);
        check(f.getSend(0, 5) == 0.25f && f.getSend(0, MASTER_BUS) == 0.0f, "routes survive save/load");
        check(f.getSend(5, MASTER_BUS) == 1.0f, "untouched strips keep master route");
        check(f.getStateJson() == json, "save round-trips exactly");
    }
    {
        // A pre-48-strip save: 4 strips + master, no routes.
        std::string old = "{\"buses\":[";
        for (int b = 0; b < 5; b++) {
            if (b) old += ",";
            old += std::string("{\"gain\":") + (b == 4 ? "-6" : "0") +
                   ",\"pan\":0,\"muted\":false,\"soloed\":false,\"inputEnabled\":" +
                   (b == 0 ? "true" : "false") + ",\"plugins\":[]}";
        }
        old += "]}";
        DspEngine e(kRate, kBlock);
        e.loadStateJson(old);
        const std::string now = e.getStateJson();
        check(now.find("{\"gain\":-6") == now.rfind("{\"gain\":"), "old master lands on the master");
        check(e.getSend(0, MASTER_BUS) == 1.0f && e.getSend(3, MASTER_BUS) == 1.0f, "old strips route to master");
        check(near(run(e), 0.5f * kCentre * kCentre * std::pow(10.0f, -6.0f / 20.0f)), "old save plays as before");
    }
    {
        // Hand-edited loop in a file is dropped, not loaded.
        DspEngine e(kRate, kBlock);
        std::string j = "{\"buses\":[{\"gain\":0,\"pan\":0,\"muted\":false,\"soloed\":false,\"inputEnabled\":true,"
                        "\"sends\":[1,1],\"plugins\":[]},{\"gain\":0,\"pan\":0,\"muted\":false,\"soloed\":false,"
                        "\"inputEnabled\":false,\"sends\":[0,1,-1,1],\"plugins\":[]},{\"gain\":0,\"pan\":0,"
                        "\"muted\":false,\"soloed\":false,\"inputEnabled\":false,\"plugins\":[]}]}";
        e.loadStateJson(j);
        check(e.getSend(0, 1) == 1.0f && e.getSend(1, 0) == 0.0f && e.getSend(1, MASTER_BUS) == 1.0f,
              "looping route in a file is skipped");
    }
    std::printf("%s\n", failures ? "FAILED" : "all passed");
    return failures ? 1 : 0;
}
