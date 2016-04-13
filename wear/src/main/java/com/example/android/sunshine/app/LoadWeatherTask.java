package com.example.android.sunshine.app;

import android.os.AsyncTask;

/**
 * Created by Spectre on 4/13/2016.
 */
public class LoadWeatherTask extends AsyncTask<Void,Void,SimpleWeatherData> {

    @Override
    protected SimpleWeatherData doInBackground(Void... params) {
        return null;
    }

    @Override
    protected void onPostExecute(SimpleWeatherData simpleWeatherData) {
        super.onPostExecute(simpleWeatherData);
    }

}
