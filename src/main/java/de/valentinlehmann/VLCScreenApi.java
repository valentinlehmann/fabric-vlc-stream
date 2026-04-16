package de.valentinlehmann;

import net.minecraft.server.MinecraftServer;

/**
 * Public server-side API for other mods that want to control the shared VLC
 * stream programmatically. Every method mutates the authoritative
 * {@link VLCServerState}, persists it via {@link VLCServerConfig#save}, and
 * broadcasts the new config (or a playback transport message) to all
 * connected clients — identical to running the {@code /vlc …} commands.
 *
 * <p>Per-player configuration is intentionally not supported: the server
 * keeps a single shared stream, and everyone connected sees the same screen.
 * Clients without {@code vlc-stream} installed simply ignore the packets.
 */
public final class VLCScreenApi {

	public static final float DEFAULT_HEARING_DISTANCE = 16.0f;

	private VLCScreenApi() {}

	/** Change the stream URL for everyone and mark the screen enabled. */
	public static void setUrl(MinecraftServer server, String url) {
		mutate(server, c -> c.withUrl(url == null ? "" : url).withEnabled(true));
	}

	public static void setPosition(MinecraftServer server, double x, double y, double z) {
		mutate(server, c -> c.withPos(x, y, z));
	}

	public static void setSize(MinecraftServer server, float width, float height) {
		mutate(server, c -> c.withSize(width, height));
	}

	public static void setRotation(MinecraftServer server, float yaw, float pitch, float roll) {
		mutate(server, c -> c.withRotation(yaw, pitch, roll));
	}

	public static void setHearingDistance(MinecraftServer server, float hearingDistance) {
		mutate(server, c -> c.withHearingDistance(hearingDistance));
	}

	public static void setEnabled(MinecraftServer server, boolean enabled) {
		mutate(server, c -> c.withEnabled(enabled));
	}

	/** Apply an arbitrary transform to the config. */
	public static void mutate(MinecraftServer server, java.util.function.UnaryOperator<VLCServerState.Config> fn) {
		synchronized (VLCServerState.class) {
			VLCServerState.set(fn.apply(VLCServerState.get()));
		}
		VLCServerConfig.save();
		VLCServerState.broadcast(server);
	}

	public static void play(MinecraftServer server) {
		VLCServerState.broadcastPlayback(server, VLCPlaybackPayload.Action.PLAY);
	}

	public static void pause(MinecraftServer server) {
		VLCServerState.broadcastPlayback(server, VLCPlaybackPayload.Action.PAUSE);
	}

	public static void stop(MinecraftServer server) {
		VLCServerState.broadcastPlayback(server, VLCPlaybackPayload.Action.STOP);
	}

	/** Seek to an absolute millisecond offset on every connected client. */
	public static void seek(MinecraftServer server, long timeMs) {
		VLCServerState.broadcastSeek(server, timeMs);
	}
}
