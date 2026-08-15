# Halalify

An Android prototype for on-device content blur. Users select one visual model class—male or female—and the local vision engine blurs matching detections over the shared app or device screen.

## Current functionality

- English settings screen for blur target and content type.
- Settings persist locally across app launches.
- A C++17 vision core performs RGB letterboxing, LiteRT inference, YOLO output decoding, class-agnostic NMS, and the selected-target policy.
- Full-display Android MediaProjection frames are passed through JNI to the packaged v3 model. Matching detections are blurred both in the protected preview and through a touch-through system overlay.
- Adult-site protection includes a DNS-only local `VpnService`. It routes DNS queries to the local TUN endpoint, asks the shared native site-policy engine about each domain, and forwards other queries to a family-filtering DNS resolver.
- The optional Android playback-audio monitor now runs the packaged YAMNet music detector and streaming DTLN speech separator on-device. After two consecutive music-positive frames it mutes the device media stream, requests a transient audio focus so a cooperative media player pauses, and shows the action in the app and foreground notification.
- The model stays on device. Captured preview frames are not persisted or uploaded.

## Current limitation

Device-level blur requires the explicit Android display-over-other-apps permission. Application overlays remain below critical system windows such as the status bar and keyboard, and Android protected surfaces may also be absent from MediaProjection captures. Coordinate, rotation, and OEM behavior still require broader device testing.

Playback capture is limited to apps and players that allow it. Android gives a normal app a copy of eligible playback; it does not guarantee that Halalify can close another app or replace its device output with the processed speech stem. The pause request is best-effort and player-dependent.

The checked-in vision model is still the gender-only v3 artifact. Adding a second label in Kotlin or C++ cannot make it detect NSFW content. To meet the single-model requirement, provide a retrained v4 detector whose classes encode both properties (for example `female_safe`, `male_safe`, `female_nsfw`, `male_nsfw`) and update the model manifest and native output contract together. Until that artifact is supplied, the app must not claim that its visual model detects NSFW content.

The DNS VPN is a filtering layer rather than a full traffic-forwarding VPN. It does not inspect HTTPS page contents and cannot reliably stop DNS-over-HTTPS, DNS-over-TLS, direct-IP access, or an app that bypasses the system resolver. Device-managed lockdown or a full VPN forwarder is required for stronger enforcement.

## Shared site-policy engine

The domain rules are data, not Kotlin constants. The packaged default is [`Model/site_blocklist.txt`](Model/site_blocklist.txt). At runtime Android first checks the app-private file `files/site_blocklist.txt`; that allows a future updater to replace the list without changing the Kotlin or native code. The parser accepts plain domains, hosts-file lines, and the portable `||domain^` form.

The matching implementation is in `native/core/site_filter.*` with a small C API in `native/include/halalify_site_filter.h`. Android's `AdultSiteVpnService` is only a transport adapter. An iOS implementation can compile the same core with Objective-C++ and call it from a `NetworkExtension` packet/DNS provider.

## Model-label contract

The extracted web implementation currently interprets model label `a` as female and `b` as male, while `c` is not blurred. Validate that mapping with known images before relying on it in a production inference pipeline.

## Image AI engine plan

The verified deployed model, its machine-readable contract, provenance notes, and the cross-platform C++ integration plan are in [`Model`](Model/README.md).

## Audio AI engine plan

The playback-capture architecture, exact TFLite waveform contract, integration point, and Android platform limits are documented.

The audio-model preparation scripts, licensing notes, local Python environment instructions, and fine-tuning/export commands are documented. Downloaded datasets, virtual environments, and intermediate training artifacts are intentionally excluded from the repository.

## Verification

Run the following from the repository root:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug
```
