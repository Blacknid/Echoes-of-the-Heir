package ai;

import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.PriorityQueue;

import entity.Entity;
import main.GamePanel;

public class PathFinder {

    GamePanel gp;
    Node[][] nodes;
    // OPTIMIZATION: PriorityQueue for O(log n) best-node selection instead of O(n) linear scan
    PriorityQueue<Node> openQueue = new PriorityQueue<>(64, Comparator.comparingInt((Node n) -> n.fCost).thenComparingInt(n -> n.gCost));
    public ArrayList<Node> pathList = new ArrayList<>();

    Node startNode, goalNode, currentNode;
    boolean goalReached;
    int step;

    // OPTIMIZATION: Reusable Rectangle to avoid per-node allocation in collision checks
    private final Rectangle futureHitbox = new Rectangle();
    private final Rectangle tempHitbox = new Rectangle();

    public PathFinder(GamePanel gp) {
        this.gp = gp;
        instantiateNodes();
    }

    public void instantiateNodes() {
        nodes = new Node[gp.maxWorldCol][gp.maxWorldRow];
        for (int col = 0; col < gp.maxWorldCol; col++) {
            for (int row = 0; row < gp.maxWorldRow; row++) {
                nodes[col][row] = new Node(col, row);
            }
        }
    }

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

        openQueue.clear();
        pathList.clear();
        goalReached = false;
        step = 0;
    }

    public void setNodes(int startCol, int startRow,
                         int goalCol, int goalRow,
                         Entity entity) {

        resetNodes();

        if (startCol < 0 || startCol >= gp.maxWorldCol ||
            startRow < 0 || startRow >= gp.maxWorldRow ||
            goalCol < 0 || goalCol >= gp.maxWorldCol ||
            goalRow < 0 || goalRow >= gp.maxWorldRow) {
            return;
        }

        startNode = nodes[startCol][startRow];
        goalNode = nodes[goalCol][goalRow];
        currentNode = startNode;

        openQueue.add(currentNode);
        currentNode.open = true;

        // Determine if this entity is chasing the player
        int playerTileCol = gp.player.getTileCol();
        int playerTileRow = gp.player.getTileRow();
        boolean chasingPlayer = (goalCol == playerTileCol && goalRow == playerTileRow);

        // OPTIMIZATION: Only check nodes in a bounding box around start→goal (not the entire map)
        int dist = Math.abs(startCol - goalCol) + Math.abs(startRow - goalRow);
        int margin = Math.max(10, dist / 2); // scale margin with path length
        int minCol = Math.max(0, Math.min(startCol, goalCol) - margin);
        int maxCol = Math.min(gp.maxWorldCol - 1, Math.max(startCol, goalCol) + margin);
        int minRow = Math.max(0, Math.min(startRow, goalRow) - margin);
        int maxRow = Math.min(gp.maxWorldRow - 1, Math.max(startRow, goalRow) + margin);

        for (int col = minCol; col <= maxCol; col++) {
            for (int row = minRow; row <= maxRow; row++) {
                Node node = nodes[col][row];
                if (isCollisionForEntityAtNode(col, row, entity, chasingPlayer)) {
                    node.solid = true;
                }
                calculateCost(node);
            }
        }

        startNode.solid = false;
        goalNode.solid = false;
    }

    private boolean isCollisionForEntityAtNode(int col, int row, Entity entity, boolean chasingPlayer) {
        int hitboxWidth = entity.solidArea.width > 0 ? entity.solidArea.width : gp.tileSize;
        int hitboxHeight = entity.solidArea.height > 0 ? entity.solidArea.height : gp.tileSize;

        futureHitbox.setBounds(
                col * gp.tileSize + entity.solidArea.x,
                row * gp.tileSize + entity.solidArea.y,
                hitboxWidth,
                hitboxHeight
        );

        // Check collision shapes via spatial grid (O(k) instead of O(n))
        if (gp.cChecker.rectHitsCollision(futureHitbox)) {
            return true;
        }

        // Check player as obstacle ONLY if not chasing the player
        if (!chasingPlayer) {
            tempHitbox.setBounds(
                    gp.player.worldX + gp.player.solidArea.x,
                    gp.player.worldY + gp.player.solidArea.y,
                    gp.player.solidArea.width,
                    gp.player.solidArea.height
            );
            if (futureHitbox.intersects(tempHitbox)) {
                return true;
            }
        }

        // Check obstacle entities
        for (int i = 0; i < gp.obj.length; i++) {
            Entity obj = gp.obj[i];
            if (obj != null && obj.collision && obj.type == Entity.TYPE_OBSTACLE) {
                tempHitbox.setBounds(
                        obj.worldX + obj.solidArea.x,
                        obj.worldY + obj.solidArea.y,
                        obj.solidArea.width,
                        obj.solidArea.height
                );
                if (futureHitbox.intersects(tempHitbox)) {
                    return true;
                }
            }
        }

        return false;
    }

    public void calculateCost(Node node) {
        // Manhattan distance
        int dxStart = Math.abs(node.col - startNode.col);
        int dyStart = Math.abs(node.row - startNode.row);
        node.gCost = dxStart + dyStart;

        int dxGoal = Math.abs(node.col - goalNode.col);
        int dyGoal = Math.abs(node.row - goalNode.row);
        node.hCost = dxGoal + dyGoal;

        node.fCost = node.gCost + node.hCost;
    }

    public boolean search() {

        while (!goalReached && step < 500) {

            currentNode.checked = true;

            int col = currentNode.col;
            int row = currentNode.row;

            if (row - 1 >= 0) openNode(nodes[col][row - 1]);
            if (col - 1 >= 0) openNode(nodes[col - 1][row]);
            if (row + 1 < gp.maxWorldRow) openNode(nodes[col][row + 1]);
            if (col + 1 < gp.maxWorldCol) openNode(nodes[col + 1][row]);

            if (openQueue.isEmpty()) break;

            // OPTIMIZATION: O(log n) poll instead of O(n) linear scan
            currentNode = openQueue.poll();

            if (currentNode == goalNode) {
                goalReached = true;
                trackPath();
            }
            step++;
        }
        return goalReached;
    }

    private void openNode(Node node) {
        if (!node.open && !node.checked && !node.solid) {
            node.open = true;
            node.parent = currentNode;
            // Recalculate proper g-cost through current path
            node.gCost = currentNode.gCost + 1;
            node.fCost = node.gCost + node.hCost;
            openQueue.add(node);
        }
    }

    private void trackPath() {
        Node current = goalNode;
        while (current != startNode) {
            pathList.add(0, current);
            current = current.parent;
        }
    }
}