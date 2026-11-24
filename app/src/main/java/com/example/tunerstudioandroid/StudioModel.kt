package com.example.tunerstudioandroid

import android.app.Application
import androidx.lifecycle.AndroidViewModel

class StudioModel(application: Application) : AndroidViewModel(application) {
    var session = StudioSession(application)
    internal var usbReconnect: UsbReconnectTarget? = null
    override fun onCleared() { session.close() }
}
