#!/usr/bin/env python3
"""
Generates the game's sound effects from scratch (sine tones, simple harmonics and noise), so every
sound is original and needs no license. Output: app/src/main/res/raw/*.wav (22.05 kHz, mono, 16-bit).

Run from the project root:  python3 tools/generate_sounds.py
Only the Python standard library is used. The random seed is fixed, so the output is reproducible.
"""
import math
import os
import random
import struct
import wave

RATE = 22050
OUT = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "res", "raw")
rng = random.Random(1234)


def silence(seconds):
    return [0.0] * int(RATE * seconds)


def mix(base, other, at=0.0):
    """Adds [other] into [base] starting at [at] seconds (extends base if needed)."""
    start = int(at * RATE)
    end = start + len(other)
    if end > len(base):
        base.extend([0.0] * (end - len(base)))
    for i, v in enumerate(other):
        base[start + i] += v
    return base


def tone(freq, seconds, volume=0.5, decay=6.0, harmonics=((1, 1.0),), attack=0.004, glide_to=None):
    """A sine tone with optional harmonics, a short attack and an exponential decay."""
    n = int(RATE * seconds)
    out = []
    phase = [0.0] * len(harmonics)
    for i in range(n):
        t = i / RATE
        f = freq if glide_to is None else freq + (glide_to - freq) * (i / n)
        env = min(1.0, t / attack) * math.exp(-decay * t)
        v = 0.0
        for k, (mult, amp) in enumerate(harmonics):
            phase[k] += 2 * math.pi * f * mult / RATE
            v += amp * math.sin(phase[k])
        out.append(volume * env * v)
    return out


def noise_burst(seconds, volume=0.5, decay=40.0, lowpass=0.35):
    """Filtered noise with a fast decay: clicks, knocks and rattles."""
    n = int(RATE * seconds)
    out = []
    last = 0.0
    for i in range(n):
        t = i / RATE
        raw = rng.uniform(-1, 1)
        last = last + lowpass * (raw - last)  # one-pole low-pass
        out.append(volume * math.exp(-decay * t) * last)
    return out


def normalize(samples, peak):
    top = max(1e-9, max(abs(s) for s in samples))
    return [s * peak / top for s in samples]


def fade_out(samples, seconds=0.02):
    n = min(len(samples), int(RATE * seconds))
    for i in range(n):
        samples[-1 - i] *= i / n
    return samples


def write(name, samples, peak=0.6):
    samples = fade_out(normalize(samples, peak))
    path = os.path.join(OUT, name + ".wav")
    with wave.open(path, "w") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(RATE)
        w.writeframes(b"".join(struct.pack("<h", int(max(-1, min(1, s)) * 32767)) for s in samples))
    print("wrote", os.path.relpath(path), f"{len(samples) / RATE:.2f}s")


def dice_roll():
    # A die rattling in a cup: irregular knocks that get sparser and quieter.
    out = silence(0.55)
    t = 0.0
    gap = 0.03
    volume = 0.9
    while t < 0.48:
        knock = noise_burst(0.05, volume, decay=70, lowpass=0.5)
        mix(out, knock, t)
        mix(out, tone(rng.uniform(900, 1500), 0.03, volume * 0.3, decay=90), t)
        t += gap + rng.uniform(0, 0.02)
        gap *= 1.18
        volume *= 0.9
    return out


def clack():
    # The die landing on the table: a short hard knock with a woody ring.
    out = noise_burst(0.05, 0.9, decay=90, lowpass=0.55)
    return mix(out, tone(740, 0.09, 0.35, decay=45, harmonics=((1, 1.0), (2.4, 0.4))))


def hop():
    # A soft wooden tick.
    return mix(tone(1100, 0.07, 0.6, decay=70, harmonics=((1, 1.0), (2.7, 0.3))), noise_burst(0.02, 0.2, decay=150, lowpass=0.6))


def capture():
    # A cartoon "bonk": a falling pitch with a knock on top.
    out = tone(520, 0.35, 0.8, decay=9, harmonics=((1, 1.0), (2, 0.25)), glide_to=150)
    return mix(out, noise_burst(0.06, 0.6, decay=50, lowpass=0.3))


def bell(freq, seconds, volume=0.5):
    return tone(freq, seconds, volume, decay=5.5, harmonics=((1, 1.0), (2, 0.35), (3.01, 0.15), (4.2, 0.08)))


def home():
    # A happy rising chime: C6, E6, G6.
    out = silence(0.8)
    for i, f in enumerate((1047, 1319, 1568)):
        mix(out, bell(f, 0.6, 0.5), i * 0.08)
    return out


def six():
    # A sparkle: a quick upward shimmer of high tones.
    out = silence(0.5)
    for i in range(7):
        mix(out, tone(1400 + i * 260, 0.18, 0.35, decay=18, harmonics=((1, 1.0), (2, 0.2))), i * 0.035)
    return out


def fanfare_note(freq, seconds, volume=0.5):
    # A brassy note: odd harmonics, a soft attack and slow decay.
    return tone(freq, seconds, volume, decay=2.2, attack=0.02, harmonics=((1, 1.0), (2, 0.4), (3, 0.3), (4, 0.15), (5, 0.1)))


def win():
    # A short victory fanfare: G4 C5 E5, then a C major chord.
    out = silence(1.7)
    for i, f in enumerate((392, 523, 659)):
        mix(out, fanfare_note(f, 0.22, 0.45), i * 0.13)
    for f in (523, 659, 784, 1047):
        mix(out, fanfare_note(f, 1.2, 0.3), 0.42)
    return out


def lose():
    # A gentle, friendly "oh well": two soft falling notes.
    out = silence(1.0)
    mix(out, tone(523, 0.45, 0.45, decay=4, attack=0.03, harmonics=((1, 1.0), (2, 0.15))), 0.0)
    mix(out, tone(392, 0.6, 0.45, decay=3.5, attack=0.03, harmonics=((1, 1.0), (2, 0.15))), 0.3)
    return out


def click():
    # A short button pop.
    return mix(tone(1800, 0.04, 0.5, decay=110), noise_burst(0.025, 0.4, decay=160, lowpass=0.7))


def your_turn():
    # A friendly two-note chime: E5 then A5.
    out = silence(0.5)
    mix(out, bell(659, 0.35, 0.5), 0.0)
    mix(out, bell(880, 0.4, 0.5), 0.12)
    return out


if __name__ == "__main__":
    os.makedirs(OUT, exist_ok=True)
    write("sfx_roll", dice_roll(), peak=0.55)
    write("sfx_clack", clack(), peak=0.5)
    write("sfx_hop", hop(), peak=0.35)
    write("sfx_capture", capture(), peak=0.6)
    write("sfx_home", home(), peak=0.5)
    write("sfx_six", six(), peak=0.45)
    write("sfx_win", win(), peak=0.55)
    write("sfx_lose", lose(), peak=0.45)
    write("sfx_click", click(), peak=0.4)
    write("sfx_your_turn", your_turn(), peak=0.45)
