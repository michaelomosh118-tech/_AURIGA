#!/usr/bin/env python3
"""
AurigaPi — Raspberry Pi Spatial Intelligence Node
==================================================
Session 18 — Phase 4 (Platforms).

Runs headlessly on a Raspberry Pi 4B or Pi Zero 2W. Pairs with:
  - Pi Camera Module 3 (or any USB / CSI webcam)
  - 3× ERM vibration motors on GPIO 17 (left), 18 (centre), 27 (right)
  - USB earpiece / 3.5mm headphone → espeak-ng via ALSA
  - Optional: Bluetooth earpiece (pair normally before running)

This module mirrors the AurigaPC feature set (see platforms/pc/auriga_pc.py)
but adds:
  - RPi.GPIO haptic motor control on three GPIO lines
  - Pi Camera Module support via Picamera2 (preferred) or OpenCV fallback
  - Lower resolution / frame rate tuned for Pi Zero 2W (640×480 @ ~4fps)
  - Systemd-compatible signal handling (SIGTERM → graceful shutdown)

Install (Raspberry Pi OS Lite / Bookworm, 64-bit)
--------------------------------------------------
  sudo apt update
  sudo apt install -y python3-pip espeak-ng python3-opencv
  pip3 install RPi.GPIO numpy

  # For Pi Camera Module 3:
  sudo apt install -y python3-picamera2

  # For ONNX YOLO (ARM64):
  pip3 install onnxruntime

  # Copy the ONNX model:
  scp yolov8n.onnx pi@<pi-ip>:~/auriga/

Quick start (manual)
--------------------
  python3 auriga_pi.py

Systemd (boot-to-run)
---------------------
  sudo cp auriga.service /etc/systemd/system/
  sudo systemctl daemon-reload
  sudo systemctl enable auriga
  sudo systemctl start auriga

GPIO wiring
-----------
  GPIO 17 (pin 11) → LEFT  motor driver IN+
  GPIO 18 (pin 12) → CENTRE motor driver IN+
  GPIO 27 (pin 13) → RIGHT motor driver IN+
  GND              → all motor driver IN−

  Recommended driver: DRV2605L or L293D with 5V ERM motor at 60–200mA.
"""

import argparse
import os
import signal
import subprocess
import sys
import threading
import time
from pathlib import Path
from typing import List, Optional, Tuple

import numpy as np

# ── Platform detection ────────────────────────────────────────────────────────
_ON_PI = os.path.exists("/sys/bus/platform/drivers/gpio-fan")

# ── GPIO support ──────────────────────────────────────────────────────────────
try:
    import RPi.GPIO as GPIO
    _GPIO_AVAILABLE = True
except ImportError:
    _GPIO_AVAILABLE = False
    print("[WARN] RPi.GPIO not available — haptic output disabled.")

# ── Camera support ────────────────────────────────────────────────────────────
try:
    from picamera2 import Picamera2
    _PICAMERA2 = True
except ImportError:
    _PICAMERA2 = False

try:
    import cv2
    _CV2 = True
except ImportError:
    _CV2 = False
    print("[WARN] OpenCV not available.")

# ── ONNX Runtime ──────────────────────────────────────────────────────────────
try:
    import onnxruntime as ort
    _ONNX = True
except ImportError:
    _ONNX = False
    print("[WARN] onnxruntime not found — detection disabled.")

# ── Reuse engine code from sibling pc module ──────────────────────────────────
_PC_DIR = Path(__file__).parent.parent / "pc"
if str(_PC_DIR) not in sys.path:
    sys.path.insert(0, str(_PC_DIR))

try:
    from auriga_pc import (
        ZoneAnalyser, StairSenseEngine, ColorSenseEngine,
        SceneDescriberEngine, CommandRouter, YoloDetector,
        OutputLayer as _PCOutputLayer, COCO_LABELS, FOCAL_PX,
    )
    _PC_ENGINES = True
except ImportError:
    _PC_ENGINES = False
    print("[WARN] Could not import PC engines — using built-in fallbacks.")

# ─────────────────────────────────────────────────────────────────────────────
# GPIO constants
# ─────────────────────────────────────────────────────────────────────────────
GPIO_LEFT   = 17
GPIO_CENTRE = 18
GPIO_RIGHT  = 27
MOTOR_PINS  = (GPIO_LEFT, GPIO_CENTRE, GPIO_RIGHT)

