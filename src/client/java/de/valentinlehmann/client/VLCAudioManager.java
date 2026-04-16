package de.valentinlehmann.client;

import com.sun.jna.Pointer;
import uk.co.caprica.vlcj.player.base.MediaPlayer;
import uk.co.caprica.vlcj.player.base.callback.AudioCallback;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;

/**
 * Routes VLC audio through the JVM's JavaSound output and applies a simple
 * world-space mix so the stream sounds like it's coming from the screen's
 * position: volume rolls off with distance and stereo pans according to
 * where the screen sits relative to the listener's facing direction.
 *
 * <p>Threading: the {@link AudioCallback#play} invocation runs on libVLC's
 * audio thread, which is not the render thread. It reads the listener pose
 * (updated once per frame by the render hook via {@link #updateListener}),
 * the screen config (via {@link VLCScreenState}), and the master volume
 * (via {@link #volume}). All reads are through volatile or atomic state, so
 * no locking is needed.
 *
 * <p>Attenuation model: linear roll-off from 1.0 at distance 0 to 0.0 at
 * {@code hearingDistance}. Past that, silent.
 *
 * <p>Panning model: constant-power equal-angle pan, computed from the
 * horizontal angle between the listener's forward vector and the vector to
 * the source. Vertical position does not affect panning (no HRTF), only
 * distance attenuation.
 */
public final class VLCAudioManager {

	public static final String PCM_FORMAT = "S16N";
	public static final int SAMPLE_RATE = 44_100;
	public static final int CHANNELS = 2;
	/** 16-bit PCM → 2 bytes per channel sample. */
	private static final int BYTES_PER_CHANNEL_SAMPLE = 2;
	private static final int BYTES_PER_FRAME = CHANNELS * BYTES_PER_CHANNEL_SAMPLE;

	/** Target line-buffer latency. Small enough that the warm-up fill is
	 *  negligible and write() backpressure paces VLC tightly; large enough
	 *  that a brief render-thread stall doesn't underrun. */
	private static final int LINE_BUFFER_MS = 30;

	private VLCAudioManager() {}

	private static SourceDataLine line;
	/** Actual size of the line's internal ring in bytes — read back after
	 *  {@link SourceDataLine#open} since the mixer may round it up. */
	private static int lineBufferBytes;
	/** Scratch buffer reused across audio callbacks to avoid per-frame
	 *  allocation. Sized up lazily. */
	private static byte[] mixBuffer = new byte[0];

	/** 0.0–1.0 linear gain, the master volume slider. Volatile because the
	 *  options screen writes from the client thread and VLC reads here. */
	private static volatile float volume = 1.0f;
	private static volatile boolean opened = false;

	// Listener pose, written from the render thread once per frame.
	private static volatile double listenerX;
	private static volatile double listenerY;
	private static volatile double listenerZ;
	/** Yaw in degrees, Minecraft convention (0 = looking +Z). */
	private static volatile float listenerYaw;

	/** Called from the render hook every frame — audio thread reads these. */
	public static void updateListener(double x, double y, double z, float yawDegrees) {
		listenerX = x;
		listenerY = y;
		listenerZ = z;
		listenerYaw = yawDegrees;
	}

	/** Apply a new master volume (0.0–1.0). Safe to call from any thread. */
	public static void setVolume(float v) {
		if (v < 0f) v = 0f;
		if (v > 1f) v = 1f;
		volume = v;
	}

	public static float getVolume() {
		return volume;
	}

	/**
	 * Expected output latency in microseconds — the time between when VLC hands
	 * us a sample in the audio callback and when that sample physically leaves
	 * the speakers. Corresponds to the JavaSound line buffer (which we keep
	 * full, see {@link #open}). Does not include whatever additional latency
	 * the OS audio stack adds downstream — use the user-tunable offset in
	 * {@link VLCPlayerManager#setSyncOffsetMillis} for that.
	 *
	 * <p>Returns 0 if the line hasn't been opened yet.
	 */
	public static long getOutputLatencyMicros() {
		if (!opened || lineBufferBytes == 0) return 0;
		return (long) lineBufferBytes * 1_000_000L / ((long) SAMPLE_RATE * BYTES_PER_FRAME);
	}

