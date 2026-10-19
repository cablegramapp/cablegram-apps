# tv-media

Plays MP4 and Matroska (MKV) on Samsung Tizen and LG webOS TVs. It demuxes the file in TypeScript, repackages the
samples as fragmented MP4 and feeds the TV's own hardware decoders through Media Source Extensions. Byte sources are
HTTP range requests and Telegram (MTProto).

Status: milestone 1, in progress. Plan: `docs/plans/tv-media-engine-m1.md`.

Licence: to be decided (the package is `UNLICENSED` until then).

Runs on Chromium 53 (webOS 4): pure TypeScript, no WebAssembly, no BigInt.

## Development

```
npm install
npm run fixtures   # needs ffmpeg; writes fixtures/ (not committed)
npm test
```
