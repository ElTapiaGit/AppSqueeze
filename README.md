# AppSqueeze - On-Device LLM App Blocker for Android

[![Android](https://img.shields.io/badge/Platform-Android%208.0%2B-brightgreen.svg)](https://android.com)
[![Kotlin](https://img.shields.io/badge/Language-Kotlin%202.2-blue.svg)](https://kotlinlang.org)
[![Compose](https://img.shields.io/badge/UI-Jetpack%20Compose%20(Material%203)-purple.svg)](https://developer.android.com/jetpack/compose)
[![LLM](https://img.shields.io/badge/Inference-llama.cpp%20(GGUF)-orange.svg)](https://github.com/ggml-org/llama.cpp)
[![Model](https://img.shields.io/badge/Model-Qwen%202.5%200.5B%20Instruct-red.svg)](https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF)

**AppSqueeze** es un bloqueador de aplicaciones y pantalla de estudio para Android impulsado **100% por Inteligencia Artificial local On-Device**. Utiliza un `AccessibilityService` para interceptar cuando el usuario intenta abrir apps distractoras o salir al launcher, forzándolo a responder correctamente 3 preguntas técnicas de entrevistas de software generadas en tiempo real y de forma completamente offline por un modelo LLM (**Qwen 2.5 0.5B Instruct**) mediante **llama.cpp**.

---

## Características Principales

- **Bloqueo Inteligente de Pantalla:** Intercepta cambios de ventana (`typeWindowStateChanged`) y previene la evasión de la pantalla mediante `BackHandler` y permisos de sobreposición (`SYSTEM_ALERT_WINDOW`).
- **Inferencia 100% Local y Offline (Zero Cloud):** Todo el procesamiento de lenguaje natural se realiza directamente en la CPU del dispositivo usando cuantización de 4 bits (`Q4_K_M`), garantizando máxima privacidad sin consumo de datos móviles ni latencia de red.
- **Banco Dinámico de Preguntas Técnicas:** Preguntas de opción múltiple de nivel entrevista técnica en áreas clave:
  - Bases de Datos Relacionales (SQL, Índices, Transacciones ACID, PostgreSQL)
  - Backend y APIs (REST, Códigos de Estado HTTP, JWT, Middleware)
  - Kotlin Avanzado (Coroutines, Flow, Dispatchers, Inmutabilidad)
  - Arquitectura de Software (Clean Architecture, SOLID, Inyección de Dependencias, MVVM)
  - Git y DevOps (Rebase vs Merge, Docker básico, CI/CD)
  - Manejo de Memoria y Optimización (Leaks, Garbage Collection, Caché)
- **Barajado Aleatorio y Deduplicación:** La opción correcta se distribuye al azar entre A, B, C y D (`shuffled()`), y un registro histórico de enunciados recientes previene preguntas repetidas.
- **Caché en Segundo Plano:** El motor de IA genera y almacena preguntas periódicamente en la caché local (`ai_questions_cache.json`) para que la pantalla de bloqueo responda al instante (0 ms).

---

## Arquitectura y Concurrencia

El proyecto sigue una arquitectura **MVI / MVVM reactiva** construida sobre Jetpack Compose y Kotlin Coroutines:

```mermaid
graph TD
    subgraph UI_Layer [Capa UI - Jetpack Compose]
        MainActivity --> QuizViewModel
        QuizViewModel --> QuizScreen
        QuizUiState -->|Loading / Active / Unlocked / Error| QuizScreen
    end

    subgraph Service_Layer [Capa de Servicio]
        SqueezeAccessibilityService -->|Bloqueo Detectado| MainActivity
    end

    subgraph Data_Layer [Repositorio & Caché]
        QuizViewModel --> PreguntasRepository
        PreguntasRepository --> Cache[(ai_questions_cache.json)]
    end

    subgraph LLM_Engine [Motor de Inferencia llama.cpp]
        QuizViewModel --> LlmManager
        LlmManager --> Mutex[generationMutex.withLock]
        Mutex --> LlamaModel[LlamaModel - llama.cpp ARM64]
        LlamaModel --> Qwen[qwen2.5-0.5b-instruct-q4_k_m.gguf]
        LlmManager -.->|Alimentación en Background| PreguntasRepository
    end
```

### Control de Concurrencia con `Mutex`
Dado que el contexto de decodificación en C++ de `llama.cpp` es secuencial por diseño, `LlmManager` utiliza un `Mutex` (`kotlinx.coroutines.sync.Mutex`) para sincronizar de forma atómica las peticiones on-demand de la UI y la precarga del generador en segundo plano, evitando colisiones de tipo `Generation already in progress`.

---

## Tech Stack

| Componente | Tecnología |
| :--- | :--- |
| **Lenguaje** | Kotlin 2.2.10 |
| **Interfaz de Usuario** | Jetpack Compose + Material 3 |
| **Gestión de Estado** | StateFlow, ViewModel (`QuizUiState`) |
| **Motor LLM Local** | llama.cpp mediante `org.codeshipping:llama-kotlin-android:0.1.3` |
| **Modelo de Lenguaje** | Qwen 2.5 0.5B Instruct GGUF (`qwen2.5-0.5b-instruct-q4_k_m.gguf` ~491 MB) |
| **Formato de Prompt** | ChatML Few-Shot con prefill de asistente |
| **Servicio de Bloqueo** | Android `AccessibilityService` |
| **Concurrencia** | Kotlin Coroutines (`Dispatchers.IO.limitedParallelism(1)` + `Mutex`) |

---

## Dispositivo y Rendimiento Probado

- **Dispositivo de Prueba:** Xiaomi Redmi Note 8 Pro
- **SoC:** MediaTek Helio G90T (2x Cortex-A76 @ 2.05 GHz + 6x Cortex-A55 @ 2.0 GHz)
- **Memoria RAM:** 6 GB LPDDR4X
- **Sistema Operativo:** Android 11 (MIUI 12.5)
- **Tiempo de Inferencia:** ~**7.3 segundos** por pregunta completa (con 4 opciones y validación).
- **Consumo de Memoria:** Estable por debajo de ~1.1 GB totales de proceso (cero cierres por Out-Of-Memory).

---

## Guía de Configuración Local

Debido a las políticas de tamaño de Git y GitHub, el archivo binario del modelo GGUF (~491 MB) no está versionado en este repositorio.

### Paso 1: Clonar el Repositorio
```bash
git clone https://github.com/TU_USUARIO/AppSqueeze.git
cd AppSqueeze
```

### Paso 2: Descargar el Modelo Qwen GGUF
Descarga el modelo oficial cuantizado en 4 bits desde el repositorio de **Qwen en Hugging Face**:

- **Modelo:** `Qwen2.5-0.5B-Instruct-GGUF`
- **Archivo:** `qwen2.5-0.5b-instruct-q4_k_m.gguf` (~491 MB)
- **Enlace de Descarga:** [Hugging Face - qwen2.5-0.5b-instruct-q4_k_m.gguf](https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/blob/main/qwen2.5-0.5b-instruct-q4_k_m.gguf)

### Paso 3: Ubicar el Modelo en el Proyecto
Coloca el archivo descargado en la carpeta de assets de la aplicación:
```text
app/src/main/assets/qwen2.5-0.5b-instruct-q4_k_m.gguf
```

> [!TIP]
> **Optimización para pruebas rápidas vía ADB:**
> Para no inflar el tamaño del APK de desarrollo, puedes transferir el modelo directamente al almacenamiento del teléfono una sola vez:
> ```bash
> adb push qwen2.5-0.5b-instruct-q4_k_m.gguf /sdcard/Download/qwen2.5-0.5b-instruct-q4_k_m.gguf
> ```
> La app detecta automáticamente si el modelo ya está presente en `/sdcard/Download/` o en `filesDir` y lo utiliza directamente sin duplicarlo.

### Paso 4: Compilar e Instalar
```powershell
.\gradlew.bat assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

---

## Permisos Requeridos en el Dispositivo

1. **Servicio de Accesibilidad:** Configuración > Accesibilidad > Servicios descargados > Activar **AppSqueeze**.
2. **Sobreponerse a otras apps (Draw over other apps):** Permitir mostrar la pantalla de cuestionario por encima de otras aplicaciones.

---

## Licencia

Este proyecto está distribuido bajo la licencia MIT. Consulta el archivo `LICENSE` para más detalles.
