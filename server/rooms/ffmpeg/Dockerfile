# The rooms server's ffmpeg (src/ffmpeg.ts): turns a shared song in a codec no
# browser plays into AAC for the website. Static ffmpeg and ffprobe, copied
# from mwader/static-ffmpeg, beside Python's own small web server.
FROM mwader/static-ffmpeg:7.1 AS ffmpeg

FROM python:3.12-alpine
COPY --from=ffmpeg /ffmpeg /ffprobe /usr/local/bin/
COPY server.py /server.py
EXPOSE 8080
CMD ["python3", "-u", "/server.py"]
