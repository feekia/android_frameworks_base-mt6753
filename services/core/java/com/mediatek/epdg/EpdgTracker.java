/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.mediatek.epdg;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.NetworkCapabilities;
import android.net.NetworkFactory;
import android.net.NetworkInfo;
import android.net.NetworkRequest;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.INetworkManagementService;
import android.os.Looper;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemProperties;
import android.util.Slog;

import com.android.server.net.BaseNetworkObserver;

import java.io.FileDescriptor;
import java.io.PrintWriter;

/**
 * Manages ePDG connections over Wi-Fi interface.
 *
 * @hide
 */
class EpdgTracker {
    private static final String NETWORK_TYPE = "EpdgNetworkFactory";
    private static final String FACTORY_NAME = "Epdg";
    private static final String TAG = "EpdgTracker";
    private static final String WIFI_INTERFACE = "wifi.interface";
    private static final boolean DBG = true;

    private static final int EPDG_LOW_SCORE = -1;

    /** Tracks interface changes. Called from NetworkManagementService. */
    private InterfaceObserver mInterfaceObserver;

    /** To set link state and configure IP addresses. */
    private INetworkManagementService mNMService;

    /* To communicate with ConnectivityManager */
    private NetworkCapabilities mNetworkCapabilities;
    private EpdgNetworkFactory mFactory;
    private Context mContext;

    private EpdgConnection[] mEpdgConnections;
    private static EpdgConnector sEpdgConnector;

    /** Data members. All accesses to these must be synchronized(this). */
    private static String sIface = "";

    EpdgTracker() {
        sEpdgConnector = EpdgConnector.getInstance();
        mEpdgConnections = new EpdgConnection[EpdgManager.MAX_NETWORK_NUM + 1];

        sIface = SystemProperties.get(WIFI_INTERFACE, "wlan0");

        initNetworkCapabilities();
    }

