package com.priperi;

import android.net.Uri;

/** SAF results must name a provider document, never a path opened with our own file privileges. */
final class DocumentUris {
    private DocumentUris() {}
    static boolean isProviderDocument(Uri uri) {
        return uri != null && "content".equals(uri.getScheme())
                && uri.getAuthority() != null && !uri.getAuthority().isEmpty();
    }
}
