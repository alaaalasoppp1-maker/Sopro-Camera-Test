# SoproCameraTest v0.1

Diagnostic Android USB app for the legacy Sopro/eMPIA intraoral camera.

Known target:

- VID: `EB1A`
- PID: `2821`
- Windows interface observed: `MI_00`
- Windows driver: Sopro cameras driver / eMPIA family

## What v0.1 does

- Detects USB devices visible to Android
- Finds `EB1A:2821`
- Requests Android USB permission
- Lists interfaces and endpoints
- Tries to claim each USB interface
- Displays a diagnostic log
- Copies or saves the log

This build intentionally does **not** display live video yet.

## GitHub build

Push the project to a GitHub repository.

Open:

`Actions` → `Build Android Debug APK`

You can either push to `main` or press `Run workflow`.

After the workflow finishes:

`Actions` → latest successful run → `Artifacts` → `SoproCameraTest-v0.1-debug`

Install `app-debug.apk` on the Android phone.

## Test procedure

1. Connect the camera using OTG / powered hub if required.
2. Open Sopro Camera Test.
3. Press `REFRESH`.
4. Confirm it reports `Target EB1A:2821 detected`.
5. Press `CONNECT CAMERA`.
6. Accept Android USB permission.
7. Press `COPY LOG`.
8. Send the complete log back for v0.2 development.

## Important

v0.1 is a USB diagnostic build. The next build will use the real interface/endpoint data reported by the phone to begin the EM28xx video engine.
