/**
 * AurigaMCU — Arduino Nano 33 BLE Haptic Belt
 * ============================================
 * Session 20 — Phase 4 (Platforms). Parallel with Session 19 (ESP32 node).
 *
 * Role
 * ----
 * Pure BLE haptic receiver node. Pairs with the Auriga Android app or
 * AurigaPi and drives three ERM vibration motors via GPIO PWM:
 *   - Motor 0 — LEFT   (D3  / PWM)
 *   - Motor 1 — CENTRE (D5  / PWM)
 *   - Motor 2 — RIGHT  (D6  / PWM)
 *
 * Uses the official ArduinoBLE library (Arduino Nano 33 BLE built-in radio).
 *
 * BLE GATT server (same service/characteristic UUIDs as the ESP32 node)
 * -----------------------------------------------------------------------
 * Service UUID     : 4155524901-0000-1000-8000-00805f9b34fb
 * Characteristic   : 4155524902-0000-1000-8000-00805f9b34fb
 *                    Properties: WRITE | WRITE_WITHOUT_RESPONSE
 *
 * 3-byte protocol (identical on all Auriga satellite nodes)
 * ---------------------------------------------------------
 * Byte 0 — ZONE
 *   0x00 = LEFT
 *   0x01 = CENTRE
 *   0x02 = RIGHT
 *   0x03 = ALL   (all three motors simultaneously)
 *
 * Byte 1 — INTENSITY   0–255  (mapped to analogWrite PWM duty)
 *
 * Byte 2 — PATTERN
 *   0x00 = SINGLE      100ms burst
 *   0x01 = PULSE       2× 100ms bursts, 100ms gap
 *   0x02 = CONTINUOUS  500ms hold
 *   0x03 = SOS         ··· — — — ··· Morse pattern
 *   0x04 = STAIR_WARN  200ms ON / 100ms OFF × 2
 *   0x05 = OBSTACLE    4× 50ms fast buzz
 *   0x06 = COMMAND_ACK 50ms tick
 *
 * Hardware bill of materials
 * --------------------------
 *   Arduino Nano 33 BLE × 1        (~$25)
 *   ERM coin vibration motor × 3   (~$3)
 *   N-channel MOSFET 2N7000 × 3    (~$1)   (or L293D half-H-bridge)
 *   1N4148 flyback diode × 3       (<$1)
 *   1 kΩ resistor × 3              (<$1)
 *   LiPo 500mAh + MCP73831 charger (~$6)
 *   Neoprene belt or wristband
 *   Total BOM: ~$36
 *
 * MOSFET wiring (per motor)
 * -------------------------
 *   Arduino PWM pin → 1kΩ → MOSFET gate
 *   MOSFET drain    → motor (–) terminal
 *   Motor (+)       → 3.3V (Nano 33 BLE is 3.3V logic / supply)
 *   MOSFET source   → GND
 *   1N4148 cathode  → motor (+), anode → motor (–)   [flyback protection]
 *
 * Note: Nano 33 BLE runs at 3.3V. Most 5V ERMs still spin at 3.3V with
 * reduced amplitude (~70%). For full intensity use a 5V boost converter
 * and keep the MOSFET gate driven from the 3.3V Arduino pin.
 *
 * Arduino IDE setup
 * -----------------
 *   1. Board: "Arduino Nano 33 BLE" (Arduino Mbed OS Nano boards 4.x.x)
 *   2. Library: ArduinoBLE 1.3.x (install via Library Manager)
 *   3. Port: select the Nano's COMx / /dev/ttyACMx port
 *
 * Power consumption
 * -----------------
 *   Idle (BLE advertising)  : ~9 mA
 *   Active (3 motors @ 3.3V): ~80–120 mA
 *   Estimated runtime (500mAh LiPo): ~6 hours idle / ~4 hours active
 */

#include <ArduinoBLE.h>

// ── Motor PWM pins ────────────────────────────────────────────────────────────
static const uint8_t PIN_LEFT   = 3;
static const uint8_t PIN_CENTRE = 5;
static const uint8_t PIN_RIGHT  = 6;

