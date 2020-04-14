package ai.e_motion.camera_android

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.content.res.Configuration
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.hardware.camera2.CameraCharacteristics.SENSOR_ORIENTATION
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Size
import android.util.SparseIntArray
import android.view.*
import android.widget.Button
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.PermissionChecker
import androidx.core.content.PermissionChecker.checkSelfPermission
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import java.io.IOException
import java.util.*
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit


const val REQUEST_VIDEO_PERMISSIONS = 1
val VIDEO_PERMISSIONS = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
const val FRAGMENT_DIALOG = "dialog"


class CameraFragment : Fragment(), View.OnClickListener,
                                    ActivityCompat.OnRequestPermissionsResultCallback {

    // UI related variables
    private lateinit var textureView: AutoFitTextureView
    private lateinit var videoButton: Button

    // Thread related variables
    private var backgroundThread: HandlerThread? = null
    private var backgroundHandler: Handler? = null

    // Preview related variables
    private lateinit var previewSize: Size
    private lateinit var videoSize: Size
    private lateinit var previewRequestBuilder: CaptureRequest.Builder

    // Camera related variables
    private val cameraOpenCloseLock = Semaphore(1)  // semaphore for managing camera access
    private var sensorOrientation = 0
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null

    //  Video recording variables
    private var isRecordingVideo = false
    private var nextVideoAbsolutePath: String? = null
    private var mediaRecorder: MediaRecorder? = null
    private val SENSOR_ORIENTATION_DEFAULT_DEGREES = 90
    private val SENSOR_ORIENTATION_INVERSE_DEGREES = 270
    private val DEFAULT_ORIENTATIONS = SparseIntArray().apply {
        append(Surface.ROTATION_0, 90)
        append(Surface.ROTATION_90, 0)
        append(Surface.ROTATION_180, 270)
        append(Surface.ROTATION_270, 180)
    }
    private val INVERSE_ORIENTATIONS = SparseIntArray().apply {
        append(Surface.ROTATION_0, 270)
        append(Surface.ROTATION_90, 180)
        append(Surface.ROTATION_180, 90)
        append(Surface.ROTATION_270, 0)
    }

    //============================================================================================
    // TextureView.SurfaceTextureListener] handles TextureView lifecycle events
    //============================================================================================

    private val surfaceTextureListener = object : TextureView.SurfaceTextureListener {

        override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
            openCamera(width, height)
        }

        override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
          //  configureTransform(width, height)
        }

        override fun onSurfaceTextureDestroyed(surfaceTexture: SurfaceTexture) = true

        override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) = Unit
    }

    //============================================================================================
    // StateCallback handling functions
    //============================================================================================
    private val stateCallback = object : CameraDevice.StateCallback() {

        override fun onOpened(cameraDevice: CameraDevice) {
            cameraOpenCloseLock.release()
            this@CameraFragment.cameraDevice = cameraDevice
            startPreview()
            configureTransform(textureView.width, textureView.height)
        }

        override fun onDisconnected(cameraDevice: CameraDevice) {
            cameraOpenCloseLock.release()
            cameraDevice.close()
            this@CameraFragment.cameraDevice = null
        }

        override fun onError(cameraDevice: CameraDevice, error: Int) {
            cameraOpenCloseLock.release()
            cameraDevice.close()
            this@CameraFragment.cameraDevice = null
            activity?.finish()
        }

    }


    //============================================================================================
    // Fragment lifecycle related functions
    //============================================================================================

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? = inflater.inflate(R.layout.fragment_camera, container, false)


    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        textureView = view.findViewById(R.id.texture)
        videoButton = view.findViewById<Button>(R.id.video).also {
            it.setOnClickListener(this)
        }
        view.findViewById<View>(R.id.info).setOnClickListener(this)
    }

    override fun onResume() {
        super.onResume()
        startBackgroundThread()

        // If textureView is available, openCamera(); otherwise wait until
        // textureView becomes available (via surfaceTextureListener callback),
        // then call openCamera()
        if (textureView.isAvailable) {
            openCamera(textureView.width, textureView.height)
        } else {
            textureView.surfaceTextureListener = surfaceTextureListener
        }
    }

    override fun onPause() {
        closeCamera()
        stopBackgroundThread()
        super.onPause()
    }

    override fun onClick(v: View) {
        when (v.id) {
            R.id.video -> if (isRecordingVideo)
                                stopRecordingVideo()
                            else
                                startRecordingVideo()
            R.id.info -> {
                if (activity != null) {
                    AlertDialog.Builder(activity)
                        .setMessage(R.string.intro_message)
                        .setPositiveButton(android.R.string.ok, null)
                        .show()
                }
            }
        }
    }

    //============================================================================================
    // Thread related functions
    //============================================================================================

    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("CameraBackground")
        backgroundThread?.start()
        backgroundHandler = Handler(backgroundThread?.looper)
    }


    private fun stopBackgroundThread() {
        backgroundThread?.quitSafely()
        try {
            backgroundThread?.join()
            backgroundThread = null
            backgroundHandler = null
        } catch (e: InterruptedException) {
            Log.e("camera_android", e.toString())
        }
    }

    companion object {
        fun newInstance(): CameraFragment = CameraFragment()
    }

    //============================================================================================
    // Camera related functions
    //============================================================================================

    @SuppressLint("MissingPermission")
    private fun openCamera(width: Int, height: Int) {
        if (!hasPermissionsGranted(VIDEO_PERMISSIONS)) {
            requestVideoPermissions()
            return
        }

        val cameraActivity = activity
        if (cameraActivity == null || cameraActivity.isFinishing) return

        val manager = cameraActivity.getSystemService(Context.CAMERA_SERVICE) as CameraManager

        try {
            if (!cameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                throw RuntimeException("Time out waiting to lock camera opening.")
            }
            val cameraId = manager.cameraIdList[0]
            val numCam = manager.cameraIdList.size
            Log.d("CameraID", cameraId)

            // Choose the sizes for camera preview and video recording
            val characteristics = manager.getCameraCharacteristics(cameraId)
            val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?:
                    throw RuntimeException("Cannot get available preview/video sizes")
            sensorOrientation = characteristics.get(SENSOR_ORIENTATION)!!
            videoSize = chooseVideoSize(map.getOutputSizes(MediaRecorder::class.java))
            previewSize = chooseOptimalSize(map.getOutputSizes(SurfaceTexture::class.java),
                width, height, videoSize)

            if (resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                textureView.setAspectRatio(previewSize.width, previewSize.height)
            } else {
                textureView.setAspectRatio(previewSize.height, previewSize.width)
            }
            configureTransform(width, height)
            mediaRecorder = MediaRecorder()
            manager.openCamera(cameraId, stateCallback, null)
        } catch (e: CameraAccessException) {
            showToast("Cannot access the camera.")
            cameraActivity.finish()
        } catch (e: NullPointerException) {
            // Currently an NPE is thrown when the Camera2API is used but not supported on the
            // device this code runs.
            //ErrorDialog.newInstance(getString(R.string.camera_error))
            //    .show(childFragmentManager, FRAGMENT_DIALOG)
        } catch (e: InterruptedException) {
            throw RuntimeException("Interrupted while trying to lock camera opening.")
        }
    }


    private fun closeCamera() {
        try {
            cameraOpenCloseLock.acquire()
            closePreviewSession()
            cameraDevice?.close()
            cameraDevice = null
            mediaRecorder?.release()
            mediaRecorder = null
        } catch (e: InterruptedException) {
            throw RuntimeException("Interrupted while trying to lock camera closing.", e)
        } finally {
            cameraOpenCloseLock.release()
        }
    }

    //============================================================================================
    // Preview related functions
    //============================================================================================
    private fun startPreview() {
        if (cameraDevice == null || !textureView.isAvailable) return

        try {
            closePreviewSession()
            val texture = textureView.surfaceTexture
            texture.setDefaultBufferSize(previewSize.width, previewSize.height)
            previewRequestBuilder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)

            val previewSurface = Surface(texture)
            previewRequestBuilder.addTarget(previewSurface)

            cameraDevice?.createCaptureSession(listOf(previewSurface),
                object : CameraCaptureSession.StateCallback() {

                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        updatePreview()
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        if (activity != null) showToast("Failed")
                    }
                }, backgroundHandler)
        } catch (e: CameraAccessException) {
            Log.e("CameraFragment", e.toString())
        }

    }

    // Update the camera preview. [startPreview] needs to be called in advance.
    private fun updatePreview() {
        if (cameraDevice == null) return

        try {
            setUpCaptureRequestBuilder(previewRequestBuilder)
            // HandlerThread("CameraPreview").start()
            captureSession?.setRepeatingRequest(previewRequestBuilder.build(),
                null, backgroundHandler)
        } catch (e: CameraAccessException) {
            Log.e("CameraFragment", e.toString())
        }

    }

    private fun setUpCaptureRequestBuilder(builder: CaptureRequest.Builder?) {
        builder?.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
    }


    // Transform the Preview with desired width and height
    private fun configureTransform(viewWidth: Int, viewHeight: Int) {
        activity ?: return
        val rotation = (activity as FragmentActivity).windowManager.defaultDisplay.rotation
        val matrix = Matrix()
        val viewRect = RectF(0f, 0f, viewWidth.toFloat(), viewHeight.toFloat())
        val bufferRect = RectF(0f, 0f, previewSize.height.toFloat(), previewSize.width.toFloat())
        val centerX = viewRect.centerX()
        val centerY = viewRect.centerY()

        if (Surface.ROTATION_90 == rotation || Surface.ROTATION_270 == rotation) {
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY())
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)
            val scale = Math.max(
                viewHeight.toFloat() / previewSize.height,
                viewWidth.toFloat() / previewSize.width)
            with(matrix) {
                postScale(scale, scale, centerX, centerY)
                postRotate((90 * (rotation - 2)).toFloat(), centerX, centerY)
            }
        }
        textureView.setTransform(matrix)
    }

    private fun closePreviewSession() {
        captureSession?.close()
        captureSession = null
    }


    private fun chooseVideoSize(choices: Array<Size>) = choices.firstOrNull {
        it.width == it.height * 4 / 3 && it.width <= 1080 } ?: choices[choices.size - 1]


    private fun chooseOptimalSize(choices: Array<Size>, width: Int, height: Int,
                                  aspectRatio: Size): Size {
        // Collect the supported resolutions that are at least as big as the preview Surface
        val w = aspectRatio.width
        val h = aspectRatio.height
        val bigEnough = choices.filter {
            it.height == it.width * h / w && it.width >= width && it.height >= height }

        // Pick the smallest of those, assuming we found any
        return if (bigEnough.isNotEmpty()) {
            Collections.min(bigEnough, CompareSizesByArea())
        } else {
            choices[0]
        }
    }

    //============================================================================================
    // Video recording handling
    //============================================================================================
    private fun startRecordingVideo() {
        if (cameraDevice == null || !textureView.isAvailable) return

        try {
            closePreviewSession()
            setUpMediaRecorder()
            val texture = textureView.surfaceTexture.apply {
                setDefaultBufferSize(previewSize.width, previewSize.height)
            }

            // Set up Surface for camera preview and MediaRecorder
            val previewSurface = Surface(texture)
            val recorderSurface = mediaRecorder!!.surface
            val surfaces = ArrayList<Surface>().apply {
                add(previewSurface)
                add(recorderSurface)
            }
            previewRequestBuilder = cameraDevice!!.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                addTarget(previewSurface)
                addTarget(recorderSurface)
            }

            // Start a capture session
            // Once the session starts, we can update the UI and start recording
            cameraDevice?.createCaptureSession(surfaces,
                object : CameraCaptureSession.StateCallback() {

                    override fun onConfigured(cameraCaptureSession: CameraCaptureSession) {
                        captureSession = cameraCaptureSession
                        updatePreview()
                        activity?.runOnUiThread {
                            videoButton.setText(R.string.stop)
                            isRecordingVideo = true
                            mediaRecorder?.start()
                        }
                    }

                    override fun onConfigureFailed(cameraCaptureSession: CameraCaptureSession) {
                        if (activity != null) showToast("Failed")
                    }
                }, backgroundHandler)
        } catch (e: CameraAccessException) {
            Log.e("CameraFragment", e.toString())
        } catch (e: IOException) {
            Log.e("CameraFragment", e.toString())
        }
    }


    private fun stopRecordingVideo() {
        isRecordingVideo = false
        videoButton.setText(R.string.record)
        mediaRecorder?.apply {
            stop()
            reset()
        }

        if (activity != null) showToast("Video saved: $nextVideoAbsolutePath")
        nextVideoAbsolutePath = null
        startPreview()
    }


    @Throws(IOException::class)
    private fun setUpMediaRecorder() {
        val cameraActivity = activity ?: return

        if (nextVideoAbsolutePath.isNullOrEmpty()) {
            nextVideoAbsolutePath = getVideoFilePath(cameraActivity)
        }

        val rotation = cameraActivity.windowManager.defaultDisplay.rotation
        when (sensorOrientation) {
            SENSOR_ORIENTATION_DEFAULT_DEGREES ->
                mediaRecorder?.setOrientationHint(DEFAULT_ORIENTATIONS.get(rotation))
            SENSOR_ORIENTATION_INVERSE_DEGREES ->
                mediaRecorder?.setOrientationHint(INVERSE_ORIENTATIONS.get(rotation))
        }

        mediaRecorder?.apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setOutputFile(nextVideoAbsolutePath)
            setVideoEncodingBitRate(10000000)
            setVideoFrameRate(30)
            setVideoSize(videoSize.width, videoSize.height)
            setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            prepare()
        }
    }


    private fun getVideoFilePath(context: Context?): String {
        val filename = "${System.currentTimeMillis()}.mp4"
        val dir = context?.getExternalFilesDir(null)

        return if (dir == null) {
            filename
        } else {
            "${dir.absolutePath}/$filename"
        }
    }

    //============================================================================================
    // Permissions handling
    //============================================================================================

    private fun hasPermissionsGranted(permissions: Array<String>) = permissions.none {
        checkSelfPermission((activity as FragmentActivity), it) !=
                PermissionChecker.PERMISSION_GRANTED
    }

    private fun requestVideoPermissions() {
        if (shouldShowRequestPermissionRationale(VIDEO_PERMISSIONS)) {
            ConfirmationDialog().show(childFragmentManager, FRAGMENT_DIALOG)
        } else {
            requestPermissions(VIDEO_PERMISSIONS, REQUEST_VIDEO_PERMISSIONS)
        }
    }

    private fun shouldShowRequestPermissionRationale(permissions: Array<String>) =
        permissions.any { shouldShowRequestPermissionRationale(it) }

    //============================================================================================
    // Miscellaneous functions
    //============================================================================================

    private fun showToast(message : String) =
            Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()


}