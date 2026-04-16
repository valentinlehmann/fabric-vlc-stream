package de.valentinlehmann.client;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Tiny flat-file config for settings that need to survive a client restart.
 * Currently: the VLC stream volume slider position.
 *
 * <p>Format is a one-line {@code key=value} text file — easier to read & edit
 * by hand than JSON and avoids pulling in Gson just for one field.
 */
public final class VLCStreamConfig {

	private static final String FILE_NAME = "vlc-stream.properties";
	private static final String KEY_VOLUME = "volume";
	private static final String KEY_SYNC_OFFSET_MS = "sync_offset_ms";
	private static final String KEY_AUDIO_MODE = "audio_mode";

	private VLCStreamConfig() {}

	private static Path configPath() {
		return FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
	}

	/** Load from disk (or use the default) and push values into the live managers. */
	public static void load() {
		Path p = configPath();
		if (!Files.exists(p)) {
			VLCAudioManager.setVolume(1.0f);
			VLCPlayerManager.setSyncOffsetMillis(0);
			VLCAudioManager.setMode(VLCAudioMode.DIRECT);
			return;
		}
		try {
			java.util.Properties props = new java.util.Properties();
			try (var in = Files.newInputStream(p)) { props.load(in); }
			String v = props.getProperty(KEY_VOLUME, "1.0");
			try {
				VLCAudioManager.setVolume(Float.parseFloat(v));
			} catch (NumberFormatException nfe) {
				VLCAudioManager.setVolume(1.0f);
			}
			String off = props.getProperty(KEY_SYNC_OFFSET_MS, "0");
			try {
				VLCPlayerManager.setSyncOffsetMillis(Integer.parseInt(off.trim()));
			} catch (NumberFormatException nfe) {
				VLCPlayerManager.setSyncOffsetMillis(0);
			}
			VLCAudioManager.setMode(
					VLCAudioMode.parse(props.getProperty(KEY_AUDIO_MODE), VLCAudioMode.DIRECT));
		} catch (IOException e) {
			VLCStreamClient.LOGGER.warn("Could not read {}", p, e);
		}
	}

	/** Persist the current volume, sync offset and audio mode to disk. */
	public static void save() {
		Path p = configPath();
		try {
			Files.createDirectories(p.getParent());
			java.util.Properties props = new java.util.Properties();
			props.setProperty(KEY_VOLUME, Float.toString(VLCAudioManager.getVolume()));
			props.setProperty(KEY_SYNC_OFFSET_MS, Integer.toString(VLCPlayerManager.getSyncOffsetMillis()));
			props.setProperty(KEY_AUDIO_MODE, VLCAudioManager.getMode().name());
			try (var out = Files.newOutputStream(p)) {
				props.store(out, "vlc-stream client settings");
			}
		} catch (IOException e) {
			VLCStreamClient.LOGGER.warn("Could not write {}", p, e);
		}
	}
}
