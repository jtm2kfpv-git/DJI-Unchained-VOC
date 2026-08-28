# DJI FPV wired live view without DJI Fly

## A privacy-first, implementation-oriented technical report

Prepared 25 August 2026

### Executive answer

It is technically possible to receive the goggles' wired live view without running DJI Fly, but there is not one universal DJI USB video protocol. There are at least two materially different families:

1. DJI FPV Goggles V1/V2 with the original DJI Digital FPV Air Unit or Caddx Vista use a simple vendor-specific USB bulk stream. A host claims interface 3, sends the four ASCII bytes `RMVT`, then reads H.264 from the bulk IN endpoint. This is the mature and relatively easy path.
2. Newer goggles use the Android Open Accessory (AOA) model or a related USB role-switch path. The goggles act as USB host, identify themselves to Android as `com.dji.logiclink`, and do not send video until the client performs a DJI-specific control/keepalive exchange. The video itself is H.264 carried on a DJI framing channel. A field-tested open implementation currently exists for Raspberry Pi 4 + DJI Goggles 2 + DJI O3 Air Unit. Other combinations are less mature.

The safest practical answer therefore depends on the exact printed model name of both goggles and Air Unit. Do not treat "DJI FPV Goggles V2", "DJI Goggles 2", "DJI Goggles Integra", "DJI Goggles 3", and "DJI Goggles N3" as interchangeable.

For a privacy-first build:

- V1/V2 + original Air Unit/Vista: use the small open `voc-poc` receiver on a computer, or build a stripped Android client from the archived `fpv-dvca` source after removing telemetry/crash-reporting code.
- Goggles 2 + O3: use a dedicated Raspberry Pi 4 running the open `o3-usb-decoder`, with VBUS isolated between goggles and Pi.
- Integra, Goggles 3, N3, O4, or V2 + O3: do not assume either open solution works. N3 + O4 has a documented experimental receiver, but its USB role emulation currently requires specialist hardware. For a dependable field monitor today, the fallback is a dedicated, network-disabled Android device; this keeps DJI software off a personal phone but is not a fully DJI-free stack.

This report separates verified facts, implementation observations, and open gaps. DJI documents cable topology, compatibility, activation, and Android AOA behavior, but does not publish the low-level live-view protocol. The byte-level material below therefore comes from inspectable open-source implementations and captures.

## 1. Identify the hardware before doing anything

| Printed goggles name | Typical Air Unit pairing | Open no-DJI-app path | Confidence |
|---|---|---|---|
| DJI FPV Goggles V1/V2 | Original DJI Air Unit or Caddx Vista | `voc-poc` on PC; cleaned `fpv-dvca` on Android | High |
| DJI Goggles 2 | DJI O3 Air Unit | Raspberry Pi 4 + `o3-usb-decoder` | Medium-high for the exact tested setup |
| DJI Goggles Integra | O3 or O4 | No broadly validated open drop-in found | Low |
| DJI Goggles 3 | O3 or O4 | No broadly validated open drop-in found | Low |
| DJI Goggles N3 | O4 | `dji_protocol` demonstrates it, but PC emulation is experimental | Medium for protocol, low for turnkey use |
| DJI FPV Goggles V2 | O3 | DJI Fly works; legacy `RMVT` clients are not established for this pairing | Low for an open replacement |

The original Air Unit has a firmware split. DJI's 8 December 2022 release notes say Air Unit firmware 01.01.00.00 adds Goggles 2 and FPV Remote Controller 2 compatibility, but removes Goggles V1/V2 compatibility. V1/V2 users must downgrade the original Air Unit to 01.00.06.08. O3 and O4 are different product generations with different pairing matrices. O4 does not support FPV Goggles V1/V2.

Before selecting a build, record:

