# N3 Local View 0.3

Experimental, privacy-minimal Android receiver for the wired live-view output of DJI Goggles N3 with DJI O4 Air Unit Pro or DJI Avata 2.

## Privacy properties

- No `INTERNET` permission.
- No location, microphone, camera, account, contacts or broad storage permissions.
- No analytics, advertising, telemetry, crash-reporting SDK or updater.
- USB data is decoded locally and displayed through Android `MediaCodec`.
- Root is not requested or used.

## Current status

The original 0.1 implementation has been hardware-confirmed on Goggles N3 with DJI O4 Air Unit Pro / DJI Avata 2 and an Android 16 LineageOS phone. Versions 0.2 and 0.3 preserve that exact USB/protocol path. Version 0.3 adds resilient recovery and explicit connection-state reporting; its APK still needs a short on-device regression test after installation.

Implemented:

- Android Open Accessory discovery and user permission flow.
- Runtime display of the accessory identity strings.
- Exact two captured N3/O4 video-start packets, repeated every five seconds.
- Incremental N3 `logiclink` parser (`55 cc`, 16-bit port, 16-bit payload length, two zero bytes).
- Extraction of H.264 on port `0x574a`.
- Annex-B NAL/access-unit assembly across arbitrary USB boundaries.
- Hardware H.264 decode to a full-screen `SurfaceView`.
- Correct-aspect **FIT**, crop-to-screen **FILL**, and legacy **STRETCH** display modes.
- Tap the video to hide or show the control panel.
- One-second local bitrate and rendered-FPS measurements, plus packet, decoder-drop and resynchronization counters.
- Optional automatic connection when the known DJI accessory is attached or the app opens.
- Automatic recovery after USB read/write or accessory-open failures, using a capped 1/2/5/10-second retry sequence.
- Explicit disconnected, permission, connecting, connected, streaming and retry-wait states.
- Manual disconnect cancels queued retries; **Connect** immediately resets the retry sequence.
- Persistent display-mode and automatic-connection preferences.
- Unit tests for fragmented framing, resynchronization, control-packet integrity and H.264 access-unit assembly.
- Unit tests for all three display geometries and their mode cycle.
- Unit tests for retry progression, cap and reset behavior.
- Stream-state reset on reconnect and safe decoder restart when SPS/PPS changes.
- Hardware-decoder fallback if the Android codec rejects low-latency mode.
- Refusal to send N3 packets to accessories that do not identify as DJI or `logiclink`.

## Bench-test order

1. Remove propellers and provide cooling/airflow where required.
2. Activate and link the aircraft/Air Unit normally; verify video in Goggles N3.
3. Install and open N3 Local View.
4. Connect a known-good USB-C data cable from N3 to the phone.
5. Note the accessory identity shown by the app and press **Connect**. Enable **Auto reconnect** only if desired.
6. Use **Display: FIT** for the correct full-frame aspect ratio. **FILL** crops the edges to occupy the screen; **STRETCH** reproduces the original full-screen behavior.
7. If video packets remain zero, capture the displayed identity and `adb logcat -s N3LocalView` output. The app deliberately omits the accessory serial number from both. Do not repeatedly change USB roles while the aircraft is armed.

The app sends only the two published camera/app subscription packets. It contains no flight-control UI or exploratory DUML commands.

## Build

The project targets Android API 36 and Java 17. With an Android SDK and Gradle wrapper installed:

```text
./gradlew clean testDebugUnitTest lintDebug lintRelease assembleDebug assembleRelease
```

To independently compare the embedded control bytes with the pinned upstream checkout:

```text
python tools/verify_protocol.py /path/to/dji_protocol
```

The ready-to-install debug APK is `output/apk/N3-Local-View-0.3.0-debug.apk`. The release APK is unsigned by design; sign it with a private key you control after testing.

Each preserved release has a standalone source/APK folder under `GitHub-Submission/`.

## Protocol basis

The N3 mobile framing and control packets are pinned to the reverse-engineering documentation and executable reference at commit `50c71b65fdb6825783f724e5cd3c1f09c2aa88ce`:

- https://github.com/samuelsadok/dji_protocol/blob/50c71b65fdb6825783f724e5cd3c1f09c2aa88ce/usb_mobile_protocol.md
- https://github.com/samuelsadok/dji_protocol/blob/50c71b65fdb6825783f724e5cd3c1f09c2aa88ce/scripts/video_out_mobile.py
