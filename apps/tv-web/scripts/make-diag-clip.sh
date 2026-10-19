#!/bin/sh
# Builds public/diag/clip.mkv, the clip the on-TV Diagnostics screen plays: 3 s of noisy 1080p H.264 (about 10 Mbit/s, a
# worst-case-ish bitrate for a decoder), two AAC audio tracks (440 Hz English, 880 Hz German) and an SRT subtitle track.
set -e
cd "$(dirname "$0")/.."
tmp=$(mktemp -d)
cat > "$tmp/s.srt" <<'SRT'
1
00:00:00,500 --> 00:00:02,500
Cablegram diagnostics
SRT
ffmpeg -loglevel error -y \
  -f lavfi -i testsrc2=size=1920x1080:rate=25 -f lavfi -i sine=frequency=440:sample_rate=48000 -f lavfi -i sine=frequency=880:sample_rate=48000 -i "$tmp/s.srt" \
  -t 3 -map 0:v -map 1:a -map 2:a -map 3:s -vf noise=alls=25:allf=t \
  -c:v libx264 -preset veryfast -b:v 10M -maxrate 10M -bufsize 10M -g 25 -bf 2 -pix_fmt yuv420p -profile:v high -level 4.0 \
  -c:a aac -b:a 96k -c:s srt \
  -metadata:s:a:0 language=eng -metadata:s:a:1 language=deu -metadata:s:s:0 language=eng \
  public/diag/clip.mkv
ls -la public/diag/clip.mkv
