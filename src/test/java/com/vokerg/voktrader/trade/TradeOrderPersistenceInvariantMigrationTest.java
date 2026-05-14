package com.vokerg.voktrader.trade;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class TradeOrderPersistenceInvariantMigrationTest {
    @Test
    void migrationInstallsDatabaseGuardAgainstLiveBackedPaperExitOrders() throws Exception {
        String sql = Files.readString(Path.of(
                "src/main/resources/db/migration/V17__forbid_paper_exit_for_live_backed_trade.sql"
        ));

        assertThat(sql)
                .contains("trg_forbid_paper_exit_for_live_backed_trade")
                .contains("BEFORE INSERT OR UPDATE ON trade_orders")
                .contains("NEW.phase = 'EXIT' AND NEW.venue = 'PAPER_SIM'")
                .contains("entry.venue = 'POLYMARKET'")
                .contains("entry.remote_order_id IS NOT NULL")
                .contains("entry.exchange_order_id IS NOT NULL")
                .contains("RAISE EXCEPTION");
    }
}
