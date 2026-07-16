package ru.citycore.communication;

public record ChatIntent(String message, boolean shout) {
    public static ChatIntent parse(String raw, String shoutPrefix) {
        String normalized = raw == null ? "" : raw.strip();
        boolean shout = !normalized.isEmpty() && normalized.startsWith(shoutPrefix);
        String message = shout ? normalized.substring(shoutPrefix.length()).stripLeading() : normalized;
        if (message.isBlank()) throw new IllegalArgumentException("Сообщение после префикса крика пустое.");
        return new ChatIntent(message, shout);
    }
}
