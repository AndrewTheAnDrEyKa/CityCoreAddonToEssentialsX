package ru.citycore.business;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Stable business directions used by registration, review and GUI labels. */
public enum BusinessActivity {
    SMALL_RETAIL("Небольшая торговая точка", false, false),
    TRADE("Торговое предприятие", true, false),
    SERVICES("Оказание услуг", true, false),
    MANUFACTURING("Производство", true, false),
    OIL_EXTRACTION("Нефтедобыча", true, true),
    GENERAL("Общее предприятие", true, false);

    private final String displayName;
    private final boolean reviewRequired;
    private final boolean questionnaireRequired;

    BusinessActivity(String displayName, boolean reviewRequired, boolean questionnaireRequired) {
        this.displayName = displayName;
        this.reviewRequired = reviewRequired;
        this.questionnaireRequired = questionnaireRequired;
    }

    public String displayName() { return displayName; }
    public boolean reviewRequired() { return reviewRequired; }
    public boolean questionnaireRequired() { return questionnaireRequired; }

    public static BusinessActivity parse(String raw) {
        String normalized = raw == null ? "" : raw.strip().toUpperCase(Locale.ROOT);
        try {
            return valueOf(normalized);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException("Неизвестное направление предприятия: " + raw);
        }
    }

    public static List<BusinessActivity> registrationChoices() {
        return Arrays.stream(values()).filter(value -> value != GENERAL).toList();
    }
}
