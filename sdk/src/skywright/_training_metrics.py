"""Metric contract composition and Step reduction validation."""

import math
import re
from collections.abc import Callable, Iterable, Mapping
from typing import NoReturn

from skywright._training_errors import SkywrightFailure, TrainingContractViolation
from skywright._training_types import (
    MetricCatalog,
    MetricDefinition,
    MetricObservation,
    StepReduction,
)


def reduce_metric(values: list[int | float], reduction: StepReduction) -> int | float:
    if reduction == "last":
        return values[-1]
    if reduction == "sum":
        return sum(values)
    if reduction == "min":
        return min(values)
    if reduction == "max":
        return max(values)
    return sum(values) / len(values)


def validate_observation(
    name: str,
    value: object,
    definition: MetricDefinition | None,
    violate: Callable[[str, str, str], NoReturn],
) -> int | float:
    if definition is None:
        violate(
            "metric/undeclared",
            f"metric {name!r} is not declared",
            "record only names from the version-pinned Metric Catalog",
        )
    numel = getattr(value, "numel", None)
    if callable(numel) and numel() != 1:
        violate(
            "metric/not-scalar",
            f"metric {name!r} received a value with {numel()} elements",
            "reduce it to one scalar where the project owns the semantics",
        )
    item = getattr(value, "item", None)
    if callable(item):
        value = item()
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        violate(
            "metric/not-scalar",
            f"metric {name!r} received {type(value).__name__}",
            "record a finite numeric scalar",
        )
    if not math.isfinite(value):
        violate(
            "metric/non-finite",
            f"metric {name!r} received {value!r}",
            "record a finite numeric scalar",
        )
    if definition.numeric_kind == "integer" and not isinstance(value, int):
        violate(
            "metric/numeric-kind",
            f"integer metric {name!r} received {value!r}",
            "record an integer value",
        )
    if definition.numeric_kind == "integer" and abs(value) > 2**24:
        violate(
            "metric/inexact-integer",
            f"integer metric {name!r} received {value!r}",
            "record an integer exactly representable by TensorBoard scalar encoding",
        )
    return value


def reduce_observations(
    pending: Mapping[str, list[int | float]],
    definitions: Mapping[str, MetricDefinition],
    next_step: int,
    violate: Callable[[str, str, str], NoReturn],
) -> tuple[MetricObservation, ...]:
    committed: list[MetricObservation] = []
    for name, values in pending.items():
        definition = definitions[name]
        reduction = definition.step_reduction
        if reduction is None:
            violate(
                "metric/recording-basis",
                f"wall-time metric {name!r} cannot be observed at a Step",
                "record only project-owned Step metrics through observe()",
            )
        reduced = reduce_metric(values, reduction)
        if not math.isfinite(reduced):
            violate(
                "metric/non-finite-reduction",
                f"metric {name!r} reduced to {reduced!r}",
                "record finite values whose Step reduction is also finite",
            )
        if definition.minimum is not None and reduced < definition.minimum:
            violate(
                "metric/bounds",
                f"metric {name!r} reduced to {reduced!r} below {definition.minimum!r}",
                "record observations whose reduced value satisfies the Metric Definition",
            )
        if definition.maximum is not None and reduced > definition.maximum:
            violate(
                "metric/bounds",
                f"metric {name!r} reduced to {reduced!r} above {definition.maximum!r}",
                "record observations whose reduced value satisfies the Metric Definition",
            )
        committed.append(MetricObservation(name, next_step, reduced))
    return tuple(committed)


def validate_metric_catalog(
    catalog: MetricCatalog,
    project_version: str,
    skywright_schema_identity: str,
) -> dict[str, MetricDefinition]:
    if not (catalog.project_identity and catalog.project_contract_digest):
        raise TrainingContractViolation(
            "metric-catalog/project-identity",
            "the Metric Catalog is missing its project identity or contract digest",
            "load the contract pinned by the exact Training Project Version",
        )
    if not (
        catalog.skywright_schema_identity
        and catalog.skywright_schema_digest
        and catalog.units
    ):
        _raise_catalog_failure(
            "metric-catalog/schema-identity",
            "the composed catalog is missing its Skywright schema identity, digest, or unit registry",
            "load the complete pinned Skywright Metric Schema",
        )
    if catalog.project_identity != project_version:
        raise TrainingContractViolation(
            "metric-catalog/project-identity",
            f"Metric Catalog project {catalog.project_identity!r} does not match {project_version!r}",
            "use the catalog composed for the exact Training Project Version",
        )
    if catalog.skywright_schema_identity != skywright_schema_identity:
        _raise_catalog_failure(
            "metric-catalog/schema-identity",
            f"Metric Catalog schema {catalog.skywright_schema_identity!r} does not match {skywright_schema_identity!r}",
            "compose with the exact Skywright Metric Schema pinned by the Run Definition",
        )
    project = _validate_metric_definitions(catalog.project_definitions, system=False)
    try:
        system = _validate_metric_definitions(catalog.system_definitions, system=True)
    except TrainingContractViolation as failure:
        raise SkywrightFailure(failure, "construction") from failure
    overlap = set(project).intersection(system)
    if overlap:
        _raise_catalog_failure(
            "metric-catalog/duplicate",
            f"Metric Catalog contains duplicate names {sorted(overlap)!r}",
            "compose exactly one Metric Definition for each canonical name",
        )
    unknown_project_units = {
        definition.unit
        for definition in catalog.project_definitions
        if definition.unit not in catalog.units
    }
    if unknown_project_units:
        raise TrainingContractViolation(
            "metric-catalog/unit-registry",
            f"Project Metric Contract uses units outside the pinned registry {sorted(unknown_project_units)!r}",
            "declare only units from the versioned Skywright Metric Schema",
        )
    unknown_system_units = {
        definition.unit
        for definition in catalog.system_definitions
        if definition.unit not in catalog.units
    }
    if unknown_system_units:
        failure = TrainingContractViolation(
            "metric-catalog/system-unit-registry",
            f"Skywright Metric Schema uses unknown units {sorted(unknown_system_units)!r}",
            "publish a valid internally consistent Skywright Metric Schema",
        )
        raise SkywrightFailure(failure, "construction")
    return project


