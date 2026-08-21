package com.example.maintenanceapp.util;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.FragmentActivity;

import com.example.maintenanceapp.R;

/**
 * Optional fingerprint/face gate in front of the saved session.
 *
 * <p><b>What this protects, and what it doesn't.</b> {@code LoginActivity} walks straight into
 * {@code MainActivity} whenever the {@code "auth"} prefs hold a username — so anyone holding the
 * unlocked phone is already signed in. This gate makes that shortcut conditional on the device
 * owner's biometrics. It is <em>not</em> encryption: the Bearer token still sits in plain
 * SharedPreferences, so it defends against a person picking up the phone, not against someone
 * with access to the app's data directory.
 *
 * <p>The preference lives in a separate {@code "settings"} file rather than {@code "auth"}, which
 * logout clears — otherwise signing out would silently switch the lock back off.
 */
public final class BiometricLock {

    private static final String PREFS = "settings";
    private static final String KEY_ENABLED = "biometric_lock";

    /** Weak biometrics (any enrolled fingerprint/face) — this gates a convenience shortcut, not a
     *  crypto key, so requiring BIOMETRIC_STRONG would exclude devices for no security gain. */
    private static final int AUTHENTICATORS = BiometricManager.Authenticators.BIOMETRIC_WEAK;

    /** Outcome of a prompt. Exactly one method is called. */
    public interface Callback {
        void onSuccess();

        /**
         * @param message a human-readable reason, or null when the user simply dismissed the
         *                prompt (chose the negative button / pressed back) and needs no telling.
         */
        void onFailure(String message);
    }

    private BiometricLock() { }

    public static boolean isEnabled(Context ctx) {
        return prefs(ctx).getBoolean(KEY_ENABLED, false);
    }

    public static void setEnabled(Context ctx, boolean enabled) {
        prefs(ctx).edit().putBoolean(KEY_ENABLED, enabled).apply();
    }

    /** @return true when the device has usable, enrolled biometrics right now. */
    public static boolean isAvailable(Context ctx) {
        return BiometricManager.from(ctx).canAuthenticate(AUTHENTICATORS)
                == BiometricManager.BIOMETRIC_SUCCESS;
    }

    /**
     * Shows the system biometric prompt.
     *
     * <p>The negative button is the password form — deliberately, instead of
     * {@code DEVICE_CREDENTIAL}: mixing device credential into the authenticators is unsupported on
     * API 28–29 and would need version-specific handling, whereas "log in with your password" is a
     * fallback this app already has on screen and works on every API level.
     */
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
