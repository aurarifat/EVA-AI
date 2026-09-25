package com.example.eva.tools

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter

object QrTools {

    fun generateQrBitmap(content: String, size: Int = 512): Bitmap? {
        if (content.isBlank()) return null
        return try {
            val writer = QRCodeWriter()
            val bitMatrix = writer.encode(content, BarcodeFormat.QR_CODE, size, size)
            val width = bitMatrix.width
            val height = bitMatrix.height
            val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
            for (x in 0 until width) {
                for (y in 0 until height) {
                    bmp.setPixel(x, y, if (bitMatrix.get(x, y)) Color.BLACK else Color.WHITE)
                }
            }
            bmp
        } catch (_: Exception) {
            null
        }
    }

    fun buildWifiQrString(ssid: String, pass: String, securityType: String = "WPA"): String {
        return "WIFI:S:$ssid;T:$securityType;P:$pass;;"
    }

    fun buildContactQrString(name: String, phone: String, email: String = ""): String {
        return "MECARD:N:$name;TEL:$phone;EMAIL:$email;;"
    }
}
