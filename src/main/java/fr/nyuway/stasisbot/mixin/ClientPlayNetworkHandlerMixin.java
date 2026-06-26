package fr.nyuway.stasisbot.mixin;

import fr.nyuway.stasisbot.control.ControlInbox;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.packet.s2c.play.GameMessageS2CPacket;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Taps incoming system messages (where 2b2t routes whispers) at the very head of the
 * packet handler, before the Fabric message events — and crucially before any other
 * client mod can cancel the message to show it in its own UI. We only <em>observe</em>
 * (never cancel) and only forward lines that look like control frames, so the remote
 * control channel keeps working even on a client whose cheat hides whispers from chat.
 */
@Mixin(ClientPlayNetworkHandler.class)
public class ClientPlayNetworkHandlerMixin {

	@Inject(method = "onGameMessage", at = @At("HEAD"))
	private void stasisbot$tapGameMessage(GameMessageS2CPacket packet, CallbackInfo ci) {
		Text content = packet.content();
		if (content == null) return;
		String s = content.getString();
		if (s != null && s.contains("!ctl")) {
			ControlInbox.feed(s);
		}
	}
}
