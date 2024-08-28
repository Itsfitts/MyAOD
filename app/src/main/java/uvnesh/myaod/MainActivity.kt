package uvnesh.myaod

import android.animation.Animator
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.Resources.getSystem
import android.graphics.drawable.Drawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.notification.StatusBarNotification
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.addCallback
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.AppCompatImageView
import androidx.core.content.edit
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.forEach
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import androidx.lifecycle.lifecycleScope
import com.bumptech.glide.Glide
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import com.google.api.client.extensions.android.http.AndroidHttp
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.json.jackson2.JacksonFactory
import com.google.api.client.util.DateTime
import com.google.api.services.calendar.CalendarScopes
import com.google.api.services.calendar.model.Event
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.BufferedReader
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.time.LocalTime
import java.util.Calendar
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity(), SensorEventListener {

    private lateinit var lockSound: MediaPlayer
    private lateinit var unlockSound: MediaPlayer

    private lateinit var textViewDate: TextView
    private lateinit var textViewSmallTime: TextView
    private lateinit var textViewLargeTimeHoursOne: TextView
    private lateinit var textViewLargeTimeHoursTwo: TextView
    private lateinit var textViewLargeTimeMinutesOne: TextView
    private lateinit var textViewLargeTimeMinutesTwo: TextView
    private lateinit var textViewInfo: TextView
    private lateinit var textViewBattery: TextView
    private lateinit var textViewWeather: TextView
    private lateinit var weatherRoot: LinearLayout
    private lateinit var textViewAlarm: TextView
    private lateinit var textViewTouchBlock: TextView
    private lateinit var notificationSmall: LinearLayout
    private lateinit var brightnessRestore: AppCompatImageView

    private lateinit var handler: Handler
    private lateinit var timeRunnable: Runnable

    private val notificationPackages = mutableListOf<String>()

    private lateinit var sharedPrefs: SharedPreferences

    private lateinit var sensorManager: SensorManager
    private var proximitySensor: Sensor? = null

    private var isFullScreenNotificationTriggered = false
    private var shouldTriggerLogin = false
    private var isLoginTriggered = false

    private lateinit var weatherService: WeatherService

    private lateinit var googleSignInClient: GoogleSignInClient
    private val resultCodeGoogle = 9001
    private val scope = listOf(CalendarScopes.CALENDAR_READONLY)

    private fun finishApp() {
        textViewTouchBlock.animateAlpha(240)
        textViewTouchBlock.isVisible = true
        enableTouch()
        setDeviceVolume(maxAndNeededVolume, this)
        unlockSound.start()
        Handler(Looper.getMainLooper()).postDelayed({
            setDeviceVolume(currentVolume, this)
            unlockSound.release()
        }, 500)
        Handler(Looper.getMainLooper()).postDelayed({
            executeCommand("su -c settings put system screen_brightness $currentBrightness")
            finishAndRemoveTask()
        }, 120)
    }

    private fun blockTouch() {
        executeCommand("su -c cp -pr /dev/input /data/adb/aodbackup")
        executeCommand("su -c rm $(getevent -pl 2>&1 | sed -n '/^add/{h}/ABS_MT_TOUCH/{x;s/[^/]*//p}')")
    }

    private fun enableTouch() {
        Handler(Looper.getMainLooper()).postDelayed({
            executeCommand("su -c cp -pr /data/adb/aodbackup/input /dev")
        }, 120)
    }

    override fun onPause() {
        super.onPause()
        if (shouldTriggerLogin) {
            isLoginTriggered = true
        }
        if (isFullScreenNotificationTriggered || isLoginTriggered) {
            return
        }
        if (resources.getBoolean(R.bool.should_lock_screen)) {
            if (!isFinishing) {
                finishAndRemoveTask()
                return
            }
        }
        sensorManager.unregisterListener(this)
        if (!isFinishing) {
            finishApp()
        }
    }

    private val appListItems: MutableSet<Pair<String, Drawable>> = mutableSetOf()
    private val isAppsLoaded = MutableLiveData(false)

    private suspend fun loadAppIcons() {
        withContext(Dispatchers.IO) {
            val appList: List<ApplicationInfo> =
                packageManager.getInstalledApplications(PackageManager.GET_META_DATA)
            for (appInfo in appList) {
                val appName: String = appInfo.packageName
                val appIcon: Drawable = packageManager.getApplicationIcon(appInfo)
                appListItems.add(Pair(appName, appIcon))
            }
            isAppsLoaded.postValue(true)
        }
    }

    private fun signIn() {
        val signInIntent = googleSignInClient.signInIntent
        shouldTriggerLogin = true
        startActivityForResult(signInIntent, resultCodeGoogle)
    }

    @Deprecated("Deprecated")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        shouldTriggerLogin = false
        if (requestCode == resultCodeGoogle) {
            val task = GoogleSignIn.getSignedInAccountFromIntent(data)
            try {
                val account = task.getResult(ApiException::class.java)
                account?.let {
                    googleSignInAccount = account
                    CoroutineScope(Dispatchers.IO).launch {
                        getCalendarEvents(it)
                    }
                }
            } catch (e: ApiException) {
                Log.e("SignIn", "signInResult:failed code=" + e.statusCode)
            }
        }
    }

    private fun getEndOfDay(): DateTime {
        val calendar = Calendar.getInstance()
        calendar.time = Date()
        calendar.set(Calendar.HOUR_OF_DAY, 23)
        calendar.set(Calendar.MINUTE, 59)
        calendar.set(Calendar.SECOND, 59)
        calendar.set(Calendar.MILLISECOND, 0)
        return DateTime(calendar.time)
    }

    @SuppressLint("SetTextI18n")
    private suspend fun getCalendarEvents(account: GoogleSignInAccount) {
        val credential = GoogleAccountCredential.usingOAuth2(
            this, scope
        )
        credential.selectedAccount = account.account
        try {
            val transport = AndroidHttp.newCompatibleTransport()
            val jsonFactory = JacksonFactory.getDefaultInstance()
            val service = com.google.api.services.calendar.Calendar.Builder(
                transport, jsonFactory, credential
            ).setApplicationName("MyAOD").build()
            var now: DateTime
            var resultsToFetch = 1
            var shouldFetch = true
            var event: Event? = null
            var currentResultCount = 0
            while (shouldFetch) {
                now = DateTime(System.currentTimeMillis())
                val events = service.events().list("primary").setTimeMax(getEndOfDay())
                    .setMaxResults(resultsToFetch).setTimeMin(now).setOrderBy("startTime")
                    .setSingleEvents(true).execute().items
                event = events.firstOrNull {
                    it.attendees.find { attendee -> attendee.email == account.email }?.responseStatus.orEmpty() != "declined"
                }
                if (event == null && currentResultCount != events.size) {
                    currentResultCount = events.size
                    resultsToFetch++
                } else {
                    shouldFetch = false
                }
            }
            withContext(Dispatchers.Main) {
                if (event == null) {
                    Log.d("Calendar", "No upcoming events found")
                    textViewInfo.isVisible = true
                    textViewInfo.text = "No upcoming events today"
                    textViewInfo.animateAlpha(400)
                    // No Events Today. Fetch Again after next day
                    val checkOnNextDayRunnable = object : Runnable {
                        override fun run() {
                            val currentTime = LocalTime.now()
                            val midnight = LocalTime.MIDNIGHT
                            if (currentTime == midnight) {
                                CoroutineScope(Dispatchers.IO).launch {
                                    getCalendarEvents(account)
                                }
                            } else {
                                // Check if midnight every second and refresh calendar
                                Handler(Looper.getMainLooper()).postDelayed(this, 1000)
                            }
                        }
                    }
                    Handler(Looper.getMainLooper()).post(checkOnNextDayRunnable)
                } else {
                    val start = event.start?.dateTime?.value ?: event.start?.date?.value ?: 0
                    val end = event.end?.dateTime?.value ?: event.end?.date?.value ?: 0
                    val startDate = Calendar.getInstance()
                    startDate.timeInMillis = start
                    val updateTimeRunnable = object : Runnable {
                        override fun run() {
                            now = DateTime(System.currentTimeMillis())
                            val diffMillis = start - now.value
                            var diffMinutes: Int = (diffMillis / (1000 * 60)).toInt()
                            diffMinutes++
                            val nextEvent = if (diffMinutes <= 0) {
                                "Now: ${event.summary}"
                            } else if (diffMinutes <= 30) {
                                "${event.summary} in $diffMinutes minute" + if (diffMinutes > 1) "s" else ""
                            } else {
                                val dateFormat = SimpleDateFormat("h:mm a", Locale.getDefault())
                                val startTime = dateFormat.format(startDate.time)
                                "${event.summary} at $startTime"
                            }
                            if (textViewInfo.isGone) {
                                textViewInfo.isVisible = true
                                textViewInfo.text = nextEvent
                                textViewInfo.animateAlpha(400)
                            } else if (textViewInfo.text != nextEvent) {
                                textViewInfo.text = nextEvent
                            }
                            if (now.value < end) {
                                Handler(Looper.getMainLooper()).postDelayed(this, 1000)
                            } else {
                                CoroutineScope(Dispatchers.IO).launch {
                                    getCalendarEvents(account)
                                }
                            }
                        }
                    }
                    Handler(Looper.getMainLooper()).post(updateTimeRunnable)
                }
            }
        } catch (e: Throwable) {
            e.printStackTrace()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())
        if (!resources.getBoolean(R.bool.should_lock_screen)) {
            enableEdgeToEdge()
        }
        super.onCreate(savedInstanceState)
        lockSound = MediaPlayer.create(this, R.raw.lock)
        setContentView(R.layout.activity_main)
        lifecycleScope.launch {
            loadAppIcons()
        }
        textViewTouchBlock = findViewById(R.id.touchBlock)
        if (resources.getBoolean(R.bool.should_lock_screen)) {
            textViewTouchBlock.isVisible = true
            Handler(Looper.getMainLooper()).postDelayed({
                executeCommand("su -c input keyevent 223")
            }, 100)
            return
        }
        lockSound.start()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        currentBrightness = getCurrentBrightness()
        unlockSound = MediaPlayer.create(this, R.raw.unlock)
        Handler(Looper.getMainLooper()).postDelayed({
            setDeviceVolume(currentVolume, this)
        }, 500)
        onBackPressedDispatcher.addCallback {}
        sharedPrefs = getSharedPreferences("MyPrefs", Context.MODE_PRIVATE)
        textViewDate = findViewById(R.id.date)
        textViewSmallTime = findViewById(R.id.smallTime)
        textViewLargeTimeHoursOne = findViewById(R.id.largeTimeHoursOne)
        textViewLargeTimeHoursTwo = findViewById(R.id.largeTimeHoursTwo)
        textViewLargeTimeMinutesOne = findViewById(R.id.largeTimeMinutesOne)
        textViewLargeTimeMinutesTwo = findViewById(R.id.largeTimeMinutesTwo)
        textViewInfo = findViewById(R.id.info)
        textViewBattery = findViewById(R.id.battery)
        textViewWeather = findViewById(R.id.weather)
        weatherRoot = findViewById(R.id.weather_root)
        textViewAlarm = findViewById(R.id.alarm)
        textViewTouchBlock.setOnTouchListener { v, event ->
            true
        }
        notificationSmall = findViewById(R.id.notificationSmall)
        brightnessRestore = findViewById(R.id.brightnessRestore)
        if (googleSignInAccount == null) {
            val gso =
                GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN).requestEmail()
                    .requestScopes(Scope(CalendarScopes.CALENDAR_READONLY)).build()
            googleSignInClient = GoogleSignIn.getClient(this@MainActivity, gso)
            signIn()
        } else {
            CoroutineScope(Dispatchers.IO).launch {
                googleSignInAccount?.let {
                    getCalendarEvents(it)
                }
            }
        }
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        proximitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY)
        handler = Handler(Looper.getMainLooper())
        timeRunnable = object : Runnable {
            override fun run() {
                updateDateTime()
                handler.postDelayed(this, 1000) // 1 second delay
            }
        }
        if (resources.getBoolean(R.bool.should_unlock_on_tap)) {
            findViewById<View>(R.id.fpView).setOnClickListener {
                finishApp()
            }
        } else {
            findViewById<View>(R.id.fpView).setOnLongClickListener {
                finishApp()
                true
            }
        }
        listOf(
            findViewById<ViewGroup>(R.id.largeTimeHoursRoot),
            findViewById<ViewGroup>(R.id.largeTimeMinutesRoot)
        ).forEach {
            it.forEach {
                it.setOnLongClickListener {
                    toggleClock(false)
                    true
                }
            }
        }
        textViewSmallTime.setOnLongClickListener {
            toggleClock(true)
            true
        }
        handler.post(timeRunnable)
        executeCommand("su -c settings put system screen_brightness ${resources.getInteger(R.integer.aod_brightness)}")
        isAppsLoaded.observe(this, object : Observer<Boolean> {
            override fun onChanged(value: Boolean) {
                if (value) {
                    isAppsLoaded.removeObserver(this)
                    activeNotifications.observe(this@MainActivity) {
                        setNotificationInfo()
                        notificationSmall.animateAlpha(200)
                    }
                    Handler(Looper.getMainLooper()).postDelayed({
                        weatherService =
                            Retrofit.Builder().baseUrl("https://api.openweathermap.org/data/2.5/")
                                .addConverterFactory(GsonConverterFactory.create()).build()
                                .create(WeatherService::class.java)
                        CoroutineScope(Dispatchers.IO).launch {
                            val weatherData = weatherService.getWeather()
                            withContext(Dispatchers.Main) {
                                updateWeatherUI(weatherData)
                            }
                        }
                    }, 200)
                }
            }
        })
        toggleTorch.observe(this) {
            val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val cameraId = cameraManager.cameraIdList.firstOrNull() ?: return@observe
            try {
                cameraManager.setTorchMode(cameraId, it)
            } catch (e: CameraAccessException) {
                e.printStackTrace()
            }
        }
        brightnessRestore.setOnClickListener {
            shouldShowRestoreBrightness.postValue(false)
            executeCommand("su -c settings put system screen_brightness ${resources.getInteger(R.integer.aod_brightness)}")
        }
        shouldShowRestoreBrightness.observe(this) {
            brightnessRestore.isVisible = it
        }
        notificationSmall.setOnClickListener {
            executeCommand("su -c service call statusbar 1")
        }
        textViewBattery.post {
            toggleClock(sharedPrefs.getBoolean("isBig", false))
        }
    }

    @SuppressLint("SetTextI18n")
    private fun updateWeatherUI(weatherData: WeatherData) {
        textViewWeather.text = "${weatherData.main.temp.toInt()}°C"
        val iconUrl =
            "https://openweathermap.org/img/wn/${weatherData.weather.firstOrNull()?.icon}@2x.png"
        Glide.with(this).load(iconUrl).into(findViewById(R.id.image_view_weather_icon))
        weatherRoot.isVisible = true
        weatherRoot.animateAlpha(400)
    }

    override fun onResume() {
        super.onResume()
        if (isFullScreenNotificationTriggered) {
            toggleTorch.postValue(false)
            executeCommand("su -c settings put system screen_brightness ${resources.getInteger(R.integer.aod_brightness)}")
        } else if (isLoginTriggered) {
            isLoginTriggered = false
        } else {
            proximitySensor?.also { sensor ->
                sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL)
            }
        }
        isFullScreenNotificationTriggered = false
    }

    private fun getCurrentBrightness(): Int {
        val command = "su -c settings get system screen_brightness"
        val result = executeCommand(command)
        // Parse the result to extract the brightness value
        return try {
            result.toInt()
        } catch (e: NumberFormatException) {
            e.printStackTrace()
            -1 // Handle parsing error
        }
    }

    @SuppressLint("SetTextI18n")
    private fun updateDateTime() {
        val dateFormat = SimpleDateFormat("EEE, dd MMM", Locale.getDefault())
        val timeFormat = SimpleDateFormat("h:mm", Locale.getDefault())
        val currentDate = dateFormat.format(Date())
        var currentTime = timeFormat.format(Date())
        if (currentTime.length == 4) {
            currentTime = "0$currentTime"
        }
        textViewDate.text = currentDate
        textViewSmallTime.text =
            if (currentTime.startsWith("0")) currentTime.substringAfter("0") else currentTime
        textViewLargeTimeHoursOne.text = currentTime.substring(0, 1)
        textViewLargeTimeHoursTwo.text = currentTime.substring(1, 2)
        textViewLargeTimeMinutesOne.text = currentTime.substring(3, 4)
        textViewLargeTimeMinutesTwo.text = currentTime.substring(4, 5)
        val bm = getSystemService(BATTERY_SERVICE) as? BatteryManager
        val batteryStatus: Intent? = IntentFilter(Intent.ACTION_BATTERY_CHANGED).let { ifilter ->
            applicationContext.registerReceiver(null, ifilter)
        }
        val status: Int = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging =
            status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        var chargingText =
            (if (isCharging) "Charging  -  " else "") + bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
                .toString() + "%"
        if (chargingText.contains("100")) {
            chargingText = "Charged"
        }
        textViewBattery.text = chargingText
        setAlarmInfo()
    }

    private fun setNotificationInfo() {
        notificationSmall.removeAllViews()
        notificationPackages.clear()
        // Loop through the notifications
        for (notification in activeNotifications.value.orEmpty()) {
            if (notification.notification?.fullScreenIntent != null && notification.notification.channelId == "Firing" && notification.packageName == "com.google.android.deskclock" && notification.notification.actions?.size == 2) {
                // Max Brightness to make Alarm more "disturbing"
                executeCommand("su -c settings put system screen_brightness 255")
                Handler(Looper.getMainLooper()).postDelayed({
                    isFullScreenNotificationTriggered = true
                    toggleTorch.postValue(true)
                    executeCommand("su -c input tap 400 200")
                }, 1000)
                continue
            }
            // Extract information from each notification
            val packageName = notification.packageName
            if (notificationPackages.contains(packageName) || notification.notification.visibility == -1) {
                notificationPackages.add(packageName)
                continue
            }
            notificationPackages.add(packageName)
            val id = notification.id
            val tag = notification.tag
            val postTime = notification.postTime
            // Get the notification's icon
            val iconDrawable = appListItems.find { it.first == notification.packageName }?.second
            // Log or process the notification information as needed
            notificationSmall.addView(ImageView(this).apply {
                post {
                    setPadding(0, 5.px, 5.px, 5.px)
                    layoutParams.height = 36.px
                    layoutParams.width = 36.px
                    requestLayout()
                    setImageDrawable(iconDrawable)
                }
            })
            // You can access more details depending on your needs
            // For example, notification.notification.extras gives you the Notification extras
            // Handle the iconBitmap as needed
            // Example: Display the icon in an ImageView
            // imageView.setImageBitmap(iconBitmap)
        }
    }

    private fun setAlarmInfo() {
        val alarmManager = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val nextAlarm = alarmManager.nextAlarmClock
        if (nextAlarm != null) {
            // There is an upcoming alarm
            val alarmTimeMillis = nextAlarm.triggerTime
            // Convert millis to your preferred format (e.g., Date, Calendar)
            val calendar = Calendar.getInstance()
            calendar.timeInMillis = alarmTimeMillis
            // Example of formatting the alarm time
            val sdf = SimpleDateFormat("h:mm", Locale.getDefault())
            val alarmTimeString = sdf.format(calendar.time)
            // Get the current time
            val currentTime = Date()
            // Calculate the time difference in milliseconds
            val timeDifference = calendar.time.time - currentTime.time
            // Convert milliseconds to hours
            val hoursDifference = timeDifference / (1000 * 60 * 60)
            // Check if the time to be checked is within the next 12 hours
            val isWithin12Hours = hoursDifference < 12
            if (isWithin12Hours) {
                // Display or use the alarm time
                textViewAlarm.text = alarmTimeString
                textViewAlarm.isVisible = true
            } else {
                textViewAlarm.text = ""
                textViewAlarm.isVisible = false
            }
        } else {
            // There are no alarms scheduled
            textViewAlarm.text = ""
            textViewAlarm.isVisible = false
        }
    }

    private fun toggleClock(showBigClock: Boolean) {
        sharedPrefs.edit {
            putBoolean("isBig", showBigClock)
        }
        textViewSmallTime.isVisible = !showBigClock
        textViewLargeTimeHoursOne.isVisible = showBigClock
        textViewLargeTimeHoursTwo.isVisible = showBigClock
        textViewLargeTimeMinutesOne.isVisible = showBigClock
        textViewLargeTimeMinutesTwo.isVisible = showBigClock
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event?.let {
            if (it.sensor.type == Sensor.TYPE_PROXIMITY && resources.getBoolean(R.bool.should_use_proximity)) {
                // "it.values[0]" gives you the proximity distance in centimeters
                if (it.values[0] <= 0f) {
                    // Proximity sensor is covered
                    // Add your logic here
                    blockTouch()
                    textViewTouchBlock.isVisible = true
                } else {
                    // Proximity sensor is not covered
                    // Add your logic here
                    textViewTouchBlock.isVisible = false
                    enableTouch()
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onDestroy() {
        lockSound.release()
        super.onDestroy()
//        Don't kill as it delays Notifications when app is launched again
//        executeCommand("su -c killall $packageName")
    }

    companion object {

        var maxAndNeededVolume: Int = 0
        var currentVolume = 0
        val toggleTorch = MutableLiveData(false)
        val shouldShowRestoreBrightness = MutableLiveData(false)
        var currentBrightness = 0

        var googleSignInAccount: GoogleSignInAccount? = null

        var activeNotifications: MutableLiveData<Array<StatusBarNotification>> =
            MutableLiveData(arrayOf())

        fun executeCommand(command: String): String {
            if (command.contains("service call statusbar 1")) {
                executeCommand("su -c settings put system screen_brightness $currentBrightness")
                shouldShowRestoreBrightness.postValue(true)
            }
            try {
                val process = Runtime.getRuntime().exec(command)
                val reader = BufferedReader(InputStreamReader(process.inputStream))
                val output = StringBuilder()
                var line: String? = ""
                while (line != null) {
                    line = reader.readLine()
                    if (line != null) {
                        output.append(line).append("\n")
                    }
                }
                // Wait for the process to finish
                process.waitFor()
                // Close the reader
                reader.close()
                // Return the output as a string
                return output.toString().trim()
            } catch (ignored: Exception) {
                return ShizukuShell(arrayListOf(), command.substringAfter("su -c ")).exec()
            }
        }

        fun setDeviceVolume(volumeLevel: Int, applicationContext: Context) {
            val audioManager =
                applicationContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager?
            audioManager?.setStreamVolume(AudioManager.STREAM_MUSIC, volumeLevel, 0)
        }
    }

}

val Int.dp: Int get() = (this / getSystem().displayMetrics.density).toInt()
val Int.px: Int get() = (this * getSystem().displayMetrics.density).toInt()

fun View.animateAlpha(duration: Long = 100, isReverse: Boolean = false) {
    alpha = if (isReverse) 1f else 0f
    Handler(Looper.getMainLooper()).post {
        animate().alpha(if (isReverse) 0f else 1f).setListener(object : Animator.AnimatorListener {
            override fun onAnimationStart(animation: Animator) {}
            override fun onAnimationCancel(animation: Animator) {}
            override fun onAnimationRepeat(animation: Animator) {}
            override fun onAnimationEnd(animation: Animator) {
                if (isReverse) {
                    this@animateAlpha.isVisible = false
                }
            }
        }).duration = duration
    }
}