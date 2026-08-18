package de.zorro909.skywright.backend.persistence.failure;

import java.io.EOFException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.IdentityHashMap;
import org.springframework.dao.CannotSerializeTransactionException;
import org.springframework.dao.DeadlockLoserDataAccessException;
import org.springframework.dao.TransientDataAccessResourceException;

/** Classifies only explicitly transient database failures as safe to retry. */
public final class DatabaseFailureClassifier {

	private DatabaseFailureClassifier() {
	}

	public static boolean isRecognizedTransient(Throwable failure) {
		var pending = new ArrayDeque<Throwable>();
		var seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
		pending.add(failure);
		while (!pending.isEmpty()) {
			var candidate = pending.removeFirst();
			if (!seen.add(candidate)) {
				continue;
			}
			if (candidate instanceof TransientDataAccessResourceException
					|| candidate instanceof CannotSerializeTransactionException
					|| candidate instanceof DeadlockLoserDataAccessException
					|| candidate instanceof SQLTransientConnectionException || candidate instanceof ConnectException
					|| candidate instanceof NoRouteToHostException || candidate instanceof SocketException
					|| candidate instanceof UnknownHostException || candidate instanceof EOFException) {
				return true;
			}
			if (candidate.getCause() != null) {
				pending.addLast(candidate.getCause());
			}
			if (candidate instanceof SQLException sqlException && sqlException.getNextException() != null) {
				pending.addLast(sqlException.getNextException());
			}
		}
		return false;
	}

}
