# Sopro Camera Test v0.4 — One Shot Diagnostics

This build consolidates all remaining **safe pre-streaming diagnostics** into one APK.

## UI fix
The left control panel is now a real vertical `ScrollView` with a permanently visible scrollbar.
The most important button, `RUN ALL TESTS`, is fixed at the top and never hidden.

## One-button workflow

First time only:
1. Connect the Sopro camera.
2. Press `CONNECT / RECONNECT`.
3. Accept Android USB permission.

Then press:

`RUN ALL TESTS`

The app automatically performs:
- raw USB descriptor dump
- full EM28xx register dump `0x00..0x3F`
- common I2C probe
- complete I2C scan `0x08..0x77`
- all video alternate-setting selection tests
- Bulk IN `0x84` data test
- 10-second physical capture-button test

During the final 10-second test, press the physical camera capture button several times.

When the progress reaches 100%, press `COPY LOG` and send the complete log.

## Important scope

This version intentionally does not write unknown board/video initialization values.
The resulting single log is intended to give enough board-level information to build the next stage: the actual EM28xx initialization and video-stream engine.
