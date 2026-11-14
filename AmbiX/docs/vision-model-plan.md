# AmbiX on-device vision plan

## Selected starting dataset

Use the updated Roboflow Universe project **Clash Royale** by Clash Royale Data Collection as the primary arena detector:

- Project: https://universe.roboflow.com/clash-royale-data-collection/clash-royale-bhjq1
- Selected dataset version: 2
- Dataset page reports 4,452 source images and a 12,022-image dataset used by its published YOLO11n model
- Label set: 124 classes covering troops, buildings and spells
- Published model metrics: mAP@50 92.3%, precision 86.0%, recall 86.8%
- Published license: CC BY 4.0

This is a stronger starting point for arena recognition than the earlier 1,792-image candidate, but its published metrics belong to the dataset page model and are not AmbiX results. AmbiX must train and evaluate its own weights on the selected split.

## Supporting HUD dataset

Evaluate the Clash Royale Static Detection project as a separate source for static interface elements:

- Project: https://universe.roboflow.com/clash-royale-gl2uo/clash-royale-static-detection
- About 151 images and 28 classes, including elixir bar, card slots, crowns, towers and game-over/time indicators
- The page reports mAP@50 96.4%, precision 86.4%, recall 97.4%

Its small size and limited class coverage make it unsuitable as the only model. Use it to help build a dedicated HUD detector or as additional labeled training data after checking annotation compatibility.

## Implemented local inference and training pipeline

- Android now has a TensorFlow Lite runtime that loads `ambix_model.tflite` and `labels.txt` from app assets, preprocesses captured frames, parses YOLO raw outputs, applies NMS and emits normalized detections.
- The screen-capture service forwards each captured frame through the local detector when a trained model is installed. The UI exposes model readiness and detected objects.
- A manual GitHub Actions training workflow downloads dataset version 2, fine-tunes YOLO11n, evaluates the held-out test split, exports float32 TFLite, and commits the model assets to the branch. It requires the repository Actions secret `ROBOFLOW_API_KEY`.
- The model assets are not yet present in the repository; the workflow must be run successfully before on-device inference can become ready.

### HUD and hand-card baseline

A second model is trained from the YoloTrain Clash Royale dataset version 3 (115 classes, 611 images, CC BY 4.0): https://universe.roboflow.com/yolotrain-c7s7v/clash-royale-ut3g8. It includes historical card-in-hand/next and elixir labels. This is a small, older dataset, so it is treated as an auxiliary baseline; the app abstains if it cannot confidently identify at least three hand cards and elixir. New current-version gameplay captures will be needed to validate and expand its coverage.

## Android deployment constraints

1. Gameplay inference must run locally on the phone. Do not make each frame depend on a Roboflow cloud API or an embedded API key.
2. Export/train a compact detector and convert it to TensorFlow Lite; benchmark latency and memory on the Moto e32 before enabling continuous inference.
3. Normalize all boxes to frame coordinates and map them through calibrated arena/hand regions. Do not hardcode pixel coordinates for one screen resolution.
4. Run raw detections through `TemporalDetectionFilter`: same-label/team boxes are associated by IoU, duplicate boxes in one frame are suppressed, and a threat must appear in at least 3 of the last 5 distinct frames before the tactical engine sees it. Repeated calls for the same capture timestamp do not count as extra frames.
5. Validate card-in-hand recognition, elixir estimation, troop detection and tower/arena geometry independently.
6. Keep automatic gestures disabled until the model is locally loaded, confidence thresholds are met, and placement coordinates have been tested. Low confidence must result in no action.

## Current software-side precision safeguards

- Per-kind admission floors are applied before temporal association: troop/building 0.78, tower 0.82, spell 0.90, hand-card 0.94, elixir 0.96, unknown 0.99.
- These values are conservative filtering thresholds, **not** measured accuracy or calibrated probabilities. They must be tuned from a held-out confusion matrix and precision-recall curves once a real model is integrated.
- Tactical threat responses now consume only temporally confirmed detections. The filter is not a substitute for a trained detector and cannot correct a consistently wrong class prediction.
- The current screen-capture service still samples UI colors only; no trained model is bundled or producing real troop/card detections yet.

## Acceptance gates

- Model file is present and loaded locally, with a version/hash shown in diagnostics.
- Per-class precision/recall is measured on fresh gameplay screenshots not used in training.
- Card hand identity and elixir reading are delegated to the separate HUD model and still require their own hold-out tests on current gameplay.
- The decision engine is tested in observation mode before any live gesture execution.
- The user can immediately pause/stop automation.
