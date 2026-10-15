package com.example.appsqueeze.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.appsqueeze.ui.viewmodel.QuizUiState

@Composable
fun QuizScreen(
    uiState: QuizUiState,
    onOpcionSeleccionada: (Int) -> Unit,
    onDesbloqueoConfirmado: () -> Unit,
    onReintentar: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        when (uiState) {
            is QuizUiState.Loading -> {
                LoadingView(uiState = uiState)
            }
            is QuizUiState.Active -> {
                ActiveQuizView(
                    uiState = uiState,
                    onOpcionSeleccionada = onOpcionSeleccionada
                )
            }
            is QuizUiState.Unlocked -> {
                UnlockedView(
                    uiState = uiState,
                    onDesbloqueoConfirmado = onDesbloqueoConfirmado
                )
            }
            is QuizUiState.Error -> {
                ErrorView(
                    uiState = uiState,
                    onReintentar = onReintentar
                )
            }
        }
    }
}

@Composable
private fun ActiveQuizView(
    uiState: QuizUiState.Active,
    onOpcionSeleccionada: (Int) -> Unit
) {
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 20.dp, vertical = 16.dp)
            .verticalScroll(scrollState),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // ENCABEZADO DE SEGURIDAD
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Insignia de bloqueo de estudio
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.85f))
                    .padding(horizontal = 14.dp, vertical = 6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.error)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "BLOQUEO ACTIVO DE ESTUDIO",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    letterSpacing = 0.5.sp
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Barra de progreso y contador de aciertos requeridos
            Text(
                text = "Aciertos necesarios: ${uiState.aciertosActuales} de ${uiState.metaAciertos}",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )

            Spacer(modifier = Modifier.height(8.dp))

            val progreso = (uiState.aciertosActuales.toFloat() / uiState.metaAciertos).coerceIn(0f, 1f)
            LinearProgressIndicator(
                progress = { progreso },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp)),
                color = if (progreso >= 1f) Color(0xFF2E7D32) else MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Insignias informativas: IA Local Gemma 2B y estado del motor
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                SuggestionChip(
                    onClick = {},
                    label = {
                        Text(
                            text = "Qwen 2.5 • llama.cpp",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF1B5E20)
                        )
                    },
                    colors = SuggestionChipDefaults.suggestionChipColors(
                        containerColor = Color(0xFFE8F5E9)
                    ),
                    border = SuggestionChipDefaults.suggestionChipBorder(
                        enabled = true,
                        borderColor = Color(0xFFA5D6A7)
                    )
                )

                Text(
                    text = uiState.statusAiBadge,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.outline,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // TARJETA DE LA PREGUNTA
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            elevation = CardDefaults.elevatedCardElevation(defaultElevation = 3.dp),
            colors = CardDefaults.elevatedCardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            Column(
                modifier = Modifier.padding(20.dp)
            ) {
                Text(
                    text = uiState.pregunta.categoria.uppercase(),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                    letterSpacing = 1.sp
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = uiState.pregunta.enunciado,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    lineHeight = 24.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // OPCIONES DE RESPUESTA INTERACTIVAS
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            uiState.pregunta.opciones.forEachIndexed { index, opcion ->
                val esSeleccionada = uiState.opcionSeleccionada == index
                val esLaCorrecta = index == uiState.pregunta.respuestaCorrectaIndex

                val (backgroundColor, textColor, borderColor) = when {
                    esSeleccionada -> {
                        if (uiState.esRespuestaCorrecta == true) {
                            Triple(Color(0xFF2E7D32), Color.White, Color(0xFF1B5E20)) // Verde acierto
                        } else {
                            Triple(Color(0xFFC62828), Color.White, Color(0xFFB71C1C)) // Rojo fallo
                        }
                    }
                    uiState.opcionSeleccionada != null && esLaCorrecta -> {
                        Triple(Color(0xFFE8F5E9), Color(0xFF1B5E20), Color(0xFF4CAF50))
                    }
                    else -> {
                        Triple(
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            MaterialTheme.colorScheme.onSurface,
                            MaterialTheme.colorScheme.outlineVariant
                        )
                    }
                }

                Surface(
                    onClick = {
                        if (!uiState.bloqueandoSeleccion) {
                            onOpcionSeleccionada(index)
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, borderColor, RoundedCornerShape(12.dp)),
                    shape = RoundedCornerShape(12.dp),
                    color = backgroundColor,
                    enabled = !uiState.bloqueandoSeleccion
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val letra = ('A' + index).toString()
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(
                                    if (esSeleccionada) Color.White.copy(alpha = 0.25f)
                                    else MaterialTheme.colorScheme.primaryContainer
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = letra,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                color = if (esSeleccionada) Color.White else MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }

                        Spacer(modifier = Modifier.width(14.dp))

                        Text(
                            text = opcion,
                            fontSize = 14.sp,
                            fontWeight = if (esSeleccionada) FontWeight.Bold else FontWeight.Normal,
                            color = textColor,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        Text(
            text = "Responde correctamente para salir al inicio o abrir tus aplicaciones.",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.outline,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun LoadingView(uiState: QuizUiState.Loading) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(0.92f),
            shape = RoundedCornerShape(24.dp),
            elevation = CardDefaults.elevatedCardElevation(defaultElevation = 4.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 36.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(56.dp),
                    color = MaterialTheme.colorScheme.primary,
                    strokeWidth = 5.dp
                )

                Spacer(modifier = Modifier.height(24.dp))

                Text(
                    text = uiState.titulo,
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = uiState.detalle.ifBlank { "Por favor espera unos segundos mientras el modelo procesa la pregunta" },
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                    lineHeight = 20.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (uiState.progreso != null) {
                    Spacer(modifier = Modifier.height(20.dp))
                    LinearProgressIndicator(
                        progress = { uiState.progreso },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp)),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "Por favor espera unos segundos mientras el modelo procesa la pregunta",
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }
    }
}

@Composable
private fun UnlockedView(
    uiState: QuizUiState.Unlocked,
    onDesbloqueoConfirmado: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(0.92f),
            shape = RoundedCornerShape(24.dp),
            elevation = CardDefaults.elevatedCardElevation(defaultElevation = 4.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            Column(
                modifier = Modifier.padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(68.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFE8F5E9)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "✓",
                        fontSize = 34.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF2E7D32)
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                Text(
                    text = "¡Dispositivo Desbloqueado!",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF2E7D32),
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = uiState.mensaje,
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(24.dp))

                Button(
                    onClick = onDesbloqueoConfirmado,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32))
                ) {
                    Text(
                        text = "Continuar al Teléfono",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White
                    )
                }
            }
        }
    }
}

@Composable
private fun ErrorView(
    uiState: QuizUiState.Error,
    onReintentar: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(0.92f),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
        ) {
            Column(
                modifier = Modifier.padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Aviso del Sistema",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )

                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = uiState.mensaje,
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )

                if (!uiState.detalle.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = uiState.detalle,
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f)
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                Button(
                    onClick = onReintentar,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(text = "Reintentar", color = MaterialTheme.colorScheme.onError)
                }
            }
        }
    }
}