- exact goggles name from the label and its firmware;
- exact Air Unit name: original, O3, O4, O4 Pro, or Caddx Vista;
- whether the current goggles-to-Air-Unit link works normally;
- whether the required output is a clean camera feed or a pixel-for-pixel copy including goggles menus and warnings;
- display target: Android tablet, Windows/Linux/macOS computer, HDMI monitor, or recorder.

## 2. What the cable actually carries

### 2.1 Legacy V1/V2: vendor-specific USB bulk transport

The open `voc-poc` receiver and the independently developed `fpv-dvca` Android client agree on the following behavior:

| Item | Value |
|---|---|
| USB vendor ID | `0x2ca3` |
| USB product ID | `0x001f` |
| Interface | 3 |
| Command endpoint | bulk OUT address `0x03` (endpoint index 0 in the tested interface) |
| Video endpoint | bulk IN address `0x84` (endpoint index 1 in the tested interface) |
| Start command | ASCII `RMVT`, hex `52 4d 56 54` |
| Elementary video | H.264/AVC, accepted by FFmpeg without custom decoding |

After the start command, the host continuously reads the bulk IN endpoint. Mature DigiView implementations resend `RMVT` after a timeout/stall. Captures contain small proprietary prefixes and markers, including `00 01 09 10`, a six-byte counter, and `c8 67 ff`, but the interpretation is explicitly tentative. In practice FFmpeg can find and decode the Annex-B H.264 stream without first stripping a supposed fixed header. A robust custom program should buffer across USB transfer boundaries and locate H.264 start codes instead of assuming that one USB read equals one video frame.

This is not USB Video Class (UVC). Selecting the goggles as a normal webcam will not work. The host needs generic USB access through libusb/WinUSB, WebUSB, or Android USB Host APIs. The interface agreement is independently present in `voc-poc`, DigiView Android, DigiView SBC, and a WebUSB implementation. WTFOS/rooting is not required: `RMVT` is exposed by stock supported goggles. The Air Unit's own USB connector is not the same video-output interface; connect the receiver to the goggles.

### 2.2 Modern goggles: Android Open Accessory plus DJI channels

Android Open Accessory reverses the familiar phone-host arrangement. The external accessory is USB host; Android is the USB device. The accessory asks Android for its AOA version, sends identifying strings, then requests accessory mode. Android re-enumerates with Google's accessory VID/PID `18d1:2d00`, or `18d1:2d01` when ADB is also enabled. Only one Android app can own the accessory connection.

Official AOA control requests are:

| Request | Value | Purpose |
|---|---:|---|
| `AOA_GET_PROTOCOL` | `0x33` | Query supported AOA version |
| `AOA_SEND_IDENT` | `0x34` | Send six identity strings |
| `AOA_START_ACCESSORY` | `0x35` | Re-enumerate Android in accessory mode |

USB captures from DJI Goggles 2 identify the accessory as manufacturer `DJI`, model `com.dji.logiclink`, description `DJI glass`, version `v0.0.0.0`, and URI `www.dji.com`. In the validated Pi sequence, the emulated Android side first presents `18d1:4ee0`, answers the AOA requests, disconnects, and re-enumerates as `18d1:2d01` with bulk OUT `0x01` and bulk IN `0x81`. Starting directly as `2d01` failed because the goggles suspended/disconnected it. The open Raspberry Pi implementation therefore reproduces both stages using Linux USB gadget mode and `raw-gadget`.

The DJI link multiplexes framed channels. Verified channel identifiers are:

| Header bytes | Little-endian port | Direction/use |
|---|---:|---|
| `55 cc 4a 57` | `0x574a` | Goggles-to-client H.264 video |
| `55 cc 30 75` | `0x7530` | Goggles-to-client control/DUML |
| `55 cc 49 57` | `0x5749` | Client-to-goggles control/DUML |

