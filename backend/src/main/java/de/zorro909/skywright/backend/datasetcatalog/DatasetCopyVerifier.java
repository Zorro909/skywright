package de.zorro909.skywright.backend.datasetcatalog;

@FunctionalInterface
public interface DatasetCopyVerifier {

	void verify(DatasetDefinitionView definition, DatasetCopyView copy);

}
