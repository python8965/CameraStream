package com.aketon.camerastream

import android.Manifest.permission.CAMERA
import android.content.ContentValues.TAG
import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.lifecycle.ProcessCameraProvider
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
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executor


class MainActivity : ComponentActivity() {
    private var imageCapture: ImageCapture? = null

    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null

    private lateinit var previewView: PreviewView

    @OptIn(ExperimentalPermissionsApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)


        setContent {
            val cameraPermissionState = rememberPermissionState(
                permission = CAMERA,
            )



            LaunchedEffect(Unit) {
                cameraPermissionState.launchPermissionRequest()

            }

            CameraStreamTheme {

                // A surface container using the 'background' color from the theme
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
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
        val context = LocalContext.current
        val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }

        val videoListener = Consumer<VideoRecordEvent> { videoEvent ->
            videoEvent.outputOptions.location?.let { uri ->
                Log.d(TAG, "Video saved to: $uri")
            }
        }

        AndroidView(
            factory = { ctx ->
                val previewView = PreviewView(ctx)
                val executor = ContextCompat.getMainExecutor(ctx)

                cameraProviderFuture.addListener({
                    val videoCapture = VideoCapture.withOutput(Recorder.Builder().build().also { recorder ->
                        val mediaStore: MediaStoreOutputOptions = MediaStoreOutputOptions.Builder(
                            contentResolver,
                            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                        ).build()

                        val recoding = recorder.prepareRecording(context, mediaStore)

                        val recording = runCatching { recoding.start(Executor {  }, videoListener) }.onSuccess {
                            Log.d(TAG, "Recording started")
                        }.onFailure { error ->
                            Log.e(TAG, "Recording failed to start", error)
                        }
                    })

                    val cameraProvider = cameraProviderFuture.get()
                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }

                    val cameraSelector = CameraSelector.Builder()
                        .build()

                    cameraProvider.unbindAll()
                    cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        cameraSelector,
                        preview,
                        videoCapture
                    )
                }, executor)
                previewView
            },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
fun Greeting(name: String, modifier: Modifier = Modifier) {

    Text(
        text = "Hello $name!",
        modifier = modifier
    )
}

@Composable
fun FindServer() {
    Log.e(TAG, "FindServer")
    val isConnected = remember { mutableStateOf(false) }

    val context = LocalContext.current
    val nsdManager = getSystemService(context, NsdManager::class.java) as NsdManager


    val resolveListener = object : NsdManager.ResolveListener {

        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
            // Called when the resolve fails. Use the error code to debug.
            Log.e(TAG, "Resolve failed: $errorCode")
        }

        override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
            Log.e(TAG, "Resolve Succeeded. $serviceInfo")

            val port: Int = serviceInfo.port
            val host: InetAddress = serviceInfo.host
            Log.e(TAG, "port: $port")

            Socket(host, port)

            //TODO : Video stream

        }
    }
    // Instantiate a new DiscoveryListener
    val discoveryListener = object : NsdManager.DiscoveryListener {

        // Called as soon as service discovery begins.
        override fun onDiscoveryStarted(regType: String) {
            Log.d(TAG, "Service discovery started")
        }

        override fun onServiceFound(service: NsdServiceInfo) {
            // A service was found! Do something with it.
            Log.d(TAG, "Service discovery success$service")

            when {

                service.serviceType != "_camerastream._tcp."-> // Service type is the string containing the protocol and
                    // transport layer for this service.
                    Log.d(TAG, "Unknown Service Type: ${service.serviceType}")
                service.serviceName.contains("NsdChat") -> nsdManager.resolveService(service, resolveListener)
            }
        }

        override fun onServiceLost(service: NsdServiceInfo) {
            // When the network service is no longer available.
            // Internal bookkeeping code goes here.
            Log.e(TAG, "service lost: $service")
        }

        override fun onDiscoveryStopped(serviceType: String) {
            Log.i(TAG, "Discovery stopped: $serviceType")
        }

        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.e(TAG, "Discovery failed: Error code:$errorCode")
            nsdManager.stopServiceDiscovery(this)
        }

        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.e(TAG, "Discovery failed: Error code:$errorCode")
            nsdManager.stopServiceDiscovery(this)
        }
    }

    if (!isConnected.value) {
        nsdManager.discoverServices(
            "_camerastream._tcp", NsdManager.PROTOCOL_DNS_SD, discoveryListener
        )
    } else {
        nsdManager.stopServiceDiscovery(discoveryListener)
    }
}