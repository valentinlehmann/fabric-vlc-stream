package de.valentinlehmann.client;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;

/**
 * Client-side snapshot of where/how to draw the screen and how its audio
 * behaves in the world. Writers (network receiver, commands) replace the
 * immutable {@link Config} atomically; the renderer and audio mixer read a
 * consistent snapshot each frame with no locking.
 */
public final class VLCScreenState {

	public record Config(
			double x, double y, double z,
			float width, float height,
			float yaw, float pitch, float roll,
			float hearingDistance,
			boolean enabled) {

		public Config withPos(double x, double y, double z) {
			return new Config(x, y, z, width, height, yaw, pitch, roll, hearingDistance, enabled);
		}

		public Config withSize(float width, float height) {
			return new Config(x, y, z, width, height, yaw, pitch, roll, hearingDistance, enabled);
		}

		public Config withRotation(float yaw, float pitch, float roll) {
			return new Config(x, y, z, width, height, yaw, pitch, roll, hearingDistance, enabled);
		}

		public Config withHearingDistance(float hearingDistance) {
			return new Config(x, y, z, width, height, yaw, pitch, roll, hearingDistance, enabled);
		}

		public Config withEnabled(boolean enabled) {
			return new Config(x, y, z, width, height, yaw, pitch, roll, hearingDistance, enabled);
		}
	}

	/** Defaults before the server sends anything. */
	private static final Config DEFAULT =
			new Config(0.5, 80.0, 0.5, 16.0f, 9.0f, 0f, 0f, 0f, 16.0f, true);

	private static final AtomicReference<Config> CURRENT = new AtomicReference<>(DEFAULT);

	private VLCScreenState() {}

	public static Config get() {
		return CURRENT.get();
	}

	public static void set(Config config) {
		CURRENT.set(config);
	}

	/** Compare-and-set update — safe against concurrent writers. */
	public static void update(UnaryOperator<Config> fn) {
		CURRENT.updateAndGet(fn);
	}

	/** Reset to defaults — called on world/server disconnect. */
	public static void reset() {
		CURRENT.set(DEFAULT);
	}
}