# Haptic patterns (duration in seconds, for simple GPIO-only motors)
PATTERN_SINGLE   = [(0.10, True),  (0.00, False)]
PATTERN_PULSE    = [(0.10, True),  (0.10, False), (0.10, True),  (0.10, False)]
PATTERN_FAST     = [(0.05, True),  (0.05, False)] * 4
PATTERN_SOS      = ([(0.10, True), (0.10, False)] * 3 +
                    [(0.30, True), (0.20, False)] * 3 +
                    [(0.10, True), (0.10, False)] * 3)
PATTERN_STAIR    = [(0.20, True),  (0.10, False), (0.20, True),  (0.20, False)]

# ─────────────────────────────────────────────────────────────────────────────
# HapticController — drives GPIO ERM motors
# ─────────────────────────────────────────────────────────────────────────────

class HapticController:
    def __init__(self):
        self._lock = threading.Lock()
        if _GPIO_AVAILABLE:
            GPIO.setmode(GPIO.BCM)
            GPIO.setwarnings(False)
            for pin in MOTOR_PINS:
                GPIO.setup(pin, GPIO.OUT, initial=GPIO.LOW)

    def pulse(self, zone: str, pattern_name: str = "single"):
        """Fire a haptic pattern on the motor(s) for the given zone."""
        pins = self._pins_for_zone(zone)
        pattern = {
            "single":   PATTERN_SINGLE,
            "pulse":    PATTERN_PULSE,
            "fast":     PATTERN_FAST,
            "sos":      PATTERN_SOS,
            "stair":    PATTERN_STAIR,
        }.get(pattern_name, PATTERN_SINGLE)

        threading.Thread(
            target=self._play, args=(pins, pattern), daemon=True
        ).start()

    def _play(self, pins: list, pattern: list):
        if not _GPIO_AVAILABLE:
            return
        with self._lock:
            for duration, state in pattern:
                for pin in pins:
                    GPIO.output(pin, GPIO.HIGH if state else GPIO.LOW)
                if duration > 0:
                    time.sleep(duration)
            for pin in pins:
                GPIO.output(pin, GPIO.LOW)

    def _pins_for_zone(self, zone: str) -> list:
        if zone == "left":    return [GPIO_LEFT]
        if zone == "right":   return [GPIO_RIGHT]
        if zone == "all":     return list(MOTOR_PINS)
        return [GPIO_CENTRE]   # centre / default

    def cleanup(self):
        if _GPIO_AVAILABLE:
            for pin in MOTOR_PINS:
                GPIO.output(pin, GPIO.LOW)
            GPIO.cleanup()


# ─────────────────────────────────────────────────────────────────────────────
# OutputLayer (Pi) — TTS via espeak-ng + haptic
# ─────────────────────────────────────────────────────────────────────────────

