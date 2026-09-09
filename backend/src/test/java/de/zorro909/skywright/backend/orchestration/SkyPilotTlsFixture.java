package de.zorro909.skywright.backend.orchestration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

/** Generates a short-lived test CA and a separately signed TLS server certificate. */
record SkyPilotTlsFixture(SSLContext context, Path authority) {

	private static final String PASSWORD = "qualification-only";

	static SkyPilotTlsFixture create(Path directory, boolean matchingHostname) throws Exception {
		Files.createDirectories(directory);
		var caStore = directory.resolve("authority.p12");
		var serverStore = directory.resolve("server.p12");
		var authority = directory.resolve("authority.pem");
		var request = directory.resolve("server.csr");
		var certificate = directory.resolve("server.pem");
		var names = matchingHostname ? "SAN=DNS:localhost,IP:127.0.0.1" : "SAN=DNS:wrong.invalid";
		keytool(directory, "-genkeypair", "-alias", "authority", "-keystore", caStore.toString(), "-dname",
				"CN=Skywright qualification CA", "-keyalg", "RSA", "-keysize", "2048", "-validity", "2", "-ext",
				"BC:critical=ca:true", "-ext", "KU:critical=keyCertSign,cRLSign");
		keytool(directory, "-exportcert", "-rfc", "-alias", "authority", "-keystore", caStore.toString(), "-file",
				authority.toString());
		keytool(directory, "-genkeypair", "-alias", "server", "-keystore", serverStore.toString(), "-dname",
				"CN=Skywright qualification server", "-keyalg", "RSA", "-keysize", "2048", "-validity", "2");
		keytool(directory, "-certreq", "-alias", "server", "-keystore", serverStore.toString(), "-file",
				request.toString());
		keytool(directory, "-gencert", "-rfc", "-alias", "authority", "-keystore", caStore.toString(), "-infile",
				request.toString(), "-outfile", certificate.toString(), "-validity", "2", "-ext", "BC=ca:false", "-ext",
				"KU:critical=digitalSignature,keyEncipherment", "-ext", "EKU=serverAuth", "-ext", names);
		keytool(directory, "-importcert", "-noprompt", "-alias", "authority", "-keystore", serverStore.toString(),
				"-file", authority.toString());
		keytool(directory, "-importcert", "-alias", "server", "-keystore", serverStore.toString(), "-file",
				certificate.toString());
		var store = KeyStore.getInstance("PKCS12");
		try (var input = Files.newInputStream(serverStore)) {
			store.load(input, PASSWORD.toCharArray());
		}
		var keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
		keys.init(store, PASSWORD.toCharArray());
		var context = SSLContext.getInstance("TLS");
		context.init(keys.getKeyManagers(), null, null);
		return new SkyPilotTlsFixture(context, authority);
	}

	void configureTrust(ProcessBuilder builder) {
		builder.environment().put("SSL_CERT_FILE", this.authority.toString());
		builder.environment().put("REQUESTS_CA_BUNDLE", this.authority.toString());
	}

	private static void keytool(Path directory, String... arguments) throws Exception {
		var command = new ArrayList<>(List.of("keytool"));
		command.addAll(List.of(arguments));
		command.addAll(List.of("-storetype", "PKCS12", "-storepass", PASSWORD));
		var log = directory.resolve("keytool.log");
		var process = new ProcessBuilder(command).redirectErrorStream(true)
			.redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()))
			.start();
		try {
			if (!process.waitFor(30, TimeUnit.SECONDS) || process.exitValue() != 0) {
				throw new IOException("TLS fixture certificate generation failed: " + Files.readString(log));
			}
		}
		finally {
			process.destroyForcibly();
			process.waitFor(5, TimeUnit.SECONDS);
		}
	}

}
