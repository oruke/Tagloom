package com.oruke.tagloom

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.oruke.tagloom.capability.createTagloomCapabilities

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        setContent {
            App(capabilities = createTagloomCapabilities())
        }
    }
}

@Preview
@Composable
fun AppAndroidPreview() {
    App()
}
