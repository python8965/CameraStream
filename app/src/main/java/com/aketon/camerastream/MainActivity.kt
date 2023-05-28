package com.aketon.camerastream

import android.Manifest.permission.CAMERA
import android.content.ContentValues.TAG
import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.RequiresApi
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileDescriptorOutputOptions
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoOutput
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.ContextCompat.getSystemService
import androidx.core.util.Consumer

import com.aketon.camerastream.ui.theme.CameraStreamTheme
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.PermissionRequired
import com.google.accompanist.permissions.rememberPermissionState
import com.google.common.util.concurrent.ListenableFuture
import java.io.FileDescriptor
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executor
import java.util.concurrent.Executors


class MainActivity : ComponentActivity() {
    private var recordingExecutor: Executor = Executors.newSingleThreadExecutor()
    private lateinit var cameraProviderFuture: ListenableFuture<ProcessCameraProvider>
    var previewUseCase: UseCase? = null

    @RequiresApi(Build.VERSION_CODES.O)
    @OptIn(ExperimentalPermissionsApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)


        setContent {
            val cameraPermissionState = rememberPermissionState(
                permission = CAMERA,
            )

            val context = LocalContext.current

            cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }

            LaunchedEffect(Unit) {
                cameraPermissionState.launchPermissionRequest()

            }

            CameraStreamTheme {

                // A surface container using the 'background' color from the theme
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    PermissionRequired(
                        permissionState = cameraPermissionState,
                        permissionNotGrantedContent = { Greeting("Permission not granted") },
                        permissionNotAvailableContent = { }
                    ) {
                        SimpleCameraPreview()
                        FindServer()
                    }

                }
            }
        }
    }

    @Composable
    fun SimpleCameraPreview() {
        val lifecycleOwner = LocalLifecycleOwner.current




        AndroidView(
            factory = { ctx ->
                val previewView = PreviewView(ctx)
                val executor = ContextCompat.getMainExecutor(ctx)

                cameraProviderFuture.addListener({

                    val cameraProvider = cameraProviderFuture.get()
                    previewUseCase = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }

                    val cameraSelector = CameraSelector.Builder()
                        .build()

                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        cameraSelector,
                        previewUseCase
                    )
                }, executor)
                previewView
            },
            modifier = Modifier.fillMaxSize(),
        )
    }

    @Composable
    @RequiresApi(Build.VERSION_CODES.O)
    @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
    fun FindServer() {
        Log.e(TAG, "FindServer")
        val isConnected = remember { mutableStateOf(false) }


        val context = LocalContext.current
        val lifecycleOwner = LocalLifecycleOwner.current
        val nsdManager = getSystemService(context, NsdManager::class.java) as NsdManager
        val executor = ContextCompat.getMainExecutor(context)

        val serverSocket = ServerSocket(0)

        fun connected(serviceInfo: NsdServiceInfo) {
            val cameraProvider = cameraProviderFuture.get()

            Log.i(TAG, "try connecting")

            val socket = serverSocket.accept()

            Log.i(TAG, "accepted connection from ${socket.inetAddress.hostAddress}")

            cameraProviderFuture.addListener({
                cameraProvider.unbindAll()

                val cameraSelector = CameraSelector.Builder()
                    .build()

                val imageAnalysis = ImageAnalysis.Builder()
                    // enable the following line if RGBA output is needed.
                    // .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

                imageAnalysis.setAnalyzer(executor, ImageAnalysis.Analyzer { imageProxy ->
                    Log.i(TAG, imageProxy.toBitmap().run {
                        val array : IntArray = IntArray(100)
                        getPixels(array, 0,10,0,0,10,10)

                        socket.getOutputStream().write(array.fold(ArrayList<Byte>()){ acc, i->
                            acc.add(((i and 0xff).toByte()))
                            acc
                        }.toByteArray())

                        Log.i(TAG, "wrote ${array.size} bytes")

                        array.toString()
                    })


                    // insert your code here.
                    // after done, release the ImageProxy object
                    imageProxy.close()
                })

                val parcelFileDescriptor = ParcelFileDescriptor.fromSocket(socket)

                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    imageAnalysis,
                    previewUseCase
                )
            }, executor)
        }

        val registerListener = object: NsdManager.RegistrationListener{
            override fun onRegistrationFailed(p0: NsdServiceInfo?, p1: Int) {
                Log.e(TAG, "onRegistrationFailed")
            }

            override fun onUnregistrationFailed(p0: NsdServiceInfo?, p1: Int) {
                Log.e(TAG, "onUnregistrationFailed")
            }

            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                Log.i(TAG, "onServiceRegistered")
                connected(serviceInfo)
            }

            override fun onServiceUnregistered(p0: NsdServiceInfo) {
                Log.i(TAG, "onServiceUnregistered")
            }

        }



        val mLocalPort = serverSocket.localPort

        nsdManager.registerService(
            NsdServiceInfo().apply {
                serviceName = "CameraStream"
                serviceType = "_camerastream._tcp"
                port = mLocalPort
            },
            NsdManager.PROTOCOL_DNS_SD,
            registerListener
        )

        Log.i(TAG, "server opened with port $mLocalPort")

    }
}

@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {

    Text(
        text = "Hello $name!",
        modifier = modifier
    )
}