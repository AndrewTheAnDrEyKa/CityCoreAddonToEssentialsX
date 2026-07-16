package ru.citycore.config;

public record IndustryLevelSettings(long unitsPerCycle, long pricePerUnitMinor,
                                    long maintenanceMinor, int minimumLicenseDays) {}
