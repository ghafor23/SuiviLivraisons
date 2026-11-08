package com.fixly.suivilivraison

import android.os.Bundle
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.BarcodeEncoder
import android.graphics.Color
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge

class ProfileActivity : AppCompatActivity() {

    // Données de simulation : change-les avec tes infos
    private val lastName = "blghman"
    private val firstName = "abdelghafour"
    private val age = 19
    private val license = "B"
    private val courierId = "LIV-121"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )
        setContentView(R.layout.activity_profile)

        findViewById<TextView>(R.id.tvLastName).text =
            "${getString(R.string.last_name)} : $lastName"
        findViewById<TextView>(R.id.tvFirstName).text =
            "${getString(R.string.first_name)} : $firstName"
        findViewById<TextView>(R.id.tvAge).text =
            "${getString(R.string.age)} : $age"
        findViewById<TextView>(R.id.tvLicense).text =
            "${getString(R.string.license_type)} : $license"
        findViewById<TextView>(R.id.tvCourierId).text =
            "${getString(R.string.courier_id)} : $courierId"

        // Contenu du QR Code = infos d'identification
        val qrContent = "ID:$courierId;Nom:$lastName;Prenom:$firstName;Age:$age;Permis:$license"
        val bitmap = BarcodeEncoder().encodeBitmap(qrContent, BarcodeFormat.QR_CODE, 600, 600)
        findViewById<ImageView>(R.id.ivQr).setImageBitmap(bitmap)
    }
}