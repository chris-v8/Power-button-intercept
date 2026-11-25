// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Chris (chris-v8)

package io.github.chris_v8.powerbuttonintercept

import android.app.ActivityManager
import android.app.Notification
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.PowerManager
import android.os.SystemClock
import android.service.notification.StatusBarNotification
import android.telephony.TelephonyManager
import android.util.Log
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Field
import java.lang.reflect.Method

/**
 * Sends a ringing call "to the notifications": the banner and the full-screen call screen go away, the screen
 * turns off, and the call stays as a quiet notification (status bar icon, notification shade, lock screen) until
 * it stops ringing.
 *
 * Google Phone posts its ringing call notification on the `phone_incoming_call` channel (pops up as a banner,
 * carries a full-screen intent) and switches to its quieter `phone_ongoing_call` channel while its call screen
 * is visible. While a call is sent to the notifications, every incoming-call notification that reaches
 * `NotificationManagerService.enqueueNotificationInternal` is turned into the quiet form: the full-screen intent
 * is dropped and the channel is switched. That removes the banner, and it stops SystemUI from opening the
 * full-screen call screen again when the screen turns off or the phone locks.
 */
internal class CallBackgrounder(private val module: XposedModule) {

    /** True from the press that sent the call to the notifications until the phone stops ringing. */
    @Volatile
    private var active = false

    @Volatile
    private var notificationService: Any? = null

    @Volatile
    private var context: Context? = null
    private var phoneStateReceiverRegistered = false

    private lateinit var enqueue: Method
    private lateinit var notificationLock: Field
    private lateinit var notificationList: Field
    private lateinit var getSbn: Method
    private lateinit var channelId: Field
    private val getOpPkg: Method? = runCatching { StatusBarNotification::class.java.getMethod("getOpPkg") }.getOrNull()
    private val getInitialPid: Method? =
        runCatching { StatusBarNotification::class.java.getMethod("getInitialPid") }.getOrNull()

    /** False when the hook could not be installed; the power button then only turns the screen off. */
    var installed = false
        private set

    fun install(classLoader: ClassLoader) {
        val nms = classLoader.loadClass(NOTIFICATION_MANAGER_SERVICE)
        // Apps post through the 8-argument version. On ColorOS it hands most notifications to its own copy of
        // the posting code and never reaches the 9-argument version, so the hook has to sit here.
        enqueue = nms.declaredMethods.singleOrNull {
            it.name == "enqueueNotificationInternal" && it.parameterTypes.size == 8
        }?.apply { isAccessible = true }
            ?: error("$NOTIFICATION_MANAGER_SERVICE.enqueueNotificationInternal(8 args) not found")
        notificationLock = nms.field("mNotificationLock")
        notificationList = nms.field("mNotificationList")
        getSbn = classLoader.loadClass(NOTIFICATION_RECORD).getDeclaredMethod("getSbn").apply { isAccessible = true }
        channelId = Notification::class.java.field("mChannelId")

        module.hook(enqueue)
            .setExceptionMode(ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                if (notificationService == null) module.log(Log.DEBUG, LOG_TAG, "Notification hook is active")
                notificationService = chain.thisObject
                val notification = chain.getArg(6) as? Notification
                if (active && notification?.category == Notification.CATEGORY_CALL) {
                    module.log(
                        Log.DEBUG, LOG_TAG,
                        "Call notification from ${chain.getArg(0)}: channel=${notification.channelId} " +
                            "fullScreen=${notification.fullScreenIntent != null}",
                    )
                    if (!isRinging()) {
                        stop("the call stopped ringing")
                    } else if (isIncomingCall(notification)) {
                        makeQuiet(notification)
                        module.log(Log.INFO, LOG_TAG, "Kept the call in the notifications (${chain.getArg(0)})")
                    }
                }
                chain.proceed()
            }
        // The method is short, so the binder entry point apps call may have it compiled in, bypassing the hook.
        val binderEnqueue = binderService(classLoader).getDeclaredMethod(
            "enqueueNotificationWithTag", String::class.java, String::class.java, String::class.java,
            Int::class.javaPrimitiveType, Notification::class.java, Int::class.javaPrimitiveType,
        )
        module.log(Log.DEBUG, LOG_TAG, "Deoptimized ${binderEnqueue.name}: ${module.deoptimize(binderEnqueue)}")
        installed = true
        module.log(Log.INFO, LOG_TAG, "Hooked ${enqueue.declaringClass.name}.${enqueue.name}")
    }

