package com.priperi;

public final class Validation {
    private Validation() {}
    public static boolean username(String value) { return value != null && value.matches("[a-z][a-z0-9_]{2,23}"); }
    public static boolean password(String value) {
        if (value == null || value.length() < 12 || value.length() > 72 || value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 72) return false;
        boolean upper = false, lower = false, digit = false, symbol = false;
        for (char c : value.toCharArray()) {
            if (c >= 'A' && c <= 'Z') upper = true;
            else if (c >= 'a' && c <= 'z') lower = true;
            else if (c >= '0' && c <= '9') digit = true;
            else if (!Character.isWhitespace(c)) symbol = true;
        }
        return upper && lower && digit && symbol;
    }
    public static boolean message(String value) { return value != null && !value.trim().isEmpty() && value.length() <= 4000; }
}
