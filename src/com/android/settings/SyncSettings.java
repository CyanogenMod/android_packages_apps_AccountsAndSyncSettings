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
import com.google.android.googlelogin.GoogleLoginServiceConstants;
import com.google.android.collect.Maps;

import android.accounts.AccountManager;
import android.accounts.AuthenticatorException;
import android.accounts.Future2;
import android.accounts.Future2Callback;
import android.accounts.OperationCanceledException;
import android.accounts.Account;
import android.accounts.OnAccountsUpdatedListener;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ActiveSyncInfo;
import android.content.ContentResolver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SyncStatusInfo;
import android.content.SyncAdapterType;
import android.content.SyncStatusObserver;
import android.net.ConnectivityManager;
import android.os.Bundle;
import android.os.Handler;
import android.text.format.DateFormat;
import android.preference.CheckBoxPreference;
import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.preference.PreferenceScreen;
import android.provider.Gmail;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;
import android.util.Log;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.io.IOException;

public class SyncSettings extends PreferenceActivity implements OnAccountsUpdatedListener {
    CheckBoxPreference mBackgroundDataCheckBox;
    CheckBoxPreference mMasterAutoSyncCheckBox;
    TextView mErrorInfoView;

    java.text.DateFormat mDateFormat;
    java.text.DateFormat mTimeFormat;

    final Handler mHandler = new Handler();

  private static final String MASTER_AUTO_SYNC_CHECKBOX_KEY = "autoSyncCheckBox";
    private static final String BACKGROUND_DATA_CHECKBOX_KEY = "backgroundDataCheckBox";

    private static final int MENU_SYNC_NOW_ID = Menu.FIRST;
    private static final int MENU_SYNC_CANCEL_ID = Menu.FIRST + 1;

    private static final int DIALOG_DISABLE_BACKGROUND_DATA = 1;
    private static final String TAG = "SyncSettings";

    @Override
    protected void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        setContentView(R.layout.sync_settings_list_content);
        addPreferencesFromResource(R.xml.sync_settings);

        mErrorInfoView = (TextView)findViewById(R.id.sync_settings_error_info);
        mErrorInfoView.setVisibility(View.GONE);
        mErrorInfoView.setCompoundDrawablesWithIntrinsicBounds(
                getResources().getDrawable(R.drawable.ic_list_syncerror), null, null, null);

        mDateFormat = DateFormat.getDateFormat(this);
        mTimeFormat = DateFormat.getTimeFormat(this);

        mMasterAutoSyncCheckBox =
                (CheckBoxPreference) findPreference(MASTER_AUTO_SYNC_CHECKBOX_KEY);
        mBackgroundDataCheckBox = (CheckBoxPreference) findPreference(BACKGROUND_DATA_CHECKBOX_KEY);

