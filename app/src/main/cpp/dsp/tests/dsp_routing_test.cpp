// Host test for bus-to-bus routing (sends) in DspEngine. Build and run with
// the other host tests:
//
//   ./run_host_tests.sh
//
// Checks sends end to end through process(): the default (every bus to the
// master), bus-to-bus routes in dependency order with no block of delay,
// send levels, loop refusal, FL-style solo, delay compensation across a
// route, bus removal renumbering routes, and saves (with and without routes,
// old ones included).

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
constexpr int kBlock = 512;

// Runs [blocks] blocks of constant 0.5 input; the last block's last left sample.
float runDc(DspEngine& e, int blocks = 40) {
    std::vector<float> l(kBlock), r(kBlock);
    for (int b = 0; b < blocks; b++) {
        std::fill(l.begin(), l.end(), 0.5f);
        std::fill(r.begin(), r.end(), 0.5f);
        e.process(l.data(), r.data(), kBlock);
    }
    return l[kBlock - 1];
}

// Peak of the left output over the last half of [blocks] blocks of a sine.
float runSinePeak(DspEngine& e, double hz, int blocks = 80) {
    std::vector<float> l(kBlock), r(kBlock);
    float peak = 0.0f;
    long n = 0;
    for (int b = 0; b < blocks; b++) {
        for (int i = 0; i < kBlock; i++, n++) {
            l[i] = r[i] = static_cast<float>(0.25 * std::sin(2.0 * M_PI * hz * n / kRate));
        }
        e.process(l.data(), r.data(), kBlock);
        if (b >= blocks / 2) {
            for (float v : l) peak = std::max(peak, std::fabs(v));
        }
    }
    return peak;
}

bool near(float a, float b, float tol = 1e-3f) { return std::fabs(a - b) < tol; }

std::string oldFiveBusSave() {
    std::string s = "{\"buses\":[";
    for (int b = 0; b < 5; b++) {
        if (b) s += ",";
        s += std::string("{\"gain\":") + (b == 4 ? "-6" : "0") +
             ",\"pan\":0,\"muted\":false,\"soloed\":false,\"inputEnabled\":" +
             (b == 0 ? "true" : "false") + ",\"plugins\":[]}";
    }
    return s + "]}";
}

}  // namespace

