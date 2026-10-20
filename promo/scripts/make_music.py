"""Synthesize the original ZhiYu promo cue; no samples or existing score used.

Usage: python promo/scripts/make_music.py
Output: promo/public/daylight.wav (48 seconds, 48 kHz, stereo PCM16).
The deterministic score and synthesis can be freely edited with the promo.
"""

from pathlib import Path
import wave

import numpy as np


RATE = 48_000
DURATION = 48.0
BEAT = 0.75
BAR = 4 * BEAT
RNG = np.random.default_rng(20261020)
OUTPUT = Path(__file__).resolve().parents[1] / "public" / "daylight.wav"
MIX = np.zeros((int(RATE * DURATION), 2), dtype=np.float64)


def frequency(midi: float) -> float:
    return 440 * 2 ** ((midi - 69) / 12)


def place(signal: np.ndarray, when: float, gain: float, pan: float = 0.0) -> None:
    start = max(0, round(when * RATE))
    length = min(len(signal), len(MIX) - start)
    if length <= 0:
        return
    angle = (pan + 1) * np.pi / 4
    stereo = np.array([np.cos(angle), np.sin(angle)])
    MIX[start : start + length] += signal[:length, None] * stereo * gain


def felt_note(midi: int, seconds: float = 4.8) -> np.ndarray:
    """Soft additive piano-like tone with a rounded, non-clicking onset."""
    t = np.arange(round(seconds * RATE)) / RATE
    f = frequency(midi)
    tone = np.zeros(len(t))
    for harmonic, amplitude, decay in (
        (1, 1.0, 2.1),
        (2, 0.24, 1.1),
        (3, 0.10, 0.75),
        (4, 0.035, 0.45),
    ):
        detune = 1 + (harmonic - 1) * 0.00024
        tone += amplitude * np.sin(2 * np.pi * f * harmonic * detune * t) * np.exp(-t / decay)
    # A tiny unison adds life without metallic high-frequency transients.
    tone += 0.08 * np.sin(2 * np.pi * f * 1.0018 * t) * np.exp(-t / 1.7)
    envelope = np.minimum(t / 0.014, 1) * np.minimum((seconds - t) / 0.12, 1)
    return tone * envelope


def pad_note(midi: int, seconds: float = 4.6) -> np.ndarray:
    t = np.arange(round(seconds * RATE)) / RATE
    f = frequency(midi)
    tone = (
        np.sin(2 * np.pi * f * 0.9985 * t)
        + np.sin(2 * np.pi * f * 1.0015 * t)
        + 0.12 * np.sin(2 * np.pi * f * 2 * t)
    ) / 2.12
    attack = np.sin(np.minimum(t / 1.15, 1) * np.pi / 2) ** 2
    release = np.sin(np.minimum((seconds - t) / 1.7, 1) * np.pi / 2) ** 2
    return tone * attack * release


def score() -> None:
    # Sixteen bars in F; deliberately sparse, with small phrase variations.
    chords = [
        (41, 57, 60, 64, 67), (45, 55, 60, 64, 67),
        (46, 57, 60, 62, 65), (48, 55, 60, 62, 64),
        (38, 57, 60, 64, 65), (43, 58, 62, 65, 69),
        (48, 57, 60, 62, 64), (41, 57, 60, 64, 67),
        (41, 57, 60, 64, 67), (45, 55, 60, 64, 67),
        (46, 57, 60, 62, 65), (48, 55, 60, 62, 64),
        (38, 57, 60, 64, 65), (46, 57, 60, 62, 65),
        (48, 55, 60, 62, 64), (41, 57, 60, 65, 69),
    ]
    phrases = [
        ((0.5, 72), (2.25, 69)), ((0.5, 67), (2.5, 64)),
        ((1.0, 65), (2.5, 69)), ((0.75, 67), (2.75, 64)),
        ((0.5, 69), (2.25, 72)), ((0.75, 74), (2.5, 70)),
        ((1.0, 69), (2.5, 67)), ((0.75, 65),),
        ((0.5, 72), (2.0, 76), (3.25, 72)), ((1.0, 71), (2.75, 69)),
        ((0.5, 69), (2.25, 74)), ((0.75, 72), (2.75, 67)),
        ((0.5, 69), (2.0, 72)), ((0.75, 70), (2.5, 69)),
        ((0.5, 67), (2.5, 64)), ((0.65, 65),),
    ]
    # The B-natural passing tone in bar ten gently colors the A-minor harmony.
    for bar, (chord, melody) in enumerate(zip(chords, phrases, strict=True)):
        start = bar * BAR + 0.12
        for index, midi in enumerate(chord[1:]):
            place(pad_note(midi), start, 0.024, (index - 1.5) * 0.24)
        place(felt_note(chord[0], 3.5), start + 0.05, 0.13, -0.15)
        # Off-beat, lightly spread accompanying notes keep motion restrained.
        for index, midi in enumerate(chord[1:4]):
            at = start + (0.65 + index * 0.85) * BEAT
            place(felt_note(midi, 3.6), at, 0.054, (-1) ** index * 0.28)
        for beat, midi in melody:
            velocity = 0.15 + float(RNG.uniform(-0.013, 0.013))
            place(felt_note(midi, 4.5), start + beat * BEAT, velocity, 0.12)


def finish() -> np.ndarray:
    dry = MIX.copy()
    # Short, quiet stereo reflections. No feedback build-up or harsh tail.
    for delay, amount in ((0.097, 0.12), (0.173, 0.10), (0.291, 0.075), (0.443, 0.05)):
        samples = round(delay * RATE)
        MIX[samples:] += dry[:-samples, ::-1] * amount
    for channel in range(2):
        MIX[:, channel] -= MIX[:, channel].mean()
    fade_in = round(RATE * 1.0)
    fade_out = round(RATE * 2.2)
    MIX[:fade_in] *= np.linspace(0, 1, fade_in)[:, None] ** 1.6
    MIX[-fade_out:] *= np.linspace(1, 0, fade_out)[:, None] ** 1.5
    peak = float(np.max(np.abs(MIX)))
    MIX[:] *= 0.72 / max(peak, 1e-12)
    return np.rint(np.clip(MIX, -1, 1) * 32767).astype("<i2")


def main() -> None:
    score()
    pcm = finish()
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    with wave.open(str(OUTPUT), "wb") as audio:
        audio.setnchannels(2)
        audio.setsampwidth(2)
        audio.setframerate(RATE)
        audio.writeframes(pcm.tobytes())
    peak = np.max(np.abs(pcm.astype(np.float64))) / 32768
    rms = np.sqrt(np.mean((pcm.astype(np.float64) / 32768) ** 2))
    print(f"{OUTPUT}\n{DURATION:.3f}s | {RATE} Hz | stereo PCM16 | peak={peak:.5f} | RMS={rms:.5f}")


if __name__ == "__main__":
    main()
