package com.example.android.sunshine.app;

import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.net.Uri;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.LoaderManager;
import android.support.v4.content.CursorLoader;
import android.support.v4.content.Loader;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;

import com.example.android.sunshine.app.data.WeatherContract;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.wearable.Asset;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.PutDataMapRequest;
import com.google.android.gms.wearable.Wearable;

import java.io.ByteArrayOutputStream;
import java.util.Calendar;

/**
 * Created by Spectre on 4/14/2016.
 */
public class WearHandler implements DataApi.DataListener, LoaderManager.LoaderCallbacks<Cursor> {

    private static final String LOG_TAG = "WearHandler";
    private static final String WEATHER_DATA_PATH = "/weather";
    private static final String WEATHER_REQUEST_PATH = "/weather_request";
    private static final String WEATHER_BITMAP = "bitmap";
    private static final String WEATHER_HIGH = "high";
    private static final String WEATHER_LOW = "low";

    private GoogleApiClient mGoogleApiClient;
    private AppCompatActivity mContext;

    public WearHandler(AppCompatActivity context) {
        mContext = context;
        initializeGoogleApi();
    }

    private void initializeGoogleApi() {
        mGoogleApiClient = new GoogleApiClient.Builder(mContext)
                .addConnectionCallbacks(new GoogleApiClient.ConnectionCallbacks() {
                    @Override
                    public void onConnected(@Nullable Bundle bundle) {
                        Wearable.DataApi.addListener(mGoogleApiClient, WearHandler.this);
                    }

                    @Override
                    public void onConnectionSuspended(int i) {
                        Log.v(LOG_TAG, "CONNECTION SUSPENDED");
                    }
                })
                .addOnConnectionFailedListener(new GoogleApiClient.OnConnectionFailedListener() {
                    @Override
                    public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {
                        Log.v(LOG_TAG, "CONNECTION FAILED");
                    }
                }).addApi(Wearable.API)
                .build();
        mGoogleApiClient.connect();
    }

    @Override
    public void onDataChanged(DataEventBuffer dataEventBuffer) {
        for (DataEvent event : dataEventBuffer) {
            if (event.getType() == DataEvent.TYPE_CHANGED) {
                DataItem item = event.getDataItem();
                if (item.getUri().getPath().compareTo(WEATHER_REQUEST_PATH) == 0) {
                    sendWeatherPacket();
                }
            }
        }
    }

    public void sendWeatherPacket() {
        Log.v(LOG_TAG, "Request to send new weather packet!!!");
        mContext.getSupportLoaderManager().initLoader(DETAIL_LOADER, null, this);
    }

    private static final int DETAIL_LOADER = 0;

    private static final String[] DETAIL_COLUMNS = {
            WeatherContract.WeatherEntry.TABLE_NAME + "." + WeatherContract.WeatherEntry._ID,
            WeatherContract.WeatherEntry.COLUMN_MAX_TEMP,
            WeatherContract.WeatherEntry.COLUMN_MIN_TEMP,
            WeatherContract.WeatherEntry.COLUMN_WEATHER_ID,
            // This works because the WeatherProvider returns location data joined with
            // weather data, even though they're stored in two different tables.
            WeatherContract.LocationEntry.COLUMN_LOCATION_SETTING
    };

    // These indices are tied to DETAIL_COLUMNS.  If DETAIL_COLUMNS changes, these
    // must change.
    public static final int COL_WEATHER_MAX_TEMP = 1;
    public static final int COL_WEATHER_MIN_TEMP = 2;
    public static final int COL_WEATHER_CONDITION_ID = 3;

    @Override
    public Loader<Cursor> onCreateLoader(int id, Bundle args) {
        String sortOrder = WeatherContract.WeatherEntry.COLUMN_DATE + " ASC";

        String locationSetting = Utility.getPreferredLocation(mContext);
        Uri weatherForLocationUri = WeatherContract.WeatherEntry.buildWeatherLocationWithStartDate(
                locationSetting, System.currentTimeMillis());

        return new CursorLoader(mContext,
                weatherForLocationUri,
                DETAIL_COLUMNS,
                null,
                null,
                sortOrder);
    }

    @Override
    public void onLoadFinished(Loader<Cursor> loader, Cursor data) {
        if (data != null && data.moveToFirst()) {

            int weatherId = data.getInt(COL_WEATHER_CONDITION_ID);
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = 1;
            Bitmap image = BitmapFactory.decodeResource(mContext.getResources(), Utility.getArtResourceForWeatherCondition(weatherId), options);
            image = getResizedBitmap(image, 72, 72);
            Asset imageAsset = createAssetFromBitmap(image);

            double high = data.getDouble(COL_WEATHER_MAX_TEMP);
            String highString = Utility.formatTemperature(mContext, high);

            double low = data.getDouble(COL_WEATHER_MIN_TEMP);
            String lowString = Utility.formatTemperature(mContext, low);

            PutDataMapRequest request = PutDataMapRequest.create(WEATHER_DATA_PATH);
            request.getDataMap().putLong("TIME", Calendar.getInstance().getTimeInMillis());
            request.getDataMap().putString(WEATHER_HIGH, highString);
            request.getDataMap().putString(WEATHER_LOW, lowString);
            request.getDataMap().putAsset(WEATHER_BITMAP, imageAsset);
            Wearable.DataApi.putDataItem(mGoogleApiClient, request.asPutDataRequest());

        }
    }

    // From jeet.chanchawat on StackOverflow (http://stackoverflow.com/a/10703256)
    public Bitmap getResizedBitmap(Bitmap bm, int newWidth, int newHeight) {
        int width = bm.getWidth();
        int height = bm.getHeight();
        float scaleWidth = ((float) newWidth) / width;
        float scaleHeight = ((float) newHeight) / height;
        // CREATE A MATRIX FOR THE MANIPULATION
        Matrix matrix = new Matrix();
        // RESIZE THE BIT MAP
        matrix.postScale(scaleWidth, scaleHeight);

        // "RECREATE" THE NEW BITMAP
        Bitmap resizedBitmap = Bitmap.createBitmap(
                bm, 0, 0, width, height, matrix, false);
        bm.recycle();
        return resizedBitmap;
    }


    private static Asset createAssetFromBitmap(Bitmap bitmap) {
        final ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, byteStream);
        return Asset.createFromBytes(byteStream.toByteArray());
    }

    @Override
    public void onLoaderReset(Loader<Cursor> loader) {

    }
}
