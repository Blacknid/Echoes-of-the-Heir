package ai;

import gfx.geom.Rect;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.PriorityQueue;

import entity.Entity;
import main.GamePanel;

public class PathFinder {

    GamePanel gp;
    Node[][] nodes;
    PriorityQueue<Node> openQueue = new PriorityQueue<>(64, Comparator.comparingInt((Node n) -> n.fCost).thenComparingInt(n -> n.gCost));
    public ArrayList<Node> pathList = new ArrayList<>();

    Node startNode, goalNode, currentNode;
    boolean goalReached;
    int step;

    private final Rect futureHitbox = new Rect();
    private final Rect tempHitbox = new Rect();

    // Track only nodes modified this search so resetNodes() is O(visited)
    // instead of O(maxWorldCol × maxWorldRow). Typical search visits <200 nodes vs 10000+.
    private final ArrayList<Node> touchedNodes = new ArrayList<>(256);

    private Entity searchEntity;
    private boolean searchChasingPlayer;

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
        for (int i = 0, n = touchedNodes.size(); i < n; i++) {
            Node node = touchedNodes.get(i);
            node.open = false;
            node.checked = false;
            node.solid = false;
            node.parent = null;
            node.touched = false;
            node.collisionChecked = false;
            node.hCostValid = false;
        }
        touchedNodes.clear();

        openQueue.clear();
        pathList.clear();
        goalReached = false;
        step = 0;
    }

    /** Mark a node as touched so it will be reset before the next search. */
    private void markTouched(Node node) {
        if (!node.touched) {
            node.touched = true;
            touchedNodes.add(node);
        }
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

        this.searchEntity = entity;
        int playerTileCol = gp.player.getTileCol();
        int playerTileRow = gp.player.getTileRow();
        this.searchChasingPlayer = (goalCol == playerTileCol && goalRow == playerTileRow);

        startNode.gCost = 0;
        startNode.hCost = Math.abs(startCol - goalCol) + Math.abs(startRow - goalRow);
        startNode.fCost = startNode.hCost;
        startNode.hCostValid = true;
        startNode.collisionChecked = true;
        startNode.solid = false;
        startNode.open = true;
        markTouched(startNode);

        goalNode.collisionChecked = true;
        goalNode.solid = false;
        markTouched(goalNode);

        openQueue.add(startNode);
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

        if (gp.cChecker.rectHitsCollision(futureHitbox)) {
            return true;
        }

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
        if (node.open || node.checked) return;

        if (!node.collisionChecked) {
            node.solid = isCollisionForEntityAtNode(
                    node.col, node.row, searchEntity, searchChasingPlayer);
            node.collisionChecked = true;
            markTouched(node);
        }
        if (node.solid) return;

        node.open = true;
        node.parent = currentNode;
        node.gCost = currentNode.gCost + 1;

        if (!node.hCostValid) {
            int dxGoal = Math.abs(node.col - goalNode.col);
            int dyGoal = Math.abs(node.row - goalNode.row);
            node.hCost = dxGoal + dyGoal;
            node.hCostValid = true;
        }

        node.fCost = node.gCost + node.hCost;
        markTouched(node);
        openQueue.add(node);
    }

    private void trackPath() {
        Node current = goalNode;
        while (current != startNode && current != null) {
            pathList.add(current);
            current = current.parent;
        }
        int lo = 0, hi = pathList.size() - 1;
        while (lo < hi) {
            Node tmp = pathList.get(lo);
            pathList.set(lo, pathList.get(hi));
            pathList.set(hi, tmp);
            lo++; hi--;
        }
    }
}