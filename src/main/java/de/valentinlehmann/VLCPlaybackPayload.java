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
 * S2C packet that asks connected clients to change playback state. Kept
 * separate from {@link VLCScreenPayload} so the server can issue transport
 * controls (play/pause/stop) without re-sending the whole screen config.
 */
public record VLCPlaybackPayload(Action action) implements CustomPacketPayload {

	public enum Action {
		PLAY, PAUSE, STOP;

		public static Action fromOrdinal(int i) {
			Action[] values = values();
			if (i < 0 || i >= values.length) return STOP;
			return values[i];
		}
	}

	//? if hasIdentifier {
	public static final CustomPacketPayload.Type<VLCPlaybackPayload> TYPE =
			new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("vlc-stream", "playback"));
	//?} else {
	/*public static final CustomPacketPayload.Type<VLCPlaybackPayload> TYPE =
			new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath("vlc-stream", "playback"));*/
	//?}

	public static final StreamCodec<RegistryFriendlyByteBuf, VLCPlaybackPayload> CODEC =
			StreamCodec.of(
					(buf, p) -> buf.writeByte(p.action.ordinal()),
					buf -> new VLCPlaybackPayload(Action.fromOrdinal(buf.readByte())));

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
