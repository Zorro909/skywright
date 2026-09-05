package de.zorro909.skywright.backend.credential;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Arrays;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "skywright.credentials.vault.bindings-file")
class VaultConfiguration {

	@Bean
	VaultBindings vaultBindings(@Value("${skywright.credentials.vault.address}") URI address,
			@Value("${skywright.credentials.vault.mount:skywright}") String mount,
			@Value("${skywright.credentials.vault.token-file}") Path tokenFile,
			@Value("${skywright.credentials.vault.bindings-file}") Path bindingsFile) {
		try {
			var bindings = JsonMapper.builder()
				.build()
				.readValue(Files.readString(bindingsFile), CredentialBinding[].class);
			return new VaultBindings(address, mount, tokenFile, Arrays.asList(bindings), Clock.systemUTC());
		}
		catch (Exception exception) {
			throw new IllegalArgumentException("Cannot load non-secret Vault binding configuration");
		}
	}

}
