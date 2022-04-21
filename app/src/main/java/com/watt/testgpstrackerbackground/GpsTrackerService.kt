package com.watt.testgpstrackerbackground

import android.Manifest
import android.annotation.SuppressLint
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.location.Criteria
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.common.api.GoogleApiClient
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.LocationSettingsRequest
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import kotlin.math.abs


/**
 * Created by khm on 2021-12-20.
 */

class GpsTrackerService : Service(), LocationListener {

    // Interval Time(Milli Seconds)마다 위치를 브로드캐스트 하게 된다.
    private var intervalTime:Long = 1000


    @SuppressLint("StaticFieldLeak")
    companion object{
        var serviceIntent: Intent? = null
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }


    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        serviceIntent = intent
        initializeNotification()

        initCriteria()

        startLocationUpdates()

        setReceiver()



        return START_STICKY
    }

    private var defaultScope = CoroutineScope(Dispatchers.Default)
    private var jobIdleGpsReceive:Job?=null
    private var jobCheckChangedLocation:Job?=null

    private var locationManager: LocationManager?= null
    private var location: Location?=null

    private var referenceLocation:LocationData?=null

    private var prevAverageLocationData:LocationData?=null

    private var saveUtcTime:Long = 0

    private var ageMs:Long = 0
    private var basicTermMs:Long = 10000

    private val minDistanceChangeForUpdates: Float = 0F
    //1초에 한번씩
    private val activeMinTimeBwUpdates:Long = 1000
    // 5분에 한번씩
    private val idleMinTimeUpdates:Long = 60000 * 5

    // 10분
    private val checkChangedLocationTime:Long = 60000 * 10

    private var isOffScreen = false
    //private var offScreenTime:Long = 0

    private var countOver20meter = 0

    // 이 범위내의 거리는 참으로 판단
    private var correctDistanceRange = 20


    private var locationRequest: LocationRequest? = null
    private var googleApiClient: GoogleApiClient? = null


    data class LocationData(var latitude: Double = 0.0, var longitude: Double = 0.0, var saveTime:Long =0, var speed:Float = 0f)
    private val averageLocationData = HashMap<Double, LocationData>(5)
    //private val locationDataList = ArrayList<LocationData>(5)

    private val correctLocations = ArrayList<Location>(5)

    private var prevLocation:Location?=null
    private var countWrongAverageLocation = 0


    init {
        locationRequest = LocationRequest().apply {
            interval = 10000
            fastestInterval = 5000
            priority = LocationRequest.PRIORITY_HIGH_ACCURACY
        }

    }


    private fun initializeNotification() {
        val builder = NotificationCompat.Builder(this, "1")
        //builder.setSmallIcon(R.mipmap.ic_launcher)
        val style = NotificationCompat.BigTextStyle()
        style.bigText("설정을 보려면 누르세요.")
        style.setBigContentTitle(null)
        style.setSummaryText("서비스 동작중")
        builder.setContentText(null)
        builder.setContentTitle(null)
        builder.setOngoing(true)
        builder.setStyle(style)
        builder.setWhen(0)
        builder.setShowWhen(false)
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, 0)
        builder.setContentIntent(pendingIntent)
        val manager = getSystemService(Service.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    "1",
                    "undead_service",
                    NotificationManager.IMPORTANCE_NONE
                )
            )
        }
        val notification = builder.build()
        startForeground(1, notification)
    }





    private var receiver:BroadcastReceiver?=null
    private fun setReceiver(){
        val intentFilter = IntentFilter(Intent.ACTION_SCREEN_OFF)
        intentFilter.addAction(Intent.ACTION_SCREEN_ON)
        receiver = object: BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val action = intent!!.action
                Log.d("Test", "receive : $action")

                when (action) {
                    Intent.ACTION_SCREEN_ON -> {
                        Log.d("GpsTrackerService", "action screen on!!!!!")
                        stopIdleGpsReceiveMode()
                        stopCheckChangedLocation()
                        isOffScreen = false
                        //offScreenTime = 0
                        countOver20meter = 0
                        requestLocationUpdates(activeMinTimeBwUpdates, minDistanceChangeForUpdates)
                    }
                    Intent.ACTION_SCREEN_OFF -> {
                        Log.d("GpsTrackerService", "action screen off!!!!!")
                        isOffScreen = true
                        //offScreenTime = System.currentTimeMillis()
                        countOver20meter = 0
                        saveReferenceLocation()
                        startCheckChangedLocation()
                    }
                }
            }
        }

        registerReceiver(receiver, intentFilter);
    }


    private fun saveReferenceLocation(){
        prevAverageLocationData?.let{ prevLocation ->
            if(prevLocation.latitude != 0.0 && prevLocation.longitude != 0.0 && prevLocation.saveTime != 0L){
                referenceLocation = LocationData(prevLocation.latitude, prevLocation.longitude, prevLocation.saveTime)
                Log.e("SavedReferenceLocation","${referenceLocation?.latitude}, ${referenceLocation?.longitude}, ${referenceLocation?.saveTime})")
            }else{
                referenceLocation = null
            }
        }
    }



    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.d("GpsTrackerService", "onTaskRemoved")
        setAlarmTimer()
    }



    override fun onDestroy() {
        super.onDestroy()
        Log.d("GpsTrackerService", "onDestroy")

        setAlarmTimer()
        unregisterReceiver(receiver)
        defaultScope.cancel()
    }

    private fun setAlarmTimer(){
        val calendar = Calendar.getInstance()
        calendar.timeInMillis = System.currentTimeMillis()
        calendar.add(Calendar.SECOND, 3)
        val intent = Intent(this, AlarmReceiver::class.java)
        val sender = PendingIntent.getBroadcast(this, 0, intent, 0)
        val alarmManager = getSystemService(Service.ALARM_SERVICE) as AlarmManager
        alarmManager[AlarmManager.RTC_WAKEUP, calendar.timeInMillis] = sender
    }


    private fun getLocation(){
        Log.d("GpsTrackerService", "getlocation into")
        try {
            locationManager = applicationContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            Log.d("GpsTrackerService", "init location manager")

            requestLocationUpdates(activeMinTimeBwUpdates, minDistanceChangeForUpdates)

        }catch (e:Exception){
            e.printStackTrace()
        }
    }





    private fun startLocationUpdates() {
        Log.d("GpsTrackerService", "start location updates into")
        googleApiClient = GoogleApiClient.Builder(applicationContext)
            .addApi(LocationServices.API)
            .build()
        googleApiClient!!.connect()
        val builder = LocationSettingsRequest.Builder().addLocationRequest(locationRequest!!)
        builder.setAlwaysShow(true)
        val result = LocationServices.SettingsApi.checkLocationSettings(
            googleApiClient,
            builder.build()
        )
        Log.d("GpsTrackerService", "after result")
//        result.setResultCallback { result ->
//            Log.d("GpsTrackerService", "before getlocation")
//            getLocation()
//        }
        getLocation()
    }


    private var isIdleMode = false

    private fun startIdleGpsReceiveMode(){
        Log.d("Idle Mode","startIdlegpsReceiveMode")
        stopIdleGpsReceiveMode()
        isIdleMode = true
        jobIdleGpsReceive = defaultScope.launch {
            delay(idleMinTimeUpdates)

            averageLocationData.clear()

            withContext(Dispatchers.Main){
                requestLocationUpdates(activeMinTimeBwUpdates, minDistanceChangeForUpdates)
            }

            startIdleGpsReceiveMode()
        }
    }

    private fun stopIdleGpsReceiveMode(){
        isIdleMode = false
        jobIdleGpsReceive?.cancel()
    }


    private fun startCheckChangedLocation(){
        Log.d("CheckChangedLocation","start - delay seconds : $checkChangedLocationTime")
        stopCheckChangedLocation()
        jobCheckChangedLocation = defaultScope.launch {
            delay(checkChangedLocationTime)
            Log.d("GpsTrackerService","delay seconds : ${checkChangedLocationTime/1000}")
            if(countOver20meter == 0){
                Log.d("GpsTrackerService","countOver20meter == 0")
                //offScreenTime = System.currentTimeMillis()
                removeLocationUpdates()
                clearCorrectLocations()
                startIdleGpsReceiveMode()
            }else{
                countOver20meter =0
                referenceLocation = null
                Log.d("GpsTrackerService","countOver20meter > 0 -- referenceLocation clear")
                startCheckChangedLocation()
            }
        }
    }

    private fun stopCheckChangedLocation(){
        jobCheckChangedLocation?.cancel()
    }


    @SuppressLint("SimpleDateFormat")
    private fun convertElapsedTimeToSaveUtcTime(elapsedTime:Long):Long{
        return (System.currentTimeMillis() - elapsedTime)/1000
    }


    private fun getLocalDateTime(utcEpochSeconds: Long): String? {
        return try {
            val netDate = Date(utcEpochSeconds * 1000)

            if(Locale.getDefault() == Locale.KOREA){
                val simpleDate = SimpleDateFormat("yyyy년 MM월 dd일 HH시 mm분 ss초", Locale.getDefault())
                simpleDate.format(netDate)
            }else{
                val simpleDate = SimpleDateFormat("MM/dd/yyyy HH:mm:ss", Locale.getDefault())
                simpleDate.format(netDate)
            }
        } catch (e: Exception) {
            e.toString()
        }
    }



    private fun clearCorrectLocations(){
        correctLocations.clear()
    }



    override fun onLocationChanged(lc: Location) {
        if(isRequesting){
            isRequesting = false
        }

        //Log.i("Accuracy",lc.accuracy.toString())

        if(correctLocations.count() >= 5){
            // 마지막 수신받고 얼마나 지났는지 시간 계산
            ageMs = TimeUnit.NANOSECONDS.toMillis(
                    SystemClock.elapsedRealtimeNanos()
                            - lc.elapsedRealtimeNanos
            )

            if(ageMs <= basicTermMs){
                saveUtcTime = convertElapsedTimeToSaveUtcTime(ageMs)
                sendLocation(lc.latitude, lc.longitude, saveUtcTime, lc.speed)
            }else{
                Log.e("GpsTrackerService", "prevElapsedTime != lc.elapsedRealtimeNanos && ageMs>basicTermMs")
            }
        }else{
            prevLocation?.let{ prevLc->
                if(isCorrectLocation(prevLc, lc)){
                    if(correctLocations.count() < 5){
                        correctLocations.add(lc)
                        Log.i("CorrectLocation","correct location count : ${correctLocations.count()}")
                    }
                }else{
                    // 5개 이상 참인값이 연속으로 안들어올때 초기화하여 처음부터 체크
                    clearCorrectLocations()
                }
            }
        }

        prevLocation = lc
    }

    override fun onStatusChanged(p0: String?, p1: Int, p2: Bundle?) {
        Log.d("GpsTrackerService", "onStatusChanged : $p0, $p1")
    }


    private var isRequesting = false


    private fun checkDistanceFromReferenceLocation(curLocationData:LocationData){
        val distance = FloatArray(1)
        referenceLocation?.let{ rfl ->
            Location.distanceBetween(rfl.latitude, rfl.longitude, curLocationData.latitude, curLocationData.longitude, distance)
            if(distance[0] > correctDistanceRange){
                Log.e("checkDistanceFromReferenceLocation","(${rfl.latitude}, ${rfl.longitude}), (${curLocationData.latitude}, ${curLocationData.longitude}) -- distance : ${distance[0]}")
                countOver20meter++
            }
        }
    }


    private fun sendLocation(latitude:Double, longitude:Double, saveUtcTime:Long, speed:Float){
        //Log.d("sendLocation", "$latitude, $longitude")
        if(latitude != 0.0 && longitude !=0.0){
            averageLocationData[latitude+longitude] = LocationData(latitude, longitude, saveUtcTime, speed)
            if(averageLocationData.count() >= 5){
                val averageLocationData = averageLocations(averageLocationData)
                averageLocationData.saveTime = saveUtcTime

                prevAverageLocationData?.let{ prevAvLd ->
                    if(isCorrectAverageLocation(prevAvLd, averageLocationData)){
                        countWrongAverageLocation = 0

                        if(isOffScreen){
                            if(referenceLocation == null){
                                saveReferenceLocation()
                            }
                            checkDistanceFromReferenceLocation(averageLocationData)
                            if(isIdleMode){
                                if(countOver20meter == 0){
                                    Log.e("IdleMode", "countOver20meter == 0 ---- removeLocationUpdates ")
                                    removeLocationUpdates()
                                    clearCorrectLocations()
                                }else{
                                    Log.e("GpsTrackerService","isOver10Minutes, countOver20meter > 0 -- user is moving")
                                    stopIdleGpsReceiveMode()
                                    startCheckChangedLocation()
                                    //offScreenTime = System.currentTimeMillis()
                                    countOver20meter = 0
                                }
                            }
                        }
                    }else{
                        countWrongAverageLocation++
                        if(countWrongAverageLocation>=5){
                            Log.e("WrongLocation","countWrongAverageLocation >= 5")
                            //잘못된 평균위치가 5번 이상이면 처음부터 올바른 위치인지 체크
                            countWrongAverageLocation = 0
                            clearCorrectLocations()
                        }
                    }
                }


                prevAverageLocationData = averageLocationData

                sendBroadcast(averageLocationData.latitude, averageLocationData.longitude, saveUtcTime)
                this.averageLocationData.clear()
            }
        }else{
            Log.d("sendLocation","latitude == 0 && longitude == 0")
        }
    }


    private fun averageLocations(hashMap:HashMap<Double, LocationData>): LocationData {
        // Min, Max 값 제거
        val sortedMap = hashMap.toSortedMap()
        sortedMap.remove(sortedMap.lastKey())
        sortedMap.remove(sortedMap.firstKey())

        var sumLatitude:Double = 0.0
        var sumLongitude:Double = 0.0
        for(element in sortedMap.values){
            sumLatitude += element.latitude
            sumLongitude += element.longitude
        }

        var averageLatitude:Double = 0.0
        var averageLongitude:Double = 0.0
        val count = sortedMap.count()
        try{
            averageLatitude = String.format("%.8f", (sumLatitude / count)).toDouble()
            averageLongitude = String.format("%.8f", (sumLongitude / count)).toDouble()
        }catch (e:Exception){
            e.printStackTrace()
        }

        // 평균속도
        var sumSpeed:Float = 0f
        for(element in hashMap.values){
            sumSpeed += element.speed
        }
        val averageSpeed = sumSpeed / hashMap.count()

        return LocationData(averageLatitude, averageLongitude, 0, averageSpeed)
    }






    private fun sendBroadcast(latitude:Double, longitude:Double, saveUtcTime:Long){
        val sendIntent = Intent("com.watt.gpstracker.broadcast")
        sendIntent.putExtra("latitude", latitude)
        sendIntent.putExtra("longitude", longitude)
        sendIntent.putExtra("saveUtcTime", saveUtcTime)
        //sendIntent.putExtra("saveTime", getLocalDateTime(saveUtcTime))
        applicationContext.sendBroadcast(sendIntent)
    }


    private var criteria = Criteria()
    @SuppressLint("WrongConstant")
    private fun initCriteria(){
        criteria.accuracy = Criteria.ACCURACY_FINE
        criteria.powerRequirement = Criteria.POWER_MEDIUM
        criteria.isAltitudeRequired = false
        criteria.isBearingRequired = false
        criteria.isSpeedRequired = true
        criteria.isCostAllowed = true
    }


