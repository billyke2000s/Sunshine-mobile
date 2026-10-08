# Sunshine Mobile

Android screen-sharing GameStream host for a OnePlus 13R and an unmodified Moonlight Android/Android TV client. The production protocol implementation is tested against the client-core revision pinned by upstream Moonlight Android. Actual OnePlus capture, Android Keystore TLS integration, TV decoding, Wi-Fi discovery and performance still require physical-device verification.

## Use

1. Install the arm64 APK from the latest successful **Build Sunshine Mobile APK** Actions run.
2. Open Sunshine Mobile, tap **Start host**, allow playback-audio capture, and approve Android screen-sharing consent. Choose the whole screen for mirroring other apps.
3. In Moonlight on the same LAN, select **Sunshine Mobile** (or add the phone’s IPv4 address manually), and start pairing.
4. Enter the four-digit PIN displayed by Moonlight into Sunshine Mobile and tap **Approve pairing**.
5. Launch **Phone screen** in Moonlight. Use H.264, SDR, stereo, and a resolution at or below 1920×1080, at up to 60 FPS. The host uses the actual RTSP-negotiated dimensions, rate and bitrate.
6. Disconnect Moonlight to release the encoder and audio capture. The host remains available for a fresh connection without requiring another projection token. Stop the host from the app or notification to release projection and all listeners.

This is screen mirroring. Remote phone touch/gamepad injection is not advertised. Android restricts capture of protected screens and audio from apps that disallow playback capture. Screen-sharing permission must be granted again after the service stops or the process dies; Android 14+ projection tokens cannot be silently reused.

## Implementation

- Persistent RSA host certificate and private key in Android Keystore; persistent host UUID and exact paired client certificate identities in app-private storage. Backup is disabled.
- Generation-7 PIN exchange: SHA-256 salt/PIN derivation, AES challenge exchange, certificate signature binding, signed secrets, strict phases, bounded lifetime and local PIN approval. Public requests cannot launch streams or revoke persisted clients.
- mDNS `_nvstream._tcp` advertisement; HTTP discovery/pairing on 47989; certificate-authenticated HTTPS on 47984 with `serverinfo`, `applist`, `appasset`, `pair`, `launch`, `resume`, and `cancel`.
- Authenticated encrypted RTSP on 48010, using Sunshine `rtspenc://` framing, per-direction GCM IV domains and replay rejection. OPTIONS, DESCRIBE, SETUP, ANNOUNCE, PLAY and TEARDOWN are integrated with the session lifecycle.
- Native upstream ENet control on 47999 with session-specific connection data; GCM control authentication, replay protection, IDR feedback and disconnect cleanup.
- Video on 47998: full GameStream NV video headers, short frame headers, multi-block Reed–Solomon parity, sequence/timestamp handling, UDP ping destination negotiation, pacing and optional authenticated video encryption. Generic RFC6184 RTP was removed.
- Audio on 48000: Android AudioPlaybackCapture, native low-delay CBR stereo Opus, millisecond GameStream timestamps, the GameStream 4+2 audio parity matrix, and optional AES-CBC media encryption.
- One MediaProjection virtual display per consent token, reused/resized across sessions. Hardware H.264 with no B frames, SPS/PPS delivery, AVC normalization, repeated static frames, foreground service, CPU/Wi-Fi locks, session timeout, single-client ownership, reconnect and resource cleanup.

All session control requires a paired certificate or the authenticated session key. No keys, PINs, or complete request URLs are logged. Only H.264 SDR/stereo capability is advertised; HEVC, AV1, HDR and surround sound are not implemented.

## Validation

The workflow compiles the arm64 APK, runs Android lint and JVM tests, and independently runs the **same production Java protocol classes and JNI transport** on Linux with synthetic capture against unmodified upstream Moonlight code.

The compatibility job checks wrong PIN rejection, out-of-order pairing, unauthenticated launch rejection, missing TLS certificate rejection, complete pairing, certificate persistence across a host-process restart, authenticated launch, encrypted RTSP/ENet negotiation, encrypted and plaintext media, H.264 depacketization and FFmpeg decoding, Opus decoding, disconnect/reconnect, and a third streaming session with 2% loopback packet loss. Moonlight’s Debug build additionally enables its built-in video FEC synthetic-drop validation. Audio FEC recovery is required in the packet-loss run. The probe checks actual decoded frame/sample counts and invalid/FEC counters.

Run locally with a JDK 17, CMake, FFmpeg/libx264, OpenSSL, libopus-dev, and Python cryptography:

```sh
git clone --recursive https://github.com/billyke2000s/Sunshine-mobile.git
cd Sunshine-mobile
tools/protocol-test/run.sh
```

For the optional loss run on a disposable Linux test host with administrative network permissions: `NETEM=1 tools/protocol-test/run.sh`. It temporarily applies a loopback netem qdisc and removes it on exit. The test-only host uses a fixed PIN and synthetic capture; none of that behavior is included in the Android app.

## Upstream references

- Sunshine: `d6453a3ab2fbe7e1649d77105a42bd32182461c7` — `nvhttp.cpp`, `rtsp.cpp`, `stream.cpp`.
- Moonlight Android: `b48494cb96bff23d8886c4775cc4f39a1075495d` — `PairingManager.java`, `NvHTTP.java`.
- Its Moonlight common core: `874ac9548f1bd6f095ef2b435c42cdde460e7821` — RTSP, SDP, ENet control, video depacketizer/queue, audio queue. This exact core is pinned in the compatibility script.
- Native ENet, Opus and nanors source revisions are pinned Git submodules; upstream license files are retained. Sunshine Mobile is GPL-3.0.

## Remaining physical verification

- OnePlus 13R installation, MediaProjection consent, hardware-backed Keystore TLS signing, H.264 encoder settings, correct orientation/aspect ratio and static-screen capture.
- TV-box Moonlight discovery/pairing and playback using its hardware decoder; real application audio capture permissions and audio/video alignment.
- Sustained 1080p60 latency, Wi-Fi packet loss, thermal/battery behavior, phone lock/projection revocation, network switching and repeated start/stop cycles.

Successful CI establishes software protocol interoperability with Moonlight’s core. It does not establish measured latency, quality or end-to-end behavior on the actual phone/TV hardware.
