from __future__ import annotations

import hashlib
import json
import os
import shutil
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Iterable, Iterator


CLASS_NAMES = ("female_appearance", "male_appearance", "other_person")
MODEL_SIZE = 416


@dataclass(frozen=True)
class Box:
    class_id: int
    x1: float
    y1: float
    x2: float
    y2: float

    def validate(self) -> "Box":
        if self.class_id not in range(len(CLASS_NAMES)):
            raise ValueError(f"Invalid class id: {self.class_id}")
        values = (self.x1, self.y1, self.x2, self.y2)
        if not all(0.0 <= value <= 1.0 for value in values):
            raise ValueError(f"Box coordinates must be normalized: {values}")
        if self.x2 <= self.x1 or self.y2 <= self.y1:
            raise ValueError(f"Degenerate box: {values}")
        return self

    def to_yolo(self) -> str:
        self.validate()
        width = self.x2 - self.x1
        height = self.y2 - self.y1
        center_x = self.x1 + width * 0.5
        center_y = self.y1 + height * 0.5
        return (
            f"{self.class_id} {center_x:.7f} {center_y:.7f} "
            f"{width:.7f} {height:.7f}"
        )

    @classmethod
    def from_dict(cls, value: dict[str, Any]) -> "Box":
        return cls(
            class_id=int(value["class_id"]),
            x1=float(value["x1"]),
            y1=float(value["y1"]),
            x2=float(value["x2"]),
            y2=float(value["y2"]),
        ).validate()

    def as_dict(self) -> dict[str, Any]:
        return {
            "class_id": self.class_id,
            "x1": self.x1,
            "y1": self.y1,
            "x2": self.x2,
            "y2": self.y2,
        }


def stable_u64(value: str, seed: int = 0) -> int:
    payload = f"{seed}:{value}".encode("utf-8")
    return int.from_bytes(hashlib.sha256(payload).digest()[:8], "big")


def stable_split(source_id: str, validation_fraction: float, seed: int) -> str:
    if not 0.0 < validation_fraction < 0.5:
        raise ValueError("validation_fraction must be between 0 and 0.5")
    unit = stable_u64(source_id, seed) / float(2**64)
    return "val" if unit < validation_fraction else "train"


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest().lower()


def read_jsonl(path: Path) -> Iterator[dict[str, Any]]:
    with path.open("r", encoding="utf-8") as stream:
        for line_number, line in enumerate(stream, 1):
            if not line.strip():
                continue
            try:
                yield json.loads(line)
            except json.JSONDecodeError as error:
                raise ValueError(f"Invalid JSON at {path}:{line_number}") from error


def write_jsonl(path: Path, records: Iterable[dict[str, Any]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    with temporary.open("w", encoding="utf-8", newline="\n") as stream:
        for record in records:
            stream.write(json.dumps(record, ensure_ascii=False, sort_keys=True) + "\n")
    os.replace(temporary, path)


def link_or_copy(source: Path, destination: Path) -> str:
    destination.parent.mkdir(parents=True, exist_ok=True)
    if destination.exists():
        return "existing"
    try:
        os.link(source, destination)
        return "hardlink"
    except OSError:
        shutil.copy2(source, destination)
        return "copy"


def intersection_over_union(left: Box, right: Box) -> float:
    x1 = max(left.x1, right.x1)
    y1 = max(left.y1, right.y1)
    x2 = min(left.x2, right.x2)
    y2 = min(left.y2, right.y2)
    intersection = max(0.0, x2 - x1) * max(0.0, y2 - y1)
    left_area = (left.x2 - left.x1) * (left.y2 - left.y1)
    right_area = (right.x2 - right.x1) * (right.y2 - right.y1)
    union = left_area + right_area - intersection
    return intersection / union if union > 0.0 else 0.0


def projected_min_side(
    box: Box, width: int, height: int, model_size: int = MODEL_SIZE
) -> float:
    scale = min(model_size / max(1, width), model_size / max(1, height))
    return min(
        (box.x2 - box.x1) * width * scale,
        (box.y2 - box.y1) * height * scale,
    )


def load_recipe(path: Path) -> dict[str, Any]:
    recipe = json.loads(path.read_text(encoding="utf-8"))
    if recipe.get("model", {}).get("input_size") != MODEL_SIZE:
        raise ValueError(f"The mobile runtime currently requires input_size={MODEL_SIZE}")
    if tuple(recipe.get("model", {}).get("classes", ())) != CLASS_NAMES:
        raise ValueError(f"Class order must remain {CLASS_NAMES}")
    return recipe
