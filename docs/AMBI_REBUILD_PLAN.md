# Ambi — Android rebuild plan

## Product target

Ambi is an Android-only Clash Royale tactical assistant for the Motorola Moto e32 (Android 11). The target is fast, evidence-based tactical play: identify visible units and their positions, track movement across frames, choose a defensive response, preserve favorable elixir trades, and convert successful defense into counterpressure.

This document is a technical plan, not a claim that these capabilities are already implemented or validated.

## Non-negotiable constraints

- Preserve the existing applicationId `com.yoyostudios.clashcommander` during the first migration so an installed prior build can be upgraded without changing package identity.
- Keep all gameplay-critical perception and decisions on-device. A remote inference request must never be the only source of a time-critical defensive action.
- Fail closed: uncertain detections, stale frames, unknown card-in-hand state, or uncalibrated coordinates must result in WAIT, not a speculative tap.
- Do not claim a unit is detected unless the current frame supports it. Every track must carry confidence, age, and source-frame metadata.
- Do not assume NNAPI/GPU acceleration. Benchmark CPU/XNNPACK and available Android delegates on the actual Moto e32.
- Do not bundle third-party model weights until their license explicitly permits redistribution and commercial/non-commercial use as applicable.
- Never promise a global rank. Competitive performance must be measured over repeatable matches and device-side latency metrics.

## Architecture target

1. **Frame acquisition** — MediaProjection frame source with bounded frame ownership, explicit rotation/display geometry, and adaptive sampling to avoid excess copies and thermal load.
2. **Arena geometry** — calibrated arena ROI and coordinate transform derived from the current display dimensions; calibration must be validated on Moto e32, not borrowed from Moto G13.
3. **Local perception** — a legally redistributable detector exported to ONNX (or another Android-supported runtime), with documented input preprocessing, class map, confidence thresholds, NMS, and model checksum.
4. **Temporal tracking** — associate detections between frames; estimate lane, side, velocity, direction, and time-to-tower with uncertainty. Expire stale tracks aggressively.
5. **Game-state estimation** — hand/deck cycle, visible elixir estimate, match timer, tower health where legible, and opponent card-cycle hypotheses. Unknown values remain unknown.
6. **Tactical engine** — deterministic low-latency rules for immediate defense, positive trades, spell value, counterpush timing, lane pressure, and safe waiting. A cloud LLM may provide optional coaching, never a blocking hot-path dependency.
7. **Action execution** — AccessibilityService gesture queue with calibrated coordinates, cooldowns, preconditions, post-action verification where observable, and an immediate manual stop.
8. **Diagnostics** — on-device event log with frame age, detector latency, decision reason, action result, dropped-frame count, and thermal/performance observations.

## Existing implementation audit

The current branch contains useful Android foundations: MediaProjection capture, AccessibilityService gestures, overlay UI, deck management, and a card-hand classifier. It is not yet the target architecture:
- Arena detection currently uploads cropped frames to hosted Roboflow inference on an approximately 800 ms polling loop.
- Autopilot currently asks Gemini for decisions, so a network/LLM round trip can sit on the critical path.
- Card-hand classification is separate from arena-unit detection and its model accuracy/latency claims have not been validated on the target phone.
- Existing coordinate calibration was not demonstrated on the Moto e32.
- A successful Gradle build does not validate recognition quality, timing, or gameplay.

These components must be individually measured before reuse. Keep stable capture/accessibility/deck foundations; replace hosted hot-path perception and LLM-dependent hot-path decisions.

## Implementation gates

### Gate A — reproducible Android baseline
- Build and unit tests pass in CI.
- APK package name, minSdk, targetSdk, signing and checksum are recorded.
- App label is Ambi.
- Permissions and foreground-service behavior are verified on Android 11.

### Gate B — local detector qualification
- License and redistribution rights recorded.
- ONNX export and numerical parity checked against the source model.
- Test clips include troops, buildings, spells/effects, UI overlays, both lanes, mirrored sides, and animation frames.
- Report precision/recall by class, false-positive rate, model size, and p50/p95 inference latency on Moto e32.
- Do not enable automatic actions until false-positive and stale-track limits are met.

### Gate C — tactical safety
- Replay tests validate card-in-hand checks, elixir uncertainty, counter-cycle tracking, defensive placement, and counterpush decisions.
- No action on stale/low-confidence state.
- Manual STOP preempts all pending work.

### Gate D — device validation
- Test on the actual Moto e32 at native game resolution and refresh rate.
- Measure end-to-end frame-to-action latency, missed frames, memory, battery, and thermal throttling.
- Run supervised matches before enabling unattended play.

## Current status

- [x] Create dedicated `ambi-rebuild` branch from the last known project commit.
- [x] Change launcher app label to Ambi.
- [x] Rename Gradle root project to Ambi.
- [ ] Replace hosted arena inference with a licensed, measured on-device detector.
- [ ] Remove network LLM from the tactical hot path.
- [x] Add an isolated deterministic tactical policy with fail-closed behavior and unit tests (not yet connected to automatic taps).\n- [ ] Rework tracking and tactical decision engine.
- [ ] Add replay fixtures and automated tests.
- [ ] Validate coordinates and latency on Moto e32.
- [ ] Produce and inspect a release APK and checksum.
- [ ] Supervised gameplay validation.

## Model and dataset licensing research\n- The previously selected Hugging Face YOLOv8n weights still have no explicit redistributable license; do not bundle them.\n- A Roboflow Clash Royale dataset published under CC BY 4.0 is available, but a dataset license does not itself grant rights to unrelated pretrained weights or provide a qualified on-device model. It may be a lawful training-data candidate subject to attribution and dataset terms.\n- The current Roboflow hosted model remains a remote fallback only; it does not satisfy the local, low-latency perception requirement.\n\n## Branding

Working title: **Ambi**. Requested visual direction: a Heisenberg/Walter White-inspired portrait treatment. The final launcher artwork must be packaged as actual Android mipmap assets; a mockup or a generated image shown in chat is not an installed app icon.
