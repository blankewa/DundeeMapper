package com.example.dundeemapper

import android.content.Context
import android.location.Location
import androidx.core.content.edit

fun Location?.toText(): String {
    return if (this != null) {
        "($latitude, $longitude)"
    } else {
        "location unknown"
    }
}

internal object SharedPreferenceUtil {
    private const val KEY_FOREGROUND_ENABLED = "tracking_foreground_location"

    fun getLocationTrackingPref(context: Context): Boolean {
        return context.getSharedPreferences(
            context.getString(R.string.preference_file_key),
            Context.MODE_PRIVATE
        ).getBoolean(KEY_FOREGROUND_ENABLED, false)
    }

    fun saveLocationTrackingPref(
        context: Context,
        requestingLocationUpdates: Boolean
    ) {
        context.getSharedPreferences(
            context.getString(R.string.preference_file_key),
            Context.MODE_PRIVATE
        ).edit {
            putBoolean(KEY_FOREGROUND_ENABLED, requestingLocationUpdates)
        }
    }
}