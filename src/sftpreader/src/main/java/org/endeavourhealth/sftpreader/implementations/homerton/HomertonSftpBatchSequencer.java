package org.endeavourhealth.sftpreader.implementations.homerton;

import org.endeavourhealth.sftpreader.implementations.SftpBatchSequencer;
import org.endeavourhealth.sftpreader.implementations.homerton.HomertonSftpFilenameParser;
import org.endeavourhealth.sftpreader.model.db.Batch;
import org.endeavourhealth.sftpreader.model.exceptions.SftpFilenameParseException;
import org.endeavourhealth.sftpreader.model.exceptions.SftpValidationException;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class HomertonSftpBatchSequencer extends SftpBatchSequencer {

    @Override
    // There is only one batch for Homerton
    public Map<Batch, Integer> determineBatchSequenceNumbers(List<Batch> incompleteBatches, int nextSequenceNumber, Batch lastCompleteBatch) throws SftpValidationException, SftpFilenameParseException {
        Map<Batch, Integer> result = new HashMap<>();

        List<Batch> orderedIncompleteBatches = incompleteBatches
                .stream()
                .sorted(Comparator.comparing(t -> HomertonSftpFilenameParser.parseBatchIdentifier(t.getBatchIdentifier())))
                .collect(Collectors.toList());

        for (Batch batch : orderedIncompleteBatches)
            result.put(batch, nextSequenceNumber++);

        return result;
    }
}
