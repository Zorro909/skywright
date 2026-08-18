package de.zorro909.skywright.backend.runstore;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Post-hoc Run Store discovery, reference resolution, integrity, and immutable downloads.
 */
public final class RunStoreAccess {

	private static final Pattern OUTPUT = Pattern
		.compile("(artifacts|samples)/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/([0-9]{19})/(.+)");

	private final RunStoreProtocol protocol;

	private final RunStoreObjectStore objects;

	public RunStoreAccess(RunStoreProtocol protocol, RunStoreObjectStore objects) {
		this.protocol = protocol;
		this.objects = objects;
	}

	public List<RunStoreOutput> listOutputs() {
		List<RunStoreOutput> outputs = new ArrayList<>();
		for (RunStoreObject object : this.objects.list(this.protocol.runPrefix())) {
			String suffix = object.key().substring(this.protocol.runPrefix().length());
			Matcher match = OUTPUT.matcher(suffix);
			if (!match.matches()) {
				continue;
			}
			validate(object);
			String kind = match.group(1).equals("artifacts") ? "artifact" : "sample";
			if (!kind.equals(object.metadata().get("skywright-kind"))) {
				throw new RunStoreIntegrityException("RUN_STORE_METADATA_MISMATCH: object kind differs from key");
			}
			outputs.add(new RunStoreOutput(kind, Long.parseLong(match.group(2)), decode(match.group(3)), object.key(),
					object.bytes().length, object.contentType(), object.metadata().get("skywright-sha256")));
		}
		outputs.sort(Comparator.comparingLong(RunStoreOutput::step)
			.thenComparing(RunStoreOutput::kind)
			.thenComparing(RunStoreOutput::name));
		return List.copyOf(outputs);
	}

	public RunStoreObject resolveCheckpoint(String reference) {
		CheckpointReference checkpoint = CheckpointReference.parse(reference);
		RunStoreObject object = require(this.protocol.checkpointKey(checkpoint.step(), checkpoint.digest()));
		validate(object);
		if (!checkpoint.digest().equals(object.metadata().get("skywright-sha256"))) {
			throw new RunStoreIntegrityException("RUN_STORE_DIGEST_MISMATCH: reference differs from object");
		}
		return object;
	}

	public URI presignDownload(String key, int expiresInSeconds) {
		String prefix = this.protocol.runPrefix();
		if (!(key.startsWith(prefix + "checkpoints/") || key.startsWith(prefix + "artifacts/")
				|| key.startsWith(prefix + "samples/")) || expiresInSeconds < 1 || expiresInSeconds > 3600) {
			throw new IllegalArgumentException("only exact immutable outputs can be presigned for 1..3600 seconds");
		}
		validate(require(key));
		return this.objects.presignGet(key, expiresInSeconds);
	}

	private RunStoreObject require(String key) {
		RunStoreObject object = this.objects.get(key);
		if (object == null) {
			throw new RunStoreIntegrityException("RUN_STORE_MISSING_OBJECT: " + key);
		}
		return object;
	}

	private static void validate(RunStoreObject object) {
		Map<String, String> metadata = object.metadata();
		byte[] bytes = object.bytes();
		if (!Long.toString(bytes.length).equals(metadata.get("skywright-size"))
				|| !sha256(bytes).equals(metadata.get("skywright-sha256"))
				|| !"v1".equals(metadata.get("skywright-schema"))) {
			throw new RunStoreIntegrityException("RUN_STORE_DIGEST_MISMATCH: " + object.key());
		}
	}

	private static String sha256(byte[] bytes) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
		}
		catch (NoSuchAlgorithmException impossible) {
			throw new IllegalStateException(impossible);
		}
	}

	private static String decode(String value) {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		for (int index = 0; index < value.length();) {
			char character = value.charAt(index);
			if (character == '%') {
				if (index + 2 >= value.length() || !isUpperHex(value.charAt(index + 1))
						|| !isUpperHex(value.charAt(index + 2))) {
					throw new RunStoreIntegrityException("RUN_STORE_INVALID_KEY: noncanonical percent encoding");
				}
				bytes.write(Integer.parseInt(value.substring(index + 1, index + 3), 16));
				index += 3;
			}
			else if (character <= 0x7f && isUnreserved(character)) {
				bytes.write(character);
				index++;
			}
			else {
				throw new RunStoreIntegrityException("RUN_STORE_INVALID_KEY: raw reserved or non-ASCII output name");
			}
		}
		return StandardCharsets.UTF_8.decode(java.nio.ByteBuffer.wrap(bytes.toByteArray())).toString();
	}

	private static boolean isUpperHex(char value) {
		return value >= '0' && value <= '9' || value >= 'A' && value <= 'F';
	}

	private static boolean isUnreserved(char value) {
		return value >= 'A' && value <= 'Z' || value >= 'a' && value <= 'z' || value >= '0' && value <= '9'
				|| value == '-' || value == '.' || value == '_' || value == '~';
	}

}
