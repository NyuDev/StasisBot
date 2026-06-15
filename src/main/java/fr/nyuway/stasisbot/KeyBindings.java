package fr.nyuway.stasisbot;

import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

/**
 * Registers the keybind (default: H) that opens the read-only monitor screen.
 */
public final class KeyBindings {

	// 1.21.11: key categories are typed objects keyed by an Identifier, not strings.
	public static final KeyBinding.Category CATEGORY =
			KeyBinding.Category.create(Identifier.of(StasisBot.MOD_ID, "main"));

	private KeyBindings() {
	}

	public static KeyBinding registerOpenMonitor() {
		return KeyBindingHelper.registerKeyBinding(new KeyBinding(
				"key.stasisbot.open_menu",
				InputUtil.Type.KEYSYM,
				GLFW.GLFW_KEY_H,
				CATEGORY
		));
	}
}
