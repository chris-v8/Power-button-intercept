// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 Chris (chris-v8)

package io.github.chris_v8.powerbuttonintercept

import android.content.Context
import android.media.AudioManager
import android.os.Handler
import android.provider.Settings
import android.telecom.TelecomManager
import android.util.Log
import io.github.libxposed.api.XposedInterface.ExceptionMode
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface.SystemServerStartingParam
import java.lang.reflect.Field
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Stops the power button from rejecting a ringing call on ColorOS.
 *
 * ColorOS handles the power key during calls in
 * `PhoneWindowManagerExtImpl.oplusInterceptPowerKeyForTelephone(event, interactive)` (oplus-services.jar,
 * running in system server). While a call rings, the first press silences the ringer and sets `mIsMute`;
 * the next press calls `TelecomManager.endCall()` and rejects the call, whatever "Power button ends call"
 * is set to. If the ringer is already silent and "vibrate on ring" is off, even the first press rejects.
 *
 * This module lets the first press silence the ringer as before. Every press that would reject the ringing call
 * while the screen is on sends the call to the notifications instead and turns the screen off (see
 * [CallBackgrounder]); with the screen off, the press turns it on as usual. The call keeps ringing silently
 * and can still be answered. Nothing changes outside a ringing call.
 */
class ModuleMain : XposedModule() {

    private val callBackgrounder = CallBackgrounder(this)

    override fun onSystemServerStarting(param: SystemServerStartingParam) {
        // Never let a problem here escape: an exception in system server restarts the phone.
        try {
            callBackgrounder.install(param.classLoader)
        } catch (t: Throwable) {
            log(Log.ERROR, LOG_TAG, "Could not install the notification hook; presses only turn the screen off", t)
        }
        try {
            hookPowerKeyForTelephone(param.classLoader)
        } catch (t: Throwable) {
            log(Log.ERROR, LOG_TAG, "Could not install the hook; the power button keeps rejecting calls", t)
        }
    }

    private fun hookPowerKeyForTelephone(classLoader: ClassLoader) {
        val ext = classLoader.loadClass(PHONE_WINDOW_MANAGER_EXT)
        val intercept = ext.declaredMethods.singleOrNull {
            it.name == "oplusInterceptPowerKeyForTelephone" && it.parameterTypes.size == 2
        } ?: error("$PHONE_WINDOW_MANAGER_EXT.oplusInterceptPowerKeyForTelephone(event, interactive) not found")
        // Look everything up now, so a ColorOS update that renames something disables the module cleanly.
        val fields = ExtFields(
            context = ext.field("mContext"),
            isMute = ext.field("mIsMute"),
            powerKeyHandled = ext.field("mOplusPowerKeyHandled"),
            phoneWindowManager = ext.field("mBase"),
            handler = classLoader.loadClass(PHONE_WINDOW_MANAGER).field("mHandler"),
            // A system API, so it is missing from the public SDK.
            isRinging = TelecomManager::class.java.getMethod("isRinging"),
        )

        hook(intercept)
            // If the hooker throws before proceed(), ColorOS's original method runs as if there were no hook.
            .setExceptionMode(ExceptionMode.PROTECTIVE)
            .intercept { chain ->
                val self = chain.thisObject
                if (!wouldRejectRingingCall(self, fields)) {
                    if (!(fields.isMute.get(self) as AtomicBoolean).get()) callBackgrounder.reset()
                    return@intercept chain.proceed()
                }
                (fields.isMute.get(self) as AtomicBoolean).set(true)
                val interactive = chain.getArg(1) as Boolean
                if (interactive && sendCallToNotifications(self, fields)) {
                    // Mark the key as used, like ColorOS does after silencing, so the key-up doesn't also turn the
                    // screen off; the module turns it off itself once the call notification is quiet.
                    fields.powerKeyHandled.setBoolean(self, true)
                    log(Log.INFO, LOG_TAG, "Kept the ringing call; power press sends it to the notifications")
                    true
                } else {
                    // Leave the call ringing silently. Returning false tells PhoneWindowManager the key was
                    // not used for the call, so the press goes through the normal screen off / on path.
                    // Still true from the press that silenced the ringer; it would swallow this press.
                    fields.powerKeyHandled.setBoolean(self, false)
                    log(Log.INFO, LOG_TAG, "Kept the ringing call; power press turns the screen off or on instead")
                    false
                }
            }
        log(Log.INFO, LOG_TAG, "Hooked ${intercept.declaringClass.name}.${intercept.name}")
    }

    /** Mirrors ColorOS's own check: true when the original method would call `endCall()` on a ringing call. */
    private fun wouldRejectRingingCall(self: Any, fields: ExtFields): Boolean {
        val context = fields.context.get(self) as Context
        val telecom = context.getSystemService(TelecomManager::class.java) ?: return false
        if (fields.isRinging.invoke(telecom) != true) return false
        if ((fields.isMute.get(self) as AtomicBoolean).get()) return true
        // A ringer that is already silent with "vibrate on ring" off is rejected on the first press.
        val audio = context.getSystemService(AudioManager::class.java) ?: return false
        val ringerSilent = audio.getStreamVolume(AudioManager.STREAM_RING) == 0
        val neverVibrate = Settings.System.getInt(context.contentResolver, CALL_VIBRATE_METHOD, 1) == 1
        return ringerSilent && neverVibrate
    }

    private fun sendCallToNotifications(self: Any, fields: ExtFields): Boolean {
        // A failure here must not reach the framework: its fallback would run ColorOS's code and reject the call.
        return try {
            val phoneWindowManager = fields.phoneWindowManager.get(self)!!
            callBackgrounder.sendToNotificationsAndSleep(
                context = fields.context.get(self) as Context,
                phoneWindowManager = phoneWindowManager,
                handler = fields.handler.get(phoneWindowManager) as Handler,
            )
        } catch (t: Throwable) {
            log(Log.ERROR, LOG_TAG, "Could not send the call to the notifications; turning the screen off instead", t)
            false
        }
    }

    private class ExtFields(
        val context: Field,
        val isMute: Field,
        val powerKeyHandled: Field,
        val phoneWindowManager: Field,
        val handler: Field,
        val isRinging: Method,
    )

    private companion object {
        // No tag of our own: Vector then logs under its own tag with the module name in front,
        // and only its own tags are saved to the module log shown in the manager.
        val LOG_TAG: String? = null
        const val PHONE_WINDOW_MANAGER_EXT = "com.android.server.policy.PhoneWindowManagerExtImpl"
        const val PHONE_WINDOW_MANAGER = "com.android.server.policy.PhoneWindowManager"
        /** ColorOS setting "vibrate on ring"; 1 means never. */
        const val CALL_VIBRATE_METHOD = "call_vibrate_method"
    }
}
