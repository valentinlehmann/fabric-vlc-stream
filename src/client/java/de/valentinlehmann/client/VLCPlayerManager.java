package de.valentinlehmann.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
//? if hasIdentifier {
import net.minecraft.resources.Identifier;
//?} else {
/*import net.minecraft.resources.ResourceLocation;*/
//?}
import org.lwjgl.system.MemoryUtil;
import uk.co.caprica.vlcj.factory.MediaPlayerFactory;
import uk.co.caprica.vlcj.player.base.MediaPlayer;
import uk.co.caprica.vlcj.player.embedded.EmbeddedMediaPlayer;
import uk.co.caprica.vlcj.player.embedded.videosurface.CallbackVideoSurface;
import uk.co.caprica.vlcj.player.embedded.videosurface.VideoSurfaceAdapters;
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormatCallback;
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.RenderCallback;
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.format.RV32BufferFormat;

import java.nio.ByteBuffer;

/**
 * Owns the {@link EmbeddedMediaPlayer}, the shared frame buffer that VLC's
 * native thread writes into, and the Minecraft {@link DynamicTexture} the
 * main render thread uploads into.
 *
 * <p>Pixel flow: VLC writes RV32 ({@code BGRA} little-endian) into its own
 * buffer -> render callback copies+swizzles into our {@link #frameBuffer} as
 * {@code RGBA} -> render thread memcpys that into the {@link NativeImage}'s
 * native memory and calls {@link DynamicTexture#upload()}.
 */
public final class VLCPlayerManager {

	// Internal decoding resolution. VLC scales/letterboxes the source video
	// into this buffer. Higher = sharper but more GPU upload per frame.
	// Full HD — 1920x1080 is ~8.3 MB per frame uploaded to the GPU at
	// whatever frame rate the source delivers.
	private static final int FRAME_WIDTH = 1920;
	private static final int FRAME_HEIGHT = 1080;
	private static final int FRAME_BYTES = FRAME_WIDTH * FRAME_HEIGHT * 4;
	private static final int FRAME_PIXELS = FRAME_WIDTH * FRAME_HEIGHT;

	/** Identifier the texture is registered under — used by the renderer. */
	//? if hasIdentifier {
	public static final Identifier TEXTURE_ID = Identifier.fromNamespaceAndPath("vlc-stream", "stream");
	//?} else {
	/*public static final ResourceLocation TEXTURE_ID = ResourceLocation.fromNamespaceAndPath("vlc-stream", "stream");*/
	//?}

	private static MediaPlayerFactory factory;
	private static EmbeddedMediaPlayer player;

	private static DynamicTexture dynamicTexture;
	private static NativeImage nativeImage;

	/**
	 * Staging buffer VLC's callback writes into (already byte-swizzled to RGBA).
	 * The render thread memcpys this into the NativeImage's pointer on upload.
	 */
	private static ByteBuffer frameBuffer;
	private static final Object frameLock = new Object();
	private static volatile boolean frameDirty = false;
	private static volatile boolean started = false;

	/**
	 * Additional audio-delay compensation in milliseconds, on top of the
	 * automatic JavaSound line latency. Positive values delay audio further
	 * (useful if video still looks ahead of audio after the automatic
	 * compensation); negative values advance audio relative to video.
	 * Persisted to the client config so users only tune once per machine.
	 */
	private static volatile int syncOffsetMillis = 0;

	private VLCPlayerManager() {}

	public static int getFrameWidth() { return FRAME_WIDTH; }
	public static int getFrameHeight() { return FRAME_HEIGHT; }

	private static String currentUrl;

	/**
	 * Creates the VLC player, allocates the Minecraft texture, and wires up
	 * the audio callback — but does not begin playback. Call {@link #setUrl}
	 * (or wait for a server payload) to actually start a stream.
	 *
	 * <p>Safe to call once, on the client thread.
	 */
	public static synchronized void start() {
		if (started) return;
		started = true;

		frameBuffer = ByteBuffer.allocateDirect(FRAME_BYTES);

		// Create the Minecraft-side texture on the render thread. ClientLifecycle
		// CLIENT_STARTED fires on the client thread which is also the render thread.
		nativeImage = new NativeImage(NativeImage.Format.RGBA, FRAME_WIDTH, FRAME_HEIGHT, false);
		//? if newTexture {
		dynamicTexture = new DynamicTexture(() -> "vlc-stream", nativeImage);
		//?} else {
		/*dynamicTexture = new DynamicTexture(nativeImage);*/
		//?}
		Minecraft.getInstance().getTextureManager().register(TEXTURE_ID, dynamicTexture);

		factory = new MediaPlayerFactory();
		player = factory.mediaPlayers().newEmbeddedMediaPlayer();

		CallbackVideoSurface surface = new CallbackVideoSurface(
				new FormatCallback(),
				new FrameRenderCallback(),
				true,
				VideoSurfaceAdapters.getVideoSurfaceAdapter());

		player.videoSurface().set(surface);

		// Route audio through JavaSound so the Minecraft options slider can
		// control the volume. Must be done before the first play() — VLC
		// locks the audio output config once the media starts.
		VLCAudioManager.open();
		player.audio().callback(
				VLCAudioManager.PCM_FORMAT,
				VLCAudioManager.SAMPLE_RATE,
				VLCAudioManager.CHANNELS,
				VLCAudioManager.callback());
	}

	public static synchronized boolean isStarted() {
		return started && player != null;
	}

	public static synchronized String getCurrentUrl() {
		return currentUrl;
	}

	/** Loads and plays the given URL, replacing whatever is currently playing. */
	public static synchronized void setUrl(String url) {
		if (player == null) return;
		currentUrl = url;
		VLCStreamClient.LOGGER.info("Opening stream: {}", url);
		player.media().play(url);
		// libVLC resets the audio delay on every media change, so we have to
		// re-apply our compensation after each play() call.
		applyAudioDelay();
	}

