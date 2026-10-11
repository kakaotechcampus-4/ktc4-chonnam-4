-- Preserve applied V7. Permanent deletion is orchestrated by DeletionService.
ALTER TABLE roleplay_session
    DROP CONSTRAINT roleplay_session_activity_id_child_id_fkey;
ALTER TABLE roleplay_session
    ADD CONSTRAINT fk_roleplay_session_activity_child
    FOREIGN KEY (activity_id, child_id)
    REFERENCES activity(activity_id, child_id) ON DELETE NO ACTION;

ALTER TABLE roleplay_turn
    DROP CONSTRAINT roleplay_turn_session_id_fkey;
ALTER TABLE roleplay_turn
    ADD CONSTRAINT fk_roleplay_turn_session
    FOREIGN KEY (session_id)
    REFERENCES roleplay_session(session_id) ON DELETE NO ACTION;

-- Keep fk_session_last_turn DEFERRABLE INITIALLY DEFERRED from V7:
-- turns and their owning sessions must be removed in the same transaction.