// ── BLE UUIDs (must match ESP32 node and Android BleHapticEngine) ─────────────
static const char* SERVICE_UUID  = "4155524901-0000-1000-8000-00805f9b34fb";
static const char* HAPTIC_CHAR_UUID = "4155524902-0000-1000-8000-00805f9b34fb";

// ── Protocol byte constants ───────────────────────────────────────────────────
enum Zone    : uint8_t { ZONE_LEFT=0, ZONE_CENTRE, ZONE_RIGHT, ZONE_ALL };
enum Pattern : uint8_t {
    PAT_SINGLE=0, PAT_PULSE, PAT_CONTINUOUS, PAT_SOS,
    PAT_STAIR, PAT_OBSTACLE, PAT_CMD_ACK
};

// ─────────────────────────────────────────────────────────────────────────────
// BLE objects
// ─────────────────────────────────────────────────────────────────────────────

BLEService           hapticService(SERVICE_UUID);
BLECharacteristic    hapticChar(
    HAPTIC_CHAR_UUID,
    BLEWrite | BLEWriteWithoutResponse,
    3    // fixed 3-byte payload
);

// ─────────────────────────────────────────────────────────────────────────────
// Motor helpers
// ─────────────────────────────────────────────────────────────────────────────

static void motorSet(uint8_t zone, uint8_t intensity) {
    uint8_t duty = intensity;
    if (zone == ZONE_LEFT   || zone == ZONE_ALL) analogWrite(PIN_LEFT,   duty);
    if (zone == ZONE_CENTRE || zone == ZONE_ALL) analogWrite(PIN_CENTRE, duty);
    if (zone == ZONE_RIGHT  || zone == ZONE_ALL) analogWrite(PIN_RIGHT,  duty);
}

static void motorOff(uint8_t zone) { motorSet(zone, 0); }

static void burst(uint8_t zone, uint8_t intensity, uint16_t onMs, uint16_t offMs = 0) {
    motorSet(zone, intensity);
    delay(onMs);
    motorOff(zone);
    if (offMs > 0) delay(offMs);
}

// ─────────────────────────────────────────────────────────────────────────────
// Pattern playback (blocking — called from main loop)
// ─────────────────────────────────────────────────────────────────────────────