In the Goggles 2/O3 AOA captures, the four-byte channel tag is followed by a little-endian 32-bit payload length. Concatenating payloads from the video channel yields Annex-B H.264. DUML validation in the reference code uses CRC8 seed `0x77` and CRC16 seed `0x3692`. Its deframer preserves partial headers and payloads across USB reads and searches for a new magic value only at a valid frame boundary, because magic-looking bytes may occur inside payload. The independently documented N3/O4 mobile path uses the same `55 cc` and port convention but describes a shorter header with a 16-bit payload length. A receiver must therefore key its parser to the tested transport/version; it must not blindly apply one length layout to all goggles.

The video channel is gated. A passive USB reader does not receive video. The client must replay the required DJI application-control initialization and keepalive messages. For Goggles 2/O3, the public proof-of-concept uses a captured initialization sequence of 218 packets once, then a lighter sustain sequence containing heartbeats and camera subscription traffic. Important camera-related commands include command set 2, command IDs `0xeb` and `0xe8`, plus an application-alive exchange. Video begins about 1.5 seconds after the link is armed in the tested setup. Replaying the entire long sequence continuously made the video choppy; full initialization once plus a small sustain loop is the working design.

The packet array is capture-derived, not a stable DJI API. The exact auditable bytes are in `poc/pi_endpoint/gold_app_seq.py` at the pinned repository revision listed in the references. Copying them into this report would add transcription risk without improving auditability.

### 2.3 N3/O4 and Apple's MFi-style role switch

The `dji_protocol` research repository documents a second modern route using Goggles N3 + O4 Air Unit Pro. With an iPhone, the goggles initially act as USB host and detect Apple VID/PID `05ac:12a8` or `05ac:12ab`. They issue vendor request `0x51`, after which both sides switch USB roles. DJI then presents itself as `2ca3:1002`, interface 1, alternate setting 1, with string `com.dji.logiclink`. The MFi traffic on interface 0 was not required for the DJI-specific data interface in the experiment.

The documented start exchange consists of two encapsulated DUML packets. The repository's `scripts/video_out_mobile.py` is the exact executable reference. Replaying both roughly every five seconds keeps video running, while the stream stops after about eleven seconds with no refresh. The more faithful pattern sends the first command each second with a counter and handles the inverse `0x88` acknowledgement flow for the second.

To coerce this mobile path from a PC, the proof-of-concept uses a Great Scott Gadgets Cynthion with Facedancer to emulate an iPhone and acknowledge the role-switch request. The author warns that the resulting bus state is not fully understood and may briefly involve an invalid multi-device arrangement. This is research-grade, not a recommended field build.

## 3. Build A: V1/V2 plus original Air Unit or Vista

### 3.1 Fastest computer proof-of-concept

The smallest inspectable receiver is `fpv-wtf/voc-poc`, pinned here to commit `ce58c2a2feaf29ff283f87eb2f5e87ff6c070a8c`.

Prerequisites:

- a data-capable USB/OTG cable;
- Node.js and FFmpeg/ffplay;
- permission to claim the goggles' vendor interface;
- on Windows, WinUSB bound to the goggles bulk-transfer interface using Zadig.

Reference commands:

```text
git clone https://github.com/fpv-wtf/voc-poc.git
cd voc-poc
git checkout ce58c2a2feaf29ff283f87eb2f5e87ff6c070a8c
npm install
node index.js -o | ffplay -i - -analyzeduration 1 -probesize 32 -sync ext
```

Security-hardening steps:

1. Inspect `index.js` and `package-lock.json` before installing. The runtime code is small and makes no network request, but `npm install` expands the dependency trust boundary.
2. Pin the commit and preserve a hash or local copy of the source used in the field.
3. Install dependencies while online, then disconnect networking for actual use.
4. Run without administrative privileges after the one-time Windows driver association.
5. If building a permanent tool, replace the Node dependency chain with a small libusb client implementing the five operations below.

Minimal implementation algorithm:

