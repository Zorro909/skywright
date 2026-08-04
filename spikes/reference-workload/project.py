"""PROTOTYPE — throwaway. THE TRAINING PROJECT.

This file is what a consumer of Skywright would write. It owns the model, the
loss, the hyperparameters and the data semantics — and nothing else. It never
touches a path, a bucket, a checkpoint file, a device selection or an
orchestrator.

Deliberately trivial DDPM on 8x8 synthetic images: seconds on CPU, so the
contract is what is being exercised rather than the arithmetic.

Note what is NOT here: no training loop. Both entry points (train.py, drive.py)
own their own loop, which is the point of ADR 0001 — and lets the TUI drive
one Step at a time by hand without the project knowing.
"""

from __future__ import annotations

import torch
import torch.nn as nn
import torch.nn.functional as F

from skywright_proto import MetricSpec

PROJECT_VERSION = "tiny-diffusion@0.1.0+8a3c1f"

# --- Project Configuration Contract (ADR 0002): schema + defaults, version-bound ---

SCHEMA = {
    "type": "object",
    "required": ["seed", "data", "model", "optim", "diffusion"],
    "properties": {
        "seed": {"type": "integer", "minimum": 0},
        "data": {
            "type": "object",
            "required": ["dataset", "version", "size", "batch_size"],
            "properties": {
                "dataset": {"type": "string"},
                "version": {"type": "string"},
                "size": {"type": "integer", "minimum": 8},
                "batch_size": {"type": "integer", "minimum": 1, "maximum": 256},
            },
        },
        "model": {
            "type": "object",
            "required": ["channels"],
            "properties": {"channels": {"type": "integer", "minimum": 4, "maximum": 128}},
        },
        "optim": {
            "type": "object",
            "required": ["lr"],
            "properties": {
                "lr": {"type": "number", "minimum": 0},
                "weight_decay": {"type": "number", "minimum": 0},
            },
        },
        "diffusion": {
            "type": "object",
            "required": ["timesteps"],
            "properties": {"timesteps": {"type": "integer", "minimum": 4, "maximum": 1000}},
        },
    },
}

DEFAULTS = {
    "seed": 7,
    "data": {"dataset": "shapes-8px", "version": "v1+f00dcafe", "size": 512, "batch_size": 16},
    "model": {"channels": 16},
    "optim": {"lr": 2e-3, "weight_decay": 0.0},
    "diffusion": {"timesteps": 50},
}


class TinyEps(nn.Module):
    def __init__(self, channels: int, timesteps: int):
        super().__init__()
        self.t_embed = nn.Embedding(timesteps, channels)
        self.inp = nn.Conv2d(1, channels, 3, padding=1)
        self.mid = nn.Conv2d(channels, channels, 3, padding=1)
        self.out = nn.Conv2d(channels, 1, 3, padding=1)

    def forward(self, x: torch.Tensor, t: torch.Tensor) -> torch.Tensor:
        h = F.silu(self.inp(x) + self.t_embed(t)[:, :, None, None])
        h = F.silu(self.mid(h))
        return self.out(h)


class Diffusion:
    """Model + optimizer + schedule + noise schedule. Owned entirely by the project."""

    def __init__(self, ctx):
        cfg = ctx.config
        self.timesteps = cfg["diffusion"]["timesteps"]
        self.model = TinyEps(cfg["model"]["channels"], self.timesteps)
        self.opt = torch.optim.AdamW(
            self.model.parameters(),
            lr=cfg["optim"]["lr"],
            weight_decay=cfg["optim"].get("weight_decay", 0.0),
        )
        self.sched = torch.optim.lr_scheduler.StepLR(self.opt, step_size=100, gamma=0.7)

        betas = torch.linspace(1e-4, 0.02, self.timesteps)
        self.alphas = 1.0 - betas
        self.abar = torch.cumprod(self.alphas, dim=0)
        self.betas = betas

    def register(self, ctx) -> None:
        """C2: everything resumable, declared before training begins."""
        ctx.register_state("model", self.model)
        ctx.register_state("optimizer", self.opt)
        ctx.register_state("scheduler", self.sched)
        # Data position, RNG state and step counter are the library's — see contract.py.

    def declare(self, ctx) -> None:
        """O1: every metric declared before the run begins."""
        ctx.declare_metric(MetricSpec(
            "train/loss", unit="mse", reduction="mean", comparison="minimize",
            description="Noise-prediction MSE.",
        ))
        ctx.declare_metric(MetricSpec(
            "train/lr", unit="dimensionless", reduction="last",
            description="Learning rate actually applied this Step.",
        ))
        ctx.declare_metric(MetricSpec(
            "train/timestep_mean", unit="dimensionless", reduction="mean",
            description="Mean sampled diffusion timestep — a sanity check on the RNG.",
        ))

    def train_step(self, batch) -> dict[str, float]:
        x0 = batch.x
        t = torch.randint(0, self.timesteps, (x0.shape[0],))
        noise = torch.randn_like(x0)
        abar = self.abar[t][:, None, None, None]
        xt = abar.sqrt() * x0 + (1 - abar).sqrt() * noise

        loss = F.mse_loss(self.model(xt, t), noise)
        self.opt.zero_grad(set_to_none=True)
        loss.backward()
        self.opt.step()
        self.sched.step()
        return {
            "train/loss": loss.detach(),
            "train/lr": self.sched.get_last_lr()[0],
            "train/timestep_mean": t.float().mean().item(),
        }

    @torch.no_grad()
    def draw(self, size: int = 8) -> torch.Tensor:
        """Ancestral sampling — used to produce a Sample through the Run Store."""
        x = torch.randn(1, 1, size, size)
        for i in reversed(range(self.timesteps)):
            t = torch.full((1,), i, dtype=torch.long)
            eps = self.model(x, t)
            mean = (x - (self.betas[i] / (1 - self.abar[i]).sqrt()) * eps) / self.alphas[i].sqrt()
            x = mean if i == 0 else mean + self.betas[i].sqrt() * torch.randn_like(x)
        return x
