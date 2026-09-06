package de.zorro909.skywright.backend.runstore;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
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

	public RunStoreOutputPage listOutputs(RunStoreOutputKind kind, int limit, String continuation) {
		if (limit < 1 || limit > 1000) {
			throw new IllegalArgumentException("page limit must be 1..1000");
		}
		String prefix = this.protocol.runPrefix() + kind.keySegment() + "/";
		RunStoreObjectPage page = this.objects.list(prefix, limit, continuation);
		if (page.entries().size() > limit) {
			throw new RunStoreIntegrityException("RUN_STORE_INVALID_PAGE");
		}
		var outputs = new ArrayList<RunStoreOutput>();
		for (RunStoreObjectPage.Entry entry : page.entries()) {
			if (!entry.key().startsWith(prefix)) {
				throw new RunStoreIntegrityException("RUN_STORE_INVALID_KEY");
			}
			RunStoreObjectMetadata object = requireMetadata(entry.key());
			validateIdentity(object);
			if (entry.size() != object.size()) {
				throw new RunStoreIntegrityException("RUN_STORE_METADATA_MISMATCH");
			}
			Matcher match = OUTPUT.matcher(object.key().substring(this.protocol.runPrefix().length()));
			if (!match.matches()) {
				throw new RunStoreIntegrityException("RUN_STORE_INVALID_KEY");
			}
			outputs.add(new RunStoreOutput(kind, Long.parseLong(match.group(2)), decode(match.group(3)), object.key(),
					object.size(), object.contentType(), object.metadata().get("skywright-sha256")));
		}
		return new RunStoreOutputPage(outputs, page.continuation());
	}

	/**
	 * Resolves immutable identity at the injected current location without reading State.
	 */
	public RunStoreObjectMetadata resolveCheckpoint(String reference) {
		CheckpointReference checkpoint = CheckpointReference.parse(reference);
		RunStoreObjectMetadata object = requireMetadata(
				this.protocol.checkpointKey(checkpoint.step(), checkpoint.digest()));
		validateIdentity(object);
		return object;
	}

	/**
	 * Accepts bytes only after complete size and digest verification; owns one staged
	 * file.
	 */
	public VerifiedRunStoreObject stageDownload(String key, Path directory, long maxBytes) {
		if (maxBytes < 1) {
			throw new IllegalArgumentException("maxBytes must be positive");
		}
		validateKey(key);
		Path path = null;
		try {
			VerifiedRunStoreObject result;
			try (RunStoreContent content = this.objects.open(key)) {
				if (content == null) {
					throw new RunStoreIntegrityException("RUN_STORE_MISSING_OBJECT: " + key);
				}
				RunStoreObjectMetadata descriptor = content.descriptor();
				if (!descriptor.key().equals(key)) {
					throw new RunStoreIntegrityException("RUN_STORE_INVALID_KEY");
				}
				validateIdentity(descriptor);
				if (descriptor.size() > maxBytes
						|| descriptor.size() > Files.getFileStore(directory).getUsableSpace()) {
					throw new RunStoreIntegrityException("RUN_STORE_STAGING_BUDGET: object exceeds disk budget");
				}
				path = Files.createTempFile(directory, "skywright-read-", ".verified");
				MessageDigest digest = newDigest();
				long consumed = 0;
				try (var output = Files.newOutputStream(path)) {
					byte[] buffer = new byte[1024 * 1024];
					int count;
					while ((count = content.stream()
						.read(buffer, 0, (int) Math.min(buffer.length, descriptor.size() - consumed + 1))) != -1) {
						consumed += count;
						if (consumed > descriptor.size()) {
							throw new RunStoreIntegrityException("RUN_STORE_DIGEST_MISMATCH");
						}
						digest.update(buffer, 0, count);
						output.write(buffer, 0, count);
					}
				}
				if (consumed != descriptor.size() || !HexFormat.of()
					.formatHex(digest.digest())
					.equals(descriptor.metadata().get("skywright-sha256"))) {
					throw new RunStoreIntegrityException("RUN_STORE_DIGEST_MISMATCH: " + key);
				}
				result = new VerifiedRunStoreObject(path, descriptor);
				content.accept();
			}
			path = null;
			return result;
		}
		catch (IOException failure) {
			throw new UncheckedIOException(failure);
		}
		finally {
			if (path != null) {
				try {
					Files.deleteIfExists(path);
				}
				catch (IOException failure) {
					throw new UncheckedIOException(failure);
				}
			}
		}
	}

	public ProgressRecord readProgress() {
		RunStoreObject object = require(this.protocol.progressKey());
		validate(object);
		if (!"progress-record".equals(object.metadata().get("skywright-kind"))) {
			throw new RunStoreIntegrityException("RUN_STORE_METADATA_MISMATCH: expected Progress Record");
		}
		ProgressRecord progress = ProgressRecord.decode(object.bytes());
		if (!this.protocol.runId().equals(progress.runId())) {
			throw new RunStoreIntegrityException("RUN_STORE_WRONG_RUN: Progress Record belongs to another Run");
		}
		return progress;
	}

	public RunStoreDownloadLink presignDownload(String key, int expiresInSeconds) {
		if (expiresInSeconds < 1 || expiresInSeconds > 3600) {
			throw new IllegalArgumentException("downloads expire within 1..3600 seconds");
		}
		validateKey(key);
		RunStoreObjectMetadata object = requireMetadata(key);
		validateIdentity(object);
		String contentType = object.metadata().getOrDefault("skywright-media-type", object.contentType());
		String filename = decode(key.substring(key.lastIndexOf('/') + 1));
		filename = filename.substring(filename.lastIndexOf('/') + 1);
		if (filename.isBlank() || filename.chars().anyMatch(character -> character < 32)) {
			filename = "skywright-output";
		}
		return new RunStoreDownloadLink(this.objects.presignGet(key, expiresInSeconds, contentType, filename), key,
				object.size(), object.metadata().get("skywright-sha256"),
				RunStoreDownloadLink.Verification.NOT_RECORDED);
	}

	private RunStoreObjectMetadata requireMetadata(String key) {
		RunStoreObjectMetadata object = this.objects.head(key);
		if (object == null) {
			throw new RunStoreIntegrityException("RUN_STORE_MISSING_OBJECT: " + key);
		}
		if (!key.equals(object.key())) {
			throw new RunStoreIntegrityException("RUN_STORE_INVALID_KEY");
		}
		validateMetadata(object);
		return object;
	}

	private String validateKey(String key) {
		if (!key.startsWith(this.protocol.runPrefix())) {
			throw new RunStoreIntegrityException("RUN_STORE_INVALID_KEY");
		}
		String suffix = key.substring(this.protocol.runPrefix().length());
		Matcher checkpoint = Pattern.compile("checkpoints/([0-9]{19})/([0-9a-f]{64})\\.safetensors").matcher(suffix);
		if (checkpoint.matches()) {
			Long.parseLong(checkpoint.group(1));
			return "checkpoint";
		}
		Matcher output = OUTPUT.matcher(suffix);
		if (!output.matches()) {
			throw new RunStoreIntegrityException("RUN_STORE_INVALID_KEY");
		}
		Long.parseLong(output.group(2));
		String name = decode(output.group(3));
		if (name.isEmpty() || name.indexOf(0) >= 0 || !PercentCodec.encode(name).equals(output.group(3))) {
			throw new RunStoreIntegrityException("RUN_STORE_INVALID_KEY: noncanonical output name");
		}
		return RunStoreOutputKind.fromKeySegment(output.group(1)).metadataValue();
	}

	private void validateIdentity(RunStoreObjectMetadata object) {
		validateMetadata(object);
		String kind = validateKey(object.key());
		if (!kind.equals(object.metadata().get("skywright-kind"))) {
			throw new RunStoreIntegrityException("RUN_STORE_METADATA_MISMATCH: object kind differs from key");
		}
		if (kind.equals("checkpoint")) {
			String digest = object.key().substring(object.key().lastIndexOf('/') + 1).replace(".safetensors", "");
			if (!digest.equals(object.metadata().get("skywright-sha256"))) {
				throw new RunStoreIntegrityException("RUN_STORE_DIGEST_MISMATCH: reference differs from metadata");
			}
		}
	}

	private static void validateMetadata(RunStoreObjectMetadata object) {
		Map<String, String> metadata = object.metadata();
		if (object.size() < 0 || !Long.toString(object.size()).equals(metadata.get("skywright-size"))
				|| !metadata.getOrDefault("skywright-sha256", "").matches("[0-9a-f]{64}")
				|| !"v1".equals(metadata.get("skywright-schema"))) {
			throw new RunStoreIntegrityException("RUN_STORE_METADATA_MISMATCH: " + object.key());
		}
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

	private static MessageDigest newDigest() {
		try {
			return MessageDigest.getInstance("SHA-256");
		}
		catch (NoSuchAlgorithmException impossible) {
			throw new IllegalStateException(impossible);
		}
	}

	private static String sha256(byte[] bytes) {
		return HexFormat.of().formatHex(newDigest().digest(bytes));
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
		try {
			return StandardCharsets.UTF_8.newDecoder()
				.onMalformedInput(CodingErrorAction.REPORT)
				.decode(java.nio.ByteBuffer.wrap(bytes.toByteArray()))
				.toString();
		}
		catch (java.nio.charset.CharacterCodingException failure) {
			throw new RunStoreIntegrityException("RUN_STORE_INVALID_KEY: invalid UTF-8");
		}
	}

	private static boolean isUpperHex(char value) {
		return value >= '0' && value <= '9' || value >= 'A' && value <= 'F';
	}

	private static boolean isUnreserved(char value) {
		return value >= 'A' && value <= 'Z' || value >= 'a' && value <= 'z' || value >= '0' && value <= '9'
				|| value == '-' || value == '.' || value == '_' || value == '~';
	}

}
