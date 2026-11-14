import json
import shutil
from pathlib import Path

from ultralytics import YOLO

CHECKPOINT = Path("runs/ambix-checkpoints/arena_best.pt")
ASSETS = Path("AmbiX/app/src/main/assets")
IMAGE_SIZE = 320


def main():
    if not CHECKPOINT.is_file() or CHECKPOINT.stat().st_size < 1_000_000:
        raise FileNotFoundError(f"Recovered arena checkpoint missing or unexpectedly small: {CHECKPOINT}")

    model = YOLO(str(CHECKPOINT))
    raw_names = model.names
    if isinstance(raw_names, dict):
        labels = [str(raw_names[i]) for i in sorted(raw_names)]
    elif isinstance(raw_names, list):
        labels = [str(name) for name in raw_names]
    else:
        raise ValueError(f"Unsupported checkpoint class map: {type(raw_names).__name__}")
    if len(labels) < 2 or len(set(labels)) != len(labels):
        raise ValueError(f"Checkpoint class names are invalid: {len(labels)} labels")

    exported = model.export(format="tflite", imgsz=IMAGE_SIZE, int8=False, nms=False)
    exported_path = Path(exported)
    candidates = [exported_path] if exported_path.is_file() else list(exported_path.rglob("*.tflite"))
    candidates += list(CHECKPOINT.parent.rglob("*.tflite"))
    candidates = [
        path for path in candidates
        if path.is_file() and "float16" not in path.name.lower() and "int8" not in path.name.lower()
    ]
    if not candidates:
        raise FileNotFoundError("Checkpoint export completed without a float32 TFLite file")

    ASSETS.mkdir(parents=True, exist_ok=True)
    model_file = max(candidates, key=lambda path: path.stat().st_size)
    shutil.copy2(model_file, ASSETS / "ambix_model.tflite")
    (ASSETS / "labels.txt").write_text("\\n".join(labels) + "\\n")
    metadata = {
        "model": "YOLO11n fine-tuned for Clash Royale arena objects",
        "checkpoint": str(CHECKPOINT),
        "dataset": "clash-royale-data-collection/clash-royale-bhjq1/2",
        "dataset_license": "CC BY 4.0",
        "image_size": IMAGE_SIZE,
        "labels_count": len(labels),
        "tflite_file": "ambix_model.tflite",
        "tflite_input": "float32 [1,320,320,3]",
        "tflite_output": "YOLO raw output; no NMS",
        "validation_metrics": None,
        "warning": "Recovered checkpoint export only. No new training or evaluation was performed in this workflow. Validate on held-out current-game screenshots before enabling automation."
    }
    (ASSETS / "model_metadata.json").write_text(json.dumps(metadata, indent=2) + "\\n")
    print(json.dumps(metadata, indent=2))


if __name__ == "__main__":
    main()