```text
enumerate VID 0x2ca3, PID 0x001f
open device
detach kernel driver if required and authorized
claim interface 3
bulk-write [0x52, 0x4d, 0x56, 0x54] to OUT endpoint
loop: bulk-read IN endpoint and append bytes to an H.264 parser/decoder
on exit: release interface and close device
```

Do not hard-code endpoint addresses solely from "endpoint index 0/1" in a new implementation. Read the active descriptors, require one bulk OUT and one bulk IN endpoint on interface 3, log their actual addresses, and fail safely if the layout differs.

### 3.2 Android without DJI Fly

`d4rken-org/fpv-dvca` is an archived GPLv3 Android implementation pinned to commit `646bb1ff86419fb89d7b8a336826e2f18d992c69`. Its source manifest requests USB host support, wake lock, and foreground service; its USB filter selects decimal VID 11427 and PID 31, corresponding to `2ca3:001f`. It supports V1/V2, DVR and VR-oriented presentation.

Do not install an old binary blindly. The build includes Bugsnag crash-reporting code and dependency 5.9.2, even though reporting is disabled by default in application settings. An Android library can merge permissions into the final manifest, so inspecting only the app's source manifest is insufficient.

A privacy-hardened fork should:

- remove the Bugsnag Gradle dependency and all reporter initialization/code;
- omit `android.permission.INTERNET` and `ACCESS_NETWORK_STATE` from the merged APK;
- retain only USB host, foreground-service and wake-lock capabilities that the design truly needs;
- remove analytics, auto-update, remote logging and unnecessary storage access;
- target a currently supported Android SDK and update dependencies carefully;
- build reproducibly from a pinned revision;
- verify the final APK with `apkanalyzer manifest permissions` or `aapt2 dump permissions`;
- test with Wi-Fi/cellular disabled and, ideally, capture the device's network traffic to confirm no attempted egress.

Android must support USB Host mode and the user must grant access to the attached USB device. Some phones need the OTG adapter in a particular orientation/order. Charge-only cables are a common failure.

### 3.3 Smallest browser-based legacy option

The MIT-licensed `fpvout/live.fpvout.com` repository contains the same complete legacy USB transaction in TypeScript using WebUSB: match `2ca3:001f`, claim interface 3, transfer `RMVT` to endpoint number 3, read endpoint number 4, and pass H.264 to JMuxer. A privacy-first use would self-build and self-host the pinned source on `https://` or `localhost`, not rely on a public deployment. WebUSB requires a compatible Chromium browser, the dependencies are old, and present-day Android/browser compatibility needs testing. This is nevertheless a compact, auditable option when it works.

## 4. Build B: Goggles 2 plus O3 on Raspberry Pi 4

The strongest fully open modern implementation found is `dr00min/o3-usb-decoder`, pinned to commit `6f00d3a4d737d0acaf594a70e6a57822de0fda08`. Its maintainer reports field validation on:

- Raspberry Pi 4B, 2 GB;
- Raspberry Pi OS 64-bit Bookworm;
- the Pi 4 USB-C controller in `dwc2` gadget mode;
- DJI Goggles 2;
- DJI O3 Air Unit;
- HDMI display through GStreamer and atomic KMS.

It is a new, small project, and independent reproduction was not found. Treat the exact tested matrix as supported and everything else as an experiment.

### 4.1 Electrical topology - critical

For Goggles 2, the goggles act as USB host and source VBUS. The Pi 4 also normally expects power through its USB-C connector. Do not connect goggles to that port while also feeding ordinary USB-C power into the same power path.

Use this topology:

```text
Air Unit --radio--> Goggles 2 --USB-C data, VBUS blocked--> Pi 4 USB-C gadget
                                                          |
                                                  GPIO 5 V or PoE power
                                                          |
                                                       HDMI display
```

Requirements:

- a USB power blocker that blocks VBUS but passes D+/D- data, or a correctly made cable with only the VBUS conductor interrupted;
- Pi power through a properly rated GPIO 5 V supply or PoE HAT;
- correct polarity and common ground;
- no "USB data blocker", because that blocks the signal needed here.

