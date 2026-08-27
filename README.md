# SoproCameraTest v0.2

Second diagnostic build for the Sopro/eMPIA intraoral camera.

Target:
- VID `EB1A`
- PID `2821`

v0.2 uses the Android phone itself as the diagnostic tool. No Wireshark is required.

## New tests

1. `CONNECT CAMERA`
2. `PROBE EM28XX REGISTERS`
   - read-only vendor-control reads
   - includes CHIPID register `0x0A`
   - includes snapshot/button state register `0x0C`
3. `TEST BULK 0x84`
   - checks whether the bulk IN endpoint already produces data
4. `TEST PHYSICAL BUTTON`
   - press the physical capture button during the 10-second test
   - checks both the EM28xx snapshot bit and endpoint `0x81`

This build deliberately does not write EM28xx registers yet.

## Build on GitHub

Upload/replace these files in the same repository and push to `main`.

Then:
Actions → Build Android Debug APK → latest run → Artifacts → `SoproCameraTest-v0.2-debug`

## Send back

Copy the log from:
- EM28xx register probe
- Bulk 0x84 test
- Physical button test

Those results determine the v0.3 initialization/streaming path.
