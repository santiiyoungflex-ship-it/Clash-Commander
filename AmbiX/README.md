# AmbiX
Proyecto Android nativo nuevo, separado del código anterior. Compatible desde Android 6.0 y diseñado para funcionar en Android 11.

Estado: MVP de análisis manual. Permite registrar cartas rivales, estimar arquetipos mediante firmas básicas y sugerir respuestas del mazo. Todavía no captura pantalla, no reconoce cartas automáticamente, no controla el juego y no es una APK lista hasta que la compilación de CI termine correctamente.

Construcción: JDK 17 + Android SDK + `gradle -p AmbiX :app:assembleDebug`.

Siguientes etapas: pruebas del motor, captura MediaProjection con permiso, visión calibrada por resolución, seguimiento de ciclo/elixir y validación con replays. La automatización de entradas puede infringir reglas de Supercell y arriesgar la cuenta; esta base es un asistente.
