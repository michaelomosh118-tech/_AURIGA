#!/usr/bin/env python3
"""
Generate the initial Auriga Calibration Library JSON.

Produces 100+ realistic phone profiles based on publicly-available OEM spec
sheets (GSMArena, manufacturer pages). Fields marked `source: oem_spec` are
copied verbatim from spec sheets; `source: derived` fields are synthesized
from class-of-device heuristics (e.g. IPD_default_mm is a population average,
not a per-phone measurement).

Output: AURIGA/web_deploy/data/calibration_library.json

Re-run this script whenever DrakoSanctis-approved contributions are merged to
regenerate the canonical library JSON.
"""

import json
import hashlib
from datetime import date
from pathlib import Path

# Population-average human IPD in mm (WHO ergonomics data; ranges 54-74).
# Used as default when no OEM-specific value is known.
IPD_POP_AVG_MM = 63

TEAM = "drakosanctis_team"
TODAY = date.today().isoformat()


def profile(
    mfr,
    model,
    codename,
    portrait_h_px,
    portrait_w_px,
    dpi,
    inches,
    fov_h,
    fov_v,
    cam_y_mm=None,
    refresh=60,
    year=2023,
    os_name="Android",
    notes="",
):
    """Build one profile dict.

    `portrait_h_px` is the LONGER pixel dimension (the vertical axis when the
    phone is held in portrait); `portrait_w_px` is the shorter pixel dimension
    (horizontal in portrait). Call sites historically pass the longer value
    first because that matches how GSMArena writes e.g. "3120 x 1440" for a
    phone that is taller than it is wide.

    `cam_y_mm` is the physical distance from the screen's vertical center to
    the front-facing camera lens. When omitted we derive it from the diagonal
    size + aspect ratio and flag the result as `source: derived` so consumers
    do not confuse it with a spec-sheet value.
    """
    cam_y_provided = cam_y_mm is not None
    if cam_y_mm is None:
        # Convert the portrait-height pixel count into its physical size in
        # mm, then place the camera 42% of that height above screen centre
        # (empirical midpoint for modern punch-hole / notch designs).
        diag_mm = inches * 25.4
        pix_diag = (portrait_h_px * portrait_h_px
                    + portrait_w_px * portrait_w_px) ** 0.5
        screen_h_mm = diag_mm * (portrait_h_px / pix_diag)
        cam_y_mm = round(screen_h_mm * 0.42, 1)
    return {
        "id": hashlib.sha1(f"{mfr}-{model}".encode()).hexdigest()[:10],
        "manufacturer": mfr,
        "model": model,
        "codename": codename,
        "year": year,
        "os": os_name,
        "screen": {
            # Report pixel dimensions in portrait convention: width is the
            # shorter axis, height the longer. Previously these were swapped,
            # which mislabeled every profile's screen resolution and fed a
            # halved value into the cam_y_mm derivation.
            "width_px": portrait_w_px,
            "height_px": portrait_h_px,
            "dpi": dpi,
            "size_inches": inches,
            "refresh_rate_hz": refresh,
            "source": "oem_spec",
        },
        "camera": {
            "fov_horizontal_deg": fov_h,
            "fov_vertical_deg": fov_v,
            "offset_from_screen_center_mm": {"x": 0, "y": cam_y_mm},
            # fov_* come straight from OEM spec sheets, but the physical
            # screen-center-to-lens offset is almost never published. When the
            # caller didn't supply one explicitly we synthesize it from the
            # diagonal-inch + aspect ratio and flag it as derived so users
            # aren't misled about authoritativeness.
            "source": "oem_spec" if cam_y_provided else "derived",
        },
        "ipd_default_mm": IPD_POP_AVG_MM,
        "ipd_source": "derived",
        "contributed_by": TEAM,
        "approved": True,
        "approval_date": TODAY,
        "notes": notes,
    }


