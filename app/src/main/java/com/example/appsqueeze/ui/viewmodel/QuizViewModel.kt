package com.example.appsqueeze.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.appsqueeze.data.LlmManager
import com.example.appsqueeze.data.LlmStatus
import com.example.appsqueeze.data.PreguntaOpcionMultiple
import com.example.appsqueeze.data.PreguntasRepository
import com.example.appsqueeze.service.SqueezeAccessibilityService
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface QuizUiState {
    data class Loading(
        val titulo: String = "Generando con IA Local Qwen",
        val detalle: String = "Qwen 2.5 está redactando una pregunta técnica inédita...",
        val progreso: Float? = null
    ) : QuizUiState

    data class Active(
        val pregunta: PreguntaOpcionMultiple,
        val aciertosActuales: Int,
        val metaAciertos: Int = 3,
        val opcionSeleccionada: Int? = null,
        val esRespuestaCorrecta: Boolean? = null,
        val bloqueandoSeleccion: Boolean = false,
        val statusAiBadge: String = "Qwen 2.5 Operativa"
    ) : QuizUiState

    data class Unlocked(
        val totalAciertos: Int = 3,
        val mensaje: String = "¡3 Aciertos completados! Teléfono liberado con éxito."
    ) : QuizUiState

    data class Error(
        val mensaje: String,
        val detalle: String? = null
    ) : QuizUiState
}

