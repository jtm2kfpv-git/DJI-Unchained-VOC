# N3 Local View 0.1

Privacy-minimal Android receiver for the wired live-view output of DJI Goggles N3 with DJI O4 Air Unit Pro or DJI Avata 2.

## Hardware status

This version is hardware-confirmed on the user's Goggles N3 / O4 Pro or Avata 2 / Android 16 LineageOS setup. The preserved debug APK is the exact APK used for that successful test.

## Privacy properties

- No `INTERNET` permission.
- No location, microphone, camera, account, contacts or broad storage permissions.
- No analytics, advertising, telemetry, crash-reporting SDK or updater.
- USB data is decoded locally through Android `MediaCodec`.
- Root is not requested or used.

## Implemented

- Android Open Accessory discovery and user permission flow.
- Exact two captured N3/O4 video-start packets, repeated every five seconds.
- Incremental N3 `logiclink` framing and H.264 extraction on port `0x574a`.
- Annex-B NAL/access-unit assembly across arbitrary USB boundaries.
- Hardware H.264 decoding to a full-screen `SurfaceView`.
- Local bitrate, packet, decoder-drop and resynchronization counters.
- Stream reset on reconnect, SPS/PPS restart handling and decoder low-latency fallback.
- Refusal to send N3 packets to accessories that do not identify as DJI or `logiclink`.

## Build

```text
./gradlew clean testDebugUnitTest lintDebug lintRelease assembleDebug assembleRelease
```

The preserved debug APK is ready to install. The release APK is unsigned by design.

## Protocol basis

The control packets are pinned to `samuelsadok/dji_protocol` commit `50c71b65fdb6825783f724e5cd3c1f09c2aa88ce` and can be checked with `tools/verify_protocol.py`.

## Source snapshot note

The exact hardware-confirmed APK was preserved before v0.2 work. This buildable v0.1 source snapshot was reconstructed afterward from the retained v0.1 implementation text, the v0.2 change boundary, and direct inspection of the preserved APK's DEX code. It is behaviorally equivalent and independently build-tested, but is not claimed to reproduce the archived APK byte-for-byte because APK signing metadata and build timestamps are not reproducible.
