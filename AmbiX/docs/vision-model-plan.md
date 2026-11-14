# AmbiX on-device vision plan

## Selected starting dataset

Use the Roboflow Universe project **Clash royale** by bobwatson as the primary troop detector starting point:

- Project: https://universe.roboflow.com/bobwatson/clash-royale-cylln-88wu8-yeqpq
- Reported dataset size: about 1,792 images
- Label set: 145 classes, including separate ally/enemy card and troop labels
- Reported model metrics: mAP@50 81.4%, precision 86.4%, recall 65.8%
- Published license: CC BY 4.0

This is a better starting point than the smaller public alternatives found in the initial search because it has substantially broader class coverage and reports stronger precision/mAP. Its recall is not yet high enough to authorize unattended gameplay. Treat it as a baseline, not a finished competitive model.

## Supporting HUD dataset

Evaluate the Clash Royale Static Detection project as a separate source for static interface elements:

- Project: https://universe.roboflow.com/clash-royale-gl2uo/clash-royale-static-detection
- About 151 images and 28 classes, including elixir bar, card slots, crowns, towers and game-over/time indicators
- The page reports mAP@50 96.4%, precision 86.4%, recall 97.4%

Its small size and limited class coverage make it unsuitable as the only model. Use it to help build a dedicated HUD detector or as additional labeled training data after checking annotation compatibility.

## Android deployment constraints

1. Gameplay inference must run locally on the phone. Do not make each frame depend on a Roboflow cloud API or an embedded API key.
2. Export/train a compact detector and convert it to TensorFlow Lite; benchmark latency and memory on the Moto e32 before enabling continuous inference.
3. Normalize all boxes to frame coordinates and map them through calibrated arena/hand regions. Do not hardcode pixel coordinates for one screen resolution.
4. Track detections across frames to avoid counting the same troop repeatedly and to estimate opponent card cycle.
5. Validate card-in-hand recognition, elixir estimation, troop detection and tower/arena geometry independently.
6. Keep automatic gestures disabled until the model is locally loaded, confidence thresholds are met, and placement coordinates have been tested. Low confidence must result in no action.

## Acceptance gates

- Model file is present and loaded locally, with a version/hash shown in diagnostics.
- Per-class precision/recall is measured on fresh gameplay screenshots not used in training.
- Card hand and elixir estimates remain stable across animation, emotes, spell effects and resolution changes.
- The decision engine is tested in observation mode before any live gesture execution.
- The user can immediately pause/stop automation.
