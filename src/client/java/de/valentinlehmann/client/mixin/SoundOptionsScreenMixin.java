package de.valentinlehmann.client.mixin;

import de.valentinlehmann.client.VLCAudioManager;
import de.valentinlehmann.client.VLCStreamConfig;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.Options;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.client.gui.screens.options.SoundOptionsScreen;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Injects a dedicated "VLC Stream" volume slider into the vanilla sound
 * options screen, alongside Master/Music/Blocks/etc. Value changes are routed
 * to {@link VLCAudioManager} and persisted to disk via {@link VLCStreamConfig}.
 *
 * <p>We extend {@link OptionsSubScreen} (the target's superclass) so the mixin
 * can see the protected {@code list} field through normal Java inheritance
 * rules — the mixin processor merges our injected method into SoundOptionsScreen
 * at runtime.
 */
@Mixin(SoundOptionsScreen.class)
public abstract class SoundOptionsScreenMixin extends OptionsSubScreen {

	/** Unused at runtime — required only so javac is satisfied that this class
	 *  can extend OptionsSubScreen. The mixin processor strips this constructor. */
	private SoundOptionsScreenMixin(Screen lastScreen, Options options, Component title) {
		super(lastScreen, options, title);
	}

	@Inject(method = "addOptions", at = @At("TAIL"))
	private void vlcstream$addVolumeSlider(CallbackInfo ci) {
		if (this.list == null) return;

		OptionInstance<Double> option = new OptionInstance<>(
				"options.vlc-stream.volume",
				OptionInstance.noTooltip(),
				SoundOptionsScreenMixin::vlcstream$label,
				OptionInstance.UnitDouble.INSTANCE,
				(double) VLCAudioManager.getVolume(),
				value -> {
					VLCAudioManager.setVolume(value.floatValue());
					VLCStreamConfig.save();
				});

		this.list.addBig(option);
	}

	@Unique
	private static Component vlcstream$label(Component caption, Double value) {
		int percent = (int) Math.round(value * 100);
		if (percent == 0) {
			return Component.translatable("options.generic_value",
					caption, Component.translatable("options.off"));
		}
		return Component.translatable("options.percent_value", caption, percent);
	}
}
