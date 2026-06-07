"""
AurigaPC — Desktop entry point (Linux / Windows)
Implements the same module interface contracts as the Android APK.
See AURIGA/docs/AURIGA_FULL_BUILD_BLUEPRINT.md for full spec.

Phase 4 build target. Scaffold only — modules fill in per the blueprint.
"""

import sys
import threading
import time

try:
    import cv2
except ImportError:
    print("[AurigaPC] OpenCV not installed. Run: pip install opencv-python")
    sys.exit(1)

try:
    import pyttsx3
    tts_engine = pyttsx3.init()
except ImportError:
    print("[AurigaPC] pyttsx3 not installed. Run: pip install pyttsx3")
    tts_engine = None


def speak(text, priority="NORMAL"):
    print(f"[SPEAK/{priority}] {text}")
    if tts_engine:
        tts_engine.say(text)
        tts_engine.runAndWait()


def main():
    speak("Auriga PC starting.", "NORMAL")
    cap = cv2.VideoCapture(0)
    if not cap.isOpened():
        speak("No camera found. Please connect a webcam.", "HIGH")
        return

    speak("Camera active. Auriga is running.", "NORMAL")
    print("[AurigaPC] Press Q to quit.")

    while True:
        ret, frame = cap.read()
        if not ret:
            break

        cv2.imshow("Auriga PC — Camera Feed", frame)
        if cv2.waitKey(1) & 0xFF == ord('q'):
            break

        time.sleep(0.05)

    cap.release()
    cv2.destroyAllWindows()
    speak("Auriga stopped.", "NORMAL")


if __name__ == "__main__":
    main()
