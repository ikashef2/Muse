package com.kashef.archive

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.kashef.archive.ui.ArchiveApp
import com.kashef.archive.ui.theme.ArchiveTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ArchiveTheme {
                ArchiveApp()
            }
        }
    }
}
