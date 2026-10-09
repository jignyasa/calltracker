package com.example.calltracker.ui

import androidx.fragment.app.Fragment

/** Screen-to-screen navigation, implemented by [MainActivity] with plain fragment transactions. */
interface AppNavigator {
    fun openCallDetail(callId: Long)
    fun openRules()
    fun openSettings()
}

val Fragment.navigator: AppNavigator get() = requireActivity() as AppNavigator
