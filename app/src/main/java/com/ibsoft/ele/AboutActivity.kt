package com.ibsoft.ele

import android.os.Bundle
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.ibsoft.ele.databinding.ActivityAboutBinding
import android.text.Html


class AboutActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAboutBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAboutBinding.inflate(layoutInflater)
        setContentView(binding.root)
        val textViewLicense = findViewById<TextView>(R.id.textViewLicense)
        textViewLicense.text = Html.fromHtml(getString(R.string.license_text), Html.FROM_HTML_MODE_LEGACY)


    }
}
