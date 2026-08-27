package de.zorro909.skywright.backend.datasetpublication;

interface DatasetPublicationCleanupGate {

	void await(DatasetPublicationView publication, boolean operationOnly);

}
