package de.zorro909.skywright.backend.target;

public record TargetIdentity(String value) {

	private static final String SEGMENT = "[A-Za-z0-9][A-Za-z0-9._-]*";

	private static final String PATTERN = SEGMENT + "(?:/" + SEGMENT + ")*";

	public TargetIdentity {
		if (!valid(value)) {
			throw new IllegalArgumentException("Target identity is invalid");
		}
	}

	public static boolean valid(String value) {
		return value != null && value.length() <= 255 && value.matches(PATTERN);
	}

	public static TargetIdentity fromBindingToken(String token) {
		if (token == null) {
			throw new IllegalArgumentException("Target binding token is invalid");
		}
		String value = token.replace("~1", "/").replace("~0", "~");
		TargetIdentity result = new TargetIdentity(value);
		if (!result.bindingToken().equals(token)) {
			throw new IllegalArgumentException("Target binding token is invalid");
		}
		return result;
	}

	public String bindingToken() {
		return this.value.replace("~", "~0").replace("/", "~1");
	}

	@Override
	public String toString() {
		return this.value;
	}

}
