CREATE TABLE control_plane_audit_events (
    id UUID PRIMARY KEY,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
    request_method VARCHAR(16) NOT NULL,
    request_path VARCHAR(512) NOT NULL,
    principal_name VARCHAR(128) NOT NULL,
    authority_name VARCHAR(64) NOT NULL,
    remote_address VARCHAR(128) NOT NULL,
    confirmation_present BOOLEAN NOT NULL
);

CREATE INDEX idx_control_plane_audit_events_occurred_at
    ON control_plane_audit_events (occurred_at);

COMMENT ON TABLE control_plane_audit_events IS
    'Append-only audit attempts for live control-plane mutations; application mappings expose no update/delete path.';
