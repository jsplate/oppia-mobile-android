package org.digitalcampus.oppia.fragments.prefs;

import android.os.Bundle;

import org.digitalcampus.mobile.learning.R;
import org.digitalcampus.oppia.activity.PrefsActivity;
import org.digitalcampus.oppia.application.AdminSecurityManager;
import org.digitalcampus.oppia.application.App;

import java.util.Arrays;

import androidx.preference.CheckBoxPreference;
import androidx.preference.EditTextPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

public class SecurityPrefsFragment extends AdminProtectedPreferenceFragment {

    public static final String TAG = PrefsActivity.class.getSimpleName();

    public static SecurityPrefsFragment newInstance() {
        return new SecurityPrefsFragment();
    }

    public SecurityPrefsFragment(){
        // Required empty public constructor
        this.adminProtectedValues = Arrays.asList(PrefsActivity.PREF_ADMIN_PASSWORD);
    }

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        // Load the preferences from XML resources
        addPreferencesFromResource(R.xml.prefs_security);
    }

    @Override
    public void onCreate(Bundle savedInstance) {
        super.onCreate(savedInstance);
        if (!App.ADMIN_PROTECT_SETTINGS){
            // If the whole settings activity is not protected by password, we need to protect admin settings
            protectAdminPreferences();
        }
    }

    private void protectAdminPreferences(){
        final CheckBoxPreference adminEnabled = findPreference(PrefsActivity.PREF_ADMIN_PROTECTION);
        adminEnabled.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
            @Override
            public boolean onPreferenceChange(final Preference preference, final Object newValue) {
                final Boolean enableProtection = (Boolean) newValue;
                if (enableProtection) {
                    //If we are going to re-enable the preference, there is no need to prompt for the previous password
                    return true;
                }
                AdminSecurityManager.with(getActivity()).promptAdminPassword(new AdminSecurityManager.AuthListener() {
                    @Override
                    public void onPermissionGranted() {
                        adminEnabled.setChecked(enableProtection);
                        preference.getSharedPreferences().edit().putBoolean(preference.getKey(), enableProtection).apply();
                    }
                });
                return false;
            }
        });

    }



}