        AccountManager.get(this).addOnAccountsUpdatedListener(this, null, true);
    }

    ArrayList<SyncStateCheckBoxPreference> mCheckBoxen =
            new ArrayList<SyncStateCheckBoxPreference>();

    private void addSyncStateCheckBox(Account account, String authority) {
        SyncStateCheckBoxPreference item =
                new SyncStateCheckBoxPreference(this, account, authority);
        item.setPersistent(false);
//        item.setDependency("backgroundDataCheckBox");
        final String name = authority + ", " + account.mName + ", " + account.mType;
        item.setTitle(name);
        item.setKey(name);
        getPreferenceScreen().addPreference(item);
        mCheckBoxen.add(item);
    }

    private void checkForAccount() {
        // This will request a Gmail account and if none present will invoke SetupWizard
        // to login or create a new one. The result is returned through onActivityResult().
        Bundle bundle = new Bundle();
        bundle.putCharSequence("optional_message", getText(R.string.sync_plug));
        AccountManager.get(this).getAuthTokenByFeatures(
                    GoogleLoginServiceConstants.ACCOUNT_TYPE, Gmail.GMAIL_AUTH_SERVICE,
                    new String[]{GoogleLoginServiceConstants.FEATURE_GOOGLE_OR_DASHER}, this,
                    bundle, null /* loginOptions */, new Future2Callback() {
            public void run(Future2 future) {
                try {
                    // do this to check if this request succeeded or not
                    future.getResult();
                    // nothing to do here
                } catch (OperationCanceledException e) {
                    // The user canceled and there are no accounts. Just return to the previous
                    // settings page...
                    finish();
                } catch (IOException e) {
                    // The request failed and there are no accounts. Just return to the previous
                    // settings page...
                    finish();
                } catch (AuthenticatorException e) {
                    // The request failed and there are no accounts. Just return to the previous
                    // settings page...
                    finish();
                }
            }
        }, null /* handler */);
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
        boolean syncActive = ContentResolver.getActiveSync() != null;
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

    Object mStatusChangeListenerHandle;

    @Override
    protected void onResume() {
        super.onResume();
        mStatusChangeListenerHandle = ContentResolver.addStatusChangeListener(
                ContentResolver.SYNC_OBSERVER_TYPE_ACTIVE
                | ContentResolver.SYNC_OBSERVER_TYPE_STATUS
                | ContentResolver.SYNC_OBSERVER_TYPE_SETTINGS,
                mSyncStatusObserver);
        onSyncStateUpdated();
    }

    @Override
    protected void onPause() {
        super.onPause();
        ContentResolver.removeStatusChangeListener(mStatusChangeListenerHandle);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
    }

    @Override
    public boolean onPreferenceTreeClick(PreferenceScreen preferences, Preference preference) {
        if (preference == mBackgroundDataCheckBox) {
            ConnectivityManager connManager =
                    (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            boolean oldBackgroundDataSetting = connManager.getBackgroundDataSetting();
            boolean backgroundDataSetting = mBackgroundDataCheckBox.isChecked();
            if (oldBackgroundDataSetting != backgroundDataSetting) {
                if (backgroundDataSetting) {
                    setBackgroundDataInt(true);
                } else {
                    // This will get unchecked only if the user hits "Ok"
                    mBackgroundDataCheckBox.setChecked(true);
                    showDialog(DIALOG_DISABLE_BACKGROUND_DATA);
                }
            }
        } else if (preference == mMasterAutoSyncCheckBox) {
            boolean oldMasterSyncAutomatically = ContentResolver.getMasterSyncAutomatically();
            boolean newMasterSyncAutomatically = mMasterAutoSyncCheckBox.isChecked();
            if (oldMasterSyncAutomatically != newMasterSyncAutomatically) {
                ContentResolver.setMasterSyncAutomatically(newMasterSyncAutomatically);
                if (newMasterSyncAutomatically) {
                    startSyncForEnabledProviders();
                }
            }
            if (!newMasterSyncAutomatically) {
                cancelSyncForEnabledProviders();
            }
            setOneTimeSyncMode(!newMasterSyncAutomatically);
        } else if (preference instanceof SyncStateCheckBoxPreference) {
            SyncStateCheckBoxPreference syncPref = (SyncStateCheckBoxPreference) preference;
            String authority = syncPref.getAuthority();
            Account account = syncPref.getAccount();
            if (syncPref.isOneTimeSyncMode()) {
                requestOrCancelSync(account, authority, true);
            } else {
                boolean syncOn = syncPref.isChecked();
                boolean oldSyncState = ContentResolver.getSyncAutomatically(account, authority);
                if (syncOn != oldSyncState) {
                    ContentResolver.setSyncAutomatically(account, authority, syncOn);
                    requestOrCancelSync(account, authority, syncOn);
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
            if (pref instanceof SyncStateCheckBoxPreference) {
                SyncStateCheckBoxPreference syncPref = (SyncStateCheckBoxPreference) pref;
                syncPref.setOneTimeSyncMode(oneTimeSyncMode);
            }
        }
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
        requestOrCancelSyncForEnabledProviders(true /* start them */);
    }

    private void cancelSyncForEnabledProviders() {
        requestOrCancelSyncForEnabledProviders(false /* cancel them */);
    }

    private void requestOrCancelSyncForEnabledProviders(boolean startSync) {
        int count = getPreferenceScreen().getPreferenceCount();
        for (int i = 0; i < count; i++) {
            Preference pref = getPreferenceScreen().getPreference(i);
            if (! (pref instanceof SyncStateCheckBoxPreference)) {
                continue;
            }
            SyncStateCheckBoxPreference syncPref = (SyncStateCheckBoxPreference) pref;
            if (!syncPref.isChecked()) {
                continue;
            }
            requestOrCancelSync(syncPref.getAccount(), syncPref.getAuthority(), startSync);
        }
    }

    private void requestOrCancelSync(Account account, String authority, boolean flag) {
        if (flag) {
            Bundle extras = new Bundle();
            extras.putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true);
            ContentResolver.requestSync(account, authority, extras);
        } else {
            ContentResolver.cancelSync(account, authority);
        }
    }

    private SyncStatusObserver mSyncStatusObserver = new SyncStatusObserver() {
        public void onStatusChanged(int which) {
            mHandler.post(new Runnable() {
                public void run() {
                    onSyncStateUpdated();
                }
            });
        }
    };

    private void onSyncStateUpdated() {
        // Set background connection state
        ConnectivityManager connManager =
                (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        mBackgroundDataCheckBox.setChecked(connManager.getBackgroundDataSetting());
        final boolean masterSyncAutomatically = ContentResolver.getMasterSyncAutomatically();
        mMasterAutoSyncCheckBox.setChecked(masterSyncAutomatically);

        // iterate over all the preferences, setting the state properly for each
        Date date = new Date();
        ActiveSyncInfo activeSyncValues = ContentResolver.getActiveSync();
        boolean syncIsFailing = false;
        for (int i = 0, count = getPreferenceScreen().getPreferenceCount(); i < count; i++) {
            Preference pref = getPreferenceScreen().getPreference(i);
            if (! (pref instanceof SyncStateCheckBoxPreference)) {
                continue;
            }
            SyncStateCheckBoxPreference syncPref = (SyncStateCheckBoxPreference) pref;

            String authority = syncPref.getAuthority();
            Account account = syncPref.getAccount();

            SyncStatusInfo status = ContentResolver.getSyncStatus(account, authority);
            boolean syncEnabled = ContentResolver.getSyncAutomatically(account, authority);
            boolean authorityIsPending = ContentResolver.isSyncPending(account, authority);

            boolean activelySyncing = activeSyncValues != null
                    && activeSyncValues.authority.equals(authority)
                    && activeSyncValues.account.equals(account);
            boolean lastSyncFailed = status != null
                    && status.lastFailureTime != 0
                    && status.getLastFailureMesgAsInt(0)
                       != ContentResolver.SYNC_ERROR_SYNC_ALREADY_IN_PROGRESS;
            if (!syncEnabled) lastSyncFailed = false;
            if (lastSyncFailed && !activelySyncing && !authorityIsPending) {
                syncIsFailing = true;
            }
            final long successEndTime = (status == null) ? 0 : status.lastSuccessTime;
            if (successEndTime != 0) {
                date.setTime(successEndTime);
                final String timeString = mDateFormat.format(date) + " "
                        + mTimeFormat.format(date);
                syncPref.setSummary(timeString);
            } else {
                syncPref.setSummary("");
            }
            syncPref.setActive(activelySyncing);
            syncPref.setPending(authorityIsPending);
            syncPref.setFailed(lastSyncFailed);
            syncPref.setOneTimeSyncMode(!masterSyncAutomatically);
            syncPref.setChecked(syncEnabled);
        }
        mErrorInfoView.setVisibility(syncIsFailing ? View.VISIBLE : View.GONE);
    }

    public void onAccountsUpdated(Account[] accounts) {
        SyncAdapterType[] syncAdapters = ContentResolver.getSyncAdapterTypes();
        HashMap<String, ArrayList<String>> accountTypeToAuthorities = Maps.newHashMap();
        for (int i = 0, n = syncAdapters.length; i < n; i++) {
            final SyncAdapterType sa = syncAdapters[i];
            ArrayList<String> authorities = accountTypeToAuthorities.get(sa.accountType);
            if (authorities == null) {
                authorities = new ArrayList<String>();
                accountTypeToAuthorities.put(sa.accountType, authorities);
            }
            Log.d(TAG, "added authority " + sa.authority + " to accountType " + sa.accountType);
            authorities.add(sa.authority);
        }

        for (int i = 0, n = mCheckBoxen.size(); i < n; i++) {
            getPreferenceScreen().removePreference(mCheckBoxen.get(i));
        }
        mCheckBoxen.clear();

        for (int i = 0, n = accounts.length; i < n; i++) {
            final Account account = accounts[i];
            Log.d(TAG, "looking for sync adapters that match account " + account);
            final ArrayList<String> authorities = accountTypeToAuthorities.get(account.mType);
            if (authorities != null) {
                for (int j = 0, m = authorities.size(); j < m; j++) {
                    final String authority = authorities.get(j);
                    Log.d(TAG, "  found authority " + authority);
                    addSyncStateCheckBox(account, authority);
                }
            }
        }

        onSyncStateUpdated();
    }
}
