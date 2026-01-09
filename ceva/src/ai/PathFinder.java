package ai;

import main.GamePanel;
import entity.Entity;
import java.util.ArrayList;
import java.awt.Rectangle;

public class PathFinder {

    GamePanel gp;
    Node[][] nodes;
    ArrayList<Node> openList = new ArrayList<>();
    public ArrayList<Node> pathList = new ArrayList<>();

    Node startNode, goalNode, currentNode;
    boolean goalReached;
    int step;

    public PathFinder(GamePanel gp) {
        this.gp = gp;
        instantiateNodes();
    }

    // -------------------------------------------------
    // Init nodes
    // -------------------------------------------------
    public void instantiateNodes() {
        nodes = new Node[gp.maxWorldCol][gp.maxWorldRow];

        for (int col = 0; col < gp.maxWorldCol; col++) {
            for (int row = 0; row < gp.maxWorldRow; row++) {
                nodes[col][row] = new Node(col, row);
            }
        }
    }

    // -------------------------------------------------
    // Reset
    // -------------------------------------------------
    public void resetNodes() {
        for (int col = 0; col < gp.maxWorldCol; col++) {
            for (int row = 0; row < gp.maxWorldRow; row++) {
                Node n = nodes[col][row];
                n.open = false;
                n.checked = false;
                n.solid = false;
                n.parent = null;
            }
        }

        openList.clear();
        pathList.clear();
        goalReached = false;
        step = 0;
    }

    // -------------------------------------------------
    // Setup path
    // -------------------------------------------------
    public void setNodes(int startCol, int startRow,
                         int goalCol, int goalRow,
                         Entity entity) {

        resetNodes();

        startNode = nodes[startCol][startRow];
        goalNode = nodes[goalCol][goalRow];
        currentNode = startNode;

        openList.add(currentNode);

        // Mark solid nodes using TileManager collisionRects
        for (int col = 0; col < gp.maxWorldCol; col++) {
            for (int row = 0; row < gp.maxWorldRow; row++) {

                int worldX = col * gp.tileSize;
                int worldY = row * gp.tileSize;

                if (isCollisionTile(worldX, worldY)) {
                    nodes[col][row].solid = true;
                }

                calculateCost(nodes[col][row]);
            }
        }
    }

    // -------------------------------------------------
    // Collision check via TileManager
    // -------------------------------------------------
    private boolean isCollisionTile(int worldX, int worldY) {
        Rectangle tileRect = new Rectangle(
                worldX,
                worldY,
                gp.tileSize,
                gp.tileSize
        );

        for (Rectangle r : gp.tileM.collisionRects) {
            if (r.intersects(tileRect)) {
                return true;
            }
        }
        return false;
    }

    // -------------------------------------------------
    // Cost
    // -------------------------------------------------
    public void calculateCost(Node node) {
        int dxStart = Math.abs(node.col - startNode.col);
        int dyStart = Math.abs(node.row - startNode.row);
        node.gCost = dxStart + dyStart;

        int dxGoal = Math.abs(node.col - goalNode.col);
        int dyGoal = Math.abs(node.row - goalNode.row);
        node.hCost = dxGoal + dyGoal;

        node.fCost = node.gCost + node.hCost;
    }

    // -------------------------------------------------
    // Search (A*)
    // -------------------------------------------------
    public boolean search() {

        while (!goalReached && step < 500) {

            currentNode.checked = true;
            openList.remove(currentNode);

            int col = currentNode.col;
            int row = currentNode.row;

            if (row - 1 >= 0) openNode(nodes[col][row - 1]);     // up
            if (col - 1 >= 0) openNode(nodes[col - 1][row]);     // left
            if (row + 1 < gp.maxWorldRow) openNode(nodes[col][row + 1]); // down
            if (col + 1 < gp.maxWorldCol) openNode(nodes[col + 1][row]); // right

            if (openList.isEmpty()) break;

            int bestIndex = 0;
            int bestFCost = Integer.MAX_VALUE;

            for (int i = 0; i < openList.size(); i++) {
                Node n = openList.get(i);

                if (n.fCost < bestFCost ||
                    (n.fCost == bestFCost && n.gCost < openList.get(bestIndex).gCost)) {
                    bestIndex = i;
                    bestFCost = n.fCost;
                }
            }

            currentNode = openList.get(bestIndex);

            if (currentNode == goalNode) {
                goalReached = true;
                trackPath();
            }

            step++;
        }

        return goalReached;
    }

    // -------------------------------------------------
    private void openNode(Node node) {
        if (!node.open && !node.checked && !node.solid) {
            node.open = true;
            node.parent = currentNode;
            openList.add(node);
        }
    }

    // -------------------------------------------------
    private void trackPath() {
        Node current = goalNode;

        while (current != startNode) {
            pathList.add(0, current);
            current = current.parent;
        }
    }
}
