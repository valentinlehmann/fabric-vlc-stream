package de.valentinlehmann;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
//? if newPermissions {
import net.minecraft.server.permissions.Permissions;
//?}

import java.util.function.UnaryOperator;

/**
 * Server-side commands for controlling the shared VLC stream. Each mutating
 * command updates {@link VLCServerState}, persists the change via
 * {@link VLCServerConfig#save}, and broadcasts the new config (or a playback
 * transport message) to every connected client.
 *
 * <pre>
 *   /vlc url &lt;url&gt;
 *   /vlc pos &lt;x&gt; &lt;y&gt; &lt;z&gt;
 *   /vlc size &lt;width&gt; &lt;height&gt;
 *   /vlc rot &lt;yaw&gt; &lt;pitch&gt; [roll]
 *   /vlc hearing &lt;distance&gt;
 *   /vlc enable | disable
 *   /vlc play | pause | stop
 *   /vlc seek &lt;time&gt;
 *   /vlc show
 * </pre>
 *
 * <p>All commands require the {@link Permissions#COMMANDS_GAMEMASTER}
 * permission (op level 2 on a dedicated server, host of a LAN singleplayer
 * world).
 */
public final class VLCServerCommands {

	private VLCServerCommands() {}

	public static void register() {
		CommandRegistrationCallback.EVENT.register(
				(dispatcher, registryAccess, environment) -> register(dispatcher));
	}