class QuizViewModel(
    private val repository: PreguntasRepository,
    private val llmManager: LlmManager
) : ViewModel() {

    private val _uiState = MutableStateFlow<QuizUiState>(
        QuizUiState.Loading(
            titulo = "Iniciando AppSqueeze (llama.cpp)",
            detalle = "Cargando modelo Qwen 2.5 0.5B...",
            progreso = null
        )
    )
    val uiState: StateFlow<QuizUiState> = _uiState.asStateFlow()

    private var estadoAiActualBadge: String = "IA: Qwen 2.5 0.5B"

    private val temasTecnicos = LlmManager.CATEGORIAS_TECNICAS

    init {
        observarEstadoAi()
        cargarSiguientePregunta()
    }

    private fun observarEstadoAi() {
        viewModelScope.launch {
            llmManager.llmStatus.collect { status ->
                estadoAiActualBadge = when (status) {
                    is LlmStatus.Idle -> "IA: En espera"
                    is LlmStatus.PreparingModel -> "IA: Copiando Qwen (${(status.progreso * 100).toInt()}%)"
                    is LlmStatus.InitializingEngine -> "IA: Inicializando llama.cpp..."
                    is LlmStatus.Ready -> "IA: Qwen 2.5 0.5B Lista"
                    is LlmStatus.Generating -> "IA: Generando (${status.tema.take(16)}...)"
                    is LlmStatus.Error -> "IA: Error en inferencia"
                }

                val state = _uiState.value
                when (state) {
                    is QuizUiState.Active -> {
                        _uiState.value = state.copy(statusAiBadge = estadoAiActualBadge)
                    }
                    is QuizUiState.Loading -> {
                        if (status is LlmStatus.PreparingModel) {
                            _uiState.value = state.copy(
                                titulo = "Preparando Modelo Qwen GGUF",
                                detalle = status.mensaje,
                                progreso = status.progreso
                            )
                        } else if (status is LlmStatus.InitializingEngine) {
                            _uiState.value = state.copy(
                                titulo = "Cargando llama.cpp en Memoria",
                                detalle = "Inicializando modelo ultraligero Qwen 2.5 (491 MB)...",
                                progreso = null
                            )
                        } else if (status is LlmStatus.Generating) {
                            _uiState.value = state.copy(
                                titulo = "Generando con IA Local Qwen",
                                detalle = "Qwen 2.5 redactando pregunta técnica (${status.tema})...",
                                progreso = null
                            )
                        }
                    }
                    else -> {}
                }
            }
        }
    }

    fun cargarSiguientePregunta() {
        // 1. Revisar si hay preguntas en la caché de IA
        val preguntaEnCache = repository.obtenerPreguntaDeCache()
        if (preguntaEnCache != null) {
            _uiState.value = QuizUiState.Active(
                pregunta = preguntaEnCache,
                aciertosActuales = repository.obtenerAciertosActuales(),
                metaAciertos = repository.metaAciertos,
                statusAiBadge = estadoAiActualBadge
            )
            llmManager.iniciarGeneracionEnSegundoPlano(repository, metaEnCache = 5)
            return
        }

        // 2. Si no hay preguntas en caché, mostrar Loading y generar con llama.cpp
        _uiState.value = QuizUiState.Loading(
            titulo = "Generando con IA Local Qwen",
            detalle = "Qwen 2.5 está redactando una pregunta técnica inédita...",
            progreso = null
        )

        viewModelScope.launch {
            if (!llmManager.isReady()) {
                llmManager.initialize()
            }

            var nuevaPregunta: PreguntaOpcionMultiple? = null
            var intentos = 0

            // Mantener estado Loading mientras se espera el turno de inferencia
            while (intentos < 5 && nuevaPregunta == null) {
                // Verificar si durante la espera la tarea en background ya dejó una pregunta lista en caché
                val enCache = repository.obtenerPreguntaDeCache()
                if (enCache != null) {
                    nuevaPregunta = enCache
                    break
                }

                val tema = temasTecnicos.random()
                nuevaPregunta = llmManager.generarPregunta(tema)

                if (nuevaPregunta == null) {
                    intentos++
                    // Si el motor estaba ocupado con otra solicitud, esperar pacíficamente
                    delay(1000)
                }
            }

            if (nuevaPregunta != null) {
                val preguntaBarajada = repository.aleatorizarOpciones(nuevaPregunta)
                _uiState.value = QuizUiState.Active(
                    pregunta = preguntaBarajada,
                    aciertosActuales = repository.obtenerAciertosActuales(),
                    metaAciertos = repository.metaAciertos,
                    opcionSeleccionada = null,
                    esRespuestaCorrecta = null,
                    bloqueandoSeleccion = false,
                    statusAiBadge = estadoAiActualBadge
                )
                // Continuar generando en background para las siguientes preguntas
                llmManager.iniciarGeneracionEnSegundoPlano(repository, metaEnCache = 5)
            } else {
                _uiState.value = QuizUiState.Error(
                    mensaje = "El motor de IA está procesando solicitudes.",
                    detalle = "Pulsa reintentar para cargar la siguiente pregunta."
                )
            }
        }
    }

    fun onOpcionSeleccionada(indice: Int) {
        val currentState = _uiState.value as? QuizUiState.Active ?: return
        if (currentState.bloqueandoSeleccion) return

        val esCorrecta = repository.responderPregunta(currentState.pregunta, indice)
        val aciertos = repository.obtenerAciertosActuales()

        // Mostrar retroalimentación visual inmediata (verde o rojo) y bloquear pulsaciones repetidas
        _uiState.value = currentState.copy(
            opcionSeleccionada = indice,
            esRespuestaCorrecta = esCorrecta,
            aciertosActuales = aciertos,
            bloqueandoSeleccion = true
        )

        viewModelScope.launch {
            delay(850)

            if (repository.estaDesbloqueado()) {
                SqueezeAccessibilityService.estaDesbloqueado = true
                _uiState.value = QuizUiState.Unlocked(
                    totalAciertos = aciertos,
                    mensaje = "¡Felicidades! Lograste los 3 aciertos requeridos con Qwen 2.5 (llama.cpp)."
                )
            } else {
                cargarSiguientePregunta()
            }
        }
    }

    fun reintentar() {
        repository.reiniciarProgreso()
        cargarSiguientePregunta()
    }
}

class QuizViewModelFactory(
    private val repository: PreguntasRepository,
    private val llmManager: LlmManager
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(QuizViewModel::class.java)) {
            return QuizViewModel(repository, llmManager) as T
        }
        throw IllegalArgumentException("Clase ViewModel desconocida: ${modelClass.name}")
    }
}
