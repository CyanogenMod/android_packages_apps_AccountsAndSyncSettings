/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.settings;

import com.android.providers.subscribedfeeds.R;

import android.app.ActivityThread;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ActiveSyncInfo;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.DialogInterface;
import android.content.IContentService;
import android.content.ISyncStatusObserver;
import android.content.Intent;
import android.content.SyncStatusInfo;
import android.content.SyncStorageEngine;
import android.content.pm.ProviderInfo;
import android.net.ConnectivityManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.RemoteException;
import android.text.format.DateFormat;
import android.preference.CheckBoxPreference;
import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.preference.PreferenceScreen;
import android.provider.Gmail;
import android.provider.SubscribedFeeds;
import android.text.TextUtils;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Observable;
import java.util.Observer;

import com.google.android.googlelogin.GoogleLoginServiceConstants;
import com.google.android.googlelogin.GoogleLoginServiceHelper;

public class SyncSettings extends PreferenceActivity {

    IContentService    mContentService;
    List<String>       mProviderNames;
    List<ProviderInfo> mProviderInfos;

    CheckBoxPreference mAutoSyncCheckBox;
    TextView mErrorInfoView;

    java.text.DateFormat mDateFormat;
    java.text.DateFormat mTimeFormat;

    final Handler mHandler = new Handler();
    
    private static final String SYNC_CONNECTION_SETTING_CHANGED
        = "com.android.sync.SYNC_CONN_STATUS_CHANGED";

    private static final String BACKGROUND_DATA_SETTING_CHANGED
        = "com.android.sync.BG_DATA_STATUS_CHANGED";

    private static final String SYNC_KEY_PREFIX = "sync_";
    private static final String SYNC_CHECKBOX_KEY = "autoSyncCheckBox";
    private static final String BACKGROUND_DATA_CHECKBOX_KEY = "backgroundDataCheckBox";

    private static final String BACKGROUND_DATA_INTENT_EXTRA_NAME = "value";

    private static final int MENU_SYNC_NOW_ID = Menu.FIRST;
    private static final int MENU_SYNC_CANCEL_ID = Menu.FIRST + 1;
    private static final int GET_ACCOUNT_REQUEST = 14376;

    private static final int DIALOG_DISABLE_BACKGROUND_DATA = 1;
    
    @Override
    protected void onCreate(Bundle icicle) {
        super.onCreate(icicle);
        
        mContentService = ContentResolver.getContentService();
        
        setContentView(R.layout.sync_settings_list_content);
        addPreferencesFromResource(R.xml.sync_settings);

        mErrorInfoView = (TextView)findViewById(R.id.sync_settings_error_info);
        mErrorInfoView.setVisibility(View.GONE);
        mErrorInfoView.setCompoundDrawablesWithIntrinsicBounds(
                getResources().getDrawable(R.drawable.ic_list_syncerror), null, null, null);

        mDateFormat = DateFormat.getDateFormat(this);
        mTimeFormat = DateFormat.getTimeFormat(this);

        mAutoSyncCheckBox = (CheckBoxPreference) findPreference(SYNC_CHECKBOX_KEY);
        
        if (icicle == null) checkForAccount(); // First launch only; ignore orientation changes.
        
        initProviders();
        initUI();
    }
    