int main() {
    {
        DspEngine e(kRate, kBlock);
        check(near(runDc(e), 0.5f), "default: bus 1 -> master at unity");
        check(e.getSend(0, MASTER_BUS) == 1.0f && e.getSend(3, MASTER_BUS) == 1.0f,
              "every bus starts routed to the master");
        check(e.getStateJson().find("\"sends\"") == std::string::npos,
              "an unrouted mix saves exactly as before routing existed");
    }
    {
        DspEngine e(kRate, kBlock);
        check(e.setSend(0, 1, 1.0f), "route bus 1 -> bus 2");
        check(e.setSend(0, MASTER_BUS, 0.0f), "unroute bus 1 from the master");
        check(near(runDc(e), 0.5f), "bus 1 -> bus 2 -> master");
        check(!e.setSend(1, 0, 1.0f), "loop bus 2 -> bus 1 refused");
        const int b5 = e.addBus();
        check(b5 == 5 && e.setSend(1, b5, 1.0f) && !e.setSend(b5, 0, 1.0f), "longer loop refused");
        check(!e.setSend(2, 2, 1.0f), "self-send refused");
        check(!e.setSend(MASTER_BUS, 0, 1.0f), "the master sends nowhere");
        check(!e.setSend(0, 30, 1.0f), "a bus that doesn't exist can't be routed to");
    }
    {
        // Bus 4 feeds bus 1: it has to run first although its index is higher.
        DspEngine e(kRate, kBlock);
        e.setBusInputEnabled(0, false);
        e.setBusInputEnabled(3, true);
        e.setSend(3, MASTER_BUS, 0.0f);
        e.setSend(3, 0, 1.0f);
        std::vector<float> l(kBlock, 0.5f), r(kBlock, 0.5f);
        e.process(l.data(), r.data(), kBlock);
        check(near(l[kBlock - 1], 0.5f, 2e-2f), "a route to a lower index arrives in the same block");
    }
    {
        DspEngine e(kRate, kBlock);
        e.setSend(0, 1, 0.5f);  // keeps its master route too
        check(near(runDc(e), 0.75f), "a send at 0.5 alongside the master route");
    }
    {
        DspEngine e(kRate, kBlock);
        e.setSend(0, 1, 1.0f);
        e.setSend(0, MASTER_BUS, 0.0f);
        e.setBusSolo(1, true);
        check(runDc(e) > 0.4f, "solo keeps the bus feeding the soloed one");
        e.setBusSolo(1, false);
        e.setBusSolo(2, true);
        check(near(runDc(e), 0.0f), "solo elsewhere silences the chain");
        e.setBusSolo(2, false);
        e.setBusMute(0, true);
        check(near(runDc(e), 0.0f), "muting the source silences its routes");
    }
    {
        // Delay compensation across a route: bus 1 carries an effect at 4x
        // oversampling (which delays it) and sends into bus 2, which also
        // takes the player's signal dry. Unaligned, the two would partly
        // cancel; aligned they add to twice one path.
        auto build = [](bool dry, bool latent) {
            auto e = std::make_unique<DspEngine>(kRate, kBlock);
            e->setBusInputEnabled(0, latent);
            e->addPlugin(0, 0, static_cast<int>(SnapinType::GAIN));
            e->setPluginOversampling(0, 0, 4);
            e->setSend(0, MASTER_BUS, 0.0f);
            e->setSend(0, 1, 1.0f);
            e->setBusInputEnabled(1, dry);
            return e;
        };
        const double hz = 12000.0;  // high, so a few samples' offset cancels plenty
        auto dryOnly = build(true, false);
        const float dry = runSinePeak(*dryOnly, hz);
        auto latentOnly = build(false, true);
        const float latent = runSinePeak(*latentOnly, hz);
        auto both = build(true, true);
        const float sum = runSinePeak(*both, hz);
        std::printf("      dry %.4f, latent %.4f, both %.4f\n", dry, latent, sum);
        // In step, the two add; a few samples apart they would cancel a
        // good part of each other at this frequency.
        check(dry > 0.2f && latent > 0.2f && near(sum, dry + latent, 0.004f),
              "a latent route arrives in step with the dry signal");
    }
    {
        DspEngine e(kRate, kBlock);
        const int b5 = e.addBus();
        const int b6 = e.addBus();
        e.setSend(0, b5, 0.7f);
        e.setSend(0, b6, 0.3f);
        check(e.removeBus(b5), "remove bus 5");
        check(near(e.getSend(0, 5), 0.3f) && e.getSend(0, 6) == 0.0f,
              "routes follow the buses above a removed one down");
    }
    {
        DspEngine e(kRate, kBlock);
        const int b5 = e.addBus();
        e.setSend(0, b5, 0.25f);
        e.setSend(0, MASTER_BUS, 0.0f);
        e.setBusGain(MASTER_BUS, -6.0f);
        const std::string json = e.getStateJson();
        DspEngine f(kRate, kBlock);
        f.loadStateJson(json);
        check(near(f.getSend(0, b5), 0.25f) && f.getSend(0, MASTER_BUS) == 0.0f, "routes survive save/load");
        check(f.getSend(1, MASTER_BUS) == 1.0f, "untouched buses keep their master route");
        check(f.getStateJson() == json, "a routed save round-trips exactly");
    }
    {
        DspEngine e(kRate, kBlock);
        e.loadStateJson(oldFiveBusSave());
        check(e.mixBusCount() == 4 && e.getSend(0, MASTER_BUS) == 1.0f && e.getSend(3, MASTER_BUS) == 1.0f,
              "an old save loads routed to the master");
        check(near(runDc(e), 0.5f * std::pow(10.0f, -6.0f / 20.0f)), "and plays as it did");
    }
    {
        // A hand-edited loop in a file is skipped, not loaded.
        DspEngine e(kRate, kBlock);
        const std::string bus = ",\"pan\":0,\"muted\":false,\"soloed\":false,\"inputEnabled\":";
        std::string j = "{\"buses\":[{\"gain\":0" + bus + "true,\"sends\":[1,1],\"plugins\":[]},"
                        "{\"gain\":0" + bus + "false,\"sends\":[0,1,4,1],\"plugins\":[]},"
                        "{\"gain\":0" + bus + "false,\"plugins\":[]},{\"gain\":0" + bus + "false,\"plugins\":[]},"
                        "{\"gain\":0" + bus + "false,\"plugins\":[]}]}";
        e.loadStateJson(j);
        check(e.getSend(0, 1) == 1.0f && e.getSend(1, 0) == 0.0f && e.getSend(1, MASTER_BUS) == 1.0f,
              "a looping route in a file is skipped");
    }
    {
        // A pristine bus the routing grew stays out of a save — unless
        // something sends to it, which makes it the user's.
        DspEngine e(kRate, kBlock);
        e.setRouting(0, 6);
        check(e.mixBusCount() == 6, "routing grows the mixer to six buses");
        const std::string plain = e.getStateJson();
        e.setSend(0, 5, 0.5f);
        const std::string routed = e.getStateJson();
        check(plain.find("\"sends\"") == std::string::npos && routed.find("\"sends\":[4,1,5,0.5]") != std::string::npos,
              "a grown bus something sends to is saved with the route");
        e.setRouting(-1, 0);
        check(e.mixBusCount() == 5, "narrowing drops the untouched grown bus, keeps the routed one");
    }
    std::printf("%s\n", failures ? "FAILURES" : "all passed");
    return failures ? 1 : 0;
}
