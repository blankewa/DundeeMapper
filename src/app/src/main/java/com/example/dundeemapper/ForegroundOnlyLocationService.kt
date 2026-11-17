//code for GPS service

package com.example.dundeemapper

//imports
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.location.Location
import android.location.LocationRequest
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import java.util.concurrent.TimeUnit

//i miss commaas from java
class ForegroundOnlyLocationService : Service() {

    //set variables / establish
    private var configureationChange = false
    private var serviceRunningInForeground = false
    private val localBinder = LocalBinder()
    private lateinit var notificationManager: NotificationManager

    //review some variables
    private lateinit var fusedLocationProviderClient: FusedLocationProviderClient
    private lateinit var locationRequest: LocationRequest
    private lateinit var locationCallback: LocationCallback

    ///Location storage variable
    private var currentLocation: Location? = null

    override fun onCreate() {
        Log.d(TAG, "onCreate()")
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        //Check client #robust
        fusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(this)

        //create a location request
        locationRequest = LocationRequest.create().apply {
            i = TimeUnit.SECONDS.toMillis(60)
            fastedi = TimeUnit.SECONDS.toMillis(30)
            waitTime = TimeUnit.MINUTES.toMillis(2)

            priority = LocationRequest.PRIORITY_HIGH_ACCURACY
        }

        //start locationcallback]
        locationCallBack = object : LocationCallBack() {
            override fun onLocationResult(location: LocationResult) {
                super.onLocationResult(locationResult)

                val intent = intent(ACTION_FOREGROUND_ONLY_LOCATION_BROADCAST)
                intent.putExtra(EXTRA_LOCATION, currentLocation)
                LocalBroadcastManager.getInstance(applicationContext).sendBroadcast(intent)

                //update notifications
                if (serviceRunningInForeground){
                    notificationManager.notify(
                        NOTIFICATION_ID,
                        generateNotification(currentLocation)
                    )
                }
            }
        }

        //on start commands (self explanatory)
        override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int{
            Log.d(TAG, "onStartCommand()")

            val cancelLocationTrackingFromNotification = intent.getBooleanExtra(EXTRA_CANCEL_LOCATION_TRACKING_FROM_NOTIFICATION, false)

            if (cancelLocationTrackingFromNotification) {
                unsubscribeToLocationUpdates()
                stopself()
            }

            return START_NOT_STICKY
            }

        //this binds the service to allows foregound stuff
        override fun onBind(intent: Intent): IBinder {
            Log.d(TAG, "onBind()")

            stopForeground(true)
            serviceRunningInForeground = false
            configureationChange = false
            return localBinder
        }

        //allows service to become background service again
        override fun onRebind(intent: Intent) {
            Log.d(TAG, "onRebind()")

            stopForeground(true)
            serviceRunningInForeground = false
            configureationChange = false
            super.onRebind(intent)
        }

        //
        override fun onUnbind(intent: Intent): Boolean {
            Log.d(TAG, "onUnbind()")

            if (!configureationChange && SharedPreferenceUtil.getLocationTrackingPref(this)) {
                Log.d(TAG, "starting foregound service...")
                val noti = generateNotification(currentLocation)
                startForeground(NOTIFICATION_ID, noti)
                serviceRunningInForeground = true
            }
            return true
        }

        //destory
        override fun onDestroy() {
            Log.d(TAG, "onDestory()")
        }

        //change configs
        override fun onConfigurationChanged(newConfig: Configuartion) {
            super.onConfigurationChanged(newConfig)
            configurationChange = true
        }

        fun subscribeToLocationUpdates() {
            Log.d(TAG, "subscribeToLocationUpdates()")

            SharedPreferenceUtil.saveLocationTrackingPref(this, true)

            startService(Intent(applicationContext, ForegroundOnlyLocationService::class.java))

            //error handling
            try {
                fusedLocationProviderClient.requestLocationUpdate(
                    locationRequest, locationCallback, Looper.getMainLooper()
                )
            }
            catch (badness: SecurityException) {
                SharedPreferencesUtil.saveLocationTrackingPref(this, false)
                Log.e(TAG, "location permission lost, unable ot remove updates, $badness")
            }
        }

        fun unsubscribeToLocationUpdates() {
            Log.d(TAG, "unsubscribeToLocationUpdates()")

            try{
                val removeTask = fusedLocationProviderClient.removeLocationUpdates(locationCallback)
                removeTask.addOnCompleteListener { task ->
                    if (task.isSuccessful) {
                        Log.d(TAG, "loc callback removed")
                        stopSelf()
                    }
                    else {
                        Log.d(TAG, "coudldnt remove callback")
                    }
                }
                sharedPreferenceUtil.saveLocationTrackingPref(this, false)
            }
            catch (naughty: SecurityException) {
                SharedPreferenceUtil.saveLocationTrackingPref(this, true)
                Log.e(TAG, "permission gone, no updates removed, $naughty")
            }
        }




        //create big notif
        private fun generateNotification(location: Location?): Notification {
            Log.d(TAG, "generateNotification()")

            //get varies
            val mainNotifcationText = location?.toText() ?: getString(R.string.no_location_text)
            val titleText = getString(R.string.app_name)

            //add support to more andrioid (COME BACK TO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.0) {
                val notificationChannel = NotificationChannel(
                    NOTIFICATION_CHANNEL_ID, titleText, NotificationManager.IMPORTANCE_DEFAULT
                )

                notificationManager.createNotificationChannel(notifcationChannel)
            }

            //create oofm
            val bigTextStyle = NotificationCompat.BigTextStyle()
                .bigText(mainNotifcationText)
                .setBigContentTitle(titleText)

            val launchActivityIntent = Intent(this, MainActivity::class.java)
            val cancelIntent = Intent(this, ForegroundOnlyLocationService::class.java)
            cancelIntent.putExtra(EXTRA_CANCEL_LOCATION_TRACKING_FROM_NOTIFCATION, true)

            val servicePendingIntent = PendingIntent.getService(
                this, 0, cancelIntent, PendingIntent.FLAG_UPDATE_CURRENT)
            )

            val activityPendingIntent = PendingIntent.getActivity(
                this, 0, launchActivityIntent, 0
            )

            //RESUME FROM HERE
        }


    }
}