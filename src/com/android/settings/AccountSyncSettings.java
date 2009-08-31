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
import com.google.android.collect.Maps;

import android.accounts.AccountManager;
import android.accounts.Account;
import android.accounts.AccountManagerCallback;
import android.accounts.AccountManagerFuture;
import android.accounts.AuthenticatorException;
import android.accounts.OperationCanceledException;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ActiveSyncInfo;
import android.content.ContentResolver;
import android.content.DialogInterface;
import android.content.SyncStatusInfo;
import android.content.SyncAdapterType;
import android.content.pm.ProviderInfo;
import android.os.Bundle;
import android.os.Handler;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.preference.Preference;
import android.preference.PreferenceScreen;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.util.Log;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;

public class AccountSyncSettings extends AccountPreferenceBase implements OnClickListener {
    private static final String TAG = "SyncSettings";
    private static final String CHANGE_PASSWORD_KEY = "changePassword";
    private static final int MENU_SYNC_NOW_ID = Menu.FIRST;
    private static final int MENU_SYNC_CANCEL_ID = Menu.FIRST + 1;
    private static final int REALLY_REMOVE_DIALOG = 100;
    private static final int FAILED_REMOVAL_DIALOG = 101;
    private TextView mUserId;
    private TextView mProviderId;
    private ImageView mProviderIcon;
    private TextView mErrorInfoView;
    protected View mRemoveAccountArea;
    private java.text.DateFormat mDateFormat;
    private java.text.DateFormat mTimeFormat; 
    private Preference mAuthenticatorPreferences;
    private Account mAccount;
    private Button mRemoveAccountButton;
    
    public void onClick(View v) {
        if (v == mRemoveAccountButton) {
            showDialog(REALLY_REMOVE_DIALOG);
        }
    }

