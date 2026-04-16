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
 * S2C packet asking connected clients to seek VLC to an absolute timestamp,
 * expressed in milliseconds from the start of the stream. Only makes sense
 * for seekable media — live streams will silently ignore or clamp the seek.
 */
public record VLCSeekPayload(long timeMs) implements CustomPacketPayload {

	//? if hasIdentifier {
	public static final CustomPacketPayload.Type<VLCSeekPayload> TYPE =
			new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("vlc-stream", "seek"));
	//?} else {
	/*public static final CustomPacketPayload.Type<VLCSeekPayload> TYPE =
			new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("vlc-stream", "seek"));*/
	//?}

	public static final StreamCodec<RegistryFriendlyByteBuf, VLCSeekPayload> CODEC =
			StreamCodec.of(
					(buf, p) -> buf.writeLong(p.timeMs),
					buf -> new VLCSeekPayload(buf.readLong()));

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
