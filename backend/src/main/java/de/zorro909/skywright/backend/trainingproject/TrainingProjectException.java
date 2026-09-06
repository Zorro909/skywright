package de.zorro909.skywright.backend.trainingproject;

public class TrainingProjectException extends RuntimeException {

	private static final long serialVersionUID = 1L;

	private final String code;

	TrainingProjectException(String code, String message) {
		super(message);
		this.code = code;
	}

	public String code() {
		return this.code;
	}

}
