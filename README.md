# Halalify

An Android prototype for on-device content blur. Users select one visual model class—male or female—and the local vision engine blurs matching detections over the shared app or device screen.

## Current functionality

- English settings screen for blur target and content type.
- Settings persist locally across app launches.
- A C++17 vision core performs RGB letterboxing, LiteRT inference, YOLO output decoding, class-agnostic NMS, and the selected-target policy.
- Full-display Android MediaProjection frames are passed through JNI to the packaged `halalify_v2` model. Matching detections are blurred both in the protected preview and through a touch-through system overlay.
- Selected target regions are optionally scored locally with the bundled `nsfw2.tflite` Open-NSFW Android classifier. The classifier can annotate a selected region as NSFW, but it cannot override the male/female target or create a full-screen blur from a classifier-only result.
- Adult-site protection includes a DNS-only local `VpnService`. It routes DNS queries to the local TUN endpoint, asks the shared native site-policy engine about each domain, and forwards other queries to a family-filtering DNS resolver.
- The Android playback-audio monitor runs the packaged YAMNet music detector and streaming DTLN speech separator on-device. The Music isolation source action also decodes a selected local audio/video file or a direct HTTP(S) MP4/M4A URL, writes the DTLN speech stem as AAC, and saves a new file under Movies/Music > Halalify. Video samples are retained while only the audio track is replaced.
- The model stays on device. Captured preview frames are not persisted or uploaded.

## Current limitation

Device-level blur requires the explicit Android display-over-other-apps permission. Application overlays remain below critical system windows such as the status bar and keyboard, and Android protected surfaces may also be absent from MediaProjection captures. Coordinate, rotation, and OEM behavior still require broader device testing.

Playback capture is limited to apps and players that allow it. Android gives a normal app a copy of eligible playback; it does not guarantee that Halalify can close another app or replace its device output with the processed speech stem. The pause request is best-effort and player-dependent.

The source action accepts direct media URLs. A YouTube watch/share URL is a web page, not a stable media file, and is intentionally not scraped by the app; download the clip with a permitted tool and choose the resulting file, or provide a permitted direct MP4/M4A URL.

The gender detector is the separate `halalify_v2` model; NSFW is intentionally a second, small classifier because the YOLO output contract is kept behind the native adapter. The NSFW model only annotates regions already selected by the gender-target policy, so a classifier-only positive result cannot create a full-frame blur. Thresholds and accuracy still require validation on representative app screenshots.

## Native AI Engine layout

`native/ai_engine.*` is the platform-neutral orchestrator. It combines the gender detector and the NSFW classifier, while `native/backends/*` contains runtime-specific inference adapters. Android exposes this through a small JNI adapter; a future iOS target can reuse the orchestration and provide Core ML or LiteRT backends without moving model or blur policy into Swift/Kotlin.

The DNS VPN is a filtering layer rather than a full traffic-forwarding VPN. It does not inspect HTTPS page contents and cannot reliably stop DNS-over-HTTPS, DNS-over-TLS, direct-IP access, or an app that bypasses the system resolver. Device-managed lockdown or a full VPN forwarder is required for stronger enforcement.

## Shared site-policy engine

The domain rules are data, not Kotlin constants. The packaged default is [`Model/site_blocklist.txt`](Model/site_blocklist.txt). At runtime Android first checks the app-private file `files/site_blocklist.txt`; that allows a future updater to replace the list without changing the Kotlin or native code. The parser accepts plain domains, hosts-file lines, and the portable `||domain^` form.

The matching implementation is in `native/core/site_filter.*` with a small C API in `native/include/halalify_site_filter.h`. Android's `AdultSiteVpnService` is only a transport adapter. An iOS implementation can compile the same core with Objective-C++ and call it from a `NetworkExtension` packet/DNS provider.

## Model-label contract

The extracted web implementation currently interprets model label `a` as female and `b` as male, while `c` is not blurred. Validate that mapping with known images before relying on it in a production inference pipeline.

## Image AI engine plan

The verified deployed model, its machine-readable contract, provenance notes, and the cross-platform C++ integration plan are in [`Model`](Model/README.md).

The reproducible large-data mobile retraining pipeline, leakage guard, tiny-avatar simulation, INT8 export, per-class calibration, and fail-closed release gates are documented in [`training/vision/README_AR.md`](training/vision/README_AR.md).

## Audio AI engine plan

The playback-capture architecture, exact TFLite waveform contract, integration point, and Android platform limits are documented.

The audio-model preparation scripts, licensing notes, local Python environment instructions, and fine-tuning/export commands are documented. Downloaded datasets, virtual environments, and intermediate training artifacts are intentionally excluded from the repository.

## Verification

Run the following from the repository root:

```powershell
.\gradlew.bat :app:testDebugUnitTest :app:assembleDebug
```
