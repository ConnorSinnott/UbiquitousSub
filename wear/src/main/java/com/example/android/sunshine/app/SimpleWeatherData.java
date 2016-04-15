package com.example.android.sunshine.app;

import android.graphics.Bitmap;

/**
 * Created by Spectre on 4/13/2016.
 */
public class SimpleWeatherData {

    String high;
    String low;
    Bitmap icon;

    public SimpleWeatherData(String high, String low, Bitmap icon) {
        this.high = high;
        this.low = low;
        this.icon = icon;
    }

}
