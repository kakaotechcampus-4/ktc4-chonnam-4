ALTER TABLE roleplay_voice_input_failure ALTER COLUMN input_fingerprint DROP NOT NULL;
ALTER TABLE roleplay_voice_input_failure
    ADD CONSTRAINT unidentified_voice_upload_reason
    CHECK (input_fingerprint IS NOT NULL OR failure_reason = 'UPLOAD_TOO_LARGE');
