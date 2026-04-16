package de.valentinlehmann.client;

import de.valentinlehmann.VLCPlaybackPayload;
import de.valentinlehmann.VLCScreenPayload;
import de.valentinlehmann.VLCSeekPayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
//? if newRendering {
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
//?} else {
/*import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;*/
//?}
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Client entrypoint. Spins up the VLC player (idle — no stream until the
 * server tells us what to play), wires up the render hook, and listens for
 * server-pushed {@link VLCScreenPayload}/{@link VLCPlaybackPayload} packets.
 * Commands are server-side now, so this module is purely a receiver.
 */
public class VLCStreamClient implements ClientModInitializer {

	public static final Logger LOGGER = LoggerFactory.getLogger("vlc-stream");

	@Override
	public void onInitializeClient() {
		LOGGER.info("VLC Stream client initializing");

		// Read persisted settings (volume slider) before VLC starts so the
		// first audio frame is already at the user's preferred level.
		VLCStreamConfig.load();

		// Defer VLC init until the client is fully started — libVLC can take a
		// moment to locate its native runtime. We initialise the player but
		// do NOT open a stream; playback begins only when the server tells us.
		ClientLifecycleEvents.CLIENT_STARTED.register(client -> {
			try {
				VLCPlayerManager.start();
			} catch (Throwable t) {
				LOGGER.error("Failed to start VLC player. Is VLC installed on this machine?", t);
			}
		});

		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> VLCPlayerManager.shutdown());

		// Stop the stream and reset screen config whenever the player leaves a
		// world (disconnect from server, close singleplayer world, switch worlds).
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			VLCPlayerManager.stopPlayback();
			VLCScreenState.reset();
		});

		// Server → client: full screen config (position, size, rotation, url,
		// hearing distance, enabled flag). Replaces the local config wholesale.
		ClientPlayNetworking.registerGlobalReceiver(VLCScreenPayload.TYPE, (payload, ctx) -> {
			VLCScreenState.set(new VLCScreenState.Config(
					payload.x(), payload.y(), payload.z(),
					payload.width(), payload.height(),
					payload.yaw(), payload.pitch(), payload.roll(),
					payload.hearingDistance(),
					payload.enabled()));

			// Empty URL means "don't touch the URL" — lets the server move /
			// resize / orient the screen without restarting playback.
			String url = payload.url();
			if (!url.isEmpty()) {
				VLCPlayerManager.setUrl(url);
			}

			if (!payload.enabled()) {
				VLCPlayerManager.stopPlayback();
			}
		});

		// Server → client: transport controls (play/pause/stop).
		ClientPlayNetworking.registerGlobalReceiver(VLCPlaybackPayload.TYPE, (payload, ctx) -> {
			switch (payload.action()) {
				case PLAY  -> VLCPlayerManager.resume();
				case PAUSE -> VLCPlayerManager.pause();
				case STOP  -> VLCPlayerManager.stopPlayback();
			}
		});

		// Server → client: seek to an absolute timestamp.
		ClientPlayNetworking.registerGlobalReceiver(VLCSeekPayload.TYPE, (payload, ctx) -> {
			VLCPlayerManager.seek(payload.timeMs());
		});

		WorldRenderEvents.AFTER_ENTITIES.register(VLCScreenRenderer::render);
	}
}
