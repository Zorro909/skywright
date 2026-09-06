package de.zorro909.skywright.backend.runsubmission;

/**
 * Stops after the real acceptance transaction commits, before the HTTP owner dispatches.
 */
public final class AcceptedRunCrashFixture {

	public static AcceptedRun commitWithoutDispatch(RunAcceptanceStore store, LocalRunAdmission admission,
			LocalRunRequest request) {
		var created = store.accept(request, LocalRunSubmissions.digest(request), admission);
		created.prepared().close();
		return created.run();
	}

}
