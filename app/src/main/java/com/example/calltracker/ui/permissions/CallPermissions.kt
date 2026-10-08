package com.example.calltracker.ui.permissions

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

object CallPermissions {

    /** Without these the app can't see calls at all. */
    val required = arrayOf(Manifest.permission.READ_CALL_LOG, Manifest.permission.READ_PHONE_STATE)

    /** Improve classification (contacts) and the post-call prompt (notifications); the app works without them. */
    val optional: Array<String> = buildList {
        add(Manifest.permission.READ_CONTACTS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()

    fun hasRequired(context: Context) = required.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }
}
