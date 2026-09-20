package com.n2bank.infrastructure.postgres.mapper;

import com.n2bank.domain.model.JournalEntry;
import com.n2bank.domain.model.Posting;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;

/**
 * Builds a journal entry from its header row and its separately collected posting rows.
 *
 * <p>A journal entry is an aggregate rather than a single row, so this mapper intentionally does
 * not implement {@link RowMapper}.
 */
public final class JournalEntryMapper {

  public JournalEntry map(ResultSet entryRow, List<Posting> postings) throws SQLException {
    Objects.requireNonNull(entryRow, "Journal entry result set cannot be null");
    Objects.requireNonNull(postings, "Postings cannot be null");
    throw mappingNotImplemented();
  }

  private UnsupportedOperationException mappingNotImplemented() {
    return new UnsupportedOperationException("Journal entry mapping is not implemented yet");
  }
}
