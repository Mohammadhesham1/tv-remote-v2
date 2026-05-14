package com.tvremote;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * Discovers Android TV devices on the local network using mDNS / NSD.
 * The Android TV Remote Service advertises itself as "_androidtvremote2._tcp"
 */
public class AtvDiscovery {

    private static final String TAG = "AtvDiscovery";
    private static final String SERVICE_TYPE = "_androidtvremote2._tcp.";

    public static class TvDevice {
        public final String name;
        public final String host;
        public final int port;

        public TvDevice(String name, String host, int port) {
            this.name = name;
            this.host = host;
            this.port = port;
        }

        @Override public String toString() {
            return name + " (" + host + ")";
        }
    }

    public interface DiscoveryListener {
        void onDeviceFound(TvDevice device);
        void onDeviceLost(String name);
        void onError(String message);
    }

    private final NsdManager nsdManager;
    private final DiscoveryListener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private NsdManager.DiscoveryListener discoveryListener;
    private boolean running = false;

    public AtvDiscovery(Context ctx, DiscoveryListener listener) {
        this.nsdManager = (NsdManager) ctx.getSystemService(Context.NSD_SERVICE);
        this.listener = listener;
    }

    public void startDiscovery() {
        if (running) return;
        running = true;

        discoveryListener = new NsdManager.DiscoveryListener() {
            @Override
            public void onStartDiscoveryFailed(String serviceType, int errorCode) {
                Log.e(TAG, "Discovery start failed: " + errorCode);
                mainHandler.post(() -> listener.onError("فشل البحث: " + errorCode));
                running = false;
            }

            @Override
            public void onStopDiscoveryFailed(String serviceType, int errorCode) {
                Log.e(TAG, "Discovery stop failed: " + errorCode);
            }

            @Override
            public void onDiscoveryStarted(String serviceType) {
                Log.d(TAG, "Discovery started for: " + serviceType);
            }

            @Override
            public void onDiscoveryStopped(String serviceType) {
                Log.d(TAG, "Discovery stopped");
                running = false;
            }

            @Override
            public void onServiceFound(NsdServiceInfo serviceInfo) {
                Log.d(TAG, "Service found: " + serviceInfo.getServiceName());
                // Resolve to get IP + port
                nsdManager.resolveService(serviceInfo, new NsdManager.ResolveListener() {
                    @Override
                    public void onResolveFailed(NsdServiceInfo info, int errorCode) {
                        Log.e(TAG, "Resolve failed: " + errorCode);
                    }

                    @Override
                    public void onServiceResolved(NsdServiceInfo info) {
                        String host = info.getHost().getHostAddress();
                        int port = info.getPort();
                        String name = info.getServiceName();
                        Log.d(TAG, "Resolved: " + name + " at " + host + ":" + port);
                        TvDevice device = new TvDevice(name, host, port);
                        mainHandler.post(() -> listener.onDeviceFound(device));
                    }
                });
            }

            @Override
            public void onServiceLost(NsdServiceInfo serviceInfo) {
                Log.d(TAG, "Service lost: " + serviceInfo.getServiceName());
                mainHandler.post(() -> listener.onDeviceLost(serviceInfo.getServiceName()));
            }
        };

        nsdManager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener);
    }

    public void stopDiscovery() {
        if (!running || discoveryListener == null) return;
        try {
            nsdManager.stopServiceDiscovery(discoveryListener);
        } catch (Exception e) {
            Log.e(TAG, "Stop discovery error: " + e.getMessage());
        }
        running = false;
    }
}
