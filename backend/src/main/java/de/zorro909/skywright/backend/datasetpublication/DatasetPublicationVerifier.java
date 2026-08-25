package de.zorro909.skywright.backend.datasetpublication;

interface DatasetPublicationVerifier {

	DatasetPublicationWorkerResult verify(DatasetPublicationView publication);

	default DatasetPublicationWorkerResult cleanup(DatasetPublicationView publication, boolean operationOnly) {
		throw new UnsupportedOperationException();
	}

}
