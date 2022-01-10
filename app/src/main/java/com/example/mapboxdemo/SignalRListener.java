package com.example.mapboxdemo;

import android.util.Log;

import com.microsoft.signalr.HubConnection;
import com.microsoft.signalr.HubConnectionBuilder;

public class SignalRListener {
    private static SignalRListener instance;

    HubConnection hubConnection;

    public SignalRListener() {
        Log.i("TAG", "Constructor");
        hubConnection = HubConnectionBuilder.create("http://192.168.0.169:5005/update").build();
        hubConnection.on("UpdateMarker", (markerId) -> {
                MainActivity.instance.updateMarkerById(markerId);
        }, Integer.class);
        hubConnection.on("DeleteMarker", (markerId) -> {
            MainActivity.instance.removeMarkerById(markerId);
        }, Integer.class);
        hubConnection.on("AddMarker", (markerId) -> {
            Log.i("TAG", "AddMarker");
            MainActivity.instance.requestMarkerById(markerId);
        }, Integer.class);
    }

    public static SignalRListener getInstance() {
        if (instance == null)
            instance = new SignalRListener();
        return instance;
    }

    public void startConnection() {
        Log.i("TAG", "Starting hub connection");
        hubConnection.start();
    }

    public void stopConnection() {
        Log.i("TAG", "Stopping hub connection");
        hubConnection.stop();
    }
}
