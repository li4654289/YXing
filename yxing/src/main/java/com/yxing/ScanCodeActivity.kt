package com.yxing

import android.app.Activity
import android.content.Intent
import android.util.DisplayMetrics
import android.util.Log
import android.util.Size
import android.widget.RelativeLayout
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.example.yxing.R
import com.google.zxing.BarcodeFormat
import com.google.zxing.Result
import com.yxing.def.ScanStyle
import com.yxing.def.ScanType
import com.yxing.iface.OnScancodeListener
import com.yxing.view.ScanCustomizeView
import com.yxing.view.ScanQqView
import com.yxing.view.ScanWechatView
import com.yxing.view.base.BaseScanView
import kotlinx.android.synthetic.main.activity_scancode.*
import java.lang.Math.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

open class ScanCodeActivity : BaseScanActivity(), OnScancodeListener {

    //设置所选相机
    private val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
    private lateinit var scanSize: Size
    private lateinit var camera: Camera
    private lateinit var preview: Preview
    private lateinit var imageAnalyzer: ImageAnalysis
    private lateinit var cameraControl: CameraControl
    private lateinit var mCameraInfo: CameraInfo
    private lateinit var cameraExecutor: ExecutorService
    private var baseScanView: BaseScanView? = null
    private var rlParentContent: RelativeLayout? = null
    private lateinit var scModel: ScanCodeModel

    companion object {
        private const val TAG = "YXing"
        private const val RATIO_4_3_VALUE = 4.0 / 3.0
        private const val RATIO_16_9_VALUE = 16.0 / 9.0
    }

    override fun getLayoutId(): Int = R.layout.activity_scancode

    override fun initData() {
        scModel = intent?.extras?.getParcelable(ScanCodeConfig.MODEL_KEY)!!
        addScanView(scModel.style)
        // Initialize our background executor
        cameraExecutor = Executors.newSingleThreadExecutor()
        // surface准备监听
        pvCamera.post {
            //设置需要实现的用例（预览，拍照，图片数据解析等等）
            bindCameraUseCases()
        }
    }

    fun setFlashStatus(isOpenFlash: Boolean) {
        cameraControl.enableTorch(isOpenFlash)
    }

    private fun addScanView(style: Int?) {
        rlParentContent = findViewById(R.id.rlparent)
        when (style) {
            ScanStyle.QQ -> {
                baseScanView = ScanQqView(this)
            }
            ScanStyle.WECHAT -> {
                baseScanView = ScanWechatView(this)
            }
            ScanStyle.CUSTOMIZE -> {
                baseScanView = ScanCustomizeView(this).apply {
                    setScanCodeModel(scModel)
                }
            }
        }
        baseScanView?.let {
            val lp: RelativeLayout.LayoutParams = RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.MATCH_PARENT,
                RelativeLayout.LayoutParams.MATCH_PARENT
            )
            it.layoutParams = lp
            rlParentContent?.addView(it, 1)
        }
    }

    private fun bindCameraUseCases() {
        // 获取用于设置全屏分辨率相机的屏幕值
        val metrics = DisplayMetrics().also { pvCamera.display.getRealMetrics(it) }

        //获取使用的屏幕比例分辨率属性
        val screenAspectRatio = aspectRatio(metrics.widthPixels / 2, metrics.heightPixels / 2)

        val width = pvCamera.measuredWidth

        val height = if (screenAspectRatio == AspectRatio.RATIO_16_9) {
            (width * RATIO_16_9_VALUE).toInt()
        } else {
            (width * RATIO_4_3_VALUE).toInt()
        }

        scanSize = Size(width, height)

        //获取旋转角度
        val rotation = pvCamera.display.rotation

        //生命周期绑定
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({

            val cameraProvider = cameraProviderFuture.get()

            // 预览用例
            preview = getCameraPreView(rotation)

            // 图像分析用例
            imageAnalyzer = getCameraAnalyzer(rotation)

            // 必须在重新绑定用例之前取消之前绑定
            cameraProvider.unbindAll()

            try {
                //获取相机实例
                camera = cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageAnalyzer
                )
                //设置预览的view
                preview.setSurfaceProvider(pvCamera.surfaceProvider)
                cameraControl = camera.cameraControl
                mCameraInfo = camera.cameraInfo

                bindTouchListener()
            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /**
     * 获取相机预览用例
     */
    private fun getCameraPreView(rotation: Int): Preview {
        return Preview.Builder()
            .setTargetResolution(scanSize)
            .setTargetRotation(rotation)
            .build()
    }

    /**
     * 获取相机分析用例
     */
    private fun getCameraAnalyzer(rotation: Int): ImageAnalysis {
        val mImageAnalysis = ImageAnalysis.Builder()
            .setTargetResolution(scanSize)
            .setTargetRotation(rotation)
            .build()
        mImageAnalysis.setAnalyzer(
            cameraExecutor,
            ScanCodeAnalyzer(
                this,
                scModel,
                baseScanView?.scanRect,
                this
            )
        )
        return mImageAnalysis
    }

    /**
     * 触摸事件监听（方大、缩小、对焦）
     */
    private fun bindTouchListener() {
        val zoomState = mCameraInfo.zoomState
        val cameraXPreviewViewTouchListener =
            PreviewTouchListener(this)
        cameraXPreviewViewTouchListener.setCustomTouchListener(object :
            PreviewTouchListener.CustomTouchListener {
            override fun zoom(delta: Float) {
                zoomState.value?.let {
                    val currentZoomRatio = it.zoomRatio
                    cameraControl.setZoomRatio(currentZoomRatio * delta)
                }
            }

            override fun focus(pointX: Float, pointY: Float) {
                cameraFocus(pointX, pointY)
            }
        })
        pvCamera.setOnTouchListener(cameraXPreviewViewTouchListener)
    }

    /**
     * 对焦
     */
    private fun cameraFocus(pointX: Float, pointY: Float) {
        val factory = SurfaceOrientedMeteringPointFactory(
            scanSize.width.toFloat(),
            scanSize.height.toFloat()
        )
        val point = factory.createPoint(pointX, pointY)
        val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF)
            // auto calling cancelFocusAndMetering in 4 seconds
            .setAutoCancelDuration(4, TimeUnit.SECONDS)
            .build()

        cameraControl.startFocusAndMetering(action)
    }

    /**
     * 根据传入的值获取相机应该设置的分辨率比例
     */
    private fun aspectRatio(width: Int, height: Int): Int {
        val previewRatio = max(width, height).toDouble() / min(width, height)
        if (abs(previewRatio - RATIO_4_3_VALUE) <= abs(previewRatio - RATIO_16_9_VALUE)) {
            return AspectRatio.RATIO_4_3
        }
        return AspectRatio.RATIO_16_9
    }

    /**
     * 获取码类型
     */
    private fun getCodeType(barcodeFormat: BarcodeFormat): Int {
        if (DecodeFormatManager.TWO_CODE.contains(barcodeFormat)) {
            return ScanType.CODE_TWO
        }
        if (DecodeFormatManager.ONE_CODE.contains(barcodeFormat)) {
            return ScanType.CODE_ONE
        }
        return ScanType.UN_KNOW
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdownNow()
        baseScanView?.cancelAnim()
    }

    override fun onBackCode(result: Result) {
        val intent = Intent()
        intent.putExtra(
            ScanCodeConfig.CODE_TYPE,
            getCodeType(result.barcodeFormat)
        )
        intent.putExtra(ScanCodeConfig.CODE_KEY, result.text)
        setResult(Activity.RESULT_OK, intent)
        finish()
    }
}