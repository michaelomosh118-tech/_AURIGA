/**
 * AurigaMCU — ESP32 Haptic Satellite Node
 * ========================================
 * Session 19 — Phase 4 (Platforms). Parallel with Session 20 (Arduino belt).
 *
 * Role
 * ----
 * Receives 3-byte haptic commands from the Auriga Android app (or AurigaPi)
 * over BLE and drives three ERM vibration motors via GPIO PWM:
 *   - Motor 0 — LEFT   (GPIO 25)
 *   - Motor 1 — CENTRE (GPIO 26)
 *   - Motor 2 — RIGHT  (GPIO 27)
 *
 * BLE GATT server
 * ---------------
 * Service UUID  : 4AURI-0001-... (see AURIGA_SERVICE_UUID below)
 * Characteristic: 4AURI-0002-... write-without-response
 *
 * 3-byte protocol (same on all Auriga satellite nodes)
 * ----------------------------------------------------
 * Byte 0 — ZONE
 *   0x00 = LEFT
 *   0x01 = CENTRE
 *   0x02 = RIGHT
 *   0x03 = ALL   (all three motors)
 *
 * Byte 1 — INTENSITY   0–255  (maps to PWM duty cycle 0–255)
 *
 * Byte 2 — PATTERN
 *   0x00 = SINGLE      (one short burst, 100ms)
 *   0x01 = PULSE       (two short bursts, 100ms ON / 100ms OFF × 2)
 *   0x02 = CONTINUOUS  (hold for 500ms)
 *   0x03 = SOS         (··· — — — ··· Morse)
 *   0x04 = STAIR_WARN  (200ms ON / 100ms OFF × 2)
 *   0x05 = OBSTACLE    (fast buzz × 4, 50ms ON / 50ms OFF)
 *   0x06 = COMMAND_ACK (single 50ms tick)
 *
 * Hardware
 * --------
 *   ESP32-S3-DevKitC-1 (or any ESP32 with BLE)
 *   3× ERM vibration motor (10mm coin type, 5V)
 *   3× DRV2605L haptic driver OR 3× MOSFET (2N7000 / IRLML2502)
 *   LiPo 500mAh + TP4056 charger
 *   BOM: ~$18 total
 *
 * Arduino IDE setup
 * -----------------
 *   1. Board: "ESP32S3 Dev Module" (esp32 by Espressif 3.x.x)
 *   2. Partition scheme: "Default 4MB with spiffs"
 *   3. Libraries: BLE is built into the ESP32 Arduino core (no extra install)
 *
 * Power consumption
 * -----------------
 *   Idle (BLE advertising)  : ~8 mA
 *   Active (motors running) : ~120–180 mA (3 motors @ ~40–60 mA each)
 *   Estimated runtime (500mAh LiPo): ~8 hours idle / ~3 hours full haptic
 */

#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>

// ── BLE UUIDs ────────────────────────────────────────────────────────────────
// Custom Auriga service UUID (128-bit, generated with uuidgen)
#define AURIGA_SERVICE_UUID        "4155524901-0000-1000-8000-00805f9b34fb"
#define AURIGA_HAPTIC_CHAR_UUID    "4155524902-0000-1000-8000-00805f9b34fb"

// ── Motor GPIO pins ──────────────────────────────────────────────────────────
#define PIN_MOTOR_LEFT    25
#define PIN_MOTOR_CENTRE  26
#define PIN_MOTOR_RIGHT   27

// ── PWM channels (ESP32 LEDC) ────────────────────────────────────────────────
#define PWM_CH_LEFT    0
#define PWM_CH_CENTRE  1
#define PWM_CH_RIGHT   2
#define PWM_FREQ_HZ    5000   // 5 kHz — above audible range for most motors
#define PWM_BITS       8      // 8-bit resolution (0–255 duty)

// ── Protocol constants ────────────────────────────────────────────────────────
#define ZONE_LEFT      0x00
#define ZONE_CENTRE    0x01
#define ZONE_RIGHT     0x02
#define ZONE_ALL       0x03

