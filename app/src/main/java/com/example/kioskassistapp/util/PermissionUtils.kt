// File: PermissionUtils.kt
package com.example.kioskassistapp.util

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

object PermissionUtils {

    const val CAMERA_PERMISSION_REQUEST_CODE = 100
    const val AUDIO_PERMISSION_REQUEST_CODE = 101
    const val ALL_PERMISSION_REQUEST_CODE = 102

    fun isCameraPermissionGranted(activity: Activity): Boolean {
        return ContextCompat.checkSelfPermission(
            activity,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun isAudioPermissionGranted(activity: Activity): Boolean {
        return ContextCompat.checkSelfPermission(
            activity,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

//    fun requestCameraPermission(activity: Activity) {
//        ActivityCompat.requestPermissions(
//            activity,
//            arrayOf(Manifest.permission.CAMERA),
//            CAMERA_PERMISSION_REQUEST_CODE
//        )
//    }
//
//    fun requestAudioPermission(activity: Activity) {
//        ActivityCompat.requestPermissions(
//            activity,
//            arrayOf(Manifest.permission.RECORD_AUDIO),
//            AUDIO_PERMISSION_REQUEST_CODE
//        )
//    }

    // 🔹 카메라 + 오디오 둘 다 체크
    fun hasAllPermissions(activity: Activity): Boolean {
        return isCameraPermissionGranted(activity) &&
                isAudioPermissionGranted(activity)
    }

    // 🔹 카메라 + 오디오 둘 다 한 번에 요청
    fun requestAllPermissions(activity: Activity) {
        ActivityCompat.requestPermissions(
            activity,
            arrayOf(
                Manifest.permission.CAMERA,
                Manifest.permission.RECORD_AUDIO
            ),
            ALL_PERMISSION_REQUEST_CODE
        )
    }
}
