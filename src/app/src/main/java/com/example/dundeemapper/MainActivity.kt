package com.example.dundeemapper

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.location.Location
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.android.material.snackbar.Snackbar

private const val TAG = "MainActivity"
private const val REQUEST_FOREGROUND_ONLY_PERMISSIONS_REQUEST_CODE = 34

class MainActivity : AppCompatActivity(),
    SharedPreferences.OnSharedPreferenceChangeListener {

    private var foregroundOnlyLocationServiceBound = false
    private var foregroundOnlyLocationService: ForegroundOnlyLocationService? = null
    private var pendingSubscribe = false

    private lateinit var foregroundOnlyBroadcastReceiver: ForegroundOnlyBroadcastReceiver
    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var foregroundOnlyLocationButton: Button
    private lateinit var outputTextView: TextView

    private val foregroundOnlyServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            val binder = service as ForegroundOnlyLocationService.LocalBinder
            foregroundOnlyLocationService = binder.service
            foregroundOnlyLocationServiceBound = true

            // Show whatever the service is doing right now (it may already be tracking).
            logResultsToScreen("Status: ${binder.service.status.label}")

            if (pendingSubscribe) {
                pendingSubscribe = false
                if (foregroundPermissionApproved()) {
                    binder.service.subscribeToLocationUpdates()
                }
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            foregroundOnlyLocationService = null
            foregroundOnlyLocationServiceBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        foregroundOnlyBroadcastReceiver = ForegroundOnlyBroadcastReceiver()
        sharedPreferences = getSharedPreferences(
            getString(R.string.preference_file_key),
            Context.MODE_PRIVATE
        )

        foregroundOnlyLocationButton =
            findViewById(R.id.foreground_only_location_button)
        outputTextView = findViewById(R.id.output_text_view)

        foregroundOnlyLocationButton.setOnClickListener {
            val trackingEnabled = sharedPreferences.getBoolean(
                SharedPreferenceUtil.KEY_FOREGROUND_ENABLED,
                false
            )

            if (trackingEnabled) {
                foregroundOnlyLocationService?.unsubscribeToLocationUpdates()
            } else if (foregroundPermissionApproved()) {
                subscribeWhenReady()
            } else {
                requestForegroundPermissions()
            }
        }
    }

    override fun onStart() {
        super.onStart()

        updateButtonState(
            sharedPreferences.getBoolean(
                SharedPreferenceUtil.KEY_FOREGROUND_ENABLED,
                false
            )
        )
        sharedPreferences.registerOnSharedPreferenceChangeListener(this)

        bindService(
            Intent(this, ForegroundOnlyLocationService::class.java),
            foregroundOnlyServiceConnection,
            Context.BIND_AUTO_CREATE
        )
    }

    override fun onResume() {
        super.onResume()
        LocalBroadcastManager.getInstance(this).registerReceiver(
            foregroundOnlyBroadcastReceiver,
            IntentFilter().apply {
                addAction(ForegroundOnlyLocationService.ACTION_FOREGROUND_ONLY_LOCATION_BROADCAST)
                addAction(ForegroundOnlyLocationService.ACTION_STATUS_BROADCAST)
            }
        )
    }

    override fun onPause() {
        LocalBroadcastManager.getInstance(this)
            .unregisterReceiver(foregroundOnlyBroadcastReceiver)
        super.onPause()
    }

    override fun onStop() {
        if (foregroundOnlyLocationServiceBound) {
            unbindService(foregroundOnlyServiceConnection)
            foregroundOnlyLocationServiceBound = false
        }

        sharedPreferences.unregisterOnSharedPreferenceChangeListener(this)
        super.onStop()
    }

    override fun onSharedPreferenceChanged(
        sharedPreferences: SharedPreferences,
        key: String?
    ) {
        if (key == SharedPreferenceUtil.KEY_FOREGROUND_ENABLED) {
            updateButtonState(
                sharedPreferences.getBoolean(
                    SharedPreferenceUtil.KEY_FOREGROUND_ENABLED,
                    false
                )
            )
        }
    }

    private fun subscribeWhenReady() {
        val service = foregroundOnlyLocationService
        if (service != null) {
            service.subscribeToLocationUpdates()
        } else {
            Log.d(TAG, "Service not bound yet; will subscribe once connected")
            pendingSubscribe = true
        }
    }

    private fun requiredPermissions(): Array<String> {
        val permissions = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Needed on Android 13+ for the tracking notification to be shown.
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        return permissions.toTypedArray()
    }

    private fun foregroundPermissionApproved(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestForegroundPermissions() {
        if (ActivityCompat.shouldShowRequestPermissionRationale(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
        ) {
            Snackbar.make(
                foregroundOnlyLocationButton,
                R.string.permission_rationale,
                Snackbar.LENGTH_LONG
            )
                .setAction(R.string.ok) {
                    ActivityCompat.requestPermissions(
                        this,
                        requiredPermissions(),
                        REQUEST_FOREGROUND_ONLY_PERMISSIONS_REQUEST_CODE
                    )
                }
                .show()
        } else {
            ActivityCompat.requestPermissions(
                this,
                requiredPermissions(),
                REQUEST_FOREGROUND_ONLY_PERMISSIONS_REQUEST_CODE
            )
        }
    }

    @Deprecated("Use the Activity Result API for new permission requests.")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode != REQUEST_FOREGROUND_ONLY_PERMISSIONS_REQUEST_CODE) return

        val fineIndex = permissions.indexOf(Manifest.permission.ACCESS_FINE_LOCATION)

        when {
            grantResults.isEmpty() || fineIndex < 0 -> {
                Log.d(TAG, "Permission request was cancelled.")
            }

            grantResults[fineIndex] == PackageManager.PERMISSION_GRANTED -> {
                subscribeWhenReady()
            }

            else -> {
                updateButtonState(false)
                Snackbar.make(
                    foregroundOnlyLocationButton,
                    R.string.permission_denied_ex,
                    Snackbar.LENGTH_LONG
                )
                    .setAction(R.string.settings) {
                        val settingsIntent =
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = Uri.fromParts("package", packageName, null)
                            }
                        startActivity(settingsIntent)
                    }
                    .show()
            }
        }
    }

    private fun updateButtonState(trackingLocation: Boolean) {
        foregroundOnlyLocationButton.setText(
            if (trackingLocation) {
                R.string.stop_location_updates_button_text
            } else {
                R.string.start_location_updates_button_text
            }
        )
    }

    private fun logResultsToScreen(output: String) {
        outputTextView.text = "$output\n${outputTextView.text}"
    }

    private inner class ForegroundOnlyBroadcastReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ForegroundOnlyLocationService.ACTION_STATUS_BROADCAST) {
                val status = intent.getStringExtra(ForegroundOnlyLocationService.EXTRA_STATUS)
                    ?.let { name -> runCatching { TrackingStatus.valueOf(name) }.getOrNull() }
                status?.let { logResultsToScreen("Status: ${it.label}") }
                return
            }

            val location = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(
                    ForegroundOnlyLocationService.EXTRA_LOCATION,
                    Location::class.java
                )
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra<Location>(
                    ForegroundOnlyLocationService.EXTRA_LOCATION
                )
            }

            location?.let {
                logResultsToScreen("Foreground location: ${it.toText()}")
            }
        }
    }
}
