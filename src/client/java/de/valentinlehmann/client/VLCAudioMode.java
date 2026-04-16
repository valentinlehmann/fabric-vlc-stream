package de.valentinlehmann.client;

/**
 * Which audio pipeline the client uses.
 *
 * <ul>
 *   <li>{@link #DIRECT} — VLC plays straight through the OS audio stack (no
 *     callback). Highest quality, no JavaSound artifacts, but only
 *     distance-based volume attenuation is applied; stereo panning and the
 *     full spatial mix are skipped.</li>
 *   <li>{@link #SPATIAL} — Audio is routed through a JavaSound line so it can
 *     be spatialised per-sample (constant-power pan + distance roll-off).
 *     Gives a proper "screen in the world" effect but can introduce faint
 *     background hiss on some systems.</li>
 * </ul>
 *
 * <p>Switching modes requires restarting the stream because libVLC locks its
 * audio configuration on the first play().
 */
public enum VLCAudioMode {
	DIRECT,
	SPATIAL;

	public static VLCAudioMode parse(String s, VLCAudioMode fallback) {
		if (s == null) return fallback;
		try { return VLCAudioMode.valueOf(s.trim().toUpperCase(java.util.Locale.ROOT)); }
		catch (IllegalArgumentException e) { return fallback; }
	}
}
