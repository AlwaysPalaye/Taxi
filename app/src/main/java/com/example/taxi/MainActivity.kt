package com.example.taxi

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.GoogleMap
import com.google.android.gms.maps.OnMapReadyCallback
import com.google.android.gms.maps.SupportMapFragment
import com.google.android.gms.maps.model.*
import com.google.android.libraries.places.api.Places
import com.google.android.libraries.places.api.model.Place
import com.google.android.libraries.places.api.net.PlacesClient
import com.google.android.libraries.places.widget.Autocomplete
import com.google.android.libraries.places.widget.model.AutocompleteActivityMode
import com.google.maps.android.PolyUtil
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity(), OnMapReadyCallback {

    private lateinit var googleMap: GoogleMap
    private lateinit var btnEstimateRide: Button
    private lateinit var etCustomerId: EditText
    private lateinit var etOriginAddress: EditText
    private lateinit var etDestinationAddress: EditText
    private lateinit var progressBar: ProgressBar
    private lateinit var rvTripOptions: RecyclerView
    private lateinit var tvDistance: TextView
    private lateinit var tvDuration: TextView
    private lateinit var placesClient: PlacesClient
    private lateinit var customerId: String
    private var selectedOrigin: com.google.android.libraries.places.api.model.Place? = null
    private var selectedDestination: com.google.android.libraries.places.api.model.Place? = null

    private val AUTOCOMPLETE_REQUEST_ORIGIN = 1
    private val AUTOCOMPLETE_REQUEST_DESTINATION = 2

    // Cache de motoristas
    private val tripOptionsCache = mutableMapOf<String, List<TripOption>>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Inicializa o Google Places
        if (!Places.isInitialized()) {
            Places.initialize(applicationContext, getString(R.string.google_maps_key))
        }
        placesClient = Places.createClient(this)

        // Inicializa componentes
        btnEstimateRide = findViewById(R.id.btn_estimate_ride)
        etCustomerId = findViewById(R.id.et_customer_id)
        etOriginAddress = findViewById(R.id.et_origin_address)
        etDestinationAddress = findViewById(R.id.et_destination_address)
        progressBar = findViewById(R.id.progress_bar)
        rvTripOptions = findViewById(R.id.rv_trip_options)
        tvDistance = findViewById(R.id.tv_distance)
        tvDuration = findViewById(R.id.tv_duration)

        rvTripOptions.layoutManager = LinearLayoutManager(this)

        setupPlacesAutocomplete()

        val mapFragment = supportFragmentManager.findFragmentById(R.id.map_fragment) as SupportMapFragment
        mapFragment.getMapAsync(this)

        btnEstimateRide.setOnClickListener {
            if (validateInputs()) {
                displayDistancesAndDurations()
                calculateRoute()
            }
        }
    }

    private fun setupPlacesAutocomplete() {
        etOriginAddress.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                startPlacesAutocomplete(true)
            }
        }

        etDestinationAddress.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                startPlacesAutocomplete(false)
            }
        }
    }

    private fun startPlacesAutocomplete(isOrigin: Boolean) {
        val fields = listOf(
            com.google.android.libraries.places.api.model.Place.Field.ID,
            com.google.android.libraries.places.api.model.Place.Field.NAME,
            com.google.android.libraries.places.api.model.Place.Field.LAT_LNG,
            com.google.android.libraries.places.api.model.Place.Field.ADDRESS
        )
        val intent = Autocomplete.IntentBuilder(AutocompleteActivityMode.OVERLAY, fields)
            .setCountries(listOf("BR"))
            .build(this)
        
        startActivityForResult(intent, if (isOrigin) AUTOCOMPLETE_REQUEST_ORIGIN else AUTOCOMPLETE_REQUEST_DESTINATION)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        
        if (resultCode == Activity.RESULT_OK && data != null) {
            val place = Autocomplete.getPlaceFromIntent(data)
            
            when (requestCode) {
                AUTOCOMPLETE_REQUEST_ORIGIN -> {
                    selectedOrigin = place
                    etOriginAddress.setText(place.address)
                }
                AUTOCOMPLETE_REQUEST_DESTINATION -> {
                    selectedDestination = place
                    etDestinationAddress.setText(place.address)
                }
            }
        }
    }

    private fun validateInputs(): Boolean {
        customerId = etCustomerId.text.toString().trim()
        
        if (customerId.isEmpty()) {
            showToast("Por favor, insira o ID do cliente")
            return false
        }

        if (selectedOrigin == null) {
            showToast("Por favor, selecione um endereço de origem válido")
            return false
        }

        if (selectedDestination == null) {
            showToast("Por favor, selecione um endereço de destino válido")
            return false
        }

        return true
    }

    override fun onMapReady(map: GoogleMap) {
        googleMap = map
        setupMapBounds()
        calculateRoute()
        clearMap()
    }

    private fun clearMap() {
        // Remove todos os marcadores e rotas do mapa
        googleMap.clear()
    }

    private fun addMarkers(origin: LatLng, destination: LatLng) {
        clearMap() // Limpa o mapa antes de adicionar novos marcadores

        // Adiciona marcador na origem
        googleMap.addMarker(
            MarkerOptions()
                .position(origin)
                .title("Origem")
                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN)) // Ícone personalizado
        )

        // Adiciona marcador no destino
        googleMap.addMarker(
            MarkerOptions()
                .position(destination)
                .title("Destino")
                .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED)) // Ícone personalizado
        )

        // Define os limites para que os dois pontos sejam exibidos na tela
        val bounds = LatLngBounds.Builder()
        bounds.include(origin)
        bounds.include(destination)
        googleMap.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds.build(), 100))
    }

    private fun setupMapBounds() {
        if (selectedOrigin != null && selectedDestination != null) {
            val bounds = LatLngBounds.Builder()
            bounds.include(selectedOrigin!!.latLng)
            bounds.include(selectedDestination!!.latLng)
            googleMap.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds.build(), 100))
        }
    }

    private fun calculateRoute() {
        if (selectedOrigin != null && selectedDestination != null) {
            val origin = selectedOrigin!!.latLng
            val destination = selectedDestination!!.latLng

            progressBar.visibility = View.VISIBLE
            addMarkers(origin, destination) // Adiciona os marcadores
            drawRoute(origin, destination)  // Desenha a rota
            estimateRide(customerId, origin, destination) { response ->
                runOnUiThread { progressBar.visibility = View.GONE }
                handleEstimateResponse(response)
            }

            determineScenarioFromApi(etOriginAddress.text.toString().trim(), etDestinationAddress.text.toString().trim()) { tripOptions ->
                runOnUiThread {
                    if (tripOptions.isEmpty()) {
                        showToast("Nenhum motorista disponível.")
                    } else {
                        val calculatedDistance = calculateDistance(origin, destination)
                        rvTripOptions.adapter = TripOptionsAdapter(tripOptions) { selectedOption ->
                            confirmRide(selectedOption, calculatedDistance, etOriginAddress.text.toString().trim(), etDestinationAddress.text.toString().trim())
                        }

                        rvTripOptions.visibility = View.VISIBLE
                    }
                    progressBar.visibility = View.GONE
                }
            }
        }
    }

    private fun handleEstimateResponse(response: String) {
        try {
            Log.d("API_RESPONSE", response) // Log para verificar a resposta completa da API

            val jsonResponse = JSONObject(response)
            val options = jsonResponse.optJSONArray("options")

            if (options == null || options.length() == 0) {
                runOnUiThread { showToast("Nenhum motorista disponível.") }
                Log.d("HANDLE_RESPONSE", "Nenhum motorista disponível na resposta.")
                return
            }

            val tripOptions = mutableListOf<TripOption>()
            for (i in 0 until options.length()) {
                val option = options.getJSONObject(i)
                tripOptions.add(
                    TripOption(
                        id = option.getInt("id"),
                        name = option.getString("name"),
                        description = option.getString("description"),
                        vehicle = option.getString("vehicle"),
                        rating = option.getJSONObject("review").getDouble("rating"),
                        comment = option.getJSONObject("review").getString("comment"),
                        value = option.getDouble("value")
                    )
                )
            }

            Log.d("HANDLE_RESPONSE", "Motoristas carregados: ${tripOptions.size}")
            runOnUiThread {

                rvTripOptions.visibility = View.VISIBLE
            }
        } catch (e: Exception) {
            Log.e("HANDLE_RESPONSE", "Erro ao processar a resposta: ${e.message}")
        }
    }

    private fun drawRoute(origin: LatLng, destination: LatLng) {
        val apiKey = "AIzaSyD7ODMWrn_tHKFeYmyNAV_1wpnKVpkdL9Q"
        val url = "https://maps.googleapis.com/maps/api/directions/json?" +
                "origin=${origin.latitude},${origin.longitude}" +
                "&destination=${destination.latitude},${destination.longitude}" +
                "&key=$apiKey"

        val request = Request.Builder().url(url).build()
        OkHttpClient().newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread { showToast("Erro ao conectar com a API: ${e.message}")
                }
                Log.e("DIRECTIONS_ERROR", "Erro ao buscar rota: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                val responseBody = response.body?.string()
                if (!response.isSuccessful || responseBody.isNullOrEmpty()) {
                    Log.e("DIRECTIONS_API", "Erro na API de Direções: ${response.message}")
                    runOnUiThread { showToast("Erro ao obter direções. Tente novamente.") }
                    return
                }

                try {
                    val jsonResponse = JSONObject(responseBody)
                    val routes = jsonResponse.optJSONArray("routes")

                    if (routes == null || routes.length() == 0) {
                        Log.e("DIRECTIONS_API", "Nenhuma rota encontrada na resposta da API.")
                        runOnUiThread { showToast("Nenhuma rota disponível para o trajeto selecionado.") }
                        return
                    }

                    val route = routes.getJSONObject(0)
                    val overviewPolyline = route.getJSONObject("overview_polyline").getString("points")
                    val legs = route.optJSONArray("legs")?.getJSONObject(0)

                    // Extraindo distância e duração
                    val distance = legs?.optJSONObject("distance")?.optString("text") ?: "Desconhecida"
                    val duration = legs?.optJSONObject("duration")?.optString("text") ?: "Desconhecida"

                    runOnUiThread {
                        addPolylineToMap(overviewPolyline) // Adiciona a rota ao mapa
                        tvDistance.text = "Distância: $distance"
                        tvDuration.text = "Duração: $duration"
                        tvDistance.visibility = View.VISIBLE
                        tvDuration.visibility = View.VISIBLE
                    }

                } catch (e: Exception) {
                    Log.e("DIRECTIONS_API", "Erro ao processar JSON: ${e.message}")
                    runOnUiThread { showToast("Erro ao processar os dados da rota.") }
                }
            }
        })
    }

    private fun addPolylineToMap(encodedPolyline: String) {
        val decodedPath = PolyUtil.decode(encodedPolyline)
        googleMap.addPolyline(
            PolylineOptions()
                .addAll(decodedPath)
                .width(10f)
                .color(Color.BLUE)
        )
    }

    private fun estimateRide(customerId: String, origin: LatLng, destination: LatLng, callback: (String) -> Unit) {
        val url = "https://xd5zl5kk2yltomvw5fb37y3bm40vsyrx.lambda-url.sa-east-1.on.aws/ride/estimate"
        val json = JSONObject().apply {
            put("customer_id", customerId)
            put("origin", "${origin.latitude},${origin.longitude}")
            put("destination", "${destination.latitude},${destination.longitude}")
        }

        val body = json.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url(url).post(body).build()

        OkHttpClient().newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.e("API_ERROR", "Erro na conexão: ${e.message}")
                callback("Erro na conexão")
            }

            override fun onResponse(call: Call, response: Response) {
                val responseBody = response.body?.string()
                if (response.isSuccessful && responseBody != null) {
                    callback(responseBody)
                } else {
                    callback("Erro na API")
                }
            }
        })
    }

    private fun confirmRide(option: TripOption, distance: Double, origin: String, destination: String) {
        // Verifica se os campos necessários estão preenchidos
        if (origin.isEmpty() || destination.isEmpty() || distance <= 0) {
            showToast("Dados insuficientes para confirmar a viagem.")
            return
        }

        // Prepara o corpo da requisição
        val requestBody = JSONObject().apply {
            put("customer_id", customerId)
            put("origin", origin)
            put("destination", destination)
            put("distance", distance)
            put("duration", tvDuration.text.toString().trim())
            put("driver", JSONObject().apply {
                put("id", option.id)
                put("name", option.name)
            })
            put("value", option.value)
        }.toString().toRequestBody("application/json".toMediaType())

        // Configura a requisição
        val request = Request.Builder()
            .url("https://xd5zl5kk2yltomvw5fb37y3bm40vsyrx.lambda-url.sa-east-1.on.aws/ride/confirm")
            .patch(requestBody)
            .build()

        // Faz a chamada para o endpoint
        OkHttpClient().newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                runOnUiThread { showToast("Erro ao confirmar a viagem: ${e.message}") }
            }

            override fun onResponse(call: Call, response: Response) {
                val responseBody = response.body?.string()
                runOnUiThread {
                    if (response.isSuccessful) {
                        val jsonResponse = JSONObject(responseBody ?: "{}")
                        if (jsonResponse.optBoolean("success", false)) {
                            showToast("Viagem confirmada com sucesso!")

                            android.os.Handler().postDelayed({
                                val intent = Intent(this@MainActivity, HistoryScreen::class.java).apply {
                                    putExtra("userId", customerId)
                                    putExtra("driverName", option.name)
                                    putExtra("origin", origin)
                                    putExtra("destination", destination)
                                    putExtra("distance", distance)
                                    putExtra("duration", tvDuration.text.toString().trim())
                                    putExtra("value", option.value)
                                }
                                startActivity(intent)
                            }, 2000)
                        } else {
                            showToast("Erro ao confirmar a viagem.")

                        }
                    } else {
                        handleErrorResponse(response, responseBody)
                    }
                }
            }
        })

