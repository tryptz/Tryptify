// Loads app/src/test/resources/mixer_state_fixture.json — the mixer as the
// Kotlin side writes it before the engine exists (DspStateJson) — into the
// engine, and checks the engine reads back every value in it. DspStateJsonTest
// pins the Kotlin writer to the same file, so between them a mix edited
// before anything has played reaches the engine intact.
//
//   state_fixture_test <fixture.json>

#include <cctype>
#include <cmath>
#include <cstdio>
#include <fstream>
#include <sstream>
#include <string>
#include <vector>

#include "../dsp_engine.h"

namespace {

/** Every number and true/false in [s], in order: the values, ignoring layout. */
std::vector<double> values(const std::string& s) {
    std::vector<double> out;
    for (size_t i = 0; i < s.size();) {
        const char c = s[i];
        if (s.compare(i, 4, "true") == 0) { out.push_back(1.0); i += 4; continue; }
        if (s.compare(i, 5, "false") == 0) { out.push_back(0.0); i += 5; continue; }
        if (c == '-' || std::isdigit(static_cast<unsigned char>(c))) {
            size_t end = i + 1;
            while (end < s.size() && (std::isdigit(static_cast<unsigned char>(s[end])) ||
                                      s[end] == '.' || s[end] == 'e' || s[end] == 'E' ||
                                      s[end] == '-' || s[end] == '+')) end++;
            out.push_back(std::stod(s.substr(i, end - i)));
            i = end;
            continue;
        }
        i++;
    }
    return out;
}

}  // namespace

int main(int argc, char** argv) {
    if (argc < 2) { std::fprintf(stderr, "usage: %s <fixture.json>\n", argv[0]); return 2; }
    std::ifstream in(argv[1]);
    std::stringstream buf;
    buf << in.rdbuf();
    const std::string fixture = buf.str();

    DspEngine e(48000, 512);
    e.loadStateJson(fixture);
    const std::string back = e.getStateJson(true);

    const std::vector<double> want = values(fixture);
    const std::vector<double> got = values(back);
    int failures = 0;
    if (want.size() != got.size()) {
        std::printf("FAIL  fixture has %zu values, engine wrote back %zu\n", want.size(), got.size());
        failures++;
    }
    for (size_t i = 0; i < want.size() && i < got.size(); i++) {
        if (std::fabs(want[i] - got[i]) > 1e-4 * (1.0 + std::fabs(want[i]))) {
            std::printf("FAIL  value %zu: fixture %g, engine %g\n", i, want[i], got[i]);
            if (++failures > 10) break;
        }
    }
    std::printf("%s  engine reads back all %zu values of the Kotlin-written mix (%d mix buses)\n",
                failures == 0 ? "ok  " : "FAIL", want.size(), e.mixBusCount());
    std::printf("%s\n", failures == 0 ? "all passed" : "FAILURES");
    return failures == 0 ? 0 : 1;
}
