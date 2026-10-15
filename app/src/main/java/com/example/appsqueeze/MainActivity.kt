package com.example.appsqueeze

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.example.appsqueeze.data.LlmManager
import com.example.appsqueeze.data.PreguntasRepository
import com.example.appsqueeze.service.SqueezeAccessibilityService
import com.example.appsqueeze.ui.screen.QuizScreen
import com.example.appsqueeze.ui.theme.AppSqueezeTheme
import com.example.appsqueeze.ui.viewmodel.QuizViewModel
import com.example.appsqueeze.ui.viewmodel.QuizViewModelFactory

class MainActivity : ComponentActivity() {

    private val viewModel: QuizViewModel by viewModels {
        val repository = PreguntasRepository(applicationContext)
        val llmManager = LlmManager.getInstance(applicationContext)
        QuizViewModelFactory(repository, llmManager)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        verificarPermisoSobreposicion()

        setContent {
            AppSqueezeTheme {
                val uiState by viewModel.uiState.collectAsState()

                // Evita que el usuario use el gesto de retroceso para evadir el bloqueo
                BackHandler(enabled = !SqueezeAccessibilityService.estaDesbloqueado) {
                    Toast.makeText(
                        this@MainActivity,
                        "Bloqueo activo: Responde 3 preguntas correctas para liberar el dispositivo.",
                        Toast.LENGTH_SHORT
                    ).show()
                }

                QuizScreen(
                    uiState = uiState,
                    onOpcionSeleccionada = { indice ->
                        viewModel.onOpcionSeleccionada(indice)
                    },
                    onDesbloqueoConfirmado = {
                        SqueezeAccessibilityService.estaDesbloqueado = true
                        Toast.makeText(
                            this@MainActivity,
                            "¡Pantalla liberada! Puedes continuar con tus aplicaciones.",
                            Toast.LENGTH_SHORT
                        ).show()
                        finish()
                    },
                    onReintentar = {
                        viewModel.reintentar()
                    }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (SqueezeAccessibilityService.estaDesbloqueado) {
            finish()
        }
    }

    private fun verificarPermisoSobreposicion() {
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }
    }
}