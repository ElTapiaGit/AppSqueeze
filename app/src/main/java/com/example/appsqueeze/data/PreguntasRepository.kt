package com.example.appsqueeze.data

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Collections

class PreguntasRepository(private val context: Context) {

    private val TAG = "PreguntasRepository"
    private var aciertosTotales: Int = 0
    val metaAciertos: Int = 3

    // Cola de preguntas generadas 100% por Qwen IA en memoria (thread-safe)
    private val colaPreguntasAi = Collections.synchronizedList(mutableListOf<PreguntaOpcionMultiple>())

    // Historial de enunciados recientes para evitar agregar preguntas duplicadas (mantiene los últimos 150)
    private val historialEnunciados = Collections.synchronizedSet(LinkedHashSet<String>())

    init {
        cargarPreguntasPersistidas()
    }

    fun obtenerAciertosActuales(): Int = aciertosTotales
    fun estaDesbloqueado(): Boolean = aciertosTotales >= metaAciertos

    fun responderPregunta(preguntaActual: PreguntaOpcionMultiple, opcionSeleccionada: Int): Boolean {
        val esCorrecta = opcionSeleccionada == preguntaActual.respuestaCorrectaIndex
        if (esCorrecta) {
            aciertosTotales++
        }
        return esCorrecta
    }

    fun reiniciarProgreso() {
        aciertosTotales = 0
    }

    fun tienePreguntasEnCache(): Boolean {
        return synchronized(colaPreguntasAi) { colaPreguntasAi.isNotEmpty() }
    }

    fun cantidadPreguntasAiDisponibles(): Int {
        return synchronized(colaPreguntasAi) { colaPreguntasAi.size }
    }

    // Obtiene una pregunta de la caché si existe, barajando sus opciones al azar
    fun obtenerPreguntaDeCache(): PreguntaOpcionMultiple? {
        synchronized(colaPreguntasAi) {
            if (colaPreguntasAi.isNotEmpty()) {
                val preguntaOriginal = colaPreguntasAi.removeAt(0)
                guardarPreguntasPersistidas()
                val preguntaBarajada = aleatorizarOpciones(preguntaOriginal)
                Log.d(TAG, "Pregunta servida desde caché y barajada: '${preguntaBarajada.enunciado.take(45)}...' (Opción correcta en índice ${preguntaBarajada.respuestaCorrectaIndex})")
                return preguntaBarajada
            }
        }
        return null
    }

    // Aleatoriza las 4 opciones de la pregunta y recalcula el nuevo índice de la respuesta correcta
    fun aleatorizarOpciones(pregunta: PreguntaOpcionMultiple): PreguntaOpcionMultiple {
        if (pregunta.opciones.size < 2 || pregunta.respuestaCorrectaIndex !in pregunta.opciones.indices) {
            return pregunta
        }

        val textoRespuestaCorrecta = pregunta.opciones[pregunta.respuestaCorrectaIndex]
        val opcionesBarajadas = pregunta.opciones.shuffled()
        val nuevoIndiceCorrecto = opcionesBarajadas.indexOf(textoRespuestaCorrecta)

        return pregunta.copy(
            opciones = opcionesBarajadas,
            respuestaCorrectaIndex = if (nuevoIndiceCorrecto >= 0) nuevoIndiceCorrecto else 0,
            esGeneradaPorAi = true
        )
    }

    // Agrega la pregunta a la caché verificando previamente que no sea duplicada
    fun agregarPreguntaGenerada(pregunta: PreguntaOpcionMultiple): Boolean {
        val clave = normalizarEnunciado(pregunta.enunciado)

        synchronized(historialEnunciados) {
            if (historialEnunciados.contains(clave)) {
                Log.w(TAG, "Pregunta duplicada omitida: '${pregunta.enunciado.take(45)}...'")
                return false
            }

            if (historialEnunciados.size >= 150) {
                val iterador = historialEnunciados.iterator()
                if (iterador.hasNext()) {
                    iterador.next()
                    iterador.remove()
                }
            }
            historialEnunciados.add(clave)
        }

        synchronized(colaPreguntasAi) {
            colaPreguntasAi.add(pregunta)
            Log.d(TAG, "Nueva pregunta inédita de Qwen agregada. Total en cola: ${colaPreguntasAi.size}")
        }
        guardarPreguntasPersistidas()
        return true
    }

    private fun normalizarEnunciado(texto: String): String {
        return texto.trim().lowercase().replace(Regex("[^a-záéíóúüñ0-9]"), "")
    }

    // Persistencia ligera en JSON para preservar preguntas de la IA entre sesiones
    private fun guardarPreguntasPersistidas() {
        try {
            val file = File(context.filesDir, "ai_questions_cache.json")
            val array = JSONArray()
            synchronized(colaPreguntasAi) {
                for (item in colaPreguntasAi) {
                    val obj = JSONObject().apply {
                        put("enunciado", item.enunciado)
                        put("opciones", JSONArray(item.opciones))
                        put("correcta", item.respuestaCorrectaIndex)
                        put("categoria", item.categoria)
                        put("esAi", true)
                    }
                    array.put(obj)
                }
            }
            file.writeText(array.toString())
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo guardar la caché de preguntas de IA", e)
        }
    }

    private fun cargarPreguntasPersistidas() {
        try {
            val file = File(context.filesDir, "ai_questions_cache.json")
            if (file.exists()) {
                val content = file.readText()
                val array = JSONArray(content)
                val cargadas = mutableListOf<PreguntaOpcionMultiple>()
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val opcionesArray = obj.getJSONArray("opciones")
                    val opciones = mutableListOf<String>()
                    for (j in 0 until opcionesArray.length()) {
                        opciones.add(opcionesArray.getString(j))
                    }
                    if (opciones.size >= 4) {
                        val pregunta = PreguntaOpcionMultiple(
                            enunciado = obj.getString("enunciado"),
                            opciones = opciones,
                            respuestaCorrectaIndex = obj.getInt("correcta"),
                            categoria = obj.optString("categoria", "Qwen IA"),
                            esGeneradaPorAi = true
                        )
                        cargadas.add(pregunta)
                        synchronized(historialEnunciados) {
                            historialEnunciados.add(normalizarEnunciado(pregunta.enunciado))
                        }
                    }
                }
                synchronized(colaPreguntasAi) {
                    colaPreguntasAi.clear()
                    colaPreguntasAi.addAll(cargadas)
                }
                Log.d(TAG, "Se cargaron ${cargadas.size} preguntas de IA desde la caché persistente.")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error al leer preguntas persistidas", e)
        }
    }
}