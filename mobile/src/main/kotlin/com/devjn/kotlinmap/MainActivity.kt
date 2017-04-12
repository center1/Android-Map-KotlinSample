package com.devjn.kotlinmap

import android.Manifest
import android.app.Activity
import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.databinding.DataBindingUtil
import android.graphics.drawable.Drawable
import android.location.Criteria
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.support.design.widget.BottomSheetBehavior
import android.support.design.widget.NavigationView
import android.support.graphics.drawable.VectorDrawableCompat
import android.support.v4.app.ActivityCompat
import android.support.v4.content.ContextCompat
import android.support.v4.view.GravityCompat
import android.support.v4.widget.DrawerLayout
import android.support.v7.app.ActionBarDrawerToggle
import android.support.v7.app.AppCompatActivity
import android.support.v7.app.AppCompatDelegate
import android.support.v7.widget.SearchView
import android.text.Html
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import com.devjn.kotlinmap.databinding.ActivityMainBinding
import com.devjn.kotlinmap.utils.PermissionUtils
import com.devjn.kotlinmap.utils.PlacePoint
import com.devjn.kotlinmap.utils.UIUtils.getBitmap
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GooglePlayServicesNotAvailableException
import com.google.android.gms.common.GooglePlayServicesRepairableException
import com.google.android.gms.common.api.GoogleApiClient
import com.google.android.gms.location.places.Places
import com.google.android.gms.location.places.ui.PlacePicker
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.*
import com.google.maps.android.geojson.GeoJsonLayer
import com.google.maps.android.geojson.GeoJsonPointStyle
import org.ferriludium.simplegeoprox.MapObjectHolder
import org.json.JSONException
import java.io.IOException
import java.util.*

class MainActivity : AppCompatActivity(), NavigationView.OnNavigationItemSelectedListener, OnMapReadyCallback, GoogleApiClient.OnConnectionFailedListener, LocationListener, ResponseService.LocationResultListener {
    val TAG = MainActivity::class.java.kotlin.simpleName
    private lateinit var mGoogleApiClient: GoogleApiClient

    /**
     * Flag indicating whether a requested permission has been denied after returning in
     * [.onRequestPermissionsResult].
     */
    private var mPermissionDenied = false

    private lateinit var binding: ActivityMainBinding
    private var mGoogleMap: GoogleMap? = null
    private var mLastLocation: Location? = null

    private lateinit var bottomSheetBehavior: BottomSheetBehavior<*>
    private lateinit var bottomSheet: View

    private lateinit var mResponseService: ResponseService
    private var locationManager: LocationManager? = null
    private var provider: String? = null

    private val mMarkersMap = HashMap<Marker, MapObjectHolder<PlacePoint>>(3)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        this.binding = DataBindingUtil.setContentView<ActivityMainBinding>(this, R.layout.activity_main)
        val toolbar = binding.appBarMain.toolbar
        setSupportActionBar(toolbar)

        checkPermissions()

        bottomSheet = binding.appBarMain.bottomSheet.bottomSheet
        bottomSheetBehavior = BottomSheetBehavior.from(bottomSheet)
        bottomSheetBehavior.state = BottomSheetBehavior.STATE_HIDDEN
        bottomSheetBehavior.setBottomSheetCallback(object: BottomSheetBehavior.BottomSheetCallback() {
            override fun onStateChanged(bottomSheet: View, newState: Int) {}

            override fun onSlide(bottomSheet: View, slideOffset: Float) {
                if(slideOffset > 0.5f)
                    binding.appBarMain.fab.hide()
                else binding.appBarMain.fab.show()
            }
        })

        val drawer = binding.drawerLayout
        val toggle = ActionBarDrawerToggle(
                this, drawer, toolbar, R.string.navigation_drawer_open, R.string.navigation_drawer_close)
        drawer.addDrawerListener(toggle)
        toggle.syncState()

        val navigationView = binding.navView
        navigationView.setNavigationItemSelectedListener(this)

        val mapFragment = supportFragmentManager
                .findFragmentById(R.id.map) as SupportMapFragment
        mapFragment.getMapAsync(this)

        mGoogleApiClient = GoogleApiClient.Builder(this)
                .addApi(Places.PLACE_DETECTION_API)
                .enableAutoManage(this, GOOGLE_API_CLIENT_ID, this)
                .build()

        val fab = binding.appBarMain.fab
        fab.setOnClickListener { v ->
            onPickButtonClick()
            if (mGoogleApiClient.isConnected) {
                if (ContextCompat.checkSelfPermission(this@MainActivity,
                        Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                    ActivityCompat.requestPermissions(this@MainActivity,
                            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                            PERMISSIONS_REQUEST_CODE)
                } else {
                    //                        callPlaceDetectionApi();
                }

            } else
                Log.e(TAG, "mGoogleApiClient is not connected")
        }
        initLocationServices()
    }