static void playPattern(uint8_t zone, uint8_t intensity, uint8_t pattern) {
    switch (pattern) {

        case PAT_SINGLE:
            burst(zone, intensity, 100);
            break;

        case PAT_PULSE:
            burst(zone, intensity, 100, 100);
            burst(zone, intensity, 100);
            break;

        case PAT_CONTINUOUS:
            motorSet(zone, intensity);
            delay(500);
            motorOff(zone);
            break;

        case PAT_SOS:
            // · · ·
            for (int i = 0; i < 3; i++) burst(zone, intensity, 100, 100);
            delay(200);
            // — — —
            for (int i = 0; i < 3; i++) burst(zone, intensity, 300, 100);
            delay(200);
            // · · ·
            for (int i = 0; i < 3; i++) burst(zone, intensity, 100, 100);
            break;

        case PAT_STAIR:
            burst(zone, intensity, 200, 100);
            burst(zone, intensity, 200);
            break;

        case PAT_OBSTACLE:
            for (int i = 0; i < 4; i++) burst(zone, intensity, 50, 50);
            break;

        case PAT_CMD_ACK:
            burst(zone, intensity, 50);
            break;

        default:
            // Unknown pattern — safe fallback single burst
            Serial.print("[HAPTIC] Unknown pattern 0x");
            Serial.println(pattern, HEX);
            burst(zone, intensity, 100);
            break;
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Self-test — fire each motor sequentially on boot
// ─────────────────────────────────────────────────────────────────────────────

static void selfTest() {
    Serial.println("[HAPTIC] Self-test: LEFT → CENTRE → RIGHT");
    burst(ZONE_LEFT,   200, 80, 80);
    burst(ZONE_CENTRE, 200, 80, 80);
    burst(ZONE_RIGHT,  200, 80, 0);
    delay(200);
}

// ─────────────────────────────────────────────────────────────────────────────
// setup()
// ─────────────────────────────────────────────────────────────────────────────

void setup() {
    Serial.begin(115200);
    // Don't block headless operation waiting for Serial monitor
    unsigned long t0 = millis();
    while (!Serial && millis() - t0 < 2000) {}
    Serial.println("\n[AURIGA-BELT] Arduino Nano 33 BLE haptic belt starting…");

    // ── Motor pin setup ───────────────────────────────────────────────────────
    pinMode(PIN_LEFT,   OUTPUT);
    pinMode(PIN_CENTRE, OUTPUT);
    pinMode(PIN_RIGHT,  OUTPUT);
    analogWrite(PIN_LEFT,   0);
    analogWrite(PIN_CENTRE, 0);
    analogWrite(PIN_RIGHT,  0);

    // ── Self-test ─────────────────────────────────────────────────────────────
    selfTest();

    // ── BLE init ──────────────────────────────────────────────────────────────
    if (!BLE.begin()) {
        Serial.println("[ERROR] BLE initialisation failed! Halting.");
        // Rapid triple-flash on built-in LED to indicate hard fault
        while (true) {
            for (int i = 0; i < 3; i++) {
                digitalWrite(LED_BUILTIN, HIGH); delay(100);
                digitalWrite(LED_BUILTIN, LOW);  delay(100);
            }
            delay(500);
        }
    }

    BLE.setLocalName("AurigaBelt");
    BLE.setAdvertisedService(hapticService);

    // Add the haptic characteristic to the service
    hapticService.addCharacteristic(hapticChar);

    // Register the service with the BLE stack
    BLE.addService(hapticService);

    // Start advertising
    BLE.advertise();
    Serial.println("[BLE] Advertising as 'AurigaBelt'. Waiting for connection…");

    // Indicate ready: slow blink
    pinMode(LED_BUILTIN, OUTPUT);
}

// ─────────────────────────────────────────────────────────────────────────────
// loop()
// ─────────────────────────────────────────────────────────────────────────────

void loop() {
    // Poll for BLE events (connection, writes)
    BLEDevice central = BLE.central();

    if (central) {
        Serial.print("[BLE] Connected to: ");
        Serial.println(central.address());

        // Acknowledge connection
        playPattern(ZONE_CENTRE, 180, PAT_CMD_ACK);

        // Heartbeat LED: solid ON while connected
        digitalWrite(LED_BUILTIN, HIGH);

        while (central.connected()) {
            // Check for a new write to the haptic characteristic
            if (hapticChar.written()) {
                uint8_t buf[3] = {0, 0, 0};
                int len = hapticChar.readValue(buf, 3);

                if (len < 3) {
                    Serial.println("[BLE] Short packet (< 3 bytes) — ignored.");
                    continue;
                }

                uint8_t zone      = buf[0];
                uint8_t intensity = buf[1];
                uint8_t pattern   = buf[2];

                Serial.print("[BLE] CMD zone=0x");
                Serial.print(zone, HEX);
                Serial.print(" intensity=");
                Serial.print(intensity);
                Serial.print(" pattern=0x");
                Serial.println(pattern, HEX);

                // Guard against out-of-range zone
                if (zone > ZONE_ALL) {
                    Serial.print("[HAPTIC] Invalid zone 0x");
                    Serial.print(zone, HEX);
                    Serial.println(" — clamping to CENTRE.");
                    zone = ZONE_CENTRE;
                }

                playPattern(zone, intensity, pattern);
            }
        }

        // Client disconnected
        Serial.print("[BLE] Disconnected from: ");
        Serial.println(central.address());
        digitalWrite(LED_BUILTIN, LOW);
        // ArduinoBLE resumes advertising automatically after disconnect
    }

    // Heartbeat blink while advertising (500ms period)
    static unsigned long lastBlink = 0;
    static bool ledState = false;
    if (millis() - lastBlink > 500) {
        lastBlink = millis();
        ledState  = !ledState;
        digitalWrite(LED_BUILTIN, ledState ? HIGH : LOW);
    }
}
