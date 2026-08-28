# Sopro Camera Player v0.5

Target: Sopro/eMPIA `VID EB1A / PID 2821`, bridge CHIPID `0x12` (EM2820 family).

This is the first **operational player build**, not another one-test diagnostic APK.

## What is inside this single APK

- USB permission + open/claim
- Corrected EM28xx I2C addressing (`7-bit address << 1`)
- Automatic board/profile attempts
- SAA7113 initialization profile based on the Linux SAA711x driver
- Generic EM28xx analog bridge initialization
- Native Android NDK ISO engine for endpoint `0x82`
- EM28xx packet parsing:
  - `22 5A` video field-start header
  - `88 88 88 88` continuation header
- automatic common geometry inference
- live preview
- Capture
- Save image to `Pictures/SoproCamera`
- fixed visible buttons: no hidden control panel and no scrolling required for the main controls
- Auto Retry Profiles button if the first initialization does not yield ISO video

## How to use

1. Install APK.
2. Plug the Sopro camera.
3. Tap `CONNECT`.
4. Accept USB permission if Android asks.
5. Tap `START CAMERA`.
6. Wait while the app tries its built-in profiles.
7. If preview appears: use `CAPTURE`, then `SAVE IMAGE`.
8. If no preview appears: tap `AUTO RETRY PROFILES` once.
9. Only if it still fails, press `COPY LOG` and send the log. You do not need another diagnostic app.

## GitHub build

Upload this project over the previous test repository or use a new repository.
GitHub Actions installs Java 17, Android 35, NDK r27 and CMake, builds the native ISO layer and uploads:

`SoproCameraPlayer-v0.5-debug`
