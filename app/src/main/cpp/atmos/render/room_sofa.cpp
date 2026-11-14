// Reads a room impulse response out of a SOFA file: the room databases'
// conventions (MultiSpeakerBRIR, SingleRoomSRIR, SingleRoomDRIR, GeneralFIR
// and their emitter forms), which libmysofa's own HRTF path refuses
// (mysofa_check). Only the raw loader is used here.
//
// A file holds M measurements of R receivers (and E emitters). The render
// wants two channels, one an ear:
//   - the first measurement and the first emitter;
//   - two receivers: both of two (a head, a BRIR); of more (a microphone
//     array), the one furthest left and the one furthest right;
//   - of one receiver and several emitters (one microphone, many
//     loudspeakers), two loudspeakers half the array apart, so the two ears
//     get tails that differ; of one and one, that one for both ears.
#include "room_sofa.h"

#include <cstring>

#include "mysofa.h"

namespace tf {
namespace atmos {

using render::RoomGeometry;

namespace {

const char* attribute(const MYSOFA_ATTRIBUTE* a, const char* name) {
  for (; a != nullptr; a = a->next) {
    if (a->name != nullptr && a->value != nullptr && std::strcmp(a->name, name) == 0) return a->value;
  }
  return nullptr;
}

// The receivers furthest left and furthest right (SOFA's y points left).
// Spherical positions are azimuth, elevation, radius in degrees and metres.
void outermost_receivers(const MYSOFA_HRTF* h, unsigned& left, unsigned& right) {
  left = 0;
  right = h->R - 1;
  const MYSOFA_ARRAY& pos = h->ReceiverPosition;
  if (pos.values == nullptr || pos.elements < h->R * 3) return;
  const char* type = attribute(pos.attributes, "Type");
  const bool spherical = type != nullptr && std::strcmp(type, "spherical") == 0;
  float most_left = 0.0f, most_right = 0.0f;
  for (unsigned r = 0; r < h->R; ++r) {
    float c[3] = {pos.values[r * 3], pos.values[r * 3 + 1], pos.values[r * 3 + 2]};
    if (spherical) mysofa_s2c(c);
    if (r == 0 || c[1] > most_left) { most_left = c[1]; left = r; }
    if (r == 0 || c[1] < most_right) { most_right = c[1]; right = r; }
  }
  if (left == right) { left = 0; right = h->R - 1; }  // every receiver in the middle
}

const MYSOFA_ARRAY* variable(const MYSOFA_HRTF* h, const char* name) {
  for (const MYSOFA_VARIABLE* v = h->variables; v != nullptr; v = v->next) {
    if (v->name != nullptr && v->value != nullptr && std::strcmp(v->name, name) == 0) return v->value;
  }
  return nullptr;
}

// Row `row` of a position array as cartesian metres. False when it is not there.
bool position(const MYSOFA_ARRAY& a, unsigned row, float* out) {
  if (a.values == nullptr || a.elements < (row + 1) * 3) return false;
  for (int c = 0; c < 3; ++c) out[c] = a.values[row * 3 + c];
  const char* type = attribute(a.attributes, "Type");
  if (type != nullptr && std::strcmp(type, "spherical") == 0) mysofa_s2c(out);
  return true;
}

// The room, for a file that gives its two corners (a shoebox). SOFA's axes
// are the room's own; the listener's are x ahead, y to the left. The room is
// turned to the wall the listener most nearly faced.
void read_geometry(const MYSOFA_HRTF* h, RoomGeometry& g) {
  g = RoomGeometry{};
  const MYSOFA_ARRAY* ca = variable(h, "RoomCornerA");
  const MYSOFA_ARRAY* cb = variable(h, "RoomCornerB");
  float a[3], b[3], at[3];
  if (ca == nullptr || cb == nullptr || !position(*ca, 0, a) || !position(*cb, 0, b)) return;
  float lo[3], size[3];
  for (int c = 0; c < 3; ++c) {
    lo[c] = a[c] < b[c] ? a[c] : b[c];
    size[c] = a[c] < b[c] ? b[c] - a[c] : a[c] - b[c];
  }
  if (!(size[0] > 0.1f) || !(size[1] > 0.1f)) return;
  float view[3] = {1.0f, 0.0f, 0.0f};
  position(h->ListenerView, 0, view);
  const float ax = view[0] < 0.0f ? -view[0] : view[0], ay = view[1] < 0.0f ? -view[1] : view[1];
  const int ahead = ax >= ay ? 0 : 1;                   // the room axis faced
  const bool up_it = view[ahead] >= 0.0f;               // towards its far end
  const int across = 1 - ahead;
  // Left of "ahead along +x" is +y; of "ahead along +y", -x.
  const bool left_is_far = (ahead == 0) == up_it;
  auto place = [&](const float* p, float* out) {
    const float along = p[ahead] - lo[ahead], side = p[across] - lo[across];
    out[0] = left_is_far ? size[across] - side : side;  // from the left wall
    out[1] = up_it ? size[ahead] - along : along;       // from the wall ahead
    out[2] = p[2] - lo[2];
  };
  g.sized = true;
  g.width = size[across];
  g.depth = size[ahead];
  g.height = size[2];
  if (position(h->ListenerPosition, 0, at)) {
    place(at, g.listener);
  } else {
    g.listener[0] = g.width / 2.0f;
    g.listener[1] = g.depth / 2.0f;
  }
  // The loudspeakers: an array's emitters about its one position, or else
  // each measurement's source; one that is given twice is drawn once.
  float base[3] = {0.0f, 0.0f, 0.0f};
  position(h->SourcePosition, 0, base);
  const char* etype = attribute(h->EmitterPosition.attributes, "Type");
  const bool array = h->E > 1 && etype != nullptr && std::strcmp(etype, "cartesian") == 0;
  const unsigned count = array ? h->E : h->M;
  for (unsigned i = 0; i < count && g.sources < RoomGeometry::kMaxSources; ++i) {
    float p[3];
    if (array) {
      if (!position(h->EmitterPosition, i, p)) break;
      for (int c = 0; c < 3; ++c) p[c] += base[c];
    } else if (!position(h->SourcePosition, i, p)) {
      break;
    }
    float placed[3];
    place(p, placed);
    bool seen = false;
    for (int k = 0; k < g.sources && !seen; ++k) {
      float d = 0.0f;
      for (int c = 0; c < 3; ++c) d += (g.source[k][c] - placed[c]) * (g.source[k][c] - placed[c]);
      seen = d < 1e-4f;
    }
    if (!seen) {
      for (int c = 0; c < 3; ++c) g.source[g.sources][c] = placed[c];
      ++g.sources;
    }
  }
}

}  // namespace

bool read_room_sofa(const char* data, long size, std::vector<float>& left,
                    std::vector<float>& right, double& rate, RoomGeometry& geometry) {
  if (data == nullptr || size <= 0) return false;
  int err = 0;
  MYSOFA_HRTF* h = mysofa_load_data(data, static_cast<size_t>(size), &err);
  if (h == nullptr) return false;
  bool ok = false;
  const unsigned emitters = h->E > 0 ? h->E : 1;
  const size_t per_measurement = static_cast<size_t>(h->R) * h->N;
  const MYSOFA_ARRAY& ir = h->DataIR;
  // Data.IR is [M][R][N], or with an emitter dimension [M][R][E][N] (FIRE)
  // or [M][R][N][E] (FIR-E); only the first measurement is read.
  const bool with_emitters = ir.elements >= per_measurement * emitters * h->M && emitters > 1;
  const char* type = attribute(h->attributes, "DataType");
  const bool emitter_last = type != nullptr && std::strcmp(type, "FIR-E") == 0;
  if (h->R > 0 && h->N > 0 && h->M > 0 && ir.values != nullptr && ir.elements >= per_measurement &&
      h->DataSamplingRate.values != nullptr && h->DataSamplingRate.elements > 0) {
    unsigned rl = 0, rr = 0, el = 0, er = 0;
    if (h->R >= 2) outermost_receivers(h, rl, rr);
    else if (with_emitters) er = emitters / 2;
    auto channel = [&](unsigned receiver, unsigned emitter, std::vector<float>& out) {
      out.resize(h->N);
      for (unsigned n = 0; n < h->N; ++n) {
        size_t at;
        if (!with_emitters) at = static_cast<size_t>(receiver) * h->N + n;
        else if (emitter_last) at = (static_cast<size_t>(receiver) * h->N + n) * emitters + emitter;
        else at = (static_cast<size_t>(receiver) * emitters + emitter) * h->N + n;
        out[n] = ir.values[at];
      }
    };
    channel(rl, el, left);
    channel(rr, er, right);
    rate = h->DataSamplingRate.values[0];
    read_geometry(h, geometry);
    ok = true;
  }
  mysofa_free(h);
  return ok;
}

}  // namespace atmos
}  // namespace tf
