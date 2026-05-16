package dev.medveed.safeshare.util;

import android.content.Context;

import java.io.EOFException;
import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.security.GeneralSecurityException;

import dev.medveed.safeshare.R;

public final class ErrorMessages {

    private ErrorMessages() { /* no instances */ }

    public static String describe(Context ctx, Throwable t) {
        if (t == null) return ctx.getString(R.string.err_unknown);

        if (t instanceof UnknownHostException) {
            return ctx.getString(R.string.err_unknown_host);
        }
        if (t instanceof ConnectException) {
            return ctx.getString(R.string.err_connect_refused);
        }
        if (t instanceof SocketTimeoutException) {
            return ctx.getString(R.string.err_timeout);
        }
        if (t instanceof EOFException) {
            return ctx.getString(R.string.err_unexpected_eof);
        }
        if (t instanceof GeneralSecurityException) {
            return ctx.getString(R.string.err_decrypt);
        }
        if (t instanceof IOException) {
            String m = t.getMessage();
            if (m != null && (m.contains("HTTP 404") || m.contains("not found"))) {
                return ctx.getString(R.string.err_not_found);
            }
            if (m != null && (m.contains("HTTP 410") || m.contains("Gone")
                    || m.contains("expired"))) {
                return ctx.getString(R.string.err_gone);
            }
            if (m != null && m.contains("HTTP 413")) {
                return ctx.getString(R.string.err_too_large);
            }
            if (m != null && m.startsWith("HTTP 5")) {
                return ctx.getString(R.string.err_server_error);
            }
            return m != null ? m : ctx.getString(R.string.err_io);
        }
        String m = t.getMessage();
        return m != null && !m.isEmpty() ? m : ctx.getString(R.string.err_unknown);
    }
}