//    private fun isOverTargetSeconds():Boolean{
//        if(offScreenTime == 0L){
//            return false
//        }
//        return (System.currentTimeMillis() - offScreenTime) >= checkChangedLocationTime
//    }


    private fun requestLocationUpdates(minTimeUpdates:Long, minDistanceUpdates:Float){
        if(isRequesting)
            return
        isRequesting = true

        locationManager?.let{
            //이전에 등록했던 gps 수신 요청을 취소한다.
            it.removeUpdates(this)

            val bestProvider = it.getBestProvider(criteria, true) ?: LocationManager.GPS_PROVIDER
            Log.d("bestprovider", bestProvider)

            val hasFineLocationPermission = ContextCompat.checkSelfPermission(
                    applicationContext,
                    Manifest.permission.ACCESS_FINE_LOCATION
            )
            val hasCoarseLocationPermission = ContextCompat.checkSelfPermission(
                    applicationContext,
                    Manifest.permission.ACCESS_COARSE_LOCATION
            )
            if (hasFineLocationPermission == PackageManager.PERMISSION_GRANTED &&
                    hasCoarseLocationPermission == PackageManager.PERMISSION_GRANTED
            ) {
                Log.d("GpsTrackerService", "permission granted")
            } else {
                Log.e("GpsTrackerService", "permission denied")
            }

            val isGPSEnabled: Boolean =
                    it.isProviderEnabled(LocationManager.GPS_PROVIDER)
            val isNetworkEnabled: Boolean =
                    it.isProviderEnabled(LocationManager.NETWORK_PROVIDER)

            if(isGPSEnabled && isNetworkEnabled){
                Log.d("GpsTrackerService","isGpsEnable && isNetworkEnabled")
                it.requestLocationUpdates(bestProvider, minTimeUpdates, minDistanceUpdates, this@GpsTrackerService)

                location = it.getLastKnownLocation(bestProvider)
                if(location == null){
                    Log.d("GpsTrackerService","getLastKnownLocation is null (provider : $bestProvider)")
                }else{
                    ageMs = TimeUnit.NANOSECONDS.toMillis(
                            SystemClock.elapsedRealtimeNanos()
                                    - location!!.elapsedRealtimeNanos
                    )
                    if(ageMs <= basicTermMs){
                        location?.let{ lc->
                            sendLocation(lc.latitude, lc.longitude, convertElapsedTimeToSaveUtcTime(ageMs), lc.speed)
                        }
                    }else{
                        Log.d("GpsTrackerService", "last location time > basicTermMs")
                    }
                }
            }else{
                Log.e("GpsTrackerService","isGpsDisalbed!! && isNetworkDisabled")
                it.requestLocationUpdates(LocationManager.GPS_PROVIDER, minTimeUpdates, minDistanceUpdates, this@GpsTrackerService)
            }


        }



    }

    private fun removeLocationUpdates(){
        isRequesting = false
        locationManager?.removeUpdates(this)
    }


    private fun isCorrectAverageLocation(prevLd:LocationData, curLd:LocationData):Boolean{
        val distanceArr = FloatArray(1)
        Location.distanceBetween(prevLd.latitude, prevLd.longitude, curLd.latitude, curLd.longitude, distanceArr)
        val distance:Float = distanceArr[0]

        val speed = abs(curLd.speed - prevLd.speed)

        Log.d("isCorrectAverageLocation", "speed(prev:${prevLd.speed},cur:${curLd.speed}) ------ distance : $distance, speed : $speed")

        if(speed <= 0.5){
            return distance <= 6
        }

        return distance >= speed - 0.3 && distance <= speed + 0.3
    }


    private fun isCorrectLocation(prevLc:Location, curLc:Location):Boolean{
        val distanceArr = FloatArray(1)
        Location.distanceBetween(prevLc.latitude, prevLc.longitude, curLc.latitude, curLc.longitude, distanceArr)
        val distance:Float = distanceArr[0]

        val speed = abs(curLc.speed - prevLc.speed)

        Log.d("isCorrectLocation", "speed(prev:${prevLc.speed},cur:${curLc.speed}) ------ distance : $distance, speed : $speed")

        if(speed <= 0.5){
            return distance <= 3
        }

        return distance >= speed - 0.3 && distance <= speed + 0.3
    }


}