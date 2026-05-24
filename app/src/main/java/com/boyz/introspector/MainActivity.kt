package com.boyz.introspector

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.boyz.introspector.ui.navigation.NavGraph
import com.boyz.introspector.ui.theme.IntrospectorTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            IntrospectorTheme {
                NavGraph()
            }
        }
    }
}
