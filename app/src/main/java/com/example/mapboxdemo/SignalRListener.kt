package com.example.mapboxdemo

import android.util.Log
import com.microsoft.signalr.HubConnection
import com.microsoft.signalr.HubConnectionBuilder

class SignalRListenerK {
    private var hubConnection : HubConnection =
        HubConnectionBuilder.create("https://192.168.0.169:5006/update").build()

    init {
        hubConnection.on("UpdateMarker", { markerId ->
            MainActivity.instance.updateMarkerById(markerId)
        }, Int::class.java)
        hubConnection.on("DeleteMarker", { markerId ->
            MainActivity.instance.removeMarkerById(markerId)
        }, Int::class.java)
        hubConnection.on("AddMarker", { markerId ->
            Log.i("TAG", "AddMarker")
            MainActivity.instance.requestMarkerById(markerId)
        }, Int::class.java)
    }

    fun startConnection() {
        Log.i("TAG", "Started hub connection")
        hubConnection.start()
    }

    fun stopConnection() {
        hubConnection.stop()
    }
}