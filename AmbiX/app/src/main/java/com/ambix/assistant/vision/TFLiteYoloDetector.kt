package com.ambix.assistant.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min

/**
 * Runtime for an Ultralytics YOLOv8/YOLO11 TFLite export:
 * float32 input [1,H,W,3], raw output [1,4+classes,N] or [1,N,4+classes].
 *
 * The app intentionally remains vision-not-ready until both the trained model
 * and its exact labels.txt are packaged in assets. No guessed labels/model.
 */
class TFLiteYoloDetector(
    context: Context,
    modelAsset: String = MODEL_ASSET,
    labelsAsset: String = LABELS_ASSET
) : BattleObjectDetector, Closeable {
    private val interpreter: Interpreter?
    private val labels: List<String>
    private val inputWidth: Int
    private val inputHeight: Int
    private val outputShape: IntArray
    override val diagnostic: String
    override val isModelLoaded: Boolean

    private data class Candidate(
        val label: String,
        val kind: ObjectKind,
        val team: Team,
        val box: Box,
        val score: Float
    )

    init {
        var loaded: Interpreter? = null
        var names: List<String> = emptyList()
        var width = 320
        var height = 320
        var shape = intArrayOf()
        var message = "Modelo TFLite no instalado"
        try {
            val modelBytes = context.assets.open(modelAsset).use { it.readBytes() }
            names = context.assets.open(labelsAsset).bufferedReader().useLines { lines ->
                lines.map { it.trim() }.filter { it.isNotEmpty() }.toList()
            }
            require(names.isNotEmpty()) { "labels.txt está vacío" }
            val model = ByteBuffer.allocateDirect(modelBytes.size).order(ByteOrder.nativeOrder())
            model.put(modelBytes)
            model.rewind()
            loaded = Interpreter(model, Interpreter.Options().setNumThreads(2))
            val input = loaded.getInputTensor(0)
            val inputShape = input.shape()
            require(input.dataType() == DataType.FLOAT32) { "El modelo debe usar entrada float32" }
            require(inputShape.size == 4 && inputShape[0] == 1 && inputShape[3] == 3) {
                "Entrada incompatible: se esperaba [1,H,W,3]"
            }
            height = inputShape[1]
            width = inputShape[2]
            require(width in 160..640 && height in 160..640) { "Resolución de entrada no admitida" }
            require(loaded.outputTensorCount > 0) { "El modelo no tiene salidas" }
            shape = loaded.getOutputTensor(0).shape()
            require(loaded.getOutputTensor(0).dataType() == DataType.FLOAT32) {
                "La salida del modelo debe ser float32"
            }
            require(shape.size == 3 && shape[0] == 1) { "Salida incompatible: se esperaba tensor YOLO [1,C,N] o [1,N,C]" }
            require(shape[1] == 4 + names.size || shape[1] == 5 + names.size || shape[2] == 4 + names.size || shape[2] == 5 + names.size) {
                "El número de clases del tensor no coincide con labels.txt"
            }
            message = "Modelo local cargado · ${names.size} clases · ${width}×${height}"
        } catch (error: Throwable) {
            loaded?.close()
            loaded = null
            message = "Visión no disponible: ${error.message ?: error.javaClass.simpleName}"
        }
        interpreter = loaded
        labels = names
        inputWidth = width
        inputHeight = height
        outputShape = shape
        diagnostic = message
        isModelLoaded = loaded != null
    }

    @Synchronized
    override fun detect(frame: Bitmap): List<Detection> {
        val model = interpreter ?: return emptyList()
        return try {
            val sourceWidth = frame.width
            val sourceHeight = frame.height
            val scale = min(inputWidth.toFloat() / sourceWidth, inputHeight.toFloat() / sourceHeight)
            val resizedWidth = (sourceWidth * scale).toInt().coerceAtLeast(1)
            val resizedHeight = (sourceHeight * scale).toInt().coerceAtLeast(1)
            val padX = (inputWidth - resizedWidth) / 2f
            val padY = (inputHeight - resizedHeight) / 2f

            val resized = Bitmap.createScaledBitmap(frame, resizedWidth, resizedHeight, true)
            val inputBitmap = Bitmap.createBitmap(inputWidth, inputHeight, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(inputBitmap)
            canvas.drawColor(Color.rgb(114, 114, 114))
            canvas.drawBitmap(resized, padX, padY, Paint(Paint.FILTER_BITMAP_FLAG))
            if (resized !== frame) resized.recycle()

            val pixels = IntArray(inputWidth * inputHeight)
            inputBitmap.getPixels(pixels, 0, inputWidth, 0, 0, inputWidth, inputHeight)
            inputBitmap.recycle()
            val input = ByteBuffer.allocateDirect(4 * pixels.size * 3).order(ByteOrder.nativeOrder())
            pixels.forEach { color ->
                input.putFloat(Color.red(color) / 255f)
                input.putFloat(Color.green(color) / 255f)
                input.putFloat(Color.blue(color) / 255f)
            }
            input.rewind()

            val outputElements = outputShape.fold(1) { acc, value -> acc * value }
            val output = ByteBuffer.allocateDirect(outputElements * 4).order(ByteOrder.nativeOrder())
            model.run(input, output)
            output.rewind()
            val values = FloatArray(outputElements)
            output.asFloatBuffer().get(values)

            val dimA = outputShape[1]
            val dimB = outputShape[2]
            val channelsFirst = dimA == 4 + labels.size || dimA == 5 + labels.size
            val channels = if (channelsFirst) dimA else dimB
            val anchors = if (channelsFirst) dimB else dimA
            val hasObjectness = channels == 5 + labels.size
            val classOffset = if (hasObjectness) 5 else 4
            val outputScale = if (values.maxOrNull()?.let { it <= 1.5f } == true) inputWidth.toFloat() else 1f
            val candidates = ArrayList<Candidate>()

            for (anchor in 0 until anchors) {
                fun value(channel: Int): Float =
                    if (channelsFirst) values[channel * anchors + anchor]
                    else values[anchor * channels + channel]

                val cx = value(0) * outputScale
                val cy = value(1) * outputScale
                val bw = value(2) * outputScale
                val bh = value(3) * outputScale
                val objectness = if (hasObjectness) value(4) else 1f
                var bestClass = -1
                var bestScore = 0f
                for (classIndex in labels.indices) {
                    val score = value(classOffset + classIndex) * objectness
                    if (score > bestScore) {
                        bestScore = score
                        bestClass = classIndex
                    }
                }
                if (bestClass < 0) continue
                val rawLabel = labels[bestClass]
                val label = canonicalLabel(rawLabel)
                val kind = kindFor(rawLabel)
                if (bestScore < thresholdFor(kind)) continue

                val left = ((cx - bw / 2f - padX) / scale / sourceWidth).coerceIn(0f, 1f)
                val top = ((cy - bh / 2f - padY) / scale / sourceHeight).coerceIn(0f, 1f)
                val right = ((cx + bw / 2f - padX) / scale / sourceWidth).coerceIn(0f, 1f)
                val bottom = ((cy + bh / 2f - padY) / scale / sourceHeight).coerceIn(0f, 1f)
                if (right <= left || bottom <= top) continue
                val box = Box(left, top, right, bottom)
                candidates += Candidate(label, kind, teamFor(rawLabel, box.centerY), box, bestScore.coerceIn(0f, 1f))
            }

            nonMaximumSuppression(candidates).map {
                Detection(it.label, it.kind, it.team, it.box, it.score)
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun nonMaximumSuppression(input: List<Candidate>): List<Candidate> {
        val result = mutableListOf<Candidate>()
        input.groupBy { it.label.lowercase() to it.team }.values.forEach { group ->
            val remaining = group.sortedByDescending { it.score }.toMutableList()
            while (remaining.isNotEmpty() && result.size < MAX_DETECTIONS) {
                val best = remaining.removeAt(0)
                result += best
                remaining.removeAll { iou(best.box, it.box) > NMS_IOU }
            }
        }
        return result
    }

    private fun iou(a: Box, b: Box): Float {
        val w = max(0f, min(a.right, b.right) - max(a.left, b.left))
        val h = max(0f, min(a.bottom, b.bottom) - max(a.top, b.top))
        val intersection = w * h
        val union = (a.right-a.left)*(a.bottom-a.top) + (b.right-b.left)*(b.bottom-b.top) - intersection
        return if (union <= 0f) 0f else intersection / union
    }

    private fun canonicalLabel(raw: String): String = raw.lowercase()
        .removePrefix("enemy_").removePrefix("ally_")
        .replace('_', ' ').split(" ").filter { it.isNotBlank() }
        .joinToString(" ") { it.replaceFirstChar { ch -> ch.uppercase() } }

    private fun kindFor(raw: String): ObjectKind {
        val name = raw.lowercase().removePrefix("enemy_").removePrefix("ally_").replace('_', ' ')
        return when {
            name == "elixir" || name == "elixir bar" || "elixer" in name -> ObjectKind.ELIXIR
            "card" in name || "hand" in name || " next" in name -> ObjectKind.CARD_IN_HAND
            name == "princess tower" || name == "king tower" -> ObjectKind.TOWER
            name in setOf("the log", "log", "rocket", "arrow", "arrows", "fireball", "goblin barrel", "barbarian barrel", "zap", "poison", "lightning", "earthquake", "freeze", "rage", "tornado", "clone", "mirror", "void", "giant snowball", "goblin curse", "graveyard", "royal delivery") -> ObjectKind.SPELL
            "building" in name || name in setOf("cannon", "tesla", "mortar", "x-bow", "x bow", "inferno tower", "bomb tower", "tombstone", "goblin cage", "furnace", "barbarian hut", "goblin hut", "elixir collector") -> ObjectKind.BUILDING
            "elixir" in name -> ObjectKind.ELIXIR
            name.isNotBlank() -> ObjectKind.TROOP
            else -> ObjectKind.UNKNOWN
        }
    }

    private fun teamFor(raw: String, centerY: Float): Team {
        val name = raw.lowercase()
        return when {
            name.startsWith("enemy_") || name.startsWith("enemy ") || name.startsWith("opponent ") || name.startsWith("red ") -> Team.OPPONENT
            name.startsWith("ally_") || name.startsWith("ally ") || name.startsWith("player ") || name.startsWith("blue ") -> Team.PLAYER
            centerY < 0.48f -> Team.OPPONENT
            centerY > 0.55f -> Team.PLAYER
            else -> Team.UNKNOWN
        }
    }

    private fun thresholdFor(kind: ObjectKind): Float = when (kind) {
        ObjectKind.TROOP, ObjectKind.BUILDING -> 0.35f
        ObjectKind.TOWER -> 0.50f
        ObjectKind.SPELL -> 0.45f
        ObjectKind.CARD_IN_HAND -> 0.55f
        ObjectKind.ELIXIR -> 0.60f
        ObjectKind.UNKNOWN -> 0.80f
    }

    override fun close() {
        interpreter?.close()
    }

    companion object {
        const val MODEL_ASSET = "ambix_model.tflite"
        const val LABELS_ASSET = "labels.txt"
        private const val NMS_IOU = 0.45f
        private const val MAX_DETECTIONS = 80
    }
}
