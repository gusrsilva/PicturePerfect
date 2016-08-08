package com.pictureperfect;


import android.content.SharedPreferences;
import android.os.Bundle;
import android.preference.PreferenceActivity;
import android.support.v7.app.AppCompatActivity;

import com.pictureperfect.ui.camera.SettingsFragment;


public class SettingsActivity extends AppCompatActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        getFragmentManager()
                .beginTransaction()
                .replace(R.id.frag_holder, new SettingsFragment())
                .commit();
    }
}