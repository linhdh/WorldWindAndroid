/*
 * Copyright (c) 2016 United States Government as represented by the Administrator of the
 * National Aeronautics and Space Administration. All Rights Reserved.
 */

package gov.nasa.worldwindx;

import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Choreographer;
import android.view.InputEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import gov.nasa.worldwind.Navigator;
import gov.nasa.worldwind.NavigatorEvent;
import gov.nasa.worldwind.NavigatorListener;
import gov.nasa.worldwind.WorldWind;
import gov.nasa.worldwind.WorldWindow;
import gov.nasa.worldwind.geom.Camera;
import gov.nasa.worldwind.geom.Location;
import gov.nasa.worldwind.geom.LookAt;

public class NavigatorEventActivity extends BasicGlobeActivity implements Choreographer.FrameCallback {

    // UI elements
    protected TextView latView;

    protected TextView lonView;

    protected TextView altView;

    protected ImageView crosshairs;

    protected ViewGroup overlay;

    // Use pre-allocated navigator state objects to avoid per-event memory allocations
    private LookAt lookAt = new LookAt();

    private Camera camera = new Camera();

    // Track the navigation event time so the overlay refresh rate can be throttled
    private long lastEventTime;

    // Animation object used to fade the overlays
    private AnimatorSet animatorSet;

    private boolean crosshairsActive;

    // Globe rotation onFrame animation settings
    private Location currentLocation = new Location();

    private Location targetLocation = new Location();

    private Location lastLocation; // lazily allocated

    private double radiansPerMillisecond;

    private double azimuth;

    private final double COAST_DURATION_MILLIS = 3000;

    private double coastTimeRemainingMillis;

    private long lastFrameTimeNanos;

    private boolean coasting;

    // Track the state of this activity to start/stope the globe animation
    private boolean activityPaused;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setAboutBoxTitle("About the " + getResources().getText(R.string.title_navigator_event));
        setAboutBoxText("Demonstrates how to receive and consume navigator events.\n\n"
            + "The crosshairs and overlays react to the user input");

        // Initialize the UI elements that we'll update upon the navigation events
        this.crosshairs = (ImageView) findViewById(R.id.globe_crosshairs);
        this.overlay = (ViewGroup) findViewById(R.id.globe_status);
        this.crosshairs.setVisibility(View.VISIBLE);
        this.overlay.setVisibility(View.VISIBLE);
        this.latView = (TextView) findViewById(R.id.lat_value);
        this.lonView = (TextView) findViewById(R.id.lon_value);
        this.altView = (TextView) findViewById(R.id.alt_value);
        ObjectAnimator fadeOut = ObjectAnimator.ofFloat(this.crosshairs, "alpha", 0f).setDuration(1500);
        fadeOut.setStartDelay(500);
        this.animatorSet = new AnimatorSet();
        this.animatorSet.play(fadeOut);

        // Create a simple Navigator Listener that logs navigator events emitted by the World Window.
        NavigatorListener listener = new NavigatorListener() {
            @Override
            public void onNavigatorEvent(WorldWindow wwd, NavigatorEvent event) {

                long currentTime = System.currentTimeMillis();
                long elapsedTime = currentTime - lastEventTime;
                int eventAction = event.getAction();
                InputEvent lastInputEvent = event.getLastInputEvent();
                boolean receivedUserInput = (eventAction == WorldWind.NAVIGATOR_MOVED && lastInputEvent != null);

                // Update the status overlay views whenever the navigator stops moving,
                // and also it is moving but at an (arbitrary) maximum refresh rate of 20 Hz.
                if (eventAction == WorldWind.NAVIGATOR_STOPPED || elapsedTime > 50) {

                    // Get the current navigator state to apply to the overlays
                    event.getNavigator().getAsLookAt(wwd.getGlobe(), lookAt);
                    event.getNavigator().getAsCamera(wwd.getGlobe(), camera);

                    // Update the overlays
                    updateOverlayContents(lookAt, camera);
                    updateOverlayColor(eventAction, lastInputEvent);

                    // Apply some inertial to the user's gesture
                    if (receivedUserInput) {
                        updateInertiaSettings(lookAt, elapsedTime);
                    }

                    lastEventTime = currentTime;
                }

                // Show the crosshairs while the user is gesturing and fade them out after the user stops
                if (receivedUserInput) {
                    showCrosshairs();
                } else {
                    fadeCrosshairs();
                }
            }
        };

