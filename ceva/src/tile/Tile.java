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
}
