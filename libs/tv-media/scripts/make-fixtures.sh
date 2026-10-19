#!/bin/sh
# Generates short test media in ../fixtures with ffmpeg (not committed). Added per task as demuxers land.
set -e
cd "$(dirname "$0")/.."
mkdir -p fixtures
echo "no fixtures yet"
