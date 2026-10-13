package com.runledger.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One row in the batches list. The unit is {@code (teamId, batchName)}:
 * a batch name is only unique within a team, so two teams can both have a
 * batch called "sweep" and they show as two entries here.
 *
 * <p>{@code latestAt} is the most recent {@code run.created_at} among
 * non-superseded rows in the batch, so a supervisor can see which batches
 * are active.
 */
public record BatchSummary(
        UUID teamId,
        String teamName,
        String batchName,
        long runCount,
        OffsetDateTime latestAt
) {}