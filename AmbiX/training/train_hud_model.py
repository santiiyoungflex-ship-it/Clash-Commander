import json
import os
import shutil
from pathlib import Path

import yaml
from roboflow import Roboflow
from ultralytics import YOLO

WORKSPACE = "yolotrain-c7s7v"
PROJECT = "clash-royale-ut3g8"
VERSION = 3
IMAGE_SIZE = 320
EPOCHS = int(os.getenv("AMBIX_HUD_EPOCHS", "40"))
OUTPUT = Path("runs/ambix-hud")
ASSETS = Path("AmbiX/app/src/main/assets")


def main():
    api_key = os.environ.get("ROBOFLOW_API_KEY")
    if not api_key:
        raise SystemExit("Missing ROBOFLOW_API_KEY secret.")
    if not 1 <= EPOCHS <= 120:
        raise ValueError("AMBIX_HUD_EPOCHS must be between 1 and 120")

    rf = Roboflow(api_key=api_key)
    project = rf.workspace(WORKSPACE).project(PROJECT)
    dataset = project.version(VERSION).download("yolov8", location="datasets/clash-royale-hud-v3", overwrite=True)
    data_yaml = Path(dataset.location) / "data.yaml"
    if not data_yaml.exists():
        raise FileNotFoundError(f"Roboflow HUD export did not include data.yaml: {data_yaml}")

    config = yaml.safe_load(data_yaml.read_text())
    raw_names = config.get("names")
    if isinstance(raw_names, dict):
        labels = [str(raw_names[i]) for i in sorted(raw_names, key=lambda x: int(x))]
    elif isinstance(raw_names, list):
        labels = [str(x) for x in raw_names]
    else:
        raise ValueError("Unsupported HUD class names in data.yaml")
    if len(labels) < 2 or len(set(labels)) != len(labels):
        raise ValueError(f"HUD dataset class names are invalid or duplicated: {len(labels)} labels")
    print(f"Using the HUD dataset's actual class list: {len(labels)} classes")

    model = YOLO("yolo11n.pt")
    model.train(
        data=str(data_yaml),
        epochs=EPOCHS,
        imgsz=IMAGE_SIZE,
        batch=8,
        device="cpu",
        workers=2,
        patience=15,
        seed=43,
        deterministic=True,
        project=str(OUTPUT),
        name="clash-royale-hud-yolo11n",
        exist_ok=True,
        cache=False,
        amp=False,
        plots=True,
    )

    best_weights = OUTPUT / "clash-royale-hud-yolo11n" / "weights" / "best.pt"
    if not best_weights.exists():
        raise FileNotFoundError(f"HUD training did not produce best.pt: {best_weights}")

    checkpoint_dir = Path("runs/ambix-checkpoints")
    checkpoint_dir.mkdir(parents=True, exist_ok=True)
    shutil.copy2(best_weights, checkpoint_dir / "hud_best.pt")
    print(f"Persisted HUD checkpoint: {checkpoint_dir / 'hud_best.pt'}")

    trained = YOLO(str(best_weights))
    evaluation_split = "test" if config.get("test") else "val"
    evaluation = trained.val(data=str(data_yaml), imgsz=IMAGE_SIZE, device="cpu", split=evaluation_split)
    exported = trained.export(format="tflite", imgsz=IMAGE_SIZE, int8=False, nms=False)
    exported_path = Path(exported)
    candidates = [exported_path] if exported_path.is_file() else list(exported_path.rglob("*.tflite"))
    candidates += list(best_weights.parent.parent.rglob("*.tflite"))
    candidates = [p for p in candidates if p.exists() and "float16" not in p.name.lower() and "int8" not in p.name.lower()]
    if not candidates:
        raise FileNotFoundError("HUD export completed without a float32 TFLite model")

    ASSETS.mkdir(parents=True, exist_ok=True)
    shutil.copy2(max(candidates, key=lambda p: p.stat().st_size), ASSETS / "ambix_hud.tflite")
    (ASSETS / "hud_labels.txt").write_text("\n".join(labels) + "\n")

    metrics = {}
    for key, value in getattr(evaluation, "results_dict", {}).items():
        try:
            metrics[str(key)] = float(value)
        except (TypeError, ValueError):
            continue
    metadata = {
        "model": "YOLO11n HUD and hand-card baseline",
        "dataset": f"{WORKSPACE}/{PROJECT}/{VERSION}",
        "dataset_license": "CC BY 4.0",
        "dataset_images": 611,
        "image_size": IMAGE_SIZE,
        "epochs_requested": EPOCHS,
        "labels_count": len(labels),
        "tflite_file": "ambix_hud.tflite",
        "tflite_input": "float32 [1,320,320,3]",
        "tflite_output": "YOLO raw output; no NMS",
        "evaluation_split": evaluation_split,
        "evaluation_metrics": metrics,
        "warning": "Small and older dataset. Hand/elixir output must be checked on fresh current-game captures; do not automate from it without validation."
    }
    (ASSETS / "hud_model_metadata.json").write_text(json.dumps(metadata, indent=2) + "\n")
    print(json.dumps(metadata, indent=2))


if __name__ == "__main__":
    main()
