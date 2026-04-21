# Auriga bundled calibration assets

Boot-time fallback profiles that ship inside the APK. Used when the
remote calibration-library fetch has not returned yet (slow / no
network / first launch) so the engine does not run off the synthetic
640x480 defaults baked into `FiducialLUT`.

## Filename convention

```
default_profile_<Build.MODEL>.json
```

`MainActivity.onCreate` loads this asset if it exists for the running
device. Example: `default_profile_SM-A057F.json` targets the Samsung
Galaxy A07 (Navi dev device).

## Schema

Identical to `data/calibration_profiles.json` on the public mirror so
the same parser (`FiducialLUT.parseProfileJson`) handles both.

```json
[
  {
    "manufacturer": "samsung",
    "model": "SM-A057F",
    "codename": "a05s",
    "approved": true,
    "notes": "...",
    "reference_frame_width": 640,
    "reference_frame_height": 288,
    "calibration_points": [
      { "distanceM": 0.5, "pixelWidth": 800.0, "pixelRow": 276.0 },
      ...
    ]
  }
]
```

`reference_frame_width` / `reference_frame_height` document the bitmap
dimensions the anchors were measured in. The runtime bitmap size is
chosen dynamically to match the `TextureView` aspect (see
`MainActivity.startMainLoop`), so future releases that change the
target bitmap size will rescale queries rather than retrain the LUT.

## Replacing a placeholder

1. On a target device, run the 10-point calibration walk.
2. Export the captured anchors via the "contribute readings" form on
   `calibration-library.html`.
3. Moderator copies the approved entry into
   `app/src/main/assets/default_profile_<MODEL>.json`.
4. Tag a release.

Entries merged into `data/calibration_profiles.json` on the mirror
reach already-installed devices without an APK update; the asset copy
is strictly a fallback for first launch and offline use.
