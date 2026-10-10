package com.example.calltracker.ui

import androidx.fragment.app.Fragment

/** Screen-to-screen navigation, implemented by [MainActivity] with plain fragment transactions. */
interface AppNavigator {
    fun openCallDetail(callId: Long, askClassification: Boolean = false)
    fun openRules()
    fun openSettings()
    fun openSyncQueue()
    fun openAbout()

    /** Back to the call list, closing every screen above it. */
    fun showCallList()
}

val Fragment.navigator: AppNavigator get() = requireActivity() as AppNavigator
