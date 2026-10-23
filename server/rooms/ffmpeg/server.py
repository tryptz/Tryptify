# The rooms server's ffmpeg: one song in, and either "a browser plays it as it
# is" or the same song as AAC, for the website's listening party page. Only
# the rooms hub talks to it (src/ffmpeg.ts); it has no internet.
#
#   GET  /health   200
#   POST /web      the song as the body (Content-Length); 204 when a browser
#                  plays its codec as it is, else 200 with an AAC .m4a
#
# The codecs and the ffmpeg settings are the Rust server's
# (tryptz/tryptify-party, src/transcode.rs).

import os
import shutil
import subprocess
import tempfile
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

BROWSER_PLAYS = {"mp3", "aac", "flac", "opus", "vorbis", "pcm_s16le", "pcm_s24le", "pcm_f32le", "pcm_u8"}
MAX_BYTES = 100_000_000
# One song at a time: a song takes seconds, and the instance is small.
ONE_AT_A_TIME = threading.Lock()


def codec_of(path):
    out = subprocess.run(
        ["ffprobe", "-v", "error", "-select_streams", "a:0", "-show_entries", "stream=codec_name", "-of", "csv=p=0", path],
        capture_output=True, text=True, timeout=60,
    )
    codec = out.stdout.strip()
    return codec if out.returncode == 0 and codec else None


def to_aac(src, dst):
    done = subprocess.run(
        ["ffmpeg", "-nostdin", "-v", "error", "-y", "-i", src, "-vn", "-map", "0:a:0",
         "-c:a", "aac", "-b:a", "256k", "-movflags", "+faststart", "-f", "mp4", dst],
        timeout=600,
    )
    return done.returncode == 0


class Handler(BaseHTTPRequestHandler):
    def log_message(self, fmt, *args):
        print(fmt % args)

    def reply(self, status, body=b"", kind="text/plain"):
        self.send_response(status)
        self.send_header("content-type", kind)
        self.send_header("content-length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        self.reply(200, b"ok") if self.path == "/health" else self.reply(404)

    def do_POST(self):
        if self.path != "/web":
            return self.reply(404)
        length = int(self.headers.get("content-length") or 0)
        if length <= 0 or length > MAX_BYTES:
            return self.reply(413 if length else 411, b"say the song's length, at most 100 MB")
        work = tempfile.mkdtemp()
        try:
            src, dst = os.path.join(work, "in"), os.path.join(work, "out.m4a")
            with open(src, "wb") as f:
                left = length
                while left:
                    chunk = self.rfile.read(min(left, 1 << 20))
                    if not chunk:
                        return self.reply(400, b"the upload broke off")
                    f.write(chunk)
                    left -= len(chunk)
            with ONE_AT_A_TIME:
                codec = codec_of(src)
                if codec is None:
                    return self.reply(422, b"not a song ffprobe knows")
                if codec in BROWSER_PLAYS:
                    self.send_response(204)
                    self.send_header("x-codec", codec)
                    self.end_headers()
                    return
                if not to_aac(src, dst):
                    return self.reply(500, b"ffmpeg could not convert it")
            size = os.path.getsize(dst)
            self.send_response(200)
            self.send_header("content-type", "audio/mp4")
            self.send_header("content-length", str(size))
            self.send_header("x-codec", codec)
            self.end_headers()
            with open(dst, "rb") as f:
                shutil.copyfileobj(f, self.wfile, 1 << 20)
        finally:
            shutil.rmtree(work, ignore_errors=True)


ThreadingHTTPServer(("0.0.0.0", 8080), Handler).serve_forever()
