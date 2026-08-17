package com.winlator.star.inputcontrols;

import java.util.Arrays;

final class ButtonGridTouchState {
    static final int NO_CELL = -1;
    private static final int UNTRACKED = -2;
    private static final int MAX_POINTERS = 32;

    private final int[] pointerCells = new int[MAX_POINTERS];
    private final int[] cellOwnerCounts;
    private int trackedPointerCount;

    ButtonGridTouchState(int cellCount) {
        cellOwnerCounts = new int[Math.max(1, cellCount)];
        Arrays.fill(pointerCells, UNTRACKED);
    }

    boolean trackPointer(int pointerId, int cell) {
        if (!isValidPointer(pointerId) || pointerCells[pointerId] != UNTRACKED || !isValidCellOrNone(cell)) {
            return false;
        }
        pointerCells[pointerId] = cell;
        if (cell != NO_CELL) cellOwnerCounts[cell]++;
        trackedPointerCount++;
        return true;
    }

    boolean movePointer(int pointerId, int cell) {
        if (!isPointerTracked(pointerId) || !isValidCellOrNone(cell)) return false;
        int oldCell = pointerCells[pointerId];
        if (oldCell == cell) return true;
        if (oldCell != NO_CELL) cellOwnerCounts[oldCell]--;
        pointerCells[pointerId] = cell;
        if (cell != NO_CELL) cellOwnerCounts[cell]++;
        return true;
    }

    boolean untrackPointer(int pointerId) {
        if (!isPointerTracked(pointerId)) return false;
        int cell = pointerCells[pointerId];
        if (cell != NO_CELL) cellOwnerCounts[cell]--;
        pointerCells[pointerId] = UNTRACKED;
        trackedPointerCount--;
        return true;
    }

    boolean isPointerTracked(int pointerId) {
        return isValidPointer(pointerId) && pointerCells[pointerId] != UNTRACKED;
    }

    int getPointerCell(int pointerId) {
        return isPointerTracked(pointerId) ? pointerCells[pointerId] : NO_CELL;
    }

    int getCellOwnerCount(int cell) {
        return isValidCell(cell) ? cellOwnerCounts[cell] : 0;
    }

    boolean hasTrackedPointers() {
        return trackedPointerCount > 0;
    }

    void clear() {
        Arrays.fill(pointerCells, UNTRACKED);
        Arrays.fill(cellOwnerCounts, 0);
        trackedPointerCount = 0;
    }

    private boolean isValidPointer(int pointerId) {
        return pointerId >= 0 && pointerId < pointerCells.length;
    }

    private boolean isValidCell(int cell) {
        return cell >= 0 && cell < cellOwnerCounts.length;
    }

    private boolean isValidCellOrNone(int cell) {
        return cell == NO_CELL || isValidCell(cell);
    }
}
