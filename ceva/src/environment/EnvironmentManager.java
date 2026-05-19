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
     * Dacă >= 0, suprascrie întunericul ciclului zi/noapte.
     * Setat din proprietatea Tiled 'ambientLight'. Resetează la -1 pentru a reactiva ciclul.
     */
    public float pinnedFilterAlpha = -1f;
    
    public final int dayDuration = 10800;      // 3 minute — timp * 60 (FPS) = cadre totale pentru ciclul zi/noapte
    public final int transitionDuration = 3600; // tranzitie 2 minute (3600 cadre la 60 FPS)

    float transitionSpeed = 0.95f / transitionDuration;
    public static final int WEATHER_CLEAR = 0;
    public static final int WEATHER_RAIN  = 1;
    public static final int WEATHER_STORM = 2;
    public static final int WEATHER_SNOW  = 3;

    public int weatherState = WEATHER_CLEAR;
    private int weatherTarget = WEATHER_CLEAR;
    public float weatherIntensity = 1f;
    private static final float WEATHER_FADE_SPEED = 0.008f; // ~2 secunde pana la intensitate maxima
    /** Dacă >= 0, vremea este fixată din Tiled și ciclul automat este dezactivat. */
    public int pinnedWeather = -1;

    /** Dacă false, ciclul zi/noapte și cel meteo sunt dezactivate pe această hartă. */
    public boolean weatherCycleEnabled = true;

    // Ciclu meteo automat
    private int weatherTimer = 0;
    private static final int WEATHER_CYCLE_MIN = 3600;  // 1 minut
    private static final int WEATHER_CYCLE_MAX = 7200;  // 2 minute
    private int nextWeatherChange;

    public EnvironmentManager(GamePanel gp) {
        this.gp = gp;
        nextWeatherChange = WEATHER_CYCLE_MIN + (int)(Math.random() * (WEATHER_CYCLE_MAX - WEATHER_CYCLE_MIN));
    }

    public void setup() {
        lightning = new Lightning(gp);
    }

    public int getPlayerLightRadius() {
        return lightning != null ? lightning.playerLightRadius : 7;
    }

    public void setPlayerLightRadius(int radiusTiles) {
        if (lightning != null) {
            lightning.playerLightRadius = Math.max(1, Math.min(30, radiusTiles));
        }
    }

    public void update() {
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

    public void setWeather(int type) {
        if (type == WEATHER_CLEAR) {
            weatherTarget = WEATHER_CLEAR;
        } else {
            weatherTarget = type;
        }
    }

    /**
     * Setează vremea după un sir de caractere (din proprietatile hartii Tiled).
     * Valori acceptate: "CLEAR", "RAIN", "STORM", "SNOW" (insensibil la majuscule).
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
     * Sare direct la o stare de timp a zilei, setata din proprietatile hartii Tiled.
     * 0 = zi, 1 = asfintit, 2 = noapte, 3 = zori
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
        float weatherDarkness = 0f;
        if (weatherState == WEATHER_RAIN) {
            weatherDarkness = 0.35f * weatherIntensity;
        } else if (weatherState == WEATHER_STORM) {
            weatherDarkness = 0.50f * weatherIntensity;
        }
        float effectiveAlpha = Math.min(0.95f, filterAlpha + weatherDarkness);

        if (effectiveAlpha > 0.02f) {
            // Downscale adaptiv: reduce rezolutia overlay-ului sub sarcina mare
            if (lightning.lightDownscale < 4 && gp.currentFPS > 0 && gp.currentFPS < 30) {
                lightning.lightDownscale = 4;
            } else if (lightning.lightDownscale > 2 && (gp.currentFPS <= 0 || gp.currentFPS >= 45)) {
                lightning.lightDownscale = 2;
            }
            lightning.draw(g2, effectiveAlpha);
        }
    }
}
