/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.example.android.sunshine.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.annotation.Nullable;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.view.SurfaceHolder;
import android.view.WindowInsets;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.wearable.Asset;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.PutDataMapRequest;
import com.google.android.gms.wearable.Wearable;

import java.io.InputStream;
import java.lang.ref.WeakReference;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * Digital watch face with seconds. In ambient mode, the seconds aren't displayed. On devices with
 * low-bit ambient mode, the text is drawn without anti-aliasing in ambient mode.
 */
public class SunshineWatchFace extends CanvasWatchFaceService {

    private static final String LOG_TAG = "SunshineWatchFace";
    private static final String WEATHER_DATA_PATH = "/weather";
    private static final String WEATHER_REQUEST_TIME = "time";
    private static final String WEATHER_BOUNDS_SIZE = "size";
    private static final String WEATHER_BITMAP = "bitmap";
    private static final String WEATHER_HIGH = "high";
    private static final String WEATHER_LOW = "low";

    private static Context mContext;

    private GoogleApiClient mGoogleApiClient;
    private static SimpleWeatherData sSimpleWeatherData = null;
    private static int mScreenSize;
    private static boolean mInitialConnect = true;

    private static final Typeface NORMAL_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);

    /**
     * Update rate in milliseconds for interactive mode. We update once a second since seconds are
     * displayed in interactive mode.
     */
    private static final long INTERACTIVE_UPDATE_RATE_MS = TimeUnit.SECONDS.toMillis(1);
    private static final long WEATHER_UPDATE_STANDARD = TimeUnit.MINUTES.toMillis(30);
    private static final long WEATHER_UPDATE_RETRY = TimeUnit.SECONDS.toMillis(30);
    private static long sWeatherUpdateRateMs = WEATHER_UPDATE_RETRY;

    /**
     * Handler message id for updating the time periodically in interactive mode.
     */
    private static final int MSG_UPDATE_TIME = 0;
    private static final int MSG_UPDATE_WEATHER = 1;

    @Override
    public Engine onCreateEngine() {
        mContext = getApplicationContext();
        return new Engine();
    }

    private class Engine extends CanvasWatchFaceService.Engine implements DataApi.DataListener {

        final Handler mUpdateTimeHandler = new EngineHandler(this);
        boolean mRegisteredTimeZoneReceiver = false;
        Paint mBackgroundPaint;
        Paint mClockTextPaint;
        Paint mDateTextPaint;
        Paint mHighTextPaint;
        Paint mLowTextPaint;
        boolean mAmbient;
        Calendar mCalendar;
        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mCalendar.setTimeZone(TimeZone.getDefault());
                invalidate();
            }
        };
        float mClockYOffset;
        float mDateYOffset;

        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        boolean mLowBitAmbient;

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            setWatchFaceStyle(new WatchFaceStyle.Builder(SunshineWatchFace.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .build());
            Resources resources = SunshineWatchFace.this.getResources();
            mClockYOffset = resources.getDimension(R.dimen.digital_y_offset);
            mDateYOffset = resources.getDimension(R.dimen.date_y_offset);

            mBackgroundPaint = new Paint();
            mBackgroundPaint.setColor(resources.getColor(R.color.background));

            mClockTextPaint = createTextPaint(resources.getColor(R.color.clock_text));

            mDateTextPaint = createTextPaint(resources.getColor(R.color.date_text));
            mDateTextPaint.setLetterSpacing(0.12f);

            mHighTextPaint = createTextPaint(resources.getColor(R.color.clock_text));
            mLowTextPaint = createTextPaint(resources.getColor(R.color.date_text));

            mCalendar = Calendar.getInstance();

            mGoogleApiClient = new GoogleApiClient.Builder(mContext)
                    .addApi(Wearable.API)
                    .addConnectionCallbacks(new GoogleApiClient.ConnectionCallbacks() {
                        @Override
                        public void onConnected(@Nullable Bundle bundle) {
                            Wearable.DataApi.addListener(mGoogleApiClient, Engine.this);
                        }

                        @Override
                        public void onConnectionSuspended(int i) {
                        }
                    }).build();

        }


        @Override
        public void onDataChanged(DataEventBuffer dataEventBuffer) {
            for (DataEvent event : dataEventBuffer) {
                if (event.getType() == DataEvent.TYPE_CHANGED) {
                    DataItem item = event.getDataItem();
                    if (item.getUri().getPath().compareTo(WEATHER_DATA_PATH) == 0) {
                        DataMap map = DataMapItem.fromDataItem(item).getDataMap();
                        if (!map.containsKey(WEATHER_REQUEST_TIME)) {
                            updateWeather(item);
                        }
                    }
                }
            }
        }

        private void updateWeather(DataItem item) {
            new AsyncTask<DataMap, Void, Bitmap>() {

                @Override
                protected Bitmap doInBackground(DataMap... params) {
                    DataMap dataMap = params[0];

                    String high = dataMap.getString(WEATHER_HIGH);
                    String low = dataMap.getString(WEATHER_LOW);
                    Asset iconAsset = dataMap.getAsset(WEATHER_BITMAP);

                    ConnectionResult result =
                            mGoogleApiClient.blockingConnect(1000, TimeUnit.MILLISECONDS);
                    if (!result.isSuccess()) {
                        return null;
                    }
                    // convert asset into a file descriptor and block until it's ready
                    InputStream assetInputStream = Wearable.DataApi.getFdForAsset(
                            mGoogleApiClient, iconAsset).await().getInputStream();

                    // decode the stream into a bitmap
                    Bitmap icon = BitmapFactory.decodeStream(assetInputStream);
//                    icon = getResizedBitmap(icon);

                    sSimpleWeatherData = new SimpleWeatherData(high, low, icon);

                    sWeatherUpdateRateMs = WEATHER_UPDATE_STANDARD;

                    invalidate();

                    return null;

                }

            }.execute(DataMapItem.fromDataItem(item).getDataMap());
        }

        private void requestWeather() {
            PutDataMapRequest request = PutDataMapRequest.create(WEATHER_DATA_PATH);
            request.getDataMap().putLong(WEATHER_REQUEST_TIME, System.currentTimeMillis());
            request.getDataMap().putInt(WEATHER_BOUNDS_SIZE, mScreenSize);
            Wearable.DataApi.putDataItem(mGoogleApiClient, request.asPutDataRequest());
        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            Wearable.DataApi.removeListener(mGoogleApiClient, Engine.this);
            super.onDestroy();
        }

        private Paint createTextPaint(int textColor) {
            Paint paint = new Paint();
            paint.setColor(textColor);
            paint.setTypeface(NORMAL_TYPEFACE);
            paint.setAntiAlias(true);
            return paint;
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);

            if (visible) {
                registerReceiver();

                // Update time zone in case it changed while we weren't visible.
                mCalendar = Calendar.getInstance();
                mCalendar.setTimeZone(TimeZone.getDefault());
            } else {
                unregisterReceiver();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        private void registerReceiver() {
            if (mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = true;
            IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
            SunshineWatchFace.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            SunshineWatchFace.this.unregisterReceiver(mTimeZoneReceiver);
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);

            // Load resources that have alternate values for round watches.
            Resources resources = SunshineWatchFace.this.getResources();
            boolean isRound = insets.isRound();
            float clockTextSize = resources.getDimension(isRound
                    ? R.dimen.digital_text_size_round : R.dimen.digital_text_size);
            float dateTextSize = resources.getDimension(isRound
                    ? R.dimen.date_text_size_round : R.dimen.date_text_size);
            float tempTextSize = resources.getDimension(isRound
                    ? R.dimen.temp_text_size_round : R.dimen.temp_text_size);

            mClockTextPaint.setTextSize(clockTextSize);
            mDateTextPaint.setTextSize(dateTextSize);
            mHighTextPaint.setTextSize(tempTextSize);
            mLowTextPaint.setTextSize(tempTextSize);


        }

        @Override
        public void onPropertiesChanged(Bundle properties) {
            super.onPropertiesChanged(properties);
            mLowBitAmbient = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false);
        }

        @Override
        public void onTimeTick() {
            super.onTimeTick();
            invalidate();
        }

        @Override
        public void onAmbientModeChanged(boolean inAmbientMode) {
            super.onAmbientModeChanged(inAmbientMode);
            if (mAmbient != inAmbientMode) {
                mAmbient = inAmbientMode;
                if (mLowBitAmbient) {
                    mClockTextPaint.setAntiAlias(!inAmbientMode);
                }
                invalidate();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            // Draw the background.
            if (isInAmbientMode()) {
                canvas.drawColor(Color.BLACK);
            } else {
                canvas.drawRect(0, 0, bounds.width(), bounds.height(), mBackgroundPaint);
            }

            mCalendar = Calendar.getInstance();
            mScreenSize = bounds.height();

            //            String digitalText = String.format("%d:%02d", mTime.hour, mTime.minute);
            String digitalText = new SimpleDateFormat("hh:mm").format(mCalendar.getTime());
            canvas.drawText(digitalText,
                    bounds.centerX() - (mClockTextPaint.measureText(digitalText) / 2),
                    mClockYOffset, mClockTextPaint);

            String dateText = new SimpleDateFormat("E, MMM d yyyy").format(mCalendar.getTime()).toUpperCase();
            canvas.drawText(dateText,
                    bounds.centerX() - (mDateTextPaint.measureText(dateText) / 2),
                    mDateYOffset, mDateTextPaint);

            canvas.drawLine(
                    bounds.centerX() - getResources().getDimension(R.dimen.middle_divider_x_size) / 2,
                    bounds.centerY() + getResources().getDimension(R.dimen.middle_divider_y_offset),
                    bounds.centerX() + getResources().getDimension(R.dimen.middle_divider_x_size) / 2,
                    bounds.centerY() + getResources().getDimension(R.dimen.middle_divider_y_offset),
                    mDateTextPaint);

            Bitmap weatherIcon = null;
            String weatherHigh = "-";
            String weatherLow = "-";
            if (sSimpleWeatherData != null) {
                weatherHigh = String.valueOf(sSimpleWeatherData.high);
                weatherLow = String.valueOf(sSimpleWeatherData.low);
                weatherIcon = sSimpleWeatherData.icon;
            }
            canvas.drawText(weatherHigh,
                    bounds.centerX() - (mHighTextPaint.measureText(weatherHigh) / 2),
                    bounds.centerY() + getResources().getDimension(R.dimen.temp_y_offset),
                    mHighTextPaint);

            canvas.drawText(weatherLow,
                    bounds.centerX() + (mHighTextPaint.measureText(weatherLow) / 2) + getResources().getDimension(R.dimen.temp_element_margin),
                    bounds.centerY() + getResources().getDimension(R.dimen.temp_y_offset),
                    mLowTextPaint);

            if (weatherIcon != null) {
                canvas.drawBitmap(weatherIcon,
                        bounds.centerX() - Math.round(getResources().getDimension(R.dimen.temp_element_margin) * 5.5),
                        bounds.centerY() + 30,
                        null
                );
            }

        }


        /**
         * Starts the {@link #mUpdateTimeHandler} timer if it should be running and isn't currently
         * or stops it if it shouldn't be running but currently is.
         */
        private void updateTimer() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_WEATHER);
            if (shouldTimerBeRunning()) {
                mUpdateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME);
                mUpdateTimeHandler.sendEmptyMessage(MSG_UPDATE_WEATHER);
            }
        }

        /**
         * Returns whether the {@link #mUpdateTimeHandler} timer should be running. The timer should
         * only run when we're visible and in interactive mode.
         */
        private boolean shouldTimerBeRunning() {
            return isVisible() && !isInAmbientMode();
        }

        /**
         * Handle updating the time periodically in interactive mode.
         */
        private void handleUpdateTimeMessage() {
            invalidate();
            if (shouldTimerBeRunning()) {
                long timeMs = System.currentTimeMillis();
                long delayMs = INTERACTIVE_UPDATE_RATE_MS
                        - (timeMs % INTERACTIVE_UPDATE_RATE_MS);
                mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
            }
        }

        private void handleWeatherUpdateMessage() {
            if (!mGoogleApiClient.isConnected()) {
                mGoogleApiClient.connect();
            }
            if (mInitialConnect) {
                sWeatherUpdateRateMs = WEATHER_UPDATE_RETRY;
                mInitialConnect = false;
            }
            if (shouldTimerBeRunning()) {
                long timeMS = System.currentTimeMillis();
                long delayMS = sWeatherUpdateRateMs
                        - (timeMS % sWeatherUpdateRateMs);
                mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_WEATHER, delayMS);
            }
            requestWeather();
        }

    }

    private static class EngineHandler extends Handler {
        private final WeakReference<SunshineWatchFace.Engine> mWeakReference;

        public EngineHandler(SunshineWatchFace.Engine reference) {
            mWeakReference = new WeakReference<>(reference);
        }

        @Override
        public void handleMessage(Message msg) {
            SunshineWatchFace.Engine engine = mWeakReference.get();
            if (engine != null) {
                switch (msg.what) {
                    case MSG_UPDATE_TIME:
                        engine.handleUpdateTimeMessage();
                        break;
                    case MSG_UPDATE_WEATHER:
                        engine.handleWeatherUpdateMessage();
                        break;
                }
            }
        }
    }

}
