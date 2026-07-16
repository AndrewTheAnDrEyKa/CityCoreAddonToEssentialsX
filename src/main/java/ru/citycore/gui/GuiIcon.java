package ru.citycore.gui;

import org.bukkit.Material;

/**
 * The semantic visual language of CityCore. Domain meaning is selected here,
 * instead of scattering unrelated Minecraft materials through the menus.
 */
public enum GuiIcon {
    BRAND(Material.NETHER_STAR, null),
    CITY(Material.BRICKS, "b25b27ce62ca88743840a95d1c39868f43ca60696a84f564fbd7dda259be00fe"),
    ECONOMY(Material.GOLD_INGOT, "bf75d1b785d18d47b3ea8f0a7e0fd4a1fae9e7d323cf3b138c8c78cfe24ee59"),
    WALLET(Material.BUNDLE, "6e7e3e8ab060e64d0256b3688e62d433eab341a157f2a733ed43450fee4e7264"),
    TREASURY(Material.ENDER_CHEST, "8b5d160bbdaa308350325ee7a96f6059004a31338615d43564a4c722e28f7cec"),
    BUSINESS(Material.CHEST, "3b0a689e5574af803e01efda01114c9dcd357e9c4278965bb5b4dbb5c3387347"),
    APPLICATION(Material.WRITABLE_BOOK, "b4bd9dd128c94c10c945eadaa342fc6d9765f37b3df2e38f7b056dc7c927ed"),
    DOCUMENTS(Material.BOOK, "74b89ad06d318f0ae1eeaf660fea78c34eb55d05f01e1cf999f331fb32d38942"),
    MAYOR(Material.GOLDEN_HELMET, "45587da7fe7336e8ab9f791ea5e2cfc8a827ca959567eb9d53a647babf948d5"),
    GOVERNMENT(Material.SHIELD, "6a6a2483869e23c7a9291f0c3ef39f45bf472a6dbd71b8a783b533fff6dd8199"),
    ADMIN(Material.COMMAND_BLOCK, "4e51a2c5cb7f37e1ded93d00ee27aae57905a689258faf75102f56b30011e867"),
    INDUSTRY(Material.PISTON, "722c0ddd17ce1d41c833596a895ad3963c1adcaf44b67a405df47f0eaaf52c16"),
    OIL(Material.BUCKET, "6ce04b41d19ec7927f982a63a94a3d79f78ecec33363051fde0831bfabdbd"),
    DIRECTORY(Material.COMPASS, null),
    RESIDENTS(Material.PLAYER_HEAD, null),
    LICENSE(Material.ENCHANTED_BOOK, null),
    REGISTRATION(Material.LECTERN, null),
    FOUNDATION(Material.FILLED_MAP, null),
    ROLE(Material.NAME_TAG, null),
    ACCOUNT(Material.HOPPER, null),
    EMISSION(Material.RAW_GOLD, null),
    INSPECTION(Material.SPYGLASS, null),
    DEPOSIT(Material.RAW_IRON, null),
    CONTROLLER(Material.OBSERVER, null),
    POLICY(Material.COMPARATOR, null),
    STATUS(Material.RECOVERY_COMPASS, null),
    LEAVE(Material.OAK_DOOR, null),
    RESIGN(Material.IRON_DOOR, null),
    BACK(Material.ARROW, null),
    HOME(Material.COMPASS, null),
    CLOSE(Material.BARRIER, null),
    CONFIRM(Material.LIME_CONCRETE, null),
    CANCEL(Material.RED_CONCRETE, null),
    LOADING(Material.CLOCK, null),
    ERROR(Material.BARRIER, null),
    EMPTY(Material.LIGHT_GRAY_DYE, null);

    private final Material fallback;
    private final String textureHash;

    GuiIcon(Material fallback, String textureHash) {
        this.fallback = fallback;
        this.textureHash = textureHash;
    }

    public Material fallback() { return fallback; }
    public String textureHash() { return textureHash; }
    public boolean customHead() { return textureHash != null; }
}