#define PAT_SINGLE     0x00
#define PAT_PULSE      0x01
#define PAT_CONTINUOUS 0x02
#define PAT_SOS        0x03
#define PAT_STAIR      0x04
#define PAT_OBSTACLE   0x05
#define PAT_CMD_ACK    0x06

// ─────────────────────────────────────────────────────────────────────────────
// Global state
// ─────────────────────────────────────────────────────────────────────────────

BLEServer*         pServer    = nullptr;
BLECharacteristic* pHapticChar= nullptr;
volatile bool      deviceConnected = false;

// Task queue: we process haptic commands in the main loop (not in BLE callback)
// to avoid stack overflow in the BLE ISR context.
struct HapticCommand {
    uint8_t zone;
    uint8_t intensity;
    uint8_t pattern;
    bool    valid;
};
volatile HapticCommand pendingCmd = { 0, 0, 0, false };

// ─────────────────────────────────────────────────────────────────────────────
// BLE callbacks
// ─────────────────────────────────────────────────────────────────────────────

class AurigaServerCallbacks : public BLEServerCallbacks {
    void onConnect(BLEServer* svr) override {
        deviceConnected = true;
        Serial.println("[BLE] Client connected.");
        // Acknowledge connection with a single centre tick
        pendingCmd = { ZONE_CENTRE, 180, PAT_CMD_ACK, true };
    }
    void onDisconnect(BLEServer* svr) override {
        deviceConnected = false;
        Serial.println("[BLE] Client disconnected. Restarting advertising.");
        BLEDevice::startAdvertising();
    }
};

class HapticCharCallbacks : public BLECharacteristicCallbacks {
    void onWrite(BLECharacteristic* pChar) override {
        std::string data = pChar->getValue();
        if (data.length() < 3) {
            Serial.println("[BLE] Received short packet (< 3 bytes) — ignored.");
            return;
        }
        uint8_t zone      = (uint8_t) data[0];
        uint8_t intensity = (uint8_t) data[1];
        uint8_t pattern   = (uint8_t) data[2];

        Serial.printf("[BLE] CMD zone=0x%02X intensity=%u pattern=0x%02X\n",
                      zone, intensity, pattern);

        // Queue for processing in loop() — BLE callback must return quickly
        pendingCmd = { zone, intensity, pattern, true };
    }
};

// ─────────────────────────────────────────────────────────────────────────────
// Motor control helpers
// ─────────────────────────────────────────────────────────────────────────────

void motorSet(uint8_t zone, uint8_t intensity) {
    uint32_t duty = (uint32_t) intensity;
    if (zone == ZONE_LEFT || zone == ZONE_ALL) {
        ledcWrite(PWM_CH_LEFT,   duty);
    }
    if (zone == ZONE_CENTRE || zone == ZONE_ALL) {
        ledcWrite(PWM_CH_CENTRE, duty);
    }
    if (zone == ZONE_RIGHT || zone == ZONE_ALL) {
        ledcWrite(PWM_CH_RIGHT,  duty);
    }
}

void motorOff(uint8_t zone) {
    motorSet(zone, 0);
}

void burst(uint8_t zone, uint8_t intensity, uint32_t onMs, uint32_t offMs) {
    motorSet(zone, intensity);
    delay(onMs);
    motorOff(zone);
    if (offMs > 0) delay(offMs);
}

// ─────────────────────────────────────────────────────────────────────────────
// Pattern playback
// ─────────────────────────────────────────────────────────────────────────────