def _raise_catalog_failure(rule: str, problem: str, guidance: str) -> NoReturn:
    failure = TrainingContractViolation(rule, problem, guidance)
    raise SkywrightFailure(failure, "construction")


def _validate_metric_definitions(
    definitions: Iterable[MetricDefinition], *, system: bool
) -> dict[str, MetricDefinition]:
    catalog: dict[str, MetricDefinition] = {}
    for definition in definitions:
        if not system and definition.name.startswith("skywright/"):
            raise TrainingContractViolation(
                "metric-definition/reserved-name",
                f"project metric {definition.name!r} uses the skywright/ namespace",
                "declare project metrics outside the library-owned namespace",
            )
        if system and not definition.name.startswith("skywright/system/"):
            raise TrainingContractViolation(
                "metric-definition/system-name",
                f"system metric {definition.name!r} is outside the skywright/ namespace",
                "declare library-owned metrics under skywright/system/",
            )
        if not system and definition.recording_basis != "step":
            raise TrainingContractViolation(
                "metric-definition/project-basis",
                f"project metric {definition.name!r} is not Step-based",
                "declare project metrics with the step Recording Basis",
            )
        if re.fullmatch(r"[a-z][a-z0-9_]*(/[a-z][a-z0-9_]*)+", definition.name) is None:
            raise TrainingContractViolation(
                "metric-definition/name",
                f"project metric name {definition.name!r} is not lowercase slash-separated",
                "use a canonical name such as train/loss",
            )
        if definition.name in catalog:
            raise TrainingContractViolation(
                "metric-definition/duplicate",
                f"project metric {definition.name!r} is declared more than once",
                "publish exactly one definition for each canonical name",
            )
        if definition.numeric_kind not in ("real", "integer"):
            raise TrainingContractViolation(
                "metric-definition/numeric-kind",
                f"metric {definition.name!r} has unknown numeric kind {definition.numeric_kind!r}",
                "use real or integer",
            )
        if definition.comparison not in ("minimize", "maximize", "none"):
            raise TrainingContractViolation(
                "metric-definition/comparison",
                f"metric {definition.name!r} has unknown comparison {definition.comparison!r}",
                "use minimize, maximize, or none",
            )
        if definition.recording_basis not in ("step", "wall_time"):
            raise TrainingContractViolation(
                "metric-definition/recording-basis",
                f"metric {definition.name!r} has unknown Recording Basis {definition.recording_basis!r}",
                "use step or wall_time",
            )
        if (
            definition.recording_basis == "wall_time"
            and definition.step_reduction is not None
        ):
            raise TrainingContractViolation(
                "metric-definition/wall-time-reduction",
                f"wall-time metric {definition.name!r} declares a Step Reduction",
                "omit step_reduction for wall-time metrics",
            )
        if definition.recording_basis == "step" and definition.step_reduction not in (
            "mean",
            "sum",
            "min",
            "max",
            "last",
        ):
            raise TrainingContractViolation(
                "metric-definition/reduction",
                f"metric {definition.name!r} has unknown Step Reduction {definition.step_reduction!r}",
                "use mean, sum, min, max, or last",
            )
        if (
            definition.recording_basis == "step"
            and definition.numeric_kind == "integer"
            and definition.step_reduction == "mean"
        ):
            raise TrainingContractViolation(
                "metric-definition/integer-mean",
                f"integer metric {definition.name!r} uses mean reduction",
                "declare a real metric or choose a reduction that preserves integers",
            )
        if not definition.unit:
            raise TrainingContractViolation(
                "metric-definition/unit",
                f"metric {definition.name!r} has no Metric Unit",
                "use a unit from the pinned Skywright Metric Schema",
            )
        for bound_name, bound in (
            ("minimum", definition.minimum),
            ("maximum", definition.maximum),
        ):
            if bound is not None and (
                type(bound) not in (int, float) or not math.isfinite(bound)
            ):
                raise TrainingContractViolation(
                    "metric-definition/bounds",
                    f"metric {definition.name!r} has invalid {bound_name} {bound!r}",
                    "declare finite numeric bounds",
                )
        if (
            definition.minimum is not None
            and definition.maximum is not None
            and definition.minimum > definition.maximum
        ):
            raise TrainingContractViolation(
                "metric-definition/bounds",
                f"metric {definition.name!r} has minimum above maximum",
                "declare an ordered bounds interval",
            )
        catalog[definition.name] = definition
    return catalog