	private static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal("vlc")
				//? if newPermissions {
				.requires(src -> src.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
				//?} else {
				/*.requires(src -> src.hasPermission(2))*/
				//?}
				.then(Commands.literal("url")
						.then(Commands.argument("url", StringArgumentType.greedyString())
								.executes(VLCServerCommands::setUrl)))
				.then(Commands.literal("pos")
						.then(Commands.argument("x", DoubleArgumentType.doubleArg())
								.then(Commands.argument("y", DoubleArgumentType.doubleArg())
										.then(Commands.argument("z", DoubleArgumentType.doubleArg())
												.executes(VLCServerCommands::setPos)))))
				.then(Commands.literal("size")
						.then(Commands.argument("width", FloatArgumentType.floatArg(0.01f))
								.then(Commands.argument("height", FloatArgumentType.floatArg(0.01f))
										.executes(VLCServerCommands::setSize))))
				.then(Commands.literal("rot")
						.then(Commands.argument("yaw", FloatArgumentType.floatArg())
								.then(Commands.argument("pitch", FloatArgumentType.floatArg())
										.executes(ctx -> setRot(ctx, 0f))
										.then(Commands.argument("roll", FloatArgumentType.floatArg())
												.executes(ctx -> setRot(ctx, FloatArgumentType.getFloat(ctx, "roll")))))))
				.then(Commands.literal("hearing")
						.then(Commands.argument("distance", FloatArgumentType.floatArg(0f))
								.executes(VLCServerCommands::setHearing)))
				.then(Commands.literal("enable").executes(ctx -> setEnabled(ctx, true)))
				.then(Commands.literal("disable").executes(ctx -> setEnabled(ctx, false)))
				.then(Commands.literal("play").executes(ctx -> playback(ctx, VLCPlaybackPayload.Action.PLAY)))
				.then(Commands.literal("pause").executes(ctx -> playback(ctx, VLCPlaybackPayload.Action.PAUSE)))
				.then(Commands.literal("stop").executes(ctx -> playback(ctx, VLCPlaybackPayload.Action.STOP)))
				.then(Commands.literal("seek")
						.then(Commands.argument("time", StringArgumentType.word())
								.executes(VLCServerCommands::seek)))
				.then(Commands.literal("show").executes(VLCServerCommands::show))
		);
	}

	// ---- state mutation helpers ----

	/** Apply {@code fn} to the current config, persist, broadcast, and reply to the source. */
	private static int mutate(CommandContext<CommandSourceStack> ctx,
			UnaryOperator<VLCServerState.Config> fn,
			String message) {
		VLCServerState.Config next;
		synchronized (VLCServerState.class) {
			next = fn.apply(VLCServerState.get());
			VLCServerState.set(next);
		}
		VLCServerConfig.save();
		MinecraftServer server = ctx.getSource().getServer();
		VLCServerState.broadcast(server);
		ctx.getSource().sendSuccess(() -> Component.literal(message), true);
		return 1;
	}

	// ---- individual command executors ----

	private static int setUrl(CommandContext<CommandSourceStack> ctx) {
		String url = StringArgumentType.getString(ctx, "url");
		return mutate(ctx, c -> c.withUrl(url).withEnabled(true),
				"VLC URL: " + url);
	}

	private static int setPos(CommandContext<CommandSourceStack> ctx) {
		double x = DoubleArgumentType.getDouble(ctx, "x");
		double y = DoubleArgumentType.getDouble(ctx, "y");
		double z = DoubleArgumentType.getDouble(ctx, "z");
		return mutate(ctx, c -> c.withPos(x, y, z),
				String.format("Screen position: %.3f, %.3f, %.3f", x, y, z));
	}

	private static int setSize(CommandContext<CommandSourceStack> ctx) {
		float w = FloatArgumentType.getFloat(ctx, "width");
		float h = FloatArgumentType.getFloat(ctx, "height");
		return mutate(ctx, c -> c.withSize(w, h),
				String.format("Screen size: %.3f × %.3f", w, h));
	}

	private static int setRot(CommandContext<CommandSourceStack> ctx, float roll) {
		float yaw = FloatArgumentType.getFloat(ctx, "yaw");
		float pitch = FloatArgumentType.getFloat(ctx, "pitch");
		return mutate(ctx, c -> c.withRotation(yaw, pitch, roll),
				String.format("Screen rotation: yaw=%.1f° pitch=%.1f° roll=%.1f°", yaw, pitch, roll));
	}

	private static int setHearing(CommandContext<CommandSourceStack> ctx) {
		float distance = FloatArgumentType.getFloat(ctx, "distance");
		return mutate(ctx, c -> c.withHearingDistance(distance),
				String.format("Hearing distance: %.2f blocks", distance));
	}

	private static int setEnabled(CommandContext<CommandSourceStack> ctx, boolean enabled) {
		return mutate(ctx, c -> c.withEnabled(enabled),
				enabled ? "Screen enabled" : "Screen disabled");
	}

	/** Playback transport (play/pause/stop) — broadcast as a lightweight
	 *  {@link VLCPlaybackPayload} so the whole screen config doesn't have to
	 *  go on the wire. */
	private static int playback(CommandContext<CommandSourceStack> ctx, VLCPlaybackPayload.Action action) {
		MinecraftServer server = ctx.getSource().getServer();
		VLCServerState.broadcastPlayback(server, action);
		ctx.getSource().sendSuccess(() -> Component.literal("Playback: " + action.name().toLowerCase()), true);
		return 1;
	}

	private static final SimpleCommandExceptionType BAD_TIME = new SimpleCommandExceptionType(
			Component.literal("Expected seconds or hh:mm:ss / mm:ss"));

	/** Parse a user-supplied timestamp into milliseconds. Accepted forms:
	 *  plain seconds ("30", "90.5"), "mm:ss", or "hh:mm:ss" — decimals allowed
	 *  on the trailing field. */
	private static long parseTime(String s) throws CommandSyntaxException {
		String[] parts = s.split(":");
		if (parts.length == 0 || parts.length > 3) throw BAD_TIME.create();
		double total = 0.0;
		try {
			for (String part : parts) {
				if (part.isEmpty()) throw BAD_TIME.create();
				total = total * 60.0 + Double.parseDouble(part);
			}
		} catch (NumberFormatException e) {
			throw BAD_TIME.create();
		}
		if (total < 0) throw BAD_TIME.create();
		return (long) (total * 1000.0);
	}

	private static int seek(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		String raw = StringArgumentType.getString(ctx, "time");
		long ms = parseTime(raw);
		MinecraftServer server = ctx.getSource().getServer();
		VLCServerState.broadcastSeek(server, ms);
		ctx.getSource().sendSuccess(() -> Component.literal("Seeking to " + raw + " (" + ms + " ms)"), true);
		return 1;
	}

	private static int show(CommandContext<CommandSourceStack> ctx) {
		VLCServerState.Config c = VLCServerState.get();
		String msg = String.format(
				"VLC: url='%s' pos=(%.2f, %.2f, %.2f) size=%.2fx%.2f rot=(%.1f°/%.1f°/%.1f°) hearing=%.1f enabled=%s",
				c.url(), c.x(), c.y(), c.z(), c.width(), c.height(),
				c.yaw(), c.pitch(), c.roll(), c.hearingDistance(), c.enabled());
		ctx.getSource().sendSuccess(() -> Component.literal(msg), false);
		return 1;
	}
}
