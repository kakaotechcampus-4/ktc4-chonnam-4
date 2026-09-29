CREATE TABLE learning_goal (
    goal_id UUID PRIMARY KEY,
    child_id UUID NOT NULL REFERENCES child (child_id),
    instructor_id VARCHAR(36) NOT NULL,
    parent_goal_id UUID REFERENCES learning_goal (goal_id),
    title VARCHAR(200) NOT NULL,
    situation_type VARCHAR(30),
    characters_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    required_elements_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    forbidden_expressions_json JSONB NOT NULL DEFAULT '[]'::jsonb,
    content_hash VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    UNIQUE (goal_id, child_id)
);

CREATE INDEX idx_learning_goal_child_created ON learning_goal (child_id, created_at DESC);

ALTER TABLE activity ADD CONSTRAINT fk_activity_goal_child
    FOREIGN KEY (goal_id, child_id) REFERENCES learning_goal (goal_id, child_id);
