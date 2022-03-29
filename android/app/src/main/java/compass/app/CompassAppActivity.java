package compass.app;

/*
 * Created by Roope Heinonen on 1.2.2018.
 * The main activity for compass
 * Handles sensor reading, calculates the bearing and rotates the pointer
 */

import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.GeomagneticField;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.Manifest.permission;
import android.os.Bundle;
import android.view.WindowManager;
import android.support.annotation.NonNull;
import android.view.animation.Animation;
import android.view.animation.RotateAnimation;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.Locale;

public class CompassAppActivity extends Activity
    implements SensorEventListener, LocationListener
{
    // Sensors
    private Sensor sensorMag = null;
    private Sensor sensorAcc = null;
    private SensorManager sensorMgr = null;

    // Sensor data
    private final float magneticData[] = new float[3];
    private final float accelerometerData[] = new float[3];

    // Rotation and orientation data
    private final float rotationData[] = new float[9];
    private final float orientationData[] = new float[3];

    // Location
    private Location currentLocation = null;
    private LocationManager locationManager = null;
    private GeomagneticField geomagneticField = null;

    // Views
    private TextView textBearing = null;
    private TextView textLat = null;
    private TextView textLong = null;
    private ImageView compassPointer = null;

    double bearing = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_compass_app);

        // Initialize sensors
        sensorMgr = (SensorManager)getSystemService(SENSOR_SERVICE);
        if (sensorMgr == null)
            throw new AssertionError();
        sensorMag = sensorMgr.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD);
        sensorAcc = sensorMgr.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);

        // Load views
        textBearing = findViewById(R.id.textBearing);
        textLat = findViewById(R.id.textLat);
        textLong = findViewById(R.id.textLong);
        compassPointer = findViewById(R.id.compassPointer);

        // Keep screen on
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults)
    {
        // checkSelfPermission is needed to make compiler happy, instead of just reading grantResults
        if (checkSelfPermission(permissions[0]) == PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(permissions[1]) == PackageManager.PERMISSION_GRANTED)
        {
            // Request for GPS updates
            locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 30000, 10, this);
            try
            {
                // Hard fix location to central Oulu in case of GPS and network failure
                Location hardFix = new Location("Oulu");
                hardFix.setLatitude(65.012089);
                hardFix.setLongitude(25.465077);
                hardFix.setAltitude(1);
                try
                {
                    // Try to get current location from GPS or network provider
                    Location gps = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
                    Location network = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
                    if (gps != null)
                        currentLocation = gps;
                    else if (network != null)
                        currentLocation = network;
                    else
                        currentLocation = hardFix;
                }
                catch (Exception ex1)
                {
                    currentLocation = hardFix;
                }
                // Location has been updated
                onLocationChanged(currentLocation);
            }
            catch (Exception ex)
            {
                ex.printStackTrace();
            }
        }
    }

    @Override
    protected void onStart()
    {
        super.onStart();
        try
        {
            // Register sensor listeners
            // SENSOR_DELAY_UI = ~66ms; was too slow and caused stuttering while rotating the phone, even when data was filtered
            // SENSOR_DELAY_GAME = 20ms; seems to give most stable results after filtering
            sensorMgr.registerListener(this, sensorMag, SensorManager.SENSOR_DELAY_GAME);
            sensorMgr.registerListener(this, sensorAcc, SensorManager.SENSOR_DELAY_GAME);

            // Android 8.0 requires permissions
            requestPermissions(new String[]{permission.ACCESS_COARSE_LOCATION, permission.ACCESS_FINE_LOCATION}, 1);

            // Initialize location manager here but load GPS data when permissions are granted
            locationManager = (LocationManager)getSystemService(Context.LOCATION_SERVICE);
        }
        catch (Exception ex)
        {
            try
            {
                if (sensorMgr != null)
                {
                    sensorMgr.unregisterListener(this, sensorMag);
                    sensorMgr.unregisterListener(this, sensorAcc);
                    sensorMgr = null;
                }
            }
            catch (Exception ex1)
            {
                ex1.printStackTrace();
            }
        }
    }

    @Override
    protected void onStop()
    {
        super.onStop();
        sensorMgr.unregisterListener(this);
    }

    // Equation for low-pass filtering:
    // y = x * alpha + y * (1 - alpha)
    // where x = input and y = output
    float[] doLowPassFiltering(float[] input, float[] output)
    {
        // Alpha is calculated as dT / (dT + t)
        // where dT is sensor delay rate and t is a time constant
        // SENSOR_DELAY_GAME is 20ms (0.02s), it's used for both accelerometer and magnetic field sensor
        final float sensorDelay = 0.02f;
        final float alpha = sensorDelay / (sensorDelay + 1);
        for (int i = 0; i < input.length; ++i)
        {
            output[i] = input[i] * alpha + output[i] * (1 - alpha);
        }
        return output;
    }

    @Override
    public void onSensorChanged(SensorEvent sensorEvent)
    {
        // Only read data from magnetic field sensor and accelerometer sensor
        if (sensorEvent.sensor.getType() != Sensor.TYPE_MAGNETIC_FIELD &&
            sensorEvent.sensor.getType() != Sensor.TYPE_ACCELEROMETER)
            return;

        // Read x, y, z from magnetic field sensor
        if (sensorEvent.sensor.getType() == Sensor.TYPE_MAGNETIC_FIELD)
        {
            float[] filtered = doLowPassFiltering(sensorEvent.values, magneticData);
            magneticData[0] = filtered[0];
            magneticData[1] = filtered[1];
            magneticData[2] = filtered[2];
        }
        // Read x, y, z from accelerometer sensor
        else
        {
            float[] filtered = doLowPassFiltering(sensorEvent.values, accelerometerData);
            accelerometerData[0] = filtered[0];
            accelerometerData[1] = filtered[1];
            accelerometerData[2] = filtered[2];
        }

        // Compute rotational matrix from accelerometer and magnetic field data into rotationData
        SensorManager.getRotationMatrix(rotationData, null, accelerometerData, magneticData);
        // Compute orientation from rotationData into orientationData
        SensorManager.getOrientation(rotationData, orientationData);
        // final values in radians contain following data:
        // orientationData[0] = Azimuth (angle between positive Y-axis and magnetic north)
        // orientationData[1] = Pitch (angle of rotation about the x axis)
        // orientationData[2] = Roll (angle of rotation about the y axis)
        double newBearing = Math.toDegrees(orientationData[0]);

        // Get geomagnetic declination
        if (geomagneticField != null)
        {
            if (geomagneticField.getDeclination() > 0)
                newBearing += geomagneticField.getDeclination();
            else
                newBearing -= geomagneticField.getDeclination();
        }

        // Adjust bearing to 0-360 (degrees)
        if (newBearing < 0)
            newBearing += 360;

        RotateAnimation animation = new RotateAnimation((float)bearing, (float)-newBearing, Animation.RELATIVE_TO_SELF, 0.5f, Animation.RELATIVE_TO_SELF, 0.5f);
        animation.setDuration(0);
        animation.setFillAfter(true);
        compassPointer.startAnimation(animation);
        bearing = -newBearing;

        // Create a string with the bearing rounded to integer
        String str = String.format(Locale.getDefault(), "%d", (int)Math.round(newBearing));
        str += getString(R.string.degreeSign) + " "; // Degree symbol
        // Add current heading to the string
        // N, W, S, E all contain 40 degrees
        // rest have 50 degrees
        if (newBearing >= 340 || newBearing < 21)
            str += "N";
        else if (newBearing >= 21 && newBearing < 70)
            str += "NE";
        else if (newBearing >= 70 && newBearing < 111)
            str += "E";
        else if (newBearing >= 111 && newBearing < 160)
            str += "SE";
        else if (newBearing >= 160 && newBearing < 201)
            str += "S";
        else if (newBearing >= 201 && newBearing < 250)
            str += "SW";
        else if (newBearing >= 250 && newBearing < 291)
            str += "W";
        else if (newBearing >= 291 && newBearing < 340)
            str += "NW";

        textBearing.setText(str);

        // Show latitude and longitude on screen
        if (currentLocation != null)
        {
            // Convert decimal latitude and longitude to degree : minute : second format
            String latitude = "";
            double latitude_Deg = Math.floor(currentLocation.getLatitude());
            latitude_Deg = latitude_Deg > 0 ? latitude_Deg : latitude_Deg * (-1);
            double latitude_Min = (currentLocation.getLatitude() - latitude_Deg) * 60;
            double latitude_Sec = (latitude_Min - Math.floor(latitude_Min)) * 60;
            latitude += String.format(Locale.getDefault(), "%.0f", latitude_Deg);
            latitude += getString(R.string.degreeSign); // Degree symbol
            latitude += String.format(Locale.getDefault(), "%.0f", Math.floor(latitude_Min));
            latitude += "'";
            latitude += String.format(Locale.getDefault(), "%.3f", latitude_Sec);
            latitude += "''";
            if (currentLocation.getLatitude() > 0)
                latitude += "N";
            else
                latitude += "S";

            String longitude = "";
            double longitude_Deg = Math.floor(currentLocation.getLongitude());
            longitude_Deg = longitude_Deg > 0 ? longitude_Deg : longitude_Deg * (-1);
            double longitude_Min = (currentLocation.getLongitude() - longitude_Deg) * 60;
            double longitude_Sec = (longitude_Min - (int)Math.floor(longitude_Min)) * 60;
            longitude += String.format(Locale.getDefault(), "%.0f", longitude_Deg);
            longitude += getString(R.string.degreeSign); // Degree symbol
            longitude += String.format(Locale.getDefault(), "%.0f", Math.floor(longitude_Min));
            longitude += "'";
            longitude += String.format(Locale.getDefault(), "%.1f", longitude_Sec);
            longitude += "''";
            if (currentLocation.getLongitude() > 0)
                longitude += "E";
            else
                longitude += "W";

            textLat.setText(latitude);
            textLong.setText(longitude);
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int i)
    {
        // Unused
    }

    @Override
    public void onLocationChanged(Location location)
    {
        if (location == null)
            throw new NullPointerException();
        currentLocation = location;
        geomagneticField = new GeomagneticField((float)currentLocation.getLatitude(), (float)currentLocation.getLongitude(),
                           (float)currentLocation.getAltitude(), System.currentTimeMillis());
    }

    @Override
    public void onStatusChanged(String s, int i, Bundle bundle)
    {
        // Unused
    }

    @Override
    public void onProviderEnabled(String s)
    {
        // Unused
    }

    @Override
    public void onProviderDisabled(String s)
    {
        // Unused
    }
}