    private fun checkPermissions() {
        if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED || !PermissionUtils.isLocationGranted) {
            PermissionUtils.requestPermission(this, STORAGE_PERMISSION_REQUEST_CODE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    private fun initLocationServices() {
        this.mResponseService = ResponseService.instance
        mResponseService.setListener(this)

        // Get the location manager
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        // Define the criteria how to select the locatioin provider -> use default
        val criteria = Criteria()
        provider = locationManager!!.getBestProvider(criteria, false)
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
            return
        mLastLocation = locationManager!!.getLastKnownLocation(provider)

        // Initialize the location fields
        val lastLocation: Location? = mLastLocation;
        if (lastLocation != null) {
            println("Provider $provider has been selected.")
            onLocationChanged(lastLocation)
        } else {
            Log.w(TAG, "Location not available")
        }
    }

    override fun onStart() {
        super.onStart()
        mGoogleApiClient.connect()
    }

    override fun onStop() {
        mGoogleApiClient.disconnect()
        super.onStop()
    }

    override fun onBackPressed() {
        val drawer = binding.drawerLayout
        if (drawer.isDrawerOpen(GravityCompat.START)) {
            drawer.closeDrawer(GravityCompat.START)
        } else {
            super.onBackPressed()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        // Inflate the menu; this adds items to the action bar if it is present.
        menuInflater.inflate(R.menu.main, menu)
        val searchItem = menu.findItem(R.id.action_search)

        val searchManager = this@MainActivity.getSystemService(Context.SEARCH_SERVICE) as SearchManager

        var searchView: SearchView? = null
        if (searchItem != null) {
            searchView = searchItem.actionView as SearchView
        }
        if (searchView != null) {
            searchView.setSearchableInfo(searchManager.getSearchableInfo(this@MainActivity.componentName))
        }
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        val id = item.itemId


        if (id == R.id.action_settings) {
            return true
        }

        return super.onOptionsItemSelected(item)
    }

    override fun onNavigationItemSelected(item: MenuItem): Boolean {
        // Handle navigation view item clicks here.
        val id = item.itemId

        if (id == R.id.nav_camera) {
            // Handle the camera action
        } else if (id == R.id.nav_gallery) {

        } else if (id == R.id.nav_slideshow) {

        } else if (id == R.id.nav_share) {
            val builder = PlacePicker.IntentBuilder()
                    .setLatLngBounds(LatLngBounds.Builder()
                            .include(LatLng(60.1455, 24.9067))
                            .include(LatLng(60.1782, 24.9530))
                            .build())
            try {
                startActivityForResult(builder.build(this@MainActivity), REQUEST_PLACE_PICKER)
            } catch (e: GooglePlayServicesRepairableException) {
                e.printStackTrace()
            } catch (e: GooglePlayServicesNotAvailableException) {
                e.printStackTrace()
            }

        } else if (id == R.id.nav_send) {

        } else if (id == R.id.action_settings) {

        }

        val drawer = findViewById(R.id.drawer_layout) as DrawerLayout
        drawer.closeDrawer(GravityCompat.START)
        return true
    }


    /* Request updates at startup */
    override fun onResume() {
        super.onResume()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            provider?.let {
                locationManager?.requestLocationUpdates(provider, 400, 10f, this)
                println("non null provider")
            } ?: println("null provider")
        }
    }

    /* Remove the locationlistener updates when Activity is paused */
    override fun onPause() {
        super.onPause()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED)
            locationManager?.removeUpdates(this)
    }

    override fun onLocationChanged(location: Location) {
        this.mLastLocation = location
        val lat = location.latitude.toInt()
        val lng = location.longitude.toInt()
        Log.i(TAG, lat.toString())
        Log.i(TAG, lng.toString())
    }

    override fun onStatusChanged(provider: String, status: Int, extras: Bundle) {
        // TODO Auto-generated method stub
    }

    override fun onProviderEnabled(provider: String) {
        Toast.makeText(this, "Enabled new provider " + provider,
                Toast.LENGTH_SHORT).show()
    }

    override fun onProviderDisabled(provider: String) {
        Toast.makeText(this, "Disabled provider " + provider,
                Toast.LENGTH_SHORT).show()
    }

    internal var geoLayer: GeoJsonLayer? = null

    override fun onMapReady(map: GoogleMap) {
        this.mGoogleMap = map
        val pos = LatLng(60.178, 24.928)

        enableMyLocation()
        map.moveCamera(CameraUpdateFactory.newLatLngZoom(pos, 13f))

        map.setOnMarkerClickListener { marker ->
            updateBottomSheetContent(marker)
            true
        }
        map.setOnMapClickListener { bottomSheetBehavior.state = BottomSheetBehavior.STATE_HIDDEN }

        val drawable: Drawable = VectorDrawableCompat.create(resources, R.drawable.ic_menu_camera, null)!!

        map.addMarker(MarkerOptions()
                .title("Test")
                .snippet("Test location.")
                .position(pos))

        //GeoJSon
        geoLayer = null
        try {
            geoLayer = GeoJsonLayer(map, R.raw.export, applicationContext)
            //            geoLayer.getDefaultPointStyle().setIcon(BitmapDescriptorFactory.fromBitmap(getBitmap(drawable)));
            val var1 = geoLayer!!.features.iterator()

            val pointStyle = geoLayer!!.defaultPointStyle
            pointStyle.icon = BitmapDescriptorFactory.fromBitmap(getBitmap(drawable))

            val drawableFood = VectorDrawableCompat.create(resources, R.drawable.ic_food, null)!!

            val pointStyle2 = GeoJsonPointStyle()
            pointStyle2.icon = BitmapDescriptorFactory.fromBitmap(getBitmap(drawableFood))

            while (var1.hasNext()) {
                val feature = var1.next()
                //                Log.i(TAG, "feauture: "+feature);
                if (feature.getProperty("name") == null)
                    feature.pointStyle = pointStyle
                else
                    feature.pointStyle = pointStyle2
                geoLayer!!.addFeature(feature)
            }
            geoLayer!!.addLayerToMap()
        } catch (e: IOException) {
            e.printStackTrace()
        } catch (e: JSONException) {
            e.printStackTrace()
        }

    }


    /**
     * Enables the My Location geoLayer if the fine location permission has been granted.
     */
    private fun enableMyLocation() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {

            //            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
            //                    != PackageManager.PERMISSION_GRANTED)
            // Permission to access the location is missing.
            PermissionUtils.requestPermission(this, LOCATION_PERMISSION_REQUEST_CODE,
                    Manifest.permission.ACCESS_FINE_LOCATION, true)

        } else if (mGoogleMap != null) {
            // Access to the location has been granted to the app.
            mGoogleMap!!.isMyLocationEnabled = true
        }
    }

