package com.fpondarts.foodie.ui.delivery

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import android.content.IntentSender
import android.os.Bundle
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.snackbar.Snackbar
import androidx.navigation.findNavController
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.navigateUp
import androidx.navigation.ui.setupActionBarWithNavController
import androidx.navigation.ui.setupWithNavController
import androidx.drawerlayout.widget.DrawerLayout
import com.google.android.material.navigation.NavigationView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import android.view.Menu
import android.widget.Toast
import androidx.lifecycle.Observer
import com.fpondarts.foodie.R
import com.fpondarts.foodie.data.repository.AuthRepository
import com.fpondarts.foodie.data.repository.DeliveryRepository
import com.fpondarts.foodie.services.MyLocationService
import com.fpondarts.foodie.ui.auth2.AuthActivity
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.*
import com.google.android.gms.tasks.Task
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.messaging.FirebaseMessaging
import com.karumi.dexter.Dexter
import com.karumi.dexter.PermissionToken
import com.karumi.dexter.listener.PermissionDeniedResponse
import com.karumi.dexter.listener.PermissionGrantedResponse
import com.karumi.dexter.listener.PermissionRequest
import com.karumi.dexter.listener.single.PermissionListener
import kotlinx.android.synthetic.main.nav_header_delivery_home.*
import org.kodein.di.KodeinAware
import org.kodein.di.android.kodein
import org.kodein.di.generic.instance
import java.net.CacheRequest

class DeliveryHomeActivity : AppCompatActivity(), KodeinAware {

    override val kodein by kodein()

    val repository: DeliveryRepository by instance()

    private lateinit var appBarConfiguration: AppBarConfiguration

    private lateinit var locationRequest: LocationRequest

    private lateinit var fusedLocationProviderClient: FusedLocationProviderClient

    private lateinit var fb_uid:String

    private val REQUEST_CHECK_SETTINGS = 999

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_delivery_home)
        val toolbar: Toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)

        val drawerLayout: DrawerLayout = findViewById(R.id.drawer_layout)
        val navView: NavigationView = findViewById(R.id.nav_view_delivery)
        val navController = findNavController(R.id.nav_host_fragment)
        // Passing each menu ID as a set of Ids because each
        // menu should be considered as top level destinations.
        appBarConfiguration = AppBarConfiguration(
            setOf(
                R.id.delivery_nav_home,
                R.id.nav_profile
            ), drawerLayout
        )
        setupActionBarWithNavController(navController, appBarConfiguration)
        navView.setupWithNavController(navController)


        val token = intent.getStringExtra("token")
        val id = intent.getLongExtra("user_id",-1)

        if (id.equals(-1)){
            Toast.makeText(this,"Error en el login",Toast.LENGTH_LONG).show()
            val newIntent = Intent(this, AuthActivity::class.java)
            startActivity(newIntent)
            finish()
        }

        repository.initUser(token,id)

        repository.currentUser.observe(this, Observer {
            it?.let{
                drawer_user_name.text = it.name
                drawer_user_email.text = it.email
                fb_uid = it.firebase_uid
                connectToFcm(it.firebase_uid)
            }
        })

        repository.apiError.observe(this, Observer {
            it?.let {
                Toast.makeText(this,"${it.code}: ${it.message}",Toast.LENGTH_SHORT).show()
            }
        })

        askLocationPermission()

        // 3 es el índice del item logout
        navView.menu.getItem(3).setOnMenuItemClickListener {
            FirebaseAuth.getInstance().signOut()
            FirebaseMessaging.getInstance().unsubscribeFromTopic(repository.currentUser.value!!.firebase_uid)
            val repository : AuthRepository by instance()
            repository.role = null
            repository.userId = null
            repository.token = null
            val intent = Intent(this,AuthActivity::class.java)
            startActivity(intent)
            finish()
            true
        }

    }

    private fun updateLocation() {

        buildLocationRequest()

        val builder = LocationSettingsRequest.Builder()
            .addLocationRequest(locationRequest)


        val client: SettingsClient = LocationServices.getSettingsClient(this)
        val task: Task<LocationSettingsResponse> = client.checkLocationSettings(builder.build())

        task.addOnSuccessListener {

            onSettingsOk()
        }



        task.addOnFailureListener {
            if (it is ResolvableApiException){
                // Location settings are not satisfied, but this can be fixed
                // by showing the user a dialog.
                try {
                    it.startResolutionForResult(this@DeliveryHomeActivity,
                        REQUEST_CHECK_SETTINGS)
                } catch (sendEx: IntentSender.SendIntentException) {
                    Toast.makeText(this,sendEx.message,Toast.LENGTH_SHORT).show()
                }
            }

        }

    }



    private fun onSettingsOk(){
        fusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(this)
        fusedLocationProviderClient.requestLocationUpdates(locationRequest,getPendingIntent())

    }
    private fun getPendingIntent(): PendingIntent? {
        val intent = Intent(this@DeliveryHomeActivity,MyLocationService::class.java)
        intent.setAction(MyLocationService.ACTION_PROCESS_UPDATE_DELIVERY)
        return PendingIntent.getBroadcast(this@DeliveryHomeActivity,0,intent,PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun buildLocationRequest() {
        locationRequest = LocationRequest()
        locationRequest.priority = LocationRequest.PRIORITY_HIGH_ACCURACY
        locationRequest.interval = 5000
        locationRequest.fastestInterval = 3000
        locationRequest.smallestDisplacement=10f
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        // Inflate the menu; this adds items to the action bar if it is present.
        menuInflater.inflate(R.menu.delivery_home, menu)
        return true
    }

    override fun onSupportNavigateUp(): Boolean {
        val navController = findNavController(R.id.nav_host_fragment)
        return navController.navigateUp(appBarConfiguration) || super.onSupportNavigateUp()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CHECK_SETTINGS){
            if (resultCode == Activity.RESULT_OK){
                onSettingsOk()
            } else {
                updateLocation()
            }
        }
    }

    fun askLocationPermission(){

        Dexter.withActivity(this)
            .withPermission(Manifest.permission.ACCESS_FINE_LOCATION)
            .withListener(object: PermissionListener {
                override fun onPermissionGranted(response: PermissionGrantedResponse?) {
                    updateLocation()
                }

                override fun onPermissionRationaleShouldBeShown(
                    permission: PermissionRequest?,
                    token: PermissionToken?
                ) {

                }

                override fun onPermissionDenied(response: PermissionDeniedResponse?) {
                    askLocationPermission()
                    Toast.makeText(this@DeliveryHomeActivity,"This permission should be accepted",Toast.LENGTH_LONG).show()
                }

            }).check()

    }


    fun connectToFcm(uid:String){
        FirebaseMessaging.getInstance().subscribeToTopic(uid)
            .addOnCompleteListener{
                if (it.isSuccessful){

                } else {
                    connectToFcm(uid)
                }
            }
    }


    override fun onDestroy() {
        super.onDestroy()
        FirebaseMessaging.getInstance().unsubscribeFromTopic(fb_uid)
    }


}
