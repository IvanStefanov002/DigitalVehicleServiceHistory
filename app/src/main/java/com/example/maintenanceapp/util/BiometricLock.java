/*
 * BiometricLock.java
 *
 *  Created on: XX.08.2026
 *      Author: ivstefanov
 */

package com.example.maintenanceapp.util;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.FragmentActivity;

import com.example.maintenanceapp.R;

public final class BiometricLock {

    private static final String PREFS = "settings";
    private static final String KEY_ENABLED = "biometric_lock";

    /**
     * BIOMETRIC_STRONG: Sensor/matching meets Android's strictest spoof-resistance bar and runs in a secure environment.
     * BIOMETRIC_WEAK: Confirms "probably the same person" but doesn't meet the crypto-grade bar.
     */
    private static final int AUTHENTICATORS = BiometricManager.Authenticators.BIOMETRIC_WEAK;

    public interface Callback {
        void onSuccess();
        void onFailure(String message);
    }

    private BiometricLock() { }

    public static boolean isEnabled(Context ctx) {
        return prefs(ctx).getBoolean(KEY_ENABLED, false);
    }

    public static void setEnabled(Context ctx, boolean enabled) {
        prefs(ctx).edit().putBoolean(KEY_ENABLED, enabled).apply();
    }

    public static boolean isAvailable(Context ctx) {
        return BiometricManager.from(ctx).canAuthenticate(AUTHENTICATORS)
                == BiometricManager.BIOMETRIC_SUCCESS;
    }

    public static void prompt(FragmentActivity activity, Callback callback) {
        BiometricPrompt prompt = new BiometricPrompt(
                activity,
                ContextCompat.getMainExecutor(activity),
                new BiometricPrompt.AuthenticationCallback() {
                    @Override
                    public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result) {
                        callback.onSuccess();
                    }

                    @Override
                    public void onAuthenticationError(int errorCode, CharSequence errString) {
                        // Dismissing on purpose isn't an error worth reporting back to the user —
                        // they already know what they did, and the password form is right there.
                        boolean dismissed = errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON
                                || errorCode == BiometricPrompt.ERROR_USER_CANCELED
                                || errorCode == BiometricPrompt.ERROR_CANCELED;
                        callback.onFailure(dismissed ? null : errString.toString());
                    }

                    // onAuthenticationFailed (a finger that didn't match) is intentionally not
                    // overridden: the prompt stays up and lets the user try again.
                });

        BiometricPrompt.PromptInfo info = new BiometricPrompt.PromptInfo.Builder()
                .setTitle(activity.getString(R.string.bio_prompt_title))
                .setSubtitle(activity.getString(R.string.bio_prompt_subtitle))
                .setNegativeButtonText(activity.getString(R.string.bio_use_password))
                .setAllowedAuthenticators(AUTHENTICATORS)
                .setConfirmationRequired(false)
                .build();

        prompt.authenticate(info);
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
