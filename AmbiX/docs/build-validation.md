# AmbiX build validation

The Android workflow runs the temporal-detection unit tests before assembling the debug APK.
The separate manual model-training workflow builds both float32 TFLite models, verifies their
labels/metadata, runs the same Android tests, and assembles an APK with both models bundled.

No generated model weights are committed until the training workflow completes successfully.
