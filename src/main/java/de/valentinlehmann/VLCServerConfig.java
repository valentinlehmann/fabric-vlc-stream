package de.valentinlehmann;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Persistence for {@link VLCServerState}. Serialises the current config to a
 * plain {@code .properties} file in the server's config directory so the same
 * stream comes back after a restart and gets pushed to every player who
 * connects.
 */
public final class VLCServerConfig {

	private static final String FILE_NAME = "vlc-stream-server.properties";

	private VLCServerConfig() {}

	private static Path path() {
		return FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
	}

	/** Read the config from disk, or keep the default if nothing's saved. */
	public static void load() {
		Path p = path();
		if (!Files.exists(p)) return;
		try {
			Properties props = new Properties();
			try (var in = Files.newInputStream(p)) { props.load(in); }
			VLCServerState.Config def = VLCServerState.DEFAULT;
			VLCServerState.Config loaded = new VLCServerState.Config(
					getDouble(props, "x",      def.x()),
					getDouble(props, "y",      def.y()),
					getDouble(props, "z",      def.z()),
					getFloat (props, "width",  def.width()),
					getFloat (props, "height", def.height()),
					getFloat (props, "yaw",    def.yaw()),
					getFloat (props, "pitch",  def.pitch()),
					getFloat (props, "roll",   def.roll()),
					props.getProperty("url", def.url()),
					getFloat (props, "hearingDistance", def.hearingDistance()),
					Boolean.parseBoolean(props.getProperty("enabled", String.valueOf(def.enabled())))
			);
			VLCServerState.set(loaded);
			VLCStream.LOGGER.info("Loaded vlc-stream server config from {}", p);
		} catch (IOException e) {
			VLCStream.LOGGER.warn("Could not read {}", p, e);
		}
	}

	/** Write the current {@link VLCServerState} to disk. Safe to call often —
	 *  happens after every command. */
	public static void save() {
		Path p = path();
		try {
			Files.createDirectories(p.getParent());
			VLCServerState.Config c = VLCServerState.get();
			Properties props = new Properties();
			props.setProperty("x",               Double.toString(c.x()));
			props.setProperty("y",               Double.toString(c.y()));
			props.setProperty("z",               Double.toString(c.z()));
			props.setProperty("width",           Float.toString(c.width()));
			props.setProperty("height",          Float.toString(c.height()));
			props.setProperty("yaw",             Float.toString(c.yaw()));
			props.setProperty("pitch",           Float.toString(c.pitch()));
			props.setProperty("roll",            Float.toString(c.roll()));
			props.setProperty("url",             c.url());
			props.setProperty("hearingDistance", Float.toString(c.hearingDistance()));
			props.setProperty("enabled",         Boolean.toString(c.enabled()));
			try (var out = Files.newOutputStream(p)) {
				props.store(out, "vlc-stream server state");
			}
		} catch (IOException e) {
			VLCStream.LOGGER.warn("Could not write {}", p, e);
		}
	}

	private static double getDouble(Properties props, String key, double fallback) {
		String v = props.getProperty(key);
		if (v == null) return fallback;
		try { return Double.parseDouble(v); } catch (NumberFormatException e) { return fallback; }
	}

	private static float getFloat(Properties props, String key, float fallback) {
		String v = props.getProperty(key);
		if (v == null) return fallback;
		try { return Float.parseFloat(v); } catch (NumberFormatException e) { return fallback; }
	}
}
