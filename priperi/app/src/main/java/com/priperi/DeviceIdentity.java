package com.priperi;

import android.content.Context;
import android.provider.Settings;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.UUID;

/** Scoped Android identifier; possession of this value never authorizes an account. */
final class DeviceIdentity {
    private DeviceIdentity() {}
    static String value(Context context, String namespace) throws Exception {
        String id = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ANDROID_ID);
        if (id == null || !id.matches("[0-9a-fA-F]{16}") || "9774d56d682e549c".equalsIgnoreCase(id))
            throw new java.security.GeneralSecurityException("A secure device identifier is unavailable.");
        byte[] bytes = MessageDigest.getInstance("SHA-256").digest(("priperi-device-v1|" + context.getPackageName() + "|" + namespace + "|" + id.toLowerCase(java.util.Locale.ROOT)).getBytes(Crypto.UTF8));
        bytes[6] = (byte)((bytes[6] & 0x0f) | 0x40); bytes[8] = (byte)((bytes[8] & 0x3f) | 0x80);
        ByteBuffer b = ByteBuffer.wrap(bytes); return new UUID(b.getLong(), b.getLong()).toString();
    }
}
