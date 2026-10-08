# Android restrictions and host behavior

This audit covers the APIs used by this host on Android 10–16, with compile/target SDK 35. OS enforcement, OEM policy, app protections and LAN reachability are separate concerns. Emulator and protocol tests do not establish OnePlus-to-TV compatibility.

| Restriction | Implementation and remaining boundary |
| --- | --- |
| Android 14+ foreground service type and permission | The network/control service uses `connectedDevice` with its declared permission and `CHANGE_WIFI_MULTICAST_STATE`. Capture adds `mediaProjection` only after approved consent. Both types are declared. No boot receiver or background auto-start is used. |
| Foreground service launch restrictions | The user starts the host from the visible activity. Permission results are processed after the activity resumes. Synchronous foreground promotion failures are displayed and logged. |
| Fresh MediaProjection consent | Every new projection needs a new approved token. Only one virtual display is created per token; reconnect attaches a new encoder surface to that display. Consent cancellation leaves discovery/pairing available. |
| Single-app capture versus display capture | API 34+ requests the default display. Android/OEMs retain control of the consent UI and may override this request. The host cannot manufacture a valid capture token. |
| Protected screens and sensitive windows | The host sets no `FLAG_SECURE` and has no permanent password input. Pairing uses a temporary numeric field excluded from autofill. Android/app/DRM capture protection is respected; there is no production bypass setting. This does not override OnePlus privacy policy. |
| Screen lock or projection revocation | Android may stop projection when the screen locks or the user stops sharing. The stream ends, but network listeners remain online. The user must unlock and approve a fresh session. |
| Notifications | Android 13+ notification permission is requested but denial does not block the foreground service. The service provides an ongoing notification with open/stop actions. |
| Playback audio | `RECORD_AUDIO` and projection are required. Only eligible playback usages/apps in the same user profile are captured. Protected audio or apps disallowing playback capture cannot be forced. Capture initialization failure supplies silent Opus rather than blocking discovery. |
| Keystore TLS operations | The v2 RSA key authorizes `DIGEST_NONE`, required when Conscrypt signs an already calculated TLS digest, and RSA paddings for TLS. Pairing still uses SHA-256 signatures. An old host certificate changes when migrating from the v1 alias; clients must pair again. |
| Android 16 local-network permission | Restricted local-network access is opt-in on Android 16. This target-35 app does not enable the opt-in. Future mandatory versions require another permission/API update. A user-enabled compatibility restriction or OEM network deny can still block sockets. |
| Wi-Fi multicast and Moonlight discovery | Registers `_nvstream._tcp.` on 47989, matching Moonlight, and holds a multicast lock. Registration success/failure is shown. HTTP listeners start independently of projection. Registration on the phone does not prove multicast reaches the TV. |
| VPN, guest Wi-Fi, AP isolation and VLANs | Can block multicast or direct client traffic. Manual IP addition avoids reliance on mDNS but cannot bypass firewall/isolation. LAN addresses refresh in the UI. |
| OEM background/battery policy | Foreground notifications and locks help but cannot override OEM force-stop/network policy. The app links to its settings and describes OnePlus background/network settings. User grant is required where OxygenOS restricts them. |
| Encoder capabilities | H.264 profile and bitrate mode are chosen from the actual encoder capabilities. Baseline/main streams remain decodable by Moonlight. Resolution/FPS/thermal behavior still needs the physical encoder and TV decoder tested. |
| Native ABI and 16 KB pages | Production APK is arm64-v8a. NDK 27 is explicitly built with flexible page-size support. Emulator builds use x86_64 only; their native library is not the arm64 hardware build. |
| APK updates | GitHub Actions retains the debug keystore for subsequent builds. The first build after introducing this cache still requires uninstalling earlier differently signed debug APKs. This is not release signing. |
| Failure reporting | Startup, consent, Keystore, listeners, mDNS and capture stages are retained in a bounded diagnostics log. Cleanup does not overwrite an error with “Host stopped.” Copy diagnostics excludes pairing codes and key material. |

## Evidence

[Actions run 37807644367](https://github.com/billyke2000s/Sunshine-mobile/actions/runs/37807644367), runtime commit `c48a06e9aa1b229b97f9231f5056608acaad93d9`, passed the arm64 APK build, lint/JVM checks, protocol test and both API 35/36 emulator jobs. The Android instrumentation suite runs on API 35 and 36. It exercises the actual activity Start button, system consent rejection/approval, foreground host startup, mDNS registration, HTTP discovery, Android Keystore signature and persistence, mutually authenticated TLS, launch gating before consent, native ENet/Opus, MediaProjection to MediaCodec frame production, cancel and service restart. Tests use a local trusted fixture peer; this does not replace the production pairing flow.

The delivered `SunshineMobile-1.0.2-arm64.apk` has SHA-256 `13911aa2b91b1f14ae59a68c1c073e7e7501a84b3490dc8b1d983c537d3d1dda` and APK signer SHA-256 `9e07f5fc789383610bd624cd331c38eda9a4c764c2cc0f14aafca059bae4040a`. Its APK v2 signature/content digest, ARM64 ABI, native ELF load alignment and uncompressed library offsets were verified for 16 KB pages. The explicit debug keystore was saved by CI under `sunshine-mobile-debug-signing-v2`; cache retention is not a substitute for production release signing.

The separate production protocol suite exercises generation-7 pairing and streaming against Moonlight Android's pinned, unchanged upstream native core, including encrypted negotiation/media, FEC loss recovery, authorization, reconnect and cancellation.

Physical verification remains necessary for OnePlus consent/privacy policy, ARM hardware encoding and audio, multicast between the actual phone/router/TV, Moonlight Android UI pairing/playback, synchronization, performance, thermals and prolonged lifecycle behavior.

## Primary references

- [Foreground service types and prerequisites](https://developer.android.com/develop/background-work/services/fgs/service-types)
- [Background foreground-service launch restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)
- [MediaProjection consent, display reuse and stop callbacks](https://developer.android.com/media/grow/media-projection)
- [Screen-sharing protections](https://developer.android.com/about/versions/15/behavior-changes-all#screen-share-protections)
- [Playback capture requirements](https://developer.android.com/media/platform/av-capture)
- [Keystore digest and padding requirements for TLS](https://developer.android.com/reference/android/security/keystore/KeyGenParameterSpec.Builder)
- [Local-network permission rollout](https://developer.android.com/privacy-and-security/local-network-permission)
- [Native page-size support](https://developer.android.com/guide/practices/page-sizes)
- [Moonlight Android mDNS discovery](https://github.com/moonlight-stream/moonlight-android/tree/b48494cb96bff23d8886c4775cc4f39a1075495d/app/src/main/java/com/limelight/nvstream/mdns)