    /** The anonymous `INotificationManager.Stub` inside NotificationManagerService that apps talk to. */
    private fun binderService(classLoader: ClassLoader): Class<*> =
        (1..100).firstNotNullOfOrNull { i ->
            runCatching { classLoader.loadClass("$NOTIFICATION_MANAGER_SERVICE$$i") }.getOrNull()
                ?.takeIf { it.superclass?.name == "android.app.INotificationManager\$Stub" }
        } ?: error("$NOTIFICATION_MANAGER_SERVICE binder service not found")

    /**
     * Called on a power key press that would have rejected the ringing call while the screen is on. Switches the
     * call to the quiet notification (the first time), closes the call screen if it is in front, then turns the
     * screen off. Returns false when this can't be done, so the caller falls back to a plain power press.
     */
    fun sendToNotificationsAndSleep(context: Context, phoneWindowManager: Any, handler: Handler): Boolean {
        if (!installed) return false
        if (notificationService == null) {
            module.log(Log.WARN, LOG_TAG, "No notification seen since boot; can't send the call to the notifications")
            return false
        }
        this.context = context
        val first = !active
        active = true
        registerPhoneStateReceiver(context, handler)
        // Leave the key-handling thread quickly; the rest runs on PhoneWindowManager's own thread.
        handler.post {
            var reposted = 0
            try {
                if (first) reposted = repostIncomingCalls()
                val closed = closeCallScreen(context, phoneWindowManager)
                module.log(
                    Log.INFO, LOG_TAG,
                    "Call kept in the notifications (banner reposted: $reposted, call screen closed: $closed)",
                )
            } catch (t: Throwable) {
                module.log(Log.ERROR, LOG_TAG, "Could not send the call to the notifications", t)
            }
            // SystemUI opens the full-screen call screen when the screen turns off while the old banner is still
            // up, so give it time to receive the quiet notification first.
            handler.postDelayed({ goToSleep(context) }, if (reposted > 0) QUIET_NOTIFICATION_DELAY_MS else 0)
        }
        return true
    }

    private fun goToSleep(context: Context) {
        try {
            val power = context.getSystemService(PowerManager::class.java)
            POWER_GO_TO_SLEEP.invoke(power, SystemClock.uptimeMillis(), GO_TO_SLEEP_REASON_POWER_BUTTON, 0)
        } catch (t: Throwable) {
            module.log(Log.ERROR, LOG_TAG, "Could not turn the screen off", t)
        }
    }

    /** Posts the current incoming-call notification again, so the enqueue hook turns it into the quiet form. */
    private fun repostIncomingCalls(): Int {
        val service = notificationService ?: return 0
        val all = synchronized(notificationLock.get(service)!!) {
            (notificationList.get(service) as List<*>).mapNotNull { getSbn.invoke(it) as? StatusBarNotification }
        }
        val calls = all.filter { isIncomingCall(it.notification) }
        module.log(
            Log.DEBUG, LOG_TAG,
            "${all.size} notification(s); call ones: " + all.filter { it.notification.category == Notification.CATEGORY_CALL }
                .joinToString { "${it.packageName}/${it.notification.channelId}" },
        )
        for (sbn in calls) {
            enqueue.invoke(
                service,
                sbn.packageName,
                getOpPkg?.invoke(sbn) as? String ?: sbn.packageName,
                sbn.uid,
                getInitialPid?.invoke(sbn) as? Int ?: 0,
                sbn.tag,
                sbn.id,
                sbn.notification.clone(),
                @Suppress("DEPRECATION") sbn.userId,
            )
        }
        return calls.size
    }

