package com.testmod.client;

import com.testmod.expression.ExpressionSnapshot;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

final class ClientExpressionStore {
	private static final long EXPIRATION_MILLIS = 3500L;
	private static final long NO_FACE_GRACE_MILLIS = 350L;
	private static final ConcurrentMap<UUID, TrackedExpression> STATES = new ConcurrentHashMap<>();

	private ClientExpressionStore() {
	}

	static void apply(UUID playerId, ExpressionSnapshot snapshot) {
		if (playerId == null) {
			return;
		}

		if (snapshot == null || !snapshot.renderable()) {
			TrackedExpression tracked = STATES.get(playerId);
			if (tracked != null && System.currentTimeMillis() - tracked.receivedAtMillis() <= NO_FACE_GRACE_MILLIS) {
				return;
			}
			STATES.remove(playerId);
			return;
		}

		STATES.put(playerId, new TrackedExpression(snapshot, System.currentTimeMillis()));
	}

	static Optional<ExpressionSnapshot> get(UUID playerId) {
		TrackedExpression tracked = STATES.get(playerId);
		if (tracked == null) {
			return Optional.empty();
		}

		long now = System.currentTimeMillis();
		if (now - tracked.receivedAtMillis() > EXPIRATION_MILLIS) {
			STATES.remove(playerId, tracked);
			return Optional.empty();
		}

		return Optional.of(tracked.snapshot());
	}

	static void clear() {
		STATES.clear();
	}

	private record TrackedExpression(ExpressionSnapshot snapshot, long receivedAtMillis) {
	}
}