Two active 5 V supplies fighting each other can damage hardware or create an unstable link. Verify the cable with a meter before connecting expensive goggles. Other goggles may have a different USB role/power behavior; do not generalize the red-wire-cut rule without checking the project's hardware notes.

### 4.2 Software procedure

Reference flow from the pinned project:

```text
flash Raspberry Pi OS Bookworm 64-bit
git clone https://github.com/dr00min/o3-usb-decoder.git
cd o3-usb-decoder
git checkout 6f00d3a4d737d0acaf594a70e6a57822de0fda08
review scripts/pi_setup.sh and every referenced repository/revision
sudo ./scripts/pi_setup.sh
reboot
verify /sys/class/udc/fe980000.usb and /dev/raw-gadget
review service definitions
sudo ./scripts/install_hdmi_service.sh
```

The setup script installs packages, changes boot configuration, enables `dwc2`, builds and installs the `raw-gadget` kernel module and related USB proxy components, and installs system services. These are root-level changes. Use a dedicated Pi image, audit the scripts, pin transitive repositories, and keep a known-good SD-card image for rollback.

Operational order:

1. Remove propellers and provide airflow over the Air Unit during bench tests.
2. Power the Air Unit and confirm its live image appears in the goggles.
3. Boot the Pi and wait for the HDMI service.
4. Connect the verified VBUS-isolated data cable.
5. Confirm AOA re-enumeration and the `55 cc 4a 57` video channel in logs.
6. Only then move to a field installation.

The project warns that stopping or restarting the service while goggles remain attached can wedge or kernel-oops the raw-gadget path until reboot. Unplug the goggles before service maintenance. Its optional watchdog may reboot the Pi after a detected failure; that behavior should be understood before use on shared power or a recording system.

When Goggles 2 are attached to an ordinary PC as a peripheral, some firmware exposes `2ca3:0020` with RNDIS, storage, bulk and ACM interfaces. Testing found a `192.168.60.0/24` network and control-like services, but data rates were far below the live video rate. This is a separate USB personality, not the phone's live-feed path. A PCAP tool on the phone sees no H.264 feed because the AOA route has no IP layer. A normal host-only laptop therefore cannot replace the phone in this configuration; the replacement must support USB peripheral/gadget mode or use an external role-emulation device.

Observed video is H.264 High profile. Captures are commonly 1920x1080 or 1440x1080 around 50 fps, with reports up to 60 fps, at roughly 5-8 Mbit/s. These values can vary with settings and link conditions and are not a contract. The stream is softer than the goggles DVR, which is reported around 18-26 Mbit/s. Budget decoder and display latency empirically rather than assuming phone-like performance.

### 4.3 Porting the modern implementation to another device

A replacement client needs four layers:

1. USB role layer: present an Android AOA-compatible peripheral while goggles remain host, or perform the correct mobile role-switch for that goggles generation.
2. Accessory transport: service control endpoint requests and provide stable bulk IN/OUT endpoints with buffering up to the AOA logical transfer limits.
3. DJI session layer: transmit the exact validated initialization once, then current keepalive/subscription traffic; validate DUML checksums and counters where known.
4. Media layer: parse `0x574a` frames, concatenate payloads into Annex-B H.264, feed a low-latency decoder, and recover at the next SPS/PPS/IDR after loss.

Recommended diagnostics are USB enumeration trace, channel/length counters, DUML command-set/ID logging, H.264 NAL-type counts, time to first IDR, input-to-display latency, dropped-byte count and reconnect behavior. Never send exploratory flight-control commands to a powered aircraft. Protocol work should be performed propellers-off on a dedicated bench setup.

## 5. What "what the goggles see" means

The USB output is not guaranteed to be a pixel-for-pixel display mirror.

