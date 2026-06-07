#!/usr/bin/env python3
"""
AurigaPC — Linux / Windows Desktop Edition
==========================================
Mirrors the Android feature set on any PC with a webcam.

Hardware requirements
---------------------
- Python 3.11+
- OpenCV-compatible camera (USB, built-in, or IP stream)
- espeak-ng  (sudo apt install espeak-ng  /  choco install espeak)
- Optional: ONNX Runtime for zone-analyser + stair-sense models

Install dependencies
--------------------
  pip install opencv-python onnxruntime pyttsx3 pyaudio vosk numpy

Quick start
-----------
  python3 auriga_pc.py                 # webcam index 0
  python3 auriga_pc.py --camera 1      # second USB camera
  python3 auriga_pc.py --camera rtsp://192.168.1.10/stream
"""

import argparse
import subprocess
import sys
import threading
import time
from pathlib import Path
from typing import List, Optional, Tuple

import cv2
import numpy as np

# ── Optional heavy imports (graceful degradation) ─────────────────────────────
try:
    import pyttsx3
    _TTS_AVAILABLE = True
except ImportError:
    _TTS_AVAILABLE = False
    print("[WARN] pyttsx3 not found. TTS disabled — falling back to espeak-ng.")

try:
    import onnxruntime as ort
    _ONNX_AVAILABLE = True
except ImportError:
    _ONNX_AVAILABLE = False
    print("[WARN] onnxruntime not found. Running heuristic-only mode.")

# ── Constants ─────────────────────────────────────────────────────────────────
FRAME_GATE_SEC   = 0.15     # max one analysis frame every 150 ms
COCO_LABELS_FILE = Path(__file__).parent / "coco_labels.txt"

# ── Shared COCO labels (80 classes) ──────────────────────────────────────────
COCO_LABELS = [
    "person","bicycle","car","motorcycle","airplane","bus","train","truck","boat",
    "traffic light","fire hydrant","stop sign","parking meter","bench","bird","cat",
    "dog","horse","sheep","cow","elephant","bear","zebra","giraffe","backpack",
    "umbrella","handbag","tie","suitcase","frisbee","skis","snowboard","sports ball",
    "kite","baseball bat","baseball glove","skateboard","surfboard","tennis racket",
    "bottle","wine glass","cup","fork","knife","spoon","bowl","banana","apple",
    "sandwich","orange","broccoli","carrot","hot dog","pizza","donut","cake","chair",
    "couch","potted plant","bed","dining table","toilet","tv","laptop","mouse",
    "remote","keyboard","cell phone","microwave","oven","toaster","sink",
    "refrigerator","book","clock","vase","scissors","teddy bear","hair drier",
    "toothbrush",
]

# ─────────────────────────────────────────────────────────────────────────────
# OutputLayer — TTS + console
# ─────────────────────────────────────────────────────────────────────────────

class OutputLayer:
    """Unified TTS/console output, mirroring AurigaInterfaces.IOutputLayer."""

    PRIORITY_EMERGENCY = 4
    PRIORITY_HIGH      = 3
    PRIORITY_NORMAL    = 2
    PRIORITY_BACKGROUND= 1

    def __init__(self):
        self._muted = False
        self._tts_lock = threading.Lock()
        if _TTS_AVAILABLE:
            self._engine = pyttsx3.init()
            self._engine.setProperty("rate", 175)
        else:
            self._engine = None

    def speak(self, text: str, priority: int = PRIORITY_NORMAL):
        if self._muted and priority < self.PRIORITY_EMERGENCY:
            return
        print(f"[AURIGA] {text}")
        with self._tts_lock:
            if self._engine:
                self._engine.say(text)
                self._engine.runAndWait()
            else:
                try:
                    subprocess.Popen(
                        ["espeak-ng", "-s", "175", text],
                        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL
                    )
                except FileNotFoundError:
                    pass  # TTS completely unavailable — console output is all we have

    def set_muted(self, muted: bool):
        self._muted = muted

    def is_muted(self) -> bool:
        return self._muted


# ─────────────────────────────────────────────────────────────────────────────
# ZoneAnalyser — edge-density path analysis (heuristic, no model required)
# ─────────────────────────────────────────────────────────────────────────────

