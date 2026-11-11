"""Synthesizes Starwake's ship sounds (original, procedural) into assets/starwake/sounds/ship/*.ogg.

Usage: python3 tools/synth_sounds.py src/main/resources/assets/starwake/sounds/ship
Loops are built from whole cycles and circularly-filtered noise, so they repeat seamlessly.
Mono 44.1 kHz (Minecraft only positions mono sounds). Needs numpy and ffmpeg (libvorbis).
"""
import os, subprocess, sys, tempfile, wave
import numpy as np

SR = 44100
rng = np.random.default_rng(1977)
out_dir = sys.argv[1]


def t(seconds):
    return np.arange(int(SR * seconds)) / SR


def noise(seconds, lo, hi, tilt=0.0):
    """Periodic band-limited noise (FFT-shaped, so it loops seamlessly). tilt<0 = darker."""
    n = int(SR * seconds)
    spectrum = np.fft.rfft(rng.standard_normal(n))
    f = np.fft.rfftfreq(n, 1 / SR)
    gain = ((f >= lo) & (f <= hi)).astype(float)
    with np.errstate(divide="ignore"):
        gain *= np.where(f > 0, (f / max(lo, 1)) ** tilt, 0)
    sig = np.fft.irfft(spectrum * gain, n)
    return sig / (np.abs(sig).max() + 1e-9)


def env(seconds, attack, release):
    x = t(seconds)
    e = np.minimum(1, x / max(attack, 1e-4))
    return e * np.minimum(1, (seconds - x) / max(release, 1e-4))


def write(name, sig, peak=0.9):
    sig = sig / (np.abs(sig).max() + 1e-9) * peak
    pcm = (sig * 32767).astype(np.int16)
    with tempfile.NamedTemporaryFile(suffix=".wav", delete=False) as tmp:
        path = tmp.name
    with wave.open(path, "wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(SR); w.writeframes(pcm.tobytes())
    subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", path, "-c:a", "libvorbis", "-q:a", "5",
                    os.path.join(out_dir, name + ".ogg")], check=True)
    os.unlink(path)


# Main engine: deep turbine rumble with a slow throb (2 s loop; every frequency fits whole cycles).
x = t(2.0)
rumble = (np.sin(2 * np.pi * 55 * x) + 0.5 * np.sin(2 * np.pi * 110 * x) + 0.25 * np.sin(2 * np.pi * 165 * x))
rumble *= 0.8 + 0.2 * np.sin(2 * np.pi * 7 * x)
write("engine_loop", 0.6 * rumble + 0.8 * noise(2.0, 30, 400, -1.0) + 0.15 * noise(2.0, 800, 3000))

# Thruster: hissing burn (1 s loop).
x = t(1.0)
write("thruster_loop", noise(1.0, 300, 4000, -0.3) + 0.3 * np.sin(2 * np.pi * 80 * x))

# Maneuvering thruster puff: short pneumatic hiss.
x = t(0.25)
write("rcs_puff", noise(0.25, 1500, 9000) * np.minimum(1, x / 0.005) * np.exp(-x * 22), peak=0.7)

# Quantum spool: rising whine with shimmer (3 s).
x = t(3.0)
freq = 180 + 720 * (x / 3.0) ** 1.5
phase = 2 * np.pi * np.cumsum(freq) / SR
spool = np.sin(phase) + 0.4 * np.sin(2 * phase) + 0.2 * np.sin(3.01 * phase)
spool *= (x / 3.0) ** 0.7 * (0.85 + 0.15 * np.sin(2 * np.pi * 11 * x))
write("quantum_spool", spool * env(3.0, 0.3, 0.15) + 0.2 * noise(3.0, 2000, 8000) * (x / 3.0))

# Quantum engage: a falling boom, a swelling whoosh cut off sharply, a bright chime.
x = t(2.5)
boom_f = 70 * np.exp(-x * 1.2) + 28
boom = np.sin(2 * np.pi * np.cumsum(boom_f) / SR) * np.exp(-x * 1.5)
whoosh = noise(2.5, 200, 6000) * np.clip(x / 0.6, 0, 1) * np.exp(-np.maximum(0, x - 0.6) * 3)
chime = sum(np.sin(2 * np.pi * f * x) for f in (880, 1320, 1760)) * np.exp(-x * 2.5) * 0.25
write("quantum_engage", 1.2 * boom + 0.6 * whoosh + chime)

# Quantum travel: ethereal pad (4 s loop; chord and tremolo fit whole cycles).
x = t(4.0)
pad = sum(a * np.sin(2 * np.pi * f * x) for f, a in ((110, 1), (165, 0.7), (220, 0.5), (330, 0.35), (440.25, 0.2)))
pad *= 0.75 + 0.25 * np.sin(2 * np.pi * 0.5 * x)
write("quantum_travel_loop", pad + 0.35 * noise(4.0, 1000, 5000, -0.5), peak=0.6)

# Quantum exit: descending sweep into a thump.
x = t(1.5)
freq = 900 * np.exp(-x * 2.5) + 60
phase = 2 * np.pi * np.cumsum(freq) / SR
thump = np.sin(2 * np.pi * 45 * x) * np.exp(-np.maximum(0, x - 1.1) * 8) * (x > 1.1)
write("quantum_exit", (np.sin(phase) + 0.3 * np.sin(2 * phase)) * env(1.5, 0.02, 0.4) + 1.5 * thump
      + 0.3 * noise(1.5, 300, 4000) * np.exp(-x * 2))

# Cockpit alarm: two short tones.
x = t(0.5)
tone = np.where(x < 0.22, np.sin(2 * np.pi * 988 * x), np.where(x > 0.26, np.sin(2 * np.pi * 740 * x), 0))
tone = np.sign(tone) * np.abs(tone) ** 0.5  # a little squarer, more "alarm"
write("alarm", tone * env(0.5, 0.005, 0.03), peak=0.5)

# Landing gear: servo whine then a clunk.
x = t(1.2)
freq = 380 + 220 * np.minimum(1, x / 0.9)
phase = 2 * np.pi * np.cumsum(freq) / SR
servo = (np.sin(phase) + 0.5 * np.sin(2 * phase)) * (x < 0.95) * env(1.2, 0.05, 0.3)
clunk = noise(1.2, 40, 600, -1.0) * np.exp(-np.maximum(0, x - 0.95) * 25) * (x >= 0.95)
write("gear", 0.5 * servo + 1.2 * clunk)

# Re-entry: roaring plasma and wind (2 s loop).
x = t(2.0)
write("reentry_loop", noise(2.0, 40, 1200, -0.8) * (0.8 + 0.2 * np.sin(2 * np.pi * 3 * x)) + 0.4 * noise(2.0, 2000, 7000))

print("wrote", sorted(os.listdir(out_dir)))
