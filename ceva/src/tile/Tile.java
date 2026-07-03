package tile;

import java.awt.image.BufferedImage;

public class Tile {

    public BufferedImage image;
    public boolean collision = false;
    public int drawOffsetY = 0;
    // When depth-sorting a multi-row structure, add this many pixels to the tile's world Y
    // so that the top tiles sort together with the bottom row instead of popping in/out
    // independently. Set to +tileSize on every "top half" tile of a 2-tall structure.
    public int sortYOffset = 0;
    public boolean foreground = false;
    public boolean background = false;
    /**
     * Per-tile depth-sort override.
     * Set <property name="depthSort" type="bool" value="true"/> on the tile in Tiled
     * to make ONLY this tile (e.g. a campfire animation) depth-sort against entities,
     * without forcing the entire tileset into depth-sort mode.
     */
    public boolean depthSort = false;
    /**
     * When true, this tile visually reflects nearby light sources — a white/warm
     * highlight is composited over it proportional to the distance to the closest light.
     * Set <property name="reflectsLight" type="bool" value="true"/> in Tiled.
     */
    public boolean reflectsLight = false;
}
