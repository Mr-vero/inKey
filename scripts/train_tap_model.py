#!/usr/bin/env python3
"""Train the learned keystroke detector for inKey.

Consumes JSONL session files recorded by the app's Train mode
(pull them with:  adb pull /sdcard/Android/data/com.inkey.proto/files/training data/)
and trains a small temporal network in the style of "Typing on Any Surface"
(arXiv 2309.00174) / TouchInsight (UIST 2024):

  input : sliding windows of hand-landmark frames (both hands, wrist-normalized)
  output: P(keystroke at window center) + which key (a-z, space, other)

Exports a TFLite model for on-device inference.

Usage:
  python3 -m venv .venv && .venv/bin/pip install tensorflow numpy
  .venv/bin/python scripts/train_tap_model.py --data data/ --out app/src/main/assets/tap_model.tflite
"""

import argparse
import json
import math
import sys
from pathlib import Path

import numpy as np

WINDOW = 15            # frames per window (~0.5 s at 30 fps)
POSITIVE_MS = 60       # a window is positive if a key event is within this of its center
FEATS_PER_HAND = 1 + 21 * 3   # presence flag + 21 landmarks x (x, y, z)
NUM_FEATS = 2 * FEATS_PER_HAND
KEYS = "abcdefghijklmnopqrstuvwxyz "  # + "other" (backspace/enter/cal) + "no key"
OTHER_CLASS = len(KEYS)
NO_KEY_CLASS = len(KEYS) + 1
NUM_CLASSES = len(KEYS) + 2

WRIST, MID_MCP = 0, 9


def hand_features(hand):
    """Wrist-centered, scale-normalized landmark vector for one hand."""
    lm = np.array(hand["lm"], dtype=np.float32).reshape(21, 3)
    wrist = lm[WRIST].copy()
    scale = np.linalg.norm(lm[MID_MCP, :2] - wrist[:2])
    if scale < 1e-4:
        scale = 1e-4
    norm = (lm - wrist) / scale
    # Keep absolute wrist position too (where over the keyboard the hand is).
    norm[WRIST] = wrist
    return np.concatenate([[1.0], norm.flatten()])


def load_session(path):
    """Returns (frames, air-tap key events, ignore times).

    Every air tap counts as a detection positive — backspace included, since
    the motion is identical. Pinch-clicks (finger -1) and touchscreen presses
    (finger -2) are different motions: their neighborhoods are excluded from
    training entirely rather than mislabeled as negatives.
    """
    frames, keys, ignores = [], [], []
    with open(path) as f:
        for line in f:
            try:
                obj = json.loads(line)
            except json.JSONDecodeError:
                continue
            if obj["type"] == "frame":
                feat = np.zeros(NUM_FEATS, dtype=np.float32)
                for hand in obj.get("hands", []):
                    slot = 0 if hand.get("hand") == "Left" else 1
                    feat[slot * FEATS_PER_HAND:(slot + 1) * FEATS_PER_HAND] = hand_features(hand)
                frames.append((obj["t"], feat))
            elif obj["type"] == "key":
                label = obj.get("key", "")
                finger = obj.get("finger", 0)
                if finger in (-1, -2):
                    ignores.append(obj["t"])
                else:
                    keys.append((obj["t"], label))
    return frames, keys, ignores


def build_windows(frames, keys, ignores):
    xs, y_evt, y_key = [], [], []
    times = np.array([t for t, _ in frames])
    feats = np.stack([f for _, f in frames]) if frames else np.zeros((0, NUM_FEATS))
    key_times = np.array([t for t, _ in keys]) if keys else np.zeros(0)
    ignore_times = np.array(ignores) if ignores else np.zeros(0)
    half = WINDOW // 2
    for i in range(half, len(frames) - half):
        center_t = times[i]
        if len(ignore_times) and np.abs(ignore_times - center_t).min() <= 400:
            continue  # pinch/touch neighborhood: neither positive nor negative
        window = feats[i - half:i + half + 1]
        if len(key_times):
            j = np.abs(key_times - center_t).argmin()
            near = abs(key_times[j] - center_t) <= POSITIVE_MS
        else:
            near = False
        xs.append(window)
        if near:
            label = keys[j][1]
            y_evt.append(1.0)
            y_key.append(KEYS.index(label) if label in KEYS else OTHER_CLASS)
        else:
            y_evt.append(0.0)
            y_key.append(NO_KEY_CLASS)
    return np.array(xs), np.array(y_evt), np.array(y_key)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--data", default="data", help="directory of session_*.jsonl files")
    ap.add_argument("--out", default="app/src/main/assets/tap_model.tflite")
    ap.add_argument("--epochs", type=int, default=40)
    args = ap.parse_args()

    sessions = sorted(Path(args.data).rglob("session_*.jsonl"))
    if not sessions:
        sys.exit(f"No session_*.jsonl files under {args.data}. "
                 "Record with the app's Train mode, then adb pull.")

    all_x, all_evt, all_key = [], [], []
    for path in sessions:
        frames, keys, ignores = load_session(path)
        x, evt, key = build_windows(frames, keys, ignores)
        print(f"{path.name}: {len(frames)} frames, {len(keys)} keystrokes, "
              f"{int(evt.sum())} positive windows")
        if len(x):
            all_x.append(x)
            all_evt.append(evt)
            all_key.append(key)

    x = np.concatenate(all_x)
    y_evt = np.concatenate(all_evt)
    y_key = np.concatenate(all_key)
    print(f"total: {len(x)} windows, {int(y_evt.sum())} positives "
          f"({100 * y_evt.mean():.1f}%)")

    import tensorflow as tf

    # Balance: negatives vastly outnumber positives.
    pos_weight = (len(y_evt) - y_evt.sum()) / max(y_evt.sum(), 1)

    inp = tf.keras.Input(shape=(WINDOW, NUM_FEATS))
    h = tf.keras.layers.Conv1D(96, 3, padding="same", activation="relu")(inp)
    h = tf.keras.layers.Conv1D(96, 3, padding="same", activation="relu")(h)
    h = tf.keras.layers.GRU(96)(h)
    h = tf.keras.layers.Dropout(0.3)(h)
    evt_out = tf.keras.layers.Dense(1, activation="sigmoid", name="event")(h)
    key_out = tf.keras.layers.Dense(NUM_CLASSES, activation="softmax", name="key")(h)
    model = tf.keras.Model(inp, [evt_out, key_out])
    model.compile(
        optimizer=tf.keras.optimizers.Adam(1e-3),
        loss={"event": "binary_crossentropy", "key": "sparse_categorical_crossentropy"},
        loss_weights={"event": 1.0, "key": 0.5},
        metrics={"event": [tf.keras.metrics.Precision(), tf.keras.metrics.Recall()]},
    )
    model.summary()

    # Split by time (last 15% as validation) to avoid window leakage.
    split = int(len(x) * 0.85)
    model.fit(
        x[:split], {"event": y_evt[:split], "key": y_key[:split]},
        validation_data=(x[split:], {"event": y_evt[split:], "key": y_key[split:]}),
        sample_weight={"event": np.where(y_evt[:split] > 0, pos_weight, 1.0)},
        epochs=args.epochs, batch_size=256,
        callbacks=[tf.keras.callbacks.EarlyStopping(patience=6, restore_best_weights=True)],
    )

    converter = tf.lite.TFLiteConverter.from_keras_model(model)
    tflite = converter.convert()
    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_bytes(tflite)
    print(f"wrote {out} ({len(tflite) / 1024:.0f} KB)")


if __name__ == "__main__":
    main()