	/**
	 * Push the current effective audio delay to libVLC. The value is the
	 * known JavaSound output-buffer latency plus the user-tunable
	 * {@link #syncOffsetMillis}. Positive microseconds tell libVLC "the audio
	 * you submitted will physically play N µs later," which makes libVLC
	 * delay its video presentation to match. No-op if the player isn't ready.
	 */
	private static void applyAudioDelay() {
		if (player == null) return;
		long micros = VLCAudioManager.getOutputLatencyMicros()
				+ (long) syncOffsetMillis * 1_000L;
		try {
			player.audio().setDelay(micros);
		} catch (Throwable t) {
			VLCStreamClient.LOGGER.warn("Failed to set audio delay to {} µs", micros, t);
		}
	}

	/** Returns the user's current sync-offset tuning, in milliseconds. */
	public static int getSyncOffsetMillis() {
		return syncOffsetMillis;
	}

	/**
	 * Set the user-tunable sync offset (see {@link #syncOffsetMillis}). Applied
	 * immediately to the running stream. Safe to call from any thread.
	 */
	public static synchronized void setSyncOffsetMillis(int offsetMs) {
		syncOffsetMillis = offsetMs;
		applyAudioDelay();
	}

	/** Resumes playback from the current pause position. Never re-opens the
	 *  media — if the stream was fully stopped, the user must re-issue
	 *  {@link #setUrl} to restart, otherwise playback would jump back to the
	 *  beginning, which is surprising for live streams. */
	public static synchronized void resume() {
		if (player == null) return;
		if (player.status().isPlaying()) return;
		player.controls().play();
	}

	public static synchronized void pause() {
		if (player == null) return;
		player.controls().pause();
	}

	public static synchronized void stopPlayback() {
		if (player == null) return;
		player.controls().stop();
	}

	/** Seek to an absolute millisecond offset in the current media. No-op if
	 *  the stream isn't seekable (e.g. a live HLS feed) — libVLC clamps or
	 *  ignores the request internally. */
	public static synchronized void seek(long timeMs) {
		if (player == null) return;
		if (timeMs < 0) timeMs = 0;
		player.controls().setTime(timeMs);
	}

	/** Full release — closes the texture and releases libVLC. Called on client shutdown. */
	public static synchronized void shutdown() {
		if (!started) return;
		started = false;
		try {
			if (player != null) {
				player.controls().stop();
				player.release();
			}
			if (factory != null) factory.release();
			if (dynamicTexture != null) dynamicTexture.close();
			VLCAudioManager.close();
		} catch (Throwable t) {
			VLCStreamClient.LOGGER.warn("Error releasing VLC", t);
		} finally {
			player = null;
			factory = null;
			dynamicTexture = null;
			nativeImage = null;
		}
	}

	/**
	 * Returns {@code true} if the player has been started and has a texture
	 * bound. MUST be called on the render thread — uploads a dirty frame to
	 * the {@link DynamicTexture} when necessary.
	 */
	public static boolean uploadIfDirty() {
		if (!started || dynamicTexture == null || nativeImage == null) return false;

		if (frameDirty) {
			synchronized (frameLock) {
				// memcpy from the staging buffer into the NativeImage's native
				// memory. Faster than per-pixel setPixelABGR in Java.
				//? if newRendering {
				long dst = nativeImage.getPointer();
				//?} else {
				/*long dst;
				try {
					java.lang.reflect.Field f = com.mojang.blaze3d.platform.NativeImage.class.getDeclaredField("pixels");
					f.setAccessible(true);
					dst = f.getLong(nativeImage);
				} catch (ReflectiveOperationException e) { throw new RuntimeException(e); }*/
				//?}
				long src = MemoryUtil.memAddress(frameBuffer);
				MemoryUtil.memCopy(src, dst, FRAME_BYTES);
				frameDirty = false;
			}
			RenderSystem.assertOnRenderThread();
			dynamicTexture.upload();
		}
		return true;
	}

	// ------------------------------------------------------------------
	// VLC callbacks (run on VLC's native thread — keep work minimal)
	// ------------------------------------------------------------------

	private static final class FormatCallback implements BufferFormatCallback {
		@Override
		public uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormat
		getBufferFormat(int sourceWidth, int sourceHeight) {
			return new RV32BufferFormat(FRAME_WIDTH, FRAME_HEIGHT);
		}

		@Override
		public void allocatedBuffers(ByteBuffer[] buffers) {
			// Not needed — we use VLC's own buffers and copy in the render callback.
		}
	}

	private static final class FrameRenderCallback implements RenderCallback {
		@Override
		public void display(MediaPlayer mediaPlayer, ByteBuffer[] nativeBuffers,
				uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormat bufferFormat) {
			ByteBuffer src = nativeBuffers[0];
			synchronized (frameLock) {
				src.order(java.nio.ByteOrder.LITTLE_ENDIAN);
				frameBuffer.order(java.nio.ByteOrder.LITTLE_ENDIAN);
				// RV32 in memory is B,G,R,A. NativeImage.RGBA expects R,G,B,A in
				// memory. Swap byte 0 (B) with byte 2 (R); leave byte 1 (G) and
				// byte 3 (A) alone.
				for (int i = 0; i < FRAME_PIXELS; i++) {
					int off = i * 4;
					int bgra = src.getInt(off);
					int rgba = (bgra & 0xFF00FF00)
							| ((bgra & 0x00FF0000) >>> 16)
							| ((bgra & 0x000000FF) << 16);
					frameBuffer.putInt(off, rgba);
				}
				frameDirty = true;
			}
		}
	}
}
