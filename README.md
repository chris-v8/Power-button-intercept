# Power button intercept

An LSPosed / Vector module for **ColorOS** that stops the power button from
rejecting a ringing call, and moves the call to the notifications instead.

## The problem

On ColorOS, while a call is ringing:

1. the first power button press silences the ringer,
2. the second press **rejects the call**.

This happens even with "Power button ends call" turned off: ColorOS decides it in
its own power key code and doesn't look at that setting while a call is ringing.
If the ring volume is 0 and "vibrate on ring" is set to never, even the first
press rejects the call.

## What the module does

- The first press still silences the ringer.
- The second press **sends the call to the notifications** and turns the screen
  off: the banner or the full-screen call screen goes away and the call keeps
  ringing silently as a notification (status bar icon, notification shade and
  lock screen). You can still answer it from there.
- After that, turning the screen on shows your lock screen, not the full-screen
  call screen.
- If you open the call screen again by tapping the notification, a power press
  closes it and turns the screen off again.
- The power button never rejects a ringing call, including the silent ringer
  case above.

Nothing else changes: the power button outside calls, during an answered call,
long press, alarms and the camera double press all behave as before.

## Requirements

- ColorOS 12 with **Google Phone** as the phone app. Tested on a realme RMX3363
  running ColorOS 12 / Android 12 (RMX3363_11.C.15). Other ColorOS versions may
  use different code; if the module can't find what it needs, it logs an error
  and leaves that part alone. With another phone app, the power button still
  never rejects a ringing call, but the call may not move to the notifications.
- Root and an LSPosed-compatible framework that supports the modern Xposed API
  (API 101 or later), for example [Vector](https://github.com/JingMatrix/Vector).

## Installing

1. Install the APK.
2. In the LSPosed / Vector manager, enable **Power button intercept**. Its scope
   is preset to **System Framework**.
3. Reboot.

To undo, disable the module and reboot.

### If the phone doesn't boot

The module runs inside Android's system process. It is written so that any error
falls back to ColorOS's normal behaviour, but if the phone ever gets stuck
restarting:

- With a computer and ADB, disable the module through Vector's command line
  tool, then reboot:
  `adb shell su -c "/data/adb/lspd/cli modules disable io.github.chris_v8.powerbuttonintercept"`
- Or boot your root solution's safe mode, which turns off all root modules
  (including Vector) for one boot. For APatch: hold power until the screen
  turns on, then keep pressing and releasing volume down until the first screen
  appears. Disable or uninstall the module, then reboot normally.

## Building

You need JDK 17 or newer and the Android SDK (platform 37, build tools 36).
Point `local.properties` at the SDK (`sdk.dir=C:/path/to/android-sdk`), then run:

```
gradlew assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/`.

To build a signed release, create a `keystore.properties` file in the project
root with `storeFile`, `storePassword`, `keyAlias` and `keyPassword` and run
`gradlew assembleRelease`. The build also accepts the environment variables
`PBI_KEYSTORE_FILE`, `PBI_KEYSTORE_PASSWORD`, `PBI_KEY_ALIAS` and
`PBI_KEY_PASSWORD`. Keystores and `keystore.properties` are excluded from git.

## How it works (technical)

The module is scoped to `system` (system server) and installs two hooks.

**Power key.** It hooks
`com.android.server.policy.PhoneWindowManagerExtImpl.oplusInterceptPowerKeyForTelephone(KeyEvent, boolean)`
from `oplus-services.jar`. `PhoneWindowManager.interceptPowerKeyDown` calls it
for every power key press. While `TelecomManager.isRinging()` is true, ColorOS's
version silences the ringer and sets `mIsMute` on the first press, and calls
`TelecomManager.endCall()` on the next one (or on the first one when the ringer
is silent and `call_vibrate_method` is 1). When the original method would call
`endCall()`, the hook skips it and keeps `mIsMute` set:

- With the screen on, it returns `true` (key used) with `mOplusPowerKeyHandled`
  set, so the key-up doesn't turn the screen off, and sends the call to the
  notifications (below).
- With the screen off, it clears `mOplusPowerKeyHandled` (left over from the
  silencing press, it would otherwise swallow this press in `oplusPowerPress`)
  and returns `false`, so `PhoneWindowManager` wakes the screen as usual.

**Call notification.** Google Phone posts its ringing call notification on the
`phone_incoming_call` channel (banner plus full-screen intent) and switches to
`phone_ongoing_call` while its call screen is visible. SystemUI opens the
full-screen intent when the screen turns off while the banner is up, or when the
notification is posted while the screen is off or locked. The module hooks
`NotificationManagerService.enqueueNotificationInternal` (the 8-argument
version: ColorOS hands notifications from there to its own copy of the posting
code, so the 9-argument version is never reached), and deoptimizes the binder
entry point `enqueueNotificationWithTag` so the call can't be compiled in. From
the press until the phone stops ringing, every `call` notification on the
incoming channel loses its full-screen intent and moves to the ongoing channel.
On the press it reposts the current call notification so the banner goes away,
closes `InCallActivity` with `PhoneWindowManager.launchHomeFromHotKey` if it is
in front (on the lock screen this leaves the keyguard showing), and turns the
screen off with `PowerManager.goToSleep` 500 ms later, so SystemUI has the quiet
notification before the screen goes off.

All hooks use the `PROTECTIVE` exception mode, and errors in the notification
part fall back to a plain screen-off press, never to rejecting the call.
Messages appear in the Vector module log, prefixed with the module's package
name.

## Licence

Copyright (C) 2026 Chris (chris-v8)

This program is free software: you can redistribute it and/or modify it under
the terms of the GNU General Public License as published by the Free Software
Foundation, either version 3 of the License, or (at your option) any later
version. See [LICENSE](LICENSE).
