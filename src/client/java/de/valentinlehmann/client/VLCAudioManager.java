package de.valentinlehmann.client;

import com.sun.jna.Pointer;
import uk.co.caprica.vlcj.player.base.MediaPlayer;
import uk.co.caprica.vlcj.player.base.callback.AudioCallback;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;

/**
 * Owns the chosen audio pipeline — either <em>direct</em> (VLC plays through
 * its own native output) or <em>spatial</em> (audio is captured via a libVLC
 * callback, mixed into a world-space stereo image, and pushed out through a
 * JavaSound line).
 *
 * <p>In <em>spatial</em> mode the pipeline applies:
 * <ul>
 *   <li>Linear distance roll-off from 1.0 at the screen down to 0.0 at the
 *       configured hearing distance.</li>
 *   <li>Constant-power equal-angle pan based on the horizontal angle between
 *       the listener's forward vector and the vector to the source.</li>
 *   <li>The master volume slider.</li>
 * </ul>
 *
 * <p>In <em>direct</em> mode no callback is registered. Volume is forwarded
 * straight to {@code libvlc_audio_set_volume}; distance attenuation is applied
 * on top of that by {@link VLCPlayerManager} scaling the per-frame volume
 * before handing it to libVLC. Stereo panning is not available — the output
 * is whatever the source is, mixed by VLC's own output module.
 *
 * <p>Threading: listener pose, volume and the active mode are written from
 * the client/render thread and read from libVLC's audio thread. All shared
 * state is volatile so no locking is needed for the hot path.
 */
public final class VLCAudioManager {

	public static final String PCM_FORMAT = "S16N";
	public static final int SAMPLE_RATE = 44_100;
	public static final int CHANNELS = 2;
	/** 16-bit PCM → 2 bytes per channel sample. */
	private static final int BYTES_PER_CHANNEL_SAMPLE = 2;
	private static final int BYTES_PER_FRAME = CHANNELS * BYTES_PER_CHANNEL_SAMPLE;

	/** Target line-buffer latency for spatial mode. Kept large enough that a
	 *  brief GC pause can't drain the ring — 30 ms was too tight and caused
	 *  faint high-frequency hiss from intermittent underruns — but small
	 *  enough that write() backpressure still paces VLC to the soundcard
	 *  clock, which is what libVLC's AV-sync latches onto. */
	private static final int LINE_BUFFER_MS = 80;

	private VLCAudioManager() {}

	private static SourceDataLine line;
	/** Actual size of the line's internal ring in bytes — read back after
	 *  {@link SourceDataLine#open} since the mixer may round it up. */
	private static int lineBufferBytes;
	/** Scratch buffers reused across callbacks to avoid per-tick allocation. */
	private static byte[] readBuffer = new byte[0];
	private static byte[] mixBuffer = new byte[0];

	/** 0.0–1.0 linear gain, the master volume slider. Volatile because the
	 *  options screen writes from the client thread and VLC reads here. */
	private static volatile float volume = 1.0f;
	private static volatile VLCAudioMode mode = VLCAudioMode.DIRECT;
	/** True once {@link #open()} has prepared the JavaSound line. Only
	 *  meaningful in {@link VLCAudioMode#SPATIAL} mode. */
	private static volatile boolean opened = false;

	// Listener pose, written from the render thread once per frame.
	private static volatile double listenerX;
	private static volatile double listenerY;
	private static volatile double listenerZ;
	/** Yaw in degrees, Minecraft convention (0 = looking +Z). */
	private static volatile float listenerYaw;

	/** Called from the render hook every frame — spatial audio thread reads these. */
	public static void updateListener(double x, double y, double z, float yawDegrees) {
		listenerX = x;
		listenerY = y;
		listenerZ = z;
		listenerYaw = yawDegrees;
	}

	/** Apply a new master volume (0.0–1.0). Safe to call from any thread. In
	 *  direct mode the value is also forwarded to libVLC. */
	public static void setVolume(float v) {
		if (v < 0f) v = 0f;
		if (v > 1f) v = 1f;
		volume = v;
		if (mode == VLCAudioMode.DIRECT) {
			VLCPlayerManager.applyDirectVolume();
		}
	}

	public static float getVolume() {
		return volume;
	}

	public static VLCAudioMode getMode() {
		return mode;
	}

	/** Set the audio pipeline. Takes effect on the next stream start —
	 *  libVLC locks audio configuration once media begins playing. */
	public static void setMode(VLCAudioMode m) {
		if (m == null) m = VLCAudioMode.DIRECT;
		mode = m;
	}

	/**
	 * Compute the world-space distance gain for the current listener pose.
	 * Used by direct mode so {@link VLCPlayerManager} can scale libVLC's
	 * volume per frame, giving a simple distance roll-off without per-sample
	 * mixing. Returns a value in [0, 1].
	 */
	public static float computeDistanceGain() {
		VLCScreenState.Config cfg = VLCScreenState.get();
		double dx = cfg.x() - listenerX;
		double dy = cfg.y() - listenerY;
		double dz = cfg.z() - listenerZ;
		double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
		float hearing = cfg.hearingDistance();
		if (hearing <= 0f || distance >= hearing) return 0f;
		return (float) (1.0 - distance / hearing);
	}

	/**
	 * Expected output latency in microseconds for <em>spatial</em> mode —
	 * the time between libVLC handing us a sample and that sample physically
	 * leaving the speakers. Corresponds to the JavaSound line buffer which
	 * we keep full (see {@link #open}). Returns 0 in direct mode or before
	 * the line is opened; direct mode does its own AV sync via libVLC.
	 */
	public static long getOutputLatencyMicros() {
		if (!opened || lineBufferBytes == 0) return 0;
		return (long) lineBufferBytes * 1_000_000L / ((long) SAMPLE_RATE * BYTES_PER_FRAME);
	}

	/** Open the JavaSound output line. Only used in {@link VLCAudioMode#SPATIAL}
	 *  mode; direct mode never opens a line at all. */
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
			// Size the ring for LINE_BUFFER_MS of audio. Large enough to absorb
			// GC pauses without underrunning; small enough that write() still
			// paces VLC's audio thread against the soundcard clock.
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
					"VLC spatial audio output opened: {} Hz, {} ch, S16N, line buffer {} bytes (~{} ms)",
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

	/** Returns the {@link AudioCallback} to hand to
	 *  {@link uk.co.caprica.vlcj.player.base.AudioApi#callback} in spatial mode. */
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

			// Copy from native memory into our reusable byte[] instead of
			// allocating a fresh array each callback. JNA's getByteArray()
			// allocates every invocation, which on a hot path 50+ times per
			// second adds enough GC pressure to trigger micro-pauses, which
			// show up as faint hiss when the line buffer briefly underruns.
			if (readBuffer.length < bytes) readBuffer = new byte[bytes];
			samples.read(0, readBuffer, 0, bytes);

			if (mixBuffer.length < bytes) mixBuffer = new byte[bytes];
			mixSpatial(readBuffer, mixBuffer, bytes);
			// Blocking write by design: it paces VLC's audio thread to the
			// soundcard clock, which libVLC then uses as the AV-sync master.
			// Dropping samples here would silently desync audio from video.
			// The line buffer is pre-filled with silence in open(), so the
			// very first write already blocks and there's no startup race
			// where VLC's "audio submitted" clock outruns physical playout.
			l.write(mixBuffer, 0, bytes);
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
