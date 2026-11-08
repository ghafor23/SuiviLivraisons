package com.fixly.suivilivraison
import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.location.Location
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.LocationSettingsRequest
import com.google.android.gms.location.Priority
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.gms.maps.model.MapStyleOptions
import com.google.android.gms.maps.model.Marker
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.Polyline
import com.google.android.gms.maps.model.PolylineOptions
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import org.json.JSONObject
import pub.devrel.easypermissions.AfterPermissionGranted
import pub.devrel.easypermissions.AppSettingsDialog
import pub.devrel.easypermissions.EasyPermissions
import java.net.HttpURLConnection
import java.net.URL

// Une commande : où la récupérer (B) et chez quel client la livrer (C)
data class Order(
    val id: Int,
    val pickupName: String,
    val pickup: LatLng,
    val customerName: String,
    val customer: LatLng
)

class MainActivity : AppCompatActivity(), OnMapReadyCallback,
    EasyPermissions.PermissionCallbacks {

    companion object {
        private const val RC_LOCATION = 100
        private const val RC_NOTIF = 101
        private const val CHANNEL_ID = "delivery_channel"

        // Distance (en mètres) pour considérer qu'on est arrivé au point
        private const val ARRIVAL_RADIUS = 100f

        // Temps (en secondes) pour accepter une commande
        private const val OFFER_SECONDS = 30

        // Simulation : vitesse du livreur (m/s) et fréquence des mises à jour
        private const val SIM_SPEED = 20f          // 20 m/s = 72 km/h (mettre 10 pour plus réaliste)
        private const val SIM_TICK_MS = 1000L
        private val SIM_START = LatLng(34.0100, -6.8400)   // départ de secours

        // Services de routes OSRM gratuits (le second sert de secours)
        private val ROUTE_SERVERS = listOf(
            "https://router.project-osrm.org/route/v1/driving/",
            "https://routing.openstreetmap.de/routed-car/route/v1/driving/"
        )

        // Commandes proposées (coordonnées approximatives, à adapter)
        private val ALL_ORDERS = listOf(
            Order(
                1, "Restaurant Agdal", LatLng(33.9936, -6.8498),
                "Client Hay Riad", LatLng(33.9650, -6.8700)
            ),
            Order(
                2, "Gare Rabat Ville", LatLng(34.0148, -6.8369),
                "Client Hassan", LatLng(34.0243, -6.8222)
            ),
            Order(
                3, "Café Souissi", LatLng(33.9830, -6.8400),
                "Client Océan", LatLng(34.0316, -6.8366)
            )
        )
    }

    // Étapes
    private enum class Phase { IDLE, OFFER, TO_PICKUP, TO_CUSTOMER, AT_CUSTOMER, RETURNING, DONE }

    // Description d'un bouton selon l'étape
    private data class BtnSpec(val textRes: Int, val iconRes: Int, val colorRes: Int)

    // Un trajet dessiné sur la carte
    private class RouteSlot(var colorRes: Int, val zIndex: Float) {
        var points: List<LatLng>? = null
        var line: Polyline? = null
        var token = 0
    }

    // Vues
    private lateinit var infoCard: View
    private lateinit var tvDistance: TextView
    private lateinit var tvTime: TextView
    private lateinit var tvStatus: TextView
    private lateinit var tvOrderInfo: TextView
    private lateinit var layoutOrderDetails: View
    private lateinit var tvOrderPickup: TextView
    private lateinit var tvOrderCustomer: TextView
    private lateinit var tvOrderTrip: TextView
    private lateinit var rowButtons: View
    private lateinit var btnPrimary: MaterialButton
    private lateinit var btnSecondary: MaterialButton
    private lateinit var btnProfile: Button
    private lateinit var btnLocate: View
    private lateinit var btnLayers: View
    private lateinit var btnTilt: View
    private lateinit var btnSim: MaterialCardView

    // Carte et localisation
    private lateinit var map: GoogleMap
    private lateinit var fusedClient: FusedLocationProviderClient
    private var locationStarted = false
    private var firstFixShown = false
    private var courierMarker: Marker? = null
    private var pathLine: Polyline? = null
    private val pathPoints = mutableListOf<LatLng>()
    private var currentLatLng: LatLng? = null

    // Dialogue système « Activer la localisation »
    private val enableLocationLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { requestFreshLocation() }

    // Trajet 1 : livreur -> commande (ou retour au magasin), trajet 2 : commande -> client
    private val routeA = RouteSlot(R.color.blue, 1f)
    private val routeB = RouteSlot(R.color.green, 0.5f)

    // Livraison
    private val orders = ALL_ORDERS.toMutableList()
    private var order: Order = ALL_ORDERS[0]
    private var phase = Phase.IDLE
    private var lastLocation: Location? = null
    private var legToPickup = 0f      // livreur -> commande (m)
    private var legToCustomer = 0f    // commande -> client (m)
    private var legReturn = 0f        // client -> magasin (m)
    private var startTime = 0L
    private var isTracking = false

    // Simulation
    private var simulating = false
    private var simPos: LatLng = SIM_START
    private var simPath: List<LatLng>? = null
    private var simIndex = 0

    // Compte à rebours pour accepter la commande
    private var countdownLeft = 0

    // Chronomètre
    private val handler = Handler(Looper.getMainLooper())
    private val timerRunnable = object : Runnable {
        override fun run() {
            if (!isTracking) return
            val elapsed = SystemClock.elapsedRealtime() - startTime
            tvTime.text = getString(R.string.time_label, formatDuration(elapsed))
            handler.postDelayed(this, 1000)
        }
    }

    // Boucle de simulation
    private val simRunnable = object : Runnable {
        override fun run() {
            if (!simulating) return
            simStep()
            handler.postDelayed(this, SIM_TICK_MS)
        }
    }

    // Boucle du compte à rebours
    private val countdownRunnable = object : Runnable {
        override fun run() {
            if (phase != Phase.OFFER) return
            countdownLeft--
            if (countdownLeft <= 0) {
                expireOffer()
            } else {
                updateAcceptLabel()
                handler.postDelayed(this, 1000)
            }
        }
    }

    // Mises à jour de position réelles (FusedLocationProviderClient)
    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            if (simulating) return          // en simulation, on ignore le GPS réel
            val loc = result.lastLocation ?: return
            handleLocation(loc)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )
        setContentView(R.layout.activity_main)

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        infoCard = findViewById(R.id.infoCard)
        tvDistance = findViewById(R.id.tvDistance)
        tvTime = findViewById(R.id.tvTime)
        tvStatus = findViewById(R.id.tvStatus)
        tvOrderInfo = findViewById(R.id.tvOrderInfo)
        layoutOrderDetails = findViewById(R.id.layoutOrderDetails)
        tvOrderPickup = findViewById(R.id.tvOrderPickup)
        tvOrderCustomer = findViewById(R.id.tvOrderCustomer)
        tvOrderTrip = findViewById(R.id.tvOrderTrip)
        rowButtons = findViewById(R.id.rowButtons)
        btnPrimary = findViewById(R.id.btnPrimary)
        btnSecondary = findViewById(R.id.btnSecondary)
        btnProfile = findViewById(R.id.btnProfile)
        btnLocate = findViewById(R.id.btnLocate)
        btnLayers = findViewById(R.id.btnLayers)
        btnTilt = findViewById(R.id.btnTilt)
        btnSim = findViewById(R.id.btnSim)

        fusedClient = LocationServices.getFusedLocationProviderClient(this)

        createNotificationChannel()

        // Permission notifications (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), RC_NOTIF
            )
        }

        // La carte garde sa zone visible au-dessus de la carte d'information
        infoCard.addOnLayoutChangeListener { _, _, top, _, bottom, _, _, _, _ ->
            updateMapPadding(bottom - top)
        }

        btnPrimary.setOnClickListener {
            when (phase) {
                Phase.OFFER -> acceptOrder()
                Phase.TO_PICKUP -> confirmIfFar(order.pickup) { pickedUp() }
                Phase.TO_CUSTOMER -> confirmIfFar(order.customer) { arrivedAtCustomer() }
                Phase.AT_CUSTOMER -> finishDelivery(true)
                Phase.RETURNING -> confirmIfFar(order.pickup) { finishDelivery(false) }
                else -> {}
            }
        }
        btnSecondary.setOnClickListener {
            when (phase) {
                Phase.OFFER -> refuseOrder()
                Phase.AT_CUSTOMER -> startReturn()
                else -> {}
            }
        }
        btnProfile.setOnClickListener {
            startActivity(Intent(this, ProfileActivity::class.java))
        }

        // Outils de la carte
        btnLocate.setOnClickListener {
            val cur = currentLatLng
            if (cur != null && ::map.isInitialized) {
                map.animateCamera(CameraUpdateFactory.newLatLngZoom(cur, 16f))
            } else {
                Toast.makeText(this, R.string.waiting_location, Toast.LENGTH_SHORT).show()
                if (hasLocationPermission()) {
                    startTracking()
                    checkLocationSettings()
                    requestFreshLocation()
                } else {
                    requestLocationAndTrack()
                }
            }
        }
        btnLayers.setOnClickListener {
            if (!::map.isInitialized) return@setOnClickListener
            map.mapType = if (map.mapType == GoogleMap.MAP_TYPE_NORMAL) {
                GoogleMap.MAP_TYPE_HYBRID
            } else {
                GoogleMap.MAP_TYPE_NORMAL
            }
        }
        btnTilt.setOnClickListener {
            if (!::map.isInitialized) return@setOnClickListener
            val cam = map.cameraPosition
            val tilt = if (cam.tilt > 0f) 0f else 60f
            map.animateCamera(
                CameraUpdateFactory.newCameraPosition(
                    CameraPosition.Builder(cam).tilt(tilt).build()
                )
            )
        }
        btnSim.setOnClickListener { toggleSimulation() }

        resetToIdle()

        val mapFragment = supportFragmentManager
            .findFragmentById(R.id.map) as SupportMapFragment
        mapFragment.getMapAsync(this)

        requestLocationAndTrack()
    }

    override fun onMapReady(googleMap: GoogleMap) {
        map = googleMap

        // Style sombre (supprimer cette ligne pour la carte normale)
        map.setMapStyle(MapStyleOptions.loadRawResourceStyle(this, R.raw.map_night))
        updateMapPadding(infoCard.height)

        // Toucher une commande sur la carte
        map.setOnMarkerClickListener { marker ->
            val o = marker.tag as? Order
            if (o != null && (phase == Phase.IDLE || phase == Phase.OFFER)) {
                order = o
                phase = Phase.OFFER
                startCountdown()
                renderUi()
            }
            false
        }

        redrawMap()

        // On montre toutes les commandes, puis la position du livreur
        val bounds = LatLngBounds.Builder().apply {
            ALL_ORDERS.forEach { include(it.pickup); include(it.customer) }
        }.build()
        map.setOnMapLoadedCallback {
            map.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds, 120))
            currentLatLng?.let {
                firstFixShown = true
                showFirstFix(it)
            }
        }
    }

    private fun updateMapPadding(cardHeight: Int) {
        if (!::map.isInitialized) return
        val extra = (24 * resources.displayMetrics.density).toInt()
        map.setPadding(0, 0, 0, cardHeight + extra)
    }

    // Première position reçue : on la montre (avec les commandes si elles sont proches)
    private fun showFirstFix(latLng: LatLng) {
        val near = ALL_ORDERS.any { distanceBetween(latLng, it.pickup) < 30_000f }
        if (near) {
            fitCamera(latLng, *ALL_ORDERS.map { it.pickup }.toTypedArray())
        } else {
            map.animateCamera(CameraUpdateFactory.newLatLngZoom(latLng, 15f))
        }
    }

    // ---------- Permissions (EasyPermissions) ----------

    // Précise OU approximative : l'une des deux suffit
    private fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(
                    this, Manifest.permission.ACCESS_COARSE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED

    @AfterPermissionGranted(RC_LOCATION)
    private fun requestLocationAndTrack() {
        if (hasLocationPermission()) {
            startTracking()
        } else {
            EasyPermissions.requestPermissions(
                this,
                getString(R.string.permission_rationale),
                RC_LOCATION,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        EasyPermissions.onRequestPermissionsResult(requestCode, permissions, grantResults, this)
    }

    override fun onPermissionsGranted(requestCode: Int, perms: MutableList<String>) {
        if (requestCode == RC_LOCATION) startTracking()
    }

    override fun onPermissionsDenied(requestCode: Int, perms: MutableList<String>) {
        if (requestCode != RC_LOCATION) return
        if (hasLocationPermission()) {
            // L'utilisateur a choisi « approximative » : on continue quand même
            startTracking()
        } else if (EasyPermissions.somePermissionPermanentlyDenied(this, perms)) {
            // Refus définitif : on propose d'ouvrir les paramètres de l'application
            AppSettingsDialog.Builder(this).build().show()
        }
    }

    // ---------- Localisation ----------

    @SuppressLint("MissingPermission")
    private fun startTracking() {
        if (locationStarted || !hasLocationPermission()) return
        locationStarted = true

        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2000L)
            .setMinUpdateIntervalMillis(1000L)
            .build()
        fusedClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())

        checkLocationSettings()     // propose d'activer le GPS s'il est éteint
        requestFreshLocation()      // position immédiate

        // Toujours pas de position après 12 secondes : on prévient l'utilisateur
        handler.postDelayed({
            if (currentLatLng == null) {
                Toast.makeText(this, R.string.location_unavailable, Toast.LENGTH_LONG).show()
            }
        }, 12000)
    }

    // Si la localisation du téléphone est éteinte : dialogue « Activer la localisation »
    private fun checkLocationSettings() {
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2000L).build()
        val settings = LocationSettingsRequest.Builder().addLocationRequest(request).build()
        LocationServices.getSettingsClient(this).checkLocationSettings(settings)
            .addOnFailureListener { e ->
                if (e is ResolvableApiException) {
                    try {
                        enableLocationLauncher.launch(
                            IntentSenderRequest.Builder(e.resolution).build()
                        )
                    } catch (ex: Exception) {
                        Log.e("LOCATION", "Dialogue impossible : ${ex.message}")
                    }
                }
            }
    }

    // Demande tout de suite la dernière position connue et une nouvelle mesure
    @SuppressLint("MissingPermission")
    private fun requestFreshLocation() {
        if (!hasLocationPermission()) return
        fusedClient.lastLocation.addOnSuccessListener { loc ->
            if (loc != null && currentLatLng == null && !simulating) handleLocation(loc)
        }
        fusedClient.getCurrentLocation(
            Priority.PRIORITY_HIGH_ACCURACY, CancellationTokenSource().token
        ).addOnSuccessListener { loc ->
            if (loc != null && !simulating) handleLocation(loc)
        }
    }

    // Traite une position (réelle ou simulée)
    private fun handleLocation(loc: Location) {
        val latLng = LatLng(loc.latitude, loc.longitude)
        currentLatLng = latLng
        if (!::map.isInitialized) return

        updateCourierMarker(latLng)
        if (!firstFixShown) {
            firstFixShown = true
            showFirstFix(latLng)
        }

        // Position peu précise : on l'affiche, mais on ne compte pas la distance
        if (loc.hasAccuracy() && loc.accuracy > 30f) return

        val active = phase == Phase.TO_PICKUP ||
                phase == Phase.TO_CUSTOMER ||
                phase == Phase.RETURNING
        if (!active) return

        val prev = lastLocation
        if (prev == null) {
            lastLocation = loc
        } else {
            val dist = prev.distanceTo(loc)
            val seconds =
                (loc.elapsedRealtimeNanos - prev.elapsedRealtimeNanos) / 1_000_000_000.0
            if (seconds > 0 && dist / seconds > 100) {
                // Saut irréaliste (> 100 m/s) : on repart de cette position
                pathPoints.clear()
                lastLocation = loc
            } else if (dist >= 5f) {
                // On ignore les petits mouvements (bruit du GPS à l'arrêt)
                when (phase) {
                    Phase.TO_PICKUP -> legToPickup += dist
                    Phase.TO_CUSTOMER -> legToCustomer += dist
                    Phase.RETURNING -> legReturn += dist
                    else -> {}
                }
                lastLocation = loc
            }
        }

        pathPoints.add(latLng)
        pathLine?.points = pathPoints
        updateProgress()
    }

    // ---------- Compte à rebours (30 s pour accepter) ----------

    private fun startCountdown() {
        handler.removeCallbacks(countdownRunnable)
        countdownLeft = OFFER_SECONDS
        handler.postDelayed(countdownRunnable, 1000)
    }

    private fun stopCountdown() {
        handler.removeCallbacks(countdownRunnable)
    }

    private fun updateAcceptLabel() {
        if (phase == Phase.OFFER) {
            btnPrimary.text =
                "${getString(R.string.btn_accept)} (${countdownLeft}${getString(R.string.seconds_short)})"
        }
    }

    // Temps écoulé : la commande disparaît
    private fun expireOffer() {
        orders.remove(order)
        if (orders.isEmpty()) orders.addAll(ALL_ORDERS)
        phase = Phase.IDLE
        redrawMap()
        renderUi()
        Toast.makeText(this, R.string.offer_expired, Toast.LENGTH_SHORT).show()
    }

    // ---------- Simulation ----------

    private fun toggleSimulation() {
        if (simulating) {
            simulating = false
            handler.removeCallbacks(simRunnable)
            btnSim.setCardBackgroundColor(ContextCompat.getColor(this, R.color.night_card))
            Toast.makeText(this, R.string.sim_off, Toast.LENGTH_SHORT).show()
            return
        }
        // On part de la vraie position du téléphone
        if (hasLocationPermission()) {
            startSimulationFromRealPosition()
        } else {
            beginSimulation(currentLatLng)
        }
    }

    @SuppressLint("MissingPermission")
    private fun startSimulationFromRealPosition() {
        val known = currentLatLng
        if (known != null) {
            beginSimulation(known)
            return
        }
        fusedClient.lastLocation
            .addOnSuccessListener { loc ->
                beginSimulation(if (loc != null) LatLng(loc.latitude, loc.longitude) else null)
            }
            .addOnFailureListener { beginSimulation(null) }
    }

    private fun beginSimulation(start: LatLng?) {
        if (start == null) {
            Toast.makeText(this, R.string.sim_no_gps, Toast.LENGTH_LONG).show()
        }
        val from = start ?: SIM_START
        simulating = true
        simPath = null
        simPos = from
        emitSimLocation(from)
        handler.post(simRunnable)
        btnSim.setCardBackgroundColor(ContextCompat.getColor(this, R.color.green))
        Toast.makeText(this, R.string.sim_on, Toast.LENGTH_SHORT).show()
        if (::map.isInitialized) {
            map.animateCamera(CameraUpdateFactory.newLatLngZoom(from, 15f))
        }
    }

    // Le livreur simulé avance sur le trajet de l'étape en cours
    private fun simStep() {
        val pts = when (phase) {
            Phase.TO_PICKUP, Phase.RETURNING -> routeA.points
            Phase.TO_CUSTOMER -> routeB.points
            else -> null
        } ?: return

        // Nouveau trajet : on repart du début de la liste
        if (pts !== simPath) {
            simPath = pts
            simIndex = 0
        }
        if (simIndex >= pts.size) return    // déjà arrivé au bout du trajet

        var remaining = SIM_SPEED * (SIM_TICK_MS / 1000f)
        var pos = simPos
        while (remaining > 0f && simIndex < pts.size) {
            val target = pts[simIndex]
            val d = distanceBetween(pos, target)
            if (d <= remaining) {
                remaining -= d
                pos = target
                simIndex++
            } else {
                val ratio = (remaining / d).toDouble()
                pos = LatLng(
                    pos.latitude + (target.latitude - pos.latitude) * ratio,
                    pos.longitude + (target.longitude - pos.longitude) * ratio
                )
                remaining = 0f
            }
        }
        simPos = pos
        emitSimLocation(pos)

        if (simIndex >= pts.size) {
            Toast.makeText(this, R.string.sim_arrived, Toast.LENGTH_SHORT).show()
        }
    }

    private fun emitSimLocation(pos: LatLng) {
        val loc = Location("simulation").apply {
            latitude = pos.latitude
            longitude = pos.longitude
            accuracy = 5f
            time = System.currentTimeMillis()
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        }
        handleLocation(loc)
    }

    // ---------- Étapes de la livraison ----------

    private fun acceptOrder() {
        val from = currentLatLng
        if (from == null) {
            Toast.makeText(this, R.string.waiting_location, Toast.LENGTH_SHORT).show()
            return
        }
        stopCountdown()
        legToPickup = 0f
        legToCustomer = 0f
        legReturn = 0f
        lastLocation = null
        pathPoints.clear()

        // Heure de début
        startTime = SystemClock.elapsedRealtime()
        isTracking = true
        handler.post(timerRunnable)

        phase = Phase.TO_PICKUP
        clearRoutes()
        redrawMap()
        // Trajet 1 : livreur -> commande (bleu) ; trajet 2 : commande -> client (vert)
        requestRoute(routeA, from, order.pickup, R.color.blue)
        requestRoute(routeB, order.pickup, order.customer, R.color.green, 1500L)
        fitCamera(from, order.pickup, order.customer)
        renderUi()
    }

    private fun refuseOrder() {
        stopCountdown()
        orders.remove(order)
        if (orders.isEmpty()) orders.addAll(ALL_ORDERS)
        phase = Phase.IDLE
        redrawMap()
        renderUi()
        Toast.makeText(this, R.string.order_refused, Toast.LENGTH_SHORT).show()
    }

    private fun pickedUp() {
        phase = Phase.TO_CUSTOMER
        pathPoints.clear()
        clearSlot(routeA)           // le trajet 1 est terminé, le trajet 2 reste
        redrawMap()
        fitCamera(currentLatLng, order.customer)
        renderUi()
        Toast.makeText(this, R.string.picked_up_msg, Toast.LENGTH_LONG).show()
    }

    private fun arrivedAtCustomer() {
        phase = Phase.AT_CUSTOMER
        clearRoutes()
        redrawMap()
        renderUi()
    }

    private fun startReturn() {
        phase = Phase.RETURNING
        pathPoints.clear()
        clearRoutes()
        redrawMap()
        currentLatLng?.let { requestRoute(routeA, it, order.pickup, R.color.red) }
        fitCamera(currentLatLng, order.pickup)
        renderUi()
    }

    private fun finishDelivery(delivered: Boolean) {
        isTracking = false
        phase = Phase.DONE
        handler.removeCallbacks(timerRunnable)
        clearRoutes()

        val duration = formatDuration(SystemClock.elapsedRealtime() - startTime)
        val kmPickup = legToPickup / 1000f
        val kmCustomer = legToCustomer / 1000f
        val kmReturn = legReturn / 1000f
        val kmTotal = kmPickup + kmCustomer + kmReturn

        val statusRes = if (delivered) R.string.status_delivered else R.string.status_returned
        setStatus(statusRes, if (delivered) R.color.green else R.color.red)
        setButtons(null, null)
        tvDistance.text = getString(R.string.distance_label, kmTotal)

        val message = if (delivered) {
            getString(R.string.summary_text_3, kmPickup, kmCustomer, kmTotal, duration)
        } else {
            getString(
                R.string.summary_text_return, kmPickup, kmCustomer, kmReturn, kmTotal, duration
            )
        }

        orders.remove(order)

        AlertDialog.Builder(this)
            .setTitle(R.string.summary_title)
            .setMessage(message)
            .setPositiveButton(R.string.ok, null)
            .setOnDismissListener { resetToIdle() }
            .show()

        sendNotification(statusRes, kmTotal, duration)
    }

    private fun resetToIdle() {
        stopCountdown()
        phase = Phase.IDLE
        legToPickup = 0f
        legToCustomer = 0f
        legReturn = 0f
        lastLocation = null
        pathPoints.clear()
        if (orders.isEmpty()) orders.addAll(ALL_ORDERS)
        tvDistance.text = getString(R.string.distance_label, 0f)
        tvTime.text = getString(R.string.time_label, "00:00:00")
        clearRoutes()
        redrawMap()
        renderUi()
    }

    // Si le livreur est loin du point, on demande confirmation (utile pour la démo)
    private fun confirmIfFar(target: LatLng, onConfirm: () -> Unit) {
        val cur = currentLatLng
        if (cur == null) {
            onConfirm()
            return
        }
        val dist = distanceBetween(cur, target)
        if (dist <= ARRIVAL_RADIUS) {
            onConfirm()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.far_title)
            .setMessage(getString(R.string.far_message, dist.toInt()))
            .setPositiveButton(R.string.confirm) { _, _ -> onConfirm() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ---------- Interface ----------

    private fun renderUi() {
        // Les détails de la commande ne s'affichent que pour une nouvelle commande
        layoutOrderDetails.visibility = if (phase == Phase.OFFER) View.VISIBLE else View.GONE
        tvOrderInfo.visibility = if (phase == Phase.OFFER) View.GONE else View.VISIBLE

        when (phase) {
            Phase.IDLE -> {
                setStatus(R.string.status_waiting, R.color.dark_gray)
                tvOrderInfo.setText(R.string.hint_choose_order)
                setButtons(null, null)
            }
            Phase.OFFER -> {
                setStatus(R.string.status_offer, R.color.blue)
                val toPickup = currentLatLng?.let { distanceBetween(it, order.pickup) } ?: 0f
                val estimate = toPickup + distanceBetween(order.pickup, order.customer)
                tvOrderPickup.text = "${getString(R.string.pickup_title)} : ${order.pickupName}"
                tvOrderCustomer.text = "${getString(R.string.customer_title)} : ${order.customerName}"
                tvOrderTrip.text = getString(R.string.trip_estimate, estimate / 1000f)
                setButtons(
                    BtnSpec(R.string.btn_accept, R.drawable.ic_check, R.color.green),
                    BtnSpec(R.string.btn_refuse, R.drawable.ic_close, R.color.red)
                )
                updateAcceptLabel()
            }
            Phase.TO_PICKUP -> {
                setStatus(R.string.status_to_pickup, R.color.blue)
                tvOrderInfo.text = remainingText()
                setButtons(BtnSpec(R.string.btn_picked_up, R.drawable.ic_check, R.color.blue), null)
            }
            Phase.TO_CUSTOMER -> {
                setStatus(R.string.status_to_customer, R.color.green)
                tvOrderInfo.text = remainingText()
                setButtons(BtnSpec(R.string.btn_arrived, R.drawable.ic_check, R.color.green), null)
            }
            Phase.AT_CUSTOMER -> {
                setStatus(R.string.status_at_customer, R.color.dark_gray)
                tvOrderInfo.text = getString(R.string.info_at_customer, order.customerName)
                setButtons(
                    BtnSpec(R.string.btn_customer_accepts, R.drawable.ic_check, R.color.green),
                    BtnSpec(R.string.btn_customer_refuses, R.drawable.ic_close, R.color.red)
                )
            }
            Phase.RETURNING -> {
                setStatus(R.string.status_returning, R.color.red)
                tvOrderInfo.text = remainingText()
                setButtons(BtnSpec(R.string.btn_returned, R.drawable.ic_check, R.color.red), null)
            }
            Phase.DONE -> {}
        }
    }

    private fun setButtons(primary: BtnSpec?, secondary: BtnSpec?) {
        bindButton(btnPrimary, primary)
        bindButton(btnSecondary, secondary)
        rowButtons.visibility =
            if (primary == null && secondary == null) View.GONE else View.VISIBLE
    }

    private fun bindButton(btn: MaterialButton, spec: BtnSpec?) {
        if (spec == null) {
            btn.visibility = View.GONE
            return
        }
        btn.visibility = View.VISIBLE
        btn.setText(spec.textRes)
        btn.setIconResource(spec.iconRes)
        btn.backgroundTintList =
            ColorStateList.valueOf(ContextCompat.getColor(this, spec.colorRes))
    }

    private fun setStatus(statusRes: Int, colorRes: Int) {
        tvStatus.text = getString(R.string.status_label, getString(statusRes))
        tvStatus.backgroundTintList =
            ColorStateList.valueOf(ContextCompat.getColor(this, colorRes))
    }

    private fun remainingText(): String {
        val (name, target) = when (phase) {
            Phase.TO_PICKUP, Phase.RETURNING -> order.pickupName to order.pickup
            Phase.TO_CUSTOMER -> order.customerName to order.customer
            else -> return ""
        }
        val km = currentLatLng?.let { distanceBetween(it, target) / 1000f } ?: 0f
        return getString(R.string.info_remaining, name, km)
    }

    private fun updateProgress() {
        tvOrderInfo.text = remainingText()
        val total = (legToPickup + legToCustomer + legReturn) / 1000f
        tvDistance.text = getString(R.string.distance_label, total)
    }

    // ---------- Carte ----------

    private fun addPickupMarker(o: Order): Marker? {
        val m = map.addMarker(
            MarkerOptions()
                .position(o.pickup)
                .title("${getString(R.string.pickup_title)} : ${o.pickupName}")
                .snippet("${getString(R.string.customer_title)} : ${o.customerName}")
                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE))
        )
        m?.tag = o
        return m
    }

    private fun addCustomerMarker(o: Order): Marker? {
        return map.addMarker(
            MarkerOptions()
                .position(o.customer)
                .title("${getString(R.string.customer_title)} : ${o.customerName}")
                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN))
        )
    }

    private fun redrawMap() {
        if (!::map.isInitialized) return
        map.clear()
        courierMarker = null
        pathLine = null
        routeA.line = null
        routeB.line = null

        when (phase) {
            // Les commandes proposées
            Phase.IDLE, Phase.OFFER -> orders.forEach { o -> addPickupMarker(o) }
            // La commande à récupérer ET le client : 3 points avec la position du livreur
            Phase.TO_PICKUP -> {
                addPickupMarker(order)
                addCustomerMarker(order)
            }
            // Le client
            Phase.TO_CUSTOMER, Phase.AT_CUSTOMER -> addCustomerMarker(order)
            // Retour au magasin
            Phase.RETURNING -> addPickupMarker(order)
            Phase.DONE -> {}
        }

        drawSlot(routeA)
        drawSlot(routeB)

        // Le chemin réellement parcouru par le livreur (gris clair fin)
        pathLine = map.addPolyline(
            PolylineOptions()
                .addAll(pathPoints)
                .color(ContextCompat.getColor(this, R.color.light_gray))
                .width(7f)
                .zIndex(2f)
        )
        currentLatLng?.let { updateCourierMarker(it) }
    }

    private fun updateCourierMarker(latLng: LatLng) {
        if (courierMarker == null) {
            courierMarker = map.addMarker(
                MarkerOptions()
                    .position(latLng)
                    .title(getString(R.string.courier_title))
                    .zIndex(3f)
            )
        } else {
            courierMarker?.position = latLng
        }
    }

    // Ajuste la caméra pour voir tous les points
    private fun fitCamera(vararg points: LatLng?) {
        if (!::map.isInitialized) return
        val valid = points.filterNotNull()
        if (valid.size < 2) return
        try {
            val builder = LatLngBounds.Builder()
            valid.forEach { builder.include(it) }
            map.animateCamera(CameraUpdateFactory.newLatLngBounds(builder.build(), 100))
        } catch (e: Exception) {
            // la carte n'est pas encore prête : on ignore
        }
    }

    // ---------- Trajets (routes) ----------

    private fun drawSlot(slot: RouteSlot) {
        slot.line?.remove()
        slot.line = null
        val pts = slot.points ?: return
        slot.line = map.addPolyline(
            PolylineOptions()
                .addAll(pts)
                .color(ContextCompat.getColor(this, slot.colorRes))
                .width(14f)
                .zIndex(slot.zIndex)
        )
    }

    private fun clearSlot(slot: RouteSlot) {
        slot.token++
        slot.points = null
        slot.line?.remove()
        slot.line = null
    }

    private fun clearRoutes() {
        clearSlot(routeA)
        clearSlot(routeB)
    }

    private fun requestRoute(
        slot: RouteSlot, from: LatLng, to: LatLng, colorRes: Int, delayMs: Long = 0L
    ) {
        clearSlot(slot)
        slot.colorRes = colorRes
        val token = slot.token
        Thread {
            if (delayMs > 0) Thread.sleep(delayMs)
            // Si les services de routes ne répondent pas : ligne droite
            val pts = fetchRoutePoints(from, to) ?: listOf(from, to)
            runOnUiThread {
                if (token == slot.token && ::map.isInitialized) {
                    slot.points = pts
                    drawSlot(slot)
                }
            }
        }.start()
    }

    // Route par les rues avec OSRM (gratuit, sans clé)
    private fun fetchRoutePoints(from: LatLng, to: LatLng): List<LatLng>? {
        val coords = "${from.longitude},${from.latitude};${to.longitude},${to.latitude}"
        for (server in ROUTE_SERVERS) {
            try {
                val conn = URL("$server$coords?overview=full&geometries=geojson")
                    .openConnection() as HttpURLConnection
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                conn.setRequestProperty("User-Agent", "SuiviLivraisons-M205/1.0 (projet scolaire)")
                if (conn.responseCode != 200) {
                    Log.e("ROUTE", "$server -> HTTP ${conn.responseCode}")
                    continue
                }
                val text = conn.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(text)
                if (json.optString("code") != "Ok") {
                    Log.e("ROUTE", "$server -> ${json.optString("code")}")
                    continue
                }
                val geometry = json.getJSONArray("routes").getJSONObject(0)
                    .getJSONObject("geometry").getJSONArray("coordinates")
                return List(geometry.length()) { i ->
                    val c = geometry.getJSONArray(i)
                    LatLng(c.getDouble(1), c.getDouble(0))
                }
            } catch (e: Exception) {
                Log.e("ROUTE", "$server -> ${e.javaClass.simpleName}: ${e.message}")
            }
        }
        return null
    }

    // ---------- Outils ----------

    private fun distanceBetween(a: LatLng, b: LatLng): Float {
        val result = FloatArray(1)
        Location.distanceBetween(a.latitude, a.longitude, b.latitude, b.longitude, result)
        return result[0]
    }

    private fun formatDuration(ms: Long): String {
        val s = ms / 1000
        return String.format("%02d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60)
    }

    // ---------- Notifications ----------

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_HIGH
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    @SuppressLint("MissingPermission")
    private fun sendNotification(statusRes: Int, km: Float, duration: String) {
        val manager = NotificationManagerCompat.from(this)
        if (!manager.areNotificationsEnabled()) return

        val notif = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(
                getString(R.string.notif_text, getString(statusRes), km, duration)
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        manager.notify(1, notif)
    }

    override fun onDestroy() {
        super.onDestroy()
        simulating = false
        handler.removeCallbacks(simRunnable)
        handler.removeCallbacks(countdownRunnable)
        clearRoutes()
        fusedClient.removeLocationUpdates(locationCallback)
        handler.removeCallbacks(timerRunnable)
    }
}