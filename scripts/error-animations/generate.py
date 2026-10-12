"""
Draws the TV's error animations: small looping GIFs bundled in the app, in the spirit of the error clips that
used to stream from media.cablegram.app/error/ (not copies of them).

    signal: the Cablegram mark on dark navy, red glitch bars, the mark tearing now and then
    rain:   neon city lights through a wet window, drops sliding down, the mark glowing like a sign
    frame:  an orange sci-fi frame that pulses, dust drifting, the mark dim in the middle

Each loop is 4 s at 12 fps and starts where it ends. The canvas is 4:3 and the app crops it to fill its panel
(close to square on the error screens, 16:9 during a handover), so anything that must stay whole sits in SAFE.
Frames are drawn at twice the size and scaled down, for smooth edges.

Run from the repository root:

    python3 -m venv /tmp/anim && /tmp/anim/bin/pip install pillow numpy
    /tmp/anim/bin/python scripts/error-animations/generate.py

Needs ffmpeg on PATH (palette generation keeps the GIFs small). Writes apps/android-tv/src/main/res/raw/error_*.gif.
"""

import math
import random
import shutil
import subprocess
import sys
import tempfile
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter

ROOT = Path(__file__).resolve().parents[2]
MARK = ROOT / "apps/android-tv/src/main/res/drawable-nodpi/cablegram_brand_mark.png"
OUT = ROOT / "apps/android-tv/src/main/res/raw"
OUT_W, OUT_H, FPS, SECONDS = 720, 540, 12, 4
SS = 2  # supersampling
W, H = OUT_W * SS, OUT_H * SS
U = W / 640  # one "design pixel"; sizes below are in design pixels
FRAMES = FPS * SECONDS
# What survives both crops of the 4:3 canvas: to ~1.07:1 (error screens) and to 16:9 (handover).
SAFE_W, SAFE_H = H * 1.07, W * 9 / 16

NAVY = (11, 17, 30)
CHARCOAL = (16, 17, 20)
RED = (255, 72, 88)
ORANGE = (244, 140, 60)


def u(value: float) -> int:
    return round(value * U)


def mark(height: float) -> Image.Image:
    image = Image.open(MARK).convert("RGBA")
    h = u(height)
    return image.resize((round(image.width * h / image.height), h), Image.LANCZOS)


def tinted(image: Image.Image, color) -> Image.Image:
    solid = Image.new("RGBA", image.size, color + (255,))
    solid.putalpha(image.getchannel("A"))
    return solid


def faded(image: Image.Image, strength: float) -> Image.Image:
    out = image.copy()
    out.putalpha(image.getchannel("A").point(lambda a: min(255, int(a * strength))))
    return out


def glow(image: Image.Image, radius: float, strength: float = 1.0) -> Image.Image:
    blurred = image.filter(ImageFilter.GaussianBlur(u(radius)))
    return faded(blurred, strength) if strength != 1.0 else blurred


