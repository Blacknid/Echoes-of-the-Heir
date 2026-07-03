package ai;

public class Node {

    Node parent;
    public int col;
    public int row;
    int gCost;
    int hCost;
    int fCost;
    boolean solid;
    boolean open;
    boolean checked;
    // OPTIMIZATION: lazy-evaluation state — only touch nodes A* actually visits.
    // `touched` = node was modified during this search (needs reset before next search)
    // `collisionChecked` = solid flag is valid (collision test already ran)
    // `hCostValid` = hCost has been computed for this search's goal
    boolean touched;
    boolean collisionChecked;
    boolean hCostValid;

    public Node(int col, int row) {
        this.col = col;
        this.row = row;
    }
}