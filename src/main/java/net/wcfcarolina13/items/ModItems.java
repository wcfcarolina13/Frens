package net.wcfcarolina13.items;

import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;
import net.minecraft.util.Rarity;
import net.wcfcarolina13.Frens;

/**
 * Mod item registry.
 */
public final class ModItems {

	public static final Identifier WIZARD_TOME_ID = Identifier.of(Frens.MOD_ID, "wizard_tome");
	public static final RegistryKey<Item> WIZARD_TOME_KEY = RegistryKey.of(RegistryKeys.ITEM, WIZARD_TOME_ID);

	public static final Item WIZARD_TOME = Registry.register(
			Registries.ITEM,
			WIZARD_TOME_ID,
			new Item(new Item.Settings()
					.registryKey(WIZARD_TOME_KEY)
					.maxCount(1)
					.rarity(Rarity.RARE))
	);

	private ModItems() {
	}

	public static void registerAll() {
		// Static init does the registration; this is here for explicit lifecycle ordering.
	}
}