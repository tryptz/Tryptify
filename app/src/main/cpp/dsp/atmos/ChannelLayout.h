#pragma once

#include <cmath>

// ── 7.1.4 Dolby Atmos Speaker Layout ───────────────────────────────────
// 12 discrete channels: 7 ear-level + 1 LFE + 4 height
// Positions in spherical coordinates (azimuth degrees, elevation degrees)
// Azimuth: 0° = front, positive = left, negative = right
// Elevation: 0° = ear level, positive = above

namespace atmos {

enum Channel : int {
    FL   = 0,   // Front Left
    FR   = 1,   // Front Right
    FC   = 2,   // Front Center
    LFE  = 3,   // Low Frequency Effects
    BL   = 4,   // Back Left (Rear Left)
    BR   = 5,   // Back Right (Rear Right)
    SL   = 6,   // Side Left
    SR   = 7,   // Side Right
    TFL  = 8,   // Top Front Left (Height Front Left)
    TFR  = 9,   // Top Front Right (Height Front Right)
    TBL  = 10,  // Top Back Left (Height Back Left)
    TBR  = 11,  // Top Back Right (Height Back Right)
    NUM_CHANNELS = 12
};

// Speaker positions for VBAP triangulation
// {azimuth_deg, elevation_deg}
struct SpeakerPosition {
    float azimuth;    // degrees, 0=front, +left, -right
    float elevation;  // degrees, 0=ear, +up
    
    // Convert to unit Cartesian for VBAP
    void toCartesian(float& x, float& y, float& z) const {
        float azRad = azimuth * (3.14159265f / 180.0f);
        float elRad = elevation * (3.14159265f / 180.0f);
        x = std::cos(elRad) * std::sin(azRad);   // left/right
        y = std::cos(elRad) * std::cos(azRad);   // front/back
        z = std::sin(elRad);                      // up/down
    }
};

// ITU-R BS.2051 System H (7.1.4) speaker positions
static constexpr SpeakerPosition kSpeakerPositions[NUM_CHANNELS] = {
    {  30.0f,   0.0f },  // FL
    { -30.0f,   0.0f },  // FR
    {   0.0f,   0.0f },  // FC
    {   0.0f,   0.0f },  // LFE  (position irrelevant, receives bass content)
    { 135.0f,   0.0f },  // BL
    {-135.0f,   0.0f },  // BR
    {  90.0f,   0.0f },  // SL
    { -90.0f,   0.0f },  // SR
    {  45.0f,  45.0f },  // TFL
    { -45.0f,  45.0f },  // TFR
    { 135.0f,  45.0f },  // TBL
    {-135.0f,  45.0f },  // TBR
};

// ── VBAP Triangle Definitions ──────────────────────────────────────────
// Delaunay triangulation of the 7.1.4 speaker dome.
// Each triplet indexes into kSpeakerPositions (excluding LFE).
// These triangles tile the upper hemisphere for 3D VBAP panning.

struct VbapTriangle {
    int spk[3];
};

static constexpr int NUM_VBAP_TRIANGLES = 16;
static constexpr VbapTriangle kVbapTriangles[NUM_VBAP_TRIANGLES] = {
    // Ear-level front
    {{ FL,  FC,  TFL }},
    {{ FR,  FC,  TFR }},
    // Ear-level sides
    {{ FL,  SL,  TFL }},
    {{ FR,  SR,  TFR }},
    // Ear-level rear
    {{ SL,  BL,  TBL }},
    {{ SR,  BR,  TBR }},
    // Height front cap
    {{ TFL, FC,  TFR }},
    // Height rear cap
    {{ TBL, BL,  BR  }},
    {{ TBL, BR,  TBR }},
    // Height side bridges
    {{ TFL, SL,  TBL }},
    {{ TFR, SR,  TBR }},
    // Top cap
    {{ TFL, TFR, TBL }},
    {{ TFR, TBL, TBR }},
    // Lower front-side connections
    {{ FL,  FR,  FC  }},
    {{ SL,  BL,  FL  }},
    {{ SR,  BR,  FR  }},
};

// ── Bed channel mapping from EC-3 acmod ────────────────────────────────
// Maps the standard E-AC-3 channel configurations to our 7.1.4 layout.

enum class BedConfig : int {
    BED_2_0   = 0,   // L R
    BED_3_0   = 1,   // L C R
    BED_5_1   = 2,   // L C R Ls Rs LFE
    BED_7_1   = 3,   // L C R Ls Rs Lrs Rrs LFE
    BED_7_1_4 = 4,   // Full 7.1.4
};

// Downmix coefficients for folding 7.1.4 down to stereo (ITU-R BS.775)
static constexpr float kDownmixStereo[NUM_CHANNELS][2] = {
    // {left_gain, right_gain}
    { 1.000f, 0.000f },  // FL  -> L
    { 0.000f, 1.000f },  // FR  -> R
    { 0.707f, 0.707f },  // FC  -> L+R at -3dB
    { 0.707f, 0.707f },  // LFE -> L+R at -3dB (optional, often -10dB)
    { 0.707f, 0.000f },  // BL  -> L at -3dB
    { 0.000f, 0.707f },  // BR  -> R at -3dB
    { 0.707f, 0.000f },  // SL  -> L at -3dB
    { 0.000f, 0.707f },  // SR  -> R at -3dB
    { 0.866f, 0.000f },  // TFL -> L at ~-1.25dB
    { 0.000f, 0.866f },  // TFR -> R at ~-1.25dB
    { 0.500f, 0.000f },  // TBL -> L at -6dB
    { 0.000f, 0.500f },  // TBR -> R at -6dB
};

} // namespace atmos
