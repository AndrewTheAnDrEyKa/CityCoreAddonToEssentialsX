package ru.citycore.business;

import java.util.Locale;

/** Closed license vocabulary. Raw database codes must never become player-facing labels. */
public enum BusinessLicenseType {
    TRADE("Лицензия на торговлю", BusinessActivity.TRADE, null),
    OIL_EXTRACTION_I("Лицензия на нефтедобычу · уровень I", BusinessActivity.OIL_EXTRACTION, 1),
    OIL_EXTRACTION_II("Лицензия на нефтедобычу · уровень II", BusinessActivity.OIL_EXTRACTION, 2),
    OIL_EXTRACTION_III("Лицензия на нефтедобычу · уровень III", BusinessActivity.OIL_EXTRACTION, 3);

    private final String displayName;
    private final BusinessActivity activity;
    private final Integer oilLevel;

    BusinessLicenseType(String displayName, BusinessActivity activity, Integer oilLevel) {
        this.displayName = displayName;
        this.activity = activity;
        this.oilLevel = oilLevel;
    }

    public String displayName() { return displayName; }
    public BusinessActivity activity() { return activity; }
    public Integer oilLevel() { return oilLevel; }
    public String code() { return name(); }

    public static BusinessLicenseType parse(String raw) {
        String normalized = raw == null ? "" : raw.strip().toUpperCase(Locale.ROOT);
        try {
            return valueOf(normalized);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("Неизвестный тип лицензии: " + raw);
        }
    }

    public static BusinessLicenseType oil(int level) {
        return switch (level) {
            case 1 -> OIL_EXTRACTION_I;
            case 2 -> OIL_EXTRACTION_II;
            case 3 -> OIL_EXTRACTION_III;
            default -> throw new IllegalArgumentException("Уровень нефтяного объекта должен быть I–III");
        };
    }

    public static String displayName(String raw) {
        try { return parse(raw).displayName(); }
        catch (IllegalArgumentException unknown) { return "Неизвестное разрешение"; }
    }
}
