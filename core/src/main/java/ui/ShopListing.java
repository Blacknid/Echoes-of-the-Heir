package ui;

/**
 * One entry in an NPC's shop stock (see {@code entity.Entity#shopItems}), built by
 * {@code data.NPCFactory} from the "shop.items" block in npcs.json.
 */
public class ShopListing {
    public final String itemId;   // ItemFactory id
    public final int price;       // coins the PLAYER pays to buy this
    public final int maxStock;    // -1 = infinite, otherwise starting/max count
    public int stock;             // remaining count; ignored (never decremented) when maxStock < 0

    public ShopListing(String itemId, int price, int maxStock) {
        this.itemId = itemId;
        this.price = price;
        this.maxStock = maxStock;
        this.stock = maxStock;
    }

    public boolean infinite() { return maxStock < 0; }
    public boolean inStock()  { return infinite() || stock > 0; }
}
