// Loads and renders presets one at a time through projectM 4.1.6 (GLES build,
// ASan) on a headless EGL context, recording what happens to each.
//
// usage: harness <list> <first> <last> <progress> <results> <frames>
//   list     - one absolute preset path per line
//   progress - overwritten with the index about to run, so a parent can tell
//              which preset was on when the process died
//   results  - appended: index \t status \t load_ms \t frame_ms \t detail

#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES3/gl3.h>

#include <projectM-4/projectM.h>

#include <chrono>
#include <cmath>
#include <csignal>
#include <cstdio>
#include <cstdlib>
#include <fstream>
#include <string>
#include <unistd.h>
#include <vector>

static std::string g_failMsg;
static bool g_failed = false;

static void onFail(const char*, const char* message, void*) {
    g_failed = true;
    g_failMsg = message ? message : "";
}

static double ms(std::chrono::steady_clock::time_point a, std::chrono::steady_clock::time_point b) {
    return std::chrono::duration<double, std::milli>(b - a).count();
}

int main(int argc, char** argv) {
    if (argc < 7) return 2;
    std::vector<std::string> list;
    {
        std::ifstream in(argv[1]);
        std::string line;
        while (std::getline(in, line)) list.push_back(line);
    }
    const int first = atoi(argv[2]);
    const int last = std::min<int>(atoi(argv[3]), (int) list.size() - 1);
    const char* progress = argv[4];
    const char* results = argv[5];
    const int frames = atoi(argv[6]);
    const int timeoutSec = getenv("PRESET_TIMEOUT") ? atoi(getenv("PRESET_TIMEOUT")) : 30;

    auto getPlatformDisplay = (PFNEGLGETPLATFORMDISPLAYEXTPROC) eglGetProcAddress("eglGetPlatformDisplayEXT");
    EGLDisplay dpy = getPlatformDisplay(EGL_PLATFORM_SURFACELESS_MESA, EGL_DEFAULT_DISPLAY, nullptr);
    if (!eglInitialize(dpy, nullptr, nullptr)) { fprintf(stderr, "eglInitialize failed\n"); return 3; }
    eglBindAPI(EGL_OPENGL_ES_API);
    const EGLint cfgAttr[] = {EGL_SURFACE_TYPE, EGL_PBUFFER_BIT, EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
                              EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8, EGL_NONE};
    EGLConfig cfg;
    EGLint n = 0;
    if (!eglChooseConfig(dpy, cfgAttr, &cfg, 1, &n) || n == 0) { fprintf(stderr, "no config\n"); return 3; }
    const int W = 384, H = 384;
    const EGLint pbAttr[] = {EGL_WIDTH, W, EGL_HEIGHT, H, EGL_NONE};
    EGLSurface surf = eglCreatePbufferSurface(dpy, cfg, pbAttr);
    const EGLint ctxAttr[] = {EGL_CONTEXT_MAJOR_VERSION, 3, EGL_CONTEXT_MINOR_VERSION, 0, EGL_NONE};
    EGLContext ctx = eglCreateContext(dpy, cfg, EGL_NO_CONTEXT, ctxAttr);
    if (ctx == EGL_NO_CONTEXT || !eglMakeCurrent(dpy, surf, surf, ctx)) { fprintf(stderr, "no context\n"); return 3; }

    projectm_handle pm = projectm_create();
    if (!pm) { fprintf(stderr, "projectm_create failed\n"); return 3; }
    // Same knobs ProjectMBridge sets, at the app's default 32x24 mesh.
    projectm_set_window_size(pm, W, H);
    projectm_set_mesh_size(pm, 32, 24);
    projectm_set_fps(pm, 60);
    projectm_set_aspect_correction(pm, true);
    projectm_set_beat_sensitivity(pm, 1.0f);
    projectm_set_preset_locked(pm, true);
    projectm_set_soft_cut_duration(pm, 2.0);
    const char* texPaths[] = {getenv("TEXTURE_DIR") ? getenv("TEXTURE_DIR") : "/nonexistent"};
    projectm_set_texture_search_paths(pm, texPaths, 1);
    projectm_set_preset_switch_failed_event_callback(pm, onFail, nullptr);

    // Synthetic music: a kick at 2 Hz under a swept tone and some noise, at
    // a level after the bridge's auto-gain (it soft-clips into roughly +-1).
    std::vector<float> pcm(735 * 2);
    double phase = 0, t = 0;
    unsigned seed = 12345;

    for (int i = first; i <= last; ++i) {
        {
            FILE* p = fopen(progress, "w");
            fprintf(p, "%d\n", i);
            fclose(p);
        }
        alarm(timeoutSec);
        g_failed = false;
        g_failMsg.clear();
        auto t0 = std::chrono::steady_clock::now();
        projectm_load_preset_file(pm, list[i].c_str(), false);
        auto t1 = std::chrono::steady_clock::now();
        double worst = 0, total = 0;
        for (int f = 0; f < frames; ++f) {
            for (size_t s = 0; s < pcm.size() / 2; ++s) {
                t += 1.0 / 44100.0;
                const double beat = std::fmod(t, 0.5);
                const double kick = std::exp(-beat * 18.0) * std::sin(2 * M_PI * (60 + 90 * std::exp(-beat * 30)) * beat);
                phase += 2 * M_PI * (200 + 1800 * (0.5 + 0.5 * std::sin(t * 0.7))) / 44100.0;
                seed = seed * 1664525u + 1013904223u;
                const double noise = ((seed >> 9) / double(1u << 23) - 0.5) * 0.3;
                const float v = (float) std::tanh(0.9 * kick + 0.35 * std::sin(phase) + noise);
                pcm[s * 2] = v;
                pcm[s * 2 + 1] = (float) std::tanh(0.9 * kick + 0.35 * std::sin(phase * 1.01) - noise);
            }
            projectm_pcm_add_float(pm, pcm.data(), (unsigned) (pcm.size() / 2), PROJECTM_STEREO);
            auto a = std::chrono::steady_clock::now();
            projectm_opengl_render_frame(pm);
            glFinish();
            auto b = std::chrono::steady_clock::now();
            const double d = ms(a, b);
            total += d;
            if (d > worst) worst = d;
        }
        alarm(0);
        const GLenum err = glGetError();
        FILE* r = fopen(results, "a");
        std::string msg = g_failMsg;
        for (auto& c : msg) if (c == '\n' || c == '\t' || c == '\r') c = ' ';
        if (msg.size() > 300) msg.resize(300);
        fprintf(r, "%d\t%s\t%.1f\t%.2f\t%.1f\t%x\t%s\n", i, g_failed ? "load_failed" : "ok", ms(t0, t1),
                frames ? total / frames : 0.0, worst, err, msg.c_str());
        fclose(r);
    }
    projectm_destroy(pm);
    return 0;
}
