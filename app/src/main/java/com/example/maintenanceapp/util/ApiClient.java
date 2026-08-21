package com.example.maintenanceapp.util;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.concurrent.TimeUnit;

import okhttp3.ConnectionPool;
import okhttp3.OkHttpClient;
import okhttp3.Request;

/**
 * Shared {@link OkHttpClient} for the whole app. Its interceptor stamps every outgoing request
 * with {@code Authorization: Bearer <token>}, reading the token saved at login from the
 * {@code "auth"} SharedPreferences. The backend identifies the user from this token, so callers
 * no longer pass a username (as a query param or in the body).
 *
 * <p>Requests made before login (or after logout) simply go out without the header, since no
 * token is stored — this is why the login request itself can safely use the same client.
 *
 * <p><b>No connection reuse.</b> The hand-rolled C++ server closes the socket after each response
 * instead of honouring HTTP keep-alive. OkHttp would otherwise pool that socket and reuse it for
 * the next request — classically the vehicles GET fired right after the login POST — which then
 * fails mid-read with {@code "unexpected end of stream"}. Sending {@code Connection: close} and
 * using a zero-idle {@link ConnectionPool} forces a fresh connection per request, which removes
 * that intermittent first-fetch error without any backend change.
 */
public final class ApiClient {

    private static OkHttpClient instance;

    private ApiClient() { }

    /** @return the process-wide client that auto-attaches the Bearer token. */
    public static synchronized OkHttpClient get(Context ctx) {
        if (instance == null) {
            // Hold the application context so the interceptor outlives any single Activity.
            final Context app = ctx.getApplicationContext();
            instance = new OkHttpClient.Builder()
                    // Don't keep idle sockets around to be reused (see class javadoc).
                    .connectionPool(new ConnectionPool(0, 1, TimeUnit.SECONDS))
                    // Vehicle photos travel inline as base64, so a body can be a couple of MB from
                    // a server that stalls mid-send; the 10s default read timeout is tight for that.
                    .readTimeout(30, TimeUnit.SECONDS)
                    .addInterceptor(chain -> {
                        Request.Builder builder = chain.request().newBuilder()
                                // Ask the server to close the socket after replying so it isn't reused.
                                .header("Connection", "close");
                        String token = token(app);
                        if (!token.isEmpty()) {
                            builder.header("Authorization", "Bearer " + token);
                        }
                        return chain.proceed(builder.build());
                    })
                    .build();
        }
        return instance;
    }

    /** @return the saved Bearer token, or an empty string if none is stored. */
    public static String token(Context ctx) {
        SharedPreferences prefs = ctx.getSharedPreferences("auth", Context.MODE_PRIVATE);
        return prefs.getString("token", "");
    }
}
