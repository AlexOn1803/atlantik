package ua.madeisy.atlantic

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ua.madeisy.atlantic.ui.AppViewModel
import ua.madeisy.atlantic.ui.AtlanticTheme
import ua.madeisy.atlantic.ui.DevicesScreen
import ua.madeisy.atlantic.ui.HeaterScreen
import ua.madeisy.atlantic.ui.LoginScreen

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AtlanticTheme {
                Surface(Modifier.fillMaxSize()) { Root(vm) }
            }
        }
    }
}

@Composable
private fun Root(vm: AppViewModel) {
    val st by vm.state.collectAsStateWithLifecycle()
    when {
        st.checkingSaved -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        !st.loggedIn -> LoginScreen(st, vm)
        st.selected != null -> {
            BackHandler(enabled = st.allDevices.size > 1) { vm.closeDevice() }
            HeaterScreen(st, vm)
        }
        else -> DevicesScreen(st, vm)
    }
}