    @Override
    protected Dialog onCreateDialog(int id) {
        Dialog dialog = null;
        if (id == REALLY_REMOVE_DIALOG) {
            dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.really_remove_account_title)
                .setMessage(R.string.really_remove_account_message)
                .setPositiveButton(R.string.remove_account_label, 
                        new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int which) {
                        AccountManager.get(AccountSyncSettings.this).removeAccount(mAccount, 
                                new AccountManagerCallback<Boolean>() {
                            public void run(AccountManagerFuture<Boolean> future) {
                                boolean failed = true;
                                try {
                                    if (future.getResult() == true) {
                                        failed = false;
                                    }
                                } catch (OperationCanceledException e) {
                                    // ignore it
                                } catch (IOException e) {
                                    // TODO: retry?
                                } catch (AuthenticatorException e) {
                                    // TODO: retry?
                                }
                                if (failed) {
                                    showDialog(FAILED_REMOVAL_DIALOG);
                                } else {
                                    finish();
                                }
                            }
                        }, null);
                    }
                })
                .create();
        } else if (id == FAILED_REMOVAL_DIALOG) {
            dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.really_remove_account_title)
                .setMessage(R.string.remove_account_failed)
                .create();
        }
        return dialog;
    }

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        setContentView(R.layout.account_sync_screen);
        addPreferencesFromResource(R.xml.account_sync_settings);
        addAuthenticatorPreferences();
        
        mErrorInfoView = (TextView) findViewById(R.id.sync_settings_error_info);
        mErrorInfoView.setVisibility(View.GONE);
        mErrorInfoView.setCompoundDrawablesWithIntrinsicBounds(
                getResources().getDrawable(R.drawable.ic_list_syncerror), null, null, null);

        mUserId = (TextView) findViewById(R.id.user_id);
        mProviderId = (TextView) findViewById(R.id.provider_id);
        mProviderIcon = (ImageView) findViewById(R.id.provider_icon);
        mRemoveAccountArea = (View) findViewById(R.id.remove_account_area);
        mRemoveAccountButton = (Button) findViewById(R.id.remove_account_button);
        mRemoveAccountButton.setOnClickListener(this);


        mDateFormat = DateFormat.getDateFormat(this);
        mTimeFormat = DateFormat.getTimeFormat(this);

        mAccount = (Account) getIntent().getParcelableExtra("account");
        if (mAccount != null) {
            Log.v(TAG, "Got account: " + mAccount);
            mUserId.setText(mAccount.name);
            mProviderId.setText(mAccount.type);
        }
        AccountManager.get(this).addOnAccountsUpdatedListener(this, null, true);
        updateAuthDescriptions();
    }

    /*
     * Get settings.xml from authenticator for this account.
     * TODO: replace with authenticator-specific settings here when AuthenticatorDesc supports it.
     */
    private void addAuthenticatorPreferences() {
        mAuthenticatorPreferences = findPreference(CHANGE_PASSWORD_KEY);
    }

    ArrayList<SyncStateCheckBoxPreference> mCheckBoxes =
            new ArrayList<SyncStateCheckBoxPreference>();

    private void addSyncStateCheckBox(Account account, String authority) {
        SyncStateCheckBoxPreference item =
                new SyncStateCheckBoxPreference(this, account, authority);
        item.setPersistent(false);
        final ProviderInfo providerInfo = getPackageManager().resolveContentProvider(authority, 0);
        CharSequence providerLabel = providerInfo != null
                ? providerInfo.loadLabel(getPackageManager()) : null;
        if (TextUtils.isEmpty(providerLabel)) {
            Log.e(TAG, "Provider needs a label for authority '" + authority + "'");
            providerLabel = authority;
        }
        String title = getString(R.string.sync_item_title, providerLabel);
        item.setTitle(title);
        item.setKey(authority);
        getPreferenceScreen().addPreference(item);
        mCheckBoxes.add(item);
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

    @Override
    public boolean onPreferenceTreeClick(PreferenceScreen preferences, Preference preference) {
        if (preference instanceof SyncStateCheckBoxPreference) {
            SyncStateCheckBoxPreference syncPref = (SyncStateCheckBoxPreference) preference;
            String authority = syncPref.getAuthority();
            Account account = syncPref.getAccount();
            if (syncPref.isOneTimeSyncMode()) {
                requestOrCancelSync(account, authority, true);
            } else {
                boolean syncOn = syncPref.isChecked();
                boolean oldSyncState = ContentResolver.getSyncAutomatically(account, authority);
                if (syncOn != oldSyncState) {
                    // if we're enabling sync, this will request a sync as well
                    ContentResolver.setSyncAutomatically(account, authority, syncOn);
                    // if the master sync switch is off, the request above will
                    // get dropped.  when the user clicks on this toggle,
                    // we want to force the sync, however.
                    if (!ContentResolver.getMasterSyncAutomatically()) {
                        requestOrCancelSync(account, authority, syncOn);
                    }
                }
            }
        } else {
            return false;
        }
        return true;
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

    @Override
    protected void onSyncStateUpdated() {
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
                    && activeSyncValues.account.equals(account)
                    && activeSyncValues.authority.equals(authority);
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
            final boolean masterSyncAutomatically = ContentResolver.getMasterSyncAutomatically();
            syncPref.setOneTimeSyncMode(!masterSyncAutomatically);
            syncPref.setChecked(syncEnabled);
        }
        mErrorInfoView.setVisibility(syncIsFailing ? View.VISIBLE : View.GONE);
    }

    @Override
    public void onAccountsUpdated(Account[] accounts) {
        super.onAccountsUpdated(accounts);
        SyncAdapterType[] syncAdapters = ContentResolver.getSyncAdapterTypes();
        HashMap<String, ArrayList<String>> accountTypeToAuthorities = Maps.newHashMap();
        for (int i = 0, n = syncAdapters.length; i < n; i++) {
            final SyncAdapterType sa = syncAdapters[i];
            if (sa.isUserVisible()) {
                ArrayList<String> authorities = accountTypeToAuthorities.get(sa.accountType);
                if (authorities == null) {
                    authorities = new ArrayList<String>();
                    accountTypeToAuthorities.put(sa.accountType, authorities);
                }
                Log.d(TAG, "added authority " + sa.authority + " to accountType " + sa.accountType);
                authorities.add(sa.authority);
            }
        }

        for (int i = 0, n = mCheckBoxes.size(); i < n; i++) {
            getPreferenceScreen().removePreference(mCheckBoxes.get(i));
        }
        mCheckBoxes.clear();

        for (int i = 0, n = accounts.length; i < n; i++) {
            final Account account = accounts[i];
            Log.d(TAG, "looking for sync adapters that match account " + account);
            final ArrayList<String> authorities = accountTypeToAuthorities.get(account.type);
            if (authorities != null && (mAccount == null || mAccount.equals(account))) {
                for (int j = 0, m = authorities.size(); j < m; j++) {
                    final String authority = authorities.get(j);
                    Log.d(TAG, "  found authority " + authority);
                    if (ContentResolver.getIsSyncable(account, authority) > 0) {
                        addSyncStateCheckBox(account, authority);
                    }
                }
            }
        }

        onSyncStateUpdated();
    }

    /**
     * Updates the titlebar with an icon for the provider type.
     */
    @Override
    protected void onAuthDescriptionsUpdated() {
        super.onAuthDescriptionsUpdated();
        mProviderIcon.setImageDrawable(getDrawableForType(mAccount.type));
        mProviderId.setText(getLabelForType(mAccount.type));
        // TODO: Enable Remove accounts when we can tell the account can be removed
        
    }
}
