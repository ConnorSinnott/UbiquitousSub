package com.example.android.sunshine.app;

import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.net.Uri;
import android.support.v4.content.CursorLoader;
import android.support.v4.content.Loader;
import android.util.Log;

import com.example.android.sunshine.app.data.WeatherContract;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.wearable.Asset;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.PutDataMapRequest;
import com.google.android.gms.wearable.Wearable;
import com.google.android.gms.wearable.WearableListenerService;

import java.io.ByteArrayOutputStream;
import java.util.Calendar;

/**
 * Created by Spectre on 4/14/2016.
 */
//public class WearHandler implements DataApi.DataListener, LoaderManager.LoaderCallbacks<Cursor> {
public class WearHandler extends WearableListenerService implements Loader.OnLoadCompleteListener<Cursor> {

    private static final String LOG_TAG = "WearHandler";
    private static final String WEATHER_DATA_PATH = "/weather";
    private static final String WEATHER_REQUEST_TIME = "time";
    private static final String WEATHER_BOUNDS_SIZE = "size";
    private static final String WEATHER_BITMAP = "bitmap";
    private static final String WEATHER_HIGH = "high";
    private static final String WEATHER_LOW = "low";

    private CursorLoader mCursorLoader;
    private int mScreenSize;
    private Context mContext;

    public WearHandler() {
        mContext = this;
    }

    @Override
    public void onDataChanged(DataEventBuffer dataEventBuffer) {
        for (DataEvent event : dataEventBuffer) {
            if (event.getType() == DataEvent.TYPE_CHANGED) {
                DataItem item = event.getDataItem();
                if (item.getUri().getPath().compareTo(WEATHER_DATA_PATH) == 0) {
                    DataMap map = DataMapItem.fromDataItem(item).getDataMap();
                    if (map.containsKey(WEATHER_REQUEST_TIME)) {
                        Log.v(LOG_TAG, "Received request for new weather data...");
                        mScreenSize = map.getInt(WEATHER_BOUNDS_SIZE);
                        sendWeatherPacket();
                    }
                }
            }
        }
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


    public void sendWeatherPacket() {

        String sortOrder = WeatherContract.WeatherEntry.COLUMN_DATE + " ASC";

        String locationSetting = Utility.getPreferredLocation(mContext);
        Uri weatherForLocationUri = WeatherContract.WeatherEntry.buildWeatherLocationWithStartDate(
                locationSetting, System.currentTimeMillis());

        mCursorLoader = new CursorLoader(mContext, weatherForLocationUri, DETAIL_COLUMNS, null, null, sortOrder);
        mCursorLoader.registerListener(DETAIL_LOADER, this);
        mCursorLoader.startLoading();

    }

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


    @Override
    public void onLoadComplete(Loader loader, Cursor data) {
        if (data != null && data.moveToFirst()) {

            GoogleApiClient googleApiClient = new GoogleApiClient.Builder(this)
                    .addApi(Wearable.API)
                    .build();
            googleApiClient.connect();

            int weatherId = data.getInt(COL_WEATHER_CONDITION_ID);
            BitmapFactory.Options options = new BitmapFactory.Options();

//            Drawable drawable = mContext.getResources().getDrawableForDensity(Utility.getArtResourceForWeatherCondition(weatherId), requestedDPI, null);
            Bitmap image = BitmapFactory.decodeResource(mContext.getResources(), Utility.getArtResourceForWeatherCondition(weatherId), options);
            image = getResizedBitmap(image, mScreenSize / 4, mScreenSize / 4);

            ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
            image.compress(Bitmap.CompressFormat.PNG, 100, byteStream);
            Asset imageAsset = Asset.createFromBytes(byteStream.toByteArray());

            double high = data.getDouble(COL_WEATHER_MAX_TEMP);
            String highString = Utility.formatTemperature(mContext, high);

            //TODO Restore
//            double low = data.getDouble(COL_WEATHER_MIN_TEMP);
            double low = Calendar.getInstance().get(Calendar.SECOND);
            String lowString = Utility.formatTemperature(mContext, low);

            PutDataMapRequest request = PutDataMapRequest.create(WEATHER_DATA_PATH);
            request.getDataMap().putLong(WEATHER_REQUEST_TIME, Calendar.getInstance().getTimeInMillis());
            request.getDataMap().putString(WEATHER_HIGH, highString);
            request.getDataMap().putString(WEATHER_LOW, lowString);
            request.getDataMap().putAsset(WEATHER_BITMAP, imageAsset);
            Wearable.DataApi.putDataItem(googleApiClient, request.asPutDataRequest());
            Log.v(LOG_TAG, "New weather data sent...");
            googleApiClient.disconnect();
        }
    }

    @Override
    public void onDestroy() {
        // Stop the cursor loader
        if (mCursorLoader != null) {
            mCursorLoader.unregisterListener(this);
            mCursorLoader.cancelLoad();
            mCursorLoader.stopLoading();
        }
    }

}