- Legacy V1/V2 tools generally expose the camera H.264 feed. Community documentation reports that the original goggles OSD is not included and that goggles DVR may be disabled while USB video-out is active.
- Newer goggles can include or exclude parts of the display overlay depending on model and the "Camera View Recording" setting. DJI documents this setting for screen recording; third-party monitor vendors report that it also affects external output. Treat this as model-dependent until tested.
- Betaflight Canvas Mode OSD, DJI system status, menus, warnings and playback screens are different layers. A decoder that receives the video channel may not receive every layer rendered on the microdisplays.

Define acceptance explicitly: clean camera view, pilot OSD, DJI telemetry, or literal goggles UI. Test by placing a timestamped visual marker in view and opening a goggles menu while recording the external output.

## 6. Privacy and threat model

The privacy concern is reasonable, but it should be stated precisely. Installing proprietary, network-capable software expands the trust boundary; it does not automatically grant unrestricted background access to every item on a phone. Android and iOS sandboxing, runtime permissions, OS version, account configuration, and network state materially limit access.

DJI's policy says its products may process account/contact information, device and hardware identifiers, IP/device technical data, approximate location for activation/maps/safety functions, and user-uploaded media/logs. DJI states that flight logs, images and videos are optional and not uploaded by default in the documented consumer controls. It also describes global affiliates/service providers and international transfers, with DJI GmbH as EEA controller and standard contractual clauses for transfers.

DJI's Local Data Mode is a useful fallback: after activation it severs the app's internet connection, and DJI recommends disabling Wi-Fi/cellular for a fully offline setup. This reduces network exposure but still leaves DJI code on the device.

Privacy architecture ranked from strongest separation to weakest:

1. Dedicated open receiver with no radio/network interface enabled in operation, pinned source, verified build and least privilege.
2. Dedicated Android Open Source Project device with a self-built minimal USB receiver and no Google/DJI account.
3. Dedicated consumer Android phone/tablet, factory reset, no SIM, no personal accounts/files, DJI Fly only, all nonessential permissions denied, Local Data Mode enabled and Wi-Fi/cellular disabled after activation.
4. DJI Fly on a personal daily-use device with network available.

The third option is a containment fallback, not the requested fully app-free solution. It may nevertheless be the lowest-risk dependable choice for unsupported goggles until an open implementation is independently validated.

For an open receiver, also consider supply-chain and privilege risk. A tiny source repository is inspectable, but root shell scripts, kernel modules and package managers can create a larger attack surface than a sandboxed app. Audit the actual build, pin exact revisions, isolate the receiver from personal networks, and keep it single-purpose.

## 7. Validation checklist

### Hardware and safety

- [ ] Exact goggles, Air Unit and firmware recorded.
- [ ] Air Unit link already works in goggles.
- [ ] Propellers removed for bench testing.
- [ ] Air Unit has forced airflow; do not let it overheat on the bench.
- [ ] Cable is data-capable.
- [ ] For Pi 4 + Goggles 2, VBUS is physically isolated and Pi power is independent.
- [ ] Supply voltage, polarity and ground verified.

### USB and protocol

- [ ] Expected VID/PID or AOA identity observed.
- [ ] Correct interface/endpoints claimed.
- [ ] Legacy path sends only `52 4d 56 54` before reading.
- [ ] Modern path logs channel tag and sane payload lengths.
- [ ] H.264 parser sees SPS, PPS, IDR and continuing slice NAL units.
- [ ] Reconnect after cable removal works without power cycling, or limitation documented.
- [ ] No unverified DUML commands are sent to flight-control components.

### Media behavior

- [ ] Resolution, frame rate and bitrate measured, not assumed.
- [ ] Glass-to-glass latency measured with a high-speed camera or timestamp test.
- [ ] OSD/menu inclusion tested.
- [ ] Link loss and recovery tested.
- [ ] Recording does not fill storage silently.

### Privacy and software integrity

