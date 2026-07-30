ALTER TABLE analysis_predictions
    ADD COLUMN client_request_id VARCHAR(100);

ALTER TABLE analysis_predictions
    ADD CONSTRAINT uq_analysis_predictions_user_client_request
    UNIQUE (user_id, client_request_id);