    private final BroadcastReceiver mIntentReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {

            final String action = intent.getAction();

            if (action.equals(WifiManager.NETWORK_STATE_CHANGED_ACTION)) {
                NetworkInfo info = intent.getParcelableExtra(WifiManager.EXTRA_NETWORK_INFO);
                boolean connected = info.isConnected();
                if (connected) {
                    for (int i = 0; i < EpdgManager.MAX_NETWORK_NUM; i++) {
                        mEpdgConnections[i].notifyWifiConnected();
                    }
                }
            }
        }
    };

    /**
    * Handle network reqeust for ConnectivityService in EPDG.
    *
    * @hide
    */
    private class EpdgNetworkFactory extends NetworkFactory {
        EpdgNetworkFactory(String name, Context context, Looper looper) {
            super(looper, context, name, new NetworkCapabilities());
        }

        public boolean acceptRequest(NetworkRequest request, int score) {
            if (score == EpdgConstants.NETWORK_LOW_SCORE) {
                log("Can't accept this request");
                return false;
            }
            return true;
        }

        /**
         * figure out the apn type and enable it.
         *
         * @param networkRequest the network request.
         * @param score the score value.
         */
        @Override
        protected void needNetworkFor(NetworkRequest networkRequest, int score) {
            // figure out the apn type and enable it
            if (DBG) {
                log("EPDG needs Network for " + networkRequest);
            }

            EpdgConnection epdgConnection = epdgContextForNetworkRequest(networkRequest);

            if (epdgConnection != null) {
                epdgConnection.incRefCount();
            }
        }

        /**
         * release the network request.
         *
         * @param networkRequest the network request.
         */
        @Override
        protected void releaseNetworkFor(NetworkRequest networkRequest) {
            if (DBG) {
                log("EPDG releasing Network for " + networkRequest);
            }

            EpdgConnection epdgConnection = epdgContextForNetworkRequest(networkRequest);

            if (epdgConnection != null) {
                epdgConnection.decRefCount();
            }
        }

        private EpdgConnection epdgContextForNetworkRequest(NetworkRequest nr) {
            NetworkCapabilities nc = nr.networkCapabilities;

            // for now, ignore the bandwidth stuff
            if (nc.getTransportTypes().length > 0 &&
                    nc.hasTransport(NetworkCapabilities.TRANSPORT_EPDG) == false) {
                return null;
            }

            // in the near term just do 1-1 matches.
            // TODO - actually try to match the set of capabilities
            int type = -1;
            boolean error = false;

            if (nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_IMS)) {
                type = EpdgManager.TYPE_IMS;
            }

            if (nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_MMS)) {
                type = EpdgManager.TYPE_FAST;
            }

            if (nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_SUPL)) {
                type = EpdgManager.TYPE_FAST;
            }

            if (nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_XCAP)) {
                type = EpdgManager.TYPE_FAST;
            }

            if (nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_RCS)) {
                type = EpdgManager.TYPE_IMS;
            }

            EpdgConnection epdgConnection = null;

            for (int i = 0; i < EpdgManager.MAX_NETWORK_NUM; i++) {
                if (mEpdgConnections[i].getApnType() == type) {
                    Slog.i(TAG, "Found:" + type);
                    epdgConnection = mEpdgConnections[i];
                    break;
                }
            }

            if (epdgConnection == null) {
                Slog.e(TAG, "Request for unsupported EPDG type: " + type);
            }

            return epdgConnection;
        }
    }

    /**
     *
     * Class for handle network interface event.
     *
     */
    private class InterfaceObserver extends BaseNetworkObserver {
        @Override
        public void interfaceLinkStateChanged(String iface, boolean up) {

        }

        @Override
        public void interfaceAdded(String iface) {

        }

        @Override
        public void interfaceRemoved(String iface) {

        }
    }

    /**
     * Begin monitoring connectivity.
     *
     * @param context the application context
     * @param target the handler thread
     */

    synchronized void start(Context context, Handler target) {
        // The services we use.
        IBinder b = ServiceManager.getService(Context.NETWORKMANAGEMENT_SERVICE);
        mNMService = INetworkManagementService.Stub.asInterface(b);

        for (int i = 0; i < EpdgManager.MAX_NETWORK_NUM; i++) {
            mEpdgConnections[i] = new EpdgConnection(i,
                                sEpdgConnector, target, sIface);
            mEpdgConnections[i].startMonitoring(context, target);
        }

        // Create and register our NetworkFactory.
        mFactory = new EpdgNetworkFactory(FACTORY_NAME, context, target.getLooper());
        mFactory.setCapabilityFilter(mNetworkCapabilities);
        mFactory.setScoreFilter(EpdgConstants.NETWORK_SCORE);
        mFactory.register();

        mContext = context;


        // Start tracking interface change events.

        mInterfaceObserver = new InterfaceObserver();

        try {
            IntentFilter intentFilter = new IntentFilter();
            intentFilter.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
            mContext.registerReceiver(mIntentReceiver, intentFilter);
            mNMService.registerObserver(mInterfaceObserver);
        } catch (RemoteException e) {
            Slog.e(TAG, "Could not register InterfaceObserver " + e);
        }
    }

    synchronized void stop() {
        sIface = "";
        mFactory.unregister();
    }

    private void initNetworkCapabilities() {
        mNetworkCapabilities = new NetworkCapabilities();
        mNetworkCapabilities.addTransportType(NetworkCapabilities.TRANSPORT_EPDG);
        mNetworkCapabilities.addCapability(NetworkCapabilities.NET_CAPABILITY_IMS);
        mNetworkCapabilities.addCapability(NetworkCapabilities.NET_CAPABILITY_MMS);
        mNetworkCapabilities.addCapability(NetworkCapabilities.NET_CAPABILITY_SUPL);
        mNetworkCapabilities.addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED);
    }

    int getReasonCode(int capabilityType) {
        int index = EpdgManager.TYPE_FAST;

        switch(capabilityType) {
            case NetworkCapabilities.NET_CAPABILITY_MMS:
            case NetworkCapabilities.NET_CAPABILITY_SUPL:
            case NetworkCapabilities.NET_CAPABILITY_XCAP:
                index = EpdgManager.TYPE_FAST;
                break;
            case NetworkCapabilities.NET_CAPABILITY_IMS:
            case NetworkCapabilities.NET_CAPABILITY_RCS:
                index = EpdgManager.TYPE_IMS;
                break;
            default:
                break;
        }

        return mEpdgConnections[index].getReasonCode();
    }

    EpdgConfig getConfiguration(int networkType) {
        return mEpdgConnections[networkType].getConfiguration();
    }

    EpdgConfig[] getAllConfiguration() {
        EpdgConfig[] configs = new EpdgConfig[EpdgManager.MAX_NETWORK_NUM];
        for (int i = 0; i < EpdgManager.MAX_NETWORK_NUM; i++) {
            configs[i] = mEpdgConnections[i].getConfiguration();
        }
        return configs;
    }

    void setConfiguration(int networkType, EpdgConfig config) {
        mEpdgConnections[networkType].setConfiguration(config);
    }

    void setAllConfiguration(EpdgConfig[] configs) {
        for (int i = 0; i < EpdgManager.MAX_NETWORK_NUM; i++) {
            mEpdgConnections[i].setConfiguration(configs[i]);
        }
    }

    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        for (int i = 0; i < EpdgManager.MAX_NETWORK_NUM; i++) {
            mEpdgConnections[i].dump(fd, pw, args);
        }
    }

}
