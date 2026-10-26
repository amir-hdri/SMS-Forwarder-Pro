package com.example.enrollment

import android.content.Intent

object EnrollmentIntentRouter {
    /** Called by both Activity entry points; strips the fragment even when validation later fails. */
    fun consume(intent: Intent?, activate: (String) -> Unit) {
        if (intent?.action != Intent.ACTION_VIEW || intent.data?.scheme != "barpro-forwarder") return
        val value = intent.data.toString()
        intent.data = null
        activate(value)
    }
}
