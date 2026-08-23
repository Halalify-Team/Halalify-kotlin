from __future__ import annotations

"""Build the final YOLO dataset and reject every Bancmark overlap."""

import argparse
import json
import random
import sys
from collections import Counter
from pathlib import Path
from typing import Any

from PIL import Image, ImageDraw, ImageEnhance, ImageFilter, ImageOps

from training.vision.common import (
    CLASS_NAMES,
    Box,
    link_or_copy,
    load_recipe,
    projected_min_side,
    read_jsonl,
    sha256_file,
    stable_u64,
    write_jsonl,
)


IMAGE_EXTENSIONS = {".jpg", ".jpeg", ".png", ".webp", ".bmp"}


def collect_benchmark_denials(benchmark_root: Path) -> dict[str, set[Any]]:
    denials: dict[str, set[Any]] = {
        "sha256": set(),
        "source_id": set(),
        "fairface_row": set(),
        "openimages_id": set(),
    }
    for manifest in benchmark_root.rglob("*.jsonl"):
        for record in read_jsonl(manifest):
            digest = str(record.get("sha256") or "").lower()
            if digest:
                denials["sha256"].add(digest)
            source_id = record.get("source_id")
            if source_id:
                denials["source_id"].add(str(source_id))
            source = record.get("source") or {}
            if isinstance(source, dict):
                dataset = str(source.get("dataset") or "").lower()
                row_index = source.get("row_index")
                if "fairface" in dataset and row_index is not None:
                    denials["fairface_row"].add(int(row_index))
            image_id = record.get("image_id")
            if image_id:
                denials["openimages_id"].add(str(image_id))
    image_roots = [benchmark_root / "dataset", benchmark_root / "android" / "app" / "src" / "androidTest" / "assets"]
    image_count = 0
    for image_root in image_roots:
        if not image_root.exists():
            continue
        for image_path in image_root.rglob("*"):
            if image_path.is_file() and image_path.suffix.lower() in IMAGE_EXTENSIONS:
                denials["sha256"].add(sha256_file(image_path))
                image_count += 1
                if image_count % 5000 == 0:
                    print(f"Hashed {image_count:,} benchmark images for leakage guard", flush=True)
    print(
        f"Leakage guard: {len(denials['sha256']):,} hashes, "
        f"{len(denials['fairface_row']):,} FairFace rows, "
        f"{len(denials['openimages_id']):,} Open Images ids"
    )
    return denials


def is_denied(record: dict[str, Any], denials: dict[str, set[Any]]) -> bool:
    if str(record.get("sha256") or "").lower() in denials["sha256"]:
        return True
    if str(record.get("source_id") or "") in denials["source_id"]:
        return True
    if record.get("source") == "fairface_0.25" and int(record["row_index"]) in denials["fairface_row"]:
        return True
    if str(record.get("image_id") or "") in denials["openimages_id"]:
        return True
    return False


def destination_stem(source_id: str) -> str:
    return f"src_{stable_u64(source_id):016x}"


def install_source_record(
    record: dict[str, Any],
    dataset_root: Path,
) -> dict[str, Any]:
    split = str(record["split"])
    if split not in {"train", "val"}:
        raise ValueError(f"Invalid split for {record['source_id']}: {split}")
    source_image = Path(record["image"])
    if not source_image.is_file():
        raise FileNotFoundError(source_image)
    stem = destination_stem(record["source_id"])
    destination_image = dataset_root / "images" / split / f"{stem}.jpg"
    destination_label = dataset_root / "labels" / split / f"{stem}.txt"
    link_or_copy(source_image, destination_image)
    boxes = [Box.from_dict(value) for value in record.get("boxes", [])]
    destination_label.parent.mkdir(parents=True, exist_ok=True)
    destination_label.write_text(
        "".join(box.to_yolo() + "\n" for box in boxes),
        encoding="utf-8",
        newline="\n",
    )
    provenance: dict[str, str] = {}
    if record.get("source") == "fairface_0.25":
        provenance = {
            "source_url": "https://huggingface.co/datasets/HuggingFaceM4/FairFace",
            "license": "CC BY 4.0",
        }
    elif str(record.get("source") or "").startswith("openimages_v7"):
        provenance = {
            "source_url": "https://storage.googleapis.com/openimages/web/index.html",
            "annotation_license": "CC BY 4.0",
            "image_license": "listed by Open Images as CC BY 2.0; verify attribution",
        }
    return {
        **record,
        **provenance,
        "dataset_image": str(destination_image.resolve()),
        "dataset_label": str(destination_label.resolve()),
    }


