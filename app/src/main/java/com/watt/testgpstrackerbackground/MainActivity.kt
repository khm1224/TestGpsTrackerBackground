package com.watt.testgpstrackerbackground

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import com.gun0912.tedpermission.PermissionListener
import com.gun0912.tedpermission.TedPermission
import com.naver.maps.geometry.LatLng
import com.naver.maps.map.CameraUpdate
import com.naver.maps.map.NaverMap
import com.watt.testgpstrackerbackground.databinding.ActivityMainBinding
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private val binding by lazy{ ActivityMainBinding.inflate(layoutInflater) }

    private var naverMap: NaverMap?= null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)

        checkPermissions()

        setReceiver()

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        binding.naverMap.getMapAsync{
            Log.d("MainActivity","getMapAsync")
            naverMap = it
            it.uiSettings.isZoomControlEnabled = false
        }

        binding.tvZoomIn.setOnClickListener{
            naverMap?.moveCamera(CameraUpdate.zoomIn())
        }

        binding.tvZoomOut.setOnClickListener {
            naverMap?.moveCamera(CameraUpdate.zoomOut())
        }

    }


    private fun checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            TedPermission.with(this)
                .setPermissionListener(permissionListener)
                .setPermissions(
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_BACKGROUND_LOCATION,
                    Manifest.permission.FOREGROUND_SERVICE
                )
                .check()
        }else{
            TedPermission.with(this)
                .setPermissionListener(permissionListener)
                .setPermissions(
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.ACCESS_FINE_LOCATION
                )
                .check()
        }
    }


    private var permissionListener: PermissionListener = object : PermissionListener {
        override fun onPermissionGranted() {
            Log.d("MainActivity", "onPermissionGranted:::::::: ")

            //startService(Intent(applicationContext, BackgroundLocationUpdateService::class.java))
            startUndeadService()
        }

        override fun onPermissionDenied(deniedPermissions: ArrayList<String?>?) {
            Log.d("MainActivity", "onPermissionDenied:::::::: ")
            Toast.makeText(applicationContext, "권한이 허용되지 않으면 앱을 실행 할 수 없습니다.", Toast.LENGTH_SHORT)
                .show()

        }
    }



    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(receiver)
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    var receiver: BroadcastReceiver?=null

    private fun setReceiver(){
        val intentFilter = IntentFilter()
        intentFilter.addAction("com.watt.gpstracker.broadcast")

        receiver = object: BroadcastReceiver(){
            @SuppressLint("SetTextI18n")
            override fun onReceive(context: Context?, intent: Intent?) {
                val latitude = intent?.getDoubleExtra("latitude", 0.0) ?: 0.0
                val longitude = intent?.getDoubleExtra("longitude", 0.0) ?: 0.0
                val saveUtcTime = intent?.getLongExtra("saveUtcTime", 0) ?: 0
                prevSaveTime = saveUtcTime

                //Log.d("MainActivity","latitude:$latitude, longitude:$longitude, saveUtcTime:$saveUtcTime")

                binding.tvGpsInfo.text = "($latitude, $longitude) - ${getLocalDateTime(saveUtcTime)}"

                naverMap?.let{ nmap->
                    val coord = LatLng(latitude, longitude)
                    val locationOverlay = nmap.locationOverlay
                    locationOverlay.isVisible = true
                    locationOverlay.position = coord
                    nmap.moveCamera(CameraUpdate.scrollTo(coord))
                }

                //naverMap?.cameraPosition = CameraPosition(LatLng(latitude, longitude), 16.0)

            }
        }
        registerReceiver(receiver, intentFilter)
    }


    private var prevSaveTime:Long = 0



    private fun startUndeadService(){
        val foregroundServiceIntent: Intent
        if (null == GpsTrackerService.serviceIntent) {
            foregroundServiceIntent = Intent(applicationContext, GpsTrackerService::class.java)
            foregroundServiceIntent.putExtra("ready", "t")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                applicationContext.startForegroundService(foregroundServiceIntent)
            }else{
                applicationContext.startService(foregroundServiceIntent)
            }

        } else {
            foregroundServiceIntent = GpsTrackerService.serviceIntent!!
            foregroundServiceIntent.putExtra("ready", "t")
        }
    }




    private fun getLocalDateTime(utcEpochSeconds: Long): String? {
        return try {
            val netDate = Date(utcEpochSeconds * 1000)

            if(Locale.getDefault() == Locale.KOREA){
                val simpleDate = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                simpleDate.format(netDate)
            }else{
                val simpleDate = SimpleDateFormat("MM/dd/yyyy HH:mm:ss", Locale.getDefault())
                simpleDate.format(netDate)
            }
        } catch (e: Exception) {
            e.toString()
        }
    }
}