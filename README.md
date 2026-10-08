# Sunshine Mobile — Android Moonlight host

**Status: capture prototype, not yet a working Moonlight host.** The app currently requests Android MediaProjection permission, creates a hardware H.264 encoder and discards the encoded buffers. The GitHub Actions pipeline produces a debug APK.

## Target

Use an Android phone (initial target: OnePlus 13R) as the host for an unmodified Moonlight client on an Android TV box.

## Implementation milestones

1. **Capture pipeline:** detect actual screen dimensions, handle rotation and projection stop, configure encoder dynamically, and deliver timestamped H.264 access units (including SPS/PPS and keyframes) to a transport interface instead of discarding them.
2. **GameStream control plane:** implement compatible HTTP/HTTPS endpoints (serverinfo, applist, launch, resume, cancel, pair), persistent host identity and certificates, and the PIN-based pairing exchange. Follow the upstream Sunshine/Moonlight protocol rather than inventing a lookalike.
3. **Discovery:** advertise the host via mDNS and support manual IP entry; verify unmodified Moonlight recognizes the host and completes pairing.
4. **Session transport:** implement RTSP negotiation, video RTP packetization and pacing, feedback/control and session lifecycle. Test with Moonlight before marking streaming functional.
5. **Audio:** Android AudioPlaybackCapture with explicit app capture restrictions, Opus encoding and synchronized transport. DRM-protected and non-capturable apps remain unsupported.
6. **Usability:** reconnection, orientation changes, quality profiles, error reporting, battery/thermal handling, and long-running foreground service.
7. **Optional link handoff:** investigate separately. Moonlight itself is not a general-purpose URL receiver.

## Security

Keep all services bound to local network interfaces by default. Do not accept arbitrary unauthenticated launch or control requests. Pairing must authenticate clients and persist trust securely. Never expose a debug video transport to the public internet.

## Build

GitHub Actions -> **Build Sunshine Mobile APK** -> download the debug APK artifact.

## Validation gates

- Gradle build passes.
- App runs and starts/stops projection on a physical Android device.
- Moonlight discovers the phone.
- PIN pairing succeeds and survives restart.
- Moonlight receives decodable video at 1080p60 with measured latency.
- Audio works for capturable apps and stops cleanly.

**Do not advertise Moonlight compatibility until discovery, pairing and video tests pass on real devices.**