def paste_center(canvas: Image.Image, layer: Image.Image, dx: float = 0, dy: float = 0) -> None:
    canvas.alpha_composite(layer, ((W - layer.width) // 2 + u(dx), (H - layer.height) // 2 + u(dy)))


def loop_phase(frame: int) -> float:
    return 2 * math.pi * frame / FRAMES


def dot(draw: ImageDraw.ImageDraw, x: float, y: float, r: float, **style) -> None:
    draw.ellipse([x - r, y - r, x + r, y + r], **style)


# --- signal ---------------------------------------------------------------------------------------------------

def signal_frames():
    rng = random.Random(7)
    logo = mark(120)
    logo_glow = glow(tinted(logo, (180, 200, 255)), 8, 0.5)
    # Bars live for a few frames each; their start frames are spread over the loop, so the loop has no seam.
    bars = [dict(
        start=rng.randrange(FRAMES), life=rng.randint(2, 7), y=rng.uniform(0, H), h=rng.choice([2, 2, 3, 4, 6]),
        x=rng.uniform(-100 * U, W), w=rng.randint(24, 220), speed=rng.choice([-18, -9, 9, 18, 30]),
    ) for _ in range(95)]
    tears = {9, 10, 11, 27, 28, 40}
    for f in range(FRAMES):
        canvas = Image.new("RGBA", (W, H), NAVY + (255,))
        draw = ImageDraw.Draw(canvas)
        for b in bars:
            age = (f - b["start"]) % FRAMES
            if age >= b["life"]:
                continue
            x, y, h = b["x"] + u(b["speed"] * age), b["y"], u(b["h"])
            draw.rectangle([x, y, x + u(b["w"]), y + h], fill=RED + (150 if b["h"] > 3 else 210,))
            draw.rectangle([x + u(4), y + h // 2, x + u(b["w"] - 4), y + h // 2 + SS - 1], fill=(255, 170, 170, 255))
        paste_center(canvas, logo_glow)
        if f in tears:
            # Colour split, then shift a few horizontal bands of the mark.
            shift = 5 if f % 2 else -5
            paste_center(canvas, tinted(logo, (255, 60, 80)), dx=shift)
            paste_center(canvas, tinted(logo, (80, 220, 255)), dx=-shift)
            pad = u(10)
            torn = Image.new("RGBA", (logo.width + 2 * pad, logo.height))
            band = 0
            while band < logo.height:
                size = u(rng.randint(6, 18))
                strip = logo.crop((0, band, logo.width, min(logo.height, band + size)))
                torn.alpha_composite(strip, (pad + u(rng.choice([-7, -3, 0, 0, 4, 8])), band))
                band += size
            paste_center(canvas, torn)
        else:
            paste_center(canvas, logo)
        yield canvas


# --- rain -----------------------------------------------------------------------------------------------------

def rain_frames():
    rng = random.Random(11)
    city = Image.new("RGBA", (W, H), (8, 12, 26, 255))
    lights = ImageDraw.Draw(city)
    neon = [(255, 70, 120), (80, 200, 255), (255, 180, 70), (255, 60, 60), (150, 120, 255), (255, 230, 160)]
    for _ in range(64):
        dot(lights, rng.uniform(0, W), rng.uniform(u(30), H - u(20)), u(rng.randint(6, 26)), fill=rng.choice(neon) + (rng.randint(80, 150),))
    for _ in range(12):  # tall signs
        x, y = rng.uniform(u(20), W - u(20)), rng.uniform(u(20), H - u(150))
        lights.rounded_rectangle([x, y, x + u(rng.randint(12, 22)), y + u(rng.randint(60, 130))], u(6), fill=rng.choice(neon) + (190,))
    city = city.filter(ImageFilter.GaussianBlur(u(12)))

    logo = mark(104)
    logo_glow = glow(tinted(logo, (90, 200, 255)), 9, 0.45)
    dim_glow, dim_logo = faded(logo_glow, 0.55), faded(logo, 0.7)
    beads = [(rng.uniform(0, W), rng.uniform(0, H), u(rng.uniform(2, 6))) for _ in range(46)]
    # A streak falls H + its tail over a whole number of loops, so it re-enters exactly where it left.
    streaks = [dict(x=rng.uniform(0, W), y0=rng.uniform(0, H), r=u(rng.uniform(3, 5)), laps=rng.choice([1, 1, 2])) for _ in range(12)]
    for f in range(FRAMES):
        canvas = city.copy()
        neon_off = f in (17, 19, 36)
        paste_center(canvas, dim_glow if neon_off else logo_glow)
        paste_center(canvas, dim_logo if neon_off else logo)
        draw = ImageDraw.Draw(canvas)
        for x, y, r in beads:
            dot(draw, x, y, r, fill=(190, 210, 255, 30), outline=(230, 240, 255, 85), width=SS)
        for s in streaks:
            span = H + u(60)
            y = (s["y0"] + span * s["laps"] * f / FRAMES) % span - u(30)
            draw.line([s["x"], y - u(40), s["x"], y], fill=(200, 220, 255, 45), width=u(2))
            dot(draw, s["x"], y, s["r"], fill=(210, 225, 255, 55), outline=(240, 245, 255, 130), width=SS)
        yield canvas


# --- frame ----------------------------------------------------------------------------------------------------

def hud_layer(intensity: float) -> Image.Image:
    layer = Image.new("RGBA", (W, H))
    draw = ImageDraw.Draw(layer)
    left, top = (W - SAFE_W) / 2 + u(14), (H - SAFE_H) / 2 + u(14)
    right, bottom = W - left, H - top
    c = u(18)
    outline = [(left + c, top), (right - c, top), (right, top + c), (right, bottom - c), (right - c, bottom),
               (left + c, bottom), (left, bottom - c), (left, top + c), (left + c, top)]
    draw.line(outline, fill=(150, 150, 160, 120), width=SS)
    bright = ORANGE + (int(110 + 145 * intensity),)
    span = right - left
    for y, d in ((top, u(3)), (bottom, -u(3))):
        xs = [left + span * k for k in (0.2, 0.27, 0.3, 0.7, 0.73, 0.8)]
        draw.line([(xs[0], y), (xs[1], y), (xs[2], y + d), (xs[3], y + d), (xs[4], y), (xs[5], y)], fill=bright, width=u(4))
    for i, width in enumerate((70, 46, 58)):  # placeholder "readout" text
        draw.rectangle([left + u(18), top + u(16 + i * 9), left + u(18 + width), top + u(19 + i * 9)], fill=(120, 122, 130, 140))
        draw.rectangle([right - u(18 + width), bottom - u(22 + i * 9), right - u(18), bottom - u(19 + i * 9)], fill=(120, 122, 130, 140))
    lit = layer.copy()
    lit.alpha_composite(glow(layer, 9, 0.2 + 1.6 * intensity))
    lit.alpha_composite(layer)
    return lit


def frame_frames():
    rng = random.Random(23)
    base = Image.new("RGBA", (W, H), CHARCOAL + (255,))
    vignette = Image.new("L", (W, H), 0)
    ImageDraw.Draw(vignette).ellipse([W * 0.18, H * 0.12, W * 0.82, H * 0.88], fill=255)
    base = Image.composite(Image.new("RGBA", (W, H), (30, 32, 37, 255)), base, vignette.filter(ImageFilter.GaussianBlur(u(60))))
    dim_logo = faded(mark(96), 0.55)
    dust = [dict(x=rng.uniform(0, W), y=rng.uniform(0, H), ax=u(rng.uniform(4, 16)), ay=u(rng.uniform(6, 20)),
                 phase=rng.uniform(0, 2 * math.pi), r=u(rng.choice([1, 1, 1.5, 2, 2.5])), a=rng.randint(45, 150)) for _ in range(60)]
    for f in range(FRAMES):
        t = loop_phase(f)
        canvas = base.copy()
        draw = ImageDraw.Draw(canvas)
        for d in dust:
            dot(draw, d["x"] + d["ax"] * math.sin(t + d["phase"]), d["y"] + d["ay"] * math.cos(t + d["phase"]), d["r"], fill=(220, 220, 225, d["a"]))
        canvas.alpha_composite(hud_layer(0.5 + 0.5 * math.sin(t * 2)))
        paste_center(canvas, dim_logo)
        yield canvas


# --- encoding -------------------------------------------------------------------------------------------------

def encode(name: str, frames) -> Path:
    target = OUT / f"error_{name}.gif"
    with tempfile.TemporaryDirectory() as tmp:
        for i, frame in enumerate(frames):
            frame.convert("RGB").resize((OUT_W, OUT_H), Image.LANCZOS).save(Path(tmp, f"{i:03d}.png"))
        pattern = str(Path(tmp, "%03d.png"))
        palette = str(Path(tmp, "palette.png"))
        subprocess.run(["ffmpeg", "-v", "error", "-y", "-framerate", str(FPS), "-i", pattern,
                        "-vf", "palettegen=max_colors=128:stats_mode=full", palette], check=True)
        subprocess.run(["ffmpeg", "-v", "error", "-y", "-framerate", str(FPS), "-i", pattern, "-i", palette,
                        "-lavfi", "paletteuse=dither=bayer:bayer_scale=5:diff_mode=rectangle", "-loop", "0", str(target)], check=True)
    return target


def main() -> None:
    if not shutil.which("ffmpeg"):
        sys.exit("ffmpeg is required")
    OUT.mkdir(parents=True, exist_ok=True)
    for name, frames in (("signal", signal_frames()), ("rain", rain_frames()), ("frame", frame_frames())):
        path = encode(name, frames)
        print(f"{path.relative_to(ROOT)}  {path.stat().st_size / 1024:.0f} KB")


if __name__ == "__main__":
    main()
