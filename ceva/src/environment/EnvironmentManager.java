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

    /**
     * When >= 0, overrides the day/night cycle darkness.
     * Set from Tiled map 'ambientLight' property. Reset to -1 to re-enable the cycle.
     */
    public float pinnedFilterAlpha = -1f;
    
    public final int dayDuration = 10800;      // 3 minutes, time * 60 (FPS) = total frames for day/night cycle
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
    /** When >= 0 the weather is pinned by Tiled and the auto-cycle is suppressed. */
    public int pinnedWeather = -1;

    /** When false, both day/night and auto-weather cycling are disabled for this map. */
    public boolean weatherCycleEnabled = true;

    // Auto-weather cycle
    private int weatherTimer = 0;
    private static final int WEATHER_CYCLE_MIN = 3600;  // 1 min
    private static final int WEATHER_CYCLE_MAX = 7200;  // 2 min
    private int nextWeatherChange;

    public EnvironmentManager(GamePanel gp) {
        this.gp = gp;
        nextWeatherChange = WEATHER_CYCLE_MIN + (int)(Math.random() * (WEATHER_CYCLE_MAX - WEATHER_CYCLE_MIN));
    }

    public void setup() {
        lightning = new Lightning(gp);
    }

    /** Get the player light radius (in tiles). */
    public int getPlayerLightRadius() {
        return lightning != null ? lightning.playerLightRadius : 7;
    }

    /**
     * Set the player light radius (in tiles). Clamped to [1, 30].
     * The new mask is generated automatically on next draw.
     */
    public void setPlayerLightRadius(int radiusTiles) {
        if (lightning != null) {
            lightning.playerLightRadius = Math.max(1, Math.min(30, radiusTiles));
        }
    }

    public void update() {
        // Day/night cycle — skip when ambient light is pinned OR weatherCycle is disabled
        if (pinnedFilterAlpha >= 0f) {
            filterAlpha = pinnedFilterAlpha;
        } else if (weatherCycleEnabled) {
            if (dayState == day) {
                dayCounter++;
                if (dayCounter > dayDuration) {
                    dayState = dusk;
                    dayCounter = 0;
                }
            }
            if (dayState == dusk) {
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
                filterAlpha -= transitionSpeed;
                if (filterAlpha <= 0f) {
                    filterAlpha = 0f;
                    dayState = day;
                }
            }
        }

        // Weather intensity fade (always runs so transitions finish smoothly)
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

        // Auto-weather cycle — suppressed when pinned or cycling disabled
        if (pinnedWeather < 0 && weatherCycleEnabled) {
            weatherTimer++;
            if (weatherTimer >= nextWeatherChange) {
                weatherTimer = 0;
                nextWeatherChange = WEATHER_CYCLE_MIN + (int)(Math.random() * (WEATHER_CYCLE_MAX - WEATHER_CYCLE_MIN));
                if (weatherTarget == WEATHER_CLEAR) {
                    double roll = Math.random();
                    if (roll < 0.45) setWeather(WEATHER_RAIN);
                    else if (roll < 0.65) setWeather(WEATHER_STORM);
                    else if (roll < 0.85) setWeather(WEATHER_SNOW);
                } else {
                    setWeather(WEATHER_CLEAR);
                }
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

    /**
     * Set weather by name string (from Tiled map properties).
     * Accepted values: "CLEAR", "RAIN", "STORM", "SNOW" (case-insensitive).
     */
    public void setWeatherByName(String name) {
        switch (name.trim().toUpperCase()) {
            case "RAIN"  -> { pinnedWeather = WEATHER_RAIN;  setWeather(WEATHER_RAIN); }
            case "STORM" -> { pinnedWeather = WEATHER_STORM; setWeather(WEATHER_STORM); }
            case "SNOW"  -> { pinnedWeather = WEATHER_SNOW;  setWeather(WEATHER_SNOW); }
            default      -> { pinnedWeather = WEATHER_CLEAR; setWeather(WEATHER_CLEAR); }
        }
    }

    /**
     * Jump directly to a time-of-day state set from Tiled map properties.
     * 0 = day, 1 = dusk, 2 = night, 3 = dawn
     */
    public void setTimeOfDay(int state) {
        dayState = Math.max(day, Math.min(dawn, state));
        switch (dayState) {
            case day   -> filterAlpha = 0f;
            case night -> filterAlpha = 0.95f;
            case dusk  -> filterAlpha = 0.45f;
            case dawn  -> filterAlpha = 0.45f;
        }
        dayCounter = 0;
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
