package com.sti.tanghap;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import androidx.appcompat.app.AppCompatDelegate;

public class TanghapApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        
        SharedPreferences prefs = getSharedPreferences("app_prefs", Context.MODE_PRIVATE);
        boolean isDarkMode = prefs.getBoolean("dark_mode", false);
        
        if (isDarkMode) {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
        } else {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
        }
    }
}