# inKey — type on any surface, with just your phone's camera

inKey is an Android keyboard (a real system IME) that lets you type on an **empty table** in front of your propped-up phone. The front camera watches your hands with [MediaPipe hand tracking](https://ai.google.dev/edge/mediapipe/solutions/vision/hand_landmarker); taps, pinches, and glide gestures over an invisible keyboard become text in whatever app has focus. No extra hardware — no laser projection, no wearables, no depth sensor.

**How you type:**

| Gesture | What it does |
|---|---|
| ✌️ **Pinch + glide + release** | SwiftKey-style gesture typing: pinch thumb+index, sweep your finger through the word's letters (a cyan trail follows), release — the word is decoded from the path shape and committed with a space |
| 🤏 **Quick pinch (no movement)** | "Clicks" the key under your fingertip cursor — the most reliable single-key press |
| 👇 **Air-tap on the table** | A crisp down-tap of one finger types the key under it (palm-relative motion detection, so moving your whole hand does nothing) |
| ☝️ **Touchscreen tap** | Every key also responds to a normal screen touch, as a fallback |

Sloppy input is expected: every letter tap is recorded as a *probability distribution* over nearby keys, and hitting space decodes the whole word against a 20k-word / 100k-bigram English language model (with protection: if you precisely typed a real word, it's never "corrected" away). A suggestion bar shows live candidates you can tap to accept.

> **Status: working prototype.** Typing works end-to-end (demo app + system-wide IME), but precision is limited by monocular hand tracking. The path to reliability — a learned keystroke detector — is scaffolded in this repo (see [Training](#training-the-learned-keystroke-detector)).

---

## How it works

```
CameraX (front camera, RGBA frames)
        │
        ▼
HandTracker ──── MediaPipe HandLandmarker (LIVE_STREAM, GPU→CPU fallback)
        │              21 landmarks × 2 hands per frame, mirrored to match preview
        ▼
InputEngine ─── the shared brain, host-agnostic:
        │        • One-Euro filtering per fingertip (jitter kill, low lag)
        │        • translation-only calibration (slides the keyboard under your hands, 1:1 motion)
        │        • TapDetector: palm-relative dip + stop state machine, per-finger,
        │          aim captured BEFORE the dip (the dip itself moves the cursor)
        │        • pinch state machine (thumb–index distance / hand scale, hysteresis)
        │        • glide decoding: SHARK²-style path-shape matching over key centers
        │        • WordPredictor: unigram + bigram + tap-distribution decoding
        ▼
Output interface
   ├── MainActivity  → demo app (camera preview + practice buffer + Calibrate/Train buttons)
   └── InKeyImeService → real system keyboard (InputMethodService), commits text via
                         InputConnection; holds the camera ONLY while the keyboard is visible
```

### Source map

| File | What it is |
|---|---|
| [`InputEngine.kt`](app/src/main/java/com/inkey/proto/InputEngine.kt) | Full pipeline: camera binding, gesture state machines, word state, calibration |
| [`HandTracker.kt`](app/src/main/java/com/inkey/proto/HandTracker.kt) | MediaPipe wrapper (thread-safe close, strictly monotonic timestamps) |
| [`TapDetector.kt`](app/src/main/java/com/inkey/proto/TapDetector.kt) | Palm-relative tap state machine; all tuning knobs at the top |
| [`OneEuroFilter.kt`](app/src/main/java/com/inkey/proto/OneEuroFilter.kt) | Standard One-Euro filter (Casiez et al.) |
| [`KeyboardView.kt`](app/src/main/java/com/inkey/proto/KeyboardView.kt) | QWERTY overlay, fingertip dots, glide trail, per-tap letter distributions |
| [`WordPredictor.kt`](app/src/main/java/com/inkey/proto/WordPredictor.kt) | Tap-sequence decoding, glide path decoding, bigram context |
| [`InKeyImeService.kt`](app/src/main/java/com/inkey/proto/InKeyImeService.kt) | The system IME (with its own LifecycleOwner for CameraX) |
| [`MainActivity.kt`](app/src/main/java/com/inkey/proto/MainActivity.kt) | Demo/settings host + camera permission flow |
| [`TrainingActivity.kt`](app/src/main/java/com/inkey/proto/TrainingActivity.kt) / [`TrainingRecorder.kt`](app/src/main/java/com/inkey/proto/TrainingRecorder.kt) | Data-collection game for the learned detector |
| [`scripts/train_tap_model.py`](scripts/train_tap_model.py) | Offline trainer: landmark windows → Conv1D+GRU → TFLite |

Bundled assets: `hand_landmarker.task` (MediaPipe model, Apache-2.0, from Google), `words.txt` / `bigrams.txt` (top of [Peter Norvig's Google-corpus frequency lists](https://norvig.com/ngrams/)).

---

## Build & install

**Requirements:** Android SDK (compileSdk 36), JDK 17+, a physical Android phone (Android 8.0+, front camera). The Gradle wrapper is included.

```bash
git clone https://github.com/Mr-vero/inKey.git
cd inKey
# point local.properties at your SDK:
echo "sdk.dir=$HOME/Library/Android/sdk" > local.properties
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Enable it as your system keyboard

```bash
adb shell ime enable com.inkey.proto/.InKeyImeService
adb shell ime set com.inkey.proto/.InKeyImeService
```

or on the phone: **Settings → System → Languages & input → On-screen keyboard → Manage keyboards** → enable *inKey*, then select it from the keyboard picker. Open the inKey app once first to grant camera access — the IME can't show permission dialogs itself.

---

## Using it

1. **Prop the phone** against something on a table (portrait or landscape), front camera facing you, and sit so your hands rest on the table in the camera's view. Amber dots = your fingertips.
2. **Calibrate** (the ⌖ key, or the Calibrate button in the app): hold both hands in a comfortable typing pose during the 3-second countdown. This *slides* the keyboard under your hands — motion stays 1:1, nothing is stretched. Calibration is saved per orientation; redo it any time you move the phone (⌖ responds to a screen touch too, so you can never lock yourself out).
3. **Type** with pinch-glides for words, quick pinches or air-taps for single keys, space to trigger autocorrect, and the suggestion bar for alternatives.

The camera runs **only while the keyboard is visible**, everything is processed on-device, and no frames ever leave the phone.

---

## Training the learned keystroke detector

The current air-tap detector is a hand-tuned heuristic. The research consensus (Meta's [TouchInsight](https://arxiv.org/abs/2410.05940), UIST 2024; [Typing on Any Surface](https://arxiv.org/abs/2309.00174), IEEE VR 2024; Meta FRL's [surface touch typing decoder](https://dl.acm.org/doi/10.1145/3379337.3415816), UIST 2020) is that a small **temporal network over landmark sequences** dramatically outperforms geometric heuristics — treat keystroke detection like speech recognition, output probabilities, and let the language model decide.

This repo contains the full pipeline for that:

1. **Record** — the app's **Train** button opens a data-collection game: air-type the shown phrases (autocorrect disabled); every landmark frame and detected keystroke streams to a JSONL session file.
2. **Pull** the sessions:
   ```bash
   adb pull /sdcard/Android/data/com.inkey.proto/files/training data/
   ```
3. **Train** (TensorFlow):
   ```bash
   python3 -m venv .venv && .venv/bin/pip install tensorflow numpy
   .venv/bin/python scripts/train_tap_model.py --data data/ --out app/src/main/assets/tap_model.tflite
   ```
   The model is a Conv1D×2 + GRU over 15-frame windows of wrist-normalized landmarks, with two heads: P(keystroke) and which key (a–z, space, other). Backspace taps count as positives (same motion); pinch/touch events are excluded from training windows.

On-device TFLite inference wiring is the next step on the roadmap. Recorded sessions contain your hand-motion data — they are gitignored and should stay private.

---

## Design lessons learned (the hard way)

- **Never scale calibration.** Mapping a small hover zone onto the whole keyboard amplifies tracking jitter and the tap motion itself. Translation-only (1:1) is the only mapping that feels right.
- **Choose the key from the pre-dip aim point.** The tap gesture drags the cursor downward; by landing it's rows away from what the user meant.
- **Palm-relative motion, not screen motion.** Otherwise moving the hand fires every fingertip at once.
- **Pinches beat surface taps** for reliability: a pinch is fully visible to the camera; table contact must be inferred through the missing depth axis.
- **MediaPipe teardown is racy:** `detectAsync` after `close()` segfaults in native code — guard with a lock, and keep timestamps strictly increasing.

## Roadmap

- [ ] Wire the trained TFLite keystroke model into `InputEngine` (replaces the heuristic detector)
- [ ] Uncertainty-aware decoding end-to-end (per-tap Gaussians with a wider depth axis, per-finger variance)
- [ ] 60 fps camera capture where supported
- [ ] Shift/symbol/number layers
- [ ] Per-field behavior (no autocorrect in password fields)
- [ ] Neural language model + personalization
- [ ] Microphone tap-transient fusion (the phone rests on the surface being struck — parked for now, but the highest-value contact signal available)

## Acknowledgments & references

- [MediaPipe Hand Landmarker](https://ai.google.dev/edge/mediapipe/solutions/vision/hand_landmarker) (Google, Apache-2.0)
- Word/bigram frequencies from [Peter Norvig's ngram lists](https://norvig.com/ngrams/) (Google Web Trillion Word Corpus)
- One-Euro filter: Casiez, Roussel, Vogel — CHI 2012
- Shape writing: Kristensson & Zhai, SHARK² — UIST 2004
- TouchInsight: Streli et al. — UIST 2024 · Typing on Any Surface: Lyu et al. — IEEE VR 2024 · TypeAnywhere: Zhang et al. — CHI 2022
