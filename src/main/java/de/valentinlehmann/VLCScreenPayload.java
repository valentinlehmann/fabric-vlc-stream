package de.valentinlehmann;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
//? if hasIdentifier {
import net.minecraft.resources.Identifier;
//?} else {
/*import net.minecraft.resources.ResourceLocation;*/
//?}

/**
 * S2C packet that tells a client where to place its VLC screen, how big it
 * should be, how it's oriented, which URL to play, and how far away it can
 * still be heard. Clients without the mod simply ignore it.
 *
 * @param x                world-space X of the screen's centre
 * @param y                world-space Y of the screen's centre
 * @param z                world-space Z of the screen's centre
 * @param width            screen width in blocks
 * @param height           screen height in blocks
 * @param yaw              screen yaw in degrees
 * @param pitch            screen pitch in degrees
 * @param roll             screen roll in degrees
 * @param url              VLC-openable URL (m3u8, rtsp://, http://, file://…).
 *                         Empty string means "don't change the URL".
 * @param hearingDistance  maximum distance in blocks at which the screen's
 *                         audio is still audible. Past this, the stream is
 *                         silent. Use a large value for world-loud audio.
 * @param enabled          whether the screen is rendered/audible at all
 */
public record VLCScreenPayload(
		double x, double y, double z,
		float width, float height,
		float yaw, float pitch, float roll,
		String url,
		float hearingDistance,
		boolean enabled) implements CustomPacketPayload {

	//? if hasIdentifier {
	public static final CustomPacketPayload.Type<VLCScreenPayload> TYPE =
			new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("vlc-stream", "screen"));
	//?} else {
	/*public static final CustomPacketPayload.Type<VLCScreenPayload> TYPE =
			new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("vlc-stream", "screen"));*/
	//?}

	/**
	 * Manual codec — the field count is beyond {@code StreamCodec.composite}'s
	 * largest overload, and hand-rolling keeps the wire format obvious.
	 */
	public static final StreamCodec<RegistryFriendlyByteBuf, VLCScreenPayload> CODEC =
			StreamCodec.of(
					(buf, p) -> {
						buf.writeDouble(p.x);
						buf.writeDouble(p.y);
						buf.writeDouble(p.z);
						buf.writeFloat(p.width);
						buf.writeFloat(p.height);
						buf.writeFloat(p.yaw);
						buf.writeFloat(p.pitch);
						buf.writeFloat(p.roll);
						buf.writeUtf(p.url, 1024);
						buf.writeFloat(p.hearingDistance);
						buf.writeBoolean(p.enabled);
					},
					buf -> new VLCScreenPayload(
							buf.readDouble(),
							buf.readDouble(),
							buf.readDouble(),
							buf.readFloat(),
							buf.readFloat(),
							buf.readFloat(),
							buf.readFloat(),
							buf.readFloat(),
							buf.readUtf(1024),
							buf.readFloat(),
							buf.readBoolean()));

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