    private fun updateBottomSheetContent(marker: Marker) {
        val holder = mMarkersMap[marker]
        if (holder == null) {
            Log.w(TAG, "PlacePoint holder is null")
            if (geoLayer != null) {
                val feature = geoLayer!!.getFeature(marker)
                if (feature == null) {
                    Log.w(TAG, "feature is null")
                    return
                }
                if (feature.hasProperty("name")) {
                    val name = feature.getProperty("name")
                    val placePoint = PlacePoint(name, 0.0, 0.0)
                    if (feature.hasProperty("amenity")) {
                        placePoint.detailName = feature.getProperty("amenity")
                    }
                    binding.appBarMain.place = placePoint
                    bottomSheetBehavior.state = BottomSheetBehavior.STATE_COLLAPSED
                }
            }
            return
        }
        binding.appBarMain.place = holder.clientObject
        bottomSheetBehavior.state = BottomSheetBehavior.STATE_COLLAPSED
    }

    override fun onConnectionFailed(connectionResult: ConnectionResult) {
        Log.e(TAG, "Google Places API connection failed with error code: " + connectionResult.errorCode)
        Toast.makeText(this,
                "Google Places API connection failed with error code:" + connectionResult.errorCode,
                Toast.LENGTH_LONG).show()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>,
                                            grantResults: IntArray) {
        when (requestCode) {
            PERMISSIONS_REQUEST_CODE -> if (grantResults.isNotEmpty()) {
                onRequestPermissionsResult(LOCATION_PERMISSION_REQUEST_CODE, permissions, grantResults)
                onRequestPermissionsResult(STORAGE_PERMISSION_REQUEST_CODE, permissions, grantResults)
            }
            LOCATION_PERMISSION_REQUEST_CODE -> if (PermissionUtils.isPermissionGranted(permissions, grantResults,
                    Manifest.permission.ACCESS_FINE_LOCATION)) {
                // Enable the my location geoLayer if the permission has been granted.
                mPermissionDenied = false
                if (locationManager != null) {
                    if (provider == null) {
                        val criteria = Criteria()
                        provider = locationManager!!.getBestProvider(criteria, false)
                    }
                    locationManager!!.requestLocationUpdates(provider, 400, 10f, this)
                }
                enableMyLocation()
            } else {
                // Display the missing permission error dialog when the fragments resume.
                Log.w(TAG, "Permissions are not granted: " + permissions)
                mPermissionDenied = true
            }
            STORAGE_PERMISSION_REQUEST_CODE -> if (!PermissionUtils.isPermissionGranted(permissions, grantResults,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE) && ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                PermissionUtils.requestPermission(this@MainActivity, 0, Manifest.permission.READ_EXTERNAL_STORAGE, true)
            }
        }
    }

