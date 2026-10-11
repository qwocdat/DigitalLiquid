package com.digitalsmod.digitalliquid

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 48, 36, 36)
            setBackgroundColor(0xFF101114.toInt())
        }
        val title = TextView(this).apply {
            text = "DigitalLiquid"
            textSize = 30f
            setTextColor(0xFFFFFFFF.toInt())
        }
        val subtitle = TextView(this).apply {
            text = "Dynamic Island cho Android"
            textSize = 16f
            setTextColor(0xFFB9BBC3.toInt())
            setPadding(0, 8, 0, 28)
        }
        root.addView(title)
        root.addView(subtitle)

        root.addView(makeButton("1. Cấp quyền hiển thị trên ứng dụng khác") {
            if (!Settings.canDrawOverlays(this)) {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")))
            } else {
                showMessage("Quyền hiển thị nổi đã được cấp.")
            }
        })
        root.addView(makeButton("2. Bật quyền đọc thông báo (nhạc/thông báo)") {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        })
        root.addView(makeButton("3. Bật Dynamic Island") {
            if (!Settings.canDrawOverlays(this)) {
                showMessage("Hãy cấp quyền hiển thị nổi trước.")
            } else {
                val intent = Intent(this, IslandService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent)
                else startService(intent)
                showMessage("DigitalLiquid đang chạy. Vuốt viên thuốc xuống để thu gọn.")
            }
        })
        root.addView(makeButton("4. Tắt Dynamic Island") {
            stopService(Intent(this, IslandService::class.java))
        })
        root.addView(makeButton("5. Cài đặt quyền thông báo") {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        })

        val note = TextView(this).apply {
            text = "\nLưu ý: quyền thông báo và quyền hiển thị nổi chỉ được bật khi bạn tự cho phép trong Cài đặt Android."
            textSize = 14f
            setTextColor(0xFF9EA1AA.toInt())
        }
        root.addView(note)
        setContentView(root)
    }

    private fun makeButton(label: String, action: () -> Unit): Button =
        Button(this).apply {
            text = label
            isAllCaps = false
            setOnClickListener { action() }
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            params.bottomMargin = 12
            layoutParams = params
        }

    private fun showMessage(message: String) {
        AlertDialog.Builder(this)
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }
}
