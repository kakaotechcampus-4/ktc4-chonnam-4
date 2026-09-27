CREATE TABLE classroom (
    class_id      UUID PRIMARY KEY,
    instructor_id VARCHAR(36) NOT NULL,
    name          VARCHAR(100) NOT NULL,
    status        VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'
                  CHECK (status IN ('ACTIVE', 'ARCHIVED'))
);

CREATE TABLE child (
    child_id     UUID PRIMARY KEY,
    class_id     UUID NOT NULL REFERENCES classroom (class_id),
    display_name VARCHAR(100) NOT NULL,
    status       VARCHAR(20) NOT NULL DEFAULT 'ACTIVE'
                 CHECK (status IN ('ACTIVE', 'PAUSED', 'REMOVED'))
);

CREATE INDEX idx_child_class_id ON child (class_id);
