/*******************************************************************************
 * Copyright 2011 See AUTHORS file.
 * <p/>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p/>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p/>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ******************************************************************************/

package com.badlogic.gdx.pay.android.googleplay;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;

import com.android.vending.billing.IInAppBillingService;
import com.badlogic.gdx.pay.Information;
import com.badlogic.gdx.pay.PurchaseManager;
import com.badlogic.gdx.pay.PurchaseManagerConfig;
import com.badlogic.gdx.pay.PurchaseObserver;
import com.badlogic.gdx.utils.Logger;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static com.badlogic.gdx.pay.android.googleplay.GetSkuDetailsRequestConverter.convertConfigToItemIdList;

/**
 * The purchase manager implementation for Google Play (Android).

 * @author noblemaster
 */
public class AndroidGooglePlayPurchaseManager implements PurchaseManager {

    public static final int BILLING_API_VERSION = 3;

    public static final String PURCHASE_TYPE_IN_APP = "inapp";
    public static final String LOG_TAG = "GdxPay/AndroidPlay";

    private Activity activity;

    private ServiceConnection inAppBillingServiceConnection;

    private IInAppBillingService inAppBillingService;

    Logger logger = new Logger(LOG_TAG);

    private final Map<String, Information> informationMap = new ConcurrentHashMap<>();

    @SuppressWarnings("UnusedParameters") // requestCode is set by IAP.java which auto-configures IAP.
    // not yet using it though (probably needed when doing purchases and restores).
    public AndroidGooglePlayPurchaseManager(Activity activity, int requestCode) {
        this.activity = activity;
    }

    @Override
    public void install(final PurchaseObserver observer, final PurchaseManagerConfig config, final boolean autoFetchInformation) {
        installChainBindService(observer, config);
    }

    /**
     * Used by IAP for automatic configuration of gdx-pay.
     */
    public static boolean isRunningViaGooglePlay(Activity activity) {
        // who installed us?
        String packageNameInstaller;
        try {
            // obtain the package name for the installer!
            packageNameInstaller = activity.getPackageManager().getInstallerPackageName(activity.getPackageName());

            // package name matches the string below if we were installed by Google Play!
            return packageNameInstaller.equals("com.android.vending");
        }
        catch (Throwable e) {
            // error: output to console (we usually shouldn't get here!)
            Log.e(LOG_TAG, "Cannot determine installer package name.", e);

            return false;
        }
    }




    private void installChainBindService(PurchaseObserver observer, PurchaseManagerConfig config) {
        try {
            inAppBillingServiceConnection = new BillingServiceInitializingServiceConnection(observer, config);

            if (!activity.bindService(createBindBillingServiceIntent(), inAppBillingServiceConnection, Context.BIND_AUTO_CREATE)) {
                observer.handleInstallError(new GdxPayInstallFailureException("Failed to bind to service", config));
            }
        } catch (Exception e) {
            observer.handleInstallError(new GdxPayInstallFailureException(e, config));
        }
    }

    protected void runAsync(Runnable runnable) {
        new Thread(runnable).start();
    }

    private Intent createBindBillingServiceIntent() {
        Intent serviceIntent = new Intent("com.android.vending.billing.InAppBillingService.BIND");
        serviceIntent.setPackage("com.android.vending");
        return serviceIntent;
    }

    private void loadInformationsViaSkus(final PurchaseObserver observer, final PurchaseManagerConfig purchaseManagerConfig) {
        runAsync(new Runnable() {
            @Override
            public void run() {
                try {
                    loadSkusAndFillPurchaseInformation(purchaseManagerConfig, observer);
                } catch (Exception e) {
                    // TODO: not yet unit tested.
                    observer.handleInstallError(new GdxPayInstallFailureException(e, purchaseManagerConfig));
                }
            }
        });
    }

    protected void loadSkusAndFillPurchaseInformation(PurchaseManagerConfig purchaseManagerConfig, PurchaseObserver observer) throws android.os.RemoteException {
        Bundle skusRequest = convertConfigToItemIdList(purchaseManagerConfig);
        logger.error("getSkuDetails("+BILLING_API_VERSION + ", " + activity.getPackageName() + ", " + skusRequest);

        Bundle skuDetailsResponse = inAppBillingService.getSkuDetails(BILLING_API_VERSION,
                activity.getPackageName(), PURCHASE_TYPE_IN_APP,
                skusRequest);

        informationMap.clear();
        informationMap.putAll(GetSkusDetailsResponseBundleToInformationConverter.convertSkuDetailsResponse(skuDetailsResponse));

        observer.handleInstall();
    }

    @Override
    public boolean installed() {
        return !informationMap.isEmpty();
    }

    @Override
    public void dispose() {
        unbindIfBound();
        clearCaches();
    }

    @Override
    public void purchase(String identifier) {
        // FIXME
    }

    @Override
    public void purchaseRestore() {
        // FIXME
    }

    @Override
    public Information getInformation(String identifier) {
        Information information = informationMap.get(identifier);

        if (information == null) {
            return Information.UNAVAILABLE;
        }

        return information;
    }

    @Override
    public String storeName() {
        return PurchaseManagerConfig.STORE_NAME_ANDROID_GOOGLE;
    }


    private void clearCaches() {
        informationMap.clear();
    }

    private void unbindIfBound() {
        if (inAppBillingServiceConnection != null) {
            activity.unbindService(inAppBillingServiceConnection);
        }
    }

    private void onServiceConnected(PurchaseObserver observer, PurchaseManagerConfig config) {
        loadInformationsViaSkus(observer, config);
    }

    protected IInAppBillingService lookupByStubAsInterface(IBinder service) {
        return IInAppBillingService.Stub.asInterface(service);
    }

    private class BillingServiceInitializingServiceConnection implements ServiceConnection {
        private final PurchaseObserver observer;
        private final PurchaseManagerConfig config;

        public BillingServiceInitializingServiceConnection(PurchaseObserver observer, PurchaseManagerConfig config) {
            this.observer = observer;
            this.config = config;
        }

        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            inAppBillingService = lookupByStubAsInterface(service);

            AndroidGooglePlayPurchaseManager.this.onServiceConnected(observer, config);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            inAppBillingService = null;
        }
    }
}