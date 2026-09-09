"""CIFAR-10 classification through the managed Skywright Run Context."""

import io
import json
from dataclasses import asdict

import numpy as np
import torch
from PIL import Image
from torch import nn


def classifier():
    """A small convolutional classifier whose complete state fits local checkpoints."""
    layers = []
    channels = 3
    for width in (32, 64, 128):
        layers.extend(
            [
                nn.Conv2d(channels, width, 3, padding=1),
                nn.ReLU(),
                nn.Conv2d(width, width, 3, padding=1),
                nn.ReLU(),
                nn.MaxPool2d(2),
            ]
        )
        channels = width
    return nn.Sequential(*layers, nn.Flatten(), nn.Linear(128 * 4 * 4, 10))


def decode(items):
    images, labels = [], []
    for item in items:
        payload = item.payload
        pixels = payload["image"]
        label = payload["label"]
        if not isinstance(pixels, bytes) or len(pixels) != 3072:
            raise ValueError("CIFAR-10 images require 3072 planar RGB bytes")
        if type(label) is not int or not 0 <= label < 10:
            raise ValueError("CIFAR-10 labels must be integers from 0 through 9")
        images.append(np.frombuffer(pixels, dtype=np.uint8).reshape(3, 32, 32))
        labels.append(label)
    return torch.from_numpy(np.stack(images)), torch.tensor(labels, dtype=torch.long)


def prediction_grid(images):
    canvas = Image.new("RGB", (8 * 32, 8 * 32))
    for index, pixels in enumerate(images[:64]):
        canvas.paste(
            Image.fromarray(pixels.permute(1, 2, 0).numpy()),
            (index % 8 * 32, index // 8 * 32),
        )
    output = io.BytesIO()
    canvas.save(output, format="PNG")
    return output.getvalue()


def train(context):
    options = context.configuration["project"]
    steps = int(options["steps"])
    batch_size = int(options["batchSize"])
    output_every = int(options["outputEvery"])
    device = context.accelerator.device
    model = classifier().to(device)
    optimizer = torch.optim.SGD(
        model.parameters(), lr=float(options["learningRate"]), momentum=0.9
    )
    context.register_checkpoint_state("model", model)
    context.register_checkpoint_state("optimizer", optimizer)
    context.start()
    print(
        json.dumps(
            {
                "event": "training-started",
                "step": context.step,
                "cursor": asdict(context.dataset_cursor),
                "device": device,
                "deviceName": (
                    torch.cuda.get_device_name(device) if device != "cpu" else "cpu"
                ),
                "hip": torch.version.hip,
                "parameters": sum(
                    parameter.numel() for parameter in model.parameters()
                ),
            }
        ),
        flush=True,
    )
    while context.step < steps:
        pending = []
        batches = iter(context.dataset.batches(context.dataset_cursor))
        try:
            for batch in batches:
                pending.extend(batch.items)
                epoch_finished = batch.next_cursor.epoch != batch.epoch
                if len(pending) < batch_size and not epoch_finished:
                    continue
                images, labels = decode(pending)
                inputs = images.to(device=device, dtype=torch.float32).div(255)
                inputs = inputs.sub(0.5).div(0.5)
                targets = labels.to(device)
                optimizer.zero_grad(set_to_none=True)
                logits = model(inputs)
                loss = nn.functional.cross_entropy(logits, targets)
                if not torch.isfinite(loss).item():
                    raise ValueError("Training loss is not finite")
                loss.backward()
                optimizer.step()
                predictions = logits.detach().argmax(1).cpu()
                accuracy = (predictions == labels).float().mean().item()
                context.observe("train/loss", loss.item())
                context.observe("train/accuracy", accuracy)
                # The final issued batch commits every accumulated Dataset Item.
                context.commit_step(batch)
                record = {
                    "event": "training-step",
                    "step": context.step,
                    "cursor": asdict(context.dataset_cursor),
                    "ordinals": [item.ordinal for item in pending],
                    "loss": loss.item(),
                    "accuracy": accuracy,
                }
                print(json.dumps(record), flush=True)
                if context.step == 1 or context.step % output_every == 0:
                    context.persist_sample(
                        "training-images.png",
                        prediction_grid(images),
                        media_type="image/png",
                    )
                    context.persist_artifact(
                        "predictions.json",
                        json.dumps(
                            {
                                **record,
                                "labels": labels.tolist(),
                                "predictions": predictions.tolist(),
                            }
                        ).encode(),
                    )
                pending.clear()
                if context.step >= steps:
                    return
        finally:
            batches.close()


if __name__ == "__main__":
    # Publication smoke runs without a GPU; qualification runs the full entry point.
    from streaming.base.format.mds.reader import MDSReader

    assert MDSReader.__name__ == "MDSReader"
    with torch.no_grad():
        assert classifier()(torch.zeros(2, 3, 32, 32)).shape == (2, 10)
    print("CIFAR-10 classifier imports and CPU forward pass succeeded")