    private void checkForAccount() {
        // This will request a Gmail account and if none present will invoke SetupWizard
        // to login or create a new one. The result is returned through onActivityResult().
        Bundle bundle = new Bundle();
        bundle.putCharSequence("optional_message", getText(R.string.sync_plug));
        GoogleLoginServiceHelper.getCredentials(
                this, 
                GET_ACCOUNT_REQUEST, 
                bundle,
                GoogleLoginServiceConstants.PREFER_HOSTED, 
                Gmail.GMAIL_AUTH_SERVICE, 
                true);
    }
    
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == GET_ACCOUNT_REQUEST) {
            if (resultCode != RESULT_OK) {
                // The user canceled and there are no accounts. Just return to the previous
                // settings page...
                finish();
            }
        } 
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        super.onCreateOptionsMenu(menu);
        menu.add(0, MENU_SYNC_NOW_ID, 0, getString(R.string.sync_menu_sync_now))
                .setIcon(com.android.internal.R.drawable.ic_menu_refresh);
        menu.add(0, MENU_SYNC_CANCEL_ID, 0, getString(R.string.sync_menu_sync_cancel))
                .setIcon(android.R.drawable.ic_menu_close_clear_cancel);
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        super.onPrepareOptionsMenu(menu);
        boolean syncActive = false;
        try {
            syncActive = mContentService.getActiveSync() != null;
        } catch (RemoteException e) {
        }
        menu.findItem(MENU_SYNC_NOW_ID).setVisible(!syncActive);
        menu.findItem(MENU_SYNC_CANCEL_ID).setVisible(syncActive);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case MENU_SYNC_NOW_ID:
                startSyncForEnabledProviders();
                return true;
            case MENU_SYNC_CANCEL_ID:
                cancelSyncForEnabledProviders();
                return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void initProviders() {
        mProviderNames = new ArrayList<String>();
        mProviderInfos = new ArrayList<ProviderInfo>();

        try {
            ActivityThread.getPackageManager().querySyncProviders(mProviderNames,
                    mProviderInfos);
        } catch (RemoteException e) {
        }
        /*
        for (int i = 0; i < mProviderNames.size(); i++) {
            Log.i("SyncProviders", mProviderNames.get(i));
        }
        */
    }

    @Override
    protected void onResume() {
        super.onResume();
        try {
            mContentService.addStatusChangeListener(
                    SyncStorageEngine.CHANGE_ACTIVE
                    | SyncStorageEngine.CHANGE_STATUS
                    | SyncStorageEngine.CHANGE_SETTINGS,
                    mSyncStatusObserver);
        } catch (RemoteException e) {
        }
        onSyncStateUpdated();
    }

    @Override
    protected void onPause() {
        super.onPause();
        try {
            mContentService.removeStatusChangeListener(mSyncStatusObserver);
        } catch (RemoteException e) {
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
    }

    private void initUI() {
        // Set background connection state
        CheckBoxPreference backgroundData =
            (CheckBoxPreference) findPreference(BACKGROUND_DATA_CHECKBOX_KEY);
        ConnectivityManager connManager =
                (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        backgroundData.setChecked(connManager.getBackgroundDataSetting());

        // Set the Auto Sync toggle state
        CheckBoxPreference autoSync = (CheckBoxPreference) findPreference(SYNC_CHECKBOX_KEY);
        try {
            autoSync.setChecked(mContentService.getListenForNetworkTickles());
        } catch (RemoteException e) {
        }
        setOneTimeSyncMode(!autoSync.isChecked());

        // Find individual sync provider's states and initialize the toggles
        int i;
        int count = getPreferenceScreen().getPreferenceCount();
        for (i = 0; i < count; i++) {
            Preference pref = getPreferenceScreen().getPreference(i);
            if (pref.hasKey() && pref.getKey().startsWith(SYNC_KEY_PREFIX)) {
                CheckBoxPreference toggle = (CheckBoxPreference) pref;
                String providerName = toggle.getKey().substring(SYNC_KEY_PREFIX.length());
                boolean enabled = false;
                try {
                    enabled = mContentService.getSyncProviderAutomatically(providerName);
                } catch (RemoteException e) {
                }
                toggle.setChecked(enabled);
            }
        }

    }

    @Override
    public boolean onPreferenceTreeClick(PreferenceScreen preferences, Preference preference) {
        CheckBoxPreference togglePreference = (CheckBoxPreference) preference;
        String key = preference.getKey();
        if (key.equals(BACKGROUND_DATA_CHECKBOX_KEY)) {
            ConnectivityManager connManager =
                    (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            boolean oldBackgroundDataSetting = connManager.getBackgroundDataSetting();
            boolean backgroundDataSetting = togglePreference.isChecked();
            if (oldBackgroundDataSetting != backgroundDataSetting) {
                if (backgroundDataSetting) {
                    setBackgroundDataInt(true);
                } else {
                    confirmDisablingOfBackgroundData(togglePreference);
                }
            }
        } else if (key.equals(SYNC_CHECKBOX_KEY)) {
            boolean oldListenForTickles = false;
            try {
                oldListenForTickles = mContentService.getListenForNetworkTickles();
            } catch (RemoteException e) {
            }
            boolean listenForTickles = togglePreference.isChecked();
            if (oldListenForTickles != listenForTickles) {
                try {
                    mContentService.setListenForNetworkTickles(listenForTickles);
                } catch (RemoteException e) {
                }
                Intent intent = new Intent();
                intent.setAction(SYNC_CONNECTION_SETTING_CHANGED);
                sendBroadcast(intent);
                if (listenForTickles) {
                    startSyncForEnabledProviders();
                }
            }
            if (!listenForTickles) {
                cancelSyncForEnabledProviders();
            }
            setOneTimeSyncMode(!listenForTickles);
        } else if (key.startsWith(SYNC_KEY_PREFIX)) {
            SyncStateCheckBoxPreference syncPref = (SyncStateCheckBoxPreference) preference;
            String providerName = key.substring(SYNC_KEY_PREFIX.length());
            if (syncPref.isOneTimeSyncMode()) {
                startSync(providerName);
            } else {
                boolean syncOn = togglePreference.isChecked();
                boolean oldSyncState = false;
                try {
                    oldSyncState = mContentService.getSyncProviderAutomatically(providerName);
                } catch (RemoteException e) {
                }
                if (syncOn != oldSyncState) {
                    try {
                        mContentService.setSyncProviderAutomatically(providerName, syncOn);
                    } catch (RemoteException e) {
                    }
                    if (syncOn) {
                        startSync(providerName);
                    } else {
                        cancelSync(providerName);
                    }
                }
            }
        } else {
            return false;
        }
        return true;
    }

    private void setOneTimeSyncMode(boolean oneTimeSyncMode) {
        int count = getPreferenceScreen().getPreferenceCount();
        for (int i = 0; i < count; i++) {
            Preference pref = getPreferenceScreen().getPreference(i);
            if (pref.hasKey() && pref.getKey().startsWith(SYNC_KEY_PREFIX)) {
                SyncStateCheckBoxPreference syncPref = (SyncStateCheckBoxPreference) pref;
                syncPref.setOneTimeSyncMode(oneTimeSyncMode);
            }
        }
    }
    
    private void confirmDisablingOfBackgroundData(final CheckBoxPreference
            backgroundDataPreference) {
        // This will get unchecked only if the user hits "Ok"
        backgroundDataPreference.setChecked(true);
        showDialog(DIALOG_DISABLE_BACKGROUND_DATA);        
    }

    @Override
    protected Dialog onCreateDialog(int id) {
        switch (id) {
            case DIALOG_DISABLE_BACKGROUND_DATA:
                final CheckBoxPreference pref =
                    (CheckBoxPreference) findPreference(BACKGROUND_DATA_CHECKBOX_KEY); 
                return new AlertDialog.Builder(this)
                        .setTitle(R.string.background_data_dialog_title)
                        .setIcon(android.R.drawable.ic_dialog_alert)
                        .setMessage(R.string.background_data_dialog_message)
                        .setPositiveButton(android.R.string.ok,
                                    new DialogInterface.OnClickListener() {
                                public void onClick(DialogInterface dialog, int which) {
                                    setBackgroundDataInt(false);
                                    pref.setChecked(false);
                                }
                            })
                        .setNegativeButton(android.R.string.cancel, null)
                        .create();
        }
        
        return null;
    }

    private void setBackgroundDataInt(boolean enabled) {
        ConnectivityManager connManager =
                (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        connManager.setBackgroundDataSetting(enabled);
    }
    
    private void startSyncForEnabledProviders() {
        cancelOrStartSyncForEnabledProviders(true /* start them */);
    }

    private void cancelSyncForEnabledProviders() {
        cancelOrStartSyncForEnabledProviders(false /* cancel them */);
    }

    private void cancelOrStartSyncForEnabledProviders(boolean startSync) {
        int count = getPreferenceScreen().getPreferenceCount();
        for (int i = 0; i < count; i++) {
            Preference pref = getPreferenceScreen().getPreference(i);
            if (pref.hasKey() && pref.getKey().startsWith(SYNC_KEY_PREFIX)) {
                CheckBoxPreference toggle = (CheckBoxPreference) pref;
                if (!toggle.isChecked()) {
                    continue;
                }
                final String authority = toggle.getKey().substring(SYNC_KEY_PREFIX.length());
                if (startSync) {
                    startSync(authority);
                } else {
                    cancelSync(authority);
                }
            }
        }

        // treat SubscribedFeeds as an enabled provider
        final String authority = SubscribedFeeds.Feeds.CONTENT_URI.getAuthority();
        if (startSync) {
            startSync(authority);
        } else {
            cancelSync(authority);
        }
    }

    private void startSync(String providerName) {
        Uri uriToSync = (providerName != null) ? Uri.parse("content://" + providerName) : null;
        Bundle extras = new Bundle();
        extras.putBoolean(ContentResolver.SYNC_EXTRAS_FORCE, true);
        getContentResolver().startSync(uriToSync, extras);
    }

    private void cancelSync(String authority) {
        getContentResolver().cancelSync(Uri.parse("content://" + authority));
    }

    private ISyncStatusObserver mSyncStatusObserver = new ISyncStatusObserver.Stub() {
        public void onStatusChanged(int which) throws RemoteException {
            mHandler.post(new Runnable() {
                public void run() {
                    onSyncStateUpdated();
                }
            });
        }
    };

    private void onSyncStateUpdated() {
        // iterate over all the preferences, setting the state properly for each
        Date date = new Date();
        ActiveSyncInfo activeSyncValues = null;
        try {
            activeSyncValues = mContentService.getActiveSync();
        } catch (RemoteException e) {
        }
        boolean syncIsFailing = false;
        int count = getPreferenceScreen().getPreferenceCount();
        for (int i = 0; i < count; i++) {
            Preference pref = getPreferenceScreen().getPreference(i);
            final String prefKey = pref.getKey();
            if (!TextUtils.isEmpty(prefKey) && prefKey.startsWith(SYNC_KEY_PREFIX)) {
                String authority = prefKey.substring(SYNC_KEY_PREFIX.length());
                SyncStateCheckBoxPreference toggle = (SyncStateCheckBoxPreference)pref;
                SyncStatusInfo status = null;
                boolean syncEnabled = false;
                boolean authorityIsPending = false;
                try {
                    status = mContentService.getStatusByAuthority(authority);
                    syncEnabled = mContentService.getSyncProviderAutomatically(authority);
                    authorityIsPending = mContentService.isAuthorityPending(null, authority);
                } catch (RemoteException e) {
                }

                boolean activelySyncing = activeSyncValues != null
                        && activeSyncValues.authority.equals(authority);
                boolean lastSyncFailed = status != null
                        && status.lastFailureTime != 0
                        && status.getLastFailureMesgAsInt(0)
                           != SyncStorageEngine.ERROR_SYNC_ALREADY_IN_PROGRESS;
                if (!syncEnabled) lastSyncFailed = false;
                if (lastSyncFailed && !activelySyncing && !authorityIsPending) {
                    syncIsFailing = true;
                }
                final long successEndTime =
                    status == null ? 0 : status.lastSuccessTime;
                if (successEndTime != 0) {
                    date.setTime(successEndTime);
                    final String timeString = mDateFormat.format(date) + " "
                            + mTimeFormat.format(date);
                    toggle.setSummary(timeString);
                } else {
                    toggle.setSummary("");
                }
                toggle.setActive(activelySyncing);
                toggle.setPending(authorityIsPending);
                toggle.setFailed(lastSyncFailed);
            }
        }
        mErrorInfoView.setVisibility(syncIsFailing ? View.VISIBLE : View.GONE);
    }
}
