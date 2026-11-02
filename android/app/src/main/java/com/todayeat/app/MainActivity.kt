package com.todayeat.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.mutableStateOf
import com.todayeat.app.ui.AppViewModel
import com.todayeat.app.ui.Root
import com.todayeat.app.ui.theme.TodayEatTheme
import com.todayeat.app.update.AppUpdate

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()
    private val openUpdate = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        openUpdate.value = intent?.getBooleanExtra(AppUpdate.EXTRA_OPEN_UPDATE, false) == true
        val app = application as TodayEatApp
        app.updates.start()
        setContent {
            TodayEatTheme {
                Root(vm, openUpdate.value)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openUpdate.value = intent.getBooleanExtra(AppUpdate.EXTRA_OPEN_UPDATE, false)
    }

    override fun onResume() {
        super.onResume()
        vm.tick()
        val app = application as TodayEatApp
        app.updates.onHostResume(this)
        app.sync.request(0)
    }
}
