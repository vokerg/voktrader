package com.vokerg.voktrader.market;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;

@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class MarketSchemaRepair implements ApplicationRunner {

    private final DataSource dataSource;
    private final JdbcTemplate jdbcTemplate;

    public MarketSchemaRepair(DataSource dataSource, JdbcTemplate jdbcTemplate) {
        this.dataSource = dataSource;
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            if (!connection.getMetaData().getURL().startsWith("jdbc:h2:")) {
                return;
            }
        }

        Long nextId = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(id), 0) + 1 FROM markets",
                Long.class);

        if (nextId == null) {
            return;
        }

        jdbcTemplate.execute("ALTER TABLE IF EXISTS markets ALTER COLUMN id RESTART WITH " + nextId);
        jdbcTemplate.execute("ALTER TABLE IF EXISTS trades ALTER COLUMN status VARCHAR(32)");
        jdbcTemplate.execute("ALTER TABLE IF EXISTS trades ALTER COLUMN mode VARCHAR(32)");
        jdbcTemplate.execute("ALTER TABLE IF EXISTS trades ALTER COLUMN decision_side VARCHAR(16)");
        jdbcTemplate.execute("ALTER TABLE IF EXISTS trades ALTER COLUMN entry_order_type VARCHAR(32)");
        jdbcTemplate.execute("ALTER TABLE IF EXISTS trade_orders ALTER COLUMN phase VARCHAR(32)");
        jdbcTemplate.execute("ALTER TABLE IF EXISTS trade_orders ALTER COLUMN mode VARCHAR(32)");
        jdbcTemplate.execute("ALTER TABLE IF EXISTS trade_orders ALTER COLUMN venue VARCHAR(32)");
        jdbcTemplate.execute("ALTER TABLE IF EXISTS trade_orders ALTER COLUMN side VARCHAR(16)");
        jdbcTemplate.execute("ALTER TABLE IF EXISTS trade_orders ALTER COLUMN order_type VARCHAR(32)");
        jdbcTemplate.execute("ALTER TABLE IF EXISTS trade_orders ALTER COLUMN status VARCHAR(32)");
        jdbcTemplate.execute("ALTER TABLE IF EXISTS trade_risk_checks ALTER COLUMN mode VARCHAR(32)");
        jdbcTemplate.execute("ALTER TABLE IF EXISTS trade_risk_checks ALTER COLUMN severity VARCHAR(16)");
        jdbcTemplate.execute("ALTER TABLE IF EXISTS trade_fills ALTER COLUMN venue VARCHAR(32)");
        jdbcTemplate.execute("ALTER TABLE IF EXISTS trade_fills ALTER COLUMN side VARCHAR(16)");
        log.info("Repaired H2 markets identity sequence: nextId={}", nextId);
    }
}
