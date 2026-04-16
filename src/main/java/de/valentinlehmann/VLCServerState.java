package de.valentinlehmann;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * Authoritative server-side state for the VLC screen — a single shared
 * configuration that every connected client renders. Commands mutate it,
 * joining players are caught up with a fresh payload, and every mutation
 * broadcasts to everyone online.
 *
 * <p>All mutators are synchronized — commands and connection events can fire
 * on different threads, and the state is small enough that coarse locking is
 * cheaper than per-field concurrency.
 */
public final class VLCServerState {

	public record Config(
			double x, double y, double z,
			float width, float height,
			float yaw, float pitch, float roll,
			String url,
			float hearingDistance,
			boolean enabled) {

		public Config withPos(double x, double y, double z) {
			return new Config(x, y, z, width, height, yaw, pitch, roll, url, hearingDistance, enabled);
		}

		public Config withSize(float width, float height) {
			return new Config(x, y, z, width, height, yaw, pitch, roll, url, hearingDistance, enabled);
		}

		public Config withRotation(float yaw, float pitch, float roll) {
			return new Config(x, y, z, width, height, yaw, pitch, roll, url, hearingDistance, enabled);
		}

		public Config withUrl(String url) {
			return new Config(x, y, z, width, height, yaw, pitch, roll, url, hearingDistance, enabled);
		}

		public Config withHearingDistance(float hearingDistance) {
			return new Config(x, y, z, width, height, yaw, pitch, roll, url, hearingDistance, enabled);
		}

		public Config withEnabled(boolean enabled) {
			return new Config(x, y, z, width, height, yaw, pitch, roll, url, hearingDistance, enabled);
		}

		/** Build the wire payload for this config. The {@code sendUrl} flag
		 *  controls whether the URL is actually transmitted (empty string
		 *  means "don't change" to the client). */
		public VLCScreenPayload toPayload(boolean sendUrl) {
			return new VLCScreenPayload(
					x, y, z, width, height,
					yaw, pitch, roll,
					sendUrl ? url : "",
					hearingDistance, enabled);
		}
	}

	/** Matches the client-side defaults before the first command runs. */
	public static final Config DEFAULT =
			new Config(0.5, 80.0, 0.5, 16.0f, 9.0f, 0f, 0f, 0f, "", 16.0f, false);

	private static Config current = DEFAULT;

	private VLCServerState() {}

	public static synchronized Config get() {
		return current;
	}

	public static synchronized void set(Config config) {
		current = config;
	}

	/** Broadcast the current config (with its URL) to every connected player. */
	public static synchronized void broadcast(MinecraftServer server) {
		VLCScreenPayload payload = current.toPayload(true);
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			if (ServerPlayNetworking.canSend(p, VLCScreenPayload.TYPE)) {
				ServerPlayNetworking.send(p, payload);
			}
		}
	}

	/** Send the current config to one player — used on join. */
	public static synchronized void sendTo(ServerPlayer player) {
		if (ServerPlayNetworking.canSend(player, VLCScreenPayload.TYPE)) {
			ServerPlayNetworking.send(player, current.toPayload(true));
		}
	}

	/** Broadcast a playback transport action (play/pause/stop) to everyone. */
	public static void broadcastPlayback(MinecraftServer server, VLCPlaybackPayload.Action action) {
		VLCPlaybackPayload payload = new VLCPlaybackPayload(action);
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			if (ServerPlayNetworking.canSend(p, VLCPlaybackPayload.TYPE)) {
				ServerPlayNetworking.send(p, payload);
			}
		}
	}

	/** Broadcast a seek to an absolute millisecond offset to everyone. */
	public static void broadcastSeek(MinecraftServer server, long timeMs) {
		VLCSeekPayload payload = new VLCSeekPayload(timeMs);
		for (ServerPlayer p : server.getPlayerList().getPlayers()) {
			if (ServerPlayNetworking.canSend(p, VLCSeekPayload.TYPE)) {
				ServerPlayNetworking.send(p, payload);
			}
		}
	}
}
