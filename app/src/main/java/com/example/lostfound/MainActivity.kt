package com.example.lostfound

import android.content.Context
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

class MainActivity : ComponentActivity() {

    private val TAG = "TFLiteValidator"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        Log.d(TAG, "Starting TFLite Model Validation...")

        // 1. Validate YOLO model
        verifyModel("yolov11n-face_float16.tflite")

        // 2. Validate MobileFaceNet model
        verifyModel("MobileFaceNet.tflite")
    }

    // Logic to load model and print Input/Output Shape
    private fun verifyModel(modelName: String) {
        try {
            val modelBuffer = loadModelFile(this, modelName)
            val interpreter = Interpreter(modelBuffer)
            Log.d(TAG, "Successfully loaded: $modelName")

            // Check input tensor details
            val inputTensor = interpreter.getInputTensor(0)
            Log.d(TAG, "   [Input Shape]: ${inputTensor.shape().contentToString()} | Type: ${inputTensor.dataType()}")

            // Check output tensor details
            val outputTensor = interpreter.getOutputTensor(0)
            Log.d(TAG, "   [Output Shape]: ${outputTensor.shape().contentToString()} | Type: ${outputTensor.dataType()}")

            // Close interpreter to prevent memory leaks
            interpreter.close()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load: $modelName. Error: ${e.message}")
        }
    }

    // Memory-map the model file from assets
    private fun loadModelFile(context: Context, modelName: String): MappedByteBuffer {
        val fileDescriptor = context.assets.openFd(modelName)
        val inputStream = FileInputStream(fileDescriptor.fileDescriptor)
        val fileChannel = inputStream.channel
        val startOffset = fileDescriptor.startOffset
        val declaredLength = fileDescriptor.declaredLength
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength)
    }
}