package de.zorro909.skywright.backend.http;

import de.zorro909.skywright.backend.boundary.generated.api.SystemInformationApi;
import de.zorro909.skywright.backend.boundary.generated.model.SystemInformation;
import de.zorro909.skywright.backend.boundary.generated.model.SystemInformation.ApiVersionEnum;
import org.springframework.boot.info.BuildProperties;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SystemInformationHttpAdapter implements SystemInformationApi {

	private final SystemInformation systemInformation;

	SystemInformationHttpAdapter(BuildProperties buildProperties) {
		this.systemInformation = new SystemInformation(ApiVersionEnum._1_0_0, buildProperties.getVersion(),
				buildProperties.get("sourceRevision"));
	}

	@Override
	public ResponseEntity<SystemInformation> getSystemInformation() {
		return ResponseEntity.ok(systemInformation);
	}

}
