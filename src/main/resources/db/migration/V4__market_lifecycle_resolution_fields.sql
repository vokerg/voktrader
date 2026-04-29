ALTER TABLE markets ADD COLUMN IF NOT EXISTS tracking_status VARCHAR(50);
ALTER TABLE markets ADD COLUMN IF NOT EXISTS resolution_status VARCHAR(50);
ALTER TABLE markets ADD COLUMN IF NOT EXISTS last_resolution_check_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE markets ADD COLUMN IF NOT EXISTS resolution_attempts INT;

UPDATE markets
SET tracking_status = CASE WHEN accepting_orders = TRUE THEN 'TRACKING' ELSE 'STOPPED' END
WHERE tracking_status IS NULL;

UPDATE markets
SET resolution_status = CASE WHEN resolved = TRUE THEN 'RESOLVED' ELSE 'UNRESOLVED' END
WHERE resolution_status IS NULL;

UPDATE markets
SET resolution_attempts = 0
WHERE resolution_attempts IS NULL;
