package fr.nyuway.stasisbot.service;

import fr.nyuway.stasisbot.config.MasterCommands;
import fr.nyuway.stasisbot.config.StasisBotConfig;
import fr.nyuway.stasisbot.i18n.Messages;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;

import java.util.Locale;

/**
 * Parses and applies master DM commands. Pure configuration commands are handed
 * to {@link MasterCommands}; the few that need live world access — {@code sethome},
 * {@code come}, {@code goto}, {@code stop} — are handled here and delegated back to
 * {@link HomeService} for the bits that touch its state machine (busy check, manual
 * navigation). Keeping this out of {@code HomeService} leaves that class focused on
 * the home-request lifecycle.
 */
final class MasterCommandHandler {

	private final HomeService owner;
	private final StasisBotConfig config;
	private final MinecraftClient client;
	private final PlayerFeedback feedback;

	MasterCommandHandler(HomeService owner, StasisBotConfig config, MinecraftClient client, PlayerFeedback feedback) {
		this.owner = owner;
		this.config = config;
		this.client = client;
		this.feedback = feedback;
	}

	/** Apply a chat line already known to look like a command (runs on the client thread). */
	void handle(String sender, String body) {
		if (!canControl(sender)) return;
		// Live commands need the bot's position / the navigator, so handle them here
		// before the pure-config grammar in MasterCommands.
		if (handleLiveCommand(sender, body)) return;
		MasterCommands.Result r = MasterCommands.apply(config, body);
		if (r.handled()) feedback.reply(sender, r.key(), r.args());
	}

	/**
	 * Who may configure the bot: anyone while no master is set yet (bootstrap, so
	 * the owner can claim it with {@code !sb master <name>}), the master, or — when
	 * {@code members} is on — any base member detected on a stasis sign.
	 */
	private boolean canControl(String sender) {
		if (config.master().isBlank()) return true;
		if (config.isMaster(sender)) return true;
		return config.baseMembersControl() && owner.isBaseMember(sender);
	}

	/**
	 * Handle the commands that need live world access (the bot's position, the
	 * sender's entity, the navigator): {@code sethome}, {@code come}, {@code goto},
	 * {@code stop}. Returns true when {@code body} was one of them.
	 */
	private boolean handleLiveCommand(String sender, String body) {
		if (!MasterCommands.looksLikeCommand(config, body)) return false;
		String[] parts = body.trim().split("\\s+");
		if (parts.length < 2) return false;
		switch (parts[1].toLowerCase(Locale.ROOT)) {
			case "sethome" -> { doSetHome(sender, parts); return true; }
			case "come" -> { doCome(sender); return true; }
			case "goto" -> { doGoto(sender, parts); return true; }
			case "stop" -> { doStop(sender); return true; }
			default -> { return false; }
		}
	}

	/** {@code !sb sethome} pins the bot's current block as home; {@code sethome clear} reverts. */
	private void doSetHome(String sender, String[] parts) {
		if (parts.length >= 3 && parts[2].equalsIgnoreCase("clear")) {
			config.setReturnPos(null, null, null);
			feedback.reply(sender, Messages.Key.CFG_SET, "home", "start");
			return;
		}
		ClientPlayerEntity self = client.player;
		if (self == null) return;
		BlockPos p = self.getBlockPos();
		config.setReturnPos(p.getX(), p.getY(), p.getZ());
		feedback.reply(sender, Messages.Key.CFG_SET, "home", p.getX() + " " + p.getY() + " " + p.getZ());
	}

	/** {@code !sb come} walks the bot to the sender (if visible nearby). */
	private void doCome(String sender) {
		if (owner.isBusy()) { feedback.reply(sender, Messages.Key.NAV_BUSY); return; }
		BlockPos target = locatePlayer(sender);
		if (target == null) { feedback.reply(sender, Messages.Key.NAV_NOPLAYER); return; }
		owner.startManual(target);
		feedback.reply(sender, Messages.Key.NAV_COMING);
	}

	/** {@code !sb goto x y z} walks the bot to fixed coordinates. */
	private void doGoto(String sender, String[] parts) {
		if (owner.isBusy()) { feedback.reply(sender, Messages.Key.NAV_BUSY); return; }
		if (parts.length < 5) { feedback.reply(sender, Messages.Key.CFG_BADVALUE, "goto"); return; }
		try {
			int x = Integer.parseInt(parts[2]);
			int y = Integer.parseInt(parts[3]);
			int z = Integer.parseInt(parts[4]);
			owner.startManual(new BlockPos(x, y, z));
			feedback.reply(sender, Messages.Key.NAV_GOTO, x + " " + y + " " + z);
		} catch (NumberFormatException e) {
			feedback.reply(sender, Messages.Key.CFG_BADVALUE, "goto");
		}
	}

	/** {@code !sb stop} cancels a master-directed move. */
	private void doStop(String sender) {
		owner.stopManual();
		feedback.reply(sender, Messages.Key.NAV_STOPPED);
	}

	/** Find another player's block position by name in the loaded world, or null. */
	private BlockPos locatePlayer(String name) {
		ClientWorld world = client.world;
		if (world == null || name == null) return null;
		for (var p : world.getPlayers()) {
			if (name.equalsIgnoreCase(p.getGameProfile().name())) return p.getBlockPos();
		}
		return null;
	}
}
