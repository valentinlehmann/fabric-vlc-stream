package de.valentinlehmann;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class VLCStream implements ModInitializer {
	public static final String MOD_ID = "vlc-stream";

	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		// Register S2C payload types on both sides (the registry is shared).
		// Must happen before any player connects.
		PayloadTypeRegistry.playS2C().register(VLCScreenPayload.TYPE, VLCScreenPayload.CODEC);
		PayloadTypeRegistry.playS2C().register(VLCPlaybackPayload.TYPE, VLCPlaybackPayload.CODEC);
		PayloadTypeRegistry.playS2C().register(VLCSeekPayload.TYPE, VLCSeekPayload.CODEC);

		// Load persisted server config once the server starts — before any
		// player gets a chance to join.
		ServerLifecycleEvents.SERVER_STARTING.register(server -> VLCServerConfig.load());

		// Bring each joining player up to speed with the current screen
		// config. If the stream is already playing, they'll also need a PLAY
		// transport so their client actually begins rendering.
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			VLCServerState.sendTo(handler.getPlayer());
			VLCServerState.Config cfg = VLCServerState.get();
			if (cfg.enabled() && !cfg.url().isEmpty()) {
				sender.sendPacket(new VLCPlaybackPayload(VLCPlaybackPayload.Action.PLAY));
			}
		});

		VLCServerCommands.register();

		LOGGER.info("vlc-stream server ready");
	}
}
