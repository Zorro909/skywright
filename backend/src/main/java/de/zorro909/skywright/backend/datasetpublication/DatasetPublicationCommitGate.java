package de.zorro909.skywright.backend.datasetpublication;

import java.util.UUID;

interface DatasetPublicationCommitGate {

	void await(UUID datasetId);

}