def _avatar_patch(
    image_path: Path,
    size: int,
    circular: bool,
    style_variant: int,
) -> Image.Image:
    with Image.open(image_path) as source:
        patch = ImageOps.fit(source.convert("RGB"), (size, size), Image.Resampling.LANCZOS)
    if style_variant == 1:
        patch = ImageOps.posterize(patch, bits=4).filter(ImageFilter.EDGE_ENHANCE_MORE)
    elif style_variant == 2:
        patch = ImageEnhance.Color(patch).enhance(0.15)
        patch = ImageEnhance.Contrast(patch).enhance(1.35)
    elif style_variant == 3:
        patch = patch.filter(ImageFilter.SMOOTH_MORE)
        patch = ImageEnhance.Color(patch).enhance(1.45)
    if not circular:
        return patch
    mask = Image.new("L", (size, size), 0)
    ImageDraw.Draw(mask).ellipse((0, 0, size - 1, size - 1), fill=255)
    patch.putalpha(mask)
    return patch


def _draw_social_background(canvas: Image.Image, rng: random.Random) -> None:
    draw = ImageDraw.Draw(canvas)
    width, height = canvas.size
    dark = rng.random() < 0.38
    base = (18, 20, 24) if dark else (244, 246, 249)
    card = (31, 34, 40) if dark else (255, 255, 255)
    muted = (88, 94, 104) if dark else (205, 210, 218)
    draw.rectangle((0, 0, width, height), fill=base)
    header_height = max(48, int(height * 0.045))
    draw.rectangle((0, 0, width, header_height), fill=card)
    y = header_height + 18
    while y < height - 8:
        card_height = rng.randint(max(150, width // 3), max(220, width * 3 // 4))
        card_bottom = min(height - 8, y + card_height)
        if card_bottom <= y:
            break
        draw.rounded_rectangle(
            (12, y, width - 12, card_bottom),
            radius=max(8, width // 70),
            fill=card,
        )
        for line in range(rng.randint(2, 5)):
            line_y = y + 26 + line * max(12, width // 55)
            draw.rounded_rectangle(
                (max(74, width // 8), line_y, rng.randint(width // 2, width - 36), line_y + max(5, width // 130)),
                radius=3,
                fill=muted,
            )
        y += card_height + rng.randint(12, 28)


def create_avatar_composite(
    destination: Path,
    faces: list[dict[str, Any]],
    rng: random.Random,
    portrait: bool,
) -> tuple[int, int, list[Box], list[str]]:
    # Half-resolution canvases preserve the exact normalized phone geometry
    # while cutting generated-dataset storage by roughly 4x. The avatar sizes
    # below therefore represent 20-90px on a 1080px phone and 16-128px on a
    # 720px square feed.
    width, height = (540, 1200) if portrait else (360, 360)
    canvas = Image.new("RGB", (width, height))
    _draw_social_background(canvas, rng)
    sizes = [10, 12, 14, 16, 20, 24, 32, 45] if portrait else [8, 10, 12, 14, 16, 24, 32, 45, 64]
    instance_count = rng.randint(3, 8 if portrait else 6)
    chosen = rng.sample(faces, k=min(instance_count, len(faces)))
    boxes: list[Box] = []
    source_ids: list[str] = []
    used: list[tuple[int, int, int, int]] = []
    for face in chosen:
        size = rng.choice(sizes)
        for _ in range(40):
            x = rng.randint(12, max(12, width - size - 12))
            y = rng.randint(max(56, height // 40), max(56, height - size - 12))
            candidate = (x, y, x + size, y + size)
            if all(
                candidate[2] + 6 < old[0]
                or candidate[0] - 6 > old[2]
                or candidate[3] + 6 < old[1]
                or candidate[1] - 6 > old[3]
                for old in used
            ):
                break
        used.append(candidate)
        style_roll = rng.random()
        style_variant = 0
        if style_roll < 0.05:
            style_variant = 1
        elif style_roll < 0.09:
            style_variant = 2
        elif style_roll < 0.13:
            style_variant = 3
        patch = _avatar_patch(
            Path(face["image"]),
            size,
            circular=rng.random() < 0.82,
            style_variant=style_variant,
        )
        if patch.mode == "RGBA":
            canvas.paste(patch, (x, y), patch)
        else:
            canvas.paste(patch, (x, y))
        class_id = int(face["boxes"][0]["class_id"])
        boxes.append(Box(class_id, x / width, y / height, (x + size) / width, (y + size) / height))
        source_ids.append(str(face["source_id"]))
    destination.parent.mkdir(parents=True, exist_ok=True)
    canvas.save(destination, "JPEG", quality=rng.randint(72, 88), optimize=True, subsampling=2)
    return width, height, boxes, source_ids


def generate_avatar_composites(
    dataset_root: Path,
    accepted_sources: list[dict[str, Any]],
    count: int,
    seed: int,
    validation_fraction: float,
) -> list[dict[str, Any]]:
    by_split = {
        split: [
            record
            for record in accepted_sources
            if record["split"] == split
            and record["source"] == "fairface_0.25"
            and record.get("boxes")
        ]
        for split in ("train", "val")
    }
    if min(map(len, by_split.values()), default=0) < 8:
        raise ValueError("At least eight accepted FairFace images are required in each split")
    generated: list[dict[str, Any]] = []
    for index in range(count):
        split = (
            "val"
            if stable_u64(f"avatar-composite:{index}", seed) / 2**64 < validation_fraction
            else "train"
        )
        rng = random.Random(stable_u64(f"avatar-layout:{index}", seed))
        portrait = rng.random() < 0.72
        stem = f"avatar_{index:07d}"
        image_path = dataset_root / "images" / split / f"{stem}.jpg"
        label_path = dataset_root / "labels" / split / f"{stem}.txt"
        width, height, boxes, source_ids = create_avatar_composite(
            image_path,
            by_split[split],
            rng,
            portrait,
        )
        label_path.parent.mkdir(parents=True, exist_ok=True)
        label_path.write_text(
            "".join(box.to_yolo() + "\n" for box in boxes),
            encoding="utf-8",
            newline="\n",
        )
        generated.append(
            {
                "source": "avatar_composite",
                "source_id": f"avatar-composite:{index}",
                "component_source_ids": source_ids,
                "split": split,
                "image": str(image_path.resolve()),
                "dataset_image": str(image_path.resolve()),
                "dataset_label": str(label_path.resolve()),
                "width": width,
                "height": height,
                "boxes": [box.as_dict() for box in boxes],
            }
        )
        if (index + 1) % 2000 == 0:
            print(f"Generated {index + 1:,}/{count:,} phone/avatar composites", flush=True)
    return generated


def write_dataset_yaml(dataset_root: Path) -> None:
    names = "\n".join(f"  {index}: {name}" for index, name in enumerate(CLASS_NAMES))
    content = (
        f"path: {dataset_root.resolve().as_posix()}\n"
        "train: images/train\n"
        "val: images/val\n"
        "names:\n"
        f"{names}\n"
    )
    (dataset_root / "dataset.yaml").write_text(content, encoding="utf-8", newline="\n")


def build_summary(records: list[dict[str, Any]], unique_sources: int) -> dict[str, Any]:
    split_counts = Counter(record["split"] for record in records)
    class_counts: Counter[int] = Counter()
    tiny_instances = 0
    for record in records:
        for value in record.get("boxes", []):
            box = Box.from_dict(value)
            class_counts[box.class_id] += 1
            if projected_min_side(box, int(record["width"]), int(record["height"])) < 12.0:
                tiny_instances += 1
    return {
        "images": len(records),
        "train_images": split_counts["train"],
        "val_images": split_counts["val"],
        "unique_source_images": unique_sources,
        "instances_by_class": {CLASS_NAMES[key]: value for key, value in sorted(class_counts.items())},
        "tiny_instances_projected_below_12px": tiny_instances,
    }


def enforce_targets(summary: dict[str, Any], recipe: dict[str, Any]) -> None:
    targets = recipe["dataset_targets"]
    failures = []
    for key, summary_key in (
        ("minimum_unique_sources", "unique_source_images"),
        ("minimum_train_images", "train_images"),
        ("minimum_tiny_instances", "tiny_instances_projected_below_12px"),
    ):
        if int(summary[summary_key]) < int(targets[key]):
            failures.append(f"{summary_key}={summary[summary_key]} < {targets[key]}")
    for class_name in CLASS_NAMES[:2]:
        if int(summary["instances_by_class"].get(class_name, 0)) < 20000:
            failures.append(f"{class_name} has fewer than 20,000 instances")
    if failures:
        raise ValueError("Dataset release gates failed:\n- " + "\n- ".join(failures))


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sources", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--benchmark-root", type=Path, required=True)
    parser.add_argument("--recipe", type=Path, default=Path(__file__).with_name("recipe.json"))
    parser.add_argument("--avatar-composites", type=int)
    parser.add_argument("--smoke", action="store_true", help="Allow a small pipeline-only dataset")
    args = parser.parse_args()
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    recipe = load_recipe(args.recipe)
    denials = collect_benchmark_denials(args.benchmark_root.resolve())
    source_records = list(read_jsonl(args.sources.resolve()))
    accepted = [record for record in source_records if not is_denied(record, denials)]
    rejected = len(source_records) - len(accepted)
    print(f"Accepted {len(accepted):,} sources; rejected {rejected:,} benchmark overlaps")
    installed = [install_source_record(record, args.output) for record in accepted]
    composite_count = (
        args.avatar_composites
        if args.avatar_composites is not None
        else int(recipe["dataset_targets"]["avatar_composites"])
    )
    composites = generate_avatar_composites(
        args.output,
        accepted,
        composite_count,
        int(recipe["seed"]),
        float(recipe["dataset_targets"]["validation_fraction"]),
    )
    final_records = installed + composites
    write_dataset_yaml(args.output)
    write_jsonl(args.output / "training_manifest.jsonl", final_records)
    summary = build_summary(final_records, len(accepted))
    summary["benchmark_overlaps_rejected"] = rejected
    if not args.smoke:
        enforce_targets(summary, recipe)
    (args.output / "dataset_summary.json").write_text(
        json.dumps(summary, indent=2, ensure_ascii=False, sort_keys=True) + "\n",
        encoding="utf-8",
    )
    print(json.dumps(summary, indent=2, ensure_ascii=False))


if __name__ == "__main__":
    main()
