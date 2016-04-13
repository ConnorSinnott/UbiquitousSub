package com.example.android.sunshine.app;

import android.graphics.Bitmap;

/**
 * Created by Spectre on 4/13/2016.
 */
public class SimpleWeatherData {

    int high;
    int low;
    Bitmap weatherIcon;

    public SimpleWeatherData(int high, int low, Bitmap weatherIcon) {
        this.high = high;
        this.low = low;
        this.weatherIcon = weatherIcon;
    }

}
