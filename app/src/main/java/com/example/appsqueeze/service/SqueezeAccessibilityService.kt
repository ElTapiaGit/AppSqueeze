package com.example.appsqueeze.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent
import com.example.appsqueeze.MainActivity

class SqueezeAccessibilityService : AccessibilityService() {

    companion object {
        var estaDesbloqueado: Boolean = false
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!estaDesbloqueado && event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val paqueteActual = event.packageName?.toString() ?: ""

            if (paqueteActual.isNotEmpty() && paqueteActual != applicationContext.packageName) {
                val intent = Intent(this, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                }
                startActivity(intent)
            }
        }
    }

    override fun onInterrupt() {}
}