- [ ] Source pinned to a full commit hash.
- [ ] Dependencies and root scripts reviewed.
- [ ] Final Android APK permissions inspected, not merely source manifest.
- [ ] Receiver has no unnecessary account, files, SIM, Wi-Fi or cellular access.
- [ ] Network capture shows no unexpected egress during a representative session.
- [ ] A known-good image/build and rollback procedure exist.

## 8. Limitations and evidence quality

High-confidence claims come from official Android/DJI documentation or agreement between runnable source implementations. Medium-confidence claims come from a single inspectable implementation with a stated field test. Low-confidence claims are compatibility extrapolations and are labeled as such.

Important unresolved gaps:

- DJI publishes no supported low-level goggles USB live-view SDK.
- The exact session messages may change with firmware.
- `o3-usb-decoder` has a narrow tested matrix and limited independent validation.
- No broadly validated open turnkey solution was found for Integra, Goggles 3, N3, O4, or V2 + O3.
- The modern output's OSD composition varies and is incompletely documented.
- USB output latency and adaptive video parameters need measurement on the user's own link.

Research covered current DJI manuals/release notes/support/privacy material, Android's authoritative AOA specification, four open-source implementations/protocol repositories, and specialist/commercial corroboration. Additional forum searching had diminishing value because it did not replace missing hardware tests. Source/code status was checked on 25 August 2026; current app versions and repository heads can change.

## 9. Sources and audit trail

