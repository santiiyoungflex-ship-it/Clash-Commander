import json
import os
import shutil
from pathlib import Path

import yaml
from roboflow import Roboflow
from ultralytics import YOLO

WORKSPACE = "clash-royale-data-collection"
PROJECT = "clash-royale-bhjq1"
VERSION = 2
IMAGE_SIZE = 320
EPOCHS = int(os.getenv("AMBIX_EPOCHS", "30"))
OUTPUT = Path("runs/ambix")
ASSETS = Path("AmbiX/app/src/main/assets")


def main():
    api_key = os.environ.get("ROBOFLOW_API_KEY")
    if not api_key:
        raise SystemExit("Missing ROBOFLOW_API_KEY secret. Add it in GitHub repository Actions secrets.")
    if not 1 <= EPOCHS <= 120:
        raise ValueError("AMBIX_EPOCHS must be between 1 and 120")

    rf = Roboflow(api_key=api_key)
    project = rf.workspace(WORKSPACE).project(PROJECT)
    dataset = project.version(VERSION).download("yolov8", location="datasets/clash-royale-v1", overwrite=True)
    data_yaml = Path(dataset.location) / "data.yaml"
    if not data_yaml.exists():
        raise FileNotFoundError(f"Roboflow export did not include data.yaml: {data_yaml}")

    config = yaml.safe_load(data_yaml.read_text())
    raw_names = config.get("names")
    if isinstance(raw_names, dict):
        labels = [str(raw_names[i]) for i in sorted(raw_names, key=lambda x: int(x))]
    elif isinstance(raw_names, list):
        labels = [str(x) for x in raw_names]
    else:
        raise ValueError("Unsupported class names in Roboflow data.yaml")
    if len(labels) < 2 or len(set(labels)) != len(labels):
        raise ValueError(f"Dataset class names are invalid or duplicated: {len(labels)} labels")
    print(f"Using the dataset's actual class list: {len(labels)} classes")

    model = YOLO("yolo11n.pt")
    model.train(
        data=str(data_yaml),
        epochs=EPOCHS,
        imgsz=IMAGE_SIZE,
        batch=8,
        device="cpu",
        workers=2,
        patience=20,
        seed=42,
        deterministic=True,
        project=str(OUTPUT),
        name="clash-royale-yolo11n",
        exist_ok=True,
        cache=False,
        amp=False,
        plots=True,
    )

    # Ultralytics may resolve its configured runs_dir outside the repository
    # (for example inside the installed package's tests/tmp directory).
    # Read the authoritative save_dir from the trainer instead of assuming
    # that the run was written under the process working directory.
    save_dir = Path(model.trainer.save_dir)
    best_weights = save_dir / "weights" / "best.pt"
    if not best_weights.is_file():
        candidates = list(save_dir.parent.rglob("best.pt"))
        if len(candidates) == 1:
            best_weights = candidates[0]
        else:
            raise FileNotFoundError(
                f"Training completed but best.pt was not found. "
                f"Trainer save_dir={save_dir}; candidates={candidates}"
            )

    trained = YOLO(str(best_weights))
    evaluation_split = "test" if config.get("test") else "val"
    val_metrics = trained.val(data=str(data_yaml), imgsz=IMAGE_SIZE, device="cpu", split=evaluation_split)
    exported = trained.export(format="tflite", imgsz=IMAGE_SIZE, int8=False, nms=False)
    exported_path = Path(exported)
    candidates = [exported_path] if exported_path.is_file() else list(exported_path.rglob("*.tflite"))
    candidates += list(best_weights.parent.parent.rglob("*.tflite"))
    candidates = [p for p in candidates if p.exists() and "float16" not in p.name.lower() and "int8" not in p.name.lower()]
    if not candidates:
        raise FileNotFoundError("Ultralytics export completed without a float32 TFLite model")
    model_file = max(candidates, key=lambda p: p.stat().st_size)

    ASSETS.mkdir(parents=True, exist_ok=True)
    shutil.copy2(model_file, ASSETS / "ambix_model.tflite")
    (ASSETS / "labels.txt").write_text("\n".join(labels) + "\n")

    metrics_dict = getattr(val_metrics, "results_dict", {})
    metrics = {}
    for key, value in metrics_dict.items():
        try:
            metrics[str(key)] = float(value)
        except (TypeError, ValueError):
            continue
    metadata = {
        "model": "YOLO11n fine-tuned for Clash Royale arena objects",
        "dataset": f"{WORKSPACE}/{PROJECT}/{VERSION}",
        "dataset_license": "CC BY 4.0",
        "image_size": IMAGE_SIZE,
        "epochs_requested": EPOCHS,
        "labels_count": len(labels),
        "tflite_file": "ambix_model.tflite",
        "tflite_input": "float32 [1,320,320,3]",
        "tflite_output": "YOLO raw output; no NMS",
        "evaluation_split": evaluation_split,
        "validation_metrics": metrics,
        "warning": "Baseline only. Do not enable unattended gameplay until tested on held-out Moto e32 gameplay captures."
    }
    (ASSETS / "model_metadata.json").write_text(json.dumps(metadata, indent=2) + "\n")
    print(json.dumps(metadata, indent=2))


if __name__ == "__main__":
    main()
