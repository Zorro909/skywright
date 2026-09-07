package de.zorro909.skywright.backend.runtimeassembly;

import de.zorro909.skywright.backend.orchestration.OrchestratorTaskSpecification;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

/** Compiler-checked projection into the finite SkyPilot DTO contract. */
@Mapper(unmappedSourcePolicy = ReportingPolicy.ERROR, unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface LocalTaskMapper {

	@Mapping(target = "name", source = "jobName")
	@Mapping(target = "setup", source = "setupCommand")
	@Mapping(target = "run", source = "trainingCommand")
	@Mapping(target = "resources", source = "candidates")
	@Mapping(target = "environment", source = "variables")
	@Mapping(target = "runtimePullSecret", source = "pullSecret")
	@Mapping(target = "runtimePullNamespace", source = "pullNamespace")
	OrchestratorTaskSpecification map(LocalRuntimeProjection.TaskPlan plan);

}
