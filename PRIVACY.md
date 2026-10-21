# MedWake Privacy Policy

**Last updated: 2026-10-21**

MedWake (`org.wakegated.medication`) is a local-only Android application. It
does not have a server component, does not require an account, and does not
send your data anywhere.

## What MedWake stores

All data stays in the app's private storage on your device:

- medications, schedules, dose history, stock counts and notes (Room/SQLite);
- your preferences (alarm theme, snooze length, volume fade-in, etc.);
- an optional custom alarm wallpaper, if you import one.

The app does not request the `INTERNET` permission. It cannot upload data, even
if it wanted to.

## Android permissions and why they are needed

| Permission / special access | Why MedWake uses it |
| --- | --- |
| Notifications | To show the medication notification and alarm controls. |
| Exact alarms (`SCHEDULE_EXACT_ALARM`) | To wake at the scheduled minute. |
| Full-screen intent | To show the alarm over the lock screen when a dose is ringing. |
| Display over other apps (`SYSTEM_ALERT_WINDOW`) | To bring the ringing alarm back to the front if you press Back or switch apps. |
| Battery optimization exemption | So Samsung/One UI and other aggressive battery managers do not kill the alarm in deep sleep. You grant this explicitly in Settings. |
| Boot completed | To restore pending alarms after a reboot. |
| Usage access (`PACKAGE_USAGE_STATS`) | **Optional.** MedWake reads only system keyguard events (`KEYGUARD_HIDDEN` / `KEYGUARD_SHOWN`) to corroborate that you really unlocked the phone. It does not read which apps you use, and it never uploads or shares usage data. You can deny this and the app still works, with a less precise unlock signal. |
| Vibration / wake lock | For the alarm and for reliably completing dose actions. |

## Backups and exports

The Settings screen can export a JSON backup to a file you choose, and import it
again. That file is created only when you tap the button; you control where it
goes. MedWake does not upload it.

Android's system backup may include the app's database if your device has cloud
backup enabled. You can disable that in Android Settings; MedWake itself does
not enable any third-party backup service.

## Data deletion

Uninstalling MedWake deletes its private database and preferences. You can also
delete individual dose records from the History screen, or clear all app data
through Android Settings.

## Children and medical disclaimer

MedWake is a reminder tool, not a medical device and not medical advice. It can
fail to ring if the operating system, the device vendor, or the user prevents
it from running (for example by force-stopping the app or revoking
permissions). Always follow your prescriber's instructions and keep a backup
reminder method for critical medication.

## Contact

Questions or data requests: open an issue at
<https://github.com/adeldev-lab/MedWake/issues>.
