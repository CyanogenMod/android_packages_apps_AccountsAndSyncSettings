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

import android.accounts.AccountManager;
import android.accounts.AuthenticatorException;
import android.accounts.AccountManagerFuture;
import android.accounts.OperationCanceledException;
import android.accounts.AccountManagerCallback;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.preference.Preference;
import android.preference.PreferenceCategory;
import android.preference.PreferenceScreen;
import android.util.Log;

import java.io.IOException;
import java.util.ArrayList;

public class AddAccountSettings extends AccountPreferenceBase {
    private static final String TAG = "AddAccount";
    private static final String ADD_ACCOUNT_CATEGORY_KEY = "addAccountCategory";
    private String[] mAuthorities;
    private PreferenceCategory mAddAccountCategory;

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        setContentView(R.layout.add_account_screen);
        addPreferencesFromResource(R.xml.add_account_settings);
        mAuthorities = getIntent().getStringArrayExtra(AUTHORITIES_FILTER_KEY);
        mAddAccountCategory = (PreferenceCategory) findPreference(ADD_ACCOUNT_CATEGORY_KEY);
        updateAuthDescriptions();
    }

    @Override
    protected void onAuthDescriptionsUpdated() {
        // Add all account types to the preference screen
        mAddAccountCategory.removeAll();

        // Add the new ones
        for (int i = 0; i < mAuthDescs.length; i++) {
            String accountType = mAuthDescs[i].type;
            CharSequence providerName = getLabelForType(accountType);

            // Skip preferences for authorities not specified. If no authorities specified,
            // then include them all.
            ArrayList<String> accountAuths = getAuthoritiesForAccountType(accountType);
            boolean addAccountPref = true;
            if (mAuthorities != null && mAuthorities.length > 0) {
                addAccountPref = false;
                for (int k = 0; k < mAuthorities.length; k++) {
                    if (accountAuths.contains(mAuthorities[k])) {
                        addAccountPref = true;
                        break;
                    }
                }
            }
            if (addAccountPref) {
                Drawable drawable = getDrawableForType(accountType);
                mAddAccountCategory.addPreference(
                        new ProviderPreference(this, accountType, drawable, providerName));
                Log.v(TAG, "Added new pref for provider " + providerName);
            } else {
                Log.v(TAG, "Skipped pref " + providerName + ": has no authority we need");
            }
        }
    }

    private AccountManagerCallback<Bundle> mCallback = new AccountManagerCallback<Bundle>() {
        public void run(AccountManagerFuture<Bundle> future) {
            try {
                Bundle bundle = future.getResult();
                bundle.keySet();
                Log.d(TAG, "account added: " + bundle);
            } catch (OperationCanceledException e) {
                Log.d(TAG, "addAccount was canceled");
            } catch (IOException e) {
                Log.d(TAG, "addAccount failed: " + e);
            } catch (AuthenticatorException e) {
                Log.d(TAG, "addAccount failed: " + e);
            }
        }
    };

    @Override
    public boolean onPreferenceTreeClick(PreferenceScreen preferences, Preference preference) {
        if (preference instanceof ProviderPreference) {
            ProviderPreference pref = (ProviderPreference) preference;
            Log.v(TAG, "Attempting to add account of type " + pref.getAccountType());
            AccountManager.get(this).addAccount(
                    pref.getAccountType(),
                    null, /* authTokenType */
                    null, /* requiredFeatures */
                    null, /* addAccountOptions */
                    this,
                    mCallback,
                    null /* handler */);
        }
        return true;
    }
}