    /** Closes the full-screen call screen like the Home button would, if it is in front. */
    private fun closeCallScreen(context: Context, phoneWindowManager: Any): Boolean {
        val activityManager = context.getSystemService(ActivityManager::class.java) ?: return false
        @Suppress("DEPRECATION") // Still returns every task for system server.
        val top = activityManager.getRunningTasks(1).firstOrNull()?.topActivity ?: return false
        if (top.className != IN_CALL_ACTIVITY) return false
        // On the lock screen the call screen sits above the keyguard; Home leaves the keyguard showing.
        // ColorOS uses a subclass of PhoneWindowManager, so look the method up in the superclasses too.
        val launchHome = generateSequence<Class<*>>(phoneWindowManager.javaClass) { it.superclass }
            .firstNotNullOfOrNull { cls ->
                cls.declaredMethods.firstOrNull {
                    it.name == "launchHomeFromHotKey" && it.parameterTypes.contentEquals(arrayOf(Int::class.javaPrimitiveType))
                }
            } ?: error("PhoneWindowManager.launchHomeFromHotKey(int) not found")
        launchHome.isAccessible = true
        launchHome.invoke(phoneWindowManager, 0)
        return true
    }

    private fun registerPhoneStateReceiver(context: Context, handler: Handler) {
        if (phoneStateReceiverRegistered) return
        val receiver = object : BroadcastReceiver() {
            // Sent per SIM, so ask Telecom whether any call still rings instead of reading the extra.
            override fun onReceive(context: Context, intent: Intent) {
                if (active && !isRinging()) stop("the call stopped ringing")
            }
        }
        context.registerReceiver(receiver, IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED), null, handler)
        phoneStateReceiverRegistered = true
    }

    /** Called on the press that silences a new call, so a leftover state never affects the next call. */
    fun reset() {
        if (active) stop("a new call is ringing")
    }

    private fun stop(reason: String) {
        active = false
        module.log(Log.DEBUG, LOG_TAG, "Call no longer kept in the notifications: $reason")
    }

    private fun isRinging(): Boolean {
        val telecom = context?.getSystemService(android.telecom.TelecomManager::class.java) ?: return false
        return TELECOM_IS_RINGING.invoke(telecom) == true
    }

    private fun isIncomingCall(notification: Notification): Boolean =
        notification.category == Notification.CATEGORY_CALL &&
            (notification.fullScreenIntent != null || notification.channelId == INCOMING_CALL_CHANNEL)

    private fun makeQuiet(notification: Notification) {
        notification.fullScreenIntent = null
        if (notification.channelId == INCOMING_CALL_CHANNEL) channelId.set(notification, ONGOING_CALL_CHANNEL)
    }

    private companion object {
        val LOG_TAG: String? = null
        const val NOTIFICATION_MANAGER_SERVICE = "com.android.server.notification.NotificationManagerService"
        const val NOTIFICATION_RECORD = "com.android.server.notification.NotificationRecord"
        const val IN_CALL_ACTIVITY = "com.android.dialer.incall.activity.ui.InCallActivity"
        const val INCOMING_CALL_CHANNEL = "phone_incoming_call"
        const val ONGOING_CALL_CHANNEL = "phone_ongoing_call"

        /** A system API, so it is missing from the public SDK. */
        val TELECOM_IS_RINGING: Method = android.telecom.TelecomManager::class.java.getMethod("isRinging")

        /** `PowerManager.goToSleep(time, reason, flags)`, hidden from the public SDK. */
        val POWER_GO_TO_SLEEP: Method = PowerManager::class.java.getMethod(
            "goToSleep", Long::class.javaPrimitiveType, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
        )
        const val GO_TO_SLEEP_REASON_POWER_BUTTON = 4
        const val QUIET_NOTIFICATION_DELAY_MS = 500L
    }
}

internal fun Class<*>.field(name: String): Field = getDeclaredField(name).apply { isAccessible = true }
