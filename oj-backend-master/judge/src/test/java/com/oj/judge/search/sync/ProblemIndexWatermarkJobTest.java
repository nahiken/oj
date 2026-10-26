package com.oj.judge.search.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.oj.judge.model.entity.Problem;

/**
 * 水位推进的三条路径：写失败不越过、满批只到末行、未满批到扫描起点。
 */
class ProblemIndexWatermarkJobTest {

    private static final long OVERLAP = 5_000L;
    private static final long SCAN_START = 1_000_000L;

    private static Problem at(long id, long updateTime) {
        Problem problem = new Problem();
        problem.setId(id);
        problem.setUpdateTime(new Date(updateTime));
        return problem;
    }

    private static List<Problem> rows(int count, long firstUpdateTime) {
        List<Problem> rows = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            rows.add(at(i + 1, firstUpdateTime + i * 1_000L));
        }
        return rows;
    }

    @Test
    void advancesToScanStartWhenTheBatchIsDrained() {
        List<Problem> changed = rows(3, SCAN_START - 3_000);

        assertEquals(SCAN_START, ProblemIndexWatermarkJob.advanceWatermark(
                changed, changed.size(), 500, SCAN_START, OVERLAP));
    }

    @Test
    void stopsBeforeTheFailedRow() {
        List<Problem> changed = rows(3, SCAN_START - 3_000);

        // 第一条就失败：水位退到它之前，下一轮还能扫到它
        assertEquals(SCAN_START - 3_000 - OVERLAP, ProblemIndexWatermarkJob.advanceWatermark(
                changed, 0, 500, SCAN_START, OVERLAP));
    }

    @Test
    void pausesAtTheLastSuccessfulRowWhenALaterOneFails() {
        List<Problem> changed = rows(3, SCAN_START - 3_000);

        assertEquals(SCAN_START - 2_000 - OVERLAP, ProblemIndexWatermarkJob.advanceWatermark(
                changed, 1, 500, SCAN_START, OVERLAP));
    }

    @Test
    void advancesOnlyToTheLastRowWhenTheBatchIsFull() {
        List<Problem> changed = rows(3, SCAN_START - 3_000);

        assertEquals(SCAN_START - 1_000, ProblemIndexWatermarkJob.advanceWatermark(
                changed, changed.size(), 3, SCAN_START, OVERLAP));
    }

    @Test
    void emptyBatchStillAdvancesSoTheWindowDoesNotRescanForever() {
        assertEquals(SCAN_START, ProblemIndexWatermarkJob.advanceWatermark(
                List.of(), 0, 500, SCAN_START, OVERLAP));
    }
}