1. Android Developers, "USB accessory overview" and AOA application model: https://developer.android.com/develop/connectivity/usb/accessory
2. Android Open Source Project, "Android Open Accessory 2.0": https://source.android.com/docs/core/interaction/accessories/aoa
3. DJI Developer, "DJI Mobile SDK FAQ" (AOA and single-app ownership): https://developer.dji.com/cn/mobile-sdk/documentation/faq/index.html
4. DJI Support, "Introduction to DJI Virtual Flight" (AOA-capable app selection and cable troubleshooting): https://repair.dji.com/help/content?customId=01700008917&documentType=&lang=en&paperDocType=ARTICLE&re=US&spaceId=17
5. DJI, "DJI Digital FPV System Release Notes", 8 December 2022: https://dl.djicdn.com/downloads/DJI_Digital_FPV_System/20221208/DJI_Digital_FPV_System_Release_Notes_EN.pdf
6. DJI, "DJI O3 Air Unit User Manual v1.0": https://dl.djicdn.com/downloads/DJI_O3_Air_Unit/20230329/DJI_O3_Air_Unit_User_Manual_v1.0_EN.pdf
7. DJI, "DJI O3 Air Unit Release Notes", 25 July 2024: https://dl.djicdn.com/downloads/DJI_O3_Air_Unit/Release_Notes/DJI_O3_Air_Unit_Release_Notes_U3_EN.pdf
8. DJI, O3 current product specifications: https://www.dji.com/o3-air-unit/video
9. DJI, O4 Air Unit Series specifications and pairing list: https://www.dji.com/o4-air-unit/specs
10. DJI, O4 Air Unit Series FAQ: https://www.dji.com/o4-air-unit/faq
11. DJI, Goggles 2 support/cabling: https://www.dji.com/support/product/goggles-2
12. DJI, Goggles Integra support/cabling and O3 live sharing: https://www.dji.com/support/product/goggles-integra
13. DJI, Goggles 3 FAQ: https://www.dji.com/goggles-3/faq
14. DJI, Goggles N3 support and wired sharing: https://www.dji.com/support/product/goggles-n3
15. DJI Support, DJI Fly 1.9.9 goggles streaming article (Chinese, V2 example): https://repair.dji.com/help/content?customId=zh-cn03400004625&lang=zh-CN&re=CN&spaceId=34
16. DJI, O3 activation manual: https://dl.djicdn.com/downloads/DJI_O3_Air_Unit/20221121/DJI_O3_Air_Unit_User_Manual_v1.0_EN.pdf
17. DJI, O4 activation manual: https://dl.djicdn.com/downloads/DJI_O4_Air_Unit_Series/UM/DJI_O4_Air_Unit_Series_User_Manual_v1.0_en.pdf
18. DJI, "Consumer Drone Privacy Controls": https://www.dji.com/trust-center/resource/consumer-privacy-controls
19. DJI, "Privacy Policy", updated 1 March 2025: https://www.dji.com/policy
20. fpv-wtf, `voc-poc`, MIT license, commit `ce58c2a2feaf29ff283f87eb2f5e87ff6c070a8c`: https://github.com/fpv-wtf/voc-poc/tree/ce58c2a2feaf29ff283f87eb2f5e87ff6c070a8c
21. d4rken-org, `fpv-dvca`, GPLv3, archived, commit `646bb1ff86419fb89d7b8a336826e2f18d992c69`: https://github.com/d4rken-org/fpv-dvca/tree/646bb1ff86419fb89d7b8a336826e2f18d992c69
22. dr00min, `o3-usb-decoder`, MIT license, commit `6f00d3a4d737d0acaf594a70e6a57822de0fda08`: https://github.com/dr00min/o3-usb-decoder/tree/6f00d3a4d737d0acaf594a70e6a57822de0fda08
23. dr00min, exact captured Goggles 2/O3 app sequence: https://github.com/dr00min/o3-usb-decoder/blob/6f00d3a4d737d0acaf594a70e6a57822de0fda08/poc/pi_endpoint/gold_app_seq.py
24. dr00min, protocol notes: https://github.com/dr00min/o3-usb-decoder/blob/6f00d3a4d737d0acaf594a70e6a57822de0fda08/docs/protocol_notes.md
25. dr00min, supported-hardware notes: https://github.com/dr00min/o3-usb-decoder/blob/6f00d3a4d737d0acaf594a70e6a57822de0fda08/docs/SUPPORTED_HARDWARE.md
26. samuelsadok, `dji_protocol`, commit `50c71b65fdb6825783f724e5cd3c1f09c2aa88ce`: https://github.com/samuelsadok/dji_protocol/tree/50c71b65fdb6825783f724e5cd3c1f09c2aa88ce
27. samuelsadok, documented mobile USB protocol: https://github.com/samuelsadok/dji_protocol/blob/50c71b65fdb6825783f724e5cd3c1f09c2aa88ce/usb_mobile_protocol.md
28. samuelsadok, executable N3/O4 receiver reference: https://github.com/samuelsadok/dji_protocol/blob/50c71b65fdb6825783f724e5cd3c1f09c2aa88ce/scripts/video_out_mobile.py
29. DigiView Android, MIT-licensed V1/V2 client: https://github.com/fpvout/DigiView-Android
30. DigiView SBC, experimental single-board-computer client: https://github.com/fpvout/DigiView-SBC
31. fpvout, WebUSB legacy goggles client: https://github.com/fpvout/live.fpvout.com
32. fpvout, exact WebUSB goggles transaction: https://github.com/fpvout/live.fpvout.com/blob/master/src/Goggles.ts
33. Oscar Liang, specialist V1/V2 video-out setup guide: https://oscarliang.com/dji-fpv-goggles-video-out/
34. Cosmostreamer, commercial DIY wiring notes used only as hardware corroboration: https://cosmostreamer.com/products/djigoggles2/diy/

### Source notes

- Sources 1-19 are first-party or platform-authoritative for public behavior, compatibility, activation, cabling and privacy claims. They do not disclose the byte protocol.
- Sources 20-32 are inspectable code/capture evidence. Revisions are pinned where the protocol claim depends on exact content.
- Sources 33-34 are secondary corroboration and are not the basis for claiming open-source compatibility.
- Repository maintenance status is not a security audit. "Open source" means inspectable, not automatically safe.
