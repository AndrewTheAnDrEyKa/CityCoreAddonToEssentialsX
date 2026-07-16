package ru.citycore.gui;

import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.profile.PlayerProfile;
import org.bukkit.profile.PlayerTextures;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/** Builds cached, network-independent item prototypes for semantic GUI icons. */
public final class GuiIconRegistry {
    private static final String TEXTURE_ROOT = "https://textures.minecraft.net/texture/";
    private final Map<GuiIcon, ItemStack> prototypes = new EnumMap<>(GuiIcon.class);
    private final BooleanSupplier customHeads;

    public GuiIconRegistry(BooleanSupplier customHeads) {
        this.customHeads = customHeads;
    }

    public ItemStack create(GuiIcon icon) {
        if (!customHeads.getAsBoolean() || !icon.customHead()) return new ItemStack(icon.fallback());
        return prototypes.computeIfAbsent(icon, this::prototype).clone();
    }

    private ItemStack prototype(GuiIcon icon) {
        try {
            ItemStack head = new ItemStack(org.bukkit.Material.PLAYER_HEAD);
            SkullMeta meta = (SkullMeta) head.getItemMeta();
            UUID id = UUID.nameUUIDFromBytes(("citycore-icon:" + icon.name()).getBytes(StandardCharsets.UTF_8));
            PlayerProfile profile = Bukkit.createPlayerProfile(id);
            PlayerTextures textures = profile.getTextures();
            textures.setSkin(URI.create(TEXTURE_ROOT + icon.textureHash()).toURL());
            profile.setTextures(textures);
            meta.setOwnerProfile(profile);
            head.setItemMeta(meta);
            return head;
        } catch (RuntimeException | java.net.MalformedURLException invalidTexture) {
            return new ItemStack(icon.fallback());
        }
    }
}
