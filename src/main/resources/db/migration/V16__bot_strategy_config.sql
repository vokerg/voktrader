ALTER TABLE IF EXISTS bot_configs
    ADD COLUMN IF NOT EXISTS strategy_config_id VARCHAR(255);
