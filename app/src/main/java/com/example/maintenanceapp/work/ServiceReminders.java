package com.example.maintenanceapp.work;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.text.TextUtils;

import androidx.core.app.NotificationCompat;
import androidx.core.app.TaskStackBuilder;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import com.example.maintenanceapp.MainActivity;
import com.example.maintenanceapp.R;
import com.example.maintenanceapp.VehicleComplianceActivity;
import com.example.maintenanceapp.model.Vehicle;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Scheduling and notification plumbing for the service reminders that {@link ServiceReminderWorker}
 * produces. The check runs about once a day while the user is logged in, survives reboots (that's
 * WorkManager's job), and is cancelled on logout.
 */
public final class ServiceReminders {

    /** Unique work name — keeps repeated {@link #schedule} calls from stacking up jobs. */
    private static final String WORK_NAME = "service-reminders";

    private static final String CHANNEL_ID = "service_reminders";
    private static final int NOTIFICATION_ID = 2001;

    /**
     * Documents get their own channel and notification id, not just a different message. Own channel
     * because muting "your oil is due" should not also mute "your vignette expired" — one is a nag,
     * the other is a fine. Own id so the two never overwrite each other in the shade.
     */
    private static final String DOC_CHANNEL_ID = "document_reminders";
    private static final int DOC_NOTIFICATION_ID = 2002;

    /**
     * The user's own on/off switch for reminders, shown on the Profile tab.
     *
     * <p>Kept in the <b>"settings"</b> prefs file, the same one the biometric lock uses, and
     * deliberately not in "auth": logout clears "auth", which would silently switch reminders back
     * on for the next sign-in. Default is <b>on</b> — reminders are the reason the app checks
     * anything in the background, and the OS permission prompt is the real gate on Android 13+.
     */
    private static final String PREFS = "settings";
    private static final String KEY_ENABLED = "reminders_enabled";

    private ServiceReminders() { }

    public static boolean isEnabled(Context ctx) {
        return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_ENABLED, true);
    }

    public static void setEnabled(Context ctx, boolean enabled) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putBoolean(KEY_ENABLED, enabled).apply();
    }


    /**
     * Starts the daily check if it isn't already scheduled. Safe to call on every app start:
     * {@code KEEP} leaves an existing schedule alone, so opening the app doesn't push the next run
     * back by a day. (If the interval is ever changed, this must become {@code UPDATE} — with
     * {@code KEEP} the already-enqueued job keeps the old period.)
     */
    public static void schedule(Context ctx) {
        // Switched off on the Profile tab: don't enqueue the job at all, rather than enqueue it and
        // throw its result away. A job that never runs costs nothing and wakes nothing.
        if (!isEnabled(ctx)) {
            return;
        }
        Constraints constraints = new Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build();

        // Daily, with a 6h flex window so the system can batch it with other work rather than
        // waking the device on its own.
        PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(
                ServiceReminderWorker.class, 1, TimeUnit.DAYS, 6, TimeUnit.HOURS)
                .setConstraints(constraints)
                .build();

        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
                WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request);
    }

    /**
     * Stops the check and forgets what was last notified. Called on logout — without this the
     * worker would keep running for a signed-out user (it would no-op on the missing token, but
     * there's no reason to keep waking up), and a later user could be suppressed by the previous
     * user's notification signature.
     */
    public static void cancel(Context ctx) {
        WorkManager.getInstance(ctx).cancelUniqueWork(WORK_NAME);
        ctx.getSharedPreferences("reminders", Context.MODE_PRIVATE).edit().clear().apply();
    }

    /**
     * Creates the notification channel. Must exist before the first notify on API 26+; creating it
     * again is a no-op, so this is called from {@code MainActivity.onCreate} rather than being
     * tied to the worker's first run.
     */
    public static void ensureChannel(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                ctx.getString(R.string.rem_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT);
        channel.setDescription(ctx.getString(R.string.rem_channel_desc));
        ctx.getSystemService(NotificationManager.class).createNotificationChannel(channel);

        NotificationChannel docChannel = new NotificationChannel(
                DOC_CHANNEL_ID,
                ctx.getString(R.string.rem_doc_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT);
        docChannel.setDescription(ctx.getString(R.string.rem_doc_channel_desc));
        ctx.getSystemService(NotificationManager.class).createNotificationChannel(docChannel);
    }

    /**
     * Posts (or replaces) the reminder notification. Overdue vehicles lead, since they're the more
     * urgent of the two states.
     */
    public static void notifyDue(Context ctx, List<String> overdue, List<String> due) {
        // Belt and braces for the Profile switch: an already-enqueued job survives the toggle, so
        // the preference is re-checked here rather than trusted to scheduling alone.
        if (!isEnabled(ctx)) {
            return;
        }
        // The permission check has to be INLINE in the same method as notify(), duplicated across
        // the two notify methods rather than extracted into a helper. Lint's data-flow analysis only
        // recognises a checkSelfPermission guard it can see dominating the call site, so factoring it
        // out fails lintDebug with MissingPermission (tried; that is why this looks copy-pasted).
        // Both switches the user has must still be honoured: the API 33+ runtime permission and the
        // per-app/channel toggle, which lint does not accept as a permission check on its own.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        NotificationManagerCompat manager = NotificationManagerCompat.from(ctx);
        if (!manager.areNotificationsEnabled()) {
            return;
        }

        String title = overdue.isEmpty()
                ? ctx.getResources().getQuantityString(R.plurals.rem_due_title, due.size(), due.size())
                : ctx.getResources().getQuantityString(R.plurals.rem_overdue_title, overdue.size(), overdue.size());

        // "BMW 320d, Audi A3" — the names matter more than the counts when there are only a few.
        List<String> names = new ArrayList<>(overdue);
        names.addAll(due);
        String text = TextUtils.join(", ", names);

        Intent intent = new Intent(ctx, MainActivity.class)
                .setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        // FLAG_IMMUTABLE is mandatory from API 31 and harmless before it.
        PendingIntent contentIntent = PendingIntent.getActivity(
                ctx, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_build)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setContentIntent(contentIntent)
                .setAutoCancel(true);

        manager.notify(NOTIFICATION_ID, builder.build());
    }

    /**
     * Posts the document-validity notification.
     *
     * <p>Unlike the service reminder this one is <b>actionable</b>: when exactly one vehicle is
     * affected the tap lands on that car's Документи screen, where a vignette can be bought or a
     * date corrected, rather than on the fleet list. {@link TaskStackBuilder} synthesises the
     * MainActivity parent underneath it, so Back behaves as if the user had navigated there.
     *
     * @param single the affected vehicle when there is exactly one, else {@code null}
     */
    public static void notifyDocumentsDue(Context ctx, List<String> overdue, List<String> due,
                                          Vehicle single) {
        // Belt and braces for the Profile switch: an already-enqueued job survives the toggle, so
        // the preference is re-checked here rather than trusted to scheduling alone.
        if (!isEnabled(ctx)) {
            return;
        }
        // The permission check has to be INLINE in the same method as notify(), duplicated across
        // the two notify methods rather than extracted into a helper. Lint's data-flow analysis only
        // recognises a checkSelfPermission guard it can see dominating the call site, so factoring it
        // out fails lintDebug with MissingPermission (tried; that is why this looks copy-pasted).
        // Both switches the user has must still be honoured: the API 33+ runtime permission and the
        // per-app/channel toggle, which lint does not accept as a permission check on its own.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        NotificationManagerCompat manager = NotificationManagerCompat.from(ctx);
        if (!manager.areNotificationsEnabled()) {
            return;
        }

        String title = overdue.isEmpty()
                ? ctx.getResources().getQuantityString(R.plurals.rem_doc_due_title, due.size(), due.size())
                : ctx.getResources().getQuantityString(R.plurals.rem_doc_overdue_title, overdue.size(), overdue.size());

        List<String> names = new ArrayList<>(overdue);
        names.addAll(due);
        String text = TextUtils.join(", ", names);

        PendingIntent contentIntent;
        if (single != null) {
            Intent target = new Intent(ctx, VehicleComplianceActivity.class)
                    .putExtra(VehicleComplianceActivity.EXTRA_VEHICLE, single);
            TaskStackBuilder stack = TaskStackBuilder.create(ctx);
            stack.addNextIntent(new Intent(ctx, MainActivity.class));
            stack.addNextIntent(target);
            contentIntent = stack.getPendingIntent(
                    DOC_NOTIFICATION_ID,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        } else {
            Intent intent = new Intent(ctx, MainActivity.class)
                    .setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            contentIntent = PendingIntent.getActivity(ctx, DOC_NOTIFICATION_ID, intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        }

        NotificationCompat.Builder builder = new NotificationCompat.Builder(ctx, DOC_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_shield)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setContentIntent(contentIntent)
                .setAutoCancel(true);

        manager.notify(DOC_NOTIFICATION_ID, builder.build());
    }
}
