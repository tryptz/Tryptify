// Reads a room impulse response out of a SOFA file (room_sofa.cpp, libmysofa).
#ifndef TF_ATMOS_RENDER_ROOM_SOFA_H
#define TF_ATMOS_RENDER_ROOM_SOFA_H

#include <vector>

#include "measured_room.h"

namespace tf {
namespace atmos {

// One measurement of the file as two channels, one an ear, and what the file
// says of the room. False when the file cannot be read or holds no impulse
// responses. Allocates; loader thread.
bool read_room_sofa(const char* data, long size, std::vector<float>& left,
                    std::vector<float>& right, double& rate, render::RoomGeometry& geometry);

}  // namespace atmos
}  // namespace tf

#endif  // TF_ATMOS_RENDER_ROOM_SOFA_H