class PiOutputLayer:
    PRIORITY_EMERGENCY = 4
    PRIORITY_HIGH      = 3
    PRIORITY_NORMAL    = 2
    PRIORITY_BACKGROUND= 1

    def __init__(self, haptic: HapticController):
        self._haptic  = haptic
        self._muted   = False
        self._tts_lock = threading.Lock()
        # Test espeak-ng availability
        self._espeak_ok = subprocess.run(
            ["which", "espeak-ng"], capture_output=True
        ).returncode == 0

    def speak(self, text: str, priority: int = PRIORITY_NORMAL):
        if self._muted and priority < self.PRIORITY_EMERGENCY:
            return
        print(f"[AURIGA-PI] {text}")
        threading.Thread(target=self._say, args=(text,), daemon=True).start()

    def _say(self, text: str):
        with self._tts_lock:
            if self._espeak_ok:
                subprocess.run(
                    ["espeak-ng", "-s", "165", "-v", "en", text],
                    stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL
                )
            else:
                # Fall back to 'say' on macOS or silent on unknown
                try:
                    subprocess.run(["say", text],
                                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
                except FileNotFoundError:
                    pass

    def haptic(self, pattern: str, zone: str = "centre"):
        self._haptic.pulse(zone, pattern)

    def set_muted(self, v: bool): self._muted = v
    def is_muted(self) -> bool:   return self._muted


# ─────────────────────────────────────────────────────────────────────────────
# PiCamera2 / OpenCV frame source
# ─────────────────────────────────────────────────────────────────────────────

class FrameSource:
    """Abstraction over Picamera2 and OpenCV VideoCapture."""

    # Pi Zero 2W: 640×480 is comfortable; Pi 4B can do 1280×720
    DEFAULT_W = 640
    DEFAULT_H = 480

    def __init__(self, camera_source=0, width=DEFAULT_W, height=DEFAULT_H):
        self._width  = width
        self._height = height
        self._cam    = None
        self._pc2    = None
        self._open(camera_source)

    def _open(self, source):
        if _PICAMERA2 and isinstance(source, int):
            try:
                self._pc2 = Picamera2()
                cfg = self._pc2.create_preview_configuration(
                    main={"size": (self._width, self._height), "format": "RGB888"}
                )
                self._pc2.configure(cfg)
                self._pc2.start()
                print("[INFO] Picamera2 initialised.")
                return
            except Exception as e:
                print(f"[WARN] Picamera2 failed ({e}), trying OpenCV.")
                self._pc2 = None

        if _CV2:
            self._cam = cv2.VideoCapture(source)
            self._cam.set(cv2.CAP_PROP_FRAME_WIDTH,  self._width)
            self._cam.set(cv2.CAP_PROP_FRAME_HEIGHT, self._height)
            print("[INFO] OpenCV camera initialised.")
        else:
            raise RuntimeError("Neither Picamera2 nor OpenCV is available.")

    def read_bgr(self) -> Optional[np.ndarray]:
        """Return next frame as BGR numpy array, or None."""
        if self._pc2:
            rgb = self._pc2.capture_array()
            return rgb[:, :, ::-1]   # RGB → BGR
        if self._cam:
            ret, frame = self._cam.read()
            return frame if ret else None
        return None

    def release(self):
        if self._pc2:
            self._pc2.stop()
        if self._cam:
            self._cam.release()


# ─────────────────────────────────────────────────────────────────────────────
# AurigaPi — main application
# ─────────────────────────────────────────────────────────────────────────────

class AurigaPi:
    FRAME_GATE_SEC = 0.25   # Pi Zero 2W: ~4 fps analysis budget

    def __init__(self, camera_source=0, model_path: str = "yolov8n.onnx",
                 width: int = 640, height: int = 480):
        self.haptic  = HapticController()
        self.output  = PiOutputLayer(self.haptic)
        self.router  = CommandRouter() if _PC_ENGINES else _FallbackRouter()

        # Vision engines (from PC module if available)
        if _PC_ENGINES:
            self.zone_analyser = ZoneAnalyser()
            self.stair_sense   = StairSenseEngine()
            self.color_sense   = ColorSenseEngine()
            self.scene_desc    = SceneDescriberEngine()
            self.yolo          = YoloDetector.try_create(model_path) if _ONNX else None
        else:
            self.zone_analyser = None
            self.stair_sense   = None
            self.color_sense   = None
            self.scene_desc    = None
            self.yolo          = None

        self._source      = FrameSource(camera_source, width, height)
        self._running     = False
        self._last_frame  = 0.0
        self._lock        = threading.Lock()
        self._latest_dets = []
        self._latest_bgr  = None

        self._register_skills()

        # Graceful shutdown on SIGTERM (systemd sends this on stop/restart)
        signal.signal(signal.SIGTERM, self._sigterm)
        signal.signal(signal.SIGINT,  self._sigterm)

    def _sigterm(self, *_):
        print("\n[AURIGA-PI] SIGTERM received — shutting down.")
        self.stop()

    # ── Main loop ─────────────────────────────────────────────────────────────

    def run(self):
        self.output.speak("Auriga Pi is starting.", self.output.PRIORITY_HIGH)
        self._running = True

        while self._running:
            frame = self._source.read_bgr()
            if frame is None:
                time.sleep(0.1)
                continue

            now = time.time()
            if now - self._last_frame < self.FRAME_GATE_SEC:
                time.sleep(0.02)
                continue
            self._last_frame = now

            threading.Thread(
                target=self._analyse, args=(frame,), daemon=True
            ).start()

        self._source.release()
        self.haptic.cleanup()
        print("[AURIGA-PI] Shutdown complete.")

    def stop(self):
        self._running = False

    # ── Analysis pipeline ─────────────────────────────────────────────────────

    def _analyse(self, frame: np.ndarray):
        if not _CV2:
            return
        gray = cv2.cvtColor(frame, cv2.COLOR_BGR2GRAY)

        # Zone analysis
        safe_zone = "centre"
        if self.zone_analyser:
            safe_zone, ld, cd, rd = self.zone_analyser.analyse(gray)

        # YOLO detections
        dets = []
        if self.yolo:
            dets = self.yolo.detect(frame)

        # Stair detection
        stair = {"detected": False}
        if self.stair_sense:
            stair = self.stair_sense.analyse(gray)

        # Traffic light
        light = "unknown"
        if self.color_sense:
            light = self.color_sense.detect_traffic_light(frame)

        with self._lock:
            self._latest_dets = dets
            self._latest_bgr  = frame

        # Passive announcements
        if stair["detected"]:
            msg = f"Stairs {stair['direction']}, {stair['steps']} steps."
            self.output.speak(msg, self.output.PRIORITY_HIGH)
            self.output.haptic("stair", safe_zone)

        if light in ("red", "amber", "green"):
            self.output.speak(f"Traffic light is {light}.",
                              self.output.PRIORITY_NORMAL)

        # Vehicle proximity heuristic (large fast-moving blobs in centre zone)
        # A real TrafficSenseEngine would run here — stub for Pi
        _large_centre = any(
            d["label"] in ("car","bus","truck","motorcycle") and
            d["cx"] > 0.3 and d["cx"] < 0.7 and d["bh"] > 0.4
            for d in dets
        )
        if _large_centre:
            self.output.speak("Vehicle close ahead!", self.output.PRIORITY_HIGH)
            self.output.haptic("fast", "centre")

    # ── Skill registration ────────────────────────────────────────────────────

    def _register_skills(self):
        def cmd_describe(arg):
            with self._lock:
                dets = list(self._latest_dets)
            if not dets:
                return "I cannot see any objects clearly."
            h, w = (480, 640)
            with self._lock:
                if self._latest_bgr is not None:
                    h, w = self._latest_bgr.shape[:2]
            return self.scene_desc.describe(dets, w, h) if self.scene_desc \
                   else f"I see {len(dets)} object(s)."

        def cmd_stair(arg):
            with self._lock:
                frame = self._latest_bgr
            if frame is None or not _CV2 or not self.stair_sense:
                return "Stair detection unavailable."
            gray = cv2.cvtColor(frame, cv2.COLOR_BGR2GRAY)
            r = self.stair_sense.analyse(gray)
            if not r["detected"]: return "No stairs detected."
            return f"Stairs {r['direction']}, {r['steps']} steps."

        def cmd_colour(arg):
            with self._lock:
                frame = self._latest_bgr
            if frame is None or not self.color_sense:
                return "Colour detection unavailable."
            return f"The colour is {self.color_sense.analyse(frame)}."

        def cmd_mute(arg):
            self.output.set_muted(True)
            return None

        def cmd_unmute(arg):
            self.output.set_muted(False)
            return "Unmuted."

        def cmd_help(arg):
            return "Commands: describe, stair, what colour, mute, unmute, help."

        def cmd_stop(arg):
            return "Stopping."

        self.router.register("describe",     cmd_describe)
        self.router.register("stair",        cmd_stair)
        self.router.register("what colour",  cmd_colour)
        self.router.register("mute",         cmd_mute)
        self.router.register("unmute",       cmd_unmute)
        self.router.register("help",         cmd_help)
        self.router.register("stop",         cmd_stop)

    def on_voice_command(self, text: str):
        """Entry point for a voice wake-word integration (e.g. Vosk + PyAudio)."""
        response = self.router.dispatch(text)
        if response:
            self.output.speak(response, self.output.PRIORITY_HIGH)


# ─────────────────────────────────────────────────────────────────────────────
# Fallback router (when PC engines unavailable)
# ─────────────────────────────────────────────────────────────────────────────

class _FallbackRouter:
    def register(self, trigger, handler): pass
    def dispatch(self, text): return "Engine modules unavailable."


# ─────────────────────────────────────────────────────────────────────────────
# Entry point
# ─────────────────────────────────────────────────────────────────────────────

if __name__ == "__main__":
    parser = argparse.ArgumentParser(
        description="AurigaPi — Raspberry Pi Spatial Intelligence"
    )
    parser.add_argument("--camera", default=0,
                        help="Camera index (int) or RTSP URL (default: 0)")
    parser.add_argument("--model",  default="yolov8n.onnx",
                        help="Path to YOLOv8n ONNX model (optional)")
    parser.add_argument("--width",  type=int, default=640,
                        help="Camera width (default: 640)")
    parser.add_argument("--height", type=int, default=480,
                        help="Camera height (default: 480)")
    args = parser.parse_args()

    src = int(args.camera) if str(args.camera).isdigit() else args.camera

    app = AurigaPi(
        camera_source=src,
        model_path=args.model,
        width=args.width,
        height=args.height,
    )
    app.run()