	/** Open the output line. Called once after the media player is created. */
	public static synchronized void open() {
		if (opened) return;
		try {
			// 16-bit signed, native byte order matches VLC's S16N.
			AudioFormat fmt = new AudioFormat(
					AudioFormat.Encoding.PCM_SIGNED,
					SAMPLE_RATE, 16, CHANNELS,
					BYTES_PER_FRAME, SAMPLE_RATE,
					java.nio.ByteOrder.nativeOrder() == java.nio.ByteOrder.BIG_ENDIAN);

			DataLine.Info info = new DataLine.Info(SourceDataLine.class, fmt);
			line = (SourceDataLine) javax.sound.sampled.AudioSystem.getLine(info);
			// Small line buffer so the warm-up fill doesn't put audio noticeably
			// behind video. Once full, write() blocks and paces VLC's audio
			// callback to the soundcard clock — which is exactly what we want
			// libVLC's AV-sync to latch onto.
			int requested = (SAMPLE_RATE * LINE_BUFFER_MS / 1000) * BYTES_PER_FRAME;
			line.open(fmt, requested);
			lineBufferBytes = line.getBufferSize();
			// Pre-fill with silence so the very first real write() blocks.
			// Without this, VLC's first few callbacks race into the empty
			// buffer, advancing its "audio submitted" clock faster than
			// wall-time and permanently placing video ahead of audio.
			byte[] silence = new byte[lineBufferBytes];
			line.write(silence, 0, silence.length);
			line.start();
			opened = true;
			VLCStreamClient.LOGGER.info(
					"VLC audio output opened: {} Hz, {} ch, S16N, line buffer {} bytes (~{} ms)",
					SAMPLE_RATE, CHANNELS, lineBufferBytes,
					lineBufferBytes * 1000 / (SAMPLE_RATE * BYTES_PER_FRAME));
		} catch (LineUnavailableException e) {
			VLCStreamClient.LOGGER.warn("Could not open audio line — stream will be silent", e);
			line = null;
		}
	}

	public static synchronized void close() {
		if (!opened) return;
		opened = false;
		if (line != null) {
			try {
				line.drain();
				line.stop();
				line.close();
			} catch (Throwable t) {
				VLCStreamClient.LOGGER.warn("Error closing audio line", t);
			}
			line = null;
		}
	}

	/** Returns the {@link AudioCallback} to hand to {@link uk.co.caprica.vlcj.player.base.AudioApi#callback}. */
	public static AudioCallback callback() {
		return new Callback();
	}

	/**
	 * Collapses the input stereo PCM to mono, then re-pans and attenuates it
	 * based on the listener's distance from and bearing to the source. The
	 * result is written into {@code out} in the same interleaved L/R layout.
	 * Gains are held constant across the whole buffer — an audio callback's
	 * worth of samples is short enough that per-sample re-computation gains
	 * nothing and risks zippering.
	 */
	private static void mixSpatial(byte[] in, byte[] out, int len) {
		// --- Gain computation (once per callback) ---
		VLCScreenState.Config cfg = VLCScreenState.get();

		double dx = cfg.x() - listenerX;
		double dy = cfg.y() - listenerY;
		double dz = cfg.z() - listenerZ;

		double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);

		float hearing = cfg.hearingDistance();
		float distanceGain = 0f;
		if (hearing > 0f && distance < hearing) {
			distanceGain = (float) (1.0 - distance / hearing);
		}

		float pan = 0f;
		double horizDist = Math.sqrt(dx * dx + dz * dz);
		if (horizDist > 1e-6) {
			double yawRad = Math.toRadians(listenerYaw);
			// Minecraft right-vector in horizontal plane is (-cos(yaw), 0, -sin(yaw)).
			// Right-component = offset · right.
			double right = -dx * Math.cos(yawRad) - dz * Math.sin(yawRad);
			double p = right / horizDist;
			if (p > 1.0) p = 1.0;
			else if (p < -1.0) p = -1.0;
			pan = (float) p;
		}

