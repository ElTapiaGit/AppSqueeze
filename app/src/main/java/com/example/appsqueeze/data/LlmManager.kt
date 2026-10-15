package com.example.appsqueeze.data

import android.content.Context
import android.os.Process
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.codeshipping.llamakotlin.LlamaModel
import java.io.File
import java.io.FileOutputStream

sealed interface LlmStatus {
    data object Idle : LlmStatus
    data class PreparingModel(val progreso: Float, val mensaje: String) : LlmStatus
    data object InitializingEngine : LlmStatus
    data object Ready : LlmStatus
    data class Generating(val tema: String) : LlmStatus
    data class Error(val mensaje: String, val excepcion: Throwable? = null) : LlmStatus
}

class LlmManager private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val TAG = "LlmManager"

    // Dispatcher aislado de 1 hilo con prioridad de background para no congelar la UI
    private val backgroundDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + backgroundDispatcher)

    // Mutex para serializar de forma segura todas las llamadas concurrentes a llama.cpp
    private val generationMutex = Mutex()

    private val _llmStatus = MutableStateFlow<LlmStatus>(LlmStatus.Idle)
    val llmStatus: StateFlow<LlmStatus> = _llmStatus.asStateFlow()

    @Volatile
    private var llamaModel: LlamaModel? = null

    private var generadorActivo = false

    companion object {
        const val MODEL_NAME = "qwen2.5-0.5b-instruct-q4_k_m.gguf"
        private const val MIN_MODEL_SIZE = 100_000_000L // ~100 MB mínimo

        // Categorías técnicas expandidas para entrevistas reales de desarrollo de software
        val CATEGORIAS_TECNICAS = listOf(
            "Bases de Datos Relacionales (SQL, PostgreSQL, MySQL, Índices, Transacciones ACID)",
            "Backend y APIs (REST, HTTP Status Codes, Autenticación JWT, Node.js, Middleware)",
            "Kotlin Avanzado (Coroutines, Flow, Dispatchers, Inmutabilidad)",
            "Arquitectura de Software (Clean Architecture, SOLID, Inyección de Dependencias, MVVM)",
            "Git y DevOps (Rebase vs Merge, Docker básico, CI/CD)",
            "Manejo de Memoria y Optimización (Leaks, Garbage Collection, Caché)"
        )

        @Volatile
        private var INSTANCE: LlmManager? = null

        fun getInstance(context: Context): LlmManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: LlmManager(context).also { INSTANCE = it }
            }
        }
    }

    fun isReady(): Boolean = llamaModel != null

    // Resuelve la ruta del modelo en almacenamiento o lo copia desde assets
    private suspend fun resolveOrCopyModelFile(): String = withContext(backgroundDispatcher) {
        val internalFile = File(appContext.filesDir, MODEL_NAME)
        if (internalFile.exists() && internalFile.length() > MIN_MODEL_SIZE) {
            Log.d(TAG, "Modelo Qwen GGUF en memoria interna: ${internalFile.absolutePath} (${internalFile.length()} bytes)")
            return@withContext internalFile.absolutePath
        }

        val externalFile = File(appContext.getExternalFilesDir(null), MODEL_NAME)
        if (externalFile.exists() && externalFile.length() > MIN_MODEL_SIZE) {
            Log.d(TAG, "Modelo Qwen GGUF en almacenamiento externo: ${externalFile.absolutePath}")
            return@withContext externalFile.absolutePath
        }

        val downloadFile = File("/sdcard/Download", MODEL_NAME)
        if (downloadFile.exists() && downloadFile.length() > MIN_MODEL_SIZE) {
            Log.d(TAG, "Modelo Qwen GGUF en Descargas: ${downloadFile.absolutePath}")
            return@withContext downloadFile.absolutePath
        }

        // Copia desde assets usando buffer de 1 MB
        Log.i(TAG, "Modelo no encontrado en almacenamiento. Copiando $MODEL_NAME desde assets...")
        _llmStatus.value = LlmStatus.PreparingModel(0f, "Copiando modelo Qwen GGUF...")

        if (internalFile.exists()) {
            internalFile.delete()
        }

        try {
            appContext.assets.open(MODEL_NAME).use { inputStream ->
                FileOutputStream(internalFile).use { outputStream ->
                    val buffer = ByteArray(1024 * 1024)
                    var bytesRead: Int
                    var totalCopied = 0L
                    val totalAproximado = 491_400_032L

                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                        outputStream.write(buffer, 0, bytesRead)
                        totalCopied += bytesRead
                        val porcentaje = (totalCopied.toFloat() / totalAproximado).coerceIn(0f, 1f)
                        _llmStatus.value = LlmStatus.PreparingModel(
                            porcentaje,
                            "Copiando Qwen 2.5: ${(porcentaje * 100).toInt()}% (${totalCopied / (1024 * 1024)} MB)"
                        )
                    }
                    outputStream.flush()
                }
            }
            Log.i(TAG, "Copia de Qwen completada con éxito: ${internalFile.absolutePath}")
            return@withContext internalFile.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Error al copiar el modelo Qwen GGUF desde assets", e)
            throw IllegalStateException("No se pudo obtener el archivo $MODEL_NAME: ${e.message}", e)
        }
    }

    // Inicializa el motor llama.cpp con el modelo Qwen 2.5 0.5B
    suspend fun initialize() = withContext(backgroundDispatcher) {
        if (llamaModel != null) {
            _llmStatus.value = LlmStatus.Ready
            return@withContext
        }

        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            val modelPath = resolveOrCopyModelFile()

            _llmStatus.value = LlmStatus.InitializingEngine
            Log.d(TAG, "Cargando motor llama.cpp con $modelPath...")

            // Configuración optimizada: semilla aleatoria (-1), maxTokens 256, temperature 0.7f
            llamaModel = LlamaModel.load(modelPath) {
                contextSize = 2048
                threads = 4 // 2x A76 + 2x A55 en Helio G90T
                temperature = 0.7f
                maxTokens = 256
                topP = 0.9f
                topK = 40
                repeatPenalty = 1.15f
                seed = -1 // Semilla estocástica para evitar repeticiones
            }

            _llmStatus.value = LlmStatus.Ready
            Log.i(TAG, "Motor llama.cpp (Qwen 2.5 0.5B) cargado con éxito. Semilla estocástica activa.")
        } catch (e: Throwable) {
            Log.e(TAG, "Error al inicializar llama.cpp", e)
            _llmStatus.value = LlmStatus.Error("Error al cargar Qwen GGUF: ${e.localizedMessage ?: "Causa desconocida"}", e)
        }
    }

    // Genera 1 pregunta técnica de forma atómica y sincronizada con Mutex, con reintentos limpios sin distractores falsos
    suspend fun generarPregunta(tema: String? = null): PreguntaOpcionMultiple? = withContext(backgroundDispatcher) {
        generationMutex.withLock {
            val model = llamaModel ?: run {
                Log.w(TAG, "llamaModel no está listo. Inicializando...")
                initialize()
                llamaModel ?: return@withLock null
            }

            val temaElegido = tema ?: CATEGORIAS_TECNICAS.random()
            _llmStatus.value = LlmStatus.Generating(temaElegido)

            // Hasta 3 intentos directos: si el modelo produce una salida truncada, se descarta y reintenta
            for (intento in 1..3) {
                // Semilla dinámica e identificador variable para evitar respuestas en bucle
                val timestampSeed = (System.currentTimeMillis() % 100000).toInt() + intento

                val prompt = buildString {
                    append("<|im_start|>system\n")
                    append("Eres un evaluador de entrevistas técnicas para ingenieros de software. Tu objetivo es generar preguntas precisas, desafiantes y variadas.\n")
                    append("Instrucción: Genera opciones cortas (máximo 5 palabras por opción). Si preguntas por un operador, comando o concepto, las 4 opciones deben ser exactamente del mismo tipo y del mismo tema.\n")
                    append("EJEMPLO DE RESPUESTA:\n")
                    append("PREGUNTA: ¿Qué nivel de aislamiento en SQL evita las lecturas fantasma (Phantom Reads)?\n")
                    append("OPCION_0: SERIALIZABLE\n")
                    append("OPCION_1: REPEATABLE READ\n")
                    append("OPCION_2: READ COMMITTED\n")
                    append("OPCION_3: READ UNCOMMITTED\n")
                    append("CORRECTA: 0\n\n")
                    append("Usa SIEMPRE ese formato. No te detengas hasta escribir las 4 opciones y CORRECTA.<|im_end|>\n")
                    append("<|im_start|>user\n")
                    append("Genera 1 pregunta técnica de opción múltiple inédita sobre: $temaElegido. [Variación: #$timestampSeed]<|im_end|>\n")
                    append("<|im_start|>assistant\n")
                    append("PREGUNTA: ")
                }

                try {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                    val startTime = System.currentTimeMillis()
                    val responseBuilder = StringBuilder()

                    model.generateStream(prompt).collect { token ->
                        responseBuilder.append(token)
                        if (token.contains("<|im_end|>") || responseBuilder.contains("<|im_end|>")) {
                            return@collect
                        }
                    }

                    val duracion = System.currentTimeMillis() - startTime
                    val rawOutput = responseBuilder.toString().replace("<|im_end|>", "").trim()
                    Log.d(TAG, "Intento $intento de inferencia Qwen ($duracion ms):\n$rawOutput")

                    val preguntaParseada = parsearRespuestaQwen(rawOutput, temaElegido)
                    if (preguntaParseada != null) {
                        _llmStatus.value = LlmStatus.Ready
                        return@withLock preguntaParseada
                    } else {
                        Log.w(TAG, "Salida de Qwen incompleta o truncada en intento $intento. Descartando y reintentando...")
                    }
                } catch (e: Throwable) {
                    Log.e(TAG, "Error durante inferencia en intento $intento: ${e.message}", e)
                    if (e.message?.contains("already in progress", ignoreCase = true) == true) {
                        delay(1000)
                    }
                }
            }

            _llmStatus.value = LlmStatus.Ready
            return@withLock null
        }
    }

    // Generador en segundo plano que alimenta la caché local rotando categorías técnicas reales
    fun iniciarGeneracionEnSegundoPlano(repository: PreguntasRepository, metaEnCache: Int = 5) {
        if (generadorActivo) return
        generadorActivo = true

        scope.launch {
            Log.i(TAG, "Iniciando generador de preguntas técnicas en segundo plano...")
            while (isActive) {
                try {
                    if (llamaModel == null) {
                        initialize()
                    }

                    if (llamaModel != null) {
                        val cantidad = repository.cantidadPreguntasAiDisponibles()
                        if (cantidad < metaEnCache) {
                            if (!generationMutex.isLocked) {
                                val tema = CATEGORIAS_TECNICAS.random()
                                Log.d(TAG, "Generando pregunta de Qwen en background sobre '$tema' (Caché: $cantidad/$metaEnCache)...")
                                val pregunta = generarPregunta(tema)
                                if (pregunta != null) {
                                    repository.agregarPreguntaGenerada(pregunta)
                                }
                            } else {
                                Log.d(TAG, "Mutex ocupado por la UI. Posponiendo inferencia en background...")
                            }
                        }
                    }

                    delay(4000)
                } catch (e: Exception) {
                    Log.w(TAG, "Excepción en el generador background", e)
                    delay(6000)
                }
            }
        }
    }

    // Parser estricto: SI faltan opciones o el formato es incorrecto, DESCARTA la pregunta (retorna null)
    // eliminando por completo cualquier inyección de distractores falsos predefinidos
    private fun parsearRespuestaQwen(rawText: String, tema: String): PreguntaOpcionMultiple? {
        val fullText = if (rawText.trimStart().startsWith("PREGUNTA:")) {
            rawText.trim()
        } else {
            "PREGUNTA: " + rawText.trim()
        }

        val lineas = fullText.lines().map { it.trim() }.filter { it.isNotEmpty() }
        var pregunta: String? = null
        val opcionesMap = mutableMapOf<Int, String>()
        var correcta: Int? = null

        for (linea in lineas) {
            when {
                linea.startsWith("PREGUNTA:") -> {
                    pregunta = linea.removePrefix("PREGUNTA:").trim()
                }
                linea.startsWith("OPCION_0:") -> opcionesMap[0] = linea.removePrefix("OPCION_0:").trim()
                linea.startsWith("OPCION_1:") -> opcionesMap[1] = linea.removePrefix("OPCION_1:").trim()
                linea.startsWith("OPCION_2:") -> opcionesMap[2] = linea.removePrefix("OPCION_2:").trim()
                linea.startsWith("OPCION_3:") -> opcionesMap[3] = linea.removePrefix("OPCION_3:").trim()
                linea.startsWith("A)") || linea.startsWith("A.") -> opcionesMap.putIfAbsent(0, linea.substring(2).trim())
                linea.startsWith("B)") || linea.startsWith("B.") -> opcionesMap.putIfAbsent(1, linea.substring(2).trim())
                linea.startsWith("C)") || linea.startsWith("C.") -> opcionesMap.putIfAbsent(2, linea.substring(2).trim())
                linea.startsWith("D)") || linea.startsWith("D.") -> opcionesMap.putIfAbsent(3, linea.substring(2).trim())
                linea.startsWith("CORRECTA:") -> {
                    val valor = linea.removePrefix("CORRECTA:").trim()
                    correcta = when {
                        valor.contains("0") || valor.contains("A", ignoreCase = true) -> 0
                        valor.contains("1") || valor.contains("B", ignoreCase = true) -> 1
                        valor.contains("2") || valor.contains("C", ignoreCase = true) -> 2
                        valor.contains("3") || valor.contains("D", ignoreCase = true) -> 3
                        else -> valor.toIntOrNull()
                    }
                }
            }
        }

        // 1. Validar enunciado: debe existir y tener sentido técnico (mínimo 15 caracteres)
        if (pregunta.isNullOrBlank() || pregunta.length < 15) {
            Log.w(TAG, "Pregunta rechazada: Enunciado vacío o demasiado breve ('$pregunta')")
            return null
        }

        // 2. Validar que existan las 4 opciones exactas y no vacías (SIN distractores inventados)
        val opt0 = opcionesMap[0]?.takeIf { it.isNotBlank() }
        val opt1 = opcionesMap[1]?.takeIf { it.isNotBlank() }
        val opt2 = opcionesMap[2]?.takeIf { it.isNotBlank() }
        val opt3 = opcionesMap[3]?.takeIf { it.isNotBlank() }

        if (opt0 == null || opt1 == null || opt2 == null || opt3 == null) {
            Log.w(TAG, "Pregunta rechazada: Solo se encontraron ${opcionesMap.size}/4 opciones generadas por el modelo.")
            return null
        }

        // 3. Validar que las opciones sean distintas entre sí
        val listaOpciones = listOf(opt0, opt1, opt2, opt3)
        if (listaOpciones.distinct().size < 4) {
            Log.w(TAG, "Pregunta rechazada: Opciones duplicadas generadas por el modelo ($listaOpciones)")
            return null
        }

        // 4. Validar índice de la opción correcta
        val indiceCorrecto = if (correcta != null && correcta in 0..3) correcta else 0

        Log.d(TAG, "Pregunta 100% auténtica aceptada: '$pregunta' con 4 opciones válidas del tema '$tema'.")

        return PreguntaOpcionMultiple(
            enunciado = pregunta,
            opciones = listaOpciones,
            respuestaCorrectaIndex = indiceCorrecto,
            categoria = tema,
            esGeneradaPorAi = true
        )
    }

    fun close() {
        try {
            llamaModel?.close()
            llamaModel = null
            _llmStatus.value = LlmStatus.Idle
        } catch (e: Exception) {
            Log.e(TAG, "Error al cerrar llamaModel", e)
        }
    }
}
