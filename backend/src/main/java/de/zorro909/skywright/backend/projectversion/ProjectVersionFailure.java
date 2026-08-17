package de.zorro909.skywright.backend.projectversion;

/** One stable reason a selected Training Project Version is not runnable. */
public record ProjectVersionFailure(String code, String pointer) implements Comparable<ProjectVersionFailure> {

	@Override
	public int compareTo(ProjectVersionFailure other) {
		int pointerOrder = this.pointer.compareTo(other.pointer);
		return pointerOrder == 0 ? this.code.compareTo(other.code) : pointerOrder;
	}

}
