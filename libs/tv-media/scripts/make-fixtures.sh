#!/bin/sh
# Generates short test media in ../fixtures with ffmpeg (not committed), plus ffprobe packet dumps (<name>.packets.json).
set -e
cd "$(dirname "$0")/.."
mkdir -p fixtures
cd fixtures

DUR=12
V="-f lavfi -i testsrc2=size=320x180:rate=25 -t $DUR"
A="-f lavfi -i sine=frequency=440:sample_rate=48000 -t $DUR"
A2="-f lavfi -i sine=frequency=880:sample_rate=48000 -t $DUR"
X264="-c:v libx264 -preset ultrafast -g 25 -bf 2 -pix_fmt yuv420p"

cat > subs.srt <<'SRT'
1
00:00:01,000 --> 00:00:03,000
Hello world

2
00:00:04,500 --> 00:00:06,000
Second line
with a break
SRT
cat > subs.ass <<'ASS'
[Script Info]
ScriptType: v4.00+

[V4+ Styles]
Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding
Style: Default,Arial,20,&H00FFFFFF,&H000000FF,&H00000000,&H00000000,0,0,0,0,100,100,0,0,1,2,2,2,10,10,10,1

[Events]
Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
Dialogue: 0,0:00:01.00,0:00:03.00,Default,,0,0,0,,{\i1}Styled{\i0} text, with comma
Dialogue: 0,0:00:04.50,0:00:06.00,Default,,0,0,0,,Line one\NLine two
ASS

gen() { # name ffmpeg-args...
  name=$1; shift
  [ -f "$name" ] || ffmpeg -loglevel error -y "$@" -t $DUR "$name"
  ffprobe -loglevel error -show_entries stream=index,codec_name,codec_type,time_base,start_time,duration:packet=stream_index,pts,dts,duration,size,flags,pos \
    -of json "$name" > "$name.packets.json"
}

gen mp4-faststart.mp4 $V $A $X264 -c:a aac -b:a 64k -movflags +faststart
gen mp4-moov-end.mp4 $V $A $X264 -c:a aac -b:a 64k
gen mp4-tx3g.mp4 $V $A -i subs.srt $X264 -c:a aac -b:a 64k -c:s mov_text -movflags +faststart
gen mp4-hevc.mp4 $V $A -c:v libx265 -preset ultrafast -g 25 -pix_fmt yuv420p -tag:v hvc1 -x265-params log-level=error -c:a aac -b:a 64k -movflags +faststart
gen mp4-ac3.mp4 $V $A $X264 -c:a ac3 -b:a 96k -movflags +faststart

# 2-minute file for buffer-policy and seek tests in the browser
[ -f mp4-long.mp4 ] || ffmpeg -loglevel error -y -f lavfi -i testsrc2=size=320x180:rate=25 -f lavfi -i sine=frequency=440:sample_rate=48000 -t 120 $X264 -c:a aac -b:a 64k -movflags +faststart mp4-long.mp4

gen mp4-mp3.mp4 $V $A $X264 -c:a libmp3lame -b:a 64k -movflags +faststart
gen mp4-opus.mp4 $V $A $X264 -c:a libopus -b:a 48k -strict -2 -movflags +faststart

gen mkv-srt.mkv $V $A -i subs.srt $X264 -c:a aac -b:a 64k -c:s srt
gen mkv-ac3-ass.mkv $V $A -i subs.ass $X264 -c:a ac3 -b:a 96k -c:s ass
gen mkv-two-audio.mkv $V $A $A2 -map 0:v -map 1:a -map 2:a $X264 -c:a aac -b:a 64k
# ffmpeg always writes Cues; the true no-Cues, stripped-header and unknown-size variants are built in test/mkv.test.ts
gen mkv-no-cues.mkv $V $A $X264 -c:a aac -b:a 64k -write_crc32 0 -cues_to_front 0 -reserve_index_space 0 -f matroska -avoid_negative_ts make_zero
gen webm-vp9-opus.webm $V $A -c:v libvpx-vp9 -deadline realtime -cpu-used 8 -g 25 -c:a libopus -b:a 48k

if command -v mkvmerge >/dev/null 2>&1; then
  [ -f mkv-header-stripped.mkv ] || mkvmerge -q -o mkv-header-stripped.mkv --compression 0:header_removal mkv-srt.mkv
  [ -f mkv-no-cues.mkv.tmp ] || true
else
  echo "mkvmerge not found: skipping mkv-header-stripped.mkv"
fi
echo "fixtures ready"