        // Register the Navigator Listener with the activity's World Window.
        this.getWorldWindow().addNavigatorListener(listener);
    }

    /**
     * Animates a rotating globe.
     */
    @Override
    public void doFrame(long frameTimeNanos) {
        if (this.lastFrameTimeNanos != 0) {
            // Compute the frame duration in milliseconds.
            double frameDurationMillis = (frameTimeNanos - this.lastFrameTimeNanos) * 1.0e-6;

            // Move the navigator to simulate inertia from the user's last move gesture
            if (this.coastTimeRemainingMillis > 0) {
                Navigator navigator = getWorldWindow().getNavigator();

                // Compute the distance to move in this frame
                double distanceRadians = this.radiansPerMillisecond * frameDurationMillis;
                this.currentLocation.set(navigator.getLatitude(), navigator.getLongitude());
                this.currentLocation.greatCircleLocation(this.azimuth, distanceRadians, this.targetLocation);

                navigator.setLatitude(this.targetLocation.latitude);
                navigator.setLongitude(this.targetLocation.longitude);

                // Dampen the inertia
                this.coastTimeRemainingMillis -= frameDurationMillis;
                if (this.coastTimeRemainingMillis > 0) {
                    this.radiansPerMillisecond *= coastTimeRemainingMillis / COAST_DURATION_MILLIS;
                }

                // Redraw the World Window to display the above changes.
                this.getWorldWindow().requestRedraw();
            }
        }

        if (!this.activityPaused) { // stop animating when this Activity is activityPaused
            Choreographer.getInstance().postFrameCallback(this);
        }

        this.lastFrameTimeNanos = frameTimeNanos;
    }

    /**
     * Pauses the globe animation.
     */
    @Override
    protected void onPause() {
        super.onPause();
        // Stop running the globe rotation animation when this activity is activityPaused.
        this.activityPaused = true;
        this.lastFrameTimeNanos = 0;
    }

    /**
     * Starts the globe animation.
     */
    @Override
    protected void onResume() {
        super.onResume();
        // Resume the globe rotation animation
        this.activityPaused = false;
        this.lastFrameTimeNanos = 0;
        Choreographer.getInstance().postFrameCallback(this);
    }

    /**
     * Updates the settings for user gesture inertia.
     *
     * @param lookAt        The current lookAt
     * @param elapsedTimeMs The time elapsed since the last user action
     */
    protected void updateInertiaSettings(LookAt lookAt, long elapsedTimeMs) {
        if (this.lastLocation == null) {
            this.lastLocation = new Location(lookAt.latitude, lookAt.longitude);
        }
        // Compute the direction used for the coasting inertia
        this.currentLocation.set(lookAt.latitude, lookAt.longitude);
        this.azimuth = this.lastLocation.greatCircleAzimuth(this.currentLocation);

        // Compute the velocity used for the coasting
        this.radiansPerMillisecond = this.lastLocation.greatCircleDistance(this.currentLocation) / elapsedTimeMs;

        // Reset the coasting period on each user action
        this.coastTimeRemainingMillis = COAST_DURATION_MILLIS;

        this.lastLocation.set(this.currentLocation);
    }

    /**
     * Makes the crosshairs visible.
     */
    protected void showCrosshairs() {
        if (this.animatorSet.isStarted()) {
            this.animatorSet.cancel();
        }
        this.crosshairs.setAlpha(1.0f);
        this.crosshairsActive = true;
    }

    /**
     * Fades the crosshairs using animation.
     */
    protected void fadeCrosshairs() {
        if (this.crosshairsActive) {
            this.crosshairsActive = false;
            if (!this.animatorSet.isStarted()) {
                this.animatorSet.start();
            }
        }
    }

    /**
     * Displays navigator state information in the status overlay views.
     *
     * @param lookAt Where the navigator is looking
     * @param camera Where the camera is positioned
     */
    protected void updateOverlayContents(LookAt lookAt, Camera camera) {
        latView.setText(formatLatitude(lookAt.latitude));
        lonView.setText(formatLongitude(lookAt.longitude));
        altView.setText(formatAltitude(camera.altitude));
    }

    /**
     * Brightens the colors of the overlay views when when user input occurs.
     *
     * @param eventAction    The action associated with this navigator event
     * @param lastInputEvent The last user input event; will be null if no user input was detected during this navigator
     *                       event
     */
    protected void updateOverlayColor(@WorldWind.NavigatorAction int eventAction, InputEvent lastInputEvent) {
        int color = (eventAction == WorldWind.NAVIGATOR_STOPPED) ? 0xA0FFFF00 /*semi-transparent yellow*/ : Color.YELLOW;
        latView.setTextColor(color);
        lonView.setTextColor(color);
        altView.setTextColor(color);
    }

    protected String formatLatitude(double latitude) {
        int sign = (int) Math.signum(latitude);
        return String.format("%6.3f°%s", (latitude * sign), (sign >= 0.0 ? "N" : "S"));
    }

    protected String formatLongitude(double longitude) {
        int sign = (int) Math.signum(longitude);
        return String.format("%7.3f°%s", (longitude * sign), (sign >= 0.0 ? "E" : "W"));
    }

    protected String formatAltitude(double altitude) {
        return String.format("Eye: %,.0f %s",
            (altitude < 100000 ? altitude : altitude / 1000),
            (altitude < 100000 ? "m" : "km"));
    }
}
