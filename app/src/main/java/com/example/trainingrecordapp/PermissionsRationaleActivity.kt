package com.example.trainingrecordapp

import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

class PermissionsRationaleActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.health_connect_permission_title))
            .setMessage(getString(R.string.health_connect_permissions_rationale_detail))
            .setCancelable(false)
            .setPositiveButton(getString(android.R.string.ok)) { _, _ ->
                finish()
            }
            .setOnDismissListener {
                finish()
            }
            .show()
    }
}
