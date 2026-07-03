package com.example.ppvstreamresolver

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.example.ppvstreamresolver.theme.PPVStreamResolverTheme
import com.example.ppvstreamresolver.LocalHttpProxy

class MainActivity : ComponentActivity() {
    private lateinit var proxy: LocalHttpProxy

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        proxy = LocalHttpProxy(3000)
        proxy.start()

        enableEdgeToEdge()
        setContent {
            PPVStreamResolverTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainNavigation()
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        proxy.stop()
    }
}
