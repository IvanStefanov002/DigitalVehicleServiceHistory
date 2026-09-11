/*
 * ApiClient.java
 *
 *  Created on: XX.08.2026
 *      Author: ivstefanov
 */

package com.example.maintenanceapp.util;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.concurrent.TimeUnit;

import okhttp3.ConnectionPool;
import okhttp3.OkHttpClient;
import okhttp3.Request;

public final class ApiClient {

    private static OkHttpClient instance;

    private ApiClient() { }

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

    public static String token(Context ctx) {
        SharedPreferences prefs = ctx.getSharedPreferences("auth", Context.MODE_PRIVATE);
        return prefs.getString("token", "");
    }
}
