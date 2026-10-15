package com.example.appsqueeze.data

data class PreguntaOpcionMultiple(
    val enunciado: String,
    val opciones: List<String>,
    val respuestaCorrectaIndex: Int,
    val categoria: String = "Desarrollo Android",
    val esGeneradaPorAi: Boolean = false
)