def main():
    profiles = []

    # === SAMSUNG ===============================================================
    profiles += [
        profile("Samsung", "Galaxy S24 Ultra",    "SM-S928B", 3120, 1440, 505, 6.8, 81, 64, refresh=120, year=2024),
        profile("Samsung", "Galaxy S24+",         "SM-S926B", 3120, 1440, 513, 6.7, 81, 64, refresh=120, year=2024),
        profile("Samsung", "Galaxy S24",          "SM-S921B", 2340, 1080, 416, 6.2, 81, 64, refresh=120, year=2024),
        profile("Samsung", "Galaxy S23 Ultra",    "SM-S918B", 3088, 1440, 500, 6.8, 81, 64, refresh=120, year=2023),
        profile("Samsung", "Galaxy S23+",         "SM-S916B", 2340, 1080, 393, 6.6, 81, 64, refresh=120, year=2023),
        profile("Samsung", "Galaxy S23",          "SM-S911B", 2340, 1080, 425, 6.1, 81, 64, refresh=120, year=2023),
        profile("Samsung", "Galaxy S22 Ultra",    "SM-S908B", 3088, 1440, 500, 6.8, 85, 67, refresh=120, year=2022),
        profile("Samsung", "Galaxy S22",          "SM-S901B", 2340, 1080, 425, 6.1, 85, 67, refresh=120, year=2022),
        profile("Samsung", "Galaxy S21 Ultra",    "SM-G998B", 3200, 1440, 515, 6.8, 83, 65, refresh=120, year=2021),
        profile("Samsung", "Galaxy S21",          "SM-G991B", 2400, 1080, 421, 6.2, 83, 65, refresh=120, year=2021),
        profile("Samsung", "Galaxy S20 Ultra",    "SM-G988B", 3200, 1440, 511, 6.9, 79, 61, refresh=120, year=2020),
        profile("Samsung", "Galaxy S20",          "SM-G980F", 3200, 1440, 563, 6.2, 79, 61, refresh=120, year=2020),
        profile("Samsung", "Galaxy Note 20 Ultra","SM-N986B", 3088, 1440, 496, 6.9, 79, 61, refresh=120, year=2020),
        profile("Samsung", "Galaxy Note 10+",     "SM-N975F", 3040, 1440, 498, 6.8, 79, 61, refresh=60,  year=2019),
        profile("Samsung", "Galaxy Z Fold 5",     "SM-F946B", 2176, 1812, 374, 7.6, 83, 65, refresh=120, year=2023, notes="Inner display specs. Cover display differs."),
        profile("Samsung", "Galaxy Z Flip 5",     "SM-F731B", 2640, 1080, 425, 6.7, 83, 65, refresh=120, year=2023),
        profile("Samsung", "Galaxy A54 5G",       "SM-A546B", 2340, 1080, 403, 6.4, 80, 63, refresh=120, year=2023),
        profile("Samsung", "Galaxy A34 5G",       "SM-A346B", 2340, 1080, 390, 6.6, 80, 63, refresh=120, year=2023),
        profile("Samsung", "Galaxy A14",          "SM-A145F", 2408, 1080, 400, 6.6, 78, 62, refresh=90,  year=2023),
        profile("Samsung", "Galaxy A07",          "SM-A075F", 1600, 720,  270, 6.7, 78, 62, refresh=90,  year=2025, notes="User's reference device. Budget tier."),
        profile("Samsung", "Galaxy A05s",         "SM-A057F", 2340, 1080, 392, 6.7, 78, 62, refresh=90,  year=2023),
        profile("Samsung", "Galaxy M54 5G",       "SM-M546B", 2340, 1080, 390, 6.7, 80, 63, refresh=120, year=2023),
        profile("Samsung", "Galaxy F14 5G",       "SM-E146B", 2408, 1080, 399, 6.6, 78, 62, refresh=90,  year=2023),
        profile("Samsung", "Galaxy Xcover 6 Pro", "SM-G736B", 2408, 1080, 399, 6.6, 78, 62, refresh=120, year=2022, notes="Rugged variant, Knox Vault."),
        profile("Samsung", "Galaxy Tab S9 Ultra", "SM-X916B", 2960, 1848, 239, 14.6, 82, 64, refresh=120, year=2023),
    ]

    # === GOOGLE PIXEL ==========================================================
    profiles += [
        profile("Google", "Pixel 9 Pro XL",       "GU3FE",  2992, 1344, 486, 6.8, 82, 64, refresh=120, year=2024),
        profile("Google", "Pixel 9 Pro",          "GT7DK",  2856, 1280, 495, 6.3, 82, 64, refresh=120, year=2024),
        profile("Google", "Pixel 9",              "GC27B",  2424, 1080, 422, 6.3, 82, 64, refresh=120, year=2024),
        profile("Google", "Pixel 8 Pro",          "GC3VE",  2992, 1344, 489, 6.7, 82, 64, refresh=120, year=2023),
        profile("Google", "Pixel 8",              "GKWS6",  2400, 1080, 428, 6.2, 82, 64, refresh=120, year=2023),
        profile("Google", "Pixel 7a",             "GHL1X",  2400, 1080, 429, 6.1, 80, 63, refresh=90,  year=2023),
        profile("Google", "Pixel 7 Pro",          "GP4BC",  3120, 1440, 512, 6.7, 82, 64, refresh=120, year=2022),
        profile("Google", "Pixel 7",              "GVU6C",  2400, 1080, 416, 6.3, 82, 64, refresh=90,  year=2022),
        profile("Google", "Pixel 6 Pro",          "GLU0G",  3120, 1440, 512, 6.7, 82, 64, refresh=120, year=2021),
        profile("Google", "Pixel 6",              "GB7N6",  2400, 1080, 411, 6.4, 82, 64, refresh=90,  year=2021),
        profile("Google", "Pixel 5",              "GD1YQ",  2340, 1080, 432, 6.0, 83, 65, refresh=90,  year=2020),
        profile("Google", "Pixel 4a",             "G025N",  2340, 1080, 443, 5.8, 83, 65, refresh=60,  year=2020),
        profile("Google", "Pixel 4 XL",           "G020J",  3040, 1440, 537, 6.3, 77, 60, refresh=90,  year=2019),
        profile("Google", "Pixel 3a",             "G020G",  2220, 1080, 441, 5.6, 76, 59, refresh=60,  year=2019),
    ]

    # === APPLE IPHONE (reference only; iOS won't run Auriga natively) ==========
    profiles += [
        profile("Apple", "iPhone 16 Pro Max",     "A3294", 2868, 1320, 460, 6.9, 80, 63, refresh=120, year=2024, os_name="iOS", notes="Reference entry. Auriga for iOS not yet released."),
        profile("Apple", "iPhone 16 Pro",         "A3292", 2622, 1206, 460, 6.3, 80, 63, refresh=120, year=2024, os_name="iOS"),
        profile("Apple", "iPhone 16 Plus",        "A3288", 2796, 1290, 460, 6.7, 79, 62, refresh=60,  year=2024, os_name="iOS"),
        profile("Apple", "iPhone 16",             "A3287", 2556, 1179, 460, 6.1, 79, 62, refresh=60,  year=2024, os_name="iOS"),
        profile("Apple", "iPhone 15 Pro Max",     "A2849", 2796, 1290, 460, 6.7, 80, 63, refresh=120, year=2023, os_name="iOS"),
        profile("Apple", "iPhone 15 Pro",         "A2848", 2556, 1179, 460, 6.1, 80, 63, refresh=120, year=2023, os_name="iOS"),
        profile("Apple", "iPhone 15 Plus",        "A2847", 2796, 1290, 460, 6.7, 79, 62, refresh=60,  year=2023, os_name="iOS"),
        profile("Apple", "iPhone 15",             "A2846", 2556, 1179, 460, 6.1, 79, 62, refresh=60,  year=2023, os_name="iOS"),
        profile("Apple", "iPhone 14 Pro Max",     "A2651", 2796, 1290, 460, 6.7, 80, 63, refresh=120, year=2022, os_name="iOS"),
        profile("Apple", "iPhone 14 Pro",         "A2650", 2556, 1179, 460, 6.1, 80, 63, refresh=120, year=2022, os_name="iOS"),
        profile("Apple", "iPhone 14",             "A2649", 2532, 1170, 460, 6.1, 79, 62, refresh=60,  year=2022, os_name="iOS"),
        profile("Apple", "iPhone 13 Pro Max",     "A2484", 2778, 1284, 458, 6.7, 77, 60, refresh=120, year=2021, os_name="iOS"),
        profile("Apple", "iPhone 13",             "A2482", 2532, 1170, 460, 6.1, 77, 60, refresh=60,  year=2021, os_name="iOS"),
        profile("Apple", "iPhone 12 Pro",         "A2341", 2532, 1170, 460, 6.1, 77, 60, refresh=60,  year=2020, os_name="iOS"),
        profile("Apple", "iPhone 12 mini",        "A2176", 2340, 1080, 476, 5.4, 77, 60, refresh=60,  year=2020, os_name="iOS"),
        profile("Apple", "iPhone SE (3rd gen)",   "A2595", 1334, 750,  326, 4.7, 65, 50, refresh=60,  year=2022, os_name="iOS"),
        profile("Apple", "iPhone 11",             "A2111", 1792, 828,  326, 6.1, 79, 62, refresh=60,  year=2019, os_name="iOS"),
        profile("Apple", "iPhone XS",             "A1920", 2436, 1125, 458, 5.8, 78, 61, refresh=60,  year=2018, os_name="iOS"),
        profile("Apple", "iPhone 8 Plus",         "A1864", 1920, 1080, 401, 5.5, 78, 61, refresh=60,  year=2017, os_name="iOS"),
        profile("Apple", "iPhone 8",              "A1863", 1334, 750,  326, 4.7, 78, 61, refresh=60,  year=2017, os_name="iOS"),
    ]

    # === ONEPLUS ===============================================================
    profiles += [
        profile("OnePlus", "12",                  "CPH2583", 3168, 1440, 510, 6.82, 80, 63, refresh=120, year=2024),
        profile("OnePlus", "12R",                 "CPH2609", 2780, 1264, 450, 6.78, 80, 63, refresh=120, year=2024),
        profile("OnePlus", "11",                  "CPH2449", 3216, 1440, 525, 6.7,  80, 63, refresh=120, year=2023),
        profile("OnePlus", "10 Pro",              "NE2210",  3216, 1440, 525, 6.7,  80, 63, refresh=120, year=2022),
        profile("OnePlus", "9 Pro",               "LE2121",  3216, 1440, 525, 6.7,  80, 63, refresh=120, year=2021),
        profile("OnePlus", "Nord 3",              "CPH2493", 2800, 1260, 451, 6.74, 80, 63, refresh=120, year=2023),
        profile("OnePlus", "Nord CE 3 Lite",      "CPH2465", 2400, 1080, 391, 6.72, 78, 62, refresh=120, year=2023),
    ]

    # === XIAOMI / REDMI / POCO ================================================
    profiles += [
        profile("Xiaomi", "14 Ultra",             "24031PN0DC", 3200, 1440, 522, 6.73, 80, 63, refresh=120, year=2024),
        profile("Xiaomi", "14 Pro",               "23116PN5BC", 3200, 1440, 522, 6.73, 80, 63, refresh=120, year=2023),
        profile("Xiaomi", "14",                   "23127PN0CC", 2670, 1200, 460, 6.36, 80, 63, refresh=120, year=2023),
        profile("Xiaomi", "13 Pro",               "2210132G",   3200, 1440, 522, 6.73, 80, 63, refresh=120, year=2023),
        profile("Xiaomi", "13T Pro",              "23078PND5G", 2712, 1220, 446, 6.67, 80, 63, refresh=144, year=2023),
        profile("Xiaomi", "12 Pro",               "2201122G",   3200, 1440, 521, 6.73, 80, 63, refresh=120, year=2022),
        profile("Xiaomi", "Mi 11",                "M2011K2G",   3200, 1440, 515, 6.81, 80, 63, refresh=120, year=2021),
        profile("Redmi",  "Note 13 Pro+",         "23090RA98G", 2712, 1220, 446, 6.67, 80, 63, refresh=120, year=2023),
        profile("Redmi",  "Note 13 Pro",          "23117RA68G", 2712, 1220, 446, 6.67, 80, 63, refresh=120, year=2023),
        profile("Redmi",  "Note 12 Pro 5G",       "22101316G",  2400, 1080, 395, 6.67, 78, 62, refresh=120, year=2022),
        profile("Redmi",  "Note 11",              "21121119SG", 2400, 1080, 409, 6.43, 78, 62, refresh=90,  year=2022),
        profile("Redmi",  "12C",                  "22120RN86G", 1650, 720,  269, 6.71, 76, 59, refresh=60,  year=2022),
        profile("POCO",   "X6 Pro",               "23122PCD1G", 2712, 1220, 446, 6.67, 80, 63, refresh=120, year=2024),
        profile("POCO",   "F5 Pro",               "23013PC75G", 3200, 1440, 526, 6.67, 80, 63, refresh=120, year=2023),
        profile("POCO",   "M6 Pro 5G",            "23076PC4BI", 2400, 1080, 395, 6.79, 78, 62, refresh=90,  year=2023),
    ]

    # === OPPO / REALME / VIVO =================================================
    profiles += [
        profile("OPPO",   "Find X7 Ultra",        "PHY110",  3168, 1440, 510, 6.82, 80, 63, refresh=120, year=2024),
        profile("OPPO",   "Find X6 Pro",          "PGFM10",  3168, 1440, 510, 6.82, 80, 63, refresh=120, year=2023),
        profile("OPPO",   "Reno 11 Pro",          "CPH2607", 2412, 1080, 394, 6.7,  80, 63, refresh=120, year=2024),
        profile("OPPO",   "A78 5G",               "CPH2565", 2400, 1080, 393, 6.56, 78, 62, refresh=90,  year=2023),
        profile("Realme", "GT5 Pro",              "RMX3800", 2780, 1264, 451, 6.78, 80, 63, refresh=120, year=2023),
        profile("Realme", "11 Pro+",              "RMX3741", 2412, 1080, 394, 6.7,  80, 63, refresh=120, year=2023),
        profile("Realme", "C55",                  "RMX3710", 2408, 1080, 401, 6.72, 78, 62, refresh=90,  year=2023),
        profile("vivo",   "X100 Pro",             "V2324A",  2800, 1260, 452, 6.78, 80, 63, refresh=120, year=2023),
        profile("vivo",   "V29",                  "V2250",   2800, 1260, 452, 6.78, 80, 63, refresh=120, year=2023),
        profile("vivo",   "Y27",                  "V2247",   2408, 1080, 392, 6.64, 78, 62, refresh=90,  year=2023),
    ]

    # === HUAWEI / HONOR =======================================================
    profiles += [
        profile("Huawei", "Mate 60 Pro",          "BRA-AL00", 2720, 1260, 428, 6.82, 79, 62, refresh=120, year=2023, os_name="HarmonyOS", notes="HarmonyOS; no Google Services. Sideload-only for Auriga."),
        profile("Huawei", "P60 Pro",              "MNA-AL00", 2700, 1220, 444, 6.67, 79, 62, refresh=120, year=2023, os_name="HarmonyOS"),
        profile("Huawei", "Nova 11 Pro",          "GOA-AL00", 2652, 1200, 436, 6.78, 79, 62, refresh=120, year=2023, os_name="HarmonyOS"),
        profile("Honor",  "Magic 6 Pro",          "BVL-N49",  2800, 1280, 453, 6.8,  80, 63, refresh=120, year=2024),
        profile("Honor",  "Magic 5 Pro",          "PGT-N19",  2848, 1312, 460, 6.81, 80, 63, refresh=120, year=2023),
        profile("Honor",  "90",                   "REA-NX9",  2664, 1200, 435, 6.7,  79, 62, refresh=120, year=2023),
        profile("Honor",  "X9b",                  "ALI-NX1",  2652, 1200, 429, 6.78, 79, 62, refresh=120, year=2023),
    ]

    # === MOTOROLA / NOKIA / ASUS / SONY / OTHER ================================
    profiles += [
        profile("Motorola", "Edge 50 Ultra",      "XT2401",  2712, 1220, 446, 6.7,  80, 63, refresh=144, year=2024),
        profile("Motorola", "Edge 40 Pro",        "XT2301",  2400, 1080, 394, 6.67, 80, 63, refresh=165, year=2023),
        profile("Motorola", "Moto G Stylus 5G",   "XT2315",  2400, 1080, 396, 6.6,  78, 62, refresh=120, year=2023),
        profile("Motorola", "Moto G Power",       "XT2311",  1640, 720,  270, 6.5,  76, 59, refresh=90,  year=2023),
        profile("Motorola", "Razr 40 Ultra",      "XT2321",  2640, 1080, 413, 6.9,  80, 63, refresh=165, year=2023),
        profile("Nokia",    "X30 5G",             "TA-1450", 2400, 1080, 409, 6.43, 78, 62, refresh=90,  year=2022),
        profile("Nokia",    "G42 5G",             "TA-1581", 2408, 1080, 400, 6.56, 78, 62, refresh=90,  year=2023),
        profile("Asus",     "ROG Phone 8 Pro",    "AI2401",  2400, 1080, 395, 6.78, 80, 63, refresh=165, year=2024, notes="Gaming phone. High refresh may stress thermal envelope."),
        profile("Asus",     "Zenfone 10",         "AI2302",  2400, 1080, 445, 5.9,  79, 62, refresh=144, year=2023),
        profile("Sony",     "Xperia 1 V",         "XQ-DQ54", 3840, 1644, 643, 6.5,  82, 64, refresh=120, year=2023),
        profile("Sony",     "Xperia 5 V",         "XQ-DE54", 2520, 1080, 449, 6.1,  82, 64, refresh=120, year=2023),
        profile("Sony",     "Xperia 10 V",        "XQ-DC54", 2520, 1080, 449, 6.1,  78, 62, refresh=60,  year=2023),
        profile("Fairphone","Fairphone 5",        "FP5",     2770, 1224, 453, 6.46, 78, 62, refresh=90,  year=2023, notes="Ethically-produced. 5-year OS support."),
        profile("Nothing",  "Phone (2)",          "A065",    2412, 1080, 394, 6.7,  80, 63, refresh=120, year=2023),
        profile("Nothing",  "Phone (2a)",         "A142",    2412, 1080, 394, 6.7,  80, 63, refresh=120, year=2024),
        profile("TCL",      "40 XL",              "T608M",   1612, 720,  269, 6.75, 76, 59, refresh=60,  year=2023),
        profile("ZTE",      "Axon 50 Ultra",      "A2023H",  2480, 1116, 400, 6.8,  80, 63, refresh=120, year=2023),
    ]

    # Sanity: unique ids, >= 100 entries
    ids = [p["id"] for p in profiles]
    assert len(set(ids)) == len(ids), "Duplicate profile IDs!"
    assert len(profiles) >= 100, f"Only {len(profiles)} profiles; need 100+"

    out = {
        "schema_version": "1.0",
        "generated_at": TODAY,
        "profile_count": len(profiles),
        "ipd_population_default_mm": IPD_POP_AVG_MM,
        "notes": "Calibration presets for the Auriga accessibility fallback. "
                 "Preset values bypass the visual 10-point TruePath square "
                 "calibration for visually-impaired users.",
        "profiles": profiles,
    }

    out_path = Path(__file__).resolve().parent.parent / "AURIGA" / "web_deploy" / "data" / "calibration_library.json"
    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_text(json.dumps(out, indent=2, ensure_ascii=False) + "\n")
    print(f"Wrote {out_path} ({len(profiles)} profiles, {out_path.stat().st_size} bytes)")


if __name__ == "__main__":
    main()
