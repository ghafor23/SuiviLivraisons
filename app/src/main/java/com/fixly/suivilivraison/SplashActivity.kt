package com.fixly.suivilivraison
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.Button
import android.widget.TextView
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat

class SplashActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Thème sombre pour toute l'application (dialogues compris)
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )
        setContentView(R.layout.activity_splash)

        // « Suivi » en blanc, « Livraisons » en bleu
        val text = getString(R.string.app_name)
        val spannable = SpannableString(text)
        val space = text.indexOf(' ')
        if (space > 0) {
            spannable.setSpan(
                ForegroundColorSpan(ContextCompat.getColor(this, R.color.neon_blue)),
                space + 1, text.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
        findViewById<TextView>(R.id.tvTitle).text = spannable

        // Apparition en cascade : logo, titre, slogan, bouton
        val views = listOf<View>(
            findViewById(R.id.logoContainer),
            findViewById(R.id.tvTitle),
            findViewById(R.id.tvTagline),
            findViewById(R.id.btnStartApp)
        )
        views.forEachIndexed { i, v ->
            v.alpha = 0f
            v.translationY = 40f
            v.animate()
                .alpha(1f)
                .translationY(0f)
                .setStartDelay(120L + i * 150L)
                .setDuration(600L)
                .start()
        }

        // Les anneaux tournent lentement autour du logo
        ObjectAnimator.ofFloat(findViewById<View>(R.id.ivRings), View.ROTATION, 0f, 360f).apply {
            duration = 25000L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            start()
        }

        findViewById<Button>(R.id.btnStartApp).setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }
    }
}