void playPattern(uint8_t zone, uint8_t intensity, uint8_t pattern) {
    switch (pattern) {

        case PAT_SINGLE:
            burst(zone, intensity, 100, 0);
            break;

        case PAT_PULSE:
            burst(zone, intensity, 100, 100);
            burst(zone, intensity, 100, 0);
            break;

        case PAT_CONTINUOUS:
            motorSet(zone, intensity);
            delay(500);
            motorOff(zone);
            break;

        case PAT_SOS: {
            // ··· (three dots)
            for (int i = 0; i < 3; i++) burst(zone, intensity, 100, 100);
            delay(200);
            // — — — (three dashes)
            for (int i = 0; i < 3; i++) burst(zone, intensity, 300, 100);
            delay(200);
            // ··· (three dots)
            for (int i = 0; i < 3; i++) burst(zone, intensity, 100, 100);
            break;
        }

        case PAT_STAIR:
            burst(zone, intensity, 200, 100);
            burst(zone, intensity, 200, 0);
            break;

        case PAT_OBSTACLE:
            for (int i = 0; i < 4; i++) burst(zone, intensity, 50, 50);
            break;

        case PAT_CMD_ACK:
            burst(zone, intensity, 50, 0);
            break;

        default:
            // Unknown pattern — single burst as safe fallback
            Serial.printf("[HAPTIC] Unknown pattern 0x%02X — using SINGLE.\n", pattern);
            burst(zone, intensity, 100, 0);
            break;
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// setup()
// ─────────────────────────────────────────────────────────────────────────────

void setup() {
    Serial.begin(115200);
    Serial.println("\n[AURIGA-ESP32] Haptic satellite node starting…");

    // ── PWM setup ─────────────────────────────────────────────────────────────
    ledcSetup(PWM_CH_LEFT,   PWM_FREQ_HZ, PWM_BITS);
    ledcSetup(PWM_CH_CENTRE, PWM_FREQ_HZ, PWM_BITS);
    ledcSetup(PWM_CH_RIGHT,  PWM_FREQ_HZ, PWM_BITS);
    ledcAttachPin(PIN_MOTOR_LEFT,   PWM_CH_LEFT);
    ledcAttachPin(PIN_MOTOR_CENTRE, PWM_CH_CENTRE);
    ledcAttachPin(PIN_MOTOR_RIGHT,  PWM_CH_RIGHT);

    // ── Self-test: brief pulse on each motor ──────────────────────────────────
    Serial.println("[HAPTIC] Self-test: LEFT → CENTRE → RIGHT");
    burst(ZONE_LEFT,   200, 80, 80);
    burst(ZONE_CENTRE, 200, 80, 80);
    burst(ZONE_RIGHT,  200, 80, 0);
    delay(200);

    // ── BLE initialisation ────────────────────────────────────────────────────
    BLEDevice::init("AurigaHaptic");

    pServer = BLEDevice::createServer();
    pServer->setCallbacks(new AurigaServerCallbacks());

    BLEService* pService = pServer->createService(AURIGA_SERVICE_UUID);

    pHapticChar = pService->createCharacteristic(
        AURIGA_HAPTIC_CHAR_UUID,
        BLECharacteristic::PROPERTY_WRITE_NR   // write without response (low latency)
        | BLECharacteristic::PROPERTY_WRITE    // also allow write with response
    );
    pHapticChar->setCallbacks(new HapticCharCallbacks());

    pService->start();

    BLEAdvertising* pAdv = BLEDevice::getAdvertising();
    pAdv->addServiceUUID(AURIGA_SERVICE_UUID);
    pAdv->setScanResponse(true);
    pAdv->setMinPreferred(0x06);   // recommended for iOS compatibility
    BLEDevice::startAdvertising();

    Serial.println("[BLE] Advertising as 'AurigaHaptic'. Waiting for connection…");
}

// ─────────────────────────────────────────────────────────────────────────────
// loop()
// ─────────────────────────────────────────────────────────────────────────────

void loop() {
    // Process any pending haptic command queued from the BLE callback
    if (pendingCmd.valid) {
        HapticCommand cmd = {
            pendingCmd.zone,
            pendingCmd.intensity,
            pendingCmd.pattern,
            false
        };
        pendingCmd.valid = false;   // consume

        Serial.printf("[HAPTIC] Playing zone=%u intensity=%u pattern=%u\n",
                      cmd.zone, cmd.intensity, cmd.pattern);
        playPattern(cmd.zone, cmd.intensity, cmd.pattern);
    }

    // Heartbeat LED on built-in LED (GPIO 2 on most ESP32 boards)
    static unsigned long lastBlink = 0;
    static bool ledState = false;
    if (millis() - lastBlink > (deviceConnected ? 2000 : 500)) {
        lastBlink = millis();
        ledState  = !ledState;
        digitalWrite(LED_BUILTIN, ledState ? HIGH : LOW);
    }

    delay(10);   // yield to RTOS idle tasks
}
