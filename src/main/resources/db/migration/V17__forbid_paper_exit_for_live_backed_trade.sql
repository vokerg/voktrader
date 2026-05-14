CREATE OR REPLACE FUNCTION forbid_paper_exit_for_live_backed_trade()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    has_live_entry boolean;
    has_paper_exit boolean;
BEGIN
    SELECT EXISTS (
        SELECT 1
        FROM trades t
        LEFT JOIN trade_orders entry ON entry.trade_id = t.id
            AND entry.phase = 'ENTRY'
        WHERE t.id = NEW.trade_id
          AND (
                t.mode IN ('LIVE_TINY', 'LIVE')
                OR entry.mode IN ('LIVE_TINY', 'LIVE')
                OR entry.venue = 'POLYMARKET'
                OR entry.remote_order_id IS NOT NULL
                OR entry.exchange_order_id IS NOT NULL
          )
    ) INTO has_live_entry;

    IF NEW.phase = 'EXIT' AND NEW.venue = 'PAPER_SIM' AND has_live_entry THEN
        RAISE EXCEPTION
            'PAPER_SIM EXIT order forbidden for live-backed trade_id=% order_id=%',
            NEW.trade_id,
            NEW.id
            USING ERRCODE = 'check_violation';
    END IF;

    IF NEW.phase = 'ENTRY'
       AND (
            NEW.mode IN ('LIVE_TINY', 'LIVE')
            OR NEW.venue = 'POLYMARKET'
            OR NEW.remote_order_id IS NOT NULL
            OR NEW.exchange_order_id IS NOT NULL
       ) THEN
        SELECT EXISTS (
            SELECT 1
            FROM trade_orders exit_order
            WHERE exit_order.trade_id = NEW.trade_id
              AND exit_order.phase = 'EXIT'
              AND exit_order.venue = 'PAPER_SIM'
        ) INTO has_paper_exit;

        IF has_paper_exit THEN
            RAISE EXCEPTION
                'live-backed ENTRY order forbidden because trade_id=% already has PAPER_SIM EXIT',
                NEW.trade_id
                USING ERRCODE = 'check_violation';
        END IF;
    END IF;

    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_forbid_paper_exit_for_live_backed_trade ON trade_orders;

CREATE TRIGGER trg_forbid_paper_exit_for_live_backed_trade
BEFORE INSERT OR UPDATE ON trade_orders
FOR EACH ROW
EXECUTE FUNCTION forbid_paper_exit_for_live_backed_trade();

CREATE OR REPLACE FUNCTION forbid_paper_closed_live_backed_trade()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    has_live_entry boolean;
    has_paper_exit boolean;
BEGIN
    IF NEW.status <> 'CLOSED' THEN
        RETURN NEW;
    END IF;

    SELECT EXISTS (
        SELECT 1
        FROM trade_orders entry
        WHERE entry.trade_id = NEW.id
          AND entry.phase = 'ENTRY'
          AND (
                NEW.mode IN ('LIVE_TINY', 'LIVE')
                OR entry.mode IN ('LIVE_TINY', 'LIVE')
                OR entry.venue = 'POLYMARKET'
                OR entry.remote_order_id IS NOT NULL
                OR entry.exchange_order_id IS NOT NULL
          )
    ) INTO has_live_entry;

    SELECT EXISTS (
        SELECT 1
        FROM trade_orders exit_order
        WHERE exit_order.trade_id = NEW.id
          AND exit_order.phase = 'EXIT'
          AND exit_order.venue = 'PAPER_SIM'
    ) INTO has_paper_exit;

    IF has_live_entry AND has_paper_exit THEN
        RAISE EXCEPTION
            'CLOSED trade forbidden for live-backed trade_id=% with PAPER_SIM EXIT',
            NEW.id
            USING ERRCODE = 'check_violation';
    END IF;

    RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_forbid_paper_closed_live_backed_trade ON trades;

CREATE TRIGGER trg_forbid_paper_closed_live_backed_trade
BEFORE INSERT OR UPDATE ON trades
FOR EACH ROW
EXECUTE FUNCTION forbid_paper_closed_live_backed_trade();