class ZoneAnalyser:
    """Splits frame into left / centre / right zones; returns safest path."""

    EDGE_THRESH_LOW  = 50
    EDGE_THRESH_HIGH = 150

    def analyse(self, frame_gray: np.ndarray) -> Tuple[str, float, float, float]:
        """
        Returns (safe_zone, left_density, centre_density, right_density).
        safe_zone is 'left' | 'centre' | 'right' | 'unknown'.
        """
        h, w = frame_gray.shape
        edges = cv2.Canny(frame_gray, self.EDGE_THRESH_LOW, self.EDGE_THRESH_HIGH)

        l = edges[:, :w//3]
        c = edges[:, w//3: 2*w//3]
        r = edges[:, 2*w//3:]

        def density(region): return float(np.count_nonzero(region)) / region.size

        ld, cd, rd = density(l), density(c), density(r)
        safe = min([("left", ld), ("centre", cd), ("right", rd)], key=lambda x: x[1])[0]
        return safe, ld, cd, rd


# ─────────────────────────────────────────────────────────────────────────────
# StairSenseEngine — horizontal edge banding (heuristic)
# ─────────────────────────────────────────────────────────────────────────────

class StairSenseEngine:
    BAND_COUNT      = 10
    EDGE_DELTA      = 40
    HIT_THRESHOLD   = 0.25

    def analyse(self, frame_gray: np.ndarray) -> dict:
        h, w = frame_gray.shape
        roi   = frame_gray[int(h * 0.35):, :]   # lower 65%
        rh, _ = roi.shape
        band_h = max(1, rh // self.BAND_COUNT)

        hits = 0
        upper_hits, lower_hits = 0, 0

        for b in range(self.BAND_COUNT):
            band = roi[b*band_h : (b+1)*band_h, w//2 - 2 : w//2 + 2]
            if band.size == 0:
                continue
            col = band[:, 0].astype(int)
            transitions = np.sum(np.abs(np.diff(col)) > self.EDGE_DELTA)
            hit = transitions / max(1, len(col)) > self.HIT_THRESHOLD
            if hit:
                hits += 1
                if b < self.BAND_COUNT // 2:
                    upper_hits += 1
                else:
                    lower_hits += 1

        detected  = hits >= 3
        direction = "ascending" if upper_hits > lower_hits else \
                    "descending" if lower_hits > upper_hits else "unknown"
        return {"detected": detected, "steps": hits, "direction": direction}


# ─────────────────────────────────────────────────────────────────────────────
# ColorSenseEngine — HSV dominant colour
# ─────────────────────────────────────────────────────────────────────────────

class ColorSenseEngine:
    COLOR_NAMES = [
        ((0,   30),  "red"),
        ((30,  45),  "orange"),
        ((45,  75),  "yellow"),
        ((75, 165),  "green"),
        ((165,250),  "blue"),
        ((250,290),  "purple"),
        ((290,340),  "pink"),
        ((340,360),  "red"),
    ]

    def analyse(self, frame_bgr: np.ndarray) -> str:
        h, w = frame_bgr.shape[:2]
        roi  = frame_bgr[h//4: 3*h//4, w//4: 3*w//4]
        hsv  = cv2.cvtColor(roi, cv2.COLOR_BGR2HSV)
        mean_h = float(np.mean(hsv[:,:,0])) * 2.0   # OpenCV hue is 0–179
        mean_s = float(np.mean(hsv[:,:,1])) / 255.0
        mean_v = float(np.mean(hsv[:,:,2])) / 255.0

        if mean_s < 0.15:
            return "white" if mean_v > 0.60 else "grey"

        for (lo, hi), name in self.COLOR_NAMES:
            if lo <= mean_h < hi:
                return name
        return "red"

    def detect_traffic_light(self, frame_bgr: np.ndarray) -> str:
        """Returns 'red' | 'amber' | 'green' | 'unknown'."""
        h, w = frame_bgr.shape[:2]
        # Sample top third (where lights are)
        top = frame_bgr[:h//3, w//3: 2*w//3]
        hsv = cv2.cvtColor(top, cv2.COLOR_BGR2HSV)

        # Red mask (wraps hue)
        red1 = cv2.inRange(hsv, (0,100,100),   (10,255,255))
        red2 = cv2.inRange(hsv, (170,100,100), (180,255,255))
        red  = cv2.bitwise_or(red1, red2)

        amber = cv2.inRange(hsv, (10,100,100), (30,255,255))
        green = cv2.inRange(hsv, (45,80,80),   (90,255,255))

        counts = {
            "red":   float(np.count_nonzero(red)),
            "amber": float(np.count_nonzero(amber)),
            "green": float(np.count_nonzero(green)),
        }
        best = max(counts, key=counts.get)
        return best if counts[best] > 200 else "unknown"


# ─────────────────────────────────────────────────────────────────────────────
# SceneDescriberEngine — rule-based from ONNX YOLO detections or heuristics
# ─────────────────────────────────────────────────────────────────────────────

OBJECT_HEIGHTS_M = {
    "person": 1.70, "bicycle": 1.00, "car": 1.50, "motorcycle": 1.10,
    "bus": 3.20, "truck": 3.80, "traffic light": 0.60, "stop sign": 0.75,
    "bench": 0.85, "chair": 0.90, "dining table": 0.75, "tv": 0.60,
    "laptop": 0.35, "book": 0.22, "cup": 0.12, "bottle": 0.28,
    "dog": 0.55, "cat": 0.28, "backpack": 0.50, "suitcase": 0.65,
}
FOCAL_PX = 600.0

class SceneDescriberEngine:
    def describe(self, detections: list, frame_w: int, frame_h: int) -> str:
        if not detections:
            return "I cannot identify any objects clearly."

        annotated = []
        for d in detections:
            label = d["label"]
            cx    = d["cx"]
            bh_px = d["bh"] * frame_h
            ch    = OBJECT_HEIGHTS_M.get(label)
            dist  = (FOCAL_PX * ch / bh_px) if (ch and bh_px > 2) else -1.0
            zone  = "to your left" if cx < 0.33 else \
                    "to your right" if cx > 0.67 else "ahead of you"
            annotated.append((dist, label, zone))

        annotated.sort(key=lambda x: x[0] if x[0] > 0 else 999)
        annotated = annotated[:7]

        parts = []
        for dist, label, zone in annotated:
            article = "an" if label[0] in "aeiou" else "a"
            dist_str = f"at {dist:.1f} metres " if dist > 0 else ""
            parts.append(f"{article} {label} {dist_str}{zone}")

        return "I see " + ", ".join(parts) + "."


# ─────────────────────────────────────────────────────────────────────────────
# ONNX-based YOLO detector (optional — falls back to heuristic-only)
# ─────────────────────────────────────────────────────────────────────────────

class YoloDetector:
    CONF_THRESHOLD = 0.40
    IOU_THRESHOLD  = 0.45
    INPUT_SIZE     = 640

    def __init__(self, model_path: str):
        self._session = ort.InferenceSession(
            model_path,
            providers=["CPUExecutionProvider"]
        )
        self._input_name = self._session.get_inputs()[0].name

    @classmethod
    def try_create(cls, model_path: str) -> Optional["YoloDetector"]:
        if not _ONNX_AVAILABLE:
            return None
        p = Path(model_path)
        if not p.exists():
            print(f"[WARN] YOLO model not found at {model_path}. Detection disabled.")
            return None
        try:
            return cls(str(p))
        except Exception as e:
            print(f"[WARN] Failed to load YOLO model: {e}")
            return None

    def detect(self, frame_bgr: np.ndarray) -> list:
        h, w = frame_bgr.shape[:2]
        scale  = self.INPUT_SIZE / max(h, w)
        rw, rh = int(w * scale), int(h * scale)
        pad_w  = (self.INPUT_SIZE - rw) // 2
        pad_h  = (self.INPUT_SIZE - rh) // 2

        resized = cv2.resize(frame_bgr, (rw, rh))
        canvas  = np.full((self.INPUT_SIZE, self.INPUT_SIZE, 3), 114, dtype=np.uint8)
        canvas[pad_h:pad_h+rh, pad_w:pad_w+rw] = resized

        inp = canvas.astype(np.float32) / 255.0
        inp = np.transpose(inp, (2, 0, 1))[np.newaxis]

        output = self._session.run(None, {self._input_name: inp})[0]  # [1, 84, 8400]
        return self._postprocess(output[0], w, h, pad_w, pad_h, rw, rh)

    def _postprocess(self, raw, orig_w, orig_h, pad_w, pad_h, rw, rh) -> list:
        # raw shape: [84, 8400] — transpose to [8400, 84]
        anchors = raw.T
        results = []
        for anchor in anchors:
            cx, cy, bw, bh = anchor[:4]
            scores = anchor[4:]
            class_id = int(np.argmax(scores))
            conf = float(scores[class_id])
            if conf < self.CONF_THRESHOLD:
                continue
            # Unpad and un-scale back to original frame coords
            x1 = (cx - bw / 2 - pad_w) / rw
            y1 = (cy - bh / 2 - pad_h) / rh
            x2 = (cx + bw / 2 - pad_w) / rw
            y2 = (cy + bh / 2 - pad_h) / rh
            label = COCO_LABELS[class_id] if class_id < len(COCO_LABELS) else str(class_id)
            results.append({
                "label":  label,
                "conf":   conf,
                "cx":     (x1 + x2) / 2,
                "cy":     (y1 + y2) / 2,
                "bh":     max(0, y2 - y1),
                "box":    [x1, y1, x2, y2],
            })

        # NMS per class
        results.sort(key=lambda d: d["conf"], reverse=True)
        kept = []
        suppressed = set()
        for i, a in enumerate(results):
            if i in suppressed:
                continue
            kept.append(a)
            for j, b in enumerate(results[i+1:], i+1):
                if j not in suppressed and a["label"] == b["label"]:
                    if self._iou(a["box"], b["box"]) > self.IOU_THRESHOLD:
                        suppressed.add(j)
        return kept

    @staticmethod
    def _iou(box_a, box_b) -> float:
        ax1,ay1,ax2,ay2 = box_a
        bx1,by1,bx2,by2 = box_b
        ix1,iy1 = max(ax1,bx1), max(ay1,by1)
        ix2,iy2 = min(ax2,bx2), min(ay2,by2)
        inter = max(0, ix2-ix1) * max(0, iy2-iy1)
        a_area = max(0, ax2-ax1) * max(0, ay2-ay1)
        b_area = max(0, bx2-bx1) * max(0, by2-by1)
        union = a_area + b_area - inter
        return inter / union if union > 0 else 0.0


# ─────────────────────────────────────────────────────────────────────────────
# CommandRouter — spoken command dispatcher
# ─────────────────────────────────────────────────────────────────────────────

class CommandRouter:
    def __init__(self):
        self._skills: dict = {}

    def register(self, trigger: str, handler):
        self._skills[trigger.lower()] = handler

    def dispatch(self, text: str) -> Optional[str]:
        t = text.lower().strip()
        # Containment pass first
        for trigger, handler in self._skills.items():
            if trigger in t:
                arg = t.replace(trigger, "").strip()
                return handler(arg)
        return "I didn't understand that command."


# ─────────────────────────────────────────────────────────────────────────────
# AurigaPC — main application class
# ─────────────────────────────────────────────────────────────────────────────

class AurigaPC:
    def __init__(self, camera_source=0, model_path: str = "yolov8n.onnx"):
        self.output       = OutputLayer()
        self.zone_analyser= ZoneAnalyser()
        self.stair_sense  = StairSenseEngine()
        self.color_sense  = ColorSenseEngine()
        self.scene_desc   = SceneDescriberEngine()
        self.router       = CommandRouter()
        self.yolo         = YoloDetector.try_create(model_path)

        self._cap             = cv2.VideoCapture(camera_source)
        self._running         = False
        self._last_frame_t    = 0.0
        self._latest_frame    = None
        self._latest_dets     = []
        self._frame_lock      = threading.Lock()

        self._register_skills()

    # ── Camera loop ───────────────────────────────────────────────────────────

    def run(self):
        self.output.speak("Auriga PC is starting. Camera initialising.", self.output.PRIORITY_HIGH)
        self._running = True

        while self._running:
            ret, frame = self._cap.read()
            if not ret:
                time.sleep(0.1)
                continue

            now = time.time()
            if now - self._last_frame_t < FRAME_GATE_SEC:
                continue
            self._last_frame_t = now

            # Run analysis in a background thread so we don't block the capture loop
            threading.Thread(target=self._analyse, args=(frame.copy(),), daemon=True).start()

            # Show a basic overlay (useful for sighted developer/carer)
            with self._frame_lock:
                display = self._latest_frame if self._latest_frame is not None else frame
            cv2.imshow("AurigaPC", display)
            key = cv2.waitKey(1) & 0xFF
            if key == ord('q'):
                break
            elif key == ord('d'):
                self._on_command("describe")
            elif key == ord('s'):
                self._on_command("stair")
            elif key == ord('c'):
                self._on_command("what colour")

        self._cap.release()
        cv2.destroyAllWindows()

    def stop(self):
        self._running = False

    # ── Frame analysis ────────────────────────────────────────────────────────

    def _analyse(self, frame: np.ndarray):
        gray = cv2.cvtColor(frame, cv2.COLOR_BGR2GRAY)
        h, w = frame.shape[:2]

        # Zone analysis
        safe_zone, ld, cd, rd = self.zone_analyser.analyse(gray)

        # YOLO detections (if model loaded)
        dets = []
        if self.yolo:
            dets = self.yolo.detect(frame)

        # Stair detection
        stair = self.stair_sense.analyse(gray)

        # Traffic light
        light = self.color_sense.detect_traffic_light(frame)

        # Update shared state
        overlay = self._draw_overlay(frame, safe_zone, ld, cd, rd, dets, stair)
        with self._frame_lock:
            self._latest_frame = overlay
            self._latest_dets  = dets

        # Passive announcements
        if stair["detected"]:
            self.output.speak(
                f"Stairs {stair['direction']}, approximately {stair['steps']} steps.",
                self.output.PRIORITY_HIGH
            )

        if light in ("red", "green", "amber"):
            self.output.speak(f"Traffic light is {light}.", self.output.PRIORITY_NORMAL)

    # ── Overlay rendering ─────────────────────────────────────────────────────

    def _draw_overlay(self, frame, safe_zone, ld, cd, rd, dets, stair) -> np.ndarray:
        out = frame.copy()
        h, w = out.shape[:2]

        # Zone dividers
        cv2.line(out, (w//3, 0), (w//3, h), (0,180,180), 1)
        cv2.line(out, (2*w//3, 0), (2*w//3, h), (0,180,180), 1)

        # Safe zone arrow
        zone_x = {"left": w//6, "centre": w//2, "right": 5*w//6}.get(safe_zone, w//2)
        cv2.arrowedLine(out, (zone_x, h-20), (zone_x, h-60), (0,255,0), 3)

        # Detection boxes
        for d in dets:
            x1,y1,x2,y2 = [int(v * (w if i%2==0 else h)) for i,v in enumerate(d["box"])]
            cv2.rectangle(out, (x1,y1), (x2,y2), (0,255,255), 2)
            cv2.putText(out, f"{d['label']} {d['conf']:.0%}", (x1,y1-6),
                        cv2.FONT_HERSHEY_SIMPLEX, 0.5, (0,255,255), 1)

        # HUD text
        cv2.putText(out, f"Safe: {safe_zone}", (10,25),
                    cv2.FONT_HERSHEY_SIMPLEX, 0.6, (255,255,255), 2)
        if stair["detected"]:
            cv2.putText(out, f"STAIRS {stair['direction']}", (10,55),
                        cv2.FONT_HERSHEY_SIMPLEX, 0.8, (0,100,255), 2)
        cv2.putText(out, "D=describe  S=stair  C=colour  Q=quit", (10,h-10),
                    cv2.FONT_HERSHEY_SIMPLEX, 0.4, (200,200,200), 1)
        return out

    # ── Command routing ───────────────────────────────────────────────────────

    def _on_command(self, text: str):
        response = self.router.dispatch(text)
        if response:
            self.output.speak(response, self.output.PRIORITY_HIGH)

    def _register_skills(self):
        def cmd_describe(arg):
            with self._frame_lock:
                dets = self._latest_dets
            if not dets:
                return "I cannot see any objects clearly."
            return self.scene_desc.describe(dets, 640, 480)

        def cmd_stair(arg):
            with self._frame_lock:
                frame = self._latest_frame
            if frame is None:
                return "No camera frame available."
            gray  = cv2.cvtColor(frame, cv2.COLOR_BGR2GRAY)
            r     = self.stair_sense.analyse(gray)
            if not r["detected"]:
                return "No stairs detected ahead."
            return f"Stairs {r['direction']}, approximately {r['steps']} steps."

        def cmd_colour(arg):
            with self._frame_lock:
                frame = self._latest_frame
            if frame is None:
                return "No camera frame available."
            colour = self.color_sense.analyse(frame)
            return f"The colour is {colour}."

        def cmd_mute(arg):
            self.output.set_muted(True)
            return None

        def cmd_unmute(arg):
            self.output.set_muted(False)
            return "Unmuted."

        def cmd_help(arg):
            return ("Available commands: describe, stair, what colour, "
                    "mute, unmute, help, quit.")

        self.router.register("describe",     cmd_describe)
        self.router.register("stair",        cmd_stair)
        self.router.register("what colour",  cmd_colour)
        self.router.register("mute",         cmd_mute)
        self.router.register("unmute",       cmd_unmute)
        self.router.register("help",         cmd_help)


# ─────────────────────────────────────────────────────────────────────────────
# Entry point
# ─────────────────────────────────────────────────────────────────────────────

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="AurigaPC — Desktop Spatial Intelligence")
    parser.add_argument("--camera", default=0,
                        help="Camera index (int) or RTSP/HTTP URL (default: 0)")
    parser.add_argument("--model", default="yolov8n.onnx",
                        help="Path to YOLOv8n ONNX model (optional)")
    args = parser.parse_args()

    camera = int(args.camera) if str(args.camera).isdigit() else args.camera
    app = AurigaPC(camera_source=camera, model_path=args.model)
    try:
        app.run()
    except KeyboardInterrupt:
        app.stop()
        print("\n[AURIGA] Goodbye.")
