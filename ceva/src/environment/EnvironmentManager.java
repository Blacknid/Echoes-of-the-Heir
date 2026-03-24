package environment;

import java.awt.Graphics2D;
import main.GamePanel;

public class EnvironmentManager {

    GamePanel gp;
    public Lightning lightning;

    public int dayState = 0;
    public final int day = 0;
    public final int dusk = 1;
    public final int night = 2;
    public final int dawn = 3;

    public float filterAlpha = 0f; 
    public int dayCounter = 0;
    
    public final int dayDuration = 100;      // 3 minutes, time * 60 (FPS) = total frames for day/night cycle
    public final int transitionDuration = 3600; // 2 minute transition (3600 frames at 60 FPS)

    // ADD THIS LINE HERE:
    // This calculates exactly how much to fade per frame
    float transitionSpeed = 0.95f / transitionDuration; 

    // ==================== WEATHER SYSTEM ====================
    public static final int WEATHER_CLEAR = 0;
    public static final int WEATHER_RAIN  = 1;
    public static final int WEATHER_STORM = 2;
    public static final int WEATHER_SNOW  = 3;

    public int weatherState = WEATHER_CLEAR;
    private int weatherTarget = WEATHER_CLEAR; // For smooth transitions
    public float weatherIntensity = 1f;        // 0→1 smooth fade
    private static final float WEATHER_FADE_SPEED = 0.008f; // ~2 sec to full

    // Auto-weather cycle
    private int weatherTimer = 0;
    private static final int WEATHER_CYCLE_MIN = 18000; // 5 min
    private static final int WEATHER_CYCLE_MAX = 28800; // 8 min
    private int nextWeatherChange;

    public EnvironmentManager(GamePanel gp) {
        this.gp = gp;
        nextWeatherChange = WEATHER_CYCLE_MIN + (int)(Math.random() * (WEATHER_CYCLE_MAX - WEATHER_CYCLE_MIN));
    }

    public void setup() {
        lightning = new Lightning(gp);
    }

    public void update() {
        // Day/night cycle
        if (dayState == day) {
            dayCounter++;
            if (dayCounter > dayDuration) {
                dayState = dusk;
                dayCounter = 0;
            }
        }
        if (dayState == dusk) {
            // CHANGE THIS LINE: Use the speed variable
            filterAlpha += transitionSpeed; 
            
            if (filterAlpha >= 0.95f) {
                filterAlpha = 0.95f;
                dayState = night;
            }
        }
        if (dayState == night) {
            dayCounter++;
            if (dayCounter > dayDuration) {
                dayState = dawn;
                dayCounter = 0;
            }
        }
        if (dayState == dawn) {
            // CHANGE THIS LINE: Use the speed variable
            filterAlpha -= transitionSpeed; 

            if (filterAlpha <= 0f) {
                filterAlpha = 0f;
                dayState = day;
            }
        }

        // Weather intensity fade
        if (weatherTarget != WEATHER_CLEAR) {
            weatherState = weatherTarget;
            if (weatherIntensity < 1f) {
                weatherIntensity = Math.min(1f, weatherIntensity + WEATHER_FADE_SPEED);
            }
        } else {
            if (weatherIntensity > 0f) {
                weatherIntensity = Math.max(0f, weatherIntensity - WEATHER_FADE_SPEED);
                if (weatherIntensity <= 0f) {
                    weatherState = WEATHER_CLEAR;
                }
            }
        }

        // Auto-weather cycle
        weatherTimer++;
        if (weatherTimer >= nextWeatherChange) {
            weatherTimer = 0;
            nextWeatherChange = WEATHER_CYCLE_MIN + (int)(Math.random() * (WEATHER_CYCLE_MAX - WEATHER_CYCLE_MIN));
            if (weatherTarget == WEATHER_CLEAR) {
                double roll = Math.random();
                if (roll < 0.45) setWeather(WEATHER_RAIN);
                else if (roll < 0.65) setWeather(WEATHER_STORM);
                else if (roll < 0.85) setWeather(WEATHER_SNOW);
                // 15% chance stays clear
            } else {
                setWeather(WEATHER_CLEAR);
            }
        }
    }

    /** Set weather with smooth transition. */
    public void setWeather(int type) {
        if (type == WEATHER_CLEAR) {
            weatherTarget = WEATHER_CLEAR;
        } else {
            weatherTarget = type;
        }
    }

    public void draw(Graphics2D g2) {
        // Darken the sky during rain/storm for atmosphere
        float weatherDarkness = 0f;
        if (weatherState == WEATHER_RAIN) {
            weatherDarkness = 0.35f * weatherIntensity;
        } else if (weatherState == WEATHER_STORM) {
            weatherDarkness = 0.50f * weatherIntensity;
        }
        float effectiveAlpha = Math.min(0.95f, filterAlpha + weatherDarkness);

        if (dayState != day || effectiveAlpha > 0) {
            lightning.draw(g2, effectiveAlpha);
        }
    }
}