    @Throws(SecurityException::class)
    private fun callPlaceDetectionApi() {
        Log.d(TAG, "callPlaceDetectionApi")
        val result = Places.PlaceDetectionApi
                .getCurrentPlace(mGoogleApiClient, null)
        result.setResultCallback { likelyPlaces ->
            for (placeLikelihood in likelyPlaces) {
                Log.i(TAG, String.format("Place '%s' with " + "likelihood: %g",
                        placeLikelihood.place.name,
                        placeLikelihood.likelihood))
            }
            likelyPlaces.release()
        }
    }

    fun onPickButtonClick() {
        Log.d(TAG, "onPickButtonClick")
        onSearchClick()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQUEST_PLACE_PICKER && resultCode == Activity.RESULT_OK) {
            // The user has selected a place. Extract the name and address.
            val place = PlacePicker.getPlace(this, data)

            val id = place.id
            val name = place.name
            val address = place.address
            val latLng = place.latLng
            var attributions: String? = place.attributions.toString()
            if (attributions == null) {
                attributions = ""
            }
            val intent = Intent()
            intent.putExtra("name", name)
            intent.putExtra("id", id)
            intent.putExtra("lat", latLng.latitude)
            intent.putExtra("lng", latLng.longitude)
            val list = ArrayList(place.placeTypes)
            intent.putIntegerArrayListExtra("types", list)
            startActivity(intent)

            Log.i("Main", "name= " + name + " attributions:\n" + Html.fromHtml(attributions) + "\nList: " + list)
            val toastMsg = String.format("Place: %s", place.name)
            Toast.makeText(this, toastMsg, Toast.LENGTH_LONG).show()

        } else {
            super.onActivityResult(requestCode, resultCode, data)
        }
    }

    private fun onSearchClick() {
        if (mLastLocation == null) {
            Log.w(TAG, "Last location is null")
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                val criteria = Criteria()
                provider = locationManager!!.getBestProvider(criteria, false)
                mLastLocation = locationManager!!.getLastKnownLocation(provider)
                if (mLastLocation == null) {
                    Log.w(TAG, "Location is not available")
                    Toast.makeText(this, R.string.location_not_available, Toast.LENGTH_LONG).show()
                    provider?: locationManager!!.requestSingleUpdate(provider, this, null)
                    return
                }
            } else
                return
        }
        val lat = mLastLocation!!.latitude
        val lng = mLastLocation!!.longitude
        Log.i(TAG, "Search click, lat= $lat, lng= $lng")
        showBottomList(lat, lng)
    }

    override fun onResumeFragments() {
        super.onResumeFragments()
        if (mPermissionDenied) {
            // Permission was not granted, display error dialog.
            showMissingPermissionError()
            mPermissionDenied = false
        }
    }

    /**
     * Displays a dialog with error message explaining that the location permission is missing.
     */
    private fun showMissingPermissionError() {
        PermissionUtils.PermissionDeniedDialog
                .newInstance(true).show(supportFragmentManager, "dialog")
    }

    override fun onLocationResult(result: Collection<MapObjectHolder<PlacePoint>>?) {
        Log.i(TAG, "response: " + result)
        Toast.makeText(applicationContext, "response: " + result, Toast.LENGTH_LONG).show()
        if (mGoogleMap == null || result == null) return
        for (mapHolder in result) {
            val pos = LatLng(mapHolder.clientObject.latitude, mapHolder.clientObject.longitude)
            val markerOptions = MarkerOptions()
            markerOptions.title(mapHolder.clientObject.name).position(pos)
                    .anchor(0.0f, 1.0f) // Anchors the marker on the bottom left
                    .snippet("Some place")
            mMarkersMap.put(mGoogleMap!!.addMarker(markerOptions), mapHolder)
        }
    }

    internal var listBottomSheet: ListBottomSheetDialogFragment? = null

    private fun showBottomList(lat: Double, lng: Double) {
        if (listBottomSheet == null)
            listBottomSheet = ListBottomSheetDialogFragment.newInstance()
        listBottomSheet!!.show(supportFragmentManager, listBottomSheet!!.tag, lat, lng)
    }

    companion object {

        init {
            AppCompatDelegate.setCompatVectorFromResourcesEnabled(true)
        }

        private val TAG = MainActivity::class.java.simpleName
        private val GOOGLE_API_CLIENT_ID = 0
        private val PERMISSIONS_REQUEST_CODE = 100
        private val REQUEST_PLACE_PICKER = 202

        /**
         * Request code for location permission request.

         * @see .onRequestPermissionsResult
         */
        private val LOCATION_PERMISSION_REQUEST_CODE = 101
        private val STORAGE_PERMISSION_REQUEST_CODE = 102
    }

}
