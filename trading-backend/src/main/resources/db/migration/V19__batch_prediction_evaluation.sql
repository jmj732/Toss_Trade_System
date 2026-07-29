CREATE INDEX ix_analysis_predictions_due
    ON analysis_predictions (predicted_at, id);