		// Constant-power pan → both channels get cos(π/4) ≈ 0.707 when centred,
		// one channel goes to 1 while the other goes to 0 at the extremes.
		float normalized = (pan + 1f) * 0.5f;
		float leftGain  = (float) Math.cos(normalized * Math.PI * 0.5);
		float rightGain = (float) Math.sin(normalized * Math.PI * 0.5);

		float gain = volume * distanceGain;
		float gL = leftGain * gain;
		float gR = rightGain * gain;

		// --- Fast path: fully inaudible → zero the output buffer. ---
		if (gain <= 0f) {
			for (int i = 0; i < len; i++) out[i] = 0;
			return;
		}

		// --- Sample mixing ---
		boolean bigEndian = java.nio.ByteOrder.nativeOrder() == java.nio.ByteOrder.BIG_ENDIAN;
		// Each frame = 4 bytes (L16 + R16). Read L and R, collapse to mono,
		// scale per-ear, write out.
		for (int i = 0; i + BYTES_PER_FRAME <= len; i += BYTES_PER_FRAME) {
			int leftSample  = readSigned16(in, i,     bigEndian);
			int rightSample = readSigned16(in, i + 2, bigEndian);
			int mono = (leftSample + rightSample) >> 1;

			int outL = (int) (mono * gL);
			int outR = (int) (mono * gR);
			if (outL >  Short.MAX_VALUE) outL = Short.MAX_VALUE;
			if (outL <  Short.MIN_VALUE) outL = Short.MIN_VALUE;
			if (outR >  Short.MAX_VALUE) outR = Short.MAX_VALUE;
			if (outR <  Short.MIN_VALUE) outR = Short.MIN_VALUE;

			writeSigned16(out, i,     outL, bigEndian);
			writeSigned16(out, i + 2, outR, bigEndian);
		}
	}

	private static int readSigned16(byte[] buf, int off, boolean bigEndian) {
		int hi, lo;
		if (bigEndian) { hi = buf[off];     lo = buf[off + 1] & 0xFF; }
		else           { lo = buf[off] & 0xFF; hi = buf[off + 1]; }
		return (hi << 8) | lo;
	}

	private static void writeSigned16(byte[] buf, int off, int sample, boolean bigEndian) {
		if (bigEndian) {
			buf[off]     = (byte) ((sample >> 8) & 0xFF);
			buf[off + 1] = (byte) (sample & 0xFF);
		} else {
			buf[off]     = (byte) (sample & 0xFF);
			buf[off + 1] = (byte) ((sample >> 8) & 0xFF);
		}
	}

	private static final class Callback implements AudioCallback {
		@Override
		public void play(MediaPlayer mediaPlayer, Pointer samples, int sampleCount, long pts) {
			SourceDataLine l = line;
			if (l == null) return;
			int bytes = sampleCount * BYTES_PER_FRAME;

			byte[] in = samples.getByteArray(0, bytes);
			if (mixBuffer.length < bytes) mixBuffer = new byte[bytes];
			byte[] out = mixBuffer;
			mixSpatial(in, out, bytes);
			// Blocking write by design: it paces VLC's audio thread to the
			// soundcard clock, which libVLC then uses as the AV-sync master.
			// Dropping samples here would silently desync audio from video.
			// The line buffer is pre-filled with silence in open(), so the
			// very first write already blocks and there's no startup race
			// where VLC's "audio submitted" clock outruns physical playout.
			l.write(out, 0, bytes);
		}

		@Override public void pause(MediaPlayer mediaPlayer, long pts) {
			SourceDataLine l = line;
			if (l != null) l.stop();
		}

		@Override public void resume(MediaPlayer mediaPlayer, long pts) {
			SourceDataLine l = line;
			if (l != null) l.start();
		}

		@Override public void flush(MediaPlayer mediaPlayer, long pts) {
			SourceDataLine l = line;
			if (l != null) l.flush();
		}

		@Override public void drain(MediaPlayer mediaPlayer) {
			SourceDataLine l = line;
			if (l != null) l.drain();
		}

		/** VLC calls this when its own volume/mute state changes. We ignore it
		 *  because the Minecraft slider is the single source of truth. */
		@Override public void setVolume(float volume, boolean mute) { /* intentional no-op */ }
	}
}