// Dentro de confirmRide
        val trip = Trip(
            date = getCurrentDate(),
            driverName = option.name,
            origin = origin,
            destination = destination,
            distance = distance,
            duration = tvDuration.text.toString().trim(),
            value = option.value
        )
        // Salva a viagem no local storage
        saveTripToLocalStorage(trip)

    }
    // Função para salvar a viagem no armazenamento local
    private fun saveTripToLocalStorage(trip: Trip) {
        try {
            val sharedPrefs = getSharedPreferences("trip_history", MODE_PRIVATE)
            val editor = sharedPrefs.edit()

            val tripsJson = sharedPrefs.getString("trips", "[]") ?: "[]"
            val tripsArray = JSONArray(tripsJson)

            val tripJson = JSONObject().apply {
                put("date", trip.date)
                put("driverName", trip.driverName)
                put("origin", trip.origin)
                put("destination", trip.destination)
                put("distance", trip.distance)
                put("duration", trip.duration)
                put("value", trip.value)
            }

            tripsArray.put(tripJson)
            editor.putString("trips", tripsArray.toString())
            editor.apply()

            Log.d("SAVE_TRIP", "Viagem salva com sucesso: ${tripJson}")


        } catch (e: Exception) {
            Log.e("SAVE_TRIP_ERROR", "Erro ao salvar a viagem: ${e.message}")
            Toast.makeText(this, "Erro ao carregar histórico local.", Toast.LENGTH_SHORT).show()
        }
    }

    // Função para obter a data atual no formato "dd/MM/yyyy HH:mm"
    private fun getCurrentDate(): String {
        return SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date())
    }
    private fun handleErrorResponse(response: Response, responseBody: String?) {
        try {
            val errorResponse = JSONObject(responseBody ?: "{}")
            val errorCode = errorResponse.optString("error_code")
            val errorDescription = errorResponse.optString("error_description")

            when (errorCode) {
                "INVALID_DISTANCE" -> showToast("Distância inválida para o motorista.")
                "DRIVER_NOT_FOUND" -> showToast("Motorista não encontrado.")
                "INVALID_DATA" -> showToast("Dados inválidos: $errorDescription")
                else -> showToast("Erro desconhecido: $errorDescription")
            }
        } catch (e: Exception) {
            showToast("Erro ao processar a resposta de erro: ${e.message}")
        }
    }
    private fun calculateDistance(origin: LatLng, destination: LatLng): Double {
        val results = FloatArray(1)
        android.location.Location.distanceBetween(
            origin.latitude, origin.longitude,
            destination.latitude, destination.longitude,
            results
        )
        return results[0] / 1000.0 // Converte para quilômetros
    }
    private fun calculateDistancesAndDurations(callback: (List<Pair<SimulatedPlace, PlaceInfo>>) -> Unit) {
        val apiKey = "AIzaSyD7ODMWrn_tHKFeYmyNAV_1wpnKVpkdL9Q"
        val placeInfoList = mutableListOf<Pair<SimulatedPlace, PlaceInfo>>()

        // Simula a lista de lugares
        val places = listOf(
            SimulatedPlace("Av. Pres. Kenedy, 2385 - Remédios, Osasco - SP, 02675-031", LatLng(-23.532881, -46.792759)),
            SimulatedPlace("Av. Thomas Edison, 365 - Barra Funda, São Paulo - SP, 01140-000", LatLng(-23.525440, -46.664399)),
            SimulatedPlace("Av. Brasil, 2033 - Jardim America, São Paulo - SP, 01431-001", LatLng(-23.567982, -46.683396)),
            SimulatedPlace("Av. Paulista, 1538 - Bela Vista, São Paulo - SP, 01310-200", LatLng(-23.561706, -46.655980))
        )

        places.forEach { place ->
            val origin = "${place.latLng.latitude},${place.latLng.longitude}"

            places.filter { it != place }.forEach { destinationPlace ->
                val destination = "${destinationPlace.latLng.latitude},${destinationPlace.latLng.longitude}"

                val url = "https://maps.googleapis.com/maps/api/directions/json?" +
                        "origin=$origin&destination=$destination&key=$apiKey"

                val request = Request.Builder().url(url).build()
                OkHttpClient().newCall(request).enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        Log.e("DIRECTIONS_ERROR", "Erro ao buscar rota: ${e.message}")
                    }

                    override fun onResponse(call: Call, response: Response) {
                        val responseBody = response.body?.string()
                        if (response.isSuccessful && responseBody != null) {
                            val jsonResponse = JSONObject(responseBody)
                            val legs = jsonResponse
                                .optJSONArray("routes")
                                ?.optJSONObject(0)
                                ?.optJSONArray("legs")
                                ?.optJSONObject(0)

                            if (legs != null) {
                                val distance = legs.optJSONObject("distance")?.optString("text") ?: "N/A"
                                val duration = legs.optJSONObject("duration")?.optString("text") ?: "N/A"

                                placeInfoList.add(
                                    Pair(
                                        place,
                                        PlaceInfo(destinationPlace.name, distance, duration)
                                    )
                                )

                                // Callback quando todos os pares são processados
                                if (placeInfoList.size == places.size * (places.size - 1)) {
                                    runOnUiThread { callback(placeInfoList) }
                                }
                            }
                        } else {
                            Log.e("DIRECTIONS_API", "Erro na API de Direções: ${response.message}")
                        }
                    }
                })
            }
        }
    }

    private fun displayDistancesAndDurations() {
        calculateDistancesAndDurations { placeInfoList ->
            placeInfoList.forEach { (place, info) ->
                Log.d(
                    "PLACE_INFO",
                    "De: ${place.name} Para: ${info.destination}, Distância: ${info.distance}, Duração: ${info.duration}"
                )
            }

            // Aqui você pode atualizar a UI, criar uma lista ou mostrar os dados no RecyclerView.
            Toast.makeText(this, "Distâncias calculadas com sucesso!", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun determineScenarioFromApi(originText: String, destinationText: String, callback: (List<TripOption>) -> Unit) {
        if (customerId.isEmpty() || originText.isEmpty() || destinationText.isEmpty()) {
            showToast("Preencha todos os campos.")
            return
        }

        // Verifica cache primeiro
        val cacheKey = "${originText}_${destinationText}"
        tripOptionsCache[cacheKey]?.let {
            callback(it)
            return
        }

        val requestBody = JSONObject().apply {
            put("customer_id", customerId)
            put("origin", originText)
            put("destination", destinationText)
        }.toString()

        val body = requestBody.toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("https://xd5zl5kk2yltomvw5fb37y3bm40vsyrx.lambda-url.sa-east-1.on.aws/ride/estimate")
            .post(body)
            .build()

        // Implementa retry pattern
        var retryCount = 0
        val maxRetries = 3

        fun makeRequest() {
            OkHttpClient().newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (retryCount < maxRetries) {
                        retryCount++
                        Log.d("API_RETRY", "Tentativa $retryCount de $maxRetries")
                        makeRequest()
                    } else {
                        runOnUiThread {
                            showToast("Erro ao conectar-se à API após $maxRetries tentativas")
                        }
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    val responseBody = response.body?.string()
                    if (response.isSuccessful && responseBody != null) {
                        try {
                            val jsonResponse = JSONObject(responseBody)
                            val optionsJsonArray = jsonResponse.optJSONArray("options")
                            val tripOptions = mutableListOf<TripOption>()

                            if (optionsJsonArray != null) {
                                for (i in 0 until optionsJsonArray.length()) {
                                    val optionJson = optionsJsonArray.getJSONObject(i)
                                    tripOptions.add(
                                        TripOption(
                                            id = optionJson.getInt("id"),
                                            name = optionJson.getString("name"),
                                            description = optionJson.getString("description"),
                                            vehicle = optionJson.getString("vehicle"),
                                            rating = optionJson.getJSONObject("review").getDouble("rating"),
                                            comment = optionJson.getJSONObject("review").getString("comment"),
                                            value = optionJson.getDouble("value")
                                        )
                                    )
                                }
                            }

                            // Salva no cache
                            tripOptionsCache[cacheKey] = tripOptions

                            runOnUiThread {
                                callback(tripOptions)
                            }
                        } catch (e: Exception) {
                            if (retryCount < maxRetries) {
                                retryCount++
                                Log.d("API_RETRY", "Tentativa $retryCount de $maxRetries")
                                makeRequest()
                            } else {
                                runOnUiThread {
                                    showToast("Erro ao processar a resposta da API")
                                }
                            }
                        }
                    } else {
                        if (retryCount < maxRetries) {
                            retryCount++
                            Log.d("API_RETRY", "Tentativa $retryCount de $maxRetries")
                            makeRequest()
                        } else {
                            runOnUiThread {
                                showToast("Erro na API: ${response.message}")
                            }
                        }
                    }
                }
            })
        }

        makeRequest()
    }

    // Classes de dados
    data class PlaceInfo(val destination: String, val distance: String, val duration: String)
    data class SimulatedPlace(val name: String, val latLng: LatLng)
    data class TripOption(
        val id: Int,
        val name: String,
        val description: String,
        val vehicle: String,
        val rating: Double,
        val comment: String,
        val value: Double
    )
    data class Trip(
        val date: String,
        val driverName: String,
        val origin: String,
        val destination: String,
        val distance: Double,
        val duration: String,
        val value: Double
    